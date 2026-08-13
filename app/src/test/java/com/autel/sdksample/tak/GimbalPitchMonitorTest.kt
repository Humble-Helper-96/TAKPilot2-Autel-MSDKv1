package com.autel.sdksample.tak

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the erratic-pitch detector. Synthetic 10 Hz streams with a stepped clock —
 * the same feed rate as the bridge's gimbal listener. The "incident" stream reproduces
 * the 2026-08-13 signature: full-range triangle wave at ~8°/s. The "normal" streams
 * reproduce what the same day's healthy logs show: fast sweeps that end in holds.
 */
class GimbalPitchMonitorTest {

    private val stepMs = 100L   // 10 Hz

    /** Feed a stream defined by (durationMs, degreesPerSecond) segments. Returns the
     *  monitor's verdict after the last sample. */
    private fun run(
        monitor: GimbalPitchMonitor,
        startDeg: Double,
        segments: List<Pair<Long, Double>>,
        startMs: Long = 0L,
    ): Boolean {
        var t = startMs
        var pitch = startDeg
        var verdict = false
        for ((durationMs, ratePerS) in segments) {
            var elapsed = 0L
            while (elapsed < durationMs) {
                t += stepMs
                elapsed += stepMs
                pitch += ratePerS * stepMs / 1000.0
                verdict = monitor.onSample(pitch, t)
            }
        }
        return verdict
    }

    /** The incident, replayed from the logged samples of 11:41:22–11:41:59 (time offset
     *  seconds, pitch degrees). Legs of 20–55° with a reversal each 3–6 s. */
    private val incidentTrace = listOf(
        0.0 to 13.0, 3.0 to -9.9, 6.0 to 28.7, 9.0 to 10.4, 13.0 to -12.9,
        16.0 to -8.2, 19.0 to -23.7, 22.0 to 28.7, 25.0 to 28.7, 28.0 to 18.2,
        31.0 to -4.2, 34.0 to -26.1, 37.0 to 28.8,
    )

    /** Feed a (timeS, pitch) trace with linear interpolation at 10 Hz. */
    private fun replay(monitor: GimbalPitchMonitor, trace: List<Pair<Double, Double>>): Boolean {
        var verdict = false
        for (i in 1 until trace.size) {
            val (t0s, p0) = trace[i - 1]
            val (t1s, p1) = trace[i]
            var t = (t0s * 1000).toLong()
            val end = (t1s * 1000).toLong()
            while (t < end) {
                t += stepMs
                val f = (t - t0s * 1000) / (t1s * 1000 - t0s * 1000)
                verdict = monitor.onSample(p0 + (p1 - p0) * f, t)
            }
        }
        return verdict
    }

    @Test
    fun incidentReplayTrips() {
        // 37 s of the real 2026-08-13 stream must trip — the pilot took 39 s.
        assertTrue(replay(GimbalPitchMonitor(), incidentTrace))
    }

    @Test
    fun singleDeliberateSweepDoesNotTrip() {
        val m = GimbalPitchMonitor()
        // The rate-alone trap: 10°/s is FASTER than the incident, but one sweep and a
        // hold has one reversal at most.
        val verdict = run(m, 0.0, listOf(
            9_000L to -10.0,   // 0 -> -90, fast deliberate sweep
            30_000L to 0.0,    // hold
        ))
        assertFalse(verdict)
    }

    @Test
    fun threeReversalScanDoesNotTrip() {
        val m = GimbalPitchMonitor()
        // Aggressive scan: down, up, down, then hold. Three reversals stays under four.
        val verdict = run(m, 0.0, listOf(
            6_000L to -10.0,   // 0 -> -60
            6_000L to 10.0,    // -60 -> 0
            6_000L to -10.0,   // 0 -> -60
            20_000L to 0.0,    // hold
        ))
        assertFalse(verdict)
    }

    @Test
    fun hoverJitterDoesNotTrip() {
        val m = GimbalPitchMonitor()
        // ±1° stabilisation noise for 60 s: sub-minSwingDeg legs never qualify.
        var verdict = false
        var t = 0L
        for (i in 0 until 600) {
            t += stepMs
            val pitch = -30.0 + if (i % 2 == 0) 1.0 else -1.0
            verdict = m.onSample(pitch, t)
        }
        assertFalse(verdict)
    }

    @Test
    fun clearsAfterMotionStops() {
        val m = GimbalPitchMonitor()
        assertTrue(replay(m, incidentTrace))
        // Motion stops. After the window drains past the relatch hold, the verdict clears.
        val verdict = run(m, 28.8, listOf(45_000L to 0.0), startMs = 37_000L)
        assertFalse(verdict)
    }

    @Test
    fun resetDropsHistory() {
        val m = GimbalPitchMonitor()
        assertTrue(replay(m, incidentTrace))
        m.reset()
        assertFalse(m.erratic)
        // One quiet sample after reset stays quiet — no stale window.
        assertFalse(m.onSample(-30.0, 100_000L))
    }
}
