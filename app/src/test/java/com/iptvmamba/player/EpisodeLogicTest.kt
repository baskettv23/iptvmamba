package com.iptvmamba.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/** Lange Staffeln (One Piece), Weiterschauen-Regeln, Folgen über 999. */
class EpisodeLogicTest {
    private fun eps(season: Int, nums: IntRange) = nums.map { Episode(season, it, "Episode $it", "http://h/series/$season/$it.mkv") }

    // One Piece laut TMDB (gekürzt): 61, 16, 14, 39 Folgen …
    private val op = listOf(
        EpisodeLogic.TmdbSeason(0, 20, "Specials"),
        EpisodeLogic.TmdbSeason(1, 61, "East Blue"),
        EpisodeLogic.TmdbSeason(2, 16, "Season 2"),
        EpisodeLogic.TmdbSeason(3, 14, "Chopper Kingdom"),
        EpisodeLogic.TmdbSeason(4, 39, "Alabasta")
    )

    @Test fun kurzeStaffel_einAbschnitt_wieBisher() {
        val g = EpisodeLogic.groups(eps(1, 1..10), 1, 3, emptyList())
        assertEquals(1, g.size)
        assertEquals(EpisodeLogic.Group(0, 9, 1, 10, 1, 0, null), g[0])
    }

    @Test fun langeStaffel_ohneTmdb_gleichmaessigeBloecke() {
        val e = eps(1, 1..130)
        val g = EpisodeLogic.groups(e, 1, 1, emptyList())
        assertEquals(listOf("1–26", "27–52", "53–78", "79–104", "105–130"), g.map { EpisodeLogic.label(it) })
        assertEquals(3, EpisodeLogic.groupOf(g, 100))
        assertEquals(0, EpisodeLogic.groupOf(g, 0))
        // Monster: 62 Folgen = drei Abschnitte statt 50 + 12
        val m = EpisodeLogic.groups(eps(1, 1..62), 1, 1, emptyList())
        assertEquals(listOf("1–21", "22–42", "43–62"), m.map { EpisodeLogic.label(it) })
    }

    @Test fun onePiece_alleInStaffel1_wirdAufTmdbStaffelnVerteilt() {
        val e = eps(1, 1..140)   // 61+16+14+39 = 130 bei TMDB, 10 neuere Folgen
        assertTrue(EpisodeLogic.absolute(e, 1, op))
        val g = EpisodeLogic.groups(e, 1, 1, op)
        assertEquals(listOf("1–61 · East Blue", "62–77", "78–91 · Chopper Kingdom", "92–130 · Alabasta", "131–140"), g.map { EpisodeLogic.label(it) })
        // Folge 62 = TMDB Staffel 2, Folge 1
        val s2 = g[1]
        assertEquals(2, s2.tmdbSeason)
        assertEquals(1, EpisodeLogic.tmdbNum(s2, 62))
        assertEquals(39, EpisodeLogic.tmdbNum(g[3], 130))
        // neuer als TMDB: keine Infos-Staffel
        assertEquals(-1, g[4].tmdbSeason)
    }

    @Test fun riesigeTmdbStaffel_wirdZusaetzlichGeteilt() {
        val big = listOf(EpisodeLogic.TmdbSeason(1, 10, null), EpisodeLogic.TmdbSeason(2, 250, "Wano"))
        val g = EpisodeLogic.groups(eps(1, 1..260), 1, 1, big)
        assertEquals("1–10", EpisodeLogic.label(g[0]))
        val w = g.drop(1)
        assertTrue(w.all { it.to - it.from + 1 <= EpisodeLogic.BLOCK })
        assertEquals(11, w.first().firstNum); assertEquals(260, w.last().lastNum)
        assertTrue(g.drop(1).all { it.tmdbSeason == 2 && it.offset == 10 })
    }

    @Test fun eigeneStaffelnMitFortlaufenderNummer() {
        // Anbieter: Staffel 2 beginnt mit Folge 62
        val g = EpisodeLogic.groups(eps(2, 62..77), 2, 3, op)
        assertEquals(1, g.size)
        assertEquals(61, g[0].offset)
        assertEquals(1, EpisodeLogic.tmdbNum(g[0], 62))
        // normale Zählung bleibt unverändert
        assertEquals(0, EpisodeLogic.groups(eps(2, 1..16), 2, 3, op)[0].offset)
        // mehrere Staffeln beim Anbieter: nie quer verteilen
        assertFalse(EpisodeLogic.absolute(eps(1, 1..140), 2, op))
    }

