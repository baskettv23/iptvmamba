package com.iptvmamba.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pflichtanforderung: Bildschirm bleibt bei laufendem Stream an, wird sonst freigegeben. */
class AwakeLogicTest {
    private fun need(pwr: Boolean, st: Int, err: Boolean = false, fg: Boolean = true) =
        AwakeLogic.playerNeedsScreen(pwr, st, err, fg)

    @Test fun laeuftOderLaedt_bleibtWach() {
        assertTrue(need(true, AwakeLogic.STATE_READY))
        assertTrue(need(true, AwakeLogic.STATE_BUFFERING))   // Nachladen und Neuverbinden
    }

    @Test fun pause_ende_fehler_hintergrund_geben_frei() {
        assertFalse(need(false, AwakeLogic.STATE_READY))      // Pause / Sleep-Timer
        assertFalse(need(true, AwakeLogic.STATE_ENDED))       // Ende
        assertFalse(need(true, AwakeLogic.STATE_IDLE))        // gestoppt / nach Fehler
        assertFalse(need(true, AwakeLogic.STATE_READY, err = true))
        assertFalse(need(true, AwakeLogic.STATE_READY, fg = false))  // App im Hintergrund
    }

    @Test fun ablauf_mitAbbruchUndReconnect() {
        // Start -> läuft -> Netzabbruch (Fehler) -> Reconnect (Buffering) -> läuft -> Pause
        val steps = listOf(
            Triple(true, AwakeLogic.STATE_BUFFERING, false) to true,
            Triple(true, AwakeLogic.STATE_READY, false) to true,
            Triple(true, AwakeLogic.STATE_IDLE, true) to false,
            Triple(true, AwakeLogic.STATE_BUFFERING, false) to true,
            Triple(true, AwakeLogic.STATE_READY, false) to true,
            Triple(false, AwakeLogic.STATE_READY, false) to false
        )
        for ((s, want) in steps) assertEquals(want, need(s.first, s.second, s.third))
    }

    @Test fun mehrereGruende_keinerSchaltetDemAnderenAus() {
        val t = AwakeTags()
        assertTrue(t.set("player", true))
        assertTrue(t.set("playlist", true))
        assertTrue(t.set("playlist", false))   // Playlist fertig, Player läuft noch
        assertFalse(t.set("player", false))
        assertFalse(t.active)
        assertFalse(t.set("player", false))    // doppeltes Freigeben ist harmlos
    }
}
