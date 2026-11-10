package com.iptvmamba.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Eigene Senderlisten und Erinnerungen an Sendungen. */
class LiveLogicTest {
    @Test fun senderlistenAnlegenUndFuellen() {
        var l = ChannelListLogic.create(emptyList(), "  Sport\t", "a")
        l = ChannelListLogic.create(l, "")
        assertEquals(listOf(1, 2), l.map { it.id })
        assertEquals("Sport", l[0].name)
        assertEquals("Senderliste 2", l[1].name)
        l = ChannelListLogic.add(l, 1, "b")
        l = ChannelListLogic.add(l, 1, "a")                      // kein Doppel
        assertEquals(listOf("a", "b"), l[0].ids)
        l = ChannelListLogic.move(l, 1, "b", -1)
        assertEquals(listOf("b", "a"), l[0].ids)
        l = ChannelListLogic.move(l, 1, "b", -1)                 // schon oben: bleibt
        assertEquals(listOf("b", "a"), l[0].ids)
        l = ChannelListLogic.remove(l, 1, "b")
        assertEquals(listOf("a"), l[0].ids)
        l = ChannelListLogic.rename(l, 2, "Kinder")
        assertEquals("Kinder", l[1].name)
        assertEquals(l, ChannelListLogic.rename(l, 2, "   "))  // leerer Name ändert nichts
        assertEquals(listOf(1), ChannelListLogic.delete(l, 2).map { it.id })
    }

    @Test fun senderlisteAuflösenUndSpeichern() {
        val l = ChList(3, "Nachrichten", listOf("x", "weg", "y"))
        assertEquals(listOf("X", "Y"), ChannelListLogic.resolve(l, mapOf("x" to "X", "y" to "Y")))   // fehlender Sender übersprungen
        assertEquals("L:3", ChannelListLogic.key(l))
        assertEquals(3, ChannelListLogic.idOfKey("L:3"))
        assertNull(ChannelListLogic.idOfKey("Sport"))
        val all = listOf(l, ChList(4, "Leer", emptyList()))
        assertEquals(all, ChannelListLogic.decode(ChannelListLogic.encode(all)))
        assertEquals(emptyList<ChList>(), ChannelListLogic.decode("kaputt"))
    }

    @Test fun erinnerungen() {
        val r = Reminder("ard", "Das Erste", "Tagesschau", 10_000, 10_900)
        val r2 = Reminder("zdf", "ZDF", "heute", 20_000, 21_000)
        var l = ReminderLogic.toggle(emptyList(), r2)
        l = ReminderLogic.toggle(l, r)
        assertEquals(listOf(r, r2), l)                           // nach Beginn sortiert
        assertEquals(listOf(r2), ReminderLogic.toggle(l, r))      // nochmal = löschen
        // 5 Minuten Vorlauf
        assertTrue(ReminderLogic.due(l, 9_600, 300).isEmpty())
        assertEquals(listOf(r), ReminderLogic.due(l, 9_700, 300))
        assertEquals(listOf(r), ReminderLogic.due(l, 10_500, 300))  // läuft schon: noch fällig
        assertTrue(ReminderLogic.due(l, 10_900, 300).isEmpty())    // vorbei
        assertEquals(9_700L, ReminderLogic.nextAlarm(l, 5_000, 300))
        assertEquals(10_200L, ReminderLogic.nextAlarm(l, 10_200, 300))   // überfällig → sofort
        assertNull(ReminderLogic.nextAlarm(l, 30_000, 300))
        assertEquals(listOf(r2), ReminderLogic.prune(l, 11_000))
        assertTrue(ReminderLogic.canRemind(10_000, 9_000))
        assertFalse(ReminderLogic.canRemind(10_000, 10_000))
        val tricky = listOf(Reminder("a", "Sen\tder", "Titel\nmit Umbruch", 1, 2))
        assertEquals(listOf(Reminder("a", "Sen der", "Titel mit Umbruch", 1, 2)), ReminderLogic.decode(ReminderLogic.encode(tricky)))
    }
}
