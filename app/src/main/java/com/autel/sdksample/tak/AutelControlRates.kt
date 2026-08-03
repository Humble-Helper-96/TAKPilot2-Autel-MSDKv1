package com.autel.sdksample.tak

import android.content.Context
import com.autel.common.CallbackWithNoParam
import com.autel.common.CallbackWithOneParam
import com.autel.common.error.AutelError
import com.autel.common.remotecontroller.RemoteControllerCommandStickMode
import com.taklite.util.AppLog

/**
 * Controller feel: gimbal-wheel speed and yaw rate, plus the (read-only) stick mode.
 *
 * WHAT "PRECISION" IS. Both values live on the REMOTE CONTROLLER, not the aircraft:
 *   setGimbalDialAdjustSpeed(int)  — how fast the gimbal wheel drives the camera
 *   setYawCoefficient(float)       — how fast the stick yaws the aircraft
 * Precision slows both. It changes how the CONTROLS respond, not
 * how the aircraft flies — the flight-sensitivity family (pitch/roll/brake/ATTI) is a separate
 * thing and is deliberately not touched here.
 *
 * NORMAL AND PRECISION ARE EXPLICIT VALUES, set by the operator after reading the aircraft's
 * own numbers off a settings dump (2026-08-02). The airframe shipped with a gimbal dial of 100
 * and had been moved to 50 in Autel's app; neither was what the operator wanted, so the values
 * below were tuned by feel in flight rather than discovered. An earlier version LEARNED normal from whatever the
 * controller happened to report — which sounds safer but silently inherits whatever the last
 * app left behind, including a Precision value, and would ratchet the controls slower on every
 * toggle.
 *
 * Nothing here runs on connect. Same rule as obstacle avoidance: the app never changes how the
 * aircraft handles unless the pilot asks it to.
 */
object AutelControlRates {
    private const val TAG = "AutelControlRates"
    // Gimbal wheel speed. HIGHER IS FASTER — confirmed in flight 2026-08-02, not assumed.
    // Tuning history, because these numbers look arbitrary otherwise: shipped at 100, Autel's
    // app had 50, 75 felt too slow, 100 felt too fast. 90/75 is where the operator settled.
    //
    // Note how NARROW that is. Precision is a deliberate nudge, not a different mode — the
    // useful range on this wheel turned out to be much smaller than a halving.
    private const val NORMAL_DIAL = 90
    private const val PRECISION_DIAL = 75

    // Yaw rate. 0.25 is what the controller reported with no adjustment ever made in Autel's
    // app, so it is this airframe's true default and Normal keeps it exactly.
    private const val NORMAL_YAW = 0.25f
    /**
     * Yaw in Precision. NOT tied to the dial ratio — that link was dropped once the dial range
     * narrowed to 90/75, because scaling yaw by the same 0.83 would be imperceptible.
     *
     * Roughly half of normal, chosen independently. The operator has not commented on yaw feel,
     * so this is the least-validated number here: if Precision yaw feels dead rather than calm,
     * this is the one to raise.
     */
    private const val PRECISION_YAW = 0.13f

    /** Tolerance when deciding whether the live values "are" Precision. Dial speed is an int and
     *  yaw a float, and the controller may round, so an exact compare would flap. */
    private const val MATCH_TOLERANCE = 0.15

    /**
     * Guards the connect-time push. productConnected fires REPEATEDLY — three times in 17
     * seconds, observed 2026-08-02 — and an unguarded apply rewrote the controller's settings on
     * every one of them. The controller beeps to acknowledge each write, so the pilot got a
     * burst of beeps for a change that had already been made.
     */
    @Volatile private var appliedForThisConnect = false

    @Volatile var stickMode: RemoteControllerCommandStickMode? = null
        private set
    @Volatile var dialSpeed: Int? = null
        private set
    @Volatile var yawCoefficient: Float? = null
        private set

    /** True/false once we can tell, null while values are unknown. */
    @Volatile var precisionActive: Boolean? = null
        private set

