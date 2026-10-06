package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the Emergency Broadcast audit line format (v2.3.9). This is the record a reviewer
 * reads after the fact to see who overrode the video split and when — see
 * [EmergencyBroadcastLog]'s class doc for why it exists as its own file rather than a verbose
 * AppLog line.
 */
class EmergencyBroadcastLogFormatTest {

    // A whole UTC second, so the formatted timestamp is exact and stable across runs.
    private val nowMs = 1_760_000_000_000L // 2025-10-09T08:53:20Z

    @Test
    fun enabledLineCarriesCallsignAndExpiry() {
        val expiresAt = nowMs + 15 * 60 * 1000L
        val line = EmergencyBroadcastLog.formatLine("enabled", "TAKPilot2-EVO2", nowMs, expiresAt)
        assertTrue(line.startsWith("2025-10-09T08:53:20Z enabled callsign=TAKPilot2-EVO2"))
        assertTrue("expiresAt" in line)
        assertTrue(line.endsWith("\n"))
    }

    @Test
    fun cancelledLineCarriesNoExpiry() {
        val line = EmergencyBroadcastLog.formatLine("cancelled", "TAKPilot2-EVO2", nowMs)
        assertEquals("2025-10-09T08:53:20Z cancelled callsign=TAKPilot2-EVO2\n", line)
        assertFalse("expiresAt" in line)
    }

    @Test
    fun expiredLineCarriesNoExpiry() {
        val line = EmergencyBroadcastLog.formatLine("expired", "TAKPilot2-EVO2", nowMs)
        assertEquals("2025-10-09T08:53:20Z expired callsign=TAKPilot2-EVO2\n", line)
    }

    @Test
    fun resetOnReconnectLineCarriesNoExpiry() {
        val line = EmergencyBroadcastLog.formatLine("reset-on-reconnect", "TAKPilot2-EVO2", nowMs)
        assertEquals("2025-10-09T08:53:20Z reset-on-reconnect callsign=TAKPilot2-EVO2\n", line)
    }

    @Test
    fun oneLinePerEventAndNothingElse() {
        // Exactly one '\n', at the end — this file is meant to be tailed/grepped one event per
        // line; a stray newline mid-line would split one event across two lines.
        val line = EmergencyBroadcastLog.formatLine("enabled", "X", nowMs, nowMs + 1000)
        assertEquals(1, line.count { it == '\n' })
        assertTrue(line.endsWith("\n"))
    }
}
