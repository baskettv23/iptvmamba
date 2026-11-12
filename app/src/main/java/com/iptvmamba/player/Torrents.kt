package com.iptvmamba.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import kotlin.math.abs

/** One stream result from a Stremio add-on, optionally already resolved to a direct debrid link. */
data class StreamLink(
    val name: String,
    val title: String,
    val quality: String?,
    val size: String?,
    val seeders: Int?,
    val provider: String?,
    val url: String?,
    val infoHash: String?,
    val fileIdx: Int,
    val filename: String?,
    val source: String? = null
)

/** What to look up: a movie (season/episode = 0) or one episode of a series. */
data class TorrentReq(
    val item: Item,
    val imdbId: String,
    val title: String,
    val season: Int,
    val episode: Int
)

/** Torrentio (Stremio add-on) stream search. A debrid key in [config] returns ready-to-play links. */
object Torrentio {
    suspend fun streams(imdbId: String, season: Int, episode: Int, config: String): List<StreamLink> = withContext(Dispatchers.IO) {
        val series = season > 0 && episode > 0
        val id = if (series) "$imdbId:$season:$episode" else imdbId
        val type = if (series) "series" else "movie"
        val req = Request.Builder()
            .url("https://torrentio.strem.fun/${config}stream/$type/$id.json")
            .header("User-Agent", "iptvmamba")
            .build()
        val body = Net.base.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        parseStreams(body, "Torrentio")
    }
}

/** Any Stremio add-on (Nuvio/Comet/MediaFusion/AIOStreams…): reads /stream/{type}/{id}.json. */
object StremioAddon {
    /** Accepts a manifest URL, a base URL or stremio://; returns a normalized base URL or null. */
    fun normalize(base: String): String? {
        var b = base.trim()
        if (b.isEmpty()) return null
        if (b.startsWith("stremio://")) b = "https://" + b.removePrefix("stremio://")
        if (!b.startsWith("http://") && !b.startsWith("https://")) return null
        b = b.substringBefore("manifest.json").trimEnd('/')
        if (b.isEmpty()) return null
        return "$b/"
    }

    fun label(base: String): String = runCatching {
        URI(normalize(base).orEmpty()).host?.removePrefix("www.").orEmpty().ifBlank { "Stremio" }
    }.getOrDefault("Stremio")

    suspend fun streams(base: String, type: String, id: String, source: String): List<StreamLink> = withContext(Dispatchers.IO) {
        val root = normalize(base) ?: throw java.io.IOException("Ungültige Addon-URL")
        val req = Request.Builder()
            .url(root + "stream/$type/$id.json")
            .header("User-Agent", "iptvmamba")
            .build()
        val body = Net.base.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        parseStreams(body, source)
    }
}

/** Parses the "streams" array of a Stremio add-on response. */
private fun parseStreams(body: String, source: String): List<StreamLink> {
    val arr = runCatching { JSONObject(body).optJSONArray("streams") }.getOrNull() ?: return emptyList()
    val out = ArrayList<StreamLink>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val hint = o.optJSONObject("behaviorHints")
        val name = o.optString("name", "").replace("\n", " ").trim()
        val title = o.optString("title", "").replace("\n", " ").trim()
        val url = o.optString("url", "").ifBlank { null }
        val hash = o.optString("infoHash", "").ifBlank { null }
        if (url == null && hash == null) continue
        val blob = "$name $title"
        val quality = Regex("(2160p|1080p|720p|480p|4K|HDR|DV|CAM|TS)", RegexOption.IGNORE_CASE).find(blob)?.value?.let {
            when (it.lowercase()) { "4k" -> "2160p"; "hdr" -> "HDR"; "dv" -> "DV"; else -> it }
        }
        val size = Regex("([0-9]+(?:[.,][0-9]+)?\\s*[KMGT]B)", RegexOption.IGNORE_CASE).find(blob)?.value
        val seeders = Regex("👤\\s*([0-9]+)").find(blob)?.groupValues?.get(1)?.toIntOrNull()
        val provider = Regex("⚙️\\s*([^\\s]+)").find(blob)?.groupValues?.get(1)?.trim()
        val filename = hint?.optString("filename", "")?.ifBlank { null }
        out += StreamLink(name, title, quality, size, seeders, provider, url, hash, o.optInt("fileIdx", 0), filename, source)
    }
    return out
}

