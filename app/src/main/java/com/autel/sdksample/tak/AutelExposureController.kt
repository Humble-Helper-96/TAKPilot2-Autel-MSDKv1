package com.autel.sdksample.tak

import android.content.Context
import com.autel.common.CallbackWithNoParam
import com.autel.common.CallbackWithOneParam
import com.autel.common.camera.media.CameraISO
import com.autel.common.camera.media.ExposureCompensation
import com.autel.common.camera.media.ExposureMode
import com.autel.common.camera.media.ShutterSpeed
import com.autel.common.error.AutelError
import com.autel.sdk.camera.AutelXT706
import com.taklite.util.AppLog

/**
 * Autel counterpart to the DJI blueprint's `ExposureController`. Forces a known auto-exposure
 * setup on the camera so the FPV feed adapts to changing light instead of running on whatever
 * Autel Explorer last left it in, and makes the flight screen's EV slider actually bias it.
 *
 * The whole exposure API is on [AutelXT706], which the 640T's XT709 extends:
 * `setExposureMode`, `setExposure`, `setISO`, `setShutter`, `setAutoExposureLockState`,
 * `setSpotMeteringArea`, plus matching getters. An earlier pass recorded this as unavailable;
 * it isn't — see the plan doc's Step 4 revision.
 *
 * **Strategy mirrors the blueprint's final answer, not its first one.** DJI tried
 * SHUTTER_PRIORITY + fixed 1/60 + auto-ISO and the Mini 2 reported success, read the value back
 * correctly, and then never actually auto-exposed. It settled on PROGRAM (plain full auto).
 * Autel's equivalent of PROGRAM is [ExposureMode.Auto], so that's what this sends. Autel also
 * offers ShutterPriority/AperturePriority/Manual if the 640T turns out to behave differently —
 * but there is no reason to assume it does until someone flies it.
 *
 * **Not available on this SDK, unlike DJI:** metering mode. [com.autel.common.camera.media
 * .MeteringMode] exists as an enum and `XT706StateInfo.getMeteringMode()` reads it back, but no
 * public setter anywhere in the SDK takes one (checked across all 5,320 classes, not just the
 * camera package). The blueprint forces CENTER-weighted metering; here the camera keeps whatever
 * metering it is already in. `setSpotMeteringArea(x, y)` is the only metering control exposed,
 * and pointing metering at a spot is a different behaviour, not a substitute — so nothing is
 * sent. If the 640T meters badly on a high-contrast horizon, that's the lever to look at.
 */
object AutelExposureController {
    private const val TAG = "TP2Exposure"
    private const val PREFS = "takpilot2_tak"
    private const val KEY_EV = "exposure_ev"

    /**
     * Every real EV value, ordered **dark → bright**.
     *
     * Sorted by parsed numeric value rather than taken in declaration order, because Autel
     * declares this enum DESCENDING (`POSITIVE_3p0` first, `NEGATIVE_3p0` last) — the inverse
     * of DJI's ascending `N_5_0 .. P_5_0`. Porting the blueprint's ordinal arithmetic directly
     * would have silently inverted the slider: dragging toward "+" would darken the picture,
     * and it would look like it worked.
     */
    private val EV_ALL: List<ExposureCompensation> = ExposureCompensation.values()
        .filter { it != ExposureCompensation.UNKNOWN }
        .sortedBy { evValue(it) }

    /** `POSITIVE_2p7` -> +2.7, `NEGATIVE_1p3` -> -1.3, `POSITIVE_0` -> 0.0. */
    private fun evValue(ev: ExposureCompensation): Double {
        val n = ev.name
        val sign = if (n.startsWith("NEGATIVE_")) -1.0 else 1.0
        val digits = n.removePrefix("NEGATIVE_").removePrefix("POSITIVE_").replace('p', '.')
        return sign * (digits.toDoubleOrNull() ?: 0.0)
    }

    private val EV_ZERO: ExposureCompensation = ExposureCompensation.POSITIVE_0

    /** The pilot slider's range: -2.0 .. +2.0 EV in 1/3 stops (13 steps), same as the
     *  blueprint. Autel's own enum stops at ±3.0, so ±2.0 sits comfortably inside it — the
     *  blueprint capped at ±2.0 for the same reason after the Mini 2 rejected beyond +3.0. */
    val EV_SLIDER: List<ExposureCompensation> =
        EV_ALL.filter { evValue(it) >= -2.0 - 1e-6 && evValue(it) <= 2.0 + 1e-6 }

    val sliderMax: Int get() = EV_SLIDER.size - 1

    /**
     * Hidden brightness bias in 1/3-stop steps, added on top of what the pilot sets.
     *
     * **Deliberately 0 — this is a calibration constant, not a port.** The DJI build runs +2/3
     * EV here, but that number was arrived at by flying a Mini 2 three times (2026-07-22/23/25)
     * and is specific to that camera's metering and the CENTER-weighted mode it forces. The
     * 640T is a different sensor with metering this app can't even set. Carrying the number
     * over would be inventing a calibration; tune it here only after flying the 640T.
     */
    private const val HIDDEN_BIAS_STEPS = 0

