package com.autel.sdksample.tak

import android.content.Context
import com.autel.common.error.AutelError
import com.autel.common.flycontroller.EmergencyAction
import com.autel.sdk.flycontroller.Evo2FlyController
import com.taklite.util.AppLog

/**
 * Pushes the pilot-configured flight-safety limits (Pre-Flight Setup screen, "Aircraft Settings"
 * section — not yet built on this side, Phase 2) to the aircraft on connect: max altitude, max
 * distance (radius), RTH altitude. Ported from the DJI sibling's `FlightLimitsController`.
 *
 * Each is optional — an empty field means "do not override, leave the aircraft's current/default
 * setting alone." Fields are entered/persisted in feet (matching the AGL readout on the flight
 * screen); converted to meters only here, at the point of calling the SDK (confirmed via `javap`
 * against the bundled `autel-sdk-release.aar`, `AutelFlyController` interface — all three take a
 * plain `double` meters, no documented range like DJI's 20-500m, so out-of-range values are left
 * for the aircraft's own rejection to catch, same policy as the DJI side):
 *   AutelFlyController.setMaxHeight(double meters, CallbackWithNoParam)
 *   AutelFlyController.setMaxRange(double meters, CallbackWithNoParam)
 *   AutelFlyController.setReturnHeight(double meters, CallbackWithNoParam)
 *
 * **Signal-loss failsafe: `AutelFlyController.doEmergencyAction(EmergencyAction)`.**
 *
 * The method name is misleading and cost this port a wrong call once already — it reads like a
 * one-shot "do this now" command, and was initially dismissed as such, leaving the failsafe
 * control unbuilt. Decompiling the SDK shows otherwise: it sends the MAVLink command
 * **`MAV_CMD_SET_MSN_EMERGENCY`** carrying the enum value (NONE=0, HOVER=1, LAND=2, GO_HOME=3).
 * `MAV_CMD_SET_*` sets a parameter; the genuinely-immediate calls beside it in
 * `FlyControllerManager2` use `MAV_CMD_DO_*` (e.g. `setLocationToHome` → `MAV_CMD_DO_SET_HOME`).
 * So this is the aircraft-side policy setter — the equivalent of DJI's
 * `setConnectionFailSafeBehavior` — under an unhelpful name.
 *
 * **One real gap vs the blueprint: there is no read-back.** DJI pairs its setter with
 * `getConnectionFailSafeBehavior()` and this project's DJI side deliberately reads the value
 * back after setting it, because "I picked Return to Home" and "the aircraft is set to Return to
 * Home" are different claims. Autel exposes no getter, so the command result is all the
 * confirmation available — it's logged, and the UI says so rather than implying more certainty
 * than exists. Verify against Autel's own app before relying on it (Phase 4/5).
 *
 * [EmergencyAction.NONE] is deliberately not offered: DJI's picker has three options, and "do
 * nothing on link loss" is not a setting worth making one tap away.
 */
object FlightLimitsController {
    private const val TAG = "TP2LimitsAutel"
    private const val PREFS = "takpilot2_tak"
    private const val KEY_MAX_ALT_FT = "limit_max_altitude_ft"
    private const val KEY_MAX_RADIUS_FT = "limit_max_radius_ft"
    private const val KEY_RTH_ALT_FT = "limit_rth_altitude_ft"
    private const val KEY_FAILSAFE = "limit_failsafe_behavior"
    private const val KEY_LOW_BATT_PCT = "limit_low_battery_pct"
    private const val KEY_CRIT_BATT_PCT = "limit_critical_battery_pct"

    private const val FT_PER_M = 3.28084

    /** What the aircraft does when it loses the RC link. Ids are what's persisted — same id
     *  strings as the DJI sibling, so the two ports' prefs stay conceptually parallel. */
    enum class Failsafe(val id: String, val label: String, val sdk: EmergencyAction) {
        GO_HOME("gohome", "Return to Home", EmergencyAction.GO_HOME),
        ;
        companion object {
            /** Anything else — including "hover"/"land" saved by an older build — becomes
             *  GO_HOME. Those options were removed on 2026-08-02, see the enum doc. */
            fun fromId(id: String?): Failsafe = values().firstOrNull { it.id == id } ?: GO_HOME
        }
    }

    /** Defaults to Return to Home — the safe choice for the "flew out of radio range" case,
     *  and what the aircraft most likely already defaults to (this makes it explicit rather
     *  than assumed). Matches the blueprint's default. */
    fun savedFailsafe(context: Context): Failsafe =
        Failsafe.fromId(pref(context, KEY_FAILSAFE, Failsafe.GO_HOME.id))

