package com.iptvmamba.player

import android.content.Context
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

data class SubQuery(
    val title: String,
    val tmdbId: Int,
    val season: Int,
    val episode: Int,
    val lang: String
)

data class SubResult(
    val provider: String,
    val lang: String,
    val release: String,
    val downloads: Int,
    val rating: Double? = null,
    val fileId: Long? = null,
    val url: String? = null,
    val pageUrl: String? = null
)

object Subtitles {
    private const val UA = "iptvmamba/1.6"
    private const val OS_API = "https://api.opensubtitles.com/api/v1"
    private const val CAT = "https://www.subtitlecat.com"

    private val JSON = "application/json; charset=utf-8".toMediaType()

    val LANGS = listOf(
        "de" to "Deutsch",
        "en" to "English",
        "es" to "Español",
        "fr" to "Français",
        "it" to "Italiano",
        "pt" to "Português",
        "tr" to "Türkçe",
        "pl" to "Polski",
        "nl" to "Nederlands",
        "ru" to "Русский",
        "ar" to "العربية"
    )

    fun langName(code: String): String =
        LANGS.firstOrNull { it.first == code }?.second ?: code.uppercase(Locale.US)

    fun nextLang(code: String): String {
        val i = LANGS.indexOfFirst { it.first == code }
        return LANGS[(i + 1).coerceAtLeast(0) % LANGS.size].first
    }

    fun mime(file: File): String = when (file.extension.lowercase(Locale.US)) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    suspend fun search(ctx: Context, q: SubQuery): List<SubResult> = withContext(Dispatchers.IO) {
        val lang = TrackLogic.norm(q.lang) ?: q.lang
        coroutineScope {
            val open = async { runCatching { openSubtitles(q, lang) }.getOrDefault(emptyList()) }
            val subdl = async { runCatching { subdl(q, lang) }.getOrDefault(emptyList()) }
            val cat = async { runCatching { subtitleCat(q, lang) }.getOrDefault(emptyList()) }
            (open.await() + subdl.await() + cat.await())
                .distinctBy { it.provider + "|" + it.fileId + "|" + it.url + "|" + it.pageUrl }
                .sortedWith(compareByDescending<SubResult> { it.downloads }.thenBy { it.provider })
                .take(40)
        }
    }

    suspend fun download(ctx: Context, r: SubResult, q: SubQuery): File = withContext(Dispatchers.IO) {
        val raw = when (r.provider) {
            "OpenSubtitles" -> downloadOpenSubtitles(r)
            "SubDL" -> getBytes(r.url ?: error("Kein Download-Link"))
            "SubtitleCat" -> getBytes(r.url ?: error("Kein Download-Link"))
            else -> error("Unbekannter Anbieter")
        }
        save(ctx, raw, r, q)
    }

    private fun openSubtitles(q: SubQuery, lang: String): List<SubResult> {
        val type = if (q.season > 0) "tv" else "movie"
        val u = StringBuilder(OS_API)
            .append("/subtitles?query=").append(enc(baseTitle(q.title)))
            .append("&languages=").append(enc(lang))
            .append("&type=").append(type)
            .append("&order_by=download_count&order_direction=desc&page=1")
        if (q.season > 0) u.append("&season_number=").append(q.season).append("&episode_number=").append(q.episode)
        val headers = mutableMapOf("Accept" to "application/json")
        if (Config.OS_KEY.isNotBlank()) headers["Api-Key"] = Config.OS_KEY
        val data = JSONObject(get(u.toString(), headers)).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<SubResult>()
        for (i in 0 until data.length()) {
            val a = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
            val f = a.optJSONArray("files")?.optJSONObject(0) ?: continue
            val id = f.optLong("file_id")
            if (id <= 0) continue
            val feature = a.optJSONObject("feature_details")
            val release = a.optString("release").ifBlank { feature?.optString("title").orEmpty() }
            val rating = a.optDouble("ratings").takeIf { !it.isNaN() }
            out.add(
                SubResult(
                    provider = "OpenSubtitles",
                    lang = a.optString("language", lang),
                    release = release,
                    downloads = a.optInt("download_count"),
                    rating = rating,
                    fileId = id,
                    pageUrl = a.optString("url")
                )
            )
        }
        return out
    }

    private fun subdl(q: SubQuery, lang: String): List<SubResult> {
        val key = Config.SD_KEY
        if (key.isBlank()) return emptyList()
        val type = if (q.season > 0) "tv" else "movie"
        val u = StringBuilder("https://api.subdl.com/api/v1/subtitles?api_key=").append(enc(key))
            .append("&type=").append(type)
            .append("&languages=").append(enc(lang.uppercase(Locale.US)))
            .append("&subs_per_page=30&unpack=1&client=other")
        if (q.tmdbId > 0) u.append("&tmdb_id=").append(q.tmdbId)
        else u.append("&film_name=").append(enc(baseTitle(q.title)))
        if (q.season > 0) u.append("&season_number=").append(q.season).append("&episode_number=").append(q.episode)
        val j = JSONObject(get(u.toString(), mapOf("Accept" to "application/json")))
        if (!j.optBoolean("status")) return emptyList()
        val subs = j.optJSONArray("subtitles") ?: return emptyList()
        val out = ArrayList<SubResult>()
        for (i in 0 until subs.length()) {
            val o = subs.optJSONObject(i) ?: continue
            val path = o.optString("url")
            if (path.isBlank()) continue
            val full = if (path.startsWith("http")) path else "https://dl.subdl.com" + path
            val release = o.optString("release_name").ifBlank { o.optString("name") }
            out.add(
                SubResult(
                    provider = "SubDL",
                    lang = o.optString("language", lang),
                    release = release,
                    downloads = o.optInt("downloads"),
                    url = full
                )
            )
        }
        return out
    }

