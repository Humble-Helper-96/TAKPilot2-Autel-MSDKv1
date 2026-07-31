package com.autel.sdksample.tak

import android.os.Handler
import android.os.Looper
import com.taklite.util.AppLog
import com.autel.common.CallbackWithOneParam
import com.autel.common.battery.evo.EvoBatteryInfo
import com.autel.common.error.AutelError
import com.autel.common.flycontroller.evo.EvoFlyControllerInfo
import com.autel.common.gimbal.evo.EvoAngleInfo
import com.taklite.client.tak.TakManager
import kotlin.math.sqrt

/**
 * AutelTakBridge — port of TAKPilot2's DroneTakBridge from DJI MSDK v5 to Autel MSDK v1.5.
 *
 * Reads EVO II V3 telemetry and pushes the aircraft to a TAK server as a distinct air track
 * (separate from the operator's PLI), on a fixed interval.
 *
 * Key architectural difference vs the DJI original: DJI's KeyManager allows synchronous
 * getValue() polling; Autel v1.5 is listener-only. So we subscribe once to the fly-controller,
 * battery, and gimbal listeners, cache the latest values, and the 2 s tick reads the cache —
 * same shape as the original's "live gimbal cache" but applied to everything.
 *
 * Field mapping (from the project's Phase 0 tracker §4.4, decompiled-AAR verified):
 *   point lat/lon      ← EvoGpsInfo.getLatitude()/getLongitude()
 *   point hae          ← EvoGpsInfo.getAltitude()        (HAE — NOT LocalCoordinateInfo!)
 *   track speed (m/s)  ← LocalCoordinateInfo.getSpeed()  (via AltitudeAndSpeedInfo)
 *   track course       ← EvoAttitudeInfo.getYaw()        (deg; normalized 0..360)
 *   battery %          ← EvoBatteryInfo.getRemainingPercent()
 *   gimbal pitch/yaw   ← EvoAngleInfo (setAngleListener)
 */
