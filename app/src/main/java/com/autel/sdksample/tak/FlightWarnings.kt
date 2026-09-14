package com.autel.sdksample.tak

import com.autel.common.flycontroller.FlyControllerStatus
import com.autel.common.flycontroller.FlyLimitAreaWarning
import com.autel.common.flycontroller.FlyMode
import com.autel.common.flycontroller.MainFlyState
import com.taklite.util.AppLog

/**
 * Aircraft warnings for the flight screen (v1.5.9, plan §4). These are the messages that
 * Explorer showed and TAKPilot2 did not. In field event 2 the controller made warning sounds
 * for compass interference, and the screen showed nothing to explain them.
 *
 * **This object is fed. It does not subscribe.** The same contract as [FlightPathLogger]:
 * the bridge's fly-controller callback calls [onStatus] with the `FlyControllerStatus` that
 * it already receives. No new SDK listeners (standing rules 1 and 2). The call is cheap:
 * compute a small set, log only the changes.
 *
 * **The display policy lives in one place.** [compute] is the full policy — one ordered
 * block, red before amber, worst first. To change the policy after field feedback, edit that
 * one function. Each signal that is not displayed still reaches the log through the
 * transition lines. Thus a flight log can answer "must X be on screen?" before it is code.
 *
 * The flight screen polls [display] from its 500 ms HUD tick. The source repeats at 2 Hz, so
 * the display reacts to CHANGES and holds each warning for [HOLD_MS]: a warning stays
 * readable when the state flickers, and a worse warning takes the banner immediately. One
 * warning shows at a time — the worst — with a "+N" count when more are active.
 */
object FlightWarnings {

    /** Priority order IS declaration order: worse first. Red = act now, amber = know it.
     *  `banner = false` keeps a warning in the ACTIVE set — logged with timestamps like every
     *  other transition — but never on screen. */
    enum class Warning(val red: Boolean, val label: String, val banner: Boolean = true) {
        COMPASS(true, "COMPASS INTERFERENCE"),
        GPS_LOST(true, "GPS LOST — AIRCRAFT DRIFTS"),
        FC_HOT(true, "FLIGHT CONTROLLER HOT"),
        BATTERY_CRITICAL(true, "BATTERY CRITICAL"),
        /** Log-only (operator, 2026-08-07): the fleet flies public-safety missions under an
         *  FAA exception, so "NO-FLY ZONE" on the banner is wrong for the pilot acting
         *  lawfully inside one — and a red banner that is routinely correct to ignore would
         *  teach pilots to ignore red. The log line stays: it is the record of WHEN the
         *  aircraft operated in such a zone, which an exception-holder may need after the
         *  fact. The aircraft's own geofence behaviour is not changed by this either way. */
        NO_FLY_ZONE(true, "NO-FLY ZONE", banner = false),
        RTH_BATTERY(false, "RETURNING HOME — LOW BATTERY"),
        RTH_RC_LOST(false, "RETURNING HOME — SIGNAL LOST"),
        RTH_RANGE(false, "RETURNING HOME — RANGE LIMIT"),
        /** Any other go-home the aircraft entered. ⚠ NO REASON IS CLAIMED, because the SDK
         *  gives none for these modes and a guessed reason on a safety banner is worse than
         *  no reason — see [returningHome]. */
        RTH_OTHER(false, "RETURNING HOME"),
        /** Set by AutelAvoidance when connect-time enforcement could not verify a switch
         *  against the aircraft. On flight 2026-08-13 the LANDING_PROTECT write timed out
         *  with no retry, and the aircraft flew unprotected while the app showed the
         *  Pre-Flight selection. Amber: the pilot must KNOW the aircraft does not hold it. */
        AVOIDANCE_NOT_APPLIED(false, "AVOIDANCE SETTING NOT APPLIED"),
        /** Set from the bridge's gimbal feed through [GimbalPitchMonitor].
         *
         *  LOG-ONLY (operator, 2026-08-13, after flying it): erratic pitch is happening in
         *  the video the pilot is already looking at, so a banner tells them nothing they
         *  cannot see and spends the one interrupt slot that belongs to the things they
         *  CANNOT see — an avoidance switch that did not take, a compass going bad. What the
         *  detector is genuinely good for is the record: it timestamps the onset and carries
         *  the window's travel and reversal count, which is what made the 2026-08-13 runaway
         *  diagnosable afterwards. So it stays in the ACTIVE set and in the transition log,
         *  and off the screen. */
        GIMBAL_ERRATIC(false, "GIMBAL PITCH ERRATIC", banner = false),
        WIND(false, "WIND TOO HIGH"),
        BATTERY_LOW(false, "BATTERY LOW"),
        AT_MAX_ALTITUDE(false, "AT ALTITUDE LIMIT"),
        AT_MAX_RANGE(false, "AT DISTANCE LIMIT"),
        NO_HOME_POINT(false, "NO HOME POINT"),
        NEAR_AIRPORT(false, "NEAR AIRPORT"),
    }

