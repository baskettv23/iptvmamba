package com.iptvmamba.player

import android.content.Context
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
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.net.URLEncoder

/** One torrent result from Torrentio, optionally already resolved to a direct Real-Debrid link. */
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
    val filename: String?
)

/** What to look up: a movie (season/episode = 0) or one episode of a series. */
data class TorrentReq(
    val item: Item,
    val imdbId: String,
    val title: String,
    val season: Int,
    val episode: Int
)

/** Torrentio (Stremio add-on) stream search. With a Real-Debrid key it returns ready-to-play links. */
object Torrentio {
    suspend fun streams(imdbId: String, season: Int, episode: Int, rdKey: String): List<StreamLink> = withContext(Dispatchers.IO) {
        val series = season > 0 && episode > 0
        val id = if (series) "$imdbId:$season:$episode" else imdbId
        val type = if (series) "series" else "movie"
        val cfg = if (rdKey.isNotBlank()) "realdebrid=$rdKey/" else ""
        val req = Request.Builder()
            .url("https://torrentio.strem.fun/${cfg}stream/$type/$id.json")
            .header("User-Agent", "iptvmamba")
            .build()
        val body = Net.base.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        val arr = JSONObject(body).optJSONArray("streams") ?: return@withContext emptyList()
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
            out += StreamLink(name, title, quality, size, seeders, provider, url, hash, o.optInt("fileIdx", 0), filename)
        }
        out
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

    private fun select(id: String, key: String) {
        Net.base.newCall(req("/torrents/selectFiles/$id", key, FormBody.Builder().add("files", "all").build()).build())
            .execute().close()
    }

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
        select(id, key)
        var links: List<String> = emptyList()
        var status = ""
        for (n in 0 until 90) {
            val info = Net.base.newCall(req("/torrents/info/$id", key).build()).execute().use {
                if (!it.isSuccessful) throw java.io.IOException("info HTTP ${it.code}")
                JSONObject(it.body?.string().orEmpty())
            }
            status = info.optString("status", "")
            info.optJSONArray("links")?.let { a -> links = (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }
            if (status == "downloaded" && links.isNotEmpty()) break
            if (status == "error" || status == "magnet_error" || status == "virus" || status == "dead") throw java.io.IOException("Torrent-Fehler: $status")
            if (status == "waiting_files_selection") select(id, key)
            delay(2000)
        }
        if (links.isEmpty()) throw java.io.IOException("Torrent nicht bereit ($status)")
        val link = links[fileIdx.coerceIn(0, links.lastIndex)]
        Net.base.newCall(req("/unrestrict/link", key, FormBody.Builder().add("link", link).build()).build()).execute().use {
            if (!it.isSuccessful) throw java.io.IOException("unrestrict HTTP ${it.code}")
            JSONObject(it.body?.string().orEmpty()).optString("download", "")
        }.ifBlank { throw java.io.IOException("Kein Download-Link") }
    }
}

/** Overlay list of Torrentio results; resolves via Real-Debrid when Torrentio did not already. */
@Composable
fun StreamPicker(req: TorrentReq, onClose: () -> Unit, onPlay: (String) -> Unit) {
    val ctx = LocalContext.current
    val key = remember { Config.ownRd(ctx) }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<StreamLink>>(emptyList()) }
    var tryNo by remember { mutableIntStateOf(0) }
    var resolving by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }

    LaunchedEffect(tryNo, key) {
        loading = true; error = null; notice = null
        if (key.isBlank()) {
            error = "Real-Debrid-Schlüssel fehlt. Einstellungen → API-Schlüssel."
        } else {
            runCatching { Torrentio.streams(req.imdbId, req.season, req.episode, key) }
                .onSuccess { list = it; if (it.isEmpty()) error = "Keine Streams gefunden." }
                .onFailure { error = "Torrentio nicht erreichbar: " + (it.message ?: it.javaClass.simpleName) }
        }
        loading = false
    }
    LaunchedEffect(list) { if (list.isNotEmpty()) { delay(80); runCatching { first.requestFocus() } } }

    fun pick(s: StreamLink) {
        if (resolving) return
        val direct = s.url
        if (direct != null) { onPlay(direct); return }
        val hash = s.infoHash
        if (hash == null) { notice = "Dieser Stream hat keine Quelle."; return }
        if (key.isBlank()) { notice = "Real-Debrid-Schlüssel fehlt."; return }
        resolving = true; notice = null
        scope.launch {
            runCatching { RealDebrid.resolve(key, hash, s.fileIdx, s.filename) }
                .onSuccess { onPlay(it) }
                .onFailure { notice = "Stream nicht ladbar: " + (it.message ?: "Fehler"); resolving = false }
        }
    }

    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth(0.78f).fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(20.dp)).background(Surf).border(1.dp, DS.Hairline, RoundedCornerShape(20.dp))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Streams", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        req.title + (if (req.season > 0 && req.episode > 0) " · S${req.season}E${req.episode}" else ""),
                        color = Muted, fontSize = 14.sp, maxLines = 1
                    )
                }
                PillButton("Zurück", light = true) { onClose() }
            }
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
                    if (resolving) Text("Real-Debrid bereitet den Stream vor …", color = Accent2, fontSize = 15.sp)
                    notice?.let { Text(it, color = Color(0xFFFFB454), fontSize = 15.sp) }
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        list.forEachIndexed { idx, s ->
                            StreamRow(s, if (idx == 0) first else null) { pick(s) }
                        }
                    }
                    Text(list.size.toString() + " Streams · Torrentio + Real-Debrid", color = Muted, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun StreamRow(s: StreamLink, focus: FocusRequester?, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusM)
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
                listOfNotNull(s.size, s.provider, s.seeders?.let { "👤 $it" }).joinToString("  ·  "),
                color = if (f) DS.FocusInk else Muted, fontSize = 13.sp, maxLines = 1
            )
        }
        s.seeders?.let { Text(it.toString(), color = if (f) DS.FocusInk else Accent2, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
    }
}
