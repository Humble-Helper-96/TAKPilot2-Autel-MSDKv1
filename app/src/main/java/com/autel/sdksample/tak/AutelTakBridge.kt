package com.autel.sdksample.tak

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    // GNSS accuracy, in metres (-1 = unknown). ⚠ COMPUTED BUT NOT YET CONSUMED — nothing reads
    // these today, so no unverified number reaches a pilot. Before wiring them to any readout or
    // the PLI, BENCH-VERIFY [ACC_DIVISOR]: the raw units are only *believed* to be millimetres,
    // and if that guess is wrong the displayed accuracy is off by 1000x. Kept as scaffolding, not
    // shipped data.
    @Volatile private var horizAccM = -1.0
    @Volatile private var vertAccM = -1.0
    /** elapsedRealtime of the last fly-controller frame, 0 = none yet. The drone CoT is only
     *  published while this is recent — see the freshness gate in [pushOnce]. */
    @Volatile private var lastTelemetryMs = 0L
    @Volatile private var liveGimbalPitch: Double? = null
    @Volatile private var liveGimbalYaw: Double? = null
    /** Erratic-pitch detector, fed from the gimbal angle listener. Single-threaded use:
     *  only that callback touches it. */
    private val gimbalPitchMonitor = GimbalPitchMonitor()
    @Volatile private var homeLat = Double.NaN
    @Volatile private var homeLon = Double.NaN
    @Volatile private var homeSet = false


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
        // The CONTROLLER's own position, for the pilot marker. Idempotent, and silent when the
        // permission is missing — see OperatorLocation for why getLastKnownLocation alone never
        // worked here.
        com.autel.sdksample.TestApplication.getInstance()?.let { OperatorLocation.start(it) }
        subscribe()
        handler.post(tick)
        AppLog.i(TAG, "AutelTakBridge started ($droneCallsign / $droneUid, every ${intervalMs}ms)")
    }

    /**
     * @param finalizeFlight false ONLY when the holder is about to start a replacement bridge
     *   in the same breath (an identity change: enroll, logout-then-reconnect). The flight
     *   logger's session then stays open and the new bridge's telemetry continues the same
     *   track — enrolling mid-flight no longer splits the flight into two files. Every real
     *   teardown keeps the default: after unsubscribe() no more telemetry arrives, so a
     *   landing would otherwise never be detected and the session would sit open until the
     *   orphan sweep. A deliberate stop deserves a finished GPX now.
     */
    fun stop(finalizeFlight: Boolean = true) {
        running = false
        handler.removeCallbacks(tick)
        if (finalizeFlight) FlightPathLogger.endSession("bridge stopped")
        unsubscribe()
        // The bridge is the only consumer of the controller's position, so the receiver stops
        // with it. Without this the GPS ran at 2s updates for the LIFE OF THE PROCESS after the
        // first session — a permanent battery drain on the controller. start() re-arms it.
        OperatorLocation.stop()
        AppLog.i(TAG, "AutelTakBridge stopped")
    }

    /** Re-arm listeners after an aircraft reconnect (registrations die with the product). */
    fun onProductConnected() { if (running) subscribe() }

    // ---- SDK subscriptions ----
    //
    // RE-ARM SAFETY, verified in the aar (javap -p -c), 2026-08-03. Every `set*Listener` below is
    // a SUBSCRIPTION (fires continuously), and `subscribe()` is called again on every reconnect —
    // so whether re-arming accumulates or replaces is load-bearing.
    //
    //   ⚠ DO NOT trust the "set…Listener" NAME. It is not a reliable signal in this SDK: the
    //   XStar impl of `setFlyControllerInfoListener` calls `addIStarLinkLongTimeCallback` and
    //   ACCUMULATES. The check has to be per-impl, in bytecode.
    //
    //   The EVO2 impls this app actually uses are SELF-CLEANING (remove-then-add / single-slot),
    //   so re-arming REPLACES and is safe:
    //     - Evo2FlyController.setFlyControllerInfoListener  → removeXInfoListener…; addXInfoListener…
    //     - VisualModelManager.setVisualHeartListener       → removeVisualHeartListener; set…
    //       (used by AutelAvoidance, not here)
    //     - the DSP/RC/battery/gimbal setters follow the same paired remove/set pattern.
    //
    // Belt and suspenders: since CODE #1 (2026-08-03) `productConnected` arms once per product, so
    // even an accumulating setter would be armed once — but the single-slot property above is the
    // primary guarantee. The dangerous shape is a `get*(callback)` that is secretly a 2Hz
    // subscription (see getVisualSettingInfo, eliminated); this file uses none.
    fun subscribe() {
        val evo = AutelProductHolder.evo2 ?: run {
            AppLog.i(TAG, "subscribe: no aircraft yet (will re-arm on productConnected)")
            return
        }
        evo.flyController.setFlyControllerInfoListener(object :
            CallbackWithOneParam<EvoFlyControllerInfo> {
            override fun onSuccess(info: EvoFlyControllerInfo?) {
                info ?: return
                // Proof of life for the drone CoT push — see the freshness gate in pushOnce().
                lastTelemetryMs = android.os.SystemClock.elapsedRealtime()
                // Flight limits used to be pushed from here, latched on the TAK session. They are
                // now applied on AIRCRAFT connect by AutelProductHolder — a TAK server link has
                // no business gating aircraft safety parameters, and latching on it meant a
                // reconnect never re-applied them. See FlightLimitsController.applyAtConnect.
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
                // Flight path recording (v1.5.9). Fed from HERE, not from pushOnce: pushOnce
                // returns early when TAK is disconnected, and a flight with no network must
                // still produce a track — that is this release's whole theme. The call is a
                // throttle check and a post; the logger's file I/O lives on its own thread.
                FlightPathLogger.onTelemetry(
                    lat, lon, mslAlt, relAlt, speedMs, headingDeg, batteryPct, satCount)
                // The companion's airborne flag is written HERE, not in pushOnce, since
                // v1.5.9: pushOnce is TAK-gated, so with the telemetry-only bridge the flag
                // silently froze at false — and AutelAvoidance reads it to refuse setting
                // writes mid-flight. The callback is the one place that always runs.
                val flying = relAlt.isFinite() && (relAlt > 0.5 || speedMs > 0.5)
                airborne = flying
                // Warnings for the flight screen (v1.5.9): the status this callback always
                // carried and previously ignored. Cheap by contract — set compare, log on
                // change only. `flying` is passed rather than re-read so the warning and the
                // frame it came from can never disagree.
                info.flyControllerStatus?.let { FlightWarnings.onStatus(it, batteryPct, flying) }
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
                val pitchN = -info.pitch.toDouble()
                liveGimbalPitch = pitchN
                liveGimbalYaw = info.yaw.toDouble()
                // Erratic-pitch watch (2026-08-13 incident: full-range oscillation ran 39 s
                // before the pilot reacted). Fed here, at the single pitch ingest point —
                // the monitor owns no listener. Uses the normalised value so the detector
                // and the HUD describe the same motion. Not airborne-gated: a bench gimbal
                // fault deserves the same amber line.
                val wasErratic = FlightWarnings.gimbalErratic
                val isErratic = gimbalPitchMonitor.onSample(
                    pitchN, android.os.SystemClock.elapsedRealtime())
                FlightWarnings.gimbalErratic = isErratic
                if (isErratic != wasErratic) {
                    // The stats line is the tuning record: when a threshold misfires in the
                    // field, the flight log answers "what did the window hold".
                    if (isErratic) AppLog.w(TAG, "gimbal pitch ERRATIC: ${gimbalPitchMonitor.stats()}")
                    else AppLog.i(TAG, "gimbal pitch normal again: ${gimbalPitchMonitor.stats()}")
                }
            }
            override fun onFailure(error: AutelError?) {
                AppLog.w(TAG, "gimbal listener error: ${error?.description}")
            }
        })
        AppLog.i(TAG, "telemetry listeners armed")
    }

    private fun unsubscribe() {
        // Product cycle: drop the erratic-pitch history so a stale verdict cannot greet
        // the next connect.
        gimbalPitchMonitor.reset()
        FlightWarnings.gimbalErratic = false
        val evo = AutelProductHolder.evo2 ?: return
        runCatching { evo.flyController.setFlyControllerInfoListener(null) }
        runCatching { evo.battery.setBatteryStateListener(null) }
        runCatching { evo.gimbal.setAngleListener(null) }
        runCatching { evo.dsp.setDspInfoListener(null) }
        // ⚠ remoteController.setInfoDataListener(null) is DELIBERATELY ABSENT — calling it kills
        // RC signal/battery for the LIFE OF THE PROCESS. Verified in the aar (javap -c,
        // 2026-08-06): RemoteControllerManager2.unRegisteRCInfoDataCallback, on emptying its
        // listener map, detaches the whole manager from the packet dispatcher
        // (unRegisterReceiveListener("RemoteController", 1027)), but registeRCInfoDataCallback
        // is only a map put and NEVER re-attaches — only a full SDK re-init does. So a TAK
        // stop→start cycle left the toolbar signal bars gray until the app was killed (observed
        // in flight, 2026-08-06). The listener stays armed instead: it only writes @Volatile
        // fields, and the next subscribe() replaces it under the SDK's fixed key.
    }

    // ---- The 2 s report ----

    private fun pushOnce() {
        if (!tak.isConnected) return

        // THE PILOT MARKER GOES FIRST, AND IS NOT GATED ON THE AIRCRAFT.
        //
        // Everything below this returns early without a GPS fix from the AIRCRAFT, which is
        // correct — a drone marker at 0,0 would be a lie. But the operator is on the ground with
        // their own position, and the video is a capture of THEIR screen that keeps streaming
        // when the aircraft is down. Attaching the video to the drone marker meant that in
        // exactly the case screen capture exists for, nothing on the network said where the
        // stream was. So the pilot marker carries it, and it is published whenever the
        // CONTROLLER has a fix, aircraft or no aircraft.
        pushPilotPli()
        // SNAPSHOT every field this push consumes, in one go. The SDK listeners write these on
        // their own thread; reading them live through the body would let one PLI mix position
        // from one telemetry frame with heading/altitude/battery from the next. @Volatile gives
        // per-field atomicity, not a consistent SET — this does. The gimbal snapshot is passed
        // into pushCameraPoint too, so the published SPI and the PLI describe the same instant.
        val lat = this.lat
        val lon = this.lon
        if (!isValidLat(lat) || !isValidLon(lon)) return   // no GPS fix — skip, don't send 0,0
        // FRESHNESS, not just validity (operator, 2026-08-13: "as long as the controller is
        // on, the aircraft marker never stales out"). lat/lon are the LAST values the
        // fly-controller listener wrote and they persist after the aircraft goes away, so
        // this push kept re-sending the last known position every 2 s and renewed the CoT
        // stale time forever. The marker could never expire on any other client, whatever
        // stale duration we put on it. An aircraft that stopped talking must stop being
        // reported: the last message then ages out on its own (see CotBuilder's 60 s).
        if (SystemClock.elapsedRealtime() - lastTelemetryMs > TELEMETRY_FRESH_MS) return
        val hae = if (this.hae.isFinite()) this.hae else 0.0
        val relAlt = this.relAlt
        val speedMs = this.speedMs
        val heading = this.headingDeg
        val batteryPct = this.batteryPct
        val batteryCapMah = this.batteryCapMah
        val voltage = this.voltage
        val gimbalPitchN = liveGimbalPitch          // nullable — SPI needs the "not yet" case
        val gimbalYawN = liveGimbalYaw
        val agl = if (relAlt.isFinite() && relAlt > 0) relAlt else 0.0

        // Compute the camera look-point + sensor FOV BEFORE the PLI, so the PLI can carry
        // the <sensor> element (ATAK/taklite draw the FOV cone from it).
        if (cameraPointEnabled) {
            pushCameraPoint(lat, lon, agl, heading, gimbalPitchN, gimbalYawN)
        } else {
            sensorFov = -1.0; sensorVfov = -1.0; sensorAzimuth = -1.0
            sensorElevation = 0.0; sensorRange = -1.0
        }

        val gimbalPitch = gimbalPitchN ?: 0.0
        val gimbalYaw = gimbalYawN ?: 0.0
        // Same test the fly-controller callback publishes as the companion's `airborne` flag
        // (the flag is written THERE since v1.5.9 — this method is TAK-gated and froze it).
        val isFlying = relAlt.isFinite() && (relAlt > 0.5 || speedMs > 0.5)

        // north reference stays 0.0 — <sensor azimuth> is an ABSOLUTE true-north bearing
        // (the DJI original calibrated this the hard way; see its 2026-07 comment).
        tak.sendDronePLI(droneUid, droneCallsign, lat, lon, hae, heading, speedMs, batteryPct,
            // The drone marker keeps its video advertisement — unchanged. The PILOT marker
            // carries the same url as WELL (operator, 2026-08-05), so the stream is still
            // findable when the aircraft has no GPS and this message is not being sent at all.
            // Two markers advertising one stream is the point, not a duplication bug.
            videoUrl, spiUid,
            sensorFov, sensorVfov, sensorAzimuth, sensorElevation, sensorRange, 0.0,
            0.0, gimbalPitch, gimbalYaw,
            isFlying, 0,
            batteryCapMah.toInt(), (batteryCapMah * batteryPct / 100.0).toInt(), voltage)

        AppLog.v(TAG, "PLI push: lat=$lat lon=$lon hae=${"%.1f".format(hae)} hdg=${"%.0f".format(heading)} " +
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

    // pitch/yaw are the SNAPSHOT taken in pushOnce, not re-read here, so the SPI this publishes
    // and the PLI's <sensor> element describe the same telemetry frame.
    private fun pushCameraPoint(
        lat: Double, lon: Double, aglMeters: Double, aircraftHeading: Double,
        pitch: Double?, yaw: Double?,
    ) {
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
        // The SOLVED GROUND POINT is logged to 7 decimals because it is the aim-calibration
        // instrument: aim the crosshair at a feature whose true coordinates are known, and the
        // offset between this point and those coordinates IS the aim error, in metres on the
        // ground. That is measurable; judging an icon's position in oblique video is not.
        // Cross-track offset -> bearing error, along-track -> pitch error.
        AppLog.d(TAG, "SPI: lens=$activeLens pitch=$pitch yaw=$yaw heading=${"%.0f".format(aircraftHeading)} " +
            "az=${"%.2f".format(bearing)} headingPlusYaw=${"%.0f".format(headingPlusYaw)} " +
            "agl=${"%.0f".format(aglMeters)} zoom=$zoom fov=${"%.1f".format(sensorFov)} range=${Math.round(gp.rangeMeters)}m " +
            "spi=${"%.7f".format(gp.lat)},${"%.7f".format(gp.lon)} " +
            "from=${"%.7f".format(lat)},${"%.7f".format(lon)} " +
            "aim=[pitch${"%+.2f".format(TakBridgeHolder.currentPitchOffset)} " +
            "brg${"%+.2f".format(TakBridgeHolder.currentBearingOffset)}]")
    }

    /**
     * Base horizontal/vertical FOV (deg) for the active 640T camera, before zoom.
     *
     * Only the horizontal is chosen per-lens; the vertical is derived from it and the LIVE video
     * aspect, so switching to the thermal lens (640x512, 5:4) re-derives it from the same identity
     * rather than needing its own constant. The EO horizontal comes from [TakBridgeHolder]'s
     * calibratable value; IR keeps its spec constant until it gets its own calibration.
     */
    private fun baseFov(): Pair<Double, Double> {
        // The camera reports the field for whatever lens is actually live — thermal included —
        // so when it is talking, [activeLens] is not consulted at all. The per-lens constants are
        // only the fallback for a camera that has not reported yet.
        val h = TakBridgeHolder.currentHFovBase.takeIf { TakBridgeHolder.hasLiveCameraFov }
            ?: if (activeLens == Lens.IR) IR_HFOV else TakBridgeHolder.currentHFovBase
        return h to TakBridgeHolder.vFovFor(h)
    }

    /**
     * Publishes the operator's own marker: `<callsign>-Pilot`, Cyan, at the CONTROLLER's position,
     * carrying the video url while a stream is running.
     *
     * Silent when the controller has no fix. That is deliberate — the registration message sent at
     * connect already sits at 0,0, and refreshing it with more zeros would keep a false marker
     * alive on the team's map for ever instead of letting it go stale and disappear.
     */
    /** Latches for the two lines below. Transition-only: this runs at 2Hz, so a line on every
     *  tick would be useless as a diagnostic and would flood the log. */
    private var pilotFixMissing = false
    private var pilotFixOldLogged = false

    private fun pushPilotPli() {
        val fix = OperatorLocation.latest
        if (fix == null) {
            // ⚠ THE ONE LINE THAT EXPLAINS A DISAPPEARING PILOT MARKER. Publishing stops here,
            // and nothing else in the application says so. Silence at this point is what makes
            // the marker go stale on the team's map a few minutes later, and until 2026-08-15
            // this return was silent — an operator reported exactly that failure and there was
            // no trace of it anywhere in the log.
            if (!pilotFixMissing) {
                pilotFixMissing = true
                AppLog.w(TAG, "pilot marker SUSPENDED — the controller has no position fix. " +
                    "Nothing more is published for it, thus it goes stale on the team's map. " +
                    "See OperatorLocation for what feeds this.")
            }
            return
        }
        if (pilotFixMissing) {
            pilotFixMissing = false
            AppLog.i(TAG, "pilot marker resumed — the controller has a fix again")
        }

        // AGE OF THE FIX, not just its presence. A fix that stops refreshing keeps being
        // republished at the same point: the marker stays on the map and does NOT follow the
        // pilot, which is the other half of the failure reported on 2026-08-15 and looks
        // nothing like the case above.
        //
        // Measured against elapsedRealtime, never the wall clock — a clock correction mid-flight
        // must not read as an hour-old fix.
        val ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000L
        if (ageMs > PILOT_FIX_OLD_MS) {
            if (!pilotFixOldLogged) {
                pilotFixOldLogged = true
                AppLog.w(TAG, "pilot position is %.0fs old (provider=%s) — the marker still "
                    .format(ageMs / 1000.0, fix.provider) +
                    "publishes, thus it will NOT go stale, but it stops following the pilot. " +
                    "This is normal if the pilot has not moved: the receiver only reports a new " +
                    "fix after a small distance.")
            }
        } else if (pilotFixOldLogged) {
            pilotFixOldLogged = false
            AppLog.i(TAG, "pilot position is fresh again")
        }

        runCatching {
            // No team argument — the pilot marker's colour is TakManager's PILOT_TEAM, always.
            tak.sendPilotPLI(fix, droneCallsign, "Team Member",
                pilotBatteryPct(), TakBridgeHolder.videoUrlOrNull())
        }.onFailure { AppLog.w(TAG, "pilot PLI failed: ${it.message}") }
    }

    /** CONTROLLER battery, not the aircraft's — the aircraft has its own marker and its own
     *  number. A controller about to die is a reason to end the flight, and nothing else on the
     *  network reports it.
     *
     *  Cached for [BATTERY_CACHE_MS]: getIntProperty is a binder IPC, and paying it on every 2s
     *  tick bought nothing — the value drifts about a percent a minute. On failure the LAST GOOD
     *  reading is returned, not 100: a dead BatteryManager must not read as a full battery, which
     *  would mask exactly the dying-controller condition this number exists to report. 100 as
     *  the very first value is the one honest exception — before any reading at all there is
     *  nothing better to say. */
    private var batteryPctCache = 100
    private var batteryPctReadAt = 0L
    private fun pilotBatteryPct(): Int {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - batteryPctReadAt < BATTERY_CACHE_MS && batteryPctReadAt != 0L) return batteryPctCache
        runCatching {
            val ctx = com.autel.sdksample.TestApplication.getInstance() ?: return@runCatching
            val bm = ctx.getSystemService(android.content.Context.BATTERY_SERVICE)
                as? android.os.BatteryManager ?: return@runCatching
            val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            // Some devices return 0 or Integer.MIN_VALUE for "property not supported" — either
            // would be a lie worse than a slightly old truth, so only a plausible value is kept.
            if (pct in 1..100) { batteryPctCache = pct; batteryPctReadAt = now }
        }.onFailure { AppLog.w(TAG, "controller battery read failed: ${it.message}") }
        return batteryPctCache
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

    /**
     * Slant range (m) to the camera look-point, or null when there is nothing honest to
     * show: telemetry not ready, or the camera at/above the horizon (same -1° guard as the
     * SPI push — no ground intersection, so no distance). Feeds the reticle readout on the
     * 500 ms HUD tick; uses the same [CameraSlantPoint] solve as [lookPoint] so the number
     * and a dropped marker cannot disagree.
     */
    fun lookRangeMeters(): Double? {
        val pitch = liveGimbalPitch ?: return null
        val yaw = liveGimbalYaw ?: return null
        val lat = this.lat; val lon = this.lon
        if (!isValidLat(lat) || !isValidLon(lon)) return null
        val pitchAdj = pitch * PITCH_SIGN + TakBridgeHolder.currentPitchOffset
        if (pitchAdj > -1.0) return null
        val agl = aglMeters()
        val gp = CameraSlantPoint.compute(
            lat, lon, agl, cameraBearing(yaw, headingDeg), pitchAdj,
            ::elevationLookup, aircraftMsl(agl))
        return gp.rangeMeters
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

        /** How old the last fly-controller frame may be and still be worth publishing as the
         *  aircraft's position. The feed runs at about 2 Hz, so five seconds is many missed
         *  frames — long enough to ride out a hiccup, short enough that a powered-down
         *  aircraft stops being reported almost at once and its marker can then stale out. */
        private const val TELEMETRY_FRESH_MS = 5_000L

        /** How long one controller-battery reading serves the pilot PLI — see pilotBatteryPct. */
        private const val BATTERY_CACHE_MS = 30_000L

        /** How old the controller's fix may get before [pushPilotPli] says so, once.
         *
         *  FIVE MINUTES, WHICH IS THE PLI'S OWN STALE TIME (CotBuilder.STALE_DURATION_MS — it is
         *  private there, thus the figure is mirrored and not read). The threshold is chosen to
         *  mean something rather than to be round: past it, the team is looking at a position
         *  that would already have expired had the application stopped publishing, so the marker
         *  is now more confident than the data behind it.
         *
         *  It must stay WELL ABOVE a normal stationary hold. The receiver only reports a new fix
         *  after MIN_DISTANCE_M, so a pilot standing still legitimately holds one position for
         *  minutes — measured at 100s on the bench 2026-08-15. A lower threshold would fire on
         *  ordinary standing about and teach the reader to ignore the line. */
        private const val PILOT_FIX_OLD_MS = 5 * 60_000L

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

        // EVO II Dual 640T V3 per-camera HORIZONTAL FOV (deg) at 1x — CALIBRATION CONSTANTS.
        // Only the horizontal is a constant; the vertical is derived from it and the live video
        // aspect (TakBridgeHolder.currentVFovBase). The EO value seeds
        // TakBridgeHolder.DEFAULT_HFOV and is calibratable from the AR options dialog.
        //
        // WHERE 66.8 COMES FROM, and why it is not the 79 the spec sheet prints. Autel publishes
        // 79° for the EO camera, and that is the DIAGONAL. This code previously used it as the
        // horizontal, which is a category error worth ~12°. Converting properly: the sensor is
        // 4:3, so diagonal/width = 5/4, giving
        //     tan(H/2) = tan(79/2) / 1.25 = 0.6594  ->  H = 66.8°
        // and the 16:9 video mode is the same width with less height (1280x960 photo vs 1280x720
        // video — same width), so the horizontal carries over unchanged.
        //
        // ⚠ STILL A STARTING VALUE, NOT A MEASUREMENT. Field calibration landed near 64.5, and
        // deriving from the (more reliably measurable) vertical suggests ~60.8 — the spread hints
        // the 720p downlink is itself a crop of the sensor, which spec arithmetic cannot settle.
        // AutelProductHolder.liveHFovDeg now logs what the camera itself reports; prefer that
        // measurement over this constant once it has been confirmed sane on hardware.
        // FALLBACK ONLY as of the camera-reported FOV wiring — used until the camera's first
        // status push, and if it ever reports something outside sane bounds.
        const val EO_HFOV = 66.8
        // Measured off the camera 2026-08-04 during an IR toggle: fov=33.0x26.0, implied aspect
        // 1.283 (the 640x512 thermal sensor is 5:4). The previous 42.0 here was a spec-sheet guess
        // and was wrong by 9 degrees.
        private const val IR_HFOV = 33.0

        /** Effective FOV at [zoom] — what both the published <sensor> cone and the AR projection
         *  read, so they cannot disagree. Zoom narrows both axes in tangent space by the same
         *  factor, which preserves the aspect coupling the vertical is derived from. */
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
    // Remembered so they survive bridge restarts (reconnect) and a start-before-connect order.
    private var zoomFactor: Double = 1.0
    private var activeLens: AutelTakBridge.Lens = AutelTakBridge.Lens.EO

    // Calibrated EO field of view (degrees at 1x). Held here so the published FOV cone and the
    // AR projection always read the same numbers.
    //
    // ONLY THE HORIZONTAL IS STORED. The vertical is DERIVED from it and the live video aspect —
    // see [currentVFovBase]. It used to be a second independent knob, and that was the bug: for a
    // rectilinear lens the pair is rigidly coupled, so two free knobs can be tuned into a
    // combination no real camera can have. The shipped default (79 x 62) implied an aspect of
    // 1.372 against a 1.778 stream — 23% out — and the field-calibrated 64.5 x 36.5 implied 1.913,
    // still 7.6% out. Vertical error was unfixable by horizontal calibration and vice versa, which
    // is why edge calibration never converged.
    private var hFovBase: Double = DEFAULT_HFOV

    /**
     * Aspect ratio (w/h) of the live video frame, pushed from the flight screen's render-size
     * callback. 0 until the first frame arrives.
     *
     * This is the frame's OWN aspect, not the view's — the video is cropped to fill a 4:3 screen,
     * so those differ, and it is the frame that [ArOverlayView]'s videoRect represents.
     */
    @Volatile private var videoAspect: Double = 0.0

    /** Default used before any frame has arrived: the 640T's video mode is 1280x720. */
    private const val FALLBACK_ASPECT = 16.0 / 9.0

    /**
     * Tells the FOV model the shape of the live video frame. Called from the flight screen on
     * every render-size change, so a camera mode switch (16:9 video -> 4:3 photo -> 5:4 IR)
     * re-derives the vertical FOV automatically instead of needing a recalibration per mode.
     */
    fun setVideoAspect(aspect: Double) {
        if (!aspect.isFinite() || aspect <= 0.0) return
        if (Math.abs(aspect - videoAspect) < 0.001) return
        videoAspect = aspect
        AppLog.i("TakBridgeHolder", "video aspect %.3f -> vFov %.1f (hFov %.1f)"
            .format(aspect, currentVFovBase, hFovBase))
    }

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

    /** Set the calibrated 1x HORIZONTAL field of view. Clamped to sane bounds so a mis-tap can't
     *  drive the projection somewhere absurd — an FOV near zero sends every marker to infinity.
     *  The vertical follows automatically; see [currentVFovBase]. */
    fun setHFovBase(hDeg: Double) {
        hFovBase = hDeg.coerceIn(MIN_FOV, MAX_FOV)
    }

    /**
     * Horizontal FOV as reported by the camera itself (degrees at 1x, for whichever lens is live),
     * or null if the camera has never sent a usable value.
     *
     * Preferred over [hFovBase] — see [currentHFovBase]. Held separately rather than overwriting
     * the calibrated value so that losing the camera falls back cleanly instead of leaving a stale
     * measurement behind, and so the calibration dialog still shows what the PILOT set.
     */
    @Volatile private var liveCameraHFov: Double? = null

    /** True once the camera has reported a usable FOV — callers use it to decide whether their
     *  own per-lens fallback constants are still relevant. */
    val hasLiveCameraFov: Boolean get() = liveCameraHFov != null

    /** Fed from the camera's status push. Sanity-gated: a booting camera reports 0, and an FOV
     *  near zero sends every marker to infinity. */
    fun setLiveCameraFov(hDeg: Double) {
        val ok = hDeg.isFinite() && hDeg >= MIN_FOV && hDeg <= MAX_FOV
        val next = if (ok) hDeg else null
        if (next != liveCameraHFov) {
            liveCameraHFov = next
            AppLog.i("TakBridgeHolder", if (ok)
                "camera-reported hFov %.1f (vFov derives to %.1f) — using it over the calibrated %.1f"
                    .format(hDeg, vFovFor(hDeg), hFovBase)
            else
                "camera-reported hFov %.1f rejected (outside %.0f..%.0f) — falling back to %.1f"
                    .format(hDeg, MIN_FOV, MAX_FOV, hFovBase))
        }
    }

    /**
     * The horizontal FOV everything reads: the CAMERA'S OWN value when it has given us one,
     * otherwise the pilot-calibrated constant.
     *
     * Measured 2026-08-04 the camera reports 65.8 for the EO lens, against a diagonal-derived
     * constant of 66.8 and a hand-calibrated 64.5 — and its implied aspect (1.782) matches the
     * live stream (1.778) to 0.2%, so it is describing the actual video, not the raw sensor. It
     * also follows a lens change on its own (thermal reports 33.0), which hand calibration never
     * could. Trust it; the constant is the safety net for a camera that has not spoken yet.
     */
    val currentHFovBase: Double get() = liveCameraHFov ?: hFovBase

    /** What the PILOT set, ignoring the camera. The calibration dialog edits this — showing the
     *  camera's value in a stepper would imply the taps do something they don't. */
    val calibratedHFovBase: Double get() = hFovBase

    /**
     * Vertical FOV, DERIVED — never stored.
     *
     * [ArOverlayView.project] maps angles onto the video rect in tangent space
     * (`nx = tan(dBearing)/tan(hFov/2)` scaled by the rect's width, `ny` likewise by its height),
     * which is self-consistent for a rectilinear lens only when
     *
     *     tan(hFov/2) / tan(vFov/2) == frameWidth / frameHeight
     *
     * So the vertical is whatever that identity says it is. Deriving it means the two axes cannot
     * drift apart, and a camera mode change re-derives it for free.
     */
    val currentVFovBase: Double get() = vFovFor(hFovBase)

    /** The vertical that pairs with [hDeg] under the live video aspect. Shared so the published
     *  <sensor> cone, the AR projection and the IR lens all derive it exactly one way. */
    fun vFovFor(hDeg: Double): Double {
        val aspect = videoAspect.takeIf { it > 0.0 } ?: FALLBACK_ASPECT
        return 2.0 * Math.toDegrees(Math.atan(Math.tan(Math.toRadians(hDeg / 2.0)) / aspect))
    }

    val currentZoomFactor: Double get() = zoomFactor

    /** The aircraft's TAK callsign, or null before the bridge has ever been started. Read by
     *  [TakDropMarkers] so a dropped marker's name says WHICH aircraft dropped it. */
    @Volatile var droneCallsign: String? = null
        private set

    fun start(droneUid: String, droneCallsign: String) {
        this.droneCallsign = droneCallsign
        // A restart, not a teardown: the flight logger's session survives the swap, so an
        // identity change mid-flight (enroll, reconnect) continues the same track file.
        bridge?.stop(finalizeFlight = false)
        bridge = AutelTakBridge(droneUid, droneCallsign).also {
            it.videoUrl = videoUrl
            it.cameraPointEnabled = cameraPointEnabled
            it.liveZoom = zoomFactor
            it.activeLens = activeLens
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

    /** The RTSP url currently advertised, or null when nothing is streaming. Read by the
     *  bridge each tick so the pilot marker carries it. */
    fun videoUrlOrNull(): String? = videoUrl

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

    /**
     * Which spectrum is on screen. Selects the base FOV, so the published <sensor> cone and the AR
     * projection both narrow when the thermal lens goes live.
     *
     * Remembered here rather than only on the bridge so it survives a bridge restart (reconnect)
     * and a set-before-start ordering — same reason [zoomFactor] is held here.
     *
     * ⚠ This used to be unwired: `AutelTakBridge.activeLens` was declared and read by `baseFov()`
     * but never assigned from anywhere, and the IR toggle flipped only a local flag on the flight
     * screen. Thermal therefore published and projected at the EO field of view — a ~25° cone
     * error that no amount of FOV calibration would have explained.
     */
    fun setActiveLens(lens: AutelTakBridge.Lens) {
        if (activeLens == lens) return
        activeLens = lens
        bridge?.activeLens = lens
        AppLog.i("TakBridgeHolder", "active lens -> $lens")
    }

    val isCameraPointEnabled: Boolean get() = cameraPointEnabled
    val isRunning: Boolean get() = bridge != null

    fun lookPoint(): Triple<Double, Double, Double>? = bridge?.lookPoint()
    fun lookRangeMeters(): Double? = bridge?.lookRangeMeters()
    fun hud(): AutelTakBridge.Hud? = bridge?.hud()
    fun cameraPose(): AutelTakBridge.CameraPose? = bridge?.cameraPose()
    fun isOwnPublishedUid(uid: String?): Boolean = bridge?.isOwnPublishedUid(uid) ?: false
}
