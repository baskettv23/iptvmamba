package com.iptvmamba.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader

/** Große Playlists: Einlesen mit Sprachfilter, Zählen, Speicher-Schätzung, Fortsetzen, gemerkter Ladeauftrag, Zwischenspeicher v5. */
class LoadLogicTest {
    private val m3u = """#EXTM3U url-tvg="http://epg/x.xml"
#EXTINF:-1 tvg-id="ard" tvg-logo="http://l/ard.png" group-title="DE | News",DE: Das Erste HD
http://h/live/u/p/1.ts
#EXTINF:-1 group-title="TR | Ulusal",TRT 1
http://h/live/u/p/2.ts
#EXTINF:-1 tvg-logo="http://l/m.jpg" group-title="DE | Action",DE - Matrix (1999)
http://h/movie/u/p/3.mkv
#EXTINF:-1 group-title="EN | Action",EN - Matrix (1999)
http://h/movie/u/p/4.mkv
#EXTINF:-1 tvg-logo="http://l/bb.jpg" group-title="DE | Serien",DE - Breaking Bad S01 E01
http://h/series/u/p/5.mkv
#EXTINF:-1 group-title="DE | Serien",DE - Breaking Bad S01 E02
http://h/series/u/p/6.mkv
#EXTINF:-1 group-title="EN | Series",EN - Breaking Bad S01 E01
http://h/series/u/p/7.mkv
#EXTINF:-1 group-title="EN | Series",EN - Lost S01 E01
http://h/series/u/p/8.mkv
#EXTINF:-1 group-title="x",*** Trenner ***
http://h/x
"""

    @Test fun ohneFilter_genauWieVorher() {
        val p = M3uParser.parseStream(StringReader(m3u))
        assertEquals(M3uParser.parse(m3u), p.items)
        // Sprache je Serie aus der Kategorie; Folgen sortiert
        assertEquals("en", p.items.first { it.title == "Breaking Bad" }.lang)
        val de = p.items.first { it.kind == Kind.SERIES && it.lang == "de" }
        assertEquals(listOf(1, 2), de.episodes.map { it.num })
        assertEquals(listOf("http://epg/x.xml"), p.epgUrls)
    }

    @Test fun ausgeblendeteSprachenWerdenNichtAngelegtAberGezaehlt() {
        val p = M3uParser.parseStream(StringReader(m3u), hidden = setOf("en", "tr"))
        assertTrue(p.items.all { it.lang == "de" })
        assertEquals(3, p.items.size)                       // Das Erste, Matrix (DE), Breaking Bad
        val bb = p.items.first { it.kind == Kind.SERIES }
        assertTrue(bb.episodes.all { it.variants.isEmpty() })  // die englische Quelle wurde gar nicht erst angelegt
        // Titel je Sprache: Serien zählen einmal, nicht je Folge
        assertEquals(mapOf("de" to 3, "tr" to 1, "en" to 3), p.counts)
        assertTrue(p.bytes.getValue("de") > 0 && p.bytes.getValue("en") > 0)
    }

    @Test fun pruefDurchlaufZaehltGleich() {
        val s = M3uParser.scanStream(StringReader(m3u))
        val p = M3uParser.parseStream(StringReader(m3u))
        assertEquals(p.counts, s.counts)
        assertEquals(p.bytes, s.bytes)
        assertEquals(8, s.entries + 0 - 1)   // 9 Einträge inkl. Trenner
    }

    @Test fun waecheKannAbbrechen() {
        val big = StringBuilder("#EXTM3U\n")
        repeat(5000) { big.append("#EXTINF:-1 group-title=\"DE | TV\",Sender $it\nhttp://h/live/$it\n") }
        var calls = 0
        try {
            M3uParser.parseStream(StringReader(big.toString()), emptySet()) { if (++calls == 2) throw ParseStop("Speicher") }
            throw AssertionError("hätte abbrechen müssen")
        } catch (e: ParseStop) {
            assertEquals(2, calls)
        }
    }

    @Test fun speicherSchaetzungUndVorauswahl() {
        val bytes = mapOf("de" to 40L, "en" to 100L, "tr" to 30L, "?" to 5L)
        val counts = mapOf("de" to 4, "en" to 10, "tr" to 3, "?" to 1)
        assertEquals(175L, LoadLogic.estimate(bytes, emptySet()))
        assertEquals(45L, LoadLogic.estimate(bytes, setOf("en", "tr")))
        assertTrue(LoadLogic.fits(emptyMap(), emptySet(), 1))        // unbekannt = passt
        assertFalse(LoadLogic.fits(bytes, emptySet(), 100))
        assertEquals(setOf("de", "?"), LoadLogic.preselect(counts, bytes, "de", 100))
        assertEquals(setOf("de"), LoadLogic.preselect(counts, bytes, "de", 42))     // mit "ohne Kennung" zu groß
        assertEquals(setOf("tr"), LoadLogic.preselect(counts, bytes, "fr", 35))     // App-Sprache fehlt: größte, die passt
        // 40 % des Speichers, umgerechnet in Byte der Liste (kompakte Folgen: ~0,65 Byte Speicher je Byte Liste)
        assertEquals((400L * 1024 * 1024 * 0.40 / 0.65).toLong(), LoadLogic.budget(400L * 1024 * 1024))
    }