/** Spanish/Latino audio detection from the stream name/title/filename. */
object StreamLang {
    private val SPANISH = Regex("(?i)(?<![\\p{L}])(latino|lat|espanol|español|spanish|castellano|esp|spa|es)(?![\\p{L}])")
    private val MULTI = Regex("(?i)(?<![\\p{L}])(multi|dual|multilenguaje)(?![\\p{L}])")

    fun label(s: StreamLink): String? {
        val blob = listOfNotNull(s.name, s.title, s.filename, s.provider).joinToString(" ")
        val hit = SPANISH.find(blob)?.value?.lowercase()
        if (hit != null) return if (hit == "latino" || hit == "lat") "Latino" else "Español"
        if (MULTI.containsMatchIn(blob)) return "Multi"
        return null
    }

    /** 0 = Latino, 1 = Español, 2 = Multi, 3 = unknown. */
    fun rank(s: StreamLink): Int = when (label(s)) {
        "Latino" -> 0
        "Español" -> 1
        "Multi" -> 2
        else -> 3
    }
}

/** Minimal Real-Debrid client: turn a magnet/infoHash into a direct HTTPS link. */
object RealDebrid {
    private const val BASE = "https://api.real-debrid.com/rest/1.0"

    private fun req(path: String, key: String, form: RequestBody? = null): Request.Builder {
        val b = Request.Builder().url(BASE + path)
            .header("Authorization", "Bearer $key")
            .header("User-Agent", "iptvmamba")
        if (form != null) b.post(form)
        return b
    }

    private fun info(id: String, key: String): JSONObject =
        Net.base.newCall(req("/torrents/info/$id", key).build()).execute().use {
            if (!it.isSuccessful) throw java.io.IOException("info HTTP ${it.code}")
            JSONObject(it.body?.string().orEmpty())
        }

    private fun select(id: String, key: String, files: String) {
        Net.base.newCall(req("/torrents/selectFiles/$id", key, FormBody.Builder().add("files", files).build()).build())
            .execute().close()
    }

    private fun failed(status: String) =
        status == "error" || status == "magnet_error" || status == "virus" || status == "dead"

    suspend fun check(key: String): String? = withContext(Dispatchers.IO) {
        try {
            Net.base.newCall(req("/user", key).build()).execute().use {
                if (it.code == 200) null else if (it.code == 401) "Token ungültig" else "Fehler ${it.code}"
            }
        } catch (e: Exception) {
            "Keine Verbindung: " + e.javaClass.simpleName
        }
    }

