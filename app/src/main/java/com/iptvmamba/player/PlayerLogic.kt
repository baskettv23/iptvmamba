package com.iptvmamba.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.HttpDataSource
import java.util.Locale

/**
 * Pure helper functions of the player (without Compose) so they can be tested.
 * All names are deliberately "player"-specific so nothing collides with other files.
 */

/** 3725000 ms -> "01:02:05" bzw. "02:05" */
fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%02d:%02d", m, sec)
}

/** Short form for jumps: 30000 -> "0:30", 160000 -> "2:40" */
fun fmtShort(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%d:%02d", m, sec)
}

/** "+2:40" bzw. "−0:30" */
fun fmtDelta(ms: Long): String = (if (ms >= 0) "+" else "−") + fmtShort(kotlin.math.abs(ms))

/** Fast seeking: level 0..4 depending on how long a direction key is held. */
fun turboLevel(holdMs: Long): Int = when {
    holdMs < 500 -> 0
    holdMs < 1200 -> 1
    holdMs < 2200 -> 2
    holdMs < 3500 -> 3
    else -> 4
}

/** Jump size per step: level 0 = configured size, higher levels = fraction of the total length (e.g. 1/15). */
fun turboStepMs(level: Int, stepMs: Long, durMs: Long): Long {
    if (level <= 0) return stepMs
    val d = if (durMs > 0) durMs else 3_600_000L
    val frac = when (level) { 1 -> d / 60; 2 -> d / 30; 3 -> d / 15; else -> d / 8 }
    return maxOf(stepMs * (level + 1), frac)
}

/** Clock time (seconds since 1970) at which the movie ends at the current speed. */
fun endClockSec(nowMs: Long, remainMs: Long, speed: Float): Long {
    val sp = if (speed > 0f) speed else 1f
    return (nowMs + (remainMs.coerceAtLeast(0) / sp).toLong()) / 1000
}

fun qualityName(h: Int): String = when {
    h >= 2160 -> "4K"
    h >= 1440 -> "2K"
    h >= 1080 -> "FHD"
    h >= 720 -> "HD"
    else -> "SD"
}

fun rankQ(q: String): Int = when (q) { "4K" -> 4; "2K" -> 3; "FHD" -> 2; "HD" -> 1; else -> 0 }

fun codecName(mime: String?): String = when {
    mime == null -> ""
    mime.contains("avc") -> "H.264"
    mime.contains("hevc") -> "HEVC"
    mime.contains("av01") -> "AV1"
    mime.contains("vp9") -> "VP9"
    mime.contains("mpeg2") -> "MPEG-2"
    mime.contains("mp4a") -> "AAC"
    mime.contains("eac3") -> "E-AC-3"
    mime.contains("ac-3") -> "AC-3"
    else -> mime.substringAfter('/')
}

fun channelLabel(n: Int): String = when (n) {
    1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"
    else -> if (n > 0) "$n Kanäle" else ""
}

fun langLabel(code: String?): String? =
    code?.takeIf { it != "und" && it.isNotBlank() }
        ?.let { Locale.forLanguageTag(it).getDisplayLanguage(PlayerLocale.locale).replaceFirstChar { c -> c.uppercase() } }

/** Language for display names (e.g. audio track languages); set by the language settings. */
object PlayerLocale {
    @Volatile var locale: Locale = Locale.GERMAN
}

fun trackLabel(f: Format, n: Int): String {
    val base = f.label ?: langLabel(f.language) ?: "Spur $n"
    val ch = if (f.channelCount > 0) channelLabel(f.channelCount) else ""
    return if (ch.isNotEmpty()) "$base · $ch" else base
}

class TrackOpt(val label: String, val group: Tracks.Group, val idx: Int, val selected: Boolean)

fun collectTracks(player: Player, type: Int): List<TrackOpt> {
    val out = ArrayList<TrackOpt>()
    var n = 0
    for (g in player.currentTracks.groups) {
        if (g.type != type) continue
        for (i in 0 until g.length) {
            // Always show audio and subtitles completely (even if headphones/box cannot do the format) – the user decides.
            // Only video levels the device cannot decode at all stay hidden.
            val ok = g.isTrackSupported(i)
            if (!ok && type == C.TRACK_TYPE_VIDEO) continue
            n++
            val f = g.getTrackFormat(i)
            val base = if (type == C.TRACK_TYPE_VIDEO) (if (f.height > 0) "${f.height}p" else "Spur $n") else trackLabel(f, n)
            val label = if (ok || type == C.TRACK_TYPE_VIDEO) base else "$base (evtl. nicht abspielbar)"
            out.add(TrackOpt(label, g, i, g.isTrackSelected(i)))
        }
    }
    return out
}

/**
 * Cleans show data from the provider: sorts, discards broken times and makes sure
 * that two shows never overlap (the earlier one is shortened).
 */
