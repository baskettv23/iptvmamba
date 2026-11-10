package com.iptvmamba.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistLogicTest {
    @Test fun adressen() {
        assertEquals("http://a.tv/get.php?x=1", PlaylistLogic.normalizeUrl("  http://a.tv/get.php?x=1 "))
        assertEquals("https://a.tv:8080/list.m3u", PlaylistLogic.normalizeUrl("https://a.tv:8080/list.m3u"))
        assertEquals("http://u:p@a.tv/x", PlaylistLogic.normalizeUrl("http://u:p@a.tv/x"))
        assertNull(PlaylistLogic.normalizeUrl(""))
        assertNull(PlaylistLogic.normalizeUrl("a.tv/list.m3u"))
        assertNull(PlaylistLogic.normalizeUrl("file:///sdcard/x.m3u"))
        assertNull(PlaylistLogic.normalizeUrl("javascript:alert(1)"))
        assertNull(PlaylistLogic.normalizeUrl("http://"))
        assertNull(PlaylistLogic.normalizeUrl("http://a b.tv/x"))
    }

    @Test fun xtreamKodiertSonderzeichen() {
        val u = PlaylistLogic.xtreamUrl("server.tv:8080/", " max ", "p&ss#1+2")!!
        assertEquals("http://server.tv:8080/get.php?username=max&password=p%26ss%231%2B2&type=m3u_plus&output=ts", u)
        assertTrue(PlaylistLogic.xtreamUrl("https://s.tv", "a", "b")!!.startsWith("https://s.tv/get.php"))
        assertNull(PlaylistLogic.xtreamUrl("", "a", "b"))
        assertNull(PlaylistLogic.xtreamUrl("s.tv", "", "b"))
        assertNull(PlaylistLogic.xtreamUrl("s.tv", "a", " "))
    }

    @Test fun fehlertexteZeigenNieDieAdresse() {
        val secret = "http://s.tv/get.php?username=max&password=geheim"
        val list = listOf(
            IllegalArgumentException("Expected URL scheme but was $secret"),
            java.net.UnknownHostException("Unable to resolve host s.tv"),
            java.net.SocketTimeoutException("timeout $secret"),
            java.io.IOException("unexpected end of stream on $secret"),
            RuntimeException(secret)
        )
        for (e in list) assertFalse(PlaylistLogic.friendlyError(e).contains("geheim"))
        assertEquals("Keine Sender in der Playlist gefunden.", PlaylistLogic.friendlyError(PlaylistError("Keine Sender in der Playlist gefunden.")))
        assertTrue(PlaylistLogic.httpError(403).contains("403"))
    }

    @Test fun pinSperreNachFuenfFehlversuchen() {
        val g = PinGuard(maxFails = 5, lockMs = 60_000)
        var now = 1_000_000L
        repeat(4) { assertFalse(g.record(false, now)) }
        assertEquals(0L, g.waitSec(now))
        assertTrue(g.record(false, now))          // 5. Fehlversuch sperrt
        assertEquals(60L, g.waitSec(now))
        now += 30_000; assertEquals(30L, g.waitSec(now))
        now += 30_000; assertEquals(0L, g.waitSec(now))
        assertFalse(g.record(true, now))
    }
}

/** Titel aus der Playlist: HTML-Zeichen und „|EN|“-Kennung (Runde 12). */
class TitleTextTest {
    @Test fun htmlZeichen() {
        assertEquals("Asterix & Obelix: The Big Fight", TitleText.decode("Asterix &amp; Obelix: The Big Fight"))
        assertEquals("Agatha Christie's Seven Dials", TitleText.decode("Agatha Christie&#039;s Seven Dials"))
        assertEquals("Agatha Christie's", TitleText.decode("Agatha Christie&amp;#039;s"))     // doppelt kodiert
        assertEquals("Café \"Größe\"", TitleText.decode("Caf&eacute; &quot;Gr&ouml;&szlig;e&quot;"))
        assertEquals("A'B", TitleText.decode("A&#x27;B"))
        assertEquals("Tom & Jerry", TitleText.decode("Tom & Jerry"))                             // kein Eintrag: bleibt
        assertEquals("a &foo; b", TitleText.decode("a &foo; b"))
        assertEquals("x &#0; y", TitleText.decode("x &#0; y"))                                   // Steuerzeichen nicht einsetzen
    }

    @Test fun kennungAmAnfang() {
        assertEquals("When The War Is Over", TitleText.title("|EN| When The War Is Over"))
        assertEquals("When The War Is Over", TitleText.title("EN| When The War Is Over"))
        assertEquals("Agatha Christie's Seven Dials", TitleText.title("EN| Agatha Christie&#039;s Seven Dials"))
        assertEquals("Dark", TitleText.title("[DE] Dark"))
        assertEquals("CSI: Miami", TitleText.title("CSI: Miami"))                                 // Doppelpunkt bleibt
        assertEquals("EN|", TitleText.title("EN|"))                                               // nichts übrig: unverändert
        assertEquals("Rock | Pop", TitleText.title("Rock | Pop"))
    }

    @Test fun serieBehaeltIdUndBekommtSchoenenTitel() {
        val m3u = """#EXTM3U
#EXTINF:-1 group-title="|EN| NEW SERIES 2026",|EN| When The War Is Over S01 E01
http://h/series/u/p/1.mkv
#EXTINF:-1 group-title="|EN| NEW SERIES 2026",|EN| When The War Is Over S01 E02
http://h/series/u/p/2.mkv
#EXTINF:-1 group-title="|EN| MOVIES",|EN| Asterix &amp; Obelix (2025)
http://h/movie/u/p/9.mkv
"""
        val items = M3uParser.parseStream(java.io.StringReader(m3u)).items
        val s = items.single { it.kind == Kind.SERIES }
        assertEquals("When The War Is Over", s.title)
        assertEquals(2, s.episodes.size)
        assertTrue(s.id.startsWith("s_"))
        assertEquals("Asterix & Obelix", items.single { it.kind == Kind.MOVIE }.title)
    }
}

/** Runde 14: Doppelte Titel und Kurzfassung der Beschreibung. */
class Runde14ListTest {
    @Test fun ersterJeSchluessel() {
        assertEquals(listOf("a1", "b1", "c1"), PlaylistLogic.firstPerKey(listOf("a1", "b1", "a2", "c1", "b2")) { it.take(1) })
        assertEquals(emptyList<String>(), PlaylistLogic.firstPerKey(emptyList<String>()) { it })
    }

    @Test fun kurzfassung() {
        assertEquals("Kurz.", TitleText.short("  Kurz.  "))
        val long = (1..80).joinToString(" ") { "Wort$it" }
        val s = TitleText.short(long)
        assertTrue(s.length <= 250)
        assertTrue(s.endsWith("…"))
        assertFalse(s.contains("  "))
        // an einer Wortgrenze gekürzt
        assertTrue(s.removeSuffix("…").split(" ").all { it.startsWith("Wort") && it.length > 4 })
    }
}
