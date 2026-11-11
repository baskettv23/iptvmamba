package com.iptvmamba.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * API keys for the optional film information services. No key is shipped with the app: every user enters their own
 * (Settings > API keys). Developers can additionally put keys into "secrets.properties" (not in Git); those are only
 * used as a fallback when nothing was entered in the app.
 *
 *   TMDB   https://www.themoviedb.org/settings/api  – posters, descriptions, cast, trailers, automatic genres
 *   OMDb   https://www.omdbapi.com/apikey.aspx      – IMDb and Rotten Tomatoes ratings
 *
 * Intro/credits times come from TheIntroDB, which needs no key for reading.
 * Without keys the app works normally and only hides the extra information.
 */
object Config {
    @Volatile var TMDB_KEY: String = clean(BuildConfig.TMDB_KEY)
        private set
    @Volatile var OMDB_KEY: String = clean(BuildConfig.OMDB_KEY)
        private set
    /** Real-Debrid API token (for torrent streams via Torrentio). Optional. */
    @Volatile var RD_KEY: String = ""
        private set
    /** TorBox API token (alternative debrid service). Optional. */
    @Volatile var TB_KEY: String = ""
        private set
    /** Optional Stremio/Nuvio add-on base URL(s), comma separated. */
    @Volatile var ADDON_URL: String = ""
        private set
    /** true if the key was entered in the app (not from secrets.properties). */
    var tmdbOwn by androidx.compose.runtime.mutableStateOf(false)
        private set
    var omdbOwn by androidx.compose.runtime.mutableStateOf(false)
        private set
    var rdOwn by androidx.compose.runtime.mutableStateOf(false)
        private set
    private var inited = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("ipvio_keys", Context.MODE_PRIVATE)

    /** Letters and digits only (a key must never smuggle special characters into addresses). */
    fun clean(s: String): String = s.trim().filter { it.isLetterOrDigit() }.take(64)

    /** Real-Debrid tokens may also contain "-" and "_" (both URL-safe). */
    fun cleanToken(s: String): String = s.trim().filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(128)

    /** Add-on URLs: keep the text, only remove control characters. Multiple URLs may be comma separated. */
    fun cleanUrl(s: String): String = s.trim().replace("\n", ",").filter { it.code >= 32 }.take(512)

    @Synchronized
    fun init(c: Context) {
        if (inited) return
        inited = true
        val p = prefs(c)
        val t = clean(p.getString("tmdb", "").orEmpty())
        val o = clean(p.getString("omdb", "").orEmpty())
        val r = cleanToken(p.getString("rd", "").orEmpty())
        val tb = cleanToken(p.getString("tb", "").orEmpty())
        val addon = cleanUrl(p.getString("addon", "").orEmpty())
        if (t.isNotEmpty()) { TMDB_KEY = t; tmdbOwn = true }
        if (o.isNotEmpty()) { OMDB_KEY = o; omdbOwn = true }
        if (r.isNotEmpty()) { RD_KEY = r; rdOwn = true }
        if (tb.isNotEmpty()) TB_KEY = tb
        ADDON_URL = addon
    }

    /** Stores the entered keys; empty = remove key (then secrets.properties applies again if present). */
    fun save(c: Context, tmdb: String, omdb: String) {
        val t = clean(tmdb); val o = clean(omdb)
        prefs(c).edit().putString("tmdb", t).putString("omdb", o).apply()
        TMDB_KEY = t.ifEmpty { clean(BuildConfig.TMDB_KEY) }; tmdbOwn = t.isNotEmpty()
        OMDB_KEY = o.ifEmpty { clean(BuildConfig.OMDB_KEY) }; omdbOwn = o.isNotEmpty()
        inited = true
    }

    /** Entered key (for the input field); keys from secrets.properties are not shown. */
    fun ownTmdb(c: Context): String = clean(prefs(c).getString("tmdb", "").orEmpty())
    fun ownOmdb(c: Context): String = clean(prefs(c).getString("omdb", "").orEmpty())
    fun ownRd(c: Context): String = cleanToken(prefs(c).getString("rd", "").orEmpty())
    fun ownTb(c: Context): String = cleanToken(prefs(c).getString("tb", "").orEmpty())
    fun ownAddon(c: Context): String = cleanUrl(prefs(c).getString("addon", "").orEmpty())

    /** Configured Stremio/Nuvio add-on base URLs (comma separated, empty entries removed). */
    fun addons(c: Context): List<String> =
        ownAddon(c).split(',').map { it.trim() }.filter { it.isNotBlank() }

    /** Stores/removes the Real-Debrid token. */
    fun saveRd(c: Context, rd: String) {
        val r = cleanToken(rd)
        prefs(c).edit().putString("rd", r).apply()
        RD_KEY = r; rdOwn = r.isNotEmpty()
        inited = true
    }

    /** Stores/removes the TorBox token. */
    fun saveTb(c: Context, tb: String) {
        val t = cleanToken(tb)
        prefs(c).edit().putString("tb", t).apply()
        TB_KEY = t
        inited = true
    }

    /** Stores/removes the optional Stremio/Nuvio add-on URL(s). */
    fun saveAddon(c: Context, url: String) {
        val u = cleanUrl(url)
        prefs(c).edit().putString("addon", u).apply()
        ADDON_URL = u
        inited = true
    }
}

/** Checks a key with a small request to the service. */
object KeyCheck {
    /** null = OK, otherwise error text (German, gets translated). */
    suspend fun tmdb(key: String): String? = check("https://api.themoviedb.org/3/configuration?api_key=" + Config.clean(key)) { code, body ->
        if (code == 200) null else if (code == 401) "Schlüssel ungültig" else "Fehler $code"
    }

    suspend fun omdb(key: String): String? = check("https://www.omdbapi.com/?apikey=" + Config.clean(key) + "&i=tt0111161") { code, body ->
        if (code == 200 && body.contains("\"Response\":\"True\"")) null
        else if (code == 401 || body.contains("Invalid API key")) "Schlüssel ungültig" else "Fehler $code"
    }

    private suspend fun check(url: String, eval: (Int, String) -> String?): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                Net.base.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r -> eval(r.code, r.body?.string().orEmpty()) }
            } catch (e: java.net.UnknownHostException) {
                "Server nicht gefunden (DNS)"
            } catch (e: javax.net.ssl.SSLException) {
                "Sichere Verbindung fehlgeschlagen. Datum und Uhrzeit des Geräts prüfen"
            } catch (e: java.net.SocketTimeoutException) {
                "Zeitüberschreitung, Server antwortet nicht"
            } catch (e: Exception) {
                "Keine Verbindung: " + e.javaClass.simpleName
            }
        }
}