    private fun biased(nominal: ExposureCompensation): ExposureCompensation {
        if (HIDDEN_BIAS_STEPS == 0) return nominal
        val i = EV_ALL.indexOf(nominal)
        return EV_ALL[(i + HIDDEN_BIAS_STEPS).coerceIn(0, EV_ALL.size - 1)]
    }

    /** Stored EV, clamped into the slider range so a value persisted by some other build can
     *  never be sent to the camera and rejected. */
    fun savedEv(context: Context): ExposureCompensation {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EV, null)
        val stored = name?.let { runCatching { ExposureCompensation.valueOf(it) }.getOrNull() }
            ?: return EV_ZERO
        if (!EV_ALL.contains(stored)) return EV_ZERO
        val v = evValue(stored).coerceIn(-2.0, 2.0)
        return EV_SLIDER.minByOrNull { kotlin.math.abs(evValue(it) - v) } ?: EV_ZERO
    }

    private fun saveEv(context: Context, ev: ExposureCompensation) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_EV, ev.name).apply()
    }

    /** Slider position (0..[sliderMax]) matching the stored EV. */
    fun savedSliderIndex(context: Context): Int =
        EV_SLIDER.indexOf(savedEv(context)).coerceAtLeast(0)

    fun labelAt(index: Int): String = evLabel(EV_SLIDER[index.coerceIn(0, sliderMax)])

    private fun evLabel(ev: ExposureCompensation): String {
        val v = evValue(ev)
        return if (v == 0.0) "0.0" else "%+.1f".format(v)
    }

    /**
     * Push the exposure setup to the camera. Called once the camera appears (see
     * [AutelProductHolder]'s camera-change listener) so the feed is in a known auto mode
     * rather than inheriting Autel Explorer's last state.
     *
     * No camera-mode switch first, unlike the blueprint: DJI had to force VIDEO mode because
     * the Mini 2 boots into photo mode and video-exposure settings don't drive the live FPV
     * until it does. Nothing in this SDK suggests the same split, and switching the 640T's
     * media mode out from under a pilot who deliberately set it would be a worse bug than the
     * one it would be speculatively fixing.
     */
    fun applyDefaults(context: Context, camera: AutelXT706?) {
        camera ?: return
        val ev = biased(savedEv(context))
        AppLog.i(TAG, "applyDefaults: exposureMode=${ExposureMode.Auto}, ev=$ev")
        camera.setExposureMode(ExposureMode.Auto, cb("setExposureMode(Auto)") {
            camera.setExposure(ev, cb("setExposure($ev)") { logReadback(camera) })
        })
    }

    /**
     * Apply the EV at slider [index], persisting **only on success** so the saved value always
     * reflects something the camera actually accepted. [onDone] gets the nominal label — what
     * the pilot asked for — so the UI shows their input, not the hidden-biased value.
     */
    fun setEvAt(context: Context, camera: AutelXT706?, index: Int, onDone: (String) -> Unit) {
        val nominal = EV_SLIDER[index.coerceIn(0, sliderMax)]
        if (camera == null) {
            // No camera: remember the setting so it's applied on the next connect, and tell the
            // caller the label anyway. Saving unverified is safe here only because there is no
            // camera state to contradict.
            saveEv(context, nominal)
            onDone(evLabel(nominal))
            return
        }
        val ev = biased(nominal)
        camera.setExposure(ev, object : CallbackWithNoParam {
            override fun onSuccess() {
                AppLog.i(TAG, "setExposure($ev) [nominal $nominal]: OK")
                saveEv(context, nominal)
                onDone(evLabel(nominal))
            }
            override fun onFailure(error: AutelError?) {
                AppLog.w(TAG, "setExposure($ev) failed: ${error?.description}")
                onDone(evLabel(nominal))
            }
        })
    }

    /** Read back what the camera actually applied — the definitive check that a reported-OK
     *  set didn't silently revert, which is exactly what caught the Mini 2's shutter-priority
     *  problem on the DJI side. */
    private fun logReadback(camera: AutelXT706) {
        camera.getExposureMode(one<ExposureMode>("readback exposureMode"))
        camera.getExposure(one<ExposureCompensation>("readback ev"))
        camera.getISO(one<CameraISO>("readback iso"))
        camera.getShutter(one<ShutterSpeed>("readback shutter"))
    }

    private fun cb(op: String, onOk: () -> Unit = {}) = object : CallbackWithNoParam {
        override fun onSuccess() {
            AppLog.i(TAG, "$op: OK")
            onOk()
        }
        override fun onFailure(error: AutelError?) {
            AppLog.w(TAG, "$op failed: ${error?.description}")
        }
    }

    private fun <T> one(op: String) = object : CallbackWithOneParam<T> {
        override fun onSuccess(v: T?) { AppLog.i(TAG, "$op=$v") }
        override fun onFailure(error: AutelError?) {
            AppLog.w(TAG, "$op failed: ${error?.description}")
        }
    }
}