class AutelTakBridge(
    private val fallbackUid: String,
    private val droneCallsign: String,
    private val intervalMs: Long = 2000L,
) {
    private val tak = TakManager.getInstance()
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    // Stable per-airframe uid. The EVO II serial isn't trivially exposed on v1.5, so the
    // uid comes from config (matches the project guide: uid derives from the callsign).
    private val droneUid: String get() = fallbackUid

    /** Optional RTSP/stream url to advertise in the drone CoT. */
    @Volatile var videoUrl: String? = null

    /** When true, also push the camera slant point (sensor point of interest). */
    @Volatile var cameraPointEnabled: Boolean = false

    private val spiUid: String by lazy { "$droneUid-SPI" }

    // ---- Live telemetry cache (written by SDK listeners, read by the tick) ----
    @Volatile private var lat = Double.NaN
    @Volatile private var lon = Double.NaN
    @Volatile private var hae = Double.NaN          // EvoGpsInfo.getAltitude() — HAE
    @Volatile private var mslAlt = Double.NaN       // EvoGpsInfo.getHeightMeanSeaLevel()
    @Volatile private var relAlt = Double.NaN       // LocalCoordinateInfo altitude (above takeoff)
    @Volatile private var speedMs = 0.0             // ground speed m/s
    @Volatile private var headingDeg = 0.0          // aircraft yaw, 0..360
    @Volatile private var batteryPct = 0
    @Volatile private var voltage = 0.0
    @Volatile private var batteryCapMah = 0.0
    @Volatile private var satCount = 0
    @Volatile private var horizAccM = -1.0          // meters, -1 = unknown
    @Volatile private var vertAccM = -1.0
    @Volatile private var liveGimbalPitch: Double? = null
    @Volatile private var liveGimbalYaw: Double? = null
    @Volatile private var homeLat = Double.NaN
    @Volatile private var homeLon = Double.NaN
    @Volatile private var homeSet = false

    // One-shot guard so the pilot-configured flight-safety limits (max altitude/radius/RTH
    // height) are pushed exactly once per connect, off the first real telemetry report — a
    // reliable "the flight controller is actually up" signal, same pattern as the DJI sibling.
    @Volatile private var limitsApplied = false

    // Sensor FOV cone state, embedded in the drone PLI so ATAK/taklite draw the cone
    // natively. -1 = omit.
    @Volatile private var sensorFov = -1.0
    @Volatile private var sensorVfov = -1.0
    @Volatile private var sensorAzimuth = -1.0
    @Volatile private var sensorElevation = 0.0
    @Volatile private var sensorRange = -1.0

    /** Active camera spectrum; the 640T is EO + thermal. Sets the base FOV. */
    enum class Lens { EO, IR }
    @Volatile var activeLens: Lens = Lens.EO
    /** Live digital zoom ratio (1.0 = none). Setter for future camera-listener wiring. */
    @Volatile var liveZoom: Double = 1.0

    private val tick = object : Runnable {
        override fun run() {
            try {
                pushOnce()
            } catch (t: Throwable) {
                AppLog.w(TAG, "telemetry push failed: ${t.message}")
            }
            if (running) handler.postDelayed(this, intervalMs)
        }
    }

    fun start() {
        if (running) return
        running = true
        // New session = new flight = a new takeoff point, so the latched terrain reference
        // from the last one must not carry over (see TerrainAgl).
        TerrainAgl.reset()
        limitsApplied = false
        subscribe()
        handler.post(tick)
        AppLog.i(TAG, "AutelTakBridge started ($droneCallsign / $droneUid, every ${intervalMs}ms)")
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        unsubscribe()
        AppLog.i(TAG, "AutelTakBridge stopped")
    }

    /** Re-arm listeners after an aircraft reconnect (registrations die with the product). */
    fun onProductConnected() { if (running) subscribe() }

    // ---- SDK subscriptions ----

    fun subscribe() {
        val evo = AutelProductHolder.evo2 ?: run {
            AppLog.i(TAG, "subscribe: no aircraft yet (will re-arm on productConnected)")
            return
        }
        evo.flyController.setFlyControllerInfoListener(object :
            CallbackWithOneParam<EvoFlyControllerInfo> {
            override fun onSuccess(info: EvoFlyControllerInfo?) {
                info ?: return
                if (!limitsApplied) {
                    limitsApplied = true
                    val context = com.autel.sdksample.TestApplication.getInstance()
                    if (context != null) {
                        FlightLimitsController.applyDefaults(context, evo.flyController)
                    }
                }
                info.gpsInfo?.let { gps ->
                    lat = gps.latitude
                    lon = gps.longitude
                    hae = gps.altitude
                    mslAlt = gps.heightMeanSeaLevel.toDouble()
                    satCount = gps.satellitesVisible
                    // Accuracy fields believed mm (standard GNSS struct) — sanity-clamped.
                    // Verify units on the bench (tracker §4.2) and adjust ACC_DIVISOR.
                    val h = gps.horizontalAccuracy / ACC_DIVISOR
                    val v = gps.verticalAccuracy / ACC_DIVISOR
                    horizAccM = if (h in 0.01..500.0) h else -1.0
                    vertAccM = if (v in 0.01..500.0) v else -1.0
                }
                info.localCoordinateInfo?.let { local ->
                    relAlt = local.altitude.toDouble()
                    val s = local.speed.toDouble()
                    speedMs = if (s.isFinite() && s >= 0) s else run {
                        val x = local.xSpeed.toDouble(); val y = local.ySpeed.toDouble()
                        sqrt(x * x + y * y)
                    }
                    // Home point — the takeoff-terrain reference TerrainAgl latches from.
                    // getHomeEnable() is the SDK's own "has a home point been recorded yet"
                    // flag; until then homeLatitude/Longitude are meaningless zeros.
                    homeSet = local.homeEnable != 0
                    homeLat = local.homeLatitude
                    homeLon = local.homeLongitude
                }
                info.attitudeInfo?.let { att ->
                    headingDeg = CameraSlantPoint.norm360(att.yaw)
                }
            }
            override fun onFailure(error: AutelError?) {
                AppLog.w(TAG, "flyController listener error: ${error?.description}")
            }
        })
        evo.battery.setBatteryStateListener(object : CallbackWithOneParam<EvoBatteryInfo> {
            override fun onSuccess(info: EvoBatteryInfo?) {
                info ?: return
                batteryPct = info.remainingPercent
                voltage = info.voltage.toDouble()
                batteryCapMah = info.capacity.toDouble()
            }
            override fun onFailure(error: AutelError?) {
                AppLog.w(TAG, "battery listener error: ${error?.description}")
            }
        })
        evo.gimbal.setAngleListener(object : CallbackWithOneParam<EvoAngleInfo> {
            override fun onSuccess(info: EvoAngleInfo?) {
                info ?: return
                liveGimbalPitch = info.pitch.toDouble()
                liveGimbalYaw = info.yaw.toDouble()
            }
            override fun onFailure(error: AutelError?) {
                AppLog.w(TAG, "gimbal listener error: ${error?.description}")
            }
        })
        AppLog.i(TAG, "telemetry listeners armed")
    }

    private fun unsubscribe() {
        val evo = AutelProductHolder.evo2 ?: return
        runCatching { evo.flyController.setFlyControllerInfoListener(null) }
        runCatching { evo.battery.setBatteryStateListener(null) }
        runCatching { evo.gimbal.setAngleListener(null) }
    }

    // ---- The 2 s report ----

    private fun pushOnce() {
        if (!tak.isConnected) return
        val lat = this.lat
        val lon = this.lon
        if (!isValidLat(lat) || !isValidLon(lon)) return   // no GPS fix — skip, don't send 0,0
        val hae = if (this.hae.isFinite()) this.hae else 0.0

        val gimbalPitch = liveGimbalPitch ?: 0.0
        val gimbalYaw = liveGimbalYaw ?: 0.0

        // Compute the camera look-point + sensor FOV BEFORE the PLI, so the PLI can carry
        // the <sensor> element (ATAK/taklite draw the FOV cone from it).
        if (cameraPointEnabled) {
            pushCameraPoint(lat, lon, aglMeters(), headingDeg)
        } else {
            sensorFov = -1.0; sensorVfov = -1.0; sensorAzimuth = -1.0
            sensorElevation = 0.0; sensorRange = -1.0
        }

        val isFlying = relAlt.isFinite() && (relAlt > 0.5 || speedMs > 0.5)

        // north reference stays 0.0 — <sensor azimuth> is an ABSOLUTE true-north bearing
        // (the DJI original calibrated this the hard way; see its 2026-07 comment).
        tak.sendDronePLI(droneUid, droneCallsign, lat, lon, hae, headingDeg, speedMs, batteryPct,
            videoUrl, spiUid,
            sensorFov, sensorVfov, sensorAzimuth, sensorElevation, sensorRange, 0.0,
            0.0, gimbalPitch, gimbalYaw,
            isFlying, 0,
            batteryCapMah.toInt(), (batteryCapMah * batteryPct / 100.0).toInt(), voltage)

        AppLog.v(TAG, "PLI push: lat=$lat lon=$lon hae=${"%.1f".format(hae)} hdg=${"%.0f".format(headingDeg)} " +
                "spd=${"%.1f".format(speedMs)} bat=$batteryPct% flying=$isFlying")
    }

    /**
     * Height above the ground the camera ray hits — used by the slant-point math, which
     * assumes flat ground at that height when there's no DTED coverage. Best available
     * estimate: altitude above the takeoff point ([relAlt]); falls back to 0 (which trips the
     * fallback range). When DTED coverage exists, [pushCameraPoint]/[lookPoint] refine this
     * flat-ground starting point against actual terrain via [CameraSlantPoint]'s
     * `elevationAt`/`aircraftMslMeters` params — see [aircraftMsl]/[elevationLookup].
     */
    private fun aglMeters(): Double = if (relAlt.isFinite() && relAlt > 0) relAlt else 0.0

    /** Aircraft altitude above MEAN SEA LEVEL, or null before the takeoff terrain reference
     *  latches. [heightAboveTakeoff] is the bridge's own altitude; adding the takeoff point's
     *  terrain elevation puts it in the same frame as the DTED samples the slant solver
     *  compares against. */
    private fun aircraftMsl(heightAboveTakeoff: Double): Double? =
        TerrainAgl.takeoffTerrainElevMsl?.plus(heightAboveTakeoff)

    /** DTED-backed elevation lookup for [CameraSlantPoint], or null if no tile covers the
     *  point (that's the normal case until the pilot uploads coverage for the area — the
     *  math falls back to the flat-ground estimate). */
    private fun elevationLookup(lat: Double, lon: Double): Double? {
        val context = com.autel.sdksample.TestApplication.getInstance() ?: return null
        return DtedIndex.elevationAt(context, lat, lon)
    }

    /**
     * True geographic bearing the camera points along.
     *
     * ⚠ CALIBRATION REQUIRED (tracker open items #6/#7): the EVO II gimbal-yaw reference
     * frame is unverified — the DJI original needed a constant +105° offset discovered in
     * flight. Model used here: bearing = gimbal yaw treated as absolute + [BEARING_OFFSET_DEG];
     * if flight testing shows it's body-relative, switch to heading + yaw via
     * [BEARING_MODE_RELATIVE]. Both paths are logged each SPI push for exactly that test.
     */
    private fun cameraBearing(rawYaw: Double, aircraftHeading: Double): Double =
        if (BEARING_MODE_RELATIVE) CameraSlantPoint.norm360(aircraftHeading + rawYaw + BEARING_OFFSET_DEG)
        else CameraSlantPoint.norm360(rawYaw + BEARING_OFFSET_DEG)

    private fun pushCameraPoint(lat: Double, lon: Double, aglMeters: Double, aircraftHeading: Double) {
        val pitch = liveGimbalPitch
        val yaw = liveGimbalYaw
        if (pitch == null || yaw == null) {
            AppLog.d(TAG, "SPI skip: gimbal attitude not yet received")
            return
        }
        val bearing = cameraBearing(yaw, aircraftHeading)
        // ⚠ Gimbal pitch sign convention unverified on Autel (tracker item #6). Assumed
        // DJI-like: level = 0, looking down = negative. Flip PITCH_SIGN to -1.0 if the
        // SPI lands behind/above the aircraft in testing.
        val pitchAdj = pitch * PITCH_SIGN + PITCH_OFFSET_DEG

        val gp = CameraSlantPoint.compute(
            lat, lon, aglMeters, bearing, pitchAdj, ::elevationLookup, aircraftMsl(aglMeters))
        tak.sendCameraPoint(spiUid, droneUid, "$droneCallsign-SPI", gp.lat, gp.lon, gp.rangeMeters)

        val zoom = liveZoom
        val (baseH, baseV) = baseFov()
        sensorFov = baseH / zoom
        sensorVfov = baseV / zoom
        sensorAzimuth = bearing
        sensorElevation = pitchAdj
        sensorRange = gp.rangeMeters
        val headingPlusYaw = CameraSlantPoint.norm360(aircraftHeading + yaw)
        AppLog.d(TAG, "SPI: lens=$activeLens pitch=$pitch yaw=$yaw heading=${"%.0f".format(aircraftHeading)} " +
            "az=${"%.0f".format(bearing)} headingPlusYaw=${"%.0f".format(headingPlusYaw)} " +
            "agl=${"%.0f".format(aglMeters)} zoom=$zoom fov=${"%.1f".format(sensorFov)} range=${Math.round(gp.rangeMeters)}m")
    }

    /** Base horizontal/vertical FOV (deg) for the active 640T camera, before zoom. */
    private fun baseFov(): Pair<Double, Double> = when (activeLens) {
        Lens.IR -> IR_HFOV to IR_VFOV
        else -> EO_HFOV to EO_VFOV
    }

    private fun isValidLat(v: Double) = v.isFinite() && v != 0.0 && v >= -90.0 && v <= 90.0
    private fun isValidLon(v: Double) = v.isFinite() && v != 0.0 && v >= -180.0 && v <= 180.0

    /**
     * One-shot ground point the camera is currently aimed at (for "drop marker at
     * look-point"). Returns (lat, lon, alt) or null if GPS/gimbal aren't ready yet.
     */
    fun lookPoint(): Triple<Double, Double, Double>? {
        val pitch = liveGimbalPitch ?: return null
        val yaw = liveGimbalYaw ?: return null
        val lat = this.lat; val lon = this.lon
        if (!isValidLat(lat) || !isValidLon(lon)) return null
        val bearing = cameraBearing(yaw, headingDeg)
        val agl = aglMeters()
        val gp = CameraSlantPoint.compute(
            lat, lon, agl, bearing, pitch * PITCH_SIGN + PITCH_OFFSET_DEG,
            ::elevationLookup, aircraftMsl(agl))
        // Third element is the target's terrain elevation, which dropped markers publish as
        // their CoT hae. 0.0 when there's no DTED coverage — same "unknown, assume sea level"
        // fallback the SPI push has always used.
        return Triple(gp.lat, gp.lon, gp.elevationMeters)
    }

    // ---- HUD accessors for FlightActivity ----
    data class Hud(
        val lat: Double, val lon: Double, val relAlt: Double, val hae: Double,
        val speedMs: Double, val headingDeg: Double, val batteryPct: Int,
        val sats: Int, val gimbalPitch: Double?, val hasFix: Boolean,
        val homeLat: Double, val homeLon: Double, val homeSet: Boolean,
    )
    fun hud(): Hud = Hud(lat, lon, relAlt, hae, speedMs, headingDeg, batteryPct, satCount,
        liveGimbalPitch, isValidLat(lat) && isValidLon(lon), homeLat, homeLon, homeSet)

    /** Terrain-corrected AGL + MSL reading for the current telemetry snapshot (Phase 2 HUD
     *  wiring reads this); see [TerrainAgl]. Needs an app Context for the DTED lookup. */
    fun aglReading(): TerrainAgl.Reading? {
        val context = com.autel.sdksample.TestApplication.getInstance() ?: return null
        return TerrainAgl.reading(context, hud())
    }

    companion object {
        private const val TAG = "AutelTakBridge"

        /** GNSS accuracy raw units → meters. Believed mm (÷1000); bench-verify. */
        private const val ACC_DIVISOR = 1000.0

        // ---- SPI calibration constants (flight-test these; see tracker §4.6 open items) ----
        /** Added to the gimbal yaw to reach true bearing. DJI needed +105; Autel starts at 0. */
        private const val BEARING_OFFSET_DEG = 0.0
        /** false: gimbal yaw treated as absolute; true: body-relative (heading + yaw). */
        private const val BEARING_MODE_RELATIVE = false
        /** +1.0 assumes DJI-like pitch (down = negative). Flip to -1.0 if inverted. */
        private const val PITCH_SIGN = 1.0
        /** Slant-range fine-tune, added to pitch after sign correction. */
        private const val PITCH_OFFSET_DEG = 0.0

        // EVO II Dual 640T V3 per-camera FOV (deg) at 1x — CALIBRATION CONSTANTS.
        // Start values from published specs; confirm against the live cone in ATAK.
        private const val EO_HFOV = 79.0; private const val EO_VFOV = 62.0
        private const val IR_HFOV = 42.0; private const val IR_VFOV = 34.0
    }
}

