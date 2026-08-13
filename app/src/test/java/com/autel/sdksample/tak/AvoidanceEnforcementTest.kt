package com.autel.sdksample.tak

import com.autel.sdksample.tak.AvoidanceEnforcement.Outcome
import com.autel.sdksample.tak.AvoidanceEnforcement.Switch
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the enforcement decision core. Pure logic: desired versus cached aircraft
 * state in, verify/retry/give-up out. The 2026-08-13 LANDING_PROTECT timeout is the
 * incident these rules pin.
 */
class AvoidanceEnforcementTest {

    private val allOn = mapOf(Switch.SYSTEM to true, Switch.RTH to true, Switch.LANDING to true)

    private fun actual(sys: Boolean? = true, rth: Boolean? = true, landing: Boolean? = true) =
        mapOf(Switch.SYSTEM to sys, Switch.RTH to rth, Switch.LANDING to landing)

    @Test
    fun allMatchingVerifies() {
        assertEquals(Outcome.Verified,
            AvoidanceEnforcement.decide(allOn, actual(), attempt = 1, maxAttempts = 3))
    }

    @Test
    fun mismatchRetriesOnlyThatSwitch() {
        // The beep-burst rule: never rewrite a switch that already matches.
        assertEquals(Outcome.Retry(setOf(Switch.LANDING)),
            AvoidanceEnforcement.decide(allOn, actual(landing = false), attempt = 1, maxAttempts = 3))
    }

    @Test
    fun exhaustedAttemptsGiveUp() {
        assertEquals(Outcome.GiveUp(setOf(Switch.LANDING)),
            AvoidanceEnforcement.decide(allOn, actual(landing = false), attempt = 4, maxAttempts = 3))
    }

    @Test
    fun unknownCacheIsNotAMatch() {
        // A dark feed must not read as success: unknown is its own state.
        assertEquals(Outcome.Retry(setOf(Switch.LANDING)),
            AvoidanceEnforcement.decide(allOn, actual(landing = null), attempt = 2, maxAttempts = 3))
        assertEquals(Outcome.GiveUp(setOf(Switch.LANDING)),
            AvoidanceEnforcement.decide(allOn, actual(landing = null), attempt = 4, maxAttempts = 3))
    }

    @Test
    fun retryThatTookVerifiesOnNextPass() {
        assertEquals(Outcome.Retry(setOf(Switch.LANDING)),
            AvoidanceEnforcement.decide(allOn, actual(landing = false), attempt = 1, maxAttempts = 3))
        assertEquals(Outcome.Verified,
            AvoidanceEnforcement.decide(allOn, actual(), attempt = 2, maxAttempts = 3))
    }
}