/**
 * XMLTV time ("20261019132500 +0200" or without zone = UTC) -> seconds since 1970.
 * Pure calculation without SimpleDateFormat: several times faster with hundreds of thousands of shows.
 */
fun xmltvTime(s: String?): Long? {
    if (s == null) return null
    val t = s.trim()
    if (t.length < 14) return null
    for (i in 0 until 14) if (t[i] !in '0'..'9') return null
    val y = t.substring(0, 4).toInt()
    val mo = t.substring(4, 6).toInt()
    val d = t.substring(6, 8).toInt()
    val h = t.substring(8, 10).toInt()
    val mi = t.substring(10, 12).toInt()
    val se = t.substring(12, 14).toInt()
    if (mo !in 1..12 || d !in 1..31 || h > 23 || mi > 59 || se > 60) return null
    var off = 0
    val rest = t.substring(14).trim()
    if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
        val oh = rest.substring(1, 3).toIntOrNull() ?: return null
        val om = rest.substring(3, 5).toIntOrNull() ?: return null
        off = (if (rest[0] == '-') -1 else 1) * (oh * 3600 + om * 60)
    }
    // Tage seit 1970 (proleptischer gregorianischer Kalender)
    val yy = if (mo <= 2) y - 1 else y
    val era = (if (yy >= 0) yy else yy - 399) / 400
    val yoe = yy - era * 400
    val doy = (153 * (mo + (if (mo > 2) -3 else 9)) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era.toLong() * 146097L + doe - 719468L
    return days * 86400L + h * 3600L + mi * 60L + se - off
}

fun cleanEpg(src: List<EpgEntry>): List<EpgEntry> {
    val sorted = src.filter { it.end > it.start && epgSane(it.start, it.end) }.sortedBy { it.start }
    val out = ArrayList<EpgEntry>()
    for (e in sorted) {
        val last = out.lastOrNull()
        if (last != null && e.start < last.end) {
            if (e.end <= last.end) continue
            if (e.start - last.start < 60) { out[out.lastIndex] = e; continue }
            out[out.lastIndex] = last.copy(end = e.start)
        }
        out.add(e)
    }
    return out
}

fun speedLabel(s: Float): String = when (s) {
    0.5f -> "0,5×"
    0.75f -> "0,75×"
    1f -> "1× (Normal)"
    1.25f -> "1,25×"
    1.5f -> "1,5×"
    else -> "2×"
}


/** HTTP status behind a playback error (0 if there is none). */
fun httpStatusOf(e: PlaybackException): Int {
    var t: Throwable? = e.cause
    var depth = 0
    while (t != null && depth < 6) {
        if (t is HttpDataSource.InvalidResponseCodeException) return t.responseCode
        t = t.cause
        depth++
    }
    return 0
}

/** Responses where a retry brings nothing (login, subscription or address are wrong). */
fun isPermanentHttp(status: Int): Boolean = status == 401 || status == 403 || status == 404 || status == 410 || status == 451

/** Understandable error message with a solution hint. Never shows the address of the stream. */
fun playbackErrorText(code: Int, status: Int, live: Boolean, tls: Boolean = false): String = when {
    tls -> "Das Zertifikat des Anbieters passt nicht zur Server-Adresse. Beim Anbieter nach einer aktuellen Adresse fragen."
    status == 401 || status == 403 -> "Zugang abgelehnt ($status). Benutzername, Passwort oder Abo beim Anbieter prüfen."
    status == 404 || status == 410 -> if (live) "Dieser Sender ist beim Anbieter nicht mehr vorhanden." else "Dieser Titel ist beim Anbieter nicht mehr vorhanden."
    status == 429 || status == 458 || status == 509 -> "Maximale Verbindungen des Anbieters erreicht. Anderes Gerät oder Profil beenden und erneut versuchen."
    status == 512 -> "Der Server des Anbieters lehnt die Verbindung ab (512). Meist ist die Server-Adresse nicht mehr aktuell oder gesperrt."
    status in 500..599 -> "Der Anbieter hat gerade ein Problem ($status). Später erneut versuchen."
    status >= 400 -> "Der Anbieter lehnt die Wiedergabe ab ($status)."
    code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Keine Verbindung zum Anbieter. Internet und Netzwerk prüfen."
    code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
        code == PlaybackException.ERROR_CODE_DECODING_FAILED ||
        code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "Dieses Videoformat kann das Gerät nicht abspielen."
    code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
        code == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
        code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "Der Stream ist fehlerhaft oder wird nicht unterstützt."
    else -> "Wiedergabe nicht möglich."
}

object PlayerLogic {
    /** Hint when a stream has a format the player cannot open (instead of a crash). */
    const val MSG_FORMAT = "Dieses Stream-Format wird nicht unterstützt."
}
