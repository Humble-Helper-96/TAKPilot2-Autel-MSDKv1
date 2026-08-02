package com.autel.sdksample.tak

import com.autel.common.CallbackWithOneParam
import com.autel.common.error.AutelError
import com.autel.common.flycontroller.visual.AvoidanceRadarInfo
import com.autel.common.flycontroller.visual.VisualSettingInfo
import com.taklite.util.AppLog

/**
 * Obstacle-avoidance state, cached process-wide.
 *
 * WHY THIS EXISTS. Until now this app was completely blind to avoidance: it never read the
 * state, never displayed it, and never set it. So avoidance was in whatever state something
 * else left it — Autel Explorer, or the aircraft's own default — and NOTHING on any screen
 * told the pilot which. A disabled avoidance system that looks identical to an enabled one is
 * the same class of problem as the low-battery RTH the app could not see: the aircraft behaves
 * differently and the pilot has no way to know.
 *
 * PRE-FLIGHT IS THE SOURCE OF TRUTH, PUSHED AT CONNECT (operator, 2026-08-02).
 *
 * This deliberately REVERSED an earlier rule that said the app must never push a saved avoidance
 * state. That rule was protecting against a stale checkbox silently disabling a safety system —
 * a real hazard, but it left a worse one in place: Autel's own app can set avoidance to anything
 * it likes, and a pilot launching TAKPilot had no idea what they were about to fly with. Leaving
 * "whatever was there" is not neutral, it is unknown.
 *
 * So the settings are now enforced from Pre-Flight on every connect, AND shown on the Enter
 * Flight card before the pilot can reach the flight screen. Enforcement plus visibility beats
 * non-interference — the pilot can always see what was applied, which is what makes pushing
 * safe. The defaults are all ON, so an install that has never been configured errs toward
 * protection rather than away from it.
 *
 * Two independent feeds, both from the fly controller's visual interface:
 *  - [VisualSettingInfo]  — the SETTINGS (is avoidance on, is it on during RTH, etc.)
 *  - [AvoidanceRadarInfo] — LIVE obstacle distances, six faces, six sub-sectors each
 */
object AutelAvoidance {
    private const val TAG = "AutelAvoidance"

    /** True/false once the aircraft has told us; null until then. Null is NOT "off" — the
     *  difference matters, because "we do not know yet" and "it is disabled" warrant different
     *  words in front of a pilot. */
    @Volatile var systemEnabled: Boolean? = null
        private set
    @Volatile var avoidDuringRth: Boolean? = null
        private set
    @Volatile var landingProtect: Boolean? = null
        private set

    /** Latest radar sample, or null if none has arrived. Faces are front/rear/left/right/
     *  top/bottom, each a float[6] of sub-sectors. UNITS ARE NOT YET VERIFIED — see
     *  [logRadarSample]. */
    @Volatile var radar: AvoidanceRadarInfo? = null
        private set

    private var radarLogCount = 0
    @Volatile private var loggedConnectState: Boolean? = null