    /**
     * What the banner should show right now, or null for hidden.
     *
     * [text] is the collapsed line: the worst warning, plus a "+N" for the rest. [all] is every
     * active warning on its own line, worst first. The flight screen shows [all] while the
     * pilot holds the banner open with a tap (specification §4.8), thus the count is never the
     * only way to reach the other warnings. Same shape as the DJI siblings (2026-09-10).
     */
    data class Display(val text: String, val red: Boolean, val all: List<String> = emptyList())

    /** Minimum time a warning owns the banner once shown — long enough to read, short enough
     *  that a stack of warnings still cycles usefully. A WORSE warning preempts regardless. */
    private const val HOLD_MS = 4000L

    /** External conditions. These do not come from `FlyControllerStatus`, so their owners set
     *  these flags and [compute] folds them into the one policy. `AutelAvoidance` owns the
     *  first, the bridge's gimbal feed owns the second — each owner sets AND clears its flag.
     *  Volatile: written from SDK callback threads, read on the fly-controller frame. */
    @Volatile var avoidanceNotApplied: Boolean = false
    @Volatile var gimbalErratic: Boolean = false

    private val lock = Any()
    private var active: Set<Warning> = emptySet()
    private var shown: Warning? = null
    private var shownAtMs = 0L

    /**
     * One status frame from the bridge's fly-controller callback (~2Hz).
     *
     * @param batteryPct  bridge's cached aircraft battery, 0 until the first battery frame.
     * @param airborne    the PLI's own airborne test, computed in the same callback — NOT
     *   read back from the companion so this can never disagree with the frame it came with.
     */
    /**
     * The fly mode from the most recent status push, or null before the first one.
     *
     * ⚠ ONE SOURCE. The flight screen's RTH menu needs the same answer this file computes its
     * go-home banner from — see [returningHome] — and a second copy read from somewhere else
     * would be a second thing to keep in step on a safety control. The Hud snapshot
     * deliberately does not carry it: this object already receives it on every push.
     */
    @Volatile var flyMode: com.autel.common.flycontroller.FlyMode? = null
        private set

    fun onStatus(status: FlyControllerStatus, batteryPct: Int, airborne: Boolean) {
        flyMode = status.flyMode
        val next = compute(status, batteryPct, airborne)
        synchronized(lock) {
            if (next == active) return
            // Transition log — appears at W, clears at I. This is the record that lets a
            // post-flight read say WHEN the compass went bad and when it recovered, and it is
            // also where every signal we do NOT display can be judged for promotion.
            (next - active).forEach { AppLog.w(TAG, "warning ACTIVE: ${it.name} (${it.label})") }
            (active - next).forEach { AppLog.i(TAG, "warning cleared: ${it.name}") }
            active = next
        }
    }

    /** The display policy. Keep it boring: every rule is one add() with its condition. */
    private fun compute(s: FlyControllerStatus, batteryPct: Int, airborne: Boolean): Set<Warning> {
        val out = java.util.EnumSet.noneOf(Warning::class.java)

        // -------- red --------
        if (!s.isCompassValid) out.add(Warning.COMPASS)
        // Gated on airborne: a powered-on aircraft acquiring its first fix indoors is normal,
        // and a red banner during every bench session would teach pilots to ignore red.
        // Airborne without GPS hold is the state that genuinely drifts.
        if (airborne && (!s.isGpsValid || s.mainFlyState == MainFlyState.ATTITUDE)) {
            out.add(Warning.GPS_LOST)
        }
        if (s.isFlightControllerOverHeated) out.add(Warning.FC_HOT)
        // Thresholds are the AIRCRAFT's own (FlightLimitsController read-back), so this
        // agrees with what the aircraft will act on. batteryPct 0 means "no battery frame
        // yet", not an empty pack — see the bridge's field default.
        val crit = FlightLimitsController.aircraftCriticalPct
        val warn = FlightLimitsController.aircraftWarningPct
        if (batteryPct > 0 && crit != null && batteryPct <= crit) out.add(Warning.BATTERY_CRITICAL)
        when (s.flyLimitAreaWarning) {
            FlyLimitAreaWarning.AIRPORT_NO_FLY_ZONES,
            FlyLimitAreaWarning.AIRPORT_NO_FLY_ZONES_CAUTIOUS,
            FlyLimitAreaWarning.AIRPORT_NEAR_NO_FLY_ZONES,
            FlyLimitAreaWarning.AIRPORT_CLOSE_TO_NO_FLY_ZONES -> out.add(Warning.NO_FLY_ZONE)
            FlyLimitAreaWarning.AIRPORT_VICINITY,
            FlyLimitAreaWarning.AIRPORT_HEIGHT_RESTRICTED_AREAS,
            FlyLimitAreaWarning.AIRPORT_HEIGHT_RESTRICT_MAXHEIGHT,
            FlyLimitAreaWarning.AIRPORT_WARNING_AREA,
            FlyLimitAreaWarning.AIRPORT_STRENGTHEN_WARNING_AREA,
            FlyLimitAreaWarning.AIRPORT_NEAR_WARNING_AREA -> out.add(Warning.NEAR_AIRPORT)
            else -> {}
        }

        // -------- amber --------
        // The aircraft coming home on its own is something the pilot must KNOW, with the
        // reason — a drone flying itself with no banner reads as a runaway.
        when (s.flyMode) {
            FlyMode.LOW_BATTERY_GO_HOME -> out.add(Warning.RTH_BATTERY)
            FlyMode.RC_LOST_GO_HOME -> out.add(Warning.RTH_RC_LOST)
            FlyMode.EXCEED_RANGE_GO_HOME -> out.add(Warning.RTH_RANGE)
            // ⚠ **EVERY OTHER GO-HOME RAISES A BANNER TOO, AND IT DID NOT UNTIL 2026-09-14.**
            // This `when` ended in `else -> {}`, so the three named reasons warned and the rest
            // said NOTHING. On a 12.5-hour mission an aircraft returned home on its own and the
            // pilot could not see why — NORMAL_GO_HOME is what an RC button press or an SDK
            // goHome() produces, and it was one of the silent ones.
            //
            // A drone flying itself with no banner reads as a runaway, which is the whole
            // reason this block exists. Better a banner with no reason than no banner.
            else -> if (returningHome(s.flyMode)) out.add(Warning.RTH_OTHER)
        }
        if (avoidanceNotApplied) out.add(Warning.AVOIDANCE_NOT_APPLIED)
        if (gimbalErratic) out.add(Warning.GIMBAL_ERRATIC)
        if (s.isWindTooHigh) out.add(Warning.WIND)
        if (batteryPct > 0 && warn != null && batteryPct <= warn &&
            Warning.BATTERY_CRITICAL !in out) out.add(Warning.BATTERY_LOW)
        if (s.isReachMaxHeight) out.add(Warning.AT_MAX_ALTITUDE)
        if (s.isReachMaxRange || s.isNearRangeLimit) out.add(Warning.AT_MAX_RANGE)
        // Airborne-gated for the same reason as GPS: no home point BEFORE takeoff is just
        // "not ready yet", and the aircraft refuses one-touch takeoff on its own.
        if (airborne && !s.isHomePointValid) out.add(Warning.NO_HOME_POINT)

        return out
    }