/** Process-wide holder so the bridge survives screen navigation (1:1 with TAKPilot2). */
object TakBridgeHolder {
    private var bridge: AutelTakBridge? = null
    private var videoUrl: String? = null
    private var cameraPointEnabled = false

    fun start(droneUid: String, droneCallsign: String) {
        bridge?.stop()
        bridge = AutelTakBridge(droneUid, droneCallsign).also {
            it.videoUrl = videoUrl
            it.cameraPointEnabled = cameraPointEnabled
            it.start()
        }
    }

    fun stop() {
        bridge?.stop()
        bridge = null
    }

    /** Called by [AutelProductHolder] when the aircraft (re)connects — re-arm listeners. */
    fun onProductConnected() { bridge?.onProductConnected() }
    fun onProductDisconnected() { /* cache goes stale; PLI keeps last fix until fresh data */ }

    fun setVideoUrl(url: String?) {
        videoUrl = url?.takeIf { it.isNotBlank() }
        bridge?.videoUrl = videoUrl
    }

    fun setCameraPointEnabled(enabled: Boolean) {
        cameraPointEnabled = enabled
        bridge?.cameraPointEnabled = enabled
    }

    val isCameraPointEnabled: Boolean get() = cameraPointEnabled
    val isRunning: Boolean get() = bridge != null

    fun lookPoint(): Triple<Double, Double, Double>? = bridge?.lookPoint()
    fun hud(): AutelTakBridge.Hud? = bridge?.hud()
}
