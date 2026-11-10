package com.iptvmamba.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.util.TimeZone

/** Archiv (Catchup): Erkennen in der Playlist, Adressen bauen, Zeitfenster. */
class CatchupLogicTest {
    private val berlin = TimeZone.getTimeZone("Europe/Berlin")
    private val utc = TimeZone.getTimeZone("UTC")
    // 2024-05-01 18:15:00 UTC = 20:15 in Berlin (Sommerzeit)
    private val start = 1714587300L
    private val end = start + 45 * 60
    private val now = start + 3 * 3600

    @Test fun xtreamAdresseErkennen() {
        assertEquals(XtreamLive("http://h.tv:8080", "u", "p", "123", "ts"), CatchupLogic.xtreamLive("http://h.tv:8080/live/u/p/123.ts"))
        assertEquals("m3u8", CatchupLogic.xtreamLive("http://h.tv/u/p/9.m3u8")?.ext)
        assertEquals("ts", CatchupLogic.xtreamLive("http://h.tv/u/p/9")?.ext)
        assertNull(CatchupLogic.xtreamLive("http://h.tv/movie/u/p/9.mkv"))
        assertNull(CatchupLogic.xtreamLive("http://h.tv/hls/kanal/index.m3u8"))
    }

    @Test fun xtreamTimeshiftAdresse() {
        val i = CatchupInfo(CatchupLogic.XC, 7)
        assertEquals(
            "http://h.tv:8080/timeshift/u/p/45/2024-05-01:20-15/123.ts",
            CatchupLogic.url(i, "http://h.tv:8080/live/u/p/123.ts", start, end, now, berlin)
        )
        // Server in UTC, angefangene Minute wird aufgerundet
        assertEquals(
            "http://h.tv/timeshift/u/p/46/2024-05-01:18-15/9.m3u8",
            CatchupLogic.url(i, "http://h.tv/u/p/9.m3u8", start, end + 30, now, utc)
        )
        assertNull(CatchupLogic.url(i, "http://anders.tv/kanal.m3u8", start, end, now, utc))
    }

