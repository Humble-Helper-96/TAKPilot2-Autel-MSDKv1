package com.autel.sdksample.tak

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.autel.sdk.widget.AutelCodecView
import com.autel.sdksample.R
import com.taklite.client.tak.TakManager
import com.taklite.util.AppLog
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * FlightActivity — the Autel rebuild of TAKPilot2's flight screen.
 *
 * The DJI original is a 2,200-line customization of DJI's uxsdk DefaultLayoutActivity
 * (FPV widget + DJI map widget + HSI strip). None of that widget framework exists for
 * Autel, so this screen is rebuilt from primitives with the same feature set where it
 * transfers: fullscreen live video ([AutelCodecView]), telemetry HUD, TAK/video status,
 * an expandable TAK map with inbound markers + drop pins ([TakMapMarkers] /
 * [TakDropMarkers]), a drop-pin-at-camera-look-point action, and the video-stream toggle.
 *
 * Flying is done with the Smart Controller's physical sticks (stick/RTH input rides the
 * Skylink link, not this app). Camera/gimbal control beyond what's on the RC hardware is
 * NOT provided here yet — see PORT-STATUS.md.
 */
class FlightActivity : AppCompatActivity(), TakDropMarkers.Ui {

    private lateinit var fpvOverlayText: TextView
    private lateinit var fpvGimbalPitch: TextView
    private lateinit var fpvFaaCeiling: TextView
    private lateinit var fpvNotice: TextView
    private lateinit var crosshairView: CrosshairView
    private lateinit var streamToggle: LiveToggleView
    private lateinit var recordToggle: RecordToggleView
    private lateinit var toolbarSignal: SignalBarsView
    private lateinit var toolbarSignalText: TextView
    private lateinit var arButton: TextView
    private lateinit var zoomButton: TextView
    private lateinit var map: LockedMapView
    private lateinit var toolbarTakIcon: ImageView
    private lateinit var toolbarTakDot: View
    private lateinit var toolbarBattery: BatteryGaugeView
    private lateinit var toolbarGps: TextView
    private lateinit var rthButton: ImageButton
    private var codecView: AutelCodecView? = null
    private var aircraftMarker: Marker? = null
    private var homeMarker: Marker? = null
    private var homeLine: Polyline? = null
    private var lastHomeSet = false

    /** Throttles the EV slider's placeholder toast while dragging — see its wiring. */
    private var lastEvToastMs = 0L

    // FAA cell lookup cache — see updateFaaCeiling.
    private var lastFaaGridRow = Int.MIN_VALUE
    private var lastFaaGridCol = Int.MIN_VALUE
    private var cachedFaaCeilingFt: Int? = null
    private var cachedFaaWithinDownloadedArea = false

