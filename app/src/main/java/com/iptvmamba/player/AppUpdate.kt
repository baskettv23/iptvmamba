package com.iptvmamba.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

data class UpdateInfo(val code: Long, val name: String, val apkUrl: String, val notes: String)

/**
 * Update from within the app.
 * Default: the latest release on GitHub (releases of the project). The app checks once a day, shows
 * "Update available" in the bell at the top right and installs on a button press. Android always asks once
 * (for apps outside the Play Store it does not work entirely without confirmation).
 *
 * A custom address (Settings > About) still works: either a GitHub API address
 * (https://api.github.com/repos/NAME/REPO/releases/latest) or a small JSON file
 * {"versionCode": 2, "versionName": "1.1", "apk": "ipvio.apk", "notes": "What's new"}.
 *
 * So that history, favorites and settings are preserved on an update, every new APK must be signed with the same
 * key (see docs/RELEASE.md). Otherwise Android rejects the installation.
 */
object AppUpdate {
    /** Built-in update address: the latest release of the project on GitHub. */
    const val DEFAULT_URL = "https://api.github.com/repos/Emrodev/iptvmamba-Stream/releases/latest"

    /** Found update (for the bell), null = none known. */
    var available by mutableStateOf<UpdateInfo?>(null)
        private set

    /** "Später" tapped in the start dialog: stays hidden until the app is started again. */
    var promptDismissed by mutableStateOf(false)

    private fun prefs(c: Context) = c.getSharedPreferences("ipvio_upd", Context.MODE_PRIVATE)
    fun url(c: Context) = prefs(c).getString("url", "").orEmpty().ifBlank { DEFAULT_URL }
    fun setUrl(c: Context, v: String) = prefs(c).edit().putString("url", v.trim()).apply()

    /** Automatic check at start (default: on). */
    fun auto(c: Context) = prefs(c).getBoolean("auto", true)
    fun setAuto(c: Context, on: Boolean) = prefs(c).edit().putBoolean("auto", on).apply()

    fun currentCode(c: Context): Long = runCatching {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrDefault(0L)

    fun currentName(c: Context): String = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: ""

    /** Returns null if the installed version is current. */
    suspend fun check(c: Context): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val u = url(c)
            if (u.isBlank()) error("Keine Update-Adresse eingetragen")
            val req = Request.Builder().url(u).header("User-Agent", "iptvmamba")
                .header("Accept", "application/vnd.github+json").build()
            Net.base.newCall(req).execute().use { r ->
                if (r.code == 404) error("Noch keine Veröffentlichung gefunden")
                if (!r.isSuccessful) error("Fehler ${r.code}")
                val j = JSONObject(r.body?.string().orEmpty())
                val res = if (j.has("tag_name")) fromGithub(c, j) else fromFile(c, u, j)
                prefs(c).edit().putLong("last", System.currentTimeMillis()).apply()
                available = res
                remember(c, res)
                res
            }
        }
    }

    private fun fromGithub(c: Context, j: JSONObject): UpdateInfo? {
        val tag = j.optString("tag_name")
        if (j.optBoolean("draft") || j.optBoolean("prerelease")) return null
        if (!UpdateLogic.isNewerName(currentName(c), tag)) return null
        val a = j.optJSONArray("assets")
        val assets = ArrayList<Pair<String, String>>()
        if (a != null) for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            assets.add(o.optString("name") to o.optString("browser_download_url"))
        }
        val apk = UpdateLogic.pickApk(assets) ?: return null
        val notes = j.optString("body").lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.take(4).joinToString(" · ")
        return UpdateInfo(0L, tag.trimStart('v', 'V'), apk, notes.take(300))
    }

    private fun fromFile(c: Context, u: String, j: JSONObject): UpdateInfo? {
        val code = j.optLong("versionCode", 0L)
        val apk = j.optString("apk")
        if (code <= 0L || apk.isBlank()) error("Update-Datei unvollständig")
        if (!UpdateLogic.isNewer(currentCode(c), code)) return null
        return UpdateInfo(code, j.optString("versionName", code.toString()), UpdateLogic.resolve(u, apk), j.optString("notes"))
    }

    /** At start: check silently at most once a day. Errors (no network, GitHub unreachable) stay invisible. */
    suspend fun autoCheck(c: Context) {
        if (!auto(c)) return
        if (!UpdateLogic.due(prefs(c).getLong("last", 0L), System.currentTimeMillis())) {
            // Remember a known update so the bell keeps showing it after a restart
            restore(c); return
        }
        check(c)
    }

    /**
     * At every app start (not only once a day): ask for a new version so the start dialog can offer it right away.
     * At most every 10 minutes, so quick restarts do not use up GitHub's request limit. Errors stay invisible.
     */
    suspend fun startCheck(c: Context) {
        if (!auto(c)) return
        val now = System.currentTimeMillis()
        val last = prefs(c).getLong("last_start", 0L)
        if (now >= last && now - last < 10 * 60_000L) { restore(c); return }
        prefs(c).edit().putLong("last_start", now).apply()
        check(c)
    }

    private fun remember(c: Context, u: UpdateInfo?) {
        val e = prefs(c).edit()
        if (u == null) e.remove("known") else e.putString("known", JSONObject().put("c", u.code).put("n", u.name).put("a", u.apkUrl).put("t", u.notes).toString())
        e.apply()
    }

    private fun restore(c: Context) {
        val s = prefs(c).getString("known", null) ?: return
        val o = runCatching { JSONObject(s) }.getOrNull() ?: return
        val u = UpdateInfo(o.optLong("c"), o.optString("n"), o.optString("a"), o.optString("t"))
        // Installed in the meantime? Then do not show it any more
        val newer = if (u.code > 0) UpdateLogic.isNewer(currentCode(c), u.code) else UpdateLogic.isNewerName(currentName(c), u.name)
        if (newer) available = u else remember(c, null)
    }

    /** Download and open the installer; [status] gets progress and result as text. */
    suspend fun downloadAndInstall(c: Context, u: UpdateInfo, status: (String) -> Unit) {
        download(c, u) { p -> status(if (p >= 0) "Lade … $p %" else "Lade …") }
            .onSuccess { f -> status(if (install(c, f)) "Installation gestartet" else "Erlaube der App einmal „Apps installieren“ und tippe dann erneut.") }
            .onFailure { status(it.message ?: "Fehler") }
    }

    /** Downloads the APK into the cache; [onProgress] delivers percent (0..100) or -1 for unknown size. */
    suspend fun download(c: Context, info: UpdateInfo, onProgress: (Int) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(c.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val out = File(dir, "ipvio-update.apk")
            val client = Net.withTimeouts(15, 60)
            client.newCall(Request.Builder().url(info.apkUrl).header("User-Agent", "iptvmamba").build()).execute().use { r ->
                if (!r.isSuccessful) error("Fehler ${r.code}")
                val body = r.body ?: error("Leere Antwort")
                val total = body.contentLength()
                var done = 0L
                var last = -2
                body.byteStream().use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n); done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else -1
                            if (pct != last) { last = pct; onProgress(pct) }
                        }
                    }
                }
            }
            out
        }
    }

    /** Opens the system installer. If the "Apps from unknown sources" permission is missing, its setting opens instead. */
    fun install(c: Context, apk: File): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !c.packageManager.canRequestPackageInstalls()) {
            runCatching {
                c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return false
        }
        val uri = FileProvider.getUriForFile(c, c.packageName + ".fileprovider", apk)
        return runCatching {
            c.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
    }
}