    suspend fun resolve(key: String, infoHash: String, fileIdx: Int, filename: String?): String = withContext(Dispatchers.IO) {
        val magnet = "magnet:?xt=urn:btih:$infoHash" + (filename?.let { "&dn=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        val id = Net.base.newCall(req("/torrents/addMagnet", key, FormBody.Builder().add("magnet", magnet).build()).build())
            .execute().use {
                if (!it.isSuccessful) throw java.io.IOException("addMagnet HTTP ${it.code}")
                JSONObject(it.body?.string().orEmpty()).optString("id", "")
            }
        if (id.isBlank()) throw java.io.IOException("Keine Torrent-ID")

        // Select only the wanted file (episode) instead of a whole season pack, when the list is known.
        var fileId = -1
        var status = ""
        for (n in 0 until 30) {
            val o = info(id, key)
            status = o.optString("status", "")
            if (failed(status)) throw java.io.IOException("Torrent-Fehler: $status")
            val files = o.optJSONArray("files")
            if (files != null && files.length() > 0) {
                val idx = fileIdx.coerceIn(0, files.length() - 1)
                fileId = files.optJSONObject(idx)?.optInt("id", -1) ?: -1
                break
            }
            delay(1000)
        }
        val wanted = if (fileId > 0) fileId.toString() else "all"
        select(id, key, wanted)

        var links: List<String> = emptyList()
        for (n in 0 until 90) {
            val o = info(id, key)
            status = o.optString("status", "")
            o.optJSONArray("links")?.let { a -> links = (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }
            if (status == "downloaded" && links.isNotEmpty()) break
            if (failed(status)) throw java.io.IOException("Torrent-Fehler: $status")
            if (status == "waiting_files_selection") select(id, key, wanted)
            delay(2000)
        }
        if (links.isEmpty()) throw java.io.IOException("Torrent nicht bereit ($status)")
        val link = links.getOrElse(fileIdx.coerceIn(0, links.lastIndex)) { links.first() }
        Net.base.newCall(req("/unrestrict/link", key, FormBody.Builder().add("link", link).build()).build()).execute().use {
            if (!it.isSuccessful) throw java.io.IOException("unrestrict HTTP ${it.code}")
            JSONObject(it.body?.string().orEmpty()).optString("download", "")
        }.ifBlank { throw java.io.IOException("Kein Download-Link") }
    }
}

/** Minimal TorBox client: magnet/infoHash → torrent in the account → direct CDN link. */
object TorBox {
    private const val BASE = "https://api.torbox.app/v1/api"

    private fun req(path: String, key: String) = Request.Builder().url(BASE + path)
        .header("Authorization", "Bearer $key")
        .header("User-Agent", "iptvmamba")

    suspend fun check(key: String): String? = withContext(Dispatchers.IO) {
        try {
            Net.base.newCall(req("/user/me", key).build()).execute().use {
                if (it.code == 200) null else if (it.code == 401 || it.code == 403) "Token ungültig" else "Fehler ${it.code}"
            }
        } catch (e: Exception) {
            "Keine Verbindung: " + e.javaClass.simpleName
        }
    }

    private fun torrent(data: Any?): JSONObject? = when (data) {
        is JSONObject -> data
        is JSONArray -> (0 until data.length()).mapNotNull { data.optJSONObject(it) }.firstOrNull()
        else -> null
    }

    private fun idOf(o: JSONObject): String {
        val d = o.optJSONObject("data") ?: return ""
        val v = d.opt("torrent_id") ?: d.opt("torrentId") ?: return ""
        return v.toString().takeIf { it.isNotBlank() && it != "null" } ?: ""
    }

    suspend fun resolve(key: String, infoHash: String, fileIdx: Int, filename: String?): String = withContext(Dispatchers.IO) {
        val magnet = "magnet:?xt=urn:btih:$infoHash" + (filename?.let { "&dn=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("magnet", magnet)
            .addFormDataPart("seed", "3")
            .addFormDataPart("allow_zip", "false")
            .build()
        val created = Net.base.newCall(req("/torrents/createtorrent", key).post(form).build()).execute().use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw java.io.IOException("Torrent HTTP ${it.code}")
            JSONObject(body)
        }
        if (!created.optBoolean("success", true)) throw java.io.IOException(created.optString("detail", "Torrent abgelehnt"))
        val id = idOf(created)
        if (id.isBlank()) throw java.io.IOException("Keine Torrent-ID")

        var fileId = -1
        var state = ""
        var done = false
        for (n in 0 until 60) {
            val o = Net.base.newCall(req("/torrents/mylist?id=$id&bypass_cache=true", key).build()).execute().use {
                if (!it.isSuccessful) throw java.io.IOException("Status HTTP ${it.code}")
                JSONObject(it.body?.string().orEmpty())
            }
            val t = torrent(o.opt("data"))
            if (t != null) {
                state = t.optString("downloadState", "")
                done = t.optBoolean("downloadFinished") || t.optBoolean("downloadPresent") ||
                    state.lowercase() in setOf("completed", "cached", "uploading")
                val files = t.optJSONArray("files")
                if (files != null && files.length() > 0) {
                    val idx = if (fileIdx in 0 until files.length()) fileIdx else bestVideo(files)
                    fileId = files.optJSONObject(idx)?.optInt("id", -1) ?: -1
                    if (done && fileId > 0) break
                }
            }
            if (state.contains("error", true) || state.contains("failed", true)) {
                throw java.io.IOException("Torrent-Fehler: $state")
            }
            if (done && fileId > 0) break
            delay(2000)
        }
        if (!done) throw java.io.IOException("Torrent nicht bereit ($state)")
        if (fileId <= 0) throw java.io.IOException("Keine Videodatei im Torrent")

        val link = Net.base.newCall(req("/torrents/requestdl?token=$key&torrent_id=$id&file_id=$fileId", key).build()).execute().use {
            if (!it.isSuccessful) throw java.io.IOException("Link HTTP ${it.code}")
            JSONObject(it.body?.string().orEmpty())
        }
        val data = link.opt("data")
        (data as? String)?.takeIf { it.startsWith("http") }
            ?: (data as? JSONObject)?.optString("url")?.takeIf { it.startsWith("http") }
            ?: throw java.io.IOException("Kein Download-Link")
    }

    private fun bestVideo(files: JSONArray): Int {
        var best = 0
        var bestSize = -1L
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            val name = f.optString("name", "").lowercase()
            val video = Regex("\\.(mp4|mkv|avi|webm|mov|m4v|ts)$").containsMatchIn(name)
            val size = f.optLong("size", 0L)
            if (video && size > bestSize) { best = i; bestSize = size }
        }
        if (bestSize >= 0) return best
        for (i in 0 until files.length()) {
            val size = files.optJSONObject(i)?.optLong("size", 0L) ?: 0L
            if (size > bestSize) { best = i; bestSize = size }
        }
        return best
    }
}

/** Resolves a title to an IMDb id via Cinemeta (Stremio), so Torrentio also works without a TMDB key. */
object ImdbLookup {
    suspend fun resolve(title: String, year: Int?, isTv: Boolean): String? = withContext(Dispatchers.IO) {
        runCatching {
            val type = if (isTv) "series" else "movie"
            val q = URLEncoder.encode(title.trim(), "UTF-8")
            val req = Request.Builder()
                .url("https://v3-cinemeta.strem.io/catalog/$type/top/search=$q.json")
                .header("User-Agent", "iptvmamba")
                .build()
            val body = Net.base.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@runCatching null
                r.body?.string().orEmpty()
            }
            val arr = JSONObject(body).optJSONArray("metas") ?: return@runCatching null
            var best: String? = null
            var bestScore = -1
            val want = title.trim()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("imdb_id", "")
                if (id.isBlank()) continue
                val name = o.optString("name", "")
                val y = Regex("\\d{4}").find(o.optString("releaseInfo", ""))?.value?.toIntOrNull()
                var score = 0
                if (name.equals(want, ignoreCase = true)) score += 4
                else if (name.contains(want, ignoreCase = true) || want.contains(name, ignoreCase = true)) score += 2
                if (year != null && y != null) {
                    if (year == y) score += 3 else if (abs(year - y) <= 1) score += 1
                }
                if (score > bestScore) { bestScore = score; best = id }
            }
            best
        }.getOrNull()
    }
}

/** Opens an installed Stremio app with the current title. */
object StreamApps {
    private fun open(ctx: Context, intent: Intent): Boolean =
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)

