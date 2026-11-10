package com.iptvmamba.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Buffer byte limit grows with the heap, retry rules of the player. */
class PlayerBufferTest {
    private val mb = 1024 * 1024

    @Test fun byteLimit_waechstMitHeap() {
        // 256 MB heap (1 GB box): 12 % = 30 MB instead of 14 MB
        val a = BufferLogic.byteLimit(14 * mb, 256L * mb)
        assertTrue(a > 14 * mb && a < 32 * mb)
        // big heap: capped at 64 MB
        assertEquals(64 * mb, BufferLogic.byteLimit(28 * mb, 2048L * mb))
        // never below the planned value
        assertEquals(28 * mb, BufferLogic.byteLimit(28 * mb, 64L * mb))
        // no limit of our own stays no limit
        assertEquals(-1, BufferLogic.byteLimit(-1, 512L * mb))
    }

    @Test fun retry_nurWoSinnvoll() {
        assertTrue(IptvLoadErrorPolicy.isFinalStatus(403))
        assertTrue(IptvLoadErrorPolicy.isFinalStatus(404))
        assertFalse(IptvLoadErrorPolicy.isFinalStatus(408))
        assertFalse(IptvLoadErrorPolicy.isFinalStatus(429))
        assertFalse(IptvLoadErrorPolicy.isFinalStatus(500))
        assertFalse(IptvLoadErrorPolicy.isFinalStatus(512))
    }
}