    @Test fun gehezuFolge() {
        val e = eps(1, 1..20).filter { it.num != 7 }
        assertEquals(4, EpisodeLogic.indexOfNum(e, 5))
        assertEquals(6, e[EpisodeLogic.indexOfNum(e, 7)].num)     // 7 fehlt → nächstgelegene
        assertEquals(20, e[EpisodeLogic.indexOfNum(e, 999)].num)
        assertEquals(-1, EpisodeLogic.indexOfNum(emptyList(), 3))
    }

    @Test fun gehezuFolgeGesamtnummer() {
        // Grey's Anatomy: Staffeln mit eigener Zählung (9, 27, 25, 24 Folgen)
        val all = eps(1, 1..9) + eps(2, 1..27) + eps(3, 1..25) + eps(4, 1..24)
        assertEquals(3 to 5, EpisodeLogic.locate(all, 3, 5))        // gibt es in der Staffel: dort
        assertEquals(2 to 26, EpisodeLogic.locate(all, 1, 26))      // nur in Staffel 2 (und 3): erste Staffel mit der Nummer
        // 100 gibt es nirgends: Gesamtnummer 100 = 9 + 27 + 25 = 61, dann Staffel 4 Folge 24 -> 85, also über 85 Folgen
        assertNull(EpisodeLogic.locate(all, 1, 100))
        val big = all + eps(5, 1..24)                               // 109 Folgen
        assertEquals(5 to 15, EpisodeLogic.locate(big, 1, 100))     // 85 + 15
        assertNull(EpisodeLogic.locate(all, 1, 0))
        assertNull(EpisodeLogic.locate(emptyList(), 1, 3))
    }

    @Test fun tmdbNamen() {
        assertNull(EpisodeLogic.niceName("Season 4"))
        assertNull(EpisodeLogic.niceName("Staffel 12"))
        assertEquals("East Blue", EpisodeLogic.niceName(" East Blue "))
        assertEquals(mapOf(1 to 1, 2 to 62, 3 to 78, 4 to 92), EpisodeLogic.starts(op))
    }

    @Test fun gesehenUndFortschritt() {
        assertFalse(ResumeLogic.finished(10_000, 0))
        assertTrue(ResumeLogic.finished(1_380_000, 1_440_000))      // 24-min-Folge, letzte Minute (Abspann)
        assertTrue(ResumeLogic.finished(930_000, 1_000_000))
        assertFalse(ResumeLogic.finished(400_000, 1_440_000))
        assertFalse(ResumeLogic.finished(30_000, 100_000))          // kurz: nicht nach 30 s „gesehen“
        val m = linkedMapOf(ResumeLogic.key(1, 1050) to ResumeLogic.EpPos(1400, 1440), ResumeLogic.key(2, 3) to ResumeLogic.EpPos(60, 2700))
        val back = ResumeLogic.decode(ResumeLogic.encode(m))
        assertEquals(m, back)
        assertTrue(back.getValue(ResumeLogic.key(1, 1050)).done)
        assertFalse(back.getValue(ResumeLogic.key(2, 3)).done)
        assertEquals(0, ResumeLogic.decode("kaputt;1:2").size)
        assertEquals(1 to 1050, ResumeLogic.seasonEp("One Piece S1E1050"))
        assertNull(ResumeLogic.seasonEp("Matrix"))
    }

    @Test fun folgenUeber999_werdenNichtAbgeschnitten() {
        val m3u = """#EXTM3U
#EXTINF:-1 group-title="DE | Anime",DE - One Piece S01 E105
http://h/series/u/p/105.mkv
#EXTINF:-1 group-title="DE | Anime",DE - One Piece S01 E1050
http://h/series/u/p/1050.mkv
#EXTINF:-1 group-title="DE | Anime",DE - One Piece S01E1051
http://h/series/u/p/1051.mkv
"""
        val op = M3uParser.parseStream(StringReader(m3u)).items.single()
        assertEquals(listOf(105, 1050, 1051), op.episodes.map { it.num })
        assertTrue(op.episodes.all { it.variants.isEmpty() })   // 1050 ist keine zweite Quelle von 105
    }
}

