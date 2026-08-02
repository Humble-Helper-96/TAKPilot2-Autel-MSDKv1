package com.autel.sdksample.tak

import android.content.Context
import com.autel.common.error.AutelError
import com.autel.common.flycontroller.EmergencyAction
import com.autel.sdk.flycontroller.Evo2FlyController
import com.taklite.util.AppLog

/**
 * Pushes the pilot-configured flight-safety limits (Pre-Flight Setup screen, "Drone Settings"
 * section — not yet built on this side, Phase 2) to the aircraft on connect: max altitude, max
 * distance (radius), RTH altitude. Ported from the DJI sibling's `FlightLimitsController`.
 *
 * Each is optional — an empty field means "don't override, leave the aircraft's current/default
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
        HOVER("hover", "Hover in place", EmergencyAction.HOVER),
        LAND("land", "Land immediately", EmergencyAction.LAND),
        ;
        companion object {
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
     * THESE ARE THE AIRCRAFT'S OWN AUTOMATIC ACTIONS, not app warnings. Measured on this
     * airframe 2026-08-02: it shipped with low=25 / critical=15, and its behaviour matched
     * exactly — a return-to-home began near 24%, and a forced descent near 14%. So Low is the
     * level the aircraft brings itself home at, and Critical is the level it puts itself down at.
     *
     * The defaults below (15 / 10) are the OPERATOR'S choice, not Autel's: more usable flight
     * time, with a smaller reserve. That is a deliberate trade and it is theirs to make.
     *
     * ⚠ Do not set Low at or below Critical. The aircraft would begin its return and force a
     * landing in the same moment, which is worse than either alone.
     */
    fun savedLowBatteryPct(context: Context): String = pref(context, KEY_LOW_BATT_PCT, "15")
    fun savedCriticalBatteryPct(context: Context): String = pref(context, KEY_CRIT_BATT_PCT, "10")

    private fun pref(context: Context, key: String, default: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, default) ?: default

    fun save(context: Context, maxAltFt: String, maxRadiusFt: String, rthAltFt: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MAX_ALT_FT, maxAltFt.trim())
            .putString(KEY_MAX_RADIUS_FT, maxRadiusFt.trim())
            .putString(KEY_RTH_ALT_FT, rthAltFt.trim())
            .apply()
    }

    /** Apply whichever limits are configured (called once on aircraft connect). Skips any limit
     *  whose field is empty/unparseable — that limit is simply not touched. */
    fun applyDefaults(context: Context, fc: Evo2FlyController) {
        val maxAltM = ftToM(savedMaxAltitudeFt(context))
        val maxRadiusM = ftToM(savedMaxRadiusFt(context))
        val rthAltM = ftToM(savedRthAltitudeFt(context))
        AppLog.i(TAG, "applyDefaults: maxAltM=$maxAltM maxRadiusM=$maxRadiusM rthAltM=$rthAltM " +
            "(null = not configured, skipped)")

        maxAltM?.let { m ->
            fc.setMaxHeight(m.toDouble(), object : com.autel.common.CallbackWithNoParam {
                override fun onSuccess() { AppLog.i(TAG, "setMaxHeight($m): OK") }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "setMaxHeight($m) failed: ${error?.description}")
                }
            })
        }
        maxRadiusM?.let { m ->
            fc.setMaxRange(m.toDouble(), object : com.autel.common.CallbackWithNoParam {
                override fun onSuccess() { AppLog.i(TAG, "setMaxRange($m): OK") }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "setMaxRange($m) failed: ${error?.description}")
                }
            })
        }
        rthAltM?.let { m ->
            fc.setReturnHeight(m.toDouble(), object : com.autel.common.CallbackWithNoParam {
                override fun onSuccess() { AppLog.i(TAG, "setReturnHeight($m): OK") }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "setReturnHeight($m) failed: ${error?.description}")
                }
            })
        }

        // Signal-loss failsafe. Logged loudly either way: this is the one limit a pilot can't
        // casually verify in the air (confirming it for real means deliberately dropping the RC
        // link mid-flight), and unlike the DJI side there's no getter to read it back — so this
        // log line is the only evidence the aircraft accepted it.
        applyBatteryThresholds(context)
        applyRfPower(context)

        val failsafe = savedFailsafe(context)
        fc.doEmergencyAction(failsafe.sdk, object : com.autel.common.CallbackWithNoParam {
            override fun onSuccess() {
                AppLog.i(TAG, "signal-loss behavior set to '${failsafe.label}' " +
                    "(${failsafe.sdk}): OK — no read-back available on this SDK")
            }
            override fun onFailure(error: AutelError?) {
                AppLog.w(TAG, "signal-loss behavior '${failsafe.label}' REJECTED: " +
                    "${error?.description} — aircraft keeps its previous setting")
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
            return
        }
        bat.setLowBatteryNotifyThreshold(low / 100f, object : com.autel.common.CallbackWithNoParam {
            override fun onSuccess() { AppLog.i(TAG, "low battery threshold set to $low%: OK") }
            override fun onFailure(error: AutelError?) {
                AppLog.w(TAG, "low battery threshold $low% failed: ${error?.description}")
            }
        })
        bat.setCriticalBatteryNotifyThreshold(crit / 100f, object : com.autel.common.CallbackWithNoParam {
            override fun onSuccess() { AppLog.i(TAG, "critical battery threshold set to $crit%: OK") }
            override fun onFailure(error: AutelError?) {
                AppLog.w(TAG, "critical battery threshold $crit% failed: ${error?.description}")
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
        val rc = AutelProductHolder.evo2?.remoteController ?: return
        val want = savedRfPower(context)
        rc.setRFPower(want, object : com.autel.common.CallbackWithNoParam {
            override fun onSuccess() { AppLog.i(TAG, "RF power set to $want: OK") }
            override fun onFailure(error: AutelError?) {
                AppLog.w(TAG, "RF power $want failed: ${error?.description}")
            }
        })
    }

    private const val KEY_RF_POWER = "limit_rf_power"

    fun savedRfPower(context: Context): com.autel.common.remotecontroller.RFPower =
        if (pref(context, KEY_RF_POWER, "FCC") == "CE")
            com.autel.common.remotecontroller.RFPower.CE
        else com.autel.common.remotecontroller.RFPower.FCC
}
