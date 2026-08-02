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

    /**
     * Invokes [then] EXACTLY ONCE, after the aircraft's live state is known.
     *
     * ⚠ THIS IS THE BUG THAT PUT AN AIRCRAFT INTO A WALL (2026-08-02). The previous version
     * passed [then] straight to `getVisualSettingInfo`'s callback, on the assumption that a
     * getter calls back once. It does not — on this firmware that callback fires about twice a
     * second, forever, a fact this very file already documented a few lines above. So the
     * "apply once at connect" enforcement actually ran at 2 Hz for the whole flight, and because
     * each write's completion handler kicked off ANOTHER perpetual reader, every write added a
     * new 2 Hz stream on top of the last. The fly-controller channel — the same one carrying
     * vision positioning and obstacle data — ended up saturated. The flight log shows the shape
     * of it: fourteen enforcement attempts, six acknowledgements inside one 700 ms burst, then
     * the channel timing out. The hover went unstable and avoidance did not stop an impact.
     *
     * So: latch on the first callback and never call back again. Do NOT "simplify" this by
     * handing the lambda to the SDK callback directly.
     *
     * No new listener is needed for the cached values themselves — [onProductConnected] already
     * arms a continuous one. This exists only for callers that need a "state is known now" edge.
     */
    private fun readOnce(then: () -> Unit) {
        val fc = AutelProductHolder.evo2?.flyController ?: run { then(); return }
        val fired = java.util.concurrent.atomic.AtomicBoolean(false)
        fun once() { if (fired.compareAndSet(false, true)) then() }
        runCatching {
            fc.getVisualSettingInfo(object : CallbackWithOneParam<VisualSettingInfo> {
                override fun onSuccess(info: VisualSettingInfo?) {
                    info?.let {
                        systemEnabled = it.isAvoidanceSystemEnable
                        avoidDuringRth = it.isDetectObstacleEnableWhenReturn
                        landingProtect = it.isLandingProtectEnable
                    }
                    once()
                }
                override fun onFailure(error: AutelError?) { once() }
            })
        }.onFailure { once() }
    }

    fun onProductDisconnected() {
        systemEnabled = null; avoidDuringRth = null; landingProtect = null
        radar = null
        lastRadarLogMs = 0L
        loggedConnectState = null
        appliedForThisConnect = false
    }

    /**
     * Logs radar samples, biased toward the ones that matter.
     *
     * REPLACED A 20-SAMPLE CAP (2026-08-02). The cap was written to characterise the feed on a
     * bench and it did that job, but it made the feed useless for the question that actually
     * mattered — why avoidance did not brake near an obstacle. All twenty samples were spent in
     * the first two seconds after connect, on the ground, and the flight itself logged nothing.
     *
     * So the rule is now "log what is close, and a slow heartbeat otherwise":
     *  - any face reporting a real obstacle nearer than [LOG_NEAR] is logged immediately, rate
     *    limited to [NEAR_MIN_GAP_MS] so a sustained approach does not flood
     *  - otherwise one heartbeat line every [IDLE_GAP_MS], to prove the feed is still alive
     *
     * READING A SAMPLE. Each face is a float[6] of sub-sectors. Two sentinel values, both
     * confirmed against live data: 0 means "this face was not in this push" (the aircraft sends
     * one face at a time, round-robin), and 10000 means "clear, nothing detected". Anything else
     * is a real distance. Units are believed CENTIMETRES and are still not confirmed — see
     * [nearestCm].
     */
    private fun logRadarSample(info: AvoidanceRadarInfo) {
        val now = android.os.SystemClock.elapsedRealtime()
        val near = nearestCm(info)
        val isNear = near != null && near < LOG_NEAR
        val gap = if (isNear) NEAR_MIN_GAP_MS else IDLE_GAP_MS
        if (now - lastRadarLogMs < gap) return
        lastRadarLogMs = now
        fun f(a: FloatArray?) = a?.joinToString(",") { "%.0f".format(it) } ?: "null"
        val tag = if (isNear) "NEAR ${near}cm" else "clear"
        AppLog.i(TAG, "radar($tag) F=[${f(info.front)}] R=[${f(info.rear)}] " +
            "L=[${f(info.left)}] Ri=[${f(info.right)}] " +
            "U=[${f(info.top)}] D=[${f(info.bottom)}] flying=${AutelTakBridge.airborne}")
    }

    /**
     * Smallest REAL obstacle distance across every face, or null if nothing is reporting.
     *
     * Skips both sentinels: 0 (face absent from this push) and 10000 (clear). Without that, the
     * minimum would always be 0 and every sample would look like an imminent collision.
     */
    fun nearestCm(info: AvoidanceRadarInfo?): Int? {
        info ?: return null
        var best: Float? = null
        for (face in listOf(info.front, info.rear, info.left, info.right, info.top, info.bottom)) {
            for (v in face ?: continue) {
                if (v <= 0f || v >= CLEAR_SENTINEL) continue
                if (best == null || v < best!!) best = v
            }
        }
        return best?.toInt()
    }

    /** "Nothing detected on this face." Confirmed live: clear faces report exactly this. */
    const val CLEAR_SENTINEL = 10000f

    /** Below this, a sample is worth a log line. ~15 m in the believed units. */
    private const val LOG_NEAR = 1500
    private const val NEAR_MIN_GAP_MS = 500L
    private const val IDLE_GAP_MS = 10_000L
    @Volatile private var lastRadarLogMs = 0L

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
        // NEVER rewrite a safety switch on an aircraft that is already flying. Enforcement is a
        // pre-flight act: the pilot reads what was applied on the Enter Flight card and then
        // launches. Writing avoidance settings underneath an airborne aircraft changes how it
        // behaves with nobody looking at the card, which is the opposite of the point.
        if (AutelTakBridge.airborne) {
            AppLog.w(TAG, "aircraft is airborne — SKIPPING avoidance enforcement this connect")
            return
        }
        readOnce {
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
     * @param onDone true if the aircraft ACKNOWLEDGED it — not that it took. This SDK returns
     *   success for things it does not do (see the camera's setAspectRatio), so the real state
     *   still has to be read back. That read comes from the continuous listener armed in
     *   [onProductConnected], which updates the cached values within about half a second.
     *
     * ⚠ Deliberately does NOT kick off its own read on completion. It used to, and that was half
     * of the runaway described on [readOnce]: each write left behind another 2 Hz reader that
     * never stopped, so writes bred readers until the fly-controller channel gave out. One
     * standing listener is all this needs.
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
                    onDone(true)       // the standing listener reports what actually took
                }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "avoidance switch $which -> $enabled failed: ${error?.description}")
                    onDone(false)
                }
            })
        }.onFailure {
            AppLog.w(TAG, "avoidance switch $which threw: ${it.message}")
            onDone(false)
        }
    }
}