    @Test fun fortsetzenNurMitStarkemValidator() {
        assertEquals(emptyMap<String, String>(), LoadLogic.resumeHeaders(1000, null, null))
        assertEquals(emptyMap<String, String>(), LoadLogic.resumeHeaders(1000, "W/\"abc\"", null))
        assertEquals(emptyMap<String, String>(), LoadLogic.resumeHeaders(0, "\"abc\"", null))
        assertEquals(mapOf("Range" to "bytes=1000-", "If-Range" to "\"abc\""), LoadLogic.resumeHeaders(1000, "\"abc\"", "x"))
        assertEquals("Tue, 01 Oct 2026 10:00:00 GMT", LoadLogic.resumeHeaders(5, "W/\"a\"", "Tue, 01 Oct 2026 10:00:00 GMT")["If-Range"])
    }

    @Test fun antwortAufFortsetzen() {
        assertEquals(LoadLogic.APPEND, LoadLogic.resumeAction(206, "bytes 1000-1999/2000", 1000))
        assertEquals(LoadLogic.ERROR, LoadLogic.resumeAction(206, "bytes 0-1999/2000", 1000))
        assertEquals(LoadLogic.RESTART, LoadLogic.resumeAction(200, null, 1000))   // Server schickt alles neu (Inhalt geändert)
        assertEquals(LoadLogic.RESTART, LoadLogic.resumeAction(200, null, 0))
        assertEquals(LoadLogic.ERROR, LoadLogic.resumeAction(403, null, 0))
        assertEquals(2000L, LoadLogic.rangeTotal("bytes 1000-1999/2000"))
        assertEquals(-1L, LoadLogic.rangeTotal("bytes 1000-1999/*"))
    }

    @Test fun teilDateiAngaben() {
        val pi = LoadLogic.PartInfo(42, "\"e=1\"", null, "UTF-8")
        assertEquals(pi, LoadLogic.PartInfo.decode(pi.encode()))
        assertNull(LoadLogic.PartInfo.decode("kaputt"))
        assertNull(LoadLogic.PartInfo.decode(null))
    }

    @Test fun gemerkterLadeauftrag() {
        val url = "http://s.tld:8080/get.php?username=a%26b&password=p%3D1&type=m3u_plus&output=ts"
        val first = LoadLogic.started(null, url, resumed = false)
        assertEquals(1, first.attempts)
        assertTrue(first.autoResume)
        val again = LoadLogic.started(first, url, resumed = true)
        val third = LoadLogic.started(again, url, resumed = true)
        assertEquals(3, third.attempts)
        assertFalse(third.autoResume)                                   // nach 3 Unterbrechungen nicht mehr automatisch
        assertEquals(1, LoadLogic.started(third, url, resumed = false).attempts)   // von Hand: neu zählen
        val failed = first.copy(state = LoadLogic.FAILED, msg = "Zeitüberschreitung")
        assertEquals(failed, LoadLogic.Pending.decode(failed.encode()))
        assertFalse(failed.autoResume)
        assertEquals(Triple("http://s.tld:8080", "a&b", "p=1"), LoadLogic.xtreamParts(url))
        assertNull(LoadLogic.xtreamParts("http://s.tld/list.m3u"))
    }

    @Test fun zwischenspeicherV5_nurGezeigteMitZahlenDerGanzenListe() {
        val hidden = setOf("en", "tr")
        val p = M3uParser.parseStream(StringReader(m3u), hidden)
        val out = ByteArrayOutputStream()
        PlaylistCacheIO.write(out, "http://a", p.items, p.epgUrls, mapOf("DE | News" to 0), p.counts, p.bytes, hidden)
        val c = PlaylistCacheIO.read(ByteArrayInputStream(out.toByteArray()), "http://a", hidden)!!
        assertEquals(p.items, c.items)
        assertEquals(p.counts, c.counts)
        assertEquals(p.bytes, c.bytes)
        assertEquals(hidden, c.hidden)
        val h = PlaylistCacheIO.readHeader(ByteArrayInputStream(out.toByteArray()), "http://a")!!
        assertTrue(h.items.isEmpty())
        assertEquals(hidden, h.hidden)
        assertEquals(mapOf("DE | News" to 0), h.order)
        assertNull(PlaylistCacheIO.readHeader(ByteArrayInputStream(out.toByteArray()), "http://b"))
    }

    @Test fun vorauswahlNachGeraetesprache() {
        val counts = mapOf("de" to 50, "en" to 80, "tr" to 20, "yu" to 5, "?" to 3)
        assertEquals(setOf("de"), LoadLogic.systemPreselect(counts, "de"))
        assertEquals(setOf("yu"), LoadLogic.systemPreselect(counts, "sr"))       // Serbisch → Ex-YU
        assertEquals(setOf("en"), LoadLogic.systemPreselect(counts, "fr"))       // nicht vorhanden → Englisch
        assertEquals(emptySet<String>(), LoadLogic.systemPreselect(mapOf("tr" to 1), "fr"))
        assertEquals("he", LoadLogic.langCodeOf("iw"))
        assertEquals("nb", LoadLogic.langCodeOf("no"))
    }

    @Test fun ladezeitHinweise() {
        assertNull(LoadFun.line(4 * 60_000L))
        assertEquals(0, LoadFun.stage(299_999))
        assertEquals(1, LoadFun.stage(5 * 60_000L))
        assertEquals("Rome wasn't built in a day.", LoadFun.line(5 * 60_000L)!!.first)
        assertEquals(LoadFun.QUOTES[1], LoadFun.line(5 * 60_000L + LoadFun.ROTATE))
        assertEquals(2, LoadFun.stage(10 * 60_000L))
        assertNull(LoadFun.line(10 * 60_000L)!!.second)
        assertEquals(3, LoadFun.stage(15 * 60_000L))
        assertTrue(LoadFun.line(3 * 3_600_000L) != null)   // auch nach Stunden kein Absturz (Rotation)
    }
}