    @Test fun m3uAngabenLesen() {
        val xt = "http://h.tv/live/u/p/5.ts"
        assertEquals(CatchupInfo(CatchupLogic.XC, 7), CatchupLogic.fromInfo("""#EXTINF:-1 tvg-id="a" catchup="xc" catchup-days="7",A""", xt))
        // nur Tage: Art ergibt sich aus der Adresse
        assertEquals(CatchupInfo(CatchupLogic.XC, 3), CatchupLogic.fromInfo("""#EXTINF:-1 tvg-rec="3",A""", xt))
        assertEquals(CatchupInfo(CatchupLogic.SHIFT, 2), CatchupLogic.fromInfo("""#EXTINF:-1 timeshift="2",A""", "http://x.tv/a.m3u8"))
        // eigene Vorlage
        val d = CatchupLogic.fromInfo("""#EXTINF:-1 catchup="default" catchup-days="5" catchup-source="http://x.tv/a.m3u8?start={utc}&end={utcend}",A""", "http://x.tv/a.m3u8")
        assertEquals(CatchupInfo(CatchupLogic.DEFAULT, 5, "http://x.tv/a.m3u8?start={utc}&end={utcend}"), d)
        // nichts angegeben / 0 Tage / xc ohne Xtream-Adresse
        assertNull(CatchupLogic.fromInfo("""#EXTINF:-1 tvg-id="a",A""", xt))
        assertNull(CatchupLogic.fromInfo("""#EXTINF:-1 catchup-days="0",A""", xt))
        assertNull(CatchupLogic.fromInfo("""#EXTINF:-1 catchup="xc",A""", "http://x.tv/a.m3u8"))
        // Kopfzeile gilt für alle Sender ohne eigene Angabe
        val head = CatchupLogic.fromInfo("""#EXTM3U catchup-days="4"""", "")
        assertEquals(CatchupInfo(CatchupLogic.XC, 4), CatchupLogic.fromInfo("""#EXTINF:-1,A""", xt, head))
        assertEquals(CatchupInfo(CatchupLogic.SHIFT, 4), CatchupLogic.fromInfo("""#EXTINF:-1,A""", "http://x.tv/a.m3u8", head))
        // zu viele Tage werden begrenzt
        assertEquals(CatchupLogic.MAX_DAYS, CatchupLogic.fromInfo("""#EXTINF:-1 catchup-days="90",A""", xt)?.days)
    }

    @Test fun vorlagenFuellen() {
        val t = "http://x.tv/a/{Y}/{m}/{d}/{H}-{M}-{S}.m3u8?s=\${start}&e={utcend}&d={duration:60}&o={offset}&f={utc:Y-m-d H:M}&n={lutc}&x={unbekannt}"
        assertEquals(
            "http://x.tv/a/2024/05/01/20-15-00.m3u8?s=$start&e=$end&d=45&o=${now - start}&f=2024-05-01 20:15&n=$now&x={unbekannt}",
            CatchupLogic.fill(t, start, end, now, berlin)
        )
    }

    @Test fun andereArten() {
        val live = "http://x.tv/kanal/index.m3u8?token=1"
        assertEquals("$live&utc=$start&lutc=$now", CatchupLogic.url(CatchupInfo(CatchupLogic.SHIFT, 1), live, start, end, now, utc))
        assertEquals("http://x.tv/k.ts?utc=$start&lutc=$now", CatchupLogic.url(CatchupInfo(CatchupLogic.APPEND, 1), "http://x.tv/k.ts", start, end, now, utc))
        assertEquals("http://x.tv/k.ts?b=$start", CatchupLogic.url(CatchupInfo(CatchupLogic.APPEND, 1, "?b={utc}"), "http://x.tv/k.ts", start, end, now, utc))
        assertEquals("http://x.tv/k.ts?t=1&b=$start", CatchupLogic.url(CatchupInfo(CatchupLogic.APPEND, 1, "?b={utc}"), "http://x.tv/k.ts?t=1", start, end, now, utc))
        assertEquals(
            "http://x.tv/kanal/index-$start-2700.m3u8?token=1",
            CatchupLogic.url(CatchupInfo(CatchupLogic.FLUSSONIC, 1), live, start, end, now, utc)
        )
        assertEquals(
            "http://x.tv/kanal/timeshift_abs-$start.ts",
            CatchupLogic.url(CatchupInfo(CatchupLogic.FLUSSONIC, 1), "http://x.tv/kanal/mpegts", start, end, now, utc)
        )
        // default ohne Vorlage auf Xtream-Server = Xtream-Timeshift
        assertEquals(
            "http://h.tv/timeshift/u/p/45/2024-05-01:18-15/7.ts",
            CatchupLogic.url(CatchupInfo(CatchupLogic.DEFAULT, 1), "http://h.tv/u/p/7.ts", start, end, now, utc)
        )
    }

    @Test fun zeitfenster() {
        val i = CatchupInfo(CatchupLogic.XC, 2)
        assertTrue(CatchupLogic.canPlay(i, start, end, now))
        assertTrue(CatchupLogic.canPlay(i, now - 600, now + 600, now))              // läuft gerade: von Anfang an
        assertFalse(CatchupLogic.canPlay(i, now + 600, now + 1200, now))            // kommt erst
        assertFalse(CatchupLogic.canPlay(i, now - 3 * 86400, now - 3 * 86400 + 60, now))   // zu alt
        assertFalse(CatchupLogic.canPlay(null, start, end, now))
        val list = listOf(EpgEntry("A", "", start, end), EpgEntry("B", "", end, end + 600), EpgEntry("Jetzt", "", now - 60, now + 600), EpgEntry("Alt", "", now - 5 * 86400, now - 5 * 86400 + 600))
        assertEquals(listOf("B", "A"), CatchupLogic.archived(i, list, now).map { it.title })
        assertEquals("B", CatchupLogic.neighbour(list, start, 1)?.title)
        assertEquals("Alt", CatchupLogic.neighbour(list, start, -1)?.title)
        assertNull(CatchupLogic.neighbour(list, now - 60, 1))
    }

    @Test fun speichernUndXtreamZuordnen() {
        val m = mapOf("1" to CatchupInfo(CatchupLogic.XC, 7), "2" to CatchupInfo(CatchupLogic.DEFAULT, 3, "http://x/{utc}"))
        assertEquals(m, CatchupLogic.decode(CatchupLogic.encode(m)))
        assertEquals(emptyMap<String, CatchupInfo>(), CatchupLogic.decode("kaputt\n9\t99\t1\n"))
        val live = mapOf("a" to "http://h/live/u/p/10.ts", "b" to "http://h/live/u/p/11.ts", "c" to "http://anders/x.m3u8")
        val merged = CatchupLogic.merge(live, mapOf("10" to 5, "11" to 0), mapOf("c" to CatchupInfo(CatchupLogic.SHIFT, 1)))
        assertEquals(mapOf("a" to CatchupInfo(CatchupLogic.XC, 5), "c" to CatchupInfo(CatchupLogic.SHIFT, 1)), merged)
        assertEquals(1, CatchupLogic.apiInt("1")); assertEquals(7, CatchupLogic.apiInt(" 7 ")); assertEquals(0, CatchupLogic.apiInt(null)); assertEquals(1, CatchupLogic.apiInt("true"))
    }

    @Test fun playlistLiefertArchiv() {
        val m3u = """#EXTM3U
#EXTINF:-1 tvg-id="ard" catchup="xc" catchup-days="7" group-title="DE",Das Erste
http://h.tv/live/u/p/1.ts
#EXTINF:-1 tvg-id="zdf" group-title="DE",ZDF
http://h.tv/live/u/p/2.ts
#EXTINF:-1 group-title="Filme",Matrix (1999)
http://h.tv/movie/u/p/3.mkv
"""
        val p = M3uParser.parseStream(StringReader(m3u))
        val ard = p.items.first { it.title == "Das Erste" }
        assertEquals(mapOf(ard.id to CatchupInfo(CatchupLogic.XC, 7)), p.catchup)
    }
}