    private val handler = Handler(Looper.getMainLooper())
    private var hudTickCount = 0
    private val refresh = object : Runnable {
        override fun run() {
            updateHud()
            // Every ~5s, not every 500ms tick, so Detailed mode stays readable in flight.
            if (++hudTickCount % 10 == 0) logHudSnapshot()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.v(TAG, "onCreate")
        // osmdroid must be configured before the MapView inflates.
        val osmBase = File(filesDir, "osmdroid").apply { mkdirs() }
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = osmBase
            osmdroidTileCache = File(osmBase, "tiles").apply { mkdirs() }
        }
        setContentView(R.layout.activity_flight)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        fpvOverlayText = findViewById(R.id.fpvOverlayText)
        fpvGimbalPitch = findViewById(R.id.fpvGimbalPitch)
        fpvFaaCeiling = findViewById(R.id.fpvFaaCeiling)
        fpvNotice = findViewById(R.id.fpvNotice)
        crosshairView = findViewById(R.id.flightCrosshair)
        streamToggle = findViewById(R.id.flightStreamButton)
        recordToggle = findViewById(R.id.flightRecordButton)
        toolbarSignal = findViewById(R.id.toolbarSignal)
        toolbarSignalText = findViewById(R.id.toolbarSignalText)
        arButton = findViewById(R.id.flightArButton)
        zoomButton = findViewById(R.id.flightZoomButton)
        map = findViewById(R.id.flightMap)
        toolbarTakIcon = findViewById(R.id.toolbarTakIcon)
        toolbarTakDot = findViewById(R.id.toolbarTakDot)
        toolbarBattery = findViewById(R.id.toolbarBattery)
        toolbarGps = findViewById(R.id.toolbarGps)

        // Live video, full screen behind everything.
        val container = findViewById<FrameLayout>(R.id.videoContainer)
        codecView = AutelCodecView(this).also { container.addView(it) }

        // Locked TAK mini-map. Pan/fling/double-tap-zoom are blocked inside [LockedMapView];
        // these two calls kill the remaining interactive affordances (pinch-zoom and the
        // built-in +/- buttons). Orientation is never touched, so north stays up, and the zoom
        // set here is the zoom for the whole flight — updateHud()'s recenter is the only thing
        // that ever moves the camera.
        map.setTileSource(MapStyle.tileSource(this))
        map.setMultiTouchControls(false)
        map.setBuiltInZoomControls(false)
        map.setFlingEnabled(false)
        map.controller.setZoom(MAP_ZOOM)

        // Home→aircraft line, added before any marker so it renders underneath them. Empty and
        // hidden until both a home point and a live fix exist (see updateHud()).
        homeLine = Polyline(map).apply {
            outlinePaint.color = Color.parseColor("#F44336")
            outlinePaint.strokeWidth = 2.5f * resources.displayMetrics.density
            isVisible = false
            infoWindow = null
            map.overlays.add(this)
        }

        TakDropMarkers.ui = this
        TakMapMarkers.install(applicationContext)
        TakDropMarkers.init(applicationContext)
        TakMapMarkers.onMapReady(map)

        findViewById<ImageButton>(R.id.flightBackButton).setOnClickListener {
            AppLog.v(TAG, "Back tapped"); finish()
        }
        findViewById<View>(R.id.toolbarTakButton).setOnClickListener {
            AppLog.v(TAG, "TAK icon tapped")
            toast(if (TakManager.getInstance().isConnected) "TAK: Connected" else "TAK: Disconnected — check Pre-Flight Setup")
        }
        rthButton = findViewById(R.id.flightRthButton)
        rthButton.setOnClickListener {
            AppLog.v(TAG, "RTH tapped")
            confirmRth()
        }
        // Long-press moves the home point to WHERE THE PILOT IS STANDING (the controller's own
        // GPS), matching the DJI blueprint's gesture. Genuinely wired, not a placeholder — see
        // confirmResetHome() for why it must not use the aircraft's position instead.
        rthButton.setOnLongClickListener {
            AppLog.v(TAG, "RTH long-pressed — reset home point")
            confirmResetHome()
            true
        }

        // ---- Controls present for UI parity with the DJI blueprint but not yet functional on
        // this airframe. Each needs an Autel-side subsystem that doesn't exist yet (camera
        // control, an AR overlay, a decoder-restart hook, or an RF-quality calibration). They
        // are deliberately VISIBLE rather than omitted, so the toolbar a pilot learns on the
        // Mini 2 is the same toolbar they see here — and each says plainly what it is when
        // pressed, rather than looking broken or doing nothing. See notImplemented().
        arButton.setOnClickListener {
            AppLog.v(TAG, "tap: AR (not implemented)")
            notImplemented("AR overlay", "drawing markers onto the live video")
        }
        arButton.setOnLongClickListener {
            notImplemented("AR overlay options", "choosing what the AR overlay draws"); true
        }
        findViewById<ImageButton>(R.id.flightShootPhotoButton).setOnClickListener {
            AppLog.v(TAG, "tap: Photo (not implemented)")
            notImplemented("Photo", "taking a still to the aircraft's card")
        }
        zoomButton.setOnClickListener {
            AppLog.v(TAG, "tap: Zoom (not implemented)")
            notImplemented("Zoom", "switching the camera between 1x and 2x")
        }
        findViewById<ImageButton>(R.id.flightResyncButton).setOnClickListener {
            AppLog.v(TAG, "tap: Video re-sync (not implemented)")
            notImplemented("Video re-sync", "rebuilding the video picture")
        }
        recordToggle.setOnClickListener {
            AppLog.v(TAG, "tap: REC (not implemented)")
            notImplemented("Record", "recording video to the aircraft's card")
        }
        toolbarSignal.setOnClickListener { signalNotAvailable() }
        toolbarSignalText.setOnClickListener { signalNotAvailable() }
        // NOT setOnClickListener: EvSliderView consumes ACTION_DOWN and returns true without
        // calling super, so performClick() never runs and a click listener would be dead code —
        // the thumb would slide and nothing would happen, which is exactly the "looks broken"
        // state a placeholder is supposed to prevent. onIndexChanged is the callback it
        // actually invokes. Throttled so dragging across the track doesn't stack up toasts.
        findViewById<EvSliderView>(R.id.evSlider).onIndexChanged = { _, fromUser ->
            if (fromUser) {
                val now = System.currentTimeMillis()
                if (now - lastEvToastMs > NOTICE_MS) {
                    lastEvToastMs = now
                    AppLog.v(TAG, "EV slider moved (not implemented)")
                    notImplemented("Exposure", "biasing the camera's auto-exposure")
                }
            }
        }

        // The reticle is the aiming reference for drops; DJI also makes tapping it place a
        // one-off "quick marker". That needs the marker-suite work that's still outstanding on
        // this side, so for now a tap says so rather than silently doing nothing.
        crosshairView.onReticleTap = {
            AppLog.v(TAG, "tap: crosshair quick-marker (not implemented)")
            notImplemented("Quick marker", "dropping a marker straight from the crosshair")
        }
        crosshairView.onReticleLongPress = {
            notImplemented("Quick marker", "re-aiming the quick marker")
        }

        VideoStreamerHolder.onStateChanged = Runnable { refreshStreamToggle() }
        refreshStreamToggle()
        streamToggle.setOnClickListener {
            if (VideoStreamerHolder.isActive) {
                AppLog.v(TAG, "LIVE tapped: stopping")
                VideoStreamerHolder.stop()
                toast("Video stream stopped")
            } else {
                AppLog.v(TAG, "LIVE tapped: starting")
                val ok = VideoStreamerHolder.startFromPrefs(applicationContext) { _, msg ->
                    runOnUiThread { toast(msg) }
                }
                if (!ok) toast("Set up the stream in Pre-Flight Setup first")
            }
            refreshStreamToggle()
        }

        // Single drop-pin action, placed at the camera look-point. The mini-map is locked, so
        // there is no tap-the-map placement — TakBridgeHolder.lookPoint() is the cursor, giving
        // the DTED-terrain-corrected ground intersection of the camera's line of sight. Same
        // model as the DJI sibling, including refusing the drop outright when it's unavailable:
        // a marker at a plausible-but-wrong position is worse for the shared picture than none.
        // Long-press the drop button to manage already-dropped pins (move / rename / retype /
        // re-send / delete / clear-all) — no map interaction needed, consistent with the
        // locked mini-map. Same gesture as the DJI blueprint.
        findViewById<ImageButton>(R.id.flightDropPinButton).setOnLongClickListener {
            AppLog.v(TAG, "long-press: markers list")
            onMarkersListTapped()
            true
        }
        findViewById<ImageButton>(R.id.flightDropPinButton).setOnClickListener {
            AppLog.v(TAG, "Drop Pin tapped")
            val gp = TakBridgeHolder.lookPoint()
            if (gp == null) {
                AppLog.w(TAG, "drop refused — no look-point (GPS/gimbal not ready)")
                toast("Can't drop: camera look-point not available (GPS/gimbal not ready)")
            } else {
                pickAffiliationThen { aff -> TakDropMarkers.placeAt(aff, gp.first, gp.second, gp.third) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppLog.v(TAG, "onResume")
        AutelProductHolder.install()   // reclaim the global product listener (see holder docs)
        map.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        AppLog.v(TAG, "onPause")
        map.onPause()
        handler.removeCallbacks(refresh)
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLog.v(TAG, "onDestroy")
        VideoStreamerHolder.onStateChanged = null
        TakMapMarkers.onMapDestroyed()
        // NOTE: bridge + TAK connection + video stream deliberately keep running — they
        // belong to the foreground service lifecycle, not this screen (same as TAKPilot2).
    }

    private fun logHudSnapshot() {
        val hud = TakBridgeHolder.hud()
        if (hud == null || !hud.hasFix) {
            AppLog.v(TAG, "hud: no GPS fix, battery=${hud?.batteryPct ?: "—"}%")
        } else {
            AppLog.v(TAG, "hud: lat=${hud.lat} lon=${hud.lon} alt=${hud.relAlt}m " +
                    "spd=${hud.speedMs}m/s hdg=${hud.headingDeg}deg bat=${hud.batteryPct}% sat=${hud.sats}")
        }
    }

    // ---- HUD ----

    private fun updateHud() {
        val hud = TakBridgeHolder.hud()
        val takOk = TakManager.getInstance().isConnected
        val vidOn = VideoStreamerHolder.isRunning
        val acOk = AutelProductHolder.isConnected

        // Instrument toolbar
        val takColor = if (takOk) Color.parseColor("#4CAF50") else Color.parseColor("#F44336")
        toolbarTakIcon.alpha = if (takOk) 1.0f else 0.4f
        (toolbarTakDot.background as? GradientDrawable)?.setColor(takColor)
            ?: toolbarTakDot.background?.setTint(takColor)
        toolbarBattery.setPercent(hud?.batteryPct?.takeIf { hud.hasFix || it > 0 })
        toolbarGps.text = if (hud?.hasFix == true) hud.sats.toString() else "—"
        // Signal bars stay in their null/no-data state — see signalNotAvailable(). Set every
        // tick anyway so this can't be mistaken for a stale reading that used to be live.
        toolbarSignal.setPercent(null)
        // "Waiting for aircraft…" cover. Gated on the product connection rather than on real
        // decoded frames: AutelCodecView gives no frame callback (the codec listener in
        // AutelVideoStreamer only fires while the RTSP push is running, which is a separate
        // thing a pilot may never turn on). So this can briefly clear a moment before the first
        // frame actually paints — deliberately worded "waiting for aircraft", not "waiting for
        // video", so it doesn't claim more than it knows.
        findViewById<View>(R.id.flightNoVideoCover).visibility =
            if (acOk) View.GONE else View.VISIBLE
        // REC follows the aircraft's real recording state once camera control exists; until
        // then it stays visibly stopped rather than pretending.
        recordToggle.setRecording(false)

        // Home point is independent of the CURRENT fix — once set it stays valid even if the
        // live fix drops momentarily, so this isn't gated behind hasFix like the map work below.
        val homeSet = hud?.homeSet == true && hud.homeLat.isFinite() && hud.homeLon.isFinite()
        rthButton.setImageResource(if (homeSet) R.drawable.ic_rth_home_set else R.drawable.ic_rth)
        if (homeSet) {
            val hPos = GeoPoint(hud!!.homeLat, hud.homeLon)
            val hm = homeMarker ?: Marker(map).apply {
                title = "Home"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                infoWindow = null
                TakMapMarkers.drawableToBitmap(this@FlightActivity, R.drawable.ic_home_marker, 32)?.let {
                    icon = BitmapDrawable(resources, it)
                }
                map.overlays.add(this)
                homeMarker = this
            }
            hm.position = hPos
        }
        if (homeSet && !lastHomeSet) showNotice("Home Point Set")
        lastHomeSet = homeSet

        refreshStreamToggle()

        // Computed once per tick and shared: the AGL readout and the FAA ceiling check both want
        // height above the ground *under the aircraft*, and must never disagree about it — a
        // readout saying one number while the ceiling warning judges another would be worse than
        // having no correction at all.
        val aglReading = if (hud != null) TerrainAgl.reading(this, hud)
            else TerrainAgl.Reading(0.0, terrainCorrected = false, mslMeters = null)

        updateGimbalPitch(hud)
        updateFaaCeiling(hud, aglReading)

        // Same five-line readout as the DJI blueprint, imperial throughout (see Units).
        fpvOverlayText.text = buildString {
            append(TakManager.getInstance().callsign ?: "—")
            append(if (hud != null) "   ${Units.mph(hud.speedMs)}" else "   — MPH")
            append('\n')
            if (hud != null && hud.hasFix) {
                append("%.4f, %.4f".format(hud.lat, hud.lon))
            } else {
                append("—, —")
            }
            append('\n')
            if (hud != null && hud.hasFix && homeSet) {
                val dist = CameraSlantPoint.distanceMeters(hud.homeLat, hud.homeLon, hud.lat, hud.lon)
                val bearing = CameraSlantPoint.initialBearingDeg(hud.homeLat, hud.homeLon, hud.lat, hud.lon)
                append("HOME %s  %03.0f°T".format(Units.feet(dist), bearing))
            } else {
                append("HOME — ft  —°T")
            }
            append('\n')
            // "AGL" only when DTED actually corrected it to height-above-terrain-below;
            // otherwise "ALT", which is what the raw number really is (height above the takeoff
            // point). Labelling an uncorrected figure AGL is exactly the inaccuracy the terrain
            // correction exists to remove, so the label moves with it. MSL is computed
            // separately and can be present while the first still reads ALT. See TerrainAgl.
            if (hud != null && hud.hasFix) {
                append("%s %s".format(
                    Units.feet(aglReading.meters),
                    if (aglReading.terrainCorrected) "AGL" else "ALT",
                ))
            } else {
                append("— ft AGL")
            }
            append("  ·  ")
            val msl = aglReading.mslMeters
            append(if (msl != null) "%s MSL".format(Units.feet(msl)) else "— ft MSL")
            append('\n')
            // Aircraft/TAK/SPI state. DJI shows a flight timer here; the Autel SDK's
            // EvoFlyControllerInfo exposes no flight-time field (checked against the bundled
            // aar), so this line carries link state instead of inventing a timer.
            append(if (acOk) "AC ✓" else "AC ✗")
            append("  TAK ").append(if (takOk) "✓" else "✗")
            if (TakBridgeHolder.isCameraPointEnabled) append("  SPI ✓")
        }

        if (hud == null || !hud.hasFix) return

        // Aircraft marker
        val pos = GeoPoint(hud.lat, hud.lon)
        val mk = aircraftMarker ?: Marker(map).apply {
            title = "Aircraft"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            infoWindow = null
            // Heading arrow, not the app logo: this marker is ROTATED to the aircraft's
            // heading each tick (see mk.rotation below), and a logo can't show a direction —
            // it just spins. 28dp matches the blueprint's AIRCRAFT_ICON_DP. (v1.2 used
            // takpilot2_logo here, which was a placeholder vector at the time and only became
            // obviously wrong once the real TAK-shield artwork was dropped in.)
            TakMapMarkers.drawableToBitmap(this@FlightActivity, R.drawable.ic_self_marker, 28)?.let {
                icon = BitmapDrawable(resources, it)
            }
            map.overlays.add(this)
            aircraftMarker = this
        }
        mk.position = pos
        mk.rotation = -hud.headingDeg.toFloat()
        // The locked map's only camera movement: keep the aircraft centred, zoom untouched.
        map.controller.setCenter(pos)

        // Home→aircraft line: the pilot's "which way back" reference on a map that by design
        // can't be panned around to look. Only meaningful once a home point exists.
        val hl = homeLine
        if (hl != null) {
            if (homeSet) {
                hl.setPoints(listOf(GeoPoint(hud.homeLat, hud.homeLon), pos))
                hl.isVisible = true
            } else {
                hl.isVisible = false
            }
        }
        map.invalidate()
    }

    private fun confirmRth() {
        AlertDialog.Builder(this)
            .setTitle("Return to Home")
            .setMessage("Command the aircraft to return to home now?")
            .setPositiveButton("Return to Home") { _, _ ->
                val fc = AutelProductHolder.evo2?.flyController
                if (fc == null) {
                    toast("No aircraft connected")
                } else {
                    fc.goHome(object : com.autel.common.CallbackWithNoParam {
                        override fun onSuccess() {
                            AppLog.i(TAG, "goHome: OK")
                            runOnUiThread { toast("Returning to home") }
                        }
                        override fun onFailure(error: com.autel.common.error.AutelError?) {
                            AppLog.w(TAG, "goHome failed: ${error?.description}")
                            runOnUiThread { toast("RTH failed: ${error?.description ?: "unknown error"}") }
                        }
                    })
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Says plainly that a control is present for layout parity but not yet functional on this
     * airframe. Deliberately a specific sentence per control rather than a generic "coming
     * soon": a pilot mid-flight needs to know immediately whether the thing they just reached
     * for is going to happen, and a vague message invites a second and third press.
     */
    private fun notImplemented(name: String, what: String) {
        toast("$name isn't available on the EVO II build yet — $what isn't wired up.")
    }

    /**
     * The signal indicator is inert for a different reason than the buttons above: the data
     * genuinely isn't derivable yet. Autel's SDK reports raw RF metrics (RSRP/SNR/gain) with no
     * 0-100% quality figure like DJI's, and turning those into bars needs calibration against
     * real hardware. Showing invented bars would be worse than showing none — a pilot reads
     * this to decide whether to bring the aircraft back.
     */
    private fun signalNotAvailable() {
        AppLog.v(TAG, "tap: signal bars (no data source)")
        toast("Controller signal strength isn't reported on the EVO II build yet — " +
            "use the controller's own signal indicator.")
    }

    /**
     * Moves the home point to **where the pilot is standing** — i.e. the CONTROLLER's own GPS
     * fix — matching the DJI blueprint's `onRthLongPressed`.
     *
     * **Deliberately NOT `setAircraftLocationAsHomePoint()`**, which the Autel SDK also offers.
     * That sets home to wherever the AIRCRAFT currently is, which is a different feature and
     * the wrong one here: the whole point of this gesture is "I have walked or driven away from
     * the takeoff point, come back to ME." Using the aircraft's position would instead pin home
     * to wherever it happens to be hovering, so a subsequent RTH would land it out there rather
     * than return it to the pilot. (This was wired the wrong way round on 2026-07-30 and caught
     * in review — worth the explicit note so it doesn't get "simplified" back.)
     *
     * Refuses rather than guesses when there's no controller fix: a stale or absent position
     * here is a genuine safety problem, not a cosmetic one.
     */
    private fun confirmResetHome() {
        val fc = AutelProductHolder.evo2?.flyController
        if (fc == null) {
            AppLog.w(TAG, "reset home point ignored — aircraft not connected")
            toast("Aircraft not connected")
            return
        }
        if (!hasLocationPermission()) {
            AppLog.i(TAG, "reset home point — location permission not granted, requesting")
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                REQUEST_CODE_LOCATION,
            )
            return
        }
        val loc = controllerLocation()
        if (loc == null) {
            AppLog.w(TAG, "reset home point aborted — no controller GPS fix")
            toast("No controller GPS fix — can't set the home point to your position")
            return
        }
        AppLog.i(TAG, "reset home point: controller fix %.6f, %.6f (age=%ds, acc=%.0fm)"
            .format(loc.latitude, loc.longitude,
                (System.currentTimeMillis() - loc.time) / 1000, loc.accuracy))

        // Destructive styling and the literal coordinates in the message, both matching the
        // blueprint: this changes where RTH will fly the aircraft, and a stale controller fix
        // is exactly the failure the pilot needs a chance to spot before confirming.
        AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            .setTitle("Reset Home Point")
            .setMessage("Set the aircraft's home point to your current location " +
                "(%.6f, %.6f)? This changes where Return to Home will send it."
                    .format(loc.latitude, loc.longitude))
            .setPositiveButton("Set Home Here") { _, _ ->
                AppLog.i(TAG, "reset home point confirmed — sending setLocationAsHomePoint")
                fc.setLocationAsHomePoint(
                    loc.latitude, loc.longitude,
                    object : com.autel.common.CallbackWithNoParam {
                        override fun onSuccess() {
                            AppLog.i(TAG, "setLocationAsHomePoint: OK")
                            runOnUiThread { showNotice("Home Point Updated") }
                        }
                        override fun onFailure(error: com.autel.common.error.AutelError?) {
                            AppLog.w(TAG, "setLocationAsHomePoint failed: ${error?.description}")
                            runOnUiThread {
                                toast("Set home failed: ${error?.description ?: "unknown error"}")
                            }
                        }
                    },
                )
            }
            .setNegativeButton("Cancel") { _, _ ->
                AppLog.i(TAG, "reset home point cancelled at confirm dialog")
            }
            .show()
    }

    private fun hasLocationPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_LOCATION) return
        if (hasLocationPermission()) {
            AppLog.i(TAG, "location permission granted — re-running reset home point")
            confirmResetHome()
        } else {
            AppLog.i(TAG, "location permission denied — can't set home to controller position")
            toast("Location permission is needed to set the home point to your position.")
        }
    }

    /** Most recent fix from the CONTROLLER (the Smart Controller V3 has its own GPS). Returns
     *  the raw Location so the caller can log/judge its age and accuracy. */
    private fun controllerLocation(): android.location.Location? {
        val lm = getSystemService(android.content.Context.LOCATION_SERVICE)
            as android.location.LocationManager
        return runCatching {
            listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER,
            ).mapNotNull { p -> if (lm.isProviderEnabled(p)) lm.getLastKnownLocation(p) else null }
                .maxByOrNull { it.time }
        }.getOrNull()
    }

    private fun refreshStreamToggle() {
        streamToggle.setState(
            if (VideoStreamerHolder.isActive) LiveToggleView.State.LIVE else LiveToggleView.State.OFF
        )
    }

    /** Transient green notice over the video, upper-left (DJI's fpvNotice pattern). */
    private fun showNotice(text: String) {
        fpvNotice.text = text
        fpvNotice.visibility = View.VISIBLE
        handler.removeCallbacks(hideNotice)
        handler.postDelayed(hideNotice, NOTICE_MS)
    }

    private val hideNotice = Runnable { fpvNotice.visibility = View.GONE }

    /**
     * Gimbal look angle, coloured as a marker-accuracy cue. Ground error scales as
     * 1/sin²(pitch), so a shallow look angle is a real accuracy warning, not decoration, and
     * nothing else on screen tells the pilot that. Shares [CrosshairView.accuracyColorFor] with
     * the reticle ring so the number and the ring can never disagree about the state.
     *
     * NOTE the pitch here is the RAW gimbal pitch from the bridge's cache — the sign convention
     * is still uncalibrated on Autel (`AutelTakBridge.PITCH_SIGN`, a Phase 4 bring-up item). If
     * hardware testing shows Autel reports pitch inverted vs DJI, this readout and the ring will
     * both be wrong in the same direction; fix it at the bridge, not here, so the SPI math and
     * this cue stay consistent.
     */
    private fun updateGimbalPitch(hud: AutelTakBridge.Hud?) {
        val pitch = hud?.gimbalPitch
        // Whether a marker dropped RIGHT NOW would get CameraSlantPoint's terrain-corrected
        // solve — DTED coverage at the aircraft's OWN position, not just "any DTED loaded".
        val dtedAvailable = hud != null && hud.hasFix &&
            DtedIndex.elevationAt(this, hud.lat, hud.lon) != null
        crosshairView.setGimbalPitch(pitch, dtedAvailable)
        if (pitch == null) {
            fpvGimbalPitch.text = "GIMBAL —"
            fpvGimbalPitch.setTextColor(Color.parseColor("#B0B0B0"))
            return
        }
        // Sign dropped in favour of an explicit DOWN/UP word: "-20" reads as a negative number
        // rather than as a look angle, and down is the only direction that matters for drops.
        fpvGimbalPitch.text = when {
            pitch <= -1.0 -> "GIMBAL %.0f° DOWN".format(-pitch)
            pitch >= 1.0 -> "GIMBAL %.0f° UP".format(pitch)
            else -> "GIMBAL LEVEL"
        }
        fpvGimbalPitch.setTextColor(CrosshairView.accuracyColorFor(pitch, dtedAvailable))
    }

    /**
     * FAA UASFM advisory ceiling for the cell the aircraft is over. Advisory only — nothing here
     * is wired to the aircraft's flight limits, by design.
     *
     * Judged against the terrain-corrected AGL when DTED allows (see [TerrainAgl]): a UASFM
     * ceiling is height above the ground *under the aircraft*, so comparing it to a
     * takeoff-relative altitude would misjudge the moment the aircraft leaves the elevation it
     * launched from. Without coverage the comparison falls back to the uncorrected figure and
     * marks itself `~` so the pilot can see the warning is only as good as flat ground.
     */
    private fun updateFaaCeiling(hud: AutelTakBridge.Hud?, agl: TerrainAgl.Reading) {
        if (!UasfmIndex.hasCoverage(this)) {
            fpvFaaCeiling.visibility = View.GONE
            return
        }
        if (hud == null || !hud.hasFix) {
            fpvFaaCeiling.visibility = View.VISIBLE
            fpvFaaCeiling.text = "FAA — no fix"
            fpvFaaCeiling.setTextColor(Color.parseColor("#B0B0B0"))
            return
        }

        // Cell lookup is cached per grid cell, not per tick: it's a HashMap hit plus a bounds
        // check, but it runs on the HUD tick with video decoding alongside it.
        val row = UasfmIndex.gridRowFor(hud.lat)
        val col = UasfmIndex.gridColFor(hud.lon)
        if (row != lastFaaGridRow || col != lastFaaGridCol) {
            lastFaaGridRow = row
            lastFaaGridCol = col
            cachedFaaCeilingFt = UasfmIndex.ceilingFtAt(this, hud.lat, hud.lon)
            cachedFaaWithinDownloadedArea = UasfmIndex.isWithinDownloadedArea(this, hud.lat, hud.lon)
            AppLog.v(TAG, "FAA cell ($row,$col): ceiling=${cachedFaaCeilingFt ?: "none"} " +
                "withinDownloaded=$cachedFaaWithinDownloadedArea")
        }

        val aglFt = Units.metersToFeet(agl.meters)
        val approx = if (agl.terrainCorrected) "" else "~"
        val ceiling = cachedFaaCeilingFt
        fpvFaaCeiling.visibility = View.VISIBLE
        when {
            // "AGL" spelled out because the readout above shows MSL, and a bare "FAA 200 ft"
            // next to "413 ft MSL" invites reading the ceiling as an MSL figure.
            ceiling != null -> {
                fpvFaaCeiling.text = "FAA $ceiling ft AGL$approx"
                fpvFaaCeiling.setTextColor(
                    if (aglFt > ceiling) Color.parseColor("#EF5350") else Color.WHITE
                )
            }
            // Inside what was downloaded but in no cell: the FAA publishes no facility map here,
            // which means uncontrolled airspace and the plain Part 107 ceiling. Grey + labelled
            // so it never reads as "the facility map says 400".
            cachedFaaWithinDownloadedArea -> {
                val part107 = UasfmIndex.PART_107_DEFAULT_CEILING_FT
                fpvFaaCeiling.text = "Class G · $part107 ft AGL$approx"
                fpvFaaCeiling.setTextColor(
                    if (aglFt > part107) Color.parseColor("#EF5350") else Color.parseColor("#B0B0B0")
                )
            }
            // Outside the downloaded box entirely — we genuinely don't know. Amber, because
            // silently implying 400 ft here would be a guess dressed up as information.
            else -> {
                fpvFaaCeiling.text = "FAA — no data here"
                fpvFaaCeiling.setTextColor(Color.parseColor("#FFB74D"))
            }
        }
    }

    // ---- Markers list (drop-pin long-press) ----

    /**
     * The dropped-markers panel. Rebuilt from [TakDropMarkers.listPins] each time it opens and
     * after every action, so it can't show a stale list.
     *
     * Deliberately reachable with zero pins: Clear All is still meaningful right after a
     * delete, and a panel that refuses to open when empty just makes the pilot wonder whether
     * the long-press registered.
     */
    private fun onMarkersListTapped() {
        val pins = TakDropMarkers.listPins()
        val hud = TakBridgeHolder.hud()
        // Range/bearing from the AIRCRAFT to each marker, so the list is orderable by "what's
        // near me" in the air rather than just drop order.
        val labels = pins.map { pin ->
            val range = if (hud != null && hud.hasFix) {
                val d = CameraSlantPoint.distanceMeters(hud.lat, hud.lon, pin.lat, pin.lon)
                val b = CameraSlantPoint.initialBearingDeg(hud.lat, hud.lon, pin.lat, pin.lon)
                // Units.distance (not .feet): a dropped marker has no geofence bound the way
                // the aircraft's own position does, so this can run to five digits of feet
                // where miles read better.
                "  ·  %s @ %03.0f°".format(Units.distance(d), b)
            } else ""
            "${pin.affiliation.label}: ${pin.name}$range"
        }.toTypedArray()

        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle(if (pins.isEmpty()) "Dropped Markers (none)" else "Dropped Markers")
            .setItems(labels) { _, i -> onMarkerRowTapped(pins[i]) }
            .setNegativeButton("Close", null)
            .setNeutralButton("Clear All") { _, _ -> onClearAllMarkersTapped() }
            .show()
    }

    private fun onMarkerRowTapped(pin: TakDropMarkers.PinInfo) {
        val actions = arrayOf("Move to crosshair", "Rename", "Change type", "Re-send", "Delete")
        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle(pin.name)
            .setItems(actions) { _, index ->
                when (index) {
                    0 -> onMoveMarkerTapped(pin)
                    1 -> onRenameMarkerTapped(pin)
                    2 -> onChangeTypeTapped(pin)
                    3 -> {
                        AppLog.i(TAG, "marker re-send: ${pin.key}")
                        TakDropMarkers.resend(pin.key)
                    }
                    4 -> onDeleteMarkerTapped(pin)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onMoveMarkerTapped(pin: TakDropMarkers.PinInfo) {
        val look = TakBridgeHolder.lookPoint()
        if (look == null) {
            AppLog.w(TAG, "marker move refused — no look-point")
            toast("Can't move — waiting on GPS + gimbal")
            return
        }
        val (lat, lon, elev) = look
        AppLog.i(TAG, "marker move: ${pin.key} -> $lat,$lon elev=$elev")
        TakDropMarkers.moveToLookPoint(pin.key, lat, lon, elev)
    }

    private fun onRenameMarkerTapped(pin: TakDropMarkers.PinInfo) {
        val field = android.widget.EditText(this).apply {
            setText(pin.name)
            setSelection(pin.name.length)
        }
        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Rename Marker")
            .setView(field)
            .setPositiveButton("Rename") { _, _ ->
                TakDropMarkers.rename(pin.key, field.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onChangeTypeTapped(pin: TakDropMarkers.PinInfo) {
        val affs = TakDropMarkers.Affiliation.values()
        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Change Type")
            .setItems(affs.map { it.label }.toTypedArray()) { _, i ->
                TakDropMarkers.changeType(pin.key, affs[i])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onDeleteMarkerTapped(pin: TakDropMarkers.PinInfo) {
        AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            .setTitle("Delete Marker")
            .setMessage("Remove \"${pin.name}\" from your map? This is local-only — it stays " +
                "on the TAK server until it goes stale (about 14 hours) and may still show on " +
                "other clients until then.")
            .setPositiveButton("Delete") { _, _ ->
                AppLog.i(TAG, "marker delete: ${pin.key}")
                TakDropMarkers.delete(pin.key)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onClearAllMarkersTapped() {
        AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            .setTitle("Clear All Markers")
            .setMessage("Remove all dropped markers from your map? This is local-only — each " +
                "marker stays on the TAK server until it goes stale (about 14 hours) and may " +
                "reappear on other clients' pictures until then.")
            .setPositiveButton("Clear All Markers") { _, _ ->
                AppLog.i(TAG, "markers: clear all confirmed")
                TakDropMarkers.clearAll()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Drop-pin UI ----

    private fun pickAffiliationThen(then: (TakDropMarkers.Affiliation) -> Unit) {
        val affs = TakDropMarkers.Affiliation.values()
        AlertDialog.Builder(this)
            .setTitle("Marker affiliation")
            .setItems(affs.map { it.label }.toTypedArray()) { _, which ->
                AppLog.v(TAG, "affiliation chosen: ${affs[which].label}")
                then(affs[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- TakDropMarkers.Ui ----

    override fun askSend(affiliationLabel: String, onChoice: (Boolean) -> Unit) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("$affiliationLabel pin placed")
                .setMessage("Send this pin to the TAK server?")
                .setCancelable(false)
                .setPositiveButton("Send to TAK") { _, _ -> AppLog.i(TAG, "pin send: yes ($affiliationLabel)"); onChoice(true) }
                .setNegativeButton("Don't Send") { _, _ -> AppLog.v(TAG, "pin send: no ($affiliationLabel)"); onChoice(false) }
                .show()
        }
    }

    override fun pinMenu(title: String, onSend: () -> Unit, onDelete: () -> Unit, sendLabel: String?) {
        runOnUiThread {
            val b = AlertDialog.Builder(this).setTitle(title)
            if (sendLabel != null) b.setPositiveButton(sendLabel) { _, _ -> AppLog.i(TAG, "pin menu: $sendLabel ($title)"); onSend() }
            b.setNegativeButton("Delete") { _, _ -> AppLog.i(TAG, "pin menu: delete ($title)"); onDelete() }
            b.setNeutralButton("Cancel", null)
            b.show()
        }
    }

    override fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private const val TAG = "FlightActivity"

        /** Fixed mini-map zoom. The map is locked, so this is the zoom for the whole flight —
         *  matches the DJI sibling's MAP_ZOOM so both airframes frame the same amount of
         *  ground around the aircraft. */
        private const val MAP_ZOOM = 15.0

        /** How long a transient notice ("Home Point Set") stays up. */
        private const val NOTICE_MS = 3000L

        private const val REQUEST_CODE_LOCATION = 4302
    }
}