    /**
     * Reads stick mode, gimbal dial speed and yaw coefficient from the controller.
     *
     * @param then invoked on completion whether or not every read succeeded — the caller
     *   re-renders from whatever arrived rather than waiting for a full set that may never come.
     */
    fun refresh(context: Context, then: (() -> Unit)? = null) {
        val rc = AutelProductHolder.evo2?.remoteController ?: run { then?.invoke(); return }
        // getCommandStickMode / getGimbalDialAdjustSpeed / getYawCoefficient are ONE-SHOT — the
        // RC getters are measured one-shot (verified 2026-08-02/-03), and the `outstanding`
        // counter below is itself the proof at runtime: if any callback repeated, it would
        // decrement past zero and re-fire `then`, which has never been observed across connects.
        // Unlike the fly-controller's getVisualSettingInfo, these do not subscribe.
        var outstanding = 3
        fun done() { if (--outstanding <= 0) { evaluate(context); then?.invoke() } }

        runCatching {
            rc.getCommandStickMode(object : CallbackWithOneParam<RemoteControllerCommandStickMode> {
                override fun onSuccess(m: RemoteControllerCommandStickMode?) {
                    stickMode = m; AppLog.i(TAG, "stick mode: $m"); done()
                }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "getCommandStickMode failed: ${error?.description}"); done()
                }
            })
            rc.getGimbalDialAdjustSpeed(object : CallbackWithOneParam<Int> {
                override fun onSuccess(v: Int?) { dialSpeed = v; done() }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "getGimbalDialAdjustSpeed failed: ${error?.description}"); done()
                }
            })
            rc.getYawCoefficient(object : CallbackWithOneParam<Float> {
                override fun onSuccess(v: Float?) { yawCoefficient = v; done() }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "getYawCoefficient failed: ${error?.description}"); done()
                }
            })
        }.onFailure { AppLog.w(TAG, "rates refresh threw: ${it.message}"); then?.invoke() }
    }

    /** Decides whether the live values match Precision. No learning: both states are explicit. */
    private fun evaluate(@Suppress("UNUSED_PARAMETER") context: Context) {
        val d = dialSpeed ?: return
        val y = yawCoefficient ?: return
        precisionActive = near(d.toDouble(), PRECISION_DIAL.toDouble()) &&
            near(y.toDouble(), PRECISION_YAW.toDouble())
    }

    private fun near(a: Double, b: Double) = kotlin.math.abs(a - b) <= kotlin.math.abs(b) * MATCH_TOLERANCE + 0.5

    /**
     * Switches between Normal and Precision.
     *
     * Both values are clamped to the ranges the controller itself publishes, so a halved value
     * can never fall below what the hardware accepts.
     */
    fun setPrecision(context: Context, on: Boolean, then: (Boolean) -> Unit) {
        val rc = AutelProductHolder.evo2?.remoteController ?: run { then(false); return }
        var targetDial = if (on) PRECISION_DIAL else NORMAL_DIAL
        var targetYaw = if (on) PRECISION_YAW else NORMAL_YAW
        // Clamp to what the controller itself says it accepts, so a constant edited later can
        // never push a value the hardware rejects.
        runCatching {
            rc.parameterRangeManager?.let { r ->
                r.dialAdjustSpeed?.let { targetDial = targetDial.coerceIn(it.valueFrom, it.valueTo) }
                r.yawCoefficient?.let { targetYaw = targetYaw.coerceIn(it.valueFrom, it.valueTo) }
            }
        }

        // DO NOT WRITE A VALUE THE CONTROLLER ALREADY HOLDS. Each write draws an acknowledgement
        // beep from the controller, so a redundant push is not merely wasteful — the pilot hears
        // it. This is the second guard: even if something calls in again, an unchanged setting
        // makes no noise.
        val dialSame = dialSpeed?.let { it == targetDial } ?: false
        val yawSame = yawCoefficient?.let { kotlin.math.abs(it - targetYaw) < 0.001f } ?: false
        if (dialSame && yawSame) {
            AppLog.i(TAG, "control rates already ${if (on) "PRECISION" else "NORMAL"} " +
                "(dial=$targetDial yaw=$targetYaw) — no write")
            precisionActive = on
            then(true)
            return
        }

        var outstanding = 2
        var ok = true
        fun done(success: Boolean) {
            if (!success) ok = false
            if (--outstanding <= 0) {
                // Confirm against the controller rather than trusting the acks — this SDK has
                // been caught reporting success for changes it did not make.
                refresh(context) { then(ok) }
            }
        }
        AppLog.i(TAG, "control rates -> ${if (on) "PRECISION" else "NORMAL"} (dial=$targetDial yaw=$targetYaw)")
        runCatching {
            rc.setGimbalDialAdjustSpeed(targetDial, object : CallbackWithNoParam {
                override fun onSuccess() { done(true) }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "setGimbalDialAdjustSpeed failed: ${error?.description}"); done(false)
                }
            })
            rc.setYawCoefficient(targetYaw, object : CallbackWithNoParam {
                override fun onSuccess() { done(true) }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "setYawCoefficient failed: ${error?.description}"); done(false)
                }
            })
        }.onFailure { AppLog.w(TAG, "control rate change threw: ${it.message}"); then(false) }
    }

    /**
     * The stick mode as the pilot's own "Mode 1/2/3".
     *
     * MODE 3 = CHINA IS CONFIRMED ON HARDWARE (2026-08-02): the controller's own Command Stick
     * Mode screen had Mode 3 selected and the SDK reported CHINA. Modes 1 and 2 follow the
     * standard RC convention from that anchor and have NOT been separately confirmed — if a
     * pilot ever sees this disagree with the controller's own screen, that is why, and the
     * mapping below is the only thing to change.
     */
    fun stickModeLabel(): String = when (stickMode) {
        RemoteControllerCommandStickMode.JAPAN -> "Mode 1"
        RemoteControllerCommandStickMode.USA -> "Mode 2"
        RemoteControllerCommandStickMode.CHINA -> "Mode 3"
        else -> "—"
    }

    /**
     * Enforces the pilot's chosen stick mode, skipping the write if the controller already has
     * it — a needless write costs an audible acknowledgement beep.
     *
     * Does nothing if no mode has been chosen yet. The app must never INVENT a stick mode: a
     * default would swap throttle and pitch for a pilot who never opened Pre-Flight. Pre-Flight
     * seeds the choice from whatever the controller reports the first time, and from then on it
     * is enforced so Autel's app cannot change it behind the pilot's back.
     */
    fun pushStickMode(context: Context, then: (Boolean) -> Unit) {
        val chosen = savedStickModeId(context)
        if (chosen.isEmpty()) {
            AppLog.i(TAG, "no stick mode chosen yet — leaving the controller's own setting")
            then(true); return
        }
        val want = stickModeFor(chosen)
        if (stickMode == want) {
            AppLog.i(TAG, "stick mode already Mode $chosen ($want) — no write")
            then(true); return
        }
        val rc = AutelProductHolder.evo2?.remoteController ?: run { then(false); return }
        runCatching {
            rc.setCommandStickMode(want, object : CallbackWithNoParam {
                override fun onSuccess() {
                    AppLog.i(TAG, "stick mode enforced: Mode $chosen ($want)")
                    refresh(context) { then(true) }
                }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "stick mode Mode $chosen failed: ${error?.description}")
                    refresh(context) { then(false) }
                }
            })
        }.onFailure { then(false) }
    }

    /** The pilot's selection, applied to the controller at connect. */
    fun stickModeFor(id: String): RemoteControllerCommandStickMode = when (id) {
        "1" -> RemoteControllerCommandStickMode.JAPAN
        "3" -> RemoteControllerCommandStickMode.CHINA
        else -> RemoteControllerCommandStickMode.USA
    }
    fun idFor(m: RemoteControllerCommandStickMode?): String = when (m) {
        RemoteControllerCommandStickMode.JAPAN -> "1"
        RemoteControllerCommandStickMode.CHINA -> "3"
        else -> "2"
    }

    // ---- Pilot's saved choices, pushed to the controller on connect ----

    private const val PREFS = "takpilot2_rates"
    private const val KEY_PRECISION = "precision_selected"
    private const val KEY_STICK_MODE = "stick_mode_id"

    /**
     * The pilot's control-response choice, or **null until they have made one** — the same
     * contract as [savedStickModeId], so the app never imposes a setting it was not told.
     *
     * It used to be a plain Boolean defaulting to false, and [saveSelection] was never called
     * from anywhere. The two faults compounded: the preference stayed unwritten, so every connect
     * pushed NORMAL. A pilot who selected Precision had it actively undone at each launch rather
     * than merely forgotten (operator, 2026-08-02).
     */
    fun savedPrecisionOrNull(context: Context): Boolean? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_PRECISION)) return null
        return prefs.getBoolean(KEY_PRECISION, false)
    }

    /** For UI that needs a concrete value; treats "never chosen" as Normal. */
    fun savedPrecision(context: Context): Boolean = savedPrecisionOrNull(context) ?: false

    fun saveSelection(context: Context, precision: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PRECISION, precision).apply()
    }

    /** Blank until the pilot chooses, so the app does not impose a stick mode it was never told. */
    fun savedStickModeId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STICK_MODE, "") ?: ""

    fun saveStickModeId(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_STICK_MODE, id).apply()
    }

    /**
     * Applies the pilot's saved choices to the controller at connect.
     *
     * ⚠ STICK MODE IS ONLY PUSHED IF THE PILOT HAS ACTUALLY CHOSEN ONE. An unset preference
     * leaves the controller alone, because a default that silently rewrote the stick mode would
     * swap throttle and pitch for anyone who never opened Pre-Flight. Once chosen, it IS pushed
     * every connect — which is the point, and why the Enter Flight card shows it before the
     * pilot can reach the flight screen.
     *
     * CONTROL RESPONSE FOLLOWS THE SAME RULE (2026-08-02). It previously pushed a hardcoded
     * default of NORMAL on every connect, so Precision could not survive a launch. It is now
     * pushed only once the pilot has chosen, and then on every connect — so the choice holds
     * between sessions and is re-asserted if anything else changed it.
     */
    fun applyAtConnect(context: Context) {
        if (appliedForThisConnect) return
        appliedForThisConnect = true
        pushStickMode(context) { }
        val want = savedPrecisionOrNull(context)
        if (want == null) {
            AppLog.i(TAG, "control response: no pilot choice saved, leaving the controller as it is")
            return
        }
        // READ FIRST. Without the current values the "already correct" check below has nothing
        // to compare against, so a controller that was already in the right state would still be
        // written to — and still beep.
        refresh(context) {
            setPrecision(context, want) { ok ->
                AppLog.i(TAG, "control response applied at connect " +
                    "(${if (want) "PRECISION" else "NORMAL"}): ${if (ok) "OK" else "FAILED"}")
            }
        }
    }

    fun onProductDisconnected() {
        stickMode = null; dialSpeed = null; yawCoefficient = null; precisionActive = null
        appliedForThisConnect = false
    }
}
