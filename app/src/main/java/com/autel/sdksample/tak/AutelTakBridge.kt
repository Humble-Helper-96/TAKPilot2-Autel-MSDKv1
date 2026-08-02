package com.autel.sdksample.tak

import android.os.Handler
import android.os.Looper
import com.taklite.util.AppLog
import com.autel.common.CallbackWithOneParam
import com.autel.common.battery.evo.EvoBatteryInfo
import com.autel.common.dsp.evo.EvoDspInfo
import com.autel.common.error.AutelError
import com.autel.common.remotecontroller.RemoteControllerInfo
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
    // Metres ABOVE TAKEOFF, up-positive. NOT what the SDK hands over — Autel's
    // LocalCoordinateInfo is NED (down-positive) and is negated on the way in; see the
    // assignment for the in-flight confirmation. Every consumer relies on up-positive.
    @Volatile private var relAlt = Double.NaN
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

    // ---- Link quality ----
    //
    // The CONTROLLER reports its own link quality as a ready-made percentage:
    // AutelRemoteController.setInfoDataListener -> RemoteControllerInfo
    // .getControllerSignalPercentage(). Decompiled, it is a straight passthrough of the RC
    // telemetry packet's data[1] — i.e. the same number the controller's own signal indicator
    // draws, and the same provenance as DJI's AirLink.getUplinkSignalQuality(). Nothing is
    // derived or invented here, so the bars can be shown honestly with no calibration.
    //
    // (An earlier pass concluded no percentage existed and planned an RSRP->bars calibration.
    // That was wrong: it searched the DSP/radio path only and never opened the remote-controller
    // interface. The raw RF metrics below are kept anyway — they're genuinely useful diagnostics
    // and a cross-check if the percentage ever looks wrong — but they are no longer load-bearing.)
    @Volatile private var rcSignalPct: Int? = null
    @Volatile private var rcBatteryPct: Int? = null
    @Volatile private var rcDspPct: Int? = null

    // Raw RF metrics — diagnostics only, logged with the tick.
    @Volatile private var rfRsrp: IntArray? = null
    @Volatile private var rfMasterSnr: Int? = null
    @Volatile private var rfAirSnr: Int? = null
    @Volatile private var rfSeen = false

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
                    // NEGATED — Autel's LocalCoordinateInfo is a NED frame, so its third axis is
                    // DOWN-POSITIVE: an aircraft 60m ABOVE takeoff reports altitude = -60.
                    // Confirmed in flight 2026-08-01 by the only test that settles a sign
                    // question: climbing drove the number MORE negative, descending drove it
                    // toward zero, and zero was on the ground.
                    //
                    // This was not cosmetic. Every consumer reads relAlt as up-positive metres
                    // above takeoff, so the raw value broke three things at once:
                    //   - the HUD read -198 ft while the aircraft was at +198 ft
                    //   - aglMeters() returns relAlt only when > 0, so it fell back to 0 and the
                    //     SPI slant-point solver placed every dropped marker as if the aircraft
                    //     were sitting on the ground (visible as "agl=0" in the SPI log line
                    //     while airborne) — this is what made dropped markers inaccurate
                    //   - isFlying (relAlt > 0.5) was false the entire flight
                    // Negated here, at ingest, for the same reason as the gimbal pitch above:
                    // it is the one point that fixes the HUD, the marker math, TerrainAgl's
                    // MSL derivation and the AR overlay together, with no consumer double-
                    // correcting.
                    relAlt = -local.altitude.toDouble()
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
        evo.remoteController.setInfoDataListener(
            object : CallbackWithOneParam<RemoteControllerInfo> {
                override fun onSuccess(info: RemoteControllerInfo?) {
                    info ?: return
                    // Clamped, not trusted blindly: the field is a raw passthrough from the RC
                    // packet, so an out-of-range value means the assumption that it's a 0-100
                    // percentage is wrong — better to pin the bars than to draw nonsense.
                    rcSignalPct = info.getControllerSignalPercentage().coerceIn(0, 100)
                    rcBatteryPct = info.getBatteryCapacityPercentage().coerceIn(0, 100)
                    rcDspPct = info.getDSPPercentage().coerceIn(0, 100)
                }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "remote controller info listener error: ${error?.description}")
                }
            })
        evo.dsp.setDspInfoListener(object : CallbackWithOneParam<EvoDspInfo> {
            override fun onSuccess(info: EvoDspInfo?) {
                val sig = info?.signalStrengthInfo ?: return
                rfRsrp = runCatching { sig.rsrp }.getOrNull()
                rfMasterSnr = runCatching { sig.masterSnr }.getOrNull()
                rfAirSnr = runCatching { sig.airSnr }.getOrNull()
                rfSeen = true
            }
            override fun onFailure(error: AutelError?) {
                AppLog.w(TAG, "dsp listener error: ${error?.description}")
            }
        })
        evo.gimbal.setAngleListener(object : CallbackWithOneParam<EvoAngleInfo> {
            override fun onSuccess(info: EvoAngleInfo?) {
                info ?: return
                // NEGATED AT INGEST — Autel reports gimbal pitch with DOWN POSITIVE, which is
                // the opposite of DJI. Confirmed on hardware 2026-08-01: tilting the gimbal
                // down made the HUD read "GIMBAL n° UP".
                //
                // Normalised HERE, at the single point the value enters the app, because
                // liveGimbalPitch feeds THREE independent consumers that all assume the DJI
                // convention (down = negative):
                //   1. hud() -> Hud.gimbalPitch -> the HUD readout and the crosshair accuracy ring
                //   2. pushOnce() -> the CoT pitch= attribute PUBLISHED TO TAK
                //   3. pushCameraPoint()/cameraPose() -> SPI look-point, AR overlay, sensor cone
                //
                // Flipping PITCH_SIGN instead would have fixed only (3) and left the HUD and the
                // data the TAK team receives silently inverted. One negation, at the source, is
                // the only place that fixes all three without any consumer double-correcting.
                // PITCH_SIGN stays +1.0 and remains what it was meant to be: a calibration knob,
                // not the inversion fix.
                liveGimbalPitch = -info.pitch.toDouble()
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
        runCatching { evo.dsp.setDspInfoListener(null) }
        runCatching { evo.remoteController.setInfoDataListener(null) }
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
        // Published so other subsystems can refuse to write aircraft settings while airborne.
        // See AutelAvoidance.applyAtConnect: a safety switch must not be rewritten mid-flight.
        airborne = isFlying

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

        // Link diagnostics, one line per tick. The percentages are what the toolbar draws; the
        // raw RSRP/SNR are logged alongside them so a range profile shows whether the controller's
        // percentage tracks the actual radio, and so there's something to look at if it doesn't.
        if (rfSeen || rcSignalPct != null) {
            AppLog.v(TAG, "LINK: sig=${rcSignalPct ?: "-"}% dsp=${rcDspPct ?: "-"}% " +
                    "rcBat=${rcBatteryPct ?: "-"}% | " +
                    "rsrp=${rfRsrp?.joinToString(",") ?: "-"} " +
                    "masterSnr=${rfMasterSnr ?: "-"} airSnr=${rfAirSnr ?: "-"}")
        }
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
        if (BEARING_MODE_RELATIVE) CameraSlantPoint.norm360(aircraftHeading + rawYaw + TakBridgeHolder.currentBearingOffset)
        else CameraSlantPoint.norm360(rawYaw + TakBridgeHolder.currentBearingOffset)

    private fun pushCameraPoint(lat: Double, lon: Double, aglMeters: Double, aircraftHeading: Double) {
        val pitch = liveGimbalPitch
        val yaw = liveGimbalYaw
        if (pitch == null || yaw == null) {
            AppLog.d(TAG, "SPI skip: gimbal attitude not yet received")
            return
        }
        val bearing = cameraBearing(yaw, aircraftHeading)
        // Sign convention RESOLVED 2026-08-01 — Autel reports down-positive and is negated at
        // ingest, so by here it is DJI-like: level = 0, looking down = negative. PITCH_SIGN is a
        // calibration scale, not the inversion fix; see its doc before touching it.
        val pitchAdj = pitch * PITCH_SIGN + TakBridgeHolder.currentPitchOffset

        // ABOVE THE HORIZON THERE IS NO LOOK-POINT, SO PUBLISH NOTHING.
        //
        // The camera ray only meets the ground while pointing below horizontal. Looking up (now
        // reachable — the upward gimbal limit is unlocked at connect) CameraSlantPoint cannot
        // solve, and falls back to a FIXED 300m range along the bearing. Publishing that would
        // put a confident-looking SPI on the TAK picture at a spot the camera is not seeing and
        // nobody downstream could tell it was invented. An absent SPI is honest; a fabricated
        // one is worse than none, because the team will act on it.
        //
        // Threshold matches CameraSlantPoint's own `depression > 1.0` guard, so this suppresses
        // exactly the cases it would otherwise have faked.
        if (pitchAdj > -1.0) {
            sensorFov = -1.0; sensorVfov = -1.0; sensorAzimuth = -1.0
            sensorElevation = pitchAdj; sensorRange = -1.0
            AppLog.d(TAG, "SPI suppressed: camera at or above horizon " +
                "(pitch ${"%.1f".format(pitchAdj)}) — no ground intersection to publish")
            return
        }

        val gp = CameraSlantPoint.compute(
            lat, lon, aglMeters, bearing, pitchAdj, ::elevationLookup, aircraftMsl(aglMeters))
        tak.sendCameraPoint(spiUid, droneUid, "$droneCallsign-SPI", gp.lat, gp.lon, gp.rangeMeters)

        val zoom = liveZoom
        val (baseH, baseV) = baseFov()
        // tan-based zoom correction (not base/zoom, which is a small-angle shortcut): FOV
        // halves in tangent space, and the AR overlay projects with these same helpers — the
        // published cone and the on-screen projection must agree.
        sensorFov = zoomedFov(baseH, zoom)
        sensorVfov = zoomedFov(baseV, zoom)
        sensorAzimuth = bearing
        sensorElevation = pitchAdj
        sensorRange = gp.rangeMeters
        val headingPlusYaw = CameraSlantPoint.norm360(aircraftHeading + yaw)
        AppLog.d(TAG, "SPI: lens=$activeLens pitch=$pitch yaw=$yaw heading=${"%.0f".format(aircraftHeading)} " +
            "az=${"%.0f".format(bearing)} headingPlusYaw=${"%.0f".format(headingPlusYaw)} " +
            "agl=${"%.0f".format(aglMeters)} zoom=$zoom fov=${"%.1f".format(sensorFov)} range=${Math.round(gp.rangeMeters)}m")
    }

    /** Base horizontal/vertical FOV (deg) for the active 640T camera, before zoom. The EO base
     *  comes from [TakBridgeHolder]'s calibratable values (AR FOV calibration adjusts them);
     *  IR keeps its spec constants until it gets its own calibration in Phase 3. */
    private fun baseFov(): Pair<Double, Double> = when (activeLens) {
        Lens.IR -> IR_HFOV to IR_VFOV
        else -> TakBridgeHolder.currentHFovBase to TakBridgeHolder.currentVFovBase
    }

    private fun isValidLat(v: Double) = v.isFinite() && v != 0.0 && v >= -90.0 && v <= 90.0
    private fun isValidLon(v: Double) = v.isFinite() && v != 0.0 && v >= -180.0 && v <= 180.0

    /** Where the camera is pointing: true-north bearing and pitch, both degrees. */
    data class CameraPose(val bearingDeg: Double, val pitchDeg: Double)

    /**
     * Current camera pose, or null until gimbal state has arrived.
     *
     * Deliberately computed from the SAME [cameraBearing] + [PITCH_SIGN]/[PITCH_OFFSET_DEG]
     * model that [lookPoint] uses, rather than handing out raw gimbal yaw for a caller to
     * re-derive. The AR overlay projects markers with this, and marker DROPS are placed with
     * [lookPoint] — if those two ever disagreed, a marker would render somewhere other than
     * where it was placed, and the overlay would look plausible while being wrong. One model,
     * one place.
     */
    fun cameraPose(): CameraPose? {
        val pitch = liveGimbalPitch ?: return null
        val yaw = liveGimbalYaw ?: return null
        return CameraPose(cameraBearing(yaw, headingDeg), pitch * PITCH_SIGN + TakBridgeHolder.currentPitchOffset)
    }

    /**
     * True if [candidate] is a uid THIS app publishes — our own aircraft PLI or its sensor
     * point. The server echoes both back as ordinary contacts, but neither is a target: the
     * aircraft is at its own position, and the SPI is by definition wherever the camera is
     * pointing, so drawing it would pin a marker permanently under the crosshair.
     */
    fun isOwnPublishedUid(candidate: String?): Boolean =
        candidate != null && (candidate == droneUid || candidate == spiUid)

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
            lat, lon, agl, bearing, pitch * PITCH_SIGN + TakBridgeHolder.currentPitchOffset,
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
        /** Controller→aircraft link quality, 0-100, or null before the first RC info callback.
         *  Named to match the DJI blueprint's field of the same name so the two HUD paths read
         *  identically, even though the sources differ (AirLink vs RemoteControllerInfo). */
        val uplinkSignalPct: Int?,
        /** Controller's own battery, 0-100, or null. Not shown yet — the toolbar's battery gauge
         *  is the AIRCRAFT's, and DJI's blueprint has no controller-battery readout. */
        val rcBatteryPct: Int?,
        /** DSP/video-link quality, 0-100, or null. Believed to be the downlink figure; unverified
         *  against hardware, so nothing reads it yet. */
        val dspPct: Int?,
    )
    fun hud(): Hud = Hud(lat, lon, relAlt, hae, speedMs, headingDeg, batteryPct, satCount,
        liveGimbalPitch, isValidLat(lat) && isValidLon(lon), homeLat, homeLon, homeSet,
        rcSignalPct, rcBatteryPct, rcDspPct)

    /** Terrain-corrected AGL + MSL reading for the current telemetry snapshot (Phase 2 HUD
     *  wiring reads this); see [TerrainAgl]. Needs an app Context for the DTED lookup. */
    fun aglReading(): TerrainAgl.Reading? {
        val context = com.autel.sdksample.TestApplication.getInstance() ?: return null
        return TerrainAgl.reading(context, hud())
    }

    companion object {
        private const val TAG = "AutelTakBridge"

        /** True once the aircraft is off the ground, by the same test the PLI reports.
         *
         *  On the companion rather than the instance because the readers are process-wide
         *  singletons with no handle on the bridge, and there is only ever one aircraft.
         *
         *  Conservative by design: false until the first telemetry tick, so a caller that has
         *  heard nothing treats the aircraft as grounded — which is the only state in which
         *  writing settings is permitted anyway, and the state it is genuinely in at connect. */
        @Volatile var airborne: Boolean = false
            internal set

        /** GNSS accuracy raw units → meters. Believed mm (÷1000); bench-verify. */
        private const val ACC_DIVISOR = 1000.0

        // ---- SPI calibration constants (flight-test these; see tracker §4.6 open items) ----
        /** Added to the gimbal yaw to reach true bearing. DJI needed +105; Autel starts at 0. */
        // MOVED to TakBridgeHolder.currentBearingOffset (runtime + persisted) so it can be
        // calibrated without a rebuild. See TakBridgeHolder's aim-calibration block.
        /** false: gimbal yaw treated as absolute; true: body-relative (heading + yaw). */
        private const val BEARING_MODE_RELATIVE = false
        /**
         * Slant-range pitch CALIBRATION scale — no longer the inversion fix.
         *
         * DO NOT flip this to -1.0 to correct an inverted gimbal readout. Autel's down-positive
         * convention is already normalised at ingest (see setAngleListener), because this
         * constant only reaches the SPI/AR path and would leave the HUD and the CoT pitch sent
         * to TAK inverted. Flipping it now would re-invert the SPI and AR while the HUD stayed
         * correct — the two would silently disagree, which is worse than both being wrong.
         */
        private const val PITCH_SIGN = 1.0
        /** Slant-range fine-tune, added to pitch after sign correction. */
        // MOVED to TakBridgeHolder.currentPitchOffset — see above.

        // EVO II Dual 640T V3 per-camera FOV (deg) at 1x — CALIBRATION CONSTANTS.
        // Start values from published specs; confirm against the live cone in ATAK. The EO pair
        // seeds TakBridgeHolder.DEFAULT_HFOV/VFOV and is calibratable from the AR options
        // dialog; IR stays a plain constant until Phase 3.
        const val EO_HFOV = 79.0; const val EO_VFOV = 62.0
        private const val IR_HFOV = 42.0; private const val IR_VFOV = 34.0

        /** Effective FOV at [zoom], from the calibrated EO base — what both the published
         *  <sensor> cone and the AR projection read, so they cannot disagree. */
        fun hFovDeg(zoom: Double = 1.0) = zoomedFov(TakBridgeHolder.currentHFovBase, zoom)
        fun vFovDeg(zoom: Double = 1.0) = zoomedFov(TakBridgeHolder.currentVFovBase, zoom)

        /** True-perspective zoom narrowing: FOV halves in tangent space, not linearly. */
        fun zoomedFov(baseDeg: Double, zoom: Double): Double {
            if (!zoom.isFinite() || zoom <= 1.0) return baseDeg
            val halfRad = Math.toRadians(baseDeg / 2.0)
            return 2.0 * Math.toDegrees(Math.atan(Math.tan(halfRad) / zoom))
        }
    }
}

/** Process-wide holder so the bridge survives screen navigation (1:1 with TAKPilot2). */
object TakBridgeHolder {
    const val DEFAULT_HFOV = AutelTakBridge.EO_HFOV
    const val DEFAULT_VFOV = AutelTakBridge.EO_VFOV
    const val MIN_FOV = 5.0
    const val MAX_FOV = 170.0

    /** Aim-calibration defaults: zero, i.e. trust the gimbal exactly as reported. Deliberately
     *  NOT a guessed non-zero value — an uncalibrated system should behave predictably, and a
     *  fabricated offset would be indistinguishable from a measured one later. */
    const val DEFAULT_PITCH_OFFSET = 0.0
    const val DEFAULT_BEARING_OFFSET = 0.0
    /** Past this, the problem is mechanical, not calibration — see [setAimOffsets]. */
    const val MAX_PITCH_OFFSET = 15.0

    private var bridge: AutelTakBridge? = null
    private var videoUrl: String? = null
    private var cameraPointEnabled = false
    // Remembered so it survives bridge restarts (reconnect) and a start-before-connect order.
    private var zoomFactor: Double = 1.0

    // Calibrated EO field of view (degrees at 1x). Defaults are the published 640T specs; the
    // real lens is whatever it is, which is what the AR FOV calibration measures. Held here so
    // the published FOV cone and the AR projection always read the same numbers.
    private var hFovBase: Double = DEFAULT_HFOV
    private var vFovBase: Double = DEFAULT_VFOV

    // ---- SPI aim calibration (pitch / bearing offsets) ----
    //
    // WHY THESE ARE RUNTIME AND NOT const val. They used to be compile-time constants in
    // AutelTakBridge, which meant every candidate value cost a rebuild + reinstall — so in
    // practice they were never touched and sat at 0.0 forever. That is the honest reason the
    // Autel port's marker accuracy lagged the DJI one: the DJI port's BEARING_OFFSET was
    // flight-tuned to +105, ours had never been measured at all. Uncalibrated, not worse physics.
    //
    // These correct a BIAS between what the gimbal reports and where the lens actually looks
    // (mount tolerance, gimbal wear, a hard landing). A bias is invisible at steep look angles
    // and brutal at shallow ones — ground error scales as 1/sin²(pitch), so at 200ft AGL one
    // degree costs ~5ft at 54° down and ~320ft at 6°. That asymmetry is why it went unnoticed.
    //
    // Per-airframe and NOT permanent: re-check after a gimbal strike, a repair, or swapping
    // aircraft. Treated as routine maintenance, like compass/IMU calibration.
    private var pitchOffset: Double = DEFAULT_PITCH_OFFSET
    private var bearingOffset: Double = DEFAULT_BEARING_OFFSET

    /**
     * Sets the aim calibration. Clamped: these correct mount tolerance, not gross error, so a
     * mistyped value should be refused rather than quietly aimed at the horizon. A pitch offset
     * beyond ±[MAX_PITCH_OFFSET]° would mean something mechanically wrong that calibration must
     * not paper over.
     */
    fun setAimOffsets(pitchDeg: Double, bearingDeg: Double) {
        pitchOffset = pitchDeg.coerceIn(-MAX_PITCH_OFFSET, MAX_PITCH_OFFSET)
        bearingOffset = ((bearingDeg % 360.0) + 540.0) % 360.0 - 180.0   // normalise to ±180
    }

    val currentPitchOffset: Double get() = pitchOffset
    val currentBearingOffset: Double get() = bearingOffset

    /** Set the calibrated 1x field of view. Clamped to sane bounds so a mis-tap can't drive
     *  the projection somewhere absurd — an FOV near zero sends every marker to infinity. */
    fun setFovBase(hDeg: Double, vDeg: Double) {
        hFovBase = hDeg.coerceIn(MIN_FOV, MAX_FOV)
        vFovBase = vDeg.coerceIn(MIN_FOV, MAX_FOV)
    }

    val currentHFovBase: Double get() = hFovBase
    val currentVFovBase: Double get() = vFovBase
    val currentZoomFactor: Double get() = zoomFactor

    fun start(droneUid: String, droneCallsign: String) {
        bridge?.stop()
        bridge = AutelTakBridge(droneUid, droneCallsign).also {
            it.videoUrl = videoUrl
            it.cameraPointEnabled = cameraPointEnabled
            it.liveZoom = zoomFactor
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

    /** Live digital-zoom ratio (1.0 = none) — narrows the published SPI FOV cone so other
     *  TAK clients see the camera's actual field of view, not the 1x width. */
    fun setLiveZoom(ratio: Double) {
        zoomFactor = ratio
        bridge?.liveZoom = ratio
    }

    val isCameraPointEnabled: Boolean get() = cameraPointEnabled
    val isRunning: Boolean get() = bridge != null

    fun lookPoint(): Triple<Double, Double, Double>? = bridge?.lookPoint()
    fun hud(): AutelTakBridge.Hud? = bridge?.hud()
    fun cameraPose(): AutelTakBridge.CameraPose? = bridge?.cameraPose()
    fun isOwnPublishedUid(uid: String?): Boolean = bridge?.isOwnPublishedUid(uid) ?: false
}