    fun saveFailsafe(context: Context, failsafe: Failsafe) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_FAILSAFE, failsafe.id).apply()
    }

    fun savedMaxAltitudeFt(context: Context): String = pref(context, KEY_MAX_ALT_FT, "200")
    fun savedMaxRadiusFt(context: Context): String = pref(context, KEY_MAX_RADIUS_FT, "5280")
    fun savedRthAltitudeFt(context: Context): String = pref(context, KEY_RTH_ALT_FT, "150")

    /**
     * Battery thresholds, percent.
     *
     * THESE ARE THE AIRCRAFT'S OWN AUTOMATIC ACTIONS, not app warnings. Low is the level the
     * aircraft starts bringing itself home at; Critical is the level it puts itself down at.
     *
     * The defaults below (15 / 10) are the OPERATOR'S choice, not Autel's: more usable flight
     * time, with a smaller reserve. That is a deliberate trade and it is theirs to make.
     *
     * **Flight-verified 2026-08-04, one full battery in the air at 15/10:**
     *  - The alert fires at the threshold EXACTLY — the controller read 15% when it beeped. An
     *    earlier note here claimed the action landed ~1% low (RTH "near 24%" at a 25% setting);
     *    that did not reproduce and should not be relied on. Those 25/15 numbers were the
     *    FACTORY values this airframe shipped with, observed 2026-08-02 — they were never
     *    properties of the aircraft, just what it was set to at the time. Read the thresholds
     *    from the log (`battery.lowNotifyThreshold`), never from memory of an older session.
     *  - **The Low action is DEFERRABLE.** Acknowledging the alert with the controller's physical
     *    RTH button cancels the automatic return and allows flight down to Critical. So Low is
     *    "starts coming home unless the pilot says otherwise", not a hard turn-around — worth
     *    knowing before treating it as a wall when planning a mission's endgame.
     *  - Confirmed applied BY THIS APP, not by Explorer: `applyBatteryThresholds` succeeded and
     *    the aircraft read back 0.15/0.1 on every connect that session.
     *
     * ⚠ Battery percentage is not written to the debug log anywhere, so the trigger point above
     * rests on the operator reading the screen. Anything that needs to settle a battery question
     * from the log alone has to add that first.
     *
     * ⚠ Do not set Low at or below Critical. The aircraft would begin its return and force a
     * landing in the same moment, which is worse than either alone.
     */
    fun savedLowBatteryPct(context: Context): String = pref(context, KEY_LOW_BATT_PCT, "15")
    fun savedCriticalBatteryPct(context: Context): String = pref(context, KEY_CRIT_BATT_PCT, "10")

    private fun pref(context: Context, key: String, default: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, default) ?: default

    // ---- Aircraft-accepted ranges -------------------------------------------------------
    //
    // WHY THIS EXISTS. This class used to say "no documented range like DJI's 20-500m, so
    // out-of-range values are left for the aircraft's own rejection to catch". That was wrong on
    // both counts: the SDK DOES expose the ranges (FlyControllerParameterRangeManager, reachable
    // from getParameterRangeManager()), and letting the aircraft catch it is not a strategy
    // because the rejection is invisible to the pilot.
    //
    // MEASURED 2026-08-02: with 50 ft entered here, `setReturnHeight(15)` came back "The command
    // parameters are out of range" and the aircraft carried on holding 46 m / 151 ft. Pre-Flight
    // showed 50 ft the whole time. A readback (getReturnHeight) confirmed the aircraft's real
    // value. Nothing surfaced the difference. That is the bug this section closes.
    //
    // These are plain synchronous getters — getParameterRangeManager() returns the manager
    // directly, and RangePair is a value object. No callback, so none of the
    // is-this-a-subscription hazard that applies to the get*(callback) family.

    /** An aircraft-accepted range, in METERS. */
    data class RangeM(val fromM: Float, val toM: Float) {
        val fromFt: Int get() = Math.round(fromM * FT_PER_M).toInt()
        val toFt: Int get() = Math.round(toM * FT_PER_M).toInt()
        fun containsM(m: Int): Boolean = m >= Math.floor(fromM.toDouble()) &&
            m <= Math.ceil(toM.toDouble())
    }

    private fun rangeManager(): com.autel.common.flycontroller.FlyControllerParameterRangeManager? =
        runCatching { AutelProductHolder.evo2?.flyController?.parameterRangeManager }.getOrNull()

    private fun rangeOf(
        pick: (com.autel.common.flycontroller.FlyControllerParameterRangeManager) ->
            com.autel.common.RangePair<Float>?,
    ): RangeM? = runCatching {
        val pair = rangeManager()?.let(pick) ?: return null
        val from = pair.valueFrom ?: return null
        val to = pair.valueTo ?: return null
        RangeM(from, to)
    }.getOrNull()

    /** Null when no aircraft is connected — the ranges come from the live product. */
    fun returnHeightRange(): RangeM? = rangeOf { it.returnHeightRange }
    fun maxHeightRange(): RangeM? = rangeOf { it.heightRange }
    fun maxRangeRange(): RangeM? = rangeOf { it.rangeOfMaxRange }

    fun save(context: Context, maxAltFt: String, maxRadiusFt: String, rthAltFt: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MAX_ALT_FT, maxAltFt.trim())
            .putString(KEY_MAX_RADIUS_FT, maxRadiusFt.trim())
            .putString(KEY_RTH_ALT_FT, rthAltFt.trim())
            .apply()
    }

    /**
     * Saves the battery levels locally. Separate from [save] because these are the aircraft's
     * own AUTOMATIC ACTIONS rather than geometric limits, and because an invalid pair here is
     * dangerous in a way an out-of-range altitude is not — see [applyBatteryThresholds], which
     * refuses to push low <= critical.
     *
     * Stored as typed, not clamped: a blank field means "keep the aircraft's present setting",
     * matching every other field on the Pre-Flight screen.
     */
    fun saveBatteryLevels(context: Context, lowPct: String, criticalPct: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LOW_BATT_PCT, lowPct.trim())
            .putString(KEY_CRIT_BATT_PCT, criticalPct.trim())
            .apply()
    }

    @Volatile private var appliedForThisConnect = false

    fun onProductDisconnected() {
        appliedForThisConnect = false
        // The HUD must not keep showing the last aircraft's RTH altitude as if it were current.
        aircraftReturnHeightM = null
        // Same for the battery levels: the next aircraft may be configured differently, and a
        // gauge still banded from the last one would be quietly wrong.
        aircraftWarningPct = null
        aircraftCriticalPct = null
    }

    /**
     * Applies the pilot's limits once per AIRCRAFT CONNECT.
     *
     * WHY THIS MOVED. This used to be driven from `AutelTakBridge`'s first-telemetry one-shot,
     * latched on the TAK session. That had two consequences, both found on 2026-08-02:
     *
     *  1. **No TAK session meant no limits, ever.** The push lived inside the bridge's telemetry
     *     listener, so an aircraft flown without a TAK server connected got none of the pilot's
     *     altitude, distance, RTH or battery settings. A network link to a TAK server has no
     *     business gating aircraft safety parameters.
     *  2. **A reconnect never re-applied.** `onProductConnected()` re-armed the listeners but
     *     left the latch set, so after a battery swap or an aircraft reboot mid-session the
     *     limits were silently never re-sent. The operator changed the RTH altitude in
     *     Pre-Flight, flew twice, and both flights used the stale value — because nothing
     *     between the edit and the flight ever started a TAK session.
     *
     * Pre-Flight tells the pilot "Applied automatically when the aircraft connects". This is now
     * driven by exactly that, alongside the other at-connect settings in [AutelProductHolder].
     */
    fun applyAtConnect(context: Context) {
        if (appliedForThisConnect) return
        val fc = AutelProductHolder.evo2?.flyController ?: run {
            AppLog.i(TAG, "applyAtConnect: no aircraft yet, will retry on next connect")
            return
        }
        appliedForThisConnect = true
        // A collector for this push. Nothing reads it — that is the point: a refusal from a
        // re-connect must not appear in the report for a Pre-Flight button press.
        beginApply()
        applyDefaults(context, fc)
    }

    /**
     * Pushes JUST the numeric limits, immediately, if an aircraft is connected.
     * Returns false when there is nothing to push to.
     *
     * Called from the DEBOUNCED Pre-Flight edit path — never wire this straight to a TextWatcher.
     * Those fields fire on every keystroke, so typing "200" without a debounce would send three
     * separate writes (2ft, 20ft, 200ft) to the fly-controller channel. Bursting writes at that
     * channel is what put an aircraft into a wall on 2026-08-02.
     */
    fun pushLimitsNow(context: Context): Boolean {
        val fc = AutelProductHolder.evo2?.flyController ?: return false
        applyNumericLimits(context, fc)
        return true
    }

    /** As [pushLimitsNow], for the failsafe selection. */
    fun pushFailsafeNow(context: Context): Boolean {
        val fc = AutelProductHolder.evo2?.flyController ?: return false
        applyFailsafe(context, fc)
        return true
    }

    /** As [pushLimitsNow], for the battery thresholds and RF power. */
    fun pushBatteryAndRfNow(context: Context): Boolean {
        AutelProductHolder.evo2 ?: return false
        applyBatteryThresholds(context)
        applyRfPower(context)
        return true
    }

    /**
     * The RTH altitude **the aircraft last reported**, in metres. Null until it answers.
     *
     * This is what the flight HUD displays. It is deliberately NOT the Pre-Flight value: a
     * requested number and an applied number diverged on 2026-08-02 and the pilot had no way to
     * see it. Null renders as "RTH --" — unknown has to look unknown, because a confident wrong
     * number on a flight screen is worse than no number.
     *
     * Only ever written from an aircraft reply.
     */
    @Volatile var aircraftReturnHeightM: Float? = null
        private set

    /**
     * The battery levels the AIRCRAFT actually holds, in percent — null until it tells us.
     *
     * Distinct from [savedLowBatteryPct]/[savedCriticalBatteryPct], which are what the PILOT
     * typed. The two disagree whenever a value has been edited but not yet applied, or when an
     * apply failed, and anything a pilot reads in flight must show the aircraft's state rather
     * than our intent — a battery gauge coloured from an unsent setting is exactly the kind of
     * confident-and-wrong readout this app tries not to produce.
     *
     * NOT read by a call of its own. These are filled in by [AircraftSettingsDump], which
     * already reads both once per connect; issuing a second pair of reads would add traffic to
     * the fly-controller channel for a value we are handed anyway. Only ever written from an
     * aircraft reply.
     */
    // internal, not private, set: FlightWarningsTest injects thresholds (v1.6.0). Production
    // writes stay in this file only.
    @Volatile var aircraftWarningPct: Float? = null
        internal set
    @Volatile var aircraftCriticalPct: Float? = null
        internal set

    /**
     * Records a battery level reported by the aircraft. Takes the SDK's FRACTION (0.15) and
     * stores percent; a value outside 0-1 is discarded rather than shown, since a nonsense
     * threshold on the gauge would recolour the whole readout.
     */
    fun reportAircraftBatteryLevel(isCritical: Boolean, fraction: Float?) {
        val pct = fraction?.takeIf { it > 0f && it <= 1f }?.let { it * 100f }
        if (isCritical) aircraftCriticalPct = pct else aircraftWarningPct = pct
    }

    /**
     * One-shot read of the aircraft's RTH altitude, cached into [aircraftReturnHeightM].
     *
     * `getReturnHeight` is a one-shot `ParamsQueryPacket` (`SM_RTH_Height`) — verified in the
     * bytecode, not one of the repeating-listener getters. Called at connect and after an Apply,
     * NOT polled: the fly-controller channel is the one that must not be loaded.
     */
    fun refreshReturnHeight() {
        val fc = AutelProductHolder.evo2?.flyController ?: return
        runCatching {
            fc.getReturnHeight(object : com.autel.common.CallbackWithOneParam<Float> {
                override fun onSuccess(v: Float?) {
                    aircraftReturnHeightM = v
                    AppLog.i(TAG, "aircraft reports RTH altitude = ${v}m " +
                        "(${v?.let { Math.round(it * FT_PER_M) }}ft)")
                }
                override fun onFailure(error: AutelError?) {
                    aircraftReturnHeightM = null
                    AppLog.w(TAG, "RTH altitude read failed: ${error?.description} — HUD shows unknown")
                }
            })
        }
    }

    /** HUD string. "RTH --" whenever the aircraft has not told us. */
    fun rthHudLabel(): String =
        aircraftReturnHeightM?.let { "RTH ${Math.round(it * FT_PER_M)} ft" } ?: "RTH --"

    /**
     * What the report says about ONE limit, independent of the SDK.
     *
     * [gotM] null means the aircraft did not answer. That is not a failure and it is not a
     * match. [wantM] null means the pilot left the field empty, thus nothing was requested and
     * what the aircraft holds is correct by definition.
     */
    data class LimitReadBack(val label: String, val wantM: Int?, val gotM: Float?)

    /**
     * The three states a read-back can end in.
     *
     * UNKNOWN is its own state on purpose. "The aircraft did not answer" is not "the aircraft
     * refused", and to show the second when the first occurred tells the pilot something that is
     * not true. Before v1.6.0 there were two states, and a getter that answered with no value
     * became a red "did not take all the settings" with the value missing from the line.
     */
    enum class ReportState { CONFIRMED, UNKNOWN, PROBLEM }

    /**
     * Writes this firmware ALWAYS refuses, whatever the pilot does.
     *
     * Both are measured, not assumed:
     *  - **RF power** — every SDK write path refuses it. The controller stays on the region it
     *    is pinned to; the fleet flies that way knowingly.
     *  - **Signal-loss behaviour** — `doEmergencyAction` is the ONE API the SDK exposes for it
     *    (swept the whole aar, 2026-08-13: `AutelFlyController.doEmergencyAction` and its proxy,
     *    nothing else), and this firmware never acknowledges it. There is no getter either.
     *    Flight testing DID confirm the aircraft returns home on link loss, so the behaviour the
     *    pilot needs is the behaviour the aircraft has — it is simply the aircraft's own setting
     *    and not ours to write. TAKPilot offers Return to Home as its only option (see
     *    [Failsafe]), so nothing here can put the aircraft into a different one.
     *
     * These are REPORTED but never counted as a problem: a pilot cannot correct them, and a red
     * "correct the values" on every apply is how a warning dialog becomes wallpaper.
     */
    private val FIRMWARE_MANAGED = setOf("RF power", "Signal-loss behaviour")

    /** What the aircraft actually reports back, rendered for the pilot. */
    data class ReadBackReport(val text: String, val state: ReportState)

    /**
     * The writes the aircraft REFUSED during one apply.
     *
     * WHY THIS EXISTS. Each setter in this file used to put its failure in the log and nowhere
     * else, thus an aircraft that refused the RF power, the battery levels or the signal-loss
     * behaviour told the pilot nothing. Two of those settings have no getter, thus the read-back
     * cannot find them: if the write does not record its own refusal, the refusal is lost.
     *
     * SDK callbacks arrive on the SDK thread, thus every method is synchronized. A LinkedHashSet
     * keeps one entry for each setting, in the order the settings were sent.
     */
    class RefusedWrites {
        private val labels = LinkedHashSet<String>()
        @Synchronized fun add(label: String) { labels.add(label) }
        @Synchronized fun snapshot(): List<String> = labels.toList()
    }

    /**
     * The collector for the apply that runs now.
     *
     * REPLACED, NEVER CLEARED. Each apply function reads this field ONE time into a local, thus a
     * setter that has already started keeps the instance it read. A new apply — or the automatic
     * push that runs when an aircraft re-connects in the middle of one — cannot take a refusal
     * away from the report it belongs to, and a late callback cannot put a refusal into a report
     * that came later. Do not "simplify" those locals away.
     */
    @Volatile private var refusedThisApply = RefusedWrites()

    /**
     * Starts an apply-and-verify cycle and gives back the collector to hand to [readBack].
     *
     * The automatic at-connect push calls this too. Its collector is never read, which is the
     * point: a refusal from a re-connect must not show in the report for a button press.
     */
    fun beginApply(): RefusedWrites {
        val fresh = RefusedWrites()
        refusedThisApply = fresh
        return fresh
    }

    /**
     * Tolerance, in metres. The values go over the wire as metres rounded from feet, thus an
     * exact comparison would call a correct setting wrong.
     */
    private const val MATCH_TOLERANCE_M = 0.6f

    /**
     * How long the read-back waits for the three getters before it says "unknown".
     *
     * The fly-controller channel times a request out at about 10 seconds, thus this is past the
     * point where an answer can still arrive. It exists for the case where no answer arrives AND
     * no failure is reported either: the Apply button stays disabled until this function reports,
     * and a getter that never called back used to leave it disabled for the life of the screen.
     */
    private const val READ_BACK_TIMEOUT_MS = 12_000L

    /**
     * Builds the line the pilot reads. A pure function: no SDK, no aircraft, no clock. The caller
     * has already collected the answers. This is what the unit tests pin.
     *
     * THE RULE IT HOLDS. Three outcomes, and they do not collapse into two:
     *  - CONFIRMED: each limit answered, and each answer agrees with what was asked.
     *  - UNKNOWN:   nothing disagrees, but the aircraft did not answer for one limit or more.
     *  - PROBLEM:   an answer disagrees, or a write was refused.
     * PROBLEM beats UNKNOWN, and UNKNOWN beats CONFIRMED.
     */
    internal fun buildReadBackReport(
        values: List<LimitReadBack>,
        refusedWrites: List<String>,
    ): ReadBackReport {
        val confirmed = mutableListOf<String>()
        val wrong = mutableListOf<String>()
        val unknown = mutableListOf<String>()

        for (v in values) {
            val got = v.gotM
            if (got == null) { unknown += v.label; continue }
            val ft = Math.round(got * FT_PER_M).toInt()
            if (v.wantM != null && Math.abs(got - v.wantM) > MATCH_TOLERANCE_M) {
                wrong += "${v.label} is $ft ft, not ${Math.round(v.wantM * FT_PER_M)} ft"
            } else {
                confirmed += "${v.label} $ft ft"
            }
        }

        // Two kinds of refusal, and they need different words. A refusal the pilot can act on
        // (a value out of range, battery levels crossed) belongs in "correct it and press
        // again". A refusal this FIRMWARE always gives — see [FIRMWARE_MANAGED] — cannot be
        // corrected by anyone at the controller, so telling the pilot to correct it is false
        // instruction, and a red report on every apply teaches them to dismiss the dialog that
        // is meant to carry the real problems.
        val (firmwareManaged, actionable) = refusedWrites.partition { it in FIRMWARE_MANAGED }

        val state = when {
            wrong.isNotEmpty() || actionable.isNotEmpty() -> ReportState.PROBLEM
            unknown.isNotEmpty() -> ReportState.UNKNOWN
            else -> ReportState.CONFIRMED
        }

        val text = buildString {
            when (state) {
                ReportState.CONFIRMED ->
                    append("The aircraft confirms: ${confirmed.joinToString(", ")}.")
                ReportState.UNKNOWN -> {
                    append("⚠ The aircraft did not answer for: ${unknown.joinToString(", ")}.")
                    if (confirmed.isNotEmpty()) {
                        append(" It confirms: ${confirmed.joinToString(", ")}.")
                    }
                    append(" Press the button again.")
                }
                ReportState.PROBLEM -> {
                    append("⚠ The aircraft did not take all the settings.")
                    if (actionable.isNotEmpty()) {
                        append(" It refused: ${actionable.joinToString(", ")}.")
                    }
                    if (wrong.isNotEmpty()) append(" ${wrong.joinToString(", ")}.")
                    if (confirmed.isNotEmpty()) {
                        append(" It confirms: ${confirmed.joinToString(", ")}.")
                    }
                    if (unknown.isNotEmpty()) {
                        append(" It did not answer for: ${unknown.joinToString(", ")}.")
                    }
                    append(" Correct the values, then press the button again.")
                }
            }
            // Always last, in every state: the settings this firmware keeps for itself. Stated,
            // never presented as a task.
            if (firmwareManaged.isNotEmpty()) {
                append(" The aircraft keeps its own ${firmwareManaged.joinToString(" and ")} " +
                    "— not settable on this firmware.")
            }
        }
        return ReadBackReport(text, state)
    }

    /**
     * Reads the three numeric limits back off the aircraft and compares them to what Pre-Flight
     * says, then hands the caller a line fit to show a pilot.
     *
     * WHY THIS IS THE IMPORTANT HALF. On 2026-08-02 every layer was found capable of lying on its
     * own: `setReturnHeight` reported OK for a value the aircraft did not fly, an out-of-range
     * value was rejected while Pre-Flight kept displaying it, and the pilot flew two sorties on a
     * setting they believed they had changed. The only statement worth making to a pilot is what
     * the aircraft answers when asked.
     *
     * The getters are one-shot ParamsQueryPackets (`SM_RTH_Height` and friends) — verified in the
     * bytecode, NOT the repeating-listener kind. Safe to call on demand.
     */
    fun readBack(context: Context, refused: RefusedWrites?, done: (ReadBackReport) -> Unit) {
        val fc = AutelProductHolder.evo2?.flyController ?: run {
            done(ReadBackReport(
                "Aircraft disconnected before it could be verified.", ReportState.PROBLEM))
            return
        }
        val wantAlt = ftToM(savedMaxAltitudeFt(context))
        val wantRad = ftToM(savedMaxRadiusFt(context))
        val wantRth = ftToM(savedRthAltitudeFt(context))

        val got = java.util.concurrent.ConcurrentHashMap<String, Float>()
        val outstanding = java.util.concurrent.atomic.AtomicInteger(3)
        // ONE-SHOT. This SDK fires some callbacks twice. A second fire drove the old counter to
        // -1, which is also "not greater than 0", thus the report was built and delivered again.
        // It also closes the race between the last getter and the watchdog.
        val reported = java.util.concurrent.atomic.AtomicBoolean(false)
        // ⚠ Built HERE, not as a field of this object. The unit tests run with
        // `returnDefaultValues = true`, thus Looper.getMainLooper() gives null in a test and a
        // field would fail class-initialization — which would take the other suites down with it.
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var watchdog: Runnable? = null

        fun report() {
            if (!reported.compareAndSet(false, true)) return
            watchdog?.let { main.removeCallbacks(it) }
            done(buildReadBackReport(
                listOf(
                    LimitReadBack("Max altitude", wantAlt, got["alt"]),
                    LimitReadBack("Max distance", wantRad, got["rad"]),
                    LimitReadBack("RTH altitude", wantRth, got["rth"]),
                ),
                refused?.snapshot() ?: emptyList(),
            ))
        }
        fun oneDown() { if (outstanding.decrementAndGet() <= 0) report() }

        watchdog = Runnable {
            if (!reported.get()) {
                AppLog.w(TAG, "read-back timed out after ${READ_BACK_TIMEOUT_MS}ms with " +
                    "${outstanding.get()} getter(s) unanswered — they report as unknown")
                report()
            }
        }
        main.postDelayed(watchdog, READ_BACK_TIMEOUT_MS)

        /**
         * One getter. A failure and a silence are the same thing to the pilot — neither says what
         * the aircraft holds — thus both leave the value absent and the report calls it unknown.
         * The difference stays in the log, where it helps.
         */
        fun read(key: String, label: String, call: (com.autel.common.CallbackWithOneParam<Float>) -> Unit) {
            runCatching {
                call(object : com.autel.common.CallbackWithOneParam<Float> {
                    override fun onSuccess(v: Float?) {
                        if (v == null) AppLog.w(TAG, "$label read answered with no value — unknown")
                        v?.let { got[key] = it }
                        // Keep the flight HUD in step with what we just learned.
                        if (key == "rth") aircraftReturnHeightM = v
                        oneDown()
                    }
                    override fun onFailure(error: AutelError?) {
                        AppLog.w(TAG, "$label read failed: ${error?.description} — unknown")
                        if (key == "rth") aircraftReturnHeightM = null
                        oneDown()
                    }
                })
            }.onFailure {
                AppLog.w(TAG, "$label read threw: ${it.message} — unknown")
                oneDown()
            }
        }
        read("alt", "max altitude") { fc.getMaxHeight(it) }
        read("rad", "max distance") { fc.getMaxRange(it) }
        read("rth", "RTH altitude") { fc.getReturnHeight(it) }
    }

    /** Apply whichever limits are configured. Skips any limit whose field is empty/unparseable —
     *  that limit is simply not touched. */
    fun applyDefaults(context: Context, fc: Evo2FlyController) {
        val maxAltM = ftToM(savedMaxAltitudeFt(context))
        val maxRadiusM = ftToM(savedMaxRadiusFt(context))
        val rthAltM = ftToM(savedRthAltitudeFt(context))
        AppLog.i(TAG, "applyDefaults: maxAltM=$maxAltM maxRadiusM=$maxRadiusM rthAltM=$rthAltM " +
            "(null = not configured, skipped)")

        applyNumericLimits(context, fc)
        applyBatteryThresholds(context)
        applyRfPower(context)
        applyFailsafe(context, fc)
    }

    /**
     * The three numeric limits, each checked against the aircraft's own accepted range first.
     *
     * Split out from [applyDefaults] so a Pre-Flight edit can push JUST these. Re-running the
     * whole of applyDefaults on every edit would also re-fire the failsafe write, which takes a
     * 10-second timeout to fail on this firmware — turning one keystroke into ten seconds of
     * pending traffic on the fly-controller channel.
     */
    fun applyNumericLimits(context: Context, fc: Evo2FlyController) {
        // Read ONE time into a local — see the note on [refusedThisApply].
        val refused = refusedThisApply
        val maxAltM = ftToM(savedMaxAltitudeFt(context))
        val maxRadiusM = ftToM(savedMaxRadiusFt(context))
        val rthAltM = ftToM(savedRthAltitudeFt(context))

        val rthRange = returnHeightRange()
        val altRange = maxHeightRange()
        val radRange = maxRangeRange()
        AppLog.i(TAG, "aircraft-accepted ranges: returnHeight=$rthRange maxHeight=$altRange " +
            "maxRange=$radRange (null = aircraft did not report one)")

        /** Refuses a push we already know the aircraft will reject, and says why. Pushing it
         *  anyway would only produce "out of range" in the log and leave the pilot's Pre-Flight
         *  value looking applied. */
        fun inRange(label: String, name: String, m: Int, range: RangeM?): Boolean {
            if (range == null || range.containsM(m)) return true
            AppLog.w(TAG, "REFUSING $name(${m}m / ${Math.round(m * FT_PER_M)}ft): aircraft " +
                "accepts ${range.fromM}-${range.toM}m (${range.fromFt}-${range.toFt}ft). " +
                "THE AIRCRAFT KEEPS ITS CURRENT VALUE — Pre-Flight does not match the aircraft.")
            refused.add("$label (outside the range the aircraft accepts)")
            return false
        }

        maxAltM?.let { m ->
            if (!inRange("Max altitude", "setMaxHeight", m, altRange)) return@let
            fc.setMaxHeight(m.toDouble(), object : com.autel.common.CallbackWithNoParam {
                override fun onSuccess() { AppLog.i(TAG, "setMaxHeight($m): OK") }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "setMaxHeight($m) failed: ${error?.description}")
                    refused.add("Max altitude")
                }
            })
        }
        maxRadiusM?.let { m ->
            if (!inRange("Max distance", "setMaxRange", m, radRange)) return@let
            fc.setMaxRange(m.toDouble(), object : com.autel.common.CallbackWithNoParam {
                override fun onSuccess() { AppLog.i(TAG, "setMaxRange($m): OK") }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "setMaxRange($m) failed: ${error?.description}")
                    refused.add("Max distance")
                }
            })
        }
        rthAltM?.let { m ->
            if (!inRange("RTH altitude", "setReturnHeight", m, rthRange)) return@let
            fc.setReturnHeight(m.toDouble(), object : com.autel.common.CallbackWithNoParam {
                override fun onSuccess() { AppLog.i(TAG, "setReturnHeight($m): OK") }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "setReturnHeight($m) failed: ${error?.description}")
                    refused.add("RTH altitude")
                }
            })
        }
    }

    /**
     * Signal-loss failsafe. Logged loudly either way: this is the one limit a pilot cannot
     * casually verify in the air (confirming it for real means deliberately dropping the RC
     * link mid-flight), and unlike the DJI side there's no getter to read it back — so this
     * log line is the only evidence the aircraft accepted it.
     *
     * ⚠ MEASURED 2026-08-02: on this firmware this write is NOT acknowledged. It fails with
     * "The execution of this process has timed out" after 10s, on a clean channel, with Autel
     * Explorer closed and camera init finished. Flight testing separately confirmed the aircraft
     * DOES return to home on link loss — so the behaviour is right, but it is the aircraft's own
     * setting and this call is not what puts it there. Do not present this as a working control
     * until that is resolved.
     */
    fun applyFailsafe(context: Context, fc: Evo2FlyController) {
        // Read ONE time into a local — see the note on [refusedThisApply].
        val refused = refusedThisApply
        val failsafe = savedFailsafe(context)
        fc.doEmergencyAction(failsafe.sdk, object : com.autel.common.CallbackWithNoParam {
            override fun onSuccess() {
                AppLog.i(TAG, "signal-loss behavior set to '${failsafe.label}' " +
                    "(${failsafe.sdk}): OK — no read-back available on this SDK")
            }
            override fun onFailure(error: AutelError?) {
                AppLog.w(TAG, "signal-loss behavior '${failsafe.label}' REJECTED: " +
                    "${error?.description} — aircraft keeps its previous setting")
                // This setting has NO getter, thus this is the only way a refusal can reach the
                // pilot. See the measurement note above: this write is not acknowledged on this
                // firmware, thus expect it here on each apply until that is resolved.
                refused.add("Signal-loss behaviour")
            }
        })
    }

    /** Parses a feet string to a rounded meters int, or null if blank/unparseable. */
    private fun ftToM(feetStr: String): Int? {
        val ft = feetStr.trim().toDoubleOrNull() ?: return null
        return Math.round(ft / FT_PER_M).toInt()
    }

    /**
     * Pushes the battery thresholds. See [savedLowBatteryPct] for what they actually do.
     *
     * The SDK takes a FRACTION (0.15), not a percent — the aircraft reported 0.25/0.15 for its
     * 25%/15% settings. Sent low-first so that if only one call lands, the aircraft is left with
     * a return level below its landing level rather than the other way round.
     */
    private fun applyBatteryThresholds(context: Context) {
        // Read ONE time into a local — see the note on [refusedThisApply].
        val refused = refusedThisApply
        val bat = AutelProductHolder.evo2?.battery ?: return
        val low = savedLowBatteryPct(context).trim().toFloatOrNull()
        val crit = savedCriticalBatteryPct(context).trim().toFloatOrNull()
        if (low == null || crit == null) {
            AppLog.w(TAG, "battery thresholds not configured — leaving the aircraft's own values")
            return
        }
        if (low <= crit) {
            AppLog.e(TAG, "REFUSING battery thresholds: low ($low%) is not above critical ($crit%) " +
                "— the aircraft would return home and force-land at the same moment")
            refused.add("Battery levels (Warning must be above Critical)")
            return
        }
        bat.setLowBatteryNotifyThreshold(low / 100f, object : com.autel.common.CallbackWithNoParam {
            override fun onSuccess() { AppLog.i(TAG, "low battery threshold set to $low%: OK") }
            override fun onFailure(error: AutelError?) {
                AppLog.w(TAG, "low battery threshold $low% failed: ${error?.description}")
                refused.add("Low battery level")
            }
        })
        bat.setCriticalBatteryNotifyThreshold(crit / 100f, object : com.autel.common.CallbackWithNoParam {
            override fun onSuccess() { AppLog.i(TAG, "critical battery threshold set to $crit%: OK") }
            override fun onFailure(error: AutelError?) {
                AppLog.w(TAG, "critical battery threshold $crit% failed: ${error?.description}")
                refused.add("Critical battery level")
            }
        })
    }

    /**
     * Pushes the controller's RF power region.
     *
     * ⚠ THIS IS A REGULATORY SETTING, not a performance one. FCC permits higher transmit power
     * than CE and gives more range; which is LAWFUL depends on where the aircraft is flown, and
     * that is the operator's responsibility, not the app's.
     *
     * Default is FCC because this airframe operates in Alaska (operator, 2026-08-02) and the
     * controller was found set to CE, which was costing link margin for no reason. Anyone flying
     * this build elsewhere must revisit it.
     */
    private fun applyRfPower(context: Context) {
        // Read ONE time into a local — see the note on [refusedThisApply].
        val refused = refusedThisApply
        val rc = AutelProductHolder.evo2?.remoteController ?: return
        val want = savedRfPower(context)
        rc.setRFPower(want, object : com.autel.common.CallbackWithNoParam {
            override fun onSuccess() { AppLog.i(TAG, "RF power set to $want: OK") }
            override fun onFailure(error: AutelError?) {
                AppLog.w(TAG, "RF power $want failed: ${error?.description}")
                // No getter for this one either — the refusal reaches the pilot only from here.
                refused.add("RF power")
            }
        })
    }

    private const val KEY_RF_POWER = "limit_rf_power"

    fun savedRfPower(context: Context): com.autel.common.remotecontroller.RFPower =
        if (pref(context, KEY_RF_POWER, "FCC") == "CE")
            com.autel.common.remotecontroller.RFPower.CE
        else com.autel.common.remotecontroller.RFPower.FCC
}
