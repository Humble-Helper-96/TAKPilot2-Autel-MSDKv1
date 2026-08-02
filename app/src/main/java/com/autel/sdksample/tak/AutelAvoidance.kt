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
 * NEVER CHANGES ANYTHING BY ITSELF. It reports state continuously, and it can set a switch —
 * but [setSwitch] is only ever reached from an explicit pilot action in Pre-Flight. Nothing
 * here runs on connect, on resume, or from a saved preference, because an app that silently
 * disabled a safety system from a stale checkbox would look exactly like the aircraft doing it,
 * and the pilot would have no reason to look.
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
                    AppLog.i(TAG, "avoidance at connect: enabled=$systemEnabled " +
                        "rth-avoid=$avoidDuringRth landing-protect=$landingProtect")
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

    fun onProductDisconnected() {
        systemEnabled = null; avoidDuringRth = null; landingProtect = null
        radar = null
        radarLogCount = 0
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

    /**
     * Applies ONE avoidance switch on the aircraft, right now.
     *
     * Only ever called from an explicit pilot action in Pre-Flight. Nothing in this app calls it
     * on connect, on resume, or from a saved preference — see the layout note on section 7. An
     * app that silently disabled a safety system from a stale checkbox would be indistinguishable
     * from the aircraft doing it, and the pilot would have no reason to look.
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
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "avoidance refresh failed: ${error?.description}")
                    then?.invoke()
                }
            })
        }.onFailure { then?.invoke() }
    }
}
