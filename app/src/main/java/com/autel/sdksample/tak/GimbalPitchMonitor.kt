package com.autel.sdksample.tak

/**
 * Detects erratic gimbal pitch motion from the samples the bridge feeds it.
 *
 * **This class is fed. It does not subscribe.** The bridge's gimbal angle listener calls
 * [onSample] with each pitch value it already receives (standing rule 2, the
 * [FlightPathLogger] pattern). No SDK imports, no Android imports — the caller passes the
 * clock, so the JVM tests can step time.
 *
 * **The discriminator is amplitude PLUS reversal count, not rate.** On the 2026-08-13
 * incident flight the pitch swept the full range (-26° to +28.8°) at ~8°/s with continuous
 * reversals for more than 39 s before the pilot reacted. Normal flying on the same logs
 * shows deliberate dial sweeps up to ~10°/s — FASTER than the incident — but each sweep ends
 * in a hold, so a window never collects both large accumulated travel AND many direction
 * reversals. Erratic motion collects both.
 *
 * Threshold rationale, from the incident logs (legs of 20–55° with a reversal each
 * 3–6 s):
 * - A 25 s incident window holds 4–6 closed reversals and ≈150–190° of travel. The
 *   defaults (100° / 4) trip inside that window with margin on travel.
 * - A single deliberate sweep gives ≤ ~120° travel but only 1 reversal; an aggressive
 *   down-up-down scan gives 2 closed reversals. Both stay under [minReversals].
 * - [minSwingDeg] = 15° is far above stabilisation jitter (sub-degree) and framing
 *   corrections, and below the incident's smallest qualifying legs — hover noise
 *   cannot count.
 */
class GimbalPitchMonitor(
    private val windowMs: Long = 25_000L,
    private val minTravelDeg: Double = 100.0,
    private val minReversals: Int = 4,
    private val minSwingDeg: Double = 15.0,
    private val relatchHoldMs: Long = 10_000L,
) {
    private class Sample(val timeMs: Long, val pitchDeg: Double)

    private val samples = ArrayDeque<Sample>()
    private var trippedAtMs = 0L
    private var lastStats = "no samples"

    /** True while the current window looks erratic (with hysteresis, see [onSample]). */
    var erratic: Boolean = false
        private set

    /**
     * Feed one pitch sample (degrees, app sign convention) and get the current judgement.
     * Call from ONE thread — the bridge's gimbal callback.
     */
    fun onSample(pitchDeg: Double, nowMs: Long): Boolean {
        samples.addLast(Sample(nowMs, pitchDeg))
        while (samples.isNotEmpty() && samples.first().timeMs < nowMs - windowMs) {
            samples.removeFirst()
        }

        // Walk the window as monotonic legs. A direction change only closes a leg (and
        // counts one reversal) when the finished leg spans at least minSwingDeg — the
        // jitter gate. Travel is the sum of qualifying legs plus the open leg.
        var travel = 0.0
        var reversals = 0
        var legStart = samples.first().pitchDeg
        var legEnd = legStart
        var legDir = 0   // -1 down, +1 up, 0 not yet known
        for (s in samples) {
            val d = s.pitchDeg - legEnd
            val dir = when { d > 0 -> 1; d < 0 -> -1; else -> 0 }
            if (dir != 0 && legDir != 0 && dir != legDir) {
                val span = kotlin.math.abs(legEnd - legStart)
                if (span >= minSwingDeg) {
                    travel += span
                    reversals++
                    legStart = legEnd
                } // A small wiggle folds into the current leg: keep legStart, flip direction.
                legDir = dir
            } else if (dir != 0) {
                legDir = dir
            }
            legEnd = s.pitchDeg
        }
        val openSpan = kotlin.math.abs(legEnd - legStart)
        if (openSpan >= minSwingDeg) travel += openSpan
        lastStats = "travel=%.0f° reversals=%d window=%ds".format(
            travel, reversals, windowMs / 1000)

        val trips = travel >= minTravelDeg && reversals >= minReversals
        if (trips) {
            if (!erratic) trippedAtMs = nowMs
            erratic = true
        } else if (erratic) {
            // Hysteresis: hold the verdict for relatchHoldMs, then clear only when the
            // window has really drained (half the trip threshold). This stops the flag
            // strobing at the boundary while the banner's own hold rides out.
            val heldLongEnough = nowMs - trippedAtMs >= relatchHoldMs
            if (heldLongEnough && travel < minTravelDeg / 2) erratic = false
        }
        return erratic
    }

    /** One-line summary of the current window, for the transition log. */
    fun stats(): String = lastStats

    /** Product cycle — drop all history so a stale verdict cannot greet the next connect. */
    fun reset() {
        samples.clear()
        erratic = false
        trippedAtMs = 0L
        lastStats = "no samples"
    }
}