    /** Wired from [AutelProductHolder] on every (re)connect — listener registrations do not
     *  survive a product cycle. */
    fun onProductConnected() {
        val fc = AutelProductHolder.evo2?.flyController ?: return
        runCatching {
            fc.setVisualSettingInfoListener(object : CallbackWithOneParam<VisualSettingInfo> {
                override fun onSuccess(info: VisualSettingInfo?) {
                    info ?: return
                    val was = systemEnabled
                    systemEnabled = info.isAvoidanceSystemEnable
                    avoidDuringRth = info.isDetectObstacleEnableWhenReturn
                    landingProtect = info.isLandingProtectEnable
                    // Logged only on CHANGE: this listener pushes continuously, and a per-tick
                    // line would bury everything else in the flight log.
                    if (was != systemEnabled) {
                        AppLog.i(TAG, "avoidance system ${if (systemEnabled == true) "ENABLED" else "DISABLED"} " +
                            "(rth-avoid=$avoidDuringRth landing-protect=$landingProtect)")
                    }
                }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "visual setting listener error: ${error?.description}")
                }
            })
            // One-shot read as well: the listener only fires on the aircraft's own schedule, and
            // the home screen wants an answer as soon as the product syncs rather than whenever
            // the next push happens to arrive.
            fc.getVisualSettingInfo(object : CallbackWithOneParam<VisualSettingInfo> {
                override fun onSuccess(info: VisualSettingInfo?) {
                    info ?: return
                    systemEnabled = info.isAvoidanceSystemEnable
                    avoidDuringRth = info.isDetectObstacleEnableWhenReturn
                    landingProtect = info.isLandingProtectEnable
                    // Logged only on CHANGE. getVisualSettingInfo LOOKS like a one-shot read
                    // but its callback fires about twice a second on this firmware, so an
                    // unconditional line here buried the rest of the flight log.
                    if (loggedConnectState != systemEnabled) {
                        loggedConnectState = systemEnabled
                        AppLog.i(TAG, "avoidance at connect: enabled=$systemEnabled " +
                            "rth-avoid=$avoidDuringRth landing-protect=$landingProtect")
                    }
                }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "getVisualSettingInfo failed: ${error?.description}")
                }
            })
            fc.setAvoidanceRadarInfoListener(object : CallbackWithOneParam<AvoidanceRadarInfo> {
                override fun onSuccess(info: AvoidanceRadarInfo?) {
                    info ?: return
                    radar = info
                    logRadarSample(info)
                }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "avoidance radar listener error: ${error?.description}")
                }
            })
            AppLog.i(TAG, "avoidance listeners armed")
        }.onFailure { AppLog.w(TAG, "avoidance listener install failed: ${it.message}") }
    }

    /** Re-reads live state, then invokes [then] whether or not the read succeeded. */
    fun refresh(then: (() -> Unit)? = null) {
        val fc = AutelProductHolder.evo2?.flyController ?: run { then?.invoke(); return }
        runCatching {
            fc.getVisualSettingInfo(object : CallbackWithOneParam<VisualSettingInfo> {
                override fun onSuccess(info: VisualSettingInfo?) {
                    info?.let {
                        systemEnabled = it.isAvoidanceSystemEnable
                        avoidDuringRth = it.isDetectObstacleEnableWhenReturn
                        landingProtect = it.isLandingProtectEnable
                    }
                    then?.invoke()
                }
                override fun onFailure(error: AutelError?) { then?.invoke() }
            })
        }.onFailure { then?.invoke() }
    }

    fun onProductDisconnected() {
        systemEnabled = null; avoidDuringRth = null; landingProtect = null
        radar = null
        radarLogCount = 0
        loggedConnectState = null
        appliedForThisConnect = false
    }

    /**
     * Logs the first few raw radar samples verbatim.
     *
     * DELIBERATELY RAW AND DELIBERATELY LIMITED. Nothing documents what these floats mean:
     * whether they are metres or centimetres, whether 0 means "clear" or "no reading", or
     * whether larger is nearer or further. An on-screen obstacle warning built on a guessed
     * sign would show DANGER when the path is clear, which is worse than showing nothing at
     * all — so the numbers get characterised against a real flight first.
     *
     * Capped at [RADAR_LOG_SAMPLES] because this feed pushes continuously and the point is a
     * characterisation sample, not a permanent trace.
     */
    private fun logRadarSample(info: AvoidanceRadarInfo) {
        if (radarLogCount >= RADAR_LOG_SAMPLES) return
        radarLogCount++
        fun f(a: FloatArray?) = a?.joinToString(",") { "%.2f".format(it) } ?: "null"
        AppLog.i(TAG, "radar[$radarLogCount] ts=${info.timeStamp} " +
            "F=[${f(info.front)}] R=[${f(info.rear)}] " +
            "L=[${f(info.left)}] Ri=[${f(info.right)}] " +
            "U=[${f(info.top)}] D=[${f(info.bottom)}]")
    }

    private const val RADAR_LOG_SAMPLES = 20

    // ---- Pre-Flight's saved intent, enforced on every connect ----

    private const val PREFS = "takpilot2_avoid"
    private const val KEY_SYSTEM = "avoid_system"
    private const val KEY_RTH = "avoid_rth"
    private const val KEY_LANDING = "avoid_landing"

    /** Defaults are ON. An install nobody has configured must err toward protection. */
    fun savedSystem(c: android.content.Context) = prefs(c).getBoolean(KEY_SYSTEM, true)
    fun savedRth(c: android.content.Context) = prefs(c).getBoolean(KEY_RTH, true)
    fun savedLanding(c: android.content.Context) = prefs(c).getBoolean(KEY_LANDING, true)

    fun saveIntent(c: android.content.Context, system: Boolean, rth: Boolean, landing: Boolean) {
        prefs(c).edit().putBoolean(KEY_SYSTEM, system).putBoolean(KEY_RTH, rth)
            .putBoolean(KEY_LANDING, landing).apply()
    }

    private fun prefs(c: android.content.Context) =
        c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    @Volatile private var appliedForThisConnect = false

    /**
     * Enforces the Pre-Flight selection on the aircraft, once per connect.
     *
     * Only writes switches that are actually WRONG. Every needless write costs a round trip and,
     * on the controller side, an audible acknowledgement — the same mistake that produced a burst
     * of beeps when the control-rate push went unguarded.
     */
    fun applyAtConnect(context: android.content.Context) {
        if (appliedForThisConnect) return
        appliedForThisConnect = true
        refresh {
            val want = listOf<Triple<com.autel.common.flycontroller.visual.VisualSettingSwitchblade, Boolean, Boolean?>>(
                Triple(com.autel.common.flycontroller.visual.VisualSettingSwitchblade.AVOIDANCE_SYSTEM,
                    savedSystem(context), systemEnabled),
                Triple(com.autel.common.flycontroller.visual.VisualSettingSwitchblade.RETURN_TO_HOME_AVOIDANCE,
                    savedRth(context), avoidDuringRth),
                Triple(com.autel.common.flycontroller.visual.VisualSettingSwitchblade.LANDING_PROTECT,
                    savedLanding(context), landingProtect),
            )
            var changed = 0
            for ((which, desired, actual) in want) {
                if (actual == desired) continue
                changed++
                AppLog.i(TAG, "enforcing $which -> $desired (aircraft had $actual)")
                setSwitch(which, desired) { }
            }
            if (changed == 0) AppLog.i(TAG, "avoidance already matches Pre-Flight — no writes")
        }
    }

    /**
     * Applies ONE avoidance switch on the aircraft, right now.
     *
     * Called from the Pre-Flight toggles AND from [applyAtConnect]. See the class note for why
     * pushing a saved state is the right trade here.
     *
     * @param onDone true if the aircraft accepted it. The caller re-reads the real state rather
     *   than assuming the switch took, because this SDK returns success for things it does not
     *   do (see the camera's setAspectRatio).
     */
    fun setSwitch(
        which: com.autel.common.flycontroller.visual.VisualSettingSwitchblade,
        enabled: Boolean,
        onDone: (Boolean) -> Unit,
    ) {
        val fc = AutelProductHolder.evo2?.flyController ?: run { onDone(false); return }
        runCatching {
            fc.setVisualSettingEnable(which, enabled, object : com.autel.common.CallbackWithNoParam {
                override fun onSuccess() {
                    AppLog.i(TAG, "avoidance switch $which -> $enabled: accepted")
                    refresh()          // confirm against the aircraft, do not trust the ack
                    onDone(true)
                }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "avoidance switch $which -> $enabled failed: ${error?.description}")
                    refresh()
                    onDone(false)
                }
            })
        }.onFailure {
            AppLog.w(TAG, "avoidance switch $which threw: ${it.message}")
            onDone(false)
        }
    }

    /** Re-reads the live state from the aircraft. */
    fun refreshUnused(then: (() -> Unit)? = null) {
        val fc = AutelProductHolder.evo2?.flyController ?: run { then?.invoke(); return }
        runCatching {
            fc.getVisualSettingInfo(object : CallbackWithOneParam<VisualSettingInfo> {
                override fun onSuccess(info: VisualSettingInfo?) {
                    info?.let {
                        systemEnabled = it.isAvoidanceSystemEnable
                        avoidDuringRth = it.isDetectObstacleEnableWhenReturn
                        landingProtect = it.isLandingProtectEnable
                    }
                    then?.invoke()
                }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "avoidance refresh failed: ${error?.description}")
                    then?.invoke()
                }
            })
        }.onFailure { then?.invoke() }
    }
}