/** Kompakte Folgenliste: verhält sich genau wie die normale Liste, braucht aber viel weniger Speicher. */
class EpisodeTableTest {
    private fun ep(s: Int, n: Int, url: String, q: String? = null, v: List<Variant> = emptyList()) = Episode(s, n, EpTitles.of(n), url, v, q)

    @org.junit.Test fun gleichWieVorher() {
        val v = listOf(Variant("http://h/series/u/p/9.mkv", "DE · 4K", "4K", "DE"), Variant("http://h/x/9b.mkv", "EN · HD", "HD", "EN"))
        val list = listOf(
            ep(1, 1, "http://h/series/u/p/1.mkv", "FHD"),
            ep(1, 2, "http://h/series/u/p/2.mkv"),
            ep(2, 1050, "http://other/series/u/p/1050.mp4", "HD"),
            ep(2, 1051, "http://h/series/u/p/9.mkv", "4K", v)
        )
        val t = EpisodeTable.of(list)
        org.junit.Assert.assertTrue(t is EpisodeTable)
        org.junit.Assert.assertEquals(list, t)
        org.junit.Assert.assertEquals(list.map { it.url }, t.map { it.url })
        org.junit.Assert.assertEquals(v, t[3].variants)
        org.junit.Assert.assertEquals(2, (t as EpisodeTable).seasonCount)
        org.junit.Assert.assertEquals(listOf(1050, 1051), t.filter { it.season == 2 }.map { it.num })
        org.junit.Assert.assertSame(t, EpisodeTable.of(t))
    }

    @org.junit.Test fun ungewoehnlichesBleibtUnveraendert() {
        val odd = listOf(Episode(1, 1, "Pilot", "http://h/1.mkv"))          // eigener Titel
        org.junit.Assert.assertSame(odd, EpisodeTable.of(odd))
        val q = listOf(ep(1, 1, "http://h/1.mkv", "8K"))                       // unbekannte Qualität
        org.junit.Assert.assertSame(q, EpisodeTable.of(q))
        org.junit.Assert.assertEquals(emptyList<Episode>(), EpisodeTable.of(emptyList()))
        org.junit.Assert.assertEquals("http://h/a/", EpisodeTable.prefixOf("http://h/a/b.mkv"))
        org.junit.Assert.assertEquals("", EpisodeTable.prefixOf("ohne-schraegstrich"))
    }

    @org.junit.Test fun staffelZahlFuerKarten() {
        val item = Item("s_x", Kind.SERIES, "X", null, "", null, "g", EpisodeTable.of(listOf(ep(1, 1, "http://h/1"), ep(3, 1, "http://h/2"))))
        org.junit.Assert.assertEquals(2, seasonCount(item))
        val plain = item.copy(episodes = listOf(Episode(1, 1, "Pilot", "u"), Episode(2, 1, "Pilot", "u")))
        org.junit.Assert.assertEquals(2, seasonCount(plain))
    }

    @Test fun merktAndereFolgeAuchBeiKurzemReinschauen() {
        // war bei Folge 40 gespeichert, jetzt 10 Sekunden in Folge 42: muss gemerkt werden
        assertTrue(ResumeLogic.shouldSave(true, 1, 40, 1, 42, 10_000, false))
    }

    @Test fun kurzesReinschauenInDieselbeFolgeAendertNichts() {
        assertFalse(ResumeLogic.shouldSave(true, 1, 40, 1, 40, 10_000, false))
        assertFalse(ResumeLogic.shouldSave(false, 0, 0, 1, 3, 10_000, false))
        assertTrue(ResumeLogic.shouldSave(false, 0, 0, 1, 3, 45_000, false))
        assertTrue(ResumeLogic.shouldSave(true, 1, 3, 1, 3, 10_000, true))
        // Filme (ohne Folge): kurz = nichts ändern
        assertFalse(ResumeLogic.shouldSave(true, 0, 0, 0, 0, 10_000, false))
    }
}
