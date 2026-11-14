package com.iptvmamba.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors

/** Device code returned by Trakt when the user starts the "connect" flow. */
class TraktDevice(val deviceCode: String, val userCode: String, val url: String, val expiresIn: Int, val interval: Int)

/**
 * Optional Trakt.tv scrobbling. Trakt needs an OAuth app: the user (or the developer in
 * secrets.properties) enters a Client ID and Client Secret under Settings > API keys.
 * The access/refresh tokens never leave the device and are stored encrypted when possible.
 *
 * Flow: device code (trakt.tv/activate) -> access token -> /scrobble/start, pause, stop.
 * Progress is matched through the TMDB id of the title (no TMDB key = no scrobbling).
 */
object Trakt {
    private const val API = "https://api.trakt.tv"
    private const val REDIRECT = "urn:ietf:wg:oauth:2.0:oob"
    private const val UA = "iptvmamba/1.7 (Android)"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    // One thread for all scrobble work: start/pause/stop stay in order and the session vars are not raced.
    private val scope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())

    var connected by mutableStateOf(false)
        private set
    var username by mutableStateOf("")
        private set
    var status by mutableStateOf("")
        private set

    private var access = ""
    private var refresh = ""
    private var expiresAt = 0L
    private var inited = false

    // Current scrobble session (dedupe + throttle so Trakt is not spammed on buffering).
    private var activeKey = ""
    private var activeReq: PlayReq? = null
    private var started = false
    private var startedAt = 0L
    private var lastCall = 0L
    private var lastPct = 0f

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("ipvio_trakt", Context.MODE_PRIVATE)

    @Synchronized
    fun init(c: Context) {
        if (inited) return
        inited = true
        val p = prefs(c)
        access = readSecret(p.getString("access", null))
        refresh = readSecret(p.getString("refresh", null))
        expiresAt = p.getLong("expires", 0L)
        username = p.getString("user", "").orEmpty()
        connected = access.isNotBlank()
    }

    fun enabled(c: Context): Boolean {
        init(c)
        return connected && Config.TRAKT_ID.isNotBlank() && Config.TRAKT_SECRET.isNotBlank()
    }

    private fun readSecret(s: String?): String =
        if (s.isNullOrBlank()) "" else (Secrets.decrypt(s) ?: s)

    private fun writeSecret(v: String): String = Secrets.encrypt(v) ?: v

    @Synchronized
    private fun save(c: Context) {
        prefs(c).edit()
            .putString("access", if (access.isBlank()) null else writeSecret(access))
            .putString("refresh", if (refresh.isBlank()) null else writeSecret(refresh))
            .putLong("expires", expiresAt)
            .putString("user", username)
            .apply()
        connected = access.isNotBlank()
    }

    fun logout(c: Context) {
        val tok = access
        scope.launch {
            runCatching {
                if (tok.isNotBlank()) call(
                    "/oauth/revoke",
                    JSONObject().put("token", tok).put("client_id", Config.TRAKT_ID).put("client_secret", Config.TRAKT_SECRET)
                )
            }
        }
        access = ""; refresh = ""; expiresAt = 0L; username = ""
        activeKey = ""; activeReq = null; started = false
        save(c)
        status = "Trakt getrennt"
    }

    // ------------------------------------------------------------------ HTTP

    private suspend fun call(path: String, body: JSONObject?, authed: Boolean = false): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            try {
                val b = Request.Builder().url(API + path)
                    .header("User-Agent", UA)
                    .header("Content-Type", "application/json")
                    .header("trakt-api-version", "2")
                if (Config.TRAKT_ID.isNotBlank()) b.header("trakt-api-key", Config.TRAKT_ID)
                if (authed && access.isNotBlank()) b.header("Authorization", "Bearer " + access)
                if (body != null) b.post(body.toString().toRequestBody(JSON)) else b.get()
                Net.base.newCall(b.build()).execute().use { r -> r.code to r.body?.string().orEmpty() }
            } catch (e: Exception) {
                -1 to (e.message ?: e.javaClass.simpleName)
            }
        }

    private fun errorText(code: Int, body: String): String = when {
        code == -1 -> "Keine Verbindung: " + body.take(80)
        code == 401 -> "Client-ID oder Secret ungültig"
        code == 420 || code == 429 -> "Zu viele Anfragen – kurz warten"
        body.isNotBlank() -> "Fehler $code: " + body.take(120)
        else -> "Fehler $code"
    }

    // ------------------------------------------------------------------ Device login

    suspend fun requestDevice(c: Context): Result<TraktDevice> {
        Config.init(c)
        if (Config.TRAKT_ID.isBlank() || Config.TRAKT_SECRET.isBlank())
            return Result.failure(IllegalStateException("Trakt Client-ID und Client-Secret fehlen"))
        val (code, body) = call("/oauth/device/code", JSONObject().put("client_id", Config.TRAKT_ID))
        if (code != 200) return Result.failure(IllegalStateException(errorText(code, body)))
        val d = runCatching {
            val o = JSONObject(body)
            TraktDevice(
                o.getString("device_code"), o.getString("user_code"),
                o.optString("verification_url", "https://trakt.tv/activate"),
                o.optInt("expires_in", 600).coerceAtLeast(60),
                o.optInt("interval", 5).coerceAtLeast(3)
            )
        }.getOrElse { return Result.failure(IllegalStateException("Antwort von Trakt unlesbar")) }
        return Result.success(d)
    }

    /** Polls until the user entered the code on trakt.tv/activate. Blocking for the caller (run in a coroutine). */
    suspend fun awaitDevice(c: Context, d: TraktDevice, onStatus: (String) -> Unit): Boolean {
        val deadline = System.currentTimeMillis() + d.expiresIn * 1000L
        var interval = d.interval
        onStatus("Warte auf Bestätigung auf trakt.tv/activate …")
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val (code, body) = call(
                "/oauth/device/token",
                JSONObject().put("code", d.deviceCode)
                    .put("client_id", Config.TRAKT_ID)
                    .put("client_secret", Config.TRAKT_SECRET)
            )
            when (code) {
                200 -> {
                    saveTokens(c, JSONObject(body))
                    onStatus("Verbunden mit Trakt")
                    return true
                }
                400 -> Unit // pending
                404 -> { onStatus("Code ungültig – neu verbinden"); return false }
                409 -> { onStatus("Code wurde bereits benutzt"); return false }
                410 -> { onStatus("Code abgelaufen – neu verbinden"); return false }
                418 -> { onStatus("Zugriff abgelehnt"); return false }
                420, 429 -> interval = (interval + 2).coerceAtMost(20)
                -1 -> Unit // network hiccup, keep polling
                else -> onStatus(errorText(code, body))
            }
        }
        onStatus("Zeit abgelaufen – Code erneut anfordern")
        return false
    }

    @Synchronized
    private fun saveTokens(c: Context, o: JSONObject) {
        access = o.optString("access_token")
        refresh = o.optString("refresh_token")
        val created = o.optLong("created_at", System.currentTimeMillis() / 1000L)
        expiresAt = (created + o.optLong("expires_in", 7_776_000L)) * 1000L
        save(c)
        scope.launch { fetchUser(c) }
    }

    private suspend fun fetchUser(c: Context) {
        val (code, body) = call("/users/me", null, authed = true)
        if (code == 200) runCatching {
            username = JSONObject(body).optString("username").ifBlank { "Trakt" }
            save(c)
            status = "Verbunden als " + username
        }
    }

    private suspend fun ensureToken(c: Context): Boolean {
        init(c)
        if (access.isBlank()) return false
        if (expiresAt > System.currentTimeMillis() + 60_000L) return true
        if (refresh.isBlank()) return false
        val (code, body) = call(
            "/oauth/token",
            JSONObject().put("refresh_token", refresh)
                .put("client_id", Config.TRAKT_ID)
                .put("client_secret", Config.TRAKT_SECRET)
                .put("redirect_uri", REDIRECT)
                .put("grant_type", "refresh_token")
        )
        if (code == 200) {
            runCatching {
                val o = JSONObject(body)
                access = o.optString("access_token", access)
                refresh = o.optString("refresh_token", refresh).ifBlank { refresh }
                val created = o.optLong("created_at", System.currentTimeMillis() / 1000L)
                expiresAt = (created + o.optLong("expires_in", 7_776_000L)) * 1000L
                save(c)
            }
            return access.isNotBlank()
        }
        if (code in listOf(400, 401, 403)) {
            // Refresh no longer possible: the user has to connect again.
            access = ""; refresh = ""; expiresAt = 0L
            save(c)
            status = "Trakt-Anmeldung abgelaufen"
        }
        return false
    }

    // ------------------------------------------------------------------ Scrobbling

    private fun keyOf(r: PlayReq): String = r.itemId + "|" + r.season + "|" + r.episode

    private fun bodyOf(r: PlayReq, pct: Float): JSONObject? {
        if (r.tmdbId <= 0) return null
        val ids = JSONObject().put("tmdb", r.tmdbId)
        val p = pct.coerceIn(0f, 100f).toDouble()
        return if (r.season > 0 || r.episode > 0) JSONObject()
            .put("show", JSONObject().put("ids", ids))
            .put("episode", JSONObject().put("season", r.season.coerceAtLeast(1)).put("number", r.episode.coerceAtLeast(1)))
            .put("progress", p)
        else JSONObject()
            .put("movie", JSONObject().put("ids", ids))
            .put("progress", p)
    }

    private suspend fun send(c: Context, r: PlayReq, action: String, pct: Float) {
        val body = bodyOf(r, pct) ?: return
        val (code, resp) = call("/scrobble/$action", body, authed = true)
        if (code !in 200..299 && code != 409 && code != -1) status = "Trakt: " + errorText(code, resp)
    }

    /** Called when playing starts or pauses. Progress is percent (0..100). */
    fun onPlaying(c: Context, r: PlayReq, playing: Boolean, pct: Float) {
        if (r.live || r.archive != null || r.tmdbId <= 0) return
        if (!enabled(c)) return
        scope.launch {
            if (!ensureToken(c)) return@launch
            val now = System.currentTimeMillis()
            val key = keyOf(r)
            if (playing) {
                if (key != activeKey) {
                    // New title/episode: close the previous session first.
                    activeReq?.let { old -> if (started) send(c, old, "stop", lastPct) }
                    activeKey = key
                    started = false
                }
                activeReq = r
                val due = !started || now - lastCall > 30_000L
                if (due) {
                    send(c, r, "start", pct)
                    started = true
                    startedAt = now
                    lastCall = now
                    lastPct = pct
                }
            } else if (started && now - startedAt > 4_000L && now - lastCall > 10_000L) {
                send(c, r, "pause", pct)
                started = false
                lastCall = now
                lastPct = pct
            }
        }
    }

    /** Called when playback really ends or the player is left. Trakt marks >= 80 % as watched. */
    fun stop(c: Context, r: PlayReq, pct: Float) {
        if (r.live || r.archive != null || r.tmdbId <= 0) return
        if (!enabled(c)) return
        val key = keyOf(r)
        if (activeKey != key && !started) return
        scope.launch {
            if (!ensureToken(c)) return@launch
            send(c, r, "stop", if (pct <= 0f) lastPct else pct)
            if (activeKey == key) { activeKey = ""; activeReq = null; started = false }
        }
    }
}