    /** Polled from the flight screen's 500 ms HUD tick. */
    fun display(): Display? = displayAt(System.currentTimeMillis())

    /** [display] with an injectable clock, so unit tests can step time. Same logic, one body. */
    internal fun displayAt(now: Long): Display? {
        synchronized(lock) {
            val worst = active.filter { it.banner }.minOrNull()
            val cur = shown
            val held = cur != null && now - shownAtMs < HOLD_MS
            // A worse warning takes the banner immediately; otherwise the current one keeps
            // it for the hold time, so a 2Hz flicker cannot make the text strobe.
            val next = when {
                worst == null -> if (held) cur else null
                cur == null || !held || worst < cur -> worst
                else -> cur
            }
            if (next != cur) { shown = next; shownAtMs = now }
            val show = shown ?: return null
            // "+N" = warnings stacked behind this one, counted from the live set (the shown
            // one may itself already have cleared and just be riding out its hold).
            val others = active.count { it != show && it.banner }
            val text = if (others > 0) "${show.label}  +$others" else show.label
            // Every banner warning, worst first, from the LIVE set — the same source as the
            // count. When the shown warning only rides out its hold, the live set is empty:
            // then the list holds that one line, so an open banner never goes blank.
            val live = active.filter { it.banner }.sorted()
            val all = if (live.isEmpty()) listOf(show.label) else live.map { it.label }
            return Display(text, show.red, all)
        }
    }

    /** New flight screen or aircraft cycle — drop the hold state so a stale banner from the
     *  last session cannot greet the pilot. The active set rebuilds within one frame. */
    fun reset() {
        synchronized(lock) {
            active = emptySet()
            shown = null
            shownAtMs = 0L
        }
    }

    private const val TAG = "FlightWarnings"

    /**
     * True for every FlyMode in which the aircraft is flying itself home or has arrived and is
     * holding there.
     *
     * ⚠ **SWEPT FROM THE aar, NOT REMEMBERED** (2026-09-14, standing rule 8). The enum carries
     * SIX go-home states and this application warned on three of them:
     *
     *     NORMAL_GO_HOME  LOW_BATTERY_GO_HOME  EXCEED_RANGE_GO_HOME
     *     RC_LOST_GO_HOME  GO_HOME_HOVER  MISSION_GO_HOME
     *
     * plus FlightModeShotVideoGohome, which is a shot-video mode returning. The three that were
     * covered are the three with a REASON attached; the ones that were silent are the ordinary
     * ones.
     *
     * Also the gate for offering Cancel Return on the flight screen — the menu must not offer to
     * cancel something that is not running.
     */
    fun returningHome(mode: FlyMode?): Boolean = when (mode) {
        FlyMode.NORMAL_GO_HOME,
        FlyMode.LOW_BATTERY_GO_HOME,
        FlyMode.EXCEED_RANGE_GO_HOME,
        FlyMode.RC_LOST_GO_HOME,
        FlyMode.GO_HOME_HOVER,
        FlyMode.MISSION_GO_HOME,
        FlyMode.FlightModeShotVideoGohome -> true
        else -> false
    }

}