    private fun launchPkg(ctx: Context, names: List<String>): Boolean {
        val i = names.firstNotNullOfOrNull { ctx.packageManager.getLaunchIntentForPackage(it) } ?: return false
        return open(ctx, i)
    }

    fun stremio(ctx: Context, req: TorrentReq, imdbId: String?): Boolean {
        val type = if (req.season > 0 && req.episode > 0) "series" else "movie"
        val uri = if (!imdbId.isNullOrBlank() && imdbId.startsWith("tt")) "stremio:///detail/$type/$imdbId"
        else "stremio:///search?search=" + URLEncoder.encode(req.title, "UTF-8").replace("+", "%20")
        if (open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(uri)))) return true
        return launchPkg(ctx, listOf("com.stremio.one"))
    }
}

/** Overlay list of stream results from Torrentio/TorBox and optional Stremio/Nuvio add-ons. */
@Composable
fun StreamPicker(req: TorrentReq, onClose: () -> Unit, onPlay: (String) -> Unit) {
    val ctx = LocalContext.current
    val rd = remember { Config.ownRd(ctx) }
    val tb = remember { Config.ownTb(ctx) }
    val addons = remember { Config.addons(ctx) }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<StreamLink>>(emptyList()) }
    var tryNo by remember { mutableIntStateOf(0) }
    var resolving by remember { mutableStateOf(false) }
    var spanishFirst by remember { mutableStateOf(true) }
    var imdbId by remember { mutableStateOf(req.imdbId.ifBlank { null }) }
    val first = remember { FocusRequester() }

    LaunchedEffect(tryNo) {
        loading = true; error = null; notice = null
        val id = imdbId ?: ImdbLookup.resolve(req.item.title, req.item.year, req.item.kind == Kind.SERIES)
        if (id == null) {
            error = "Kein IMDb-Treffer für „${req.item.title}“. Tipp: TMDB-Schlüssel unter Einstellungen → API-Schlüssel eintragen."
        } else {
            imdbId = id
            val series = req.season > 0 && req.episode > 0
            val type = if (series) "series" else "movie"
            val found = ArrayList<StreamLink>()
            val failures = ArrayList<String>()
            val providers = buildList {
                if (rd.isNotBlank()) add("Real-Debrid" to "realdebrid=$rd/")
                if (tb.isNotBlank()) add("TorBox" to "torbox=$tb/")
                if (isEmpty()) add("Torrentio" to "")
            }
            for ((name, cfg) in providers) {
                runCatching { Torrentio.streams(id, req.season, req.episode, cfg) }
                    .onSuccess { found += it }
                    .onFailure { failures += "$name: " + (it.message ?: it.javaClass.simpleName) }
            }
            for (base in addons) {
                val root = StremioAddon.normalize(base) ?: continue
                val src = StremioAddon.label(root)
                runCatching { StremioAddon.streams(root, type, id, src) }
                    .onSuccess { found += it }
                    .onFailure { failures += "$src: " + (it.message ?: it.javaClass.simpleName) }
            }
            val seen = HashSet<String>()
            list = found.filter { s ->
                seen.add((s.infoHash ?: s.url ?: "") + "|" + s.fileIdx + "|" + (s.quality ?: ""))
            }
            if (list.isEmpty()) {
                error = if (failures.isNotEmpty()) failures.joinToString(" · ") else "Keine Streams gefunden."
            }
        }
        loading = false
    }
    val sorted = remember(list, spanishFirst) {
        if (spanishFirst) list.sortedBy { StreamLang.rank(it) } else list
    }
    LaunchedEffect(sorted) { if (sorted.isNotEmpty()) { delay(80); runCatching { first.requestFocus() } } }

    fun pick(s: StreamLink) {
        if (resolving) return
        val direct = s.url
        if (direct != null) { onPlay(direct); return }
        val hash = s.infoHash
        if (hash == null) { notice = "Dieser Stream hat keine Quelle."; return }
        val keys = buildList {
            if (rd.isNotBlank()) add("rd" to rd)
            if (tb.isNotBlank()) add("tb" to tb)
        }
        if (keys.isEmpty()) { notice = "Real-Debrid- oder TorBox-Schlüssel fehlt. Einstellungen → API-Schlüssel."; return }
        resolving = true; notice = null
        scope.launch {
            var last: Throwable? = null
            for ((kind, key) in keys) {
                val r = runCatching {
                    if (kind == "rd") RealDebrid.resolve(key, hash, s.fileIdx, s.filename)
                    else TorBox.resolve(key, hash, s.fileIdx, s.filename)
                }
                if (r.isSuccess) { onPlay(r.getOrThrow()); return@launch }
                last = r.exceptionOrNull()
            }
            notice = "Stream nicht ladbar: " + (last?.message ?: "Fehler")
            resolving = false
        }
    }

    BackHandler(onBack = onClose)
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(Color(0xC8000000), Color(0xF0000000)))
        ),
        contentAlignment = Alignment.Center
    ) {
        GlassSurface(Modifier.fillMaxWidth(0.82f).fillMaxHeight(0.88f)) {
            Column(
                Modifier.fillMaxSize().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        Modifier.size(46.dp).clip(RoundedCornerShape(DS.RadiusM))
                            .background(Look.accent1.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) { AppIcon(AIcon.Download, Look.accent2, size = 24.dp) }
                    Column(Modifier.weight(1f)) {
                        Text("Streams", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp)
                        Text(
                            req.title + (if (req.season > 0 && req.episode > 0) " · S${req.season}E${req.episode}" else ""),
                            color = Muted, fontSize = 14.sp, maxLines = 1
                        )
                    }
                    PillButton("Zurück", light = true) { onClose() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillButton("Stremio", light = true, raw = true) {
                        if (!StreamApps.stremio(ctx, req, imdbId)) notice = "Stremio ist nicht installiert."
                    }
                    PillButton(if (spanishFirst) "Spanisch zuerst: An" else "Spanisch zuerst: Aus", light = true) {
                        spanishFirst = !spanishFirst
                    }
                }
                notice?.let { Text(it, color = Color(0xFFFFB454), fontSize = 15.sp) }
                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Spinner(Accent2, Modifier.size(30.dp))
                        Text("Streams werden gesucht …", color = Muted, fontSize = 17.sp)
                    }
                    error != null -> {
                        Text(error ?: "", color = Color(0xFFFFB454), fontSize = 17.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PillButton("Erneut versuchen", light = true) { tryNo++ }
                            PillButton("Zurück", light = true) { onClose() }
                        }
                    }
                    else -> {
                        if (resolving) Text("Stream wird vorbereitet …", color = Accent2, fontSize = 15.sp)
                        Column(
                            Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            sorted.forEachIndexed { idx, s ->
                                StreamRow(s, if (idx == 0) first else null) { pick(s) }
                            }
                        }
                        val summary = buildList {
                            add(sorted.size.toString() + " Streams")
                            if (rd.isNotBlank()) add("Real-Debrid")
                            if (tb.isNotBlank()) add("TorBox")
                            addons.mapNotNull { StremioAddon.normalize(it) }.map { StremioAddon.label(it) }.distinct().forEach { add(it) }
                        }.joinToString(" · ")
                        Text(summary, color = Muted, fontSize = 13.sp)
                        if (rd.isBlank() && tb.isBlank()) {
                            Text("Für Torrent-Treffer ohne direkten Link brauchst du Real-Debrid oder TorBox (Einstellungen → API-Schlüssel).", color = Muted, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamRow(s: StreamLink, focus: FocusRequester?, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusM)
    val lang = StreamLang.label(s)
    Row(
        Modifier.fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .clip(shape).background(if (f) DS.FocusFill else Color(0x1AFFFFFF))
            .border(1.dp, if (f) DS.FocusFill else DS.Hairline, shape)
            .onFocusChanged { f = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        AppIcon(AIcon.Download, if (f) DS.FocusInk else Accent1, size = 20.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(s.quality ?: s.name.ifBlank { "Stream" }, color = if (f) DS.FocusInk else Color.White,
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                listOfNotNull(s.size, s.provider ?: s.source, lang, s.seeders?.let { "👤 $it" }).joinToString("  ·  "),
                color = if (f) DS.FocusInk else Muted, fontSize = 13.sp, maxLines = 1
            )
        }
        lang?.let {
            Text(it, color = if (f) DS.FocusInk else Accent2, fontSize = 13.sp, fontWeight = FontWeight.Bold, raw = true)
        }
        s.seeders?.let { Text(it.toString(), color = if (f) DS.FocusInk else Accent2, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
    }
}