    private fun subtitleCat(q: SubQuery, lang: String): List<SubResult> {
        val episode = if (q.season > 0) String.format(Locale.US, " S%02dE%02d", q.season, q.episode) else ""
        val queries = listOf(baseTitle(q.title) + episode, baseTitle(q.title)).distinct()
        for (query in queries) {
            val html = get(CAT + "/index.php?search=" + enc(query))
            val pages = Regex("href=\"(subs/\\d+/[^\"]+\\.html)\"", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { CAT + "/" + it.groupValues[1] }
                .distinct()
                .take(8)
                .toList()
            val out = ArrayList<SubResult>()
            val linkRe = Regex(
                "href=\"(/subs/\\d+/[^\"]*?[-_.]" + Regex.escape(lang) + "(?:[-_.][A-Za-z]{2,3})?\\.srt)\"",
                RegexOption.IGNORE_CASE
            )
            for (page in pages) {
                val body = runCatching { get(page) }.getOrNull() ?: continue
                val link = linkRe.find(body)?.groupValues?.get(1) ?: continue
                val title = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE)
                    .find(body)?.groupValues?.get(1)
                    ?.substringBefore(" subtitles", missingDelimiterValue = "")
                    ?.trim()
                    ?.ifBlank { null }
                    ?: page.substringAfterLast('/').substringBeforeLast(".html")
                out.add(
                    SubResult(
                        provider = "SubtitleCat",
                        lang = lang,
                        release = title,
                        downloads = 0,
                        url = CAT + link
                    )
                )
            }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    private fun downloadOpenSubtitles(r: SubResult): ByteArray {
        val id = r.fileId ?: error("Keine Datei-ID")
        val body = JSONObject().put("file_id", id).put("sub_format", "srt").toString().toRequestBody(JSON)
        val b = Request.Builder()
            .url(OS_API + "/download")
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
        if (Config.OS_KEY.isNotBlank()) b.header("Api-Key", Config.OS_KEY)
        Net.withTimeouts(10, 25).newCall(b.post(body).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("OpenSubtitles: HTTP " + resp.code)
            val link = JSONObject(text).optString("link")
            if (link.isBlank()) error("OpenSubtitles: kein Download-Link")
            return getBytes(link)
        }
    }

    private fun save(ctx: Context, raw: ByteArray, r: SubResult, q: SubQuery): File {
        val bytes = gunzip(raw)
        val (data, name) = if (isZip(bytes)) unzip(bytes, q) else bytes to r.release
        if (data.isEmpty()) error("Untertiteldatei ist leer")
        if (looksHtml(data)) error("Der Anbieter hat keine Untertiteldatei geliefert")
        val ext = when {
            name.endsWith(".vtt", true) -> "vtt"
            name.endsWith(".ass", true) || name.endsWith(".ssa", true) -> "ass"
            name.endsWith(".srt", true) -> "srt"
            else -> detectExt(data)
        }
        val dir = File(ctx.cacheDir, "subs").apply { mkdirs() }
        val f = File(dir, "sub_" + q.lang + "_" + System.currentTimeMillis() + "." + ext)
        f.writeBytes(data)
        return f
    }

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> b.header(k, v) }
        Net.withTimeouts(10, 25).newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP " + r.code)
            return r.body?.string().orEmpty()
        }
    }

    private fun getBytes(url: String): ByteArray {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        Net.withTimeouts(10, 30).newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP " + r.code)
            return r.body?.bytes() ?: error("Leere Antwort")
        }
    }

    private fun baseTitle(title: String): String =
        title.replace(Regex("\\s+S\\d+E\\d+\\s*$"), "").trim()

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun gunzip(raw: ByteArray): ByteArray =
        if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte())
            GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
        else raw

    private fun isZip(b: ByteArray): Boolean =
        b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

    private fun unzip(bytes: ByteArray, q: SubQuery): Pair<ByteArray, String> {
        val entries = ArrayList<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val n = e.name.substringAfterLast('/')
                    val ext = n.substringAfterLast('.', "").lowercase(Locale.US)
                    if (ext == "srt" || ext == "ass" || ext == "ssa" || ext == "vtt") {
                        entries.add(n to z.readBytes())
                    }
                }
                z.closeEntry()
                e = z.nextEntry
            }
        }
        if (entries.isEmpty()) error("Keine Untertiteldatei im Archiv")
        val want = if (q.season > 0) String.format(Locale.US, "s%02de%02d", q.season, q.episode) else ""
        val hit = entries.firstOrNull { want.isNotEmpty() && it.first.lowercase(Locale.US).contains(want) }
            ?: entries.firstOrNull { want.isNotEmpty() && it.first.lowercase(Locale.US).contains("e" + String.format(Locale.US, "%02d", q.episode)) }
            ?: entries.first()
        return hit.second to hit.first
    }

    private fun detectExt(b: ByteArray): String {
        val head = String(b, 0, minOf(b.size, 256), Charsets.UTF_8).trimStart()
        return when {
            head.startsWith("WEBVTT", true) -> "vtt"
            head.startsWith("[Script Info]", true) -> "ass"
            else -> "srt"
        }
    }

    private fun looksHtml(b: ByteArray): Boolean {
        val head = String(b, 0, minOf(b.size, 512), Charsets.UTF_8).trimStart().lowercase(Locale.US)
        return head.startsWith("<!doctype html") || head.startsWith("<html")
    }
}
