package com.autel.sdksample.tak

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.autel.common.CallbackWithNoParam
import com.autel.common.CallbackWithOneParam
import com.autel.common.camera.XT706.DisplayMode
import com.autel.common.camera.base.MediaMode
import com.autel.common.camera.XT706.IrColor
import com.autel.common.error.AutelError
import com.autel.sdk.camera.AutelBaseCamera
import com.autel.sdk.widget.AutelCodecView
import com.autel.sdksample.R
import com.taklite.client.tak.TakManager
import com.taklite.util.AppLog
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

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

    private lateinit var fpvClock: TextView
    private lateinit var fpvOverlayText: TextView
    private lateinit var fpvGimbalPitch: TextView
    private lateinit var fpvHomeDistance: TextView
    private lateinit var fpvAntennaArc: AntennaAimView
    private lateinit var fpvFaaCeiling: TextView
    private lateinit var fpvRthAltitude: TextView
    private lateinit var lightsButton: ImageButton
    private lateinit var fpvNotice: TextView
    private lateinit var fpvWarningBanner: TextView
    private lateinit var resourceMonitorRow: View
    private lateinit var resourceMonitorCells: List<TextView>
    private lateinit var crosshairView: CrosshairView
    private lateinit var arOverlay: ArOverlayView
    private lateinit var obstacleEdges: ObstacleEdgeView
    private lateinit var streamToggle: LiveToggleView
    private lateinit var recordToggle: RecordToggleView
    private lateinit var toolbarSignal: SignalBarsView
    private lateinit var toolbarSignalText: TextView
    private lateinit var arButton: TextView
    private lateinit var zoomButton: TextView
    private lateinit var irButton: TextView
    private lateinit var irPaletteButton: TextView

    /** Thermal state. Both are re-read from the camera on connect rather than assumed — see
     *  syncIrStateFromCamera(). The buttons must never claim a mode the camera is not in. */
    /**
     * Thermal live. Setting this also tells the FOV model which lens is on screen, so the
     * published <sensor> cone and the AR projection narrow to the IR lens with it — routed
     * through the setter so every assignment site is covered, including the connect-time
     * resync in [syncIrStateFromCamera].
     */
    private var irOn = false
        set(value) {
            field = value
            TakBridgeHolder.setActiveLens(
                if (value) AutelTakBridge.Lens.IR else AutelTakBridge.Lens.EO)
            // Thermal is shown FIT and the other modes FILL, thus the transform depends on this
            // state. A mode change normally alters the frame size too and applyVideoFill re-runs
            // on its own; this covers the order where the state lands before the first frame of
            // the new mode does, which is what the connect-time resync does.
            codecView?.let { v -> runOnUiThread { applyVideoFill(v) } }
        }
    /**
     * Re-reads the camera the moment a REAL one attaches, not at onResume.
     *
     * On a cold start the flight screen is up long before the aircraft. The onResume read then
     * runs against no camera and nothing asks again, thus the buttons claimed "visible" while
     * the aircraft streamed thermal. Held as a field so it can be removed in onPause.
     */
    private val cameraReadyObserver: () -> Unit = {
        AppLog.i(TAG, "camera ready — re-reading thermal mode and palette")
        syncIrStateFromCamera()
        seedZoomFromCamera()
    }

    /** Re-renders the exterior-lamp button once the aircraft has answered. The READ is done by
     *  [AutelProductHolder] at connect; this only puts the answer on the screen. */
    private val connectionObserver: (Boolean) -> Unit = { connected ->
        if (connected) {
            // DELAYED, for the same not-ready window the other at-connect calls hit: the
            // aircraft answers productConnected before the fly controller will serve a read.
            // The read is done HERE rather than in the holder because the answer has to reach
            // the button, and the 500 ms HUD tick does not re-render it.
            handler.postDelayed({
                AutelLights.refresh { runOnUiThread { renderLightsButton() } }
            }, LAMP_READ_DELAY_MS)
        } else {
            // A disconnect clears the lamp to null, thus the button must dim back to "unknown"
            // rather than keep claiming the last state of an aircraft that has gone.
            renderLightsButton()
        }
    }

    /** The palettes the button cycles through, in tap order. A subset of the XT706's twelve
     *  on purpose (operator, 2026-08-07: Ironbow joins white/black hot): three is a cycle a
     *  pilot can predict blind; twelve is a settings menu. The camera may still be IN one of
     *  the other nine (left there by Explorer) — [refreshIrButtons] shows whatever the
     *  camera truly holds, and the next tap re-enters the cycle at WHITE HOT. */
    private val irPaletteCycle = listOf(IrColor.WhiteHot, IrColor.BlackHot, IrColor.IronBow)
    private var irPalette: IrColor = IrColor.WhiteHot
    private lateinit var map: LockedMapView
    private lateinit var mapContainer: android.widget.FrameLayout
    /** Double-tap state of the mini-map. Deliberately NOT persisted — every entry to the flight
     *  screen starts at the normal size, so a pilot never arrives at a screen with the video
     *  half covered by a map they expanded on a previous flight. */
    private var mapExpanded = false
    /** Mini-map scale bar; its length follows [toggleMapSize] (short on the small map,
     *  full on the expanded one). */
    private var mapScaleBar: org.osmdroid.views.overlay.ScaleBarOverlay? = null
    private lateinit var mapZoomButton: TextView

    /** Mini-map zoom mode. Persisted, so a pilot who settles on one keeps it across a battery
     *  swap. Defaults to WIDE: it is the only level that keeps the home point on the map at the
     *  full max-distance limit, which is the safer state to be in if nobody ever touches this. */
    private var mapWide = true
    private lateinit var toolbarTakIcon: ImageView
    private lateinit var toolbarTakDot: ImageView
    private lateinit var toolbarBattery: BatteryGaugeView
    private lateinit var toolbarGps: TextView
    private lateinit var rthButton: ImageButton
    private lateinit var shootPhotoButton: ImageButton

    private var codecView: AutelCodecView? = null

    /** Aspect ratio (w/h) of the frames the camera is currently sending; 0 until the first
     *  frame. Changes when the pilot switches photo/video/IR — see [armVideoFill]. */
    private var videoAspect: Float = 0f
    private var aircraftMarker: Marker? = null
    private var homeMarker: Marker? = null
    private var homeLine: Polyline? = null
    private var lastHomeSet = false

    /** Zoom pill state, in RAW HUNDREDTHS (100 = 1x) — the units the camera itself takes, not
     *  a step number (it was `zoomLevel: Int` of 1/2/4 until 2026-08-06).
     *
     *  SEEDED FROM THE CAMERA, NEVER ASSUMED. This starts at 1x only because something has to
     *  be on screen before the first reading arrives; [seedZoomFromCamera] corrects it as the
     *  screen comes up, and every rocker press re-seeds. Autel Explorer can leave the camera
     *  zoomed, and an app that assumes 1x then labels a true 4x as "1X" — see the incident in
     *  AutelProductHolder, 2026-08-04. See [applyZoomRaw]. */
    private var zoomRaw = ZOOM_RAW_PER_X

    /** Nearest whole step, for the pill's 1↔2 / 1↔4 toggles. */
    private val zoomStep: Int get() = Math.round(zoomRaw / ZOOM_RAW_PER_X.toDouble()).toInt()

    /** Rocker target, held ahead of the camera. The rocker repeats about every 200ms; a step
     *  moves this and repaints the pill at once, while the camera is written on a slower
     *  throttle — see [onZoomRocker]. */
    private var pendingZoomRaw = ZOOM_RAW_PER_X
    private var zoomPushScheduled = false

    /** Rocker press state — see [onZoomRocker]. [zoomHoldTicks] counts the ticks of the press
     *  in progress, which is what separates a press from a hold; the other two tell a continued
     *  hold apart from a new press or a reversal. */
    private var zoomHoldTicks = 0
    private var lastZoomTickMs = 0L
    private var lastZoomDirection = 0

    /** When this app last drove the zoom, for [seedZoomFromCamera]'s settle guard. Starts at a
     *  long time ago, so the first press of a screen seeds from the camera rather than trusting
     *  the 1x this class was constructed with. */
    private var lastZoomWriteMs = Long.MIN_VALUE / 2

    // FAA cell lookup cache — see updateFaaCeiling.
    private var lastFaaGridRow = Int.MIN_VALUE
    private var lastFaaGridCol = Int.MIN_VALUE
    private var cachedFaaCeilingFt: Int? = null
    private var cachedFaaWithinDownloadedArea = false

    private val handler = Handler(Looper.getMainLooper())
    private var hudTickCount = 0

    /** Live one-shot location request for the Home-point reset; removed as soon as a fix arrives
     *  or the request times out, and on screen destroy. Null when nothing is pending. */
    private var homeLocListener: android.location.LocationListener? = null
    private val refresh = object : Runnable {
        override fun run() {
            updateHud()
            // Polled rather than pushed: the aircraft's battery levels arrive from
            // AircraftSettingsDump a few seconds after connect, and setBands ignores a repeat.
            refreshBatteryBands()
            // Every ~5s, not every 500ms tick, so Detailed mode stays readable in flight.
            if (++hudTickCount % 10 == 0) logHudSnapshot()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.v(TAG, "onCreate")

        // OOM-restart guard. If the app is killed for memory in flight, Android restores the task
        // and recreates THIS activity directly — into a cold process where the Autel SDK was never
        // armed (install() only runs from Home). Coming up here would show a dead aircraft link and
        // a frozen HUD that looks live. The tell: we were restored (savedInstanceState != null) yet
        // this process never passed through Home. Bounce to Home, which re-arms the product listener
        // and lets the pilot re-enter the flight screen deliberately.
        if (savedInstanceState != null && !TakPilotHomeActivity.visitedThisProcess) {
            AppLog.w(TAG, "restored into a cold process (OOM restart) — routing to Home")
            startActivity(
                Intent(this, TakPilotHomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
            return
        }

        // osmdroid must be configured before the MapView inflates. Shared with Pre-Flight
        // Setup so the cache budget and paths cannot drift between the two screens.
        MapTileCache.configure(this)
        setContentView(R.layout.activity_flight)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullScreen()

        fpvClock = findViewById(R.id.fpvClock)
        fpvOverlayText = findViewById(R.id.fpvOverlayText)
        fpvGimbalPitch = findViewById(R.id.fpvGimbalPitch)
        fpvHomeDistance = findViewById(R.id.fpvHomeDistance)
        fpvAntennaArc = findViewById(R.id.fpvAntennaArc)
        fpvFaaCeiling = findViewById(R.id.fpvFaaCeiling)
        fpvRthAltitude = findViewById(R.id.fpvRthAltitude)
        fpvNotice = findViewById(R.id.fpvNotice)
        fpvWarningBanner = findViewById(R.id.fpvWarningBanner)
        // Fresh flight screen: drop any banner hold left from the last session. The active
        // set rebuilds from live telemetry within one frame.
        FlightWarnings.reset()
        resourceMonitorRow = findViewById(R.id.flightResourceMonitorRow)
        resourceMonitorCells = listOf(
            R.id.flightResSys, R.id.flightResApp, R.id.flightResCpu, R.id.flightResGpu, R.id.flightResTak,
        ).map { findViewById(it) }
        resourceMonitorRow.visibility = if (AppLog.resourceMonitor) View.VISIBLE else View.GONE
        crosshairView = findViewById(R.id.flightCrosshair)
        arOverlay = findViewById(R.id.flightArOverlay)
        obstacleEdges = findViewById(R.id.flightObstacleEdges)
        // Load the calibrated FOV before the overlay draws anything with it.
        ArSettings.loadFov(this)
        ArSettings.loadAimOffsets(this)
        // Chrome insets so edge arrows cannot be parked under the toolbar or the HUD column
        // where they're invisible — the exact case (aircraft directly overhead) the indicator
        // matters most. Measured from the real views after layout, re-read every pass, so a
        // toolbar/HUD/map-size change cannot silently break it.
        val toolbarView = findViewById<View>(R.id.flightToolbar)
        val hudColumn = findViewById<View>(R.id.flightHudColumn)
        toolbarView.viewTreeObserver.addOnGlobalLayoutListener {
            arOverlay.setChromeInsets(
                top = toolbarView.height.toFloat(),
                right = hudColumn.width.toFloat(),
            )
            // The obstacle radar needs the TOP inset for the same reason and did not have it:
            // its top-face arc and distance label drew from the view's top edge, which put them
            // under the toolbar. A proximity warning the pilot cannot see is worse than none,
            // because the display implies it would have told them.
            obstacleEdges.setTopInset(toolbarView.height.toFloat())
        }
        streamToggle = findViewById(R.id.flightStreamButton)
        recordToggle = findViewById(R.id.flightRecordButton)
        toolbarSignal = findViewById(R.id.toolbarSignal)
        toolbarSignalText = findViewById(R.id.toolbarSignalText)
        arButton = findViewById(R.id.flightArButton)
        zoomButton = findViewById(R.id.flightZoomButton)
        irButton = findViewById(R.id.flightIrButton)
        irPaletteButton = findViewById(R.id.flightIrPaletteButton)
        irButton.setOnClickListener { onIrTapped() }
        irPaletteButton.setOnClickListener { onIrPaletteTapped() }
        map = findViewById(R.id.flightMap)
        mapContainer = findViewById(R.id.flightMapContainer)
        map.onDoubleTap = { toggleMapSize() }
        // Must be resolved before the map setup below, which calls applyMapZoom() and labels
        // this button.
        mapZoomButton = findViewById(R.id.flightMapZoomButton)
        toolbarTakIcon = findViewById(R.id.toolbarTakIcon)
        toolbarTakDot = findViewById(R.id.toolbarTakDot)
        toolbarBattery = findViewById(R.id.toolbarBattery)
        toolbarGps = findViewById(R.id.toolbarGps)

        // Live video, full screen behind everything.
        val container = findViewById<FrameLayout>(R.id.videoContainer)
        codecView = AutelCodecView(this).also { container.addView(it); armVideoFill(it) }

        // Locked TAK mini-map. Pan/fling/double-tap-zoom are blocked inside [LockedMapView];
        // these two calls kill the remaining interactive affordances (pinch-zoom and the
        // built-in +/- buttons). Orientation is never touched, so north stays up, and the only
        // things that ever move the camera are updateHud()'s recenter and the zoom toggle.
        mapWide = getSharedPreferences("takpilot2_tak", MODE_PRIVATE)
            .getBoolean(KEY_MAP_WIDE, true)
        // OpenStreetMap, fixed. The Map Display section that let a pilot choose a source and
        // pre-download an area was removed 2026-08-04 (operator's call). Tiles still cache as
        // they are viewed, and osmdroid trims that store itself — see MapTileCache.configure.
        map.setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(false)
        map.setBuiltInZoomControls(false)
        map.setFlingEnabled(false)
        // Zoom is clamped to exactly the two levels the toggle offers. Blocking the gestures
        // above stops a PILOT dragging the zoom around; this stops everything else — an
        // osmdroid tile fallback, a stray zoomIn/zoomOut, a restored instance state. The map
        // still only ever sits at MAP_ZOOM_WIDE or MAP_ZOOM_NEAR; nothing lands in between.
        map.minZoomLevel = MAP_ZOOM_WIDE
        map.maxZoomLevel = MAP_ZOOM_NEAR
        applyMapZoom(persist = false)
        // Center immediately, before any fix. Without this the map sits at osmdroid's (0, 0)
        // default — open ocean off West Africa — because the per-tick recenter in updateHud()
        // is gated behind hasFix. A pilot who opens the flight screen while the aircraft is
        // still acquiring satellites would otherwise see a blank blue square and reasonably
        // conclude the map is broken.
        map.controller.setCenter(GeoPoint(DEFAULT_LAT, DEFAULT_LON))

        // Home→aircraft line, added before any marker so it renders underneath them. Empty and
        // hidden until both a home point and a live fix exist (see updateHud()).
        homeLine = Polyline(map).apply {
            outlinePaint.color = androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_danger)
            outlinePaint.strokeWidth = 2.5f * resources.displayMetrics.density
            isVisible = false
            infoWindow = null
            map.overlays.add(this)
        }

        TakDropMarkers.ui = this
        TakMapMarkers.install(applicationContext)
        TakDropMarkers.init(applicationContext)
        refreshBatteryBands()
        TakMapMarkers.onMapReady(map)

        // Distance scale, bottom-right of the mini-map (operator, 2026-08-13: without a
        // scale the pilot could not judge how far map items were). osmdroid recomputes the
        // bar for each zoom level, so WIDE and NEAR each read correctly. Imperial to match
        // every other HUD distance (see Units). Half the default bar length (maxLength is
        // in on-screen centimetres) so it shares the bottom row with the zoom toggle
        // without ever reaching it — operator preferred a shorter bar over a raised one.
        val density = resources.displayMetrics.density
        mapScaleBar = org.osmdroid.views.overlay.ScaleBarOverlay(map).apply {
            unitsOfMeasure = org.osmdroid.views.overlay.ScaleBarOverlay.UnitsOfMeasure.imperial
            setAlignBottom(true)
            setAlignRight(true)
            setMaxLength(SCALE_BAR_CM_SMALL)
            setScaleBarOffset((8 * density).toInt(), (8 * density).toInt())
            setTextSize(10 * density)
            map.overlays.add(this)
        }

        findViewById<ImageButton>(R.id.flightBackButton).setOnClickListener {
            AppLog.v(TAG, "Back tapped"); finish()
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
        // this airframe. Each needs an Autel-side subsystem that does not exist yet (camera
        // control, an AR overlay, a decoder-restart hook, or an RF-quality calibration). They
        // are deliberately VISIBLE rather than omitted, so the toolbar a pilot learns on the
        // Mini 2 is the same toolbar they see here — and each says plainly what it is when
        // pressed, rather than looking broken or doing nothing. See notImplemented().
        arButton.setOnClickListener { onArToggleTapped() }
        // Same long-press idiom as RTH (reset home) and drop-pin (markers list).
        arButton.setOnLongClickListener { onArOptionsTapped(); true }
        // ON WHEN THE SCREEN OPENS (operator, 2026-08-15). Started here rather than in onResume
        // deliberately: onDestroy stops the overlay, thus each entry to the flight screen brings
        // it up on, while a mere pause — a dialog, the Field Guide — leaves it as the pilot set
        // it. Turning it off and returning to the screen does bring it back; that is the
        // operator's choice of "always on" over "remember my last setting".
        arOverlay.start()
        refreshArButton()
        shootPhotoButton = findViewById(R.id.flightShootPhotoButton)
        shootPhotoButton.setOnClickListener {
            AppLog.v(TAG, "tap: Photo")
            onShootPhotoTapped()
        }
        zoomButton.setOnClickListener {
            AppLog.v(TAG, "tap: Zoom (currently ${zoomLabel(zoomRaw)})")
            // Tap cycles 1X <-> 2X. From 4X it returns to 1X rather than stepping down,
            // so one tap always gets the pilot back to the widest view. From a rocker-set
            // value it rounds to the nearest step first, so the tap is never a no-op.
            applyZoom(if (zoomStep == 1) 2 else 1)
        }
        zoomButton.setOnLongClickListener {
            AppLog.v(TAG, "long-press: Zoom (currently ${zoomLabel(zoomRaw)})")
            applyZoom(if (zoomStep == 4) 1 else 4)
            true
        }
        // Exterior LEDs. Replaced the video re-sync button (2026-08-02): the video is stable on
        // this airframe, and going dark at night is an operational requirement for a public-safety
        // aircraft — crews get shot at when the aircraft advertises its position.
        //
        // The button reports the AIRCRAFT's state, never our request. See AutelLights for what
        // this does and does not cover; it darkens the navigation LEDs, which is not the same
        // claim as "every exterior light is off".
        lightsButton = findViewById(R.id.flightLightsButton)
        lightsButton.setOnClickListener {
            val currentlyDark = AutelLights.isDark == true
            AppLog.v(TAG, "tap: Exterior lights (currently dark=$currentlyDark)")
            lightsButton.isEnabled = false
            AutelLights.setAllOff(!currentlyDark) { confirmed ->
                runOnUiThread {
                    lightsButton.isEnabled = true
                    renderLightsButton()
                    if (!confirmed) toast("The aircraft did not change the lights.")
                    else if (AutelLights.isDark == true) toast("Exterior lights OFF")
                    else toast("Exterior lights ON")
                }
            }
        }
        renderLightsButton()
        AutelLights.refresh { runOnUiThread { renderLightsButton() } }
        recordToggle.setOnClickListener {
            AppLog.v(TAG, "tap: REC")
            onRecordToggleTapped()
        }
        // NOTE: this REPLACED an earlier listener on the same view that only toasted the
        // current state. Two setOnClickListener calls on one view is silent — the last one
        // wins — so the old handler was dead code that read as live. Removed.
        findViewById<View>(R.id.toolbarTakButton).setOnClickListener {
            AppLog.v(TAG, "tap: TAK connection toggle")
            TakAutoConnect.toggle(applicationContext) { _, msg ->
                runOnUiThread { toast(msg) }
            }
        }
        // Same long-press idiom as the AR button and drop-pin: the short press does the common
        // thing, the long press opens what belongs to it.
        findViewById<View>(R.id.toolbarTakButton).setOnLongClickListener {
            AppLog.v(TAG, "long-press: TAK channels")
            onTakChannelsTapped()
            true
        }

        mapZoomButton.setOnClickListener {
            mapWide = !mapWide
            AppLog.v(TAG, "tap: mini-map zoom -> ${if (mapWide) "WIDE" else "NEAR"}")
            applyMapZoom(persist = true)
        }

        toolbarSignal.setOnClickListener { signalDetail() }
        toolbarSignalText.setOnClickListener { signalDetail() }
        // NOT setOnClickListener: EvSliderView consumes ACTION_DOWN and returns true without
        // calling super, so performClick() never runs and a click listener would be dead code.
        // onIndexChanged is the callback it actually invokes.
        val evSlider = findViewById<EvSliderView>(R.id.evSlider)
        evSlider.steps = AutelExposureController.sliderMax
        evSlider.index = AutelExposureController.savedSliderIndex(this)
        evSlider.onIndexChanged = { idx, fromUser ->
            if (fromUser) {
                // v() not i(): a drag fires this on every step, so keep it out of a
                // Standard-level capture.
                AppLog.v(TAG, "EV slider -> ${AutelExposureController.labelAt(idx)} (index $idx)")
                AutelExposureController.setEvAt(
                    applicationContext, AutelProductHolder.xt706, idx) {}
            }
        }

        crosshairView.onReticleTap = { onQuickMarkerAction("tap: reticle") }
        crosshairView.onReticleLongPress = { onUnknownMarkerAction("long-press: reticle") }

        VideoStreamerHolder.onStateChanged = Runnable { refreshStreamToggle() }
        refreshStreamToggle()
        streamToggle.setOnClickListener {
            if (VideoStreamerHolder.isActive) {
                AppLog.v(TAG, "LIVE tapped: stopping")
                ScreenCaptureService.stop(applicationContext)
                VideoStreamerHolder.stop()
                toast("Video stream stopped")
                refreshStreamToggle()
            } else {
                onStartStreamTapped()
            }
        }
        // Long-press picks the quality tier, same tap/long-press split the AR and drop-pin
        // buttons use: short tap does the obvious thing, long-press configures it.
        streamToggle.setOnLongClickListener { onVideoQualityTapped(); true }

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
        // SHORT TAP IS ALWAYS "CREATE AND DROP A MARKER" (operator, 2026-08-02). Same split as
        // the DJI sibling: tap makes a new marker, long-press manages the existing ones.
        //
        // An earlier pass sent a GATED short tap to the markers list instead. That came from a
        // real complaint — the button used to be a dead end when the aim was poor — but it
        // over-corrected: on the ground, or at a shallow gimbal angle, the gate is the NORMAL
        // state, so in practice both gestures landed on the same list and the create flow became
        // unreachable. Two gestures that do the same thing are one gesture and a bug.
        //
        // So the picker opens unconditionally and the gate is evaluated at PLACEMENT. The pilot
        // always reaches the menu, and still cannot place a marker whose position would be wrong
        // — which was the point of the gate all along. The reason is shown in the picker's title
        // too, so it is known BEFORE choosing rather than after.
        findViewById<ImageButton>(R.id.flightDropPinButton).setOnClickListener {
            AppLog.v(TAG, "Drop Pin tapped")
            pickAffiliationThen { aff ->
                // Re-read the look-point HERE, not before the dialog: the aircraft keeps flying
                // while the picker is open, so the position captured when the button was tapped
                // may be seconds stale by the time an affiliation is chosen.
                val gp = TakBridgeHolder.lookPoint()
                when {
                    aimTooPoorToDrop() -> refuseDropForAim()
                    gp == null -> {
                        AppLog.w(TAG, "drop refused — no look-point (GPS/gimbal not ready)")
                        showNotice("Cannot place the marker. Wait for GPS and the gimbal.",
                            refused = true)
                    }
                    else -> TakDropMarkers.placeAt(aff, gp.first, gp.second, gp.third)
                }
            }
        }
    }

    /**
     * Hides the status and navigation bars for the whole flight screen.
     *
     * Two reasons, and the second is the operational one:
     *  1. The bars cost screen the FPV image should have.
     *  2. The TAK feed is a SCREEN CAPTURE, so the Android status bar was being broadcast to the
     *     whole team — clock, battery, carrier, and any notification that happened to arrive.
     *     That is both clutter and a small privacy leak on a shared picture.
     *
     * BEHAVIOUR_SHOW_TRANSIENT_BARS_BY_SWIPE, not a hard hide: a pilot must still be able to
     * reach the bars deliberately, and they slide back away on their own. A screen that cannot
     * be escaped is the wrong answer on a device that also has to do other things.
     *
     * Re-applied in [onWindowFocusChanged] because Android restores the bars after a dialog,
     * a permission prompt or a screen-off — and this screen shows plenty of dialogs.
     */
    @Suppress("DEPRECATION")
    private fun goFullScreen() {
        // Legacy systemUiVisibility, NOT WindowInsetsController. This project is on
        // androidx.appcompat 1.0.0, whose androidx.core predates WindowInsetsControllerCompat,
        // and bumping androidx across this old dependency set to hide a status bar is a much
        // bigger risk than using the deprecated call. Deprecated is not broken: these flags are
        // still honoured through API 33, which is this app's compileSdk.
        //
        // IMMERSIVE_STICKY is the deliberate choice over plain IMMERSIVE: the bars come back
        // transiently on a swipe and hide themselves again, so a pilot can always reach them
        // and never has to think about restoring the screen afterwards.
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullScreen()
    }

    override fun onResume() {
        super.onResume()
        AppLog.v(TAG, "onResume")
        AutelProductHolder.install()   // reclaim the global product listener (see holder docs)
        AutelProductHolder.addCameraReadyListener(cameraReadyObserver)
        AutelProductHolder.addListener(connectionObserver)
        // Re-read the camera's real thermal state each time this screen comes up. Autel's own
        // app can have left the camera in IR, and the buttons must reflect the camera rather
        // than a default. No-ops when no camera is attached.
        syncIrStateFromCamera()
        // Same reasoning for the zoom, and the same source of error: Autel's own app can have
        // left the camera zoomed. The pill must show what the AIRCRAFT holds.
        seedZoomFromCamera()
        map.onResume()
        installHardwareButtonListener()
        ControllerCompass.start(this)   // BVLOS antenna aim; no-op without the sensor
        // Pre-Flight Setup may have changed the two battery levels while this screen was in the
        // background, and the gauge's colours are meaningless if they lag them.
        refreshBatteryBands()
        handler.post(refresh)
    }

    /**
     * Colours the toolbar gauge from the SAME two settings Pre-Flight sends to the aircraft:
     * amber from Battery Warning, red from Battery Critical. Hard-coded edges here would drift
     * from the thresholds every time they were retuned, which is how the gauge previously ended
     * up showing amber while the aircraft was seconds from acting.
     */
    private fun refreshBatteryBands() {
        // AIRCRAFT FIRST, pref only as a stand-in. The pilot's saved value is what they INTEND;
        // it differs from the aircraft's whenever a level was edited but not applied, or an
        // apply failed. A gauge is read to judge how much flying is left, so it has to be
        // coloured from the levels the aircraft will actually act on.
        val warn = FlightLimitsController.aircraftWarningPct
            ?: FlightLimitsController.savedLowBatteryPct(this).toFloatOrNull() ?: 15f
        val crit = FlightLimitsController.aircraftCriticalPct
            ?: FlightLimitsController.savedCriticalBatteryPct(this).toFloatOrNull() ?: 10f
        toolbarBattery.setBands(crit, warn)
    }

    override fun onPause() {
        super.onPause()
        AppLog.v(TAG, "onPause")
        // Paired with the registrations in onResume. Both lists are also cleared when the link
        // is released, thus a missed removal cannot leak an activity across a product cycle.
        AutelProductHolder.removeCameraReadyListener(cameraReadyObserver)
        AutelProductHolder.removeListener(connectionObserver)
        map.onPause()
        ControllerCompass.stop()
        // Dropped while this screen is not showing: the hardware button must not
        // place markers from the home screen or with the app in the background.
        runCatching { AutelProductHolder.evo2?.remoteController?.setRemoteButtonControllerListener(null) }
        handler.removeCallbacks(refresh)
    }

    /**
     * Maps the CONTROLLER HARDWARE BUTTONS onto the two controls a pilot most wants without
     * taking a hand off the sticks or hunting for a touch target:
     *
     *   C2 (custom B) — the quick marker: place it, or move it to what the camera is on
     *   C1 (custom A) — thermal: short changes visible/thermal, long changes the IR colours
     *
     * Short press  -> place the quick marker ([TakDropMarkers.QUICK_NAME])
     * Long press   -> re-aim the existing one at what the camera is looking at now
     *
     * Deliberately the SAME two functions the reticle already offers, so the two routes cannot
     * drift apart — and both go through the red-reticle gate, so a hardware button cannot place
     * a marker the touch UI would have refused.
     *
     * ⚠ WHICH PHYSICAL BUTTON IS UNCONFIRMED. The SDK exposes only CUSTOM_BUTTON_{SHORT,LONG}_A
     * and _B — there is no "C1"/"C2" in its vocabulary — and nothing documents which label maps
     * to which letter. [QUICK_MARKER_BUTTON] is set to B as the working assumption for C2; EVERY
     * event received is logged, so one press tells us the truth and the constant is a one-line
     * correction. Guessing silently is how the wrong button ends up wired.
     *
     * Registered per-screen rather than globally: it is dropped in onPause so the button cannot
     * place markers from the home screen or with the app in the background.
     */
    private fun installHardwareButtonListener() {
        val rc = AutelProductHolder.evo2?.remoteController ?: return
        runCatching {
            rc.setRemoteButtonControllerListener(
                object : com.autel.common.CallbackWithOneParam<
                    com.autel.common.remotecontroller.RemoteControllerNavigateButtonEvent> {
                    override fun onSuccess(
                        e: com.autel.common.remotecontroller.RemoteControllerNavigateButtonEvent?,
                    ) {
                        e ?: return
                        // READ keyValue HERE AND NOWHERE ELSE. The event is an ENUM CONSTANT,
                        // thus a process-wide singleton, and the SDK's packet parser mutates
                        // this field on it (RCButtonPacket.parseBody -> setKeyValue) before
                        // handing it over. Reading it after a thread hop would read whatever
                        // the NEXT packet has since written. Captured now, passed by value.
                        val keyValue = e.keyValue
                        // Logged unconditionally — this is how the button mapping gets confirmed,
                        // and how anyone later finds out what the other controls emit.
                        AppLog.i(TAG, "controller button event: $e (keyValue=$keyValue)")
                        // SDK thread — everything below shows toasts and touches views.
                        when (e.name) {
                            // C2: markers, MIRRORING the crosshair exactly — short re-aims the one
                            // quick marker, long drops a NEW stationary Unknown marker. Same idiom
                            // as C1 and the IR buttons: a pilot who has learned the on-screen
                            // control has learned the button, and both routes end in the SAME
                            // function, thus the two can never drift apart. The SDK gives short
                            // and long as separate events, thus no timer is needed here.
                            "CUSTOM_BUTTON_SHORT_$QUICK_MARKER_BUTTON" ->
                                runOnUiThread { onQuickMarkerAction("controller C2 short") }
                            "CUSTOM_BUTTON_LONG_$QUICK_MARKER_BUTTON" ->
                                runOnUiThread { onUnknownMarkerAction("controller C2 long") }

                            // C1: thermal, MIRRORING the on-screen IR buttons exactly — short
                            // changes the camera between visible and thermal, long changes the
                            // thermal colours. A pilot who has learned one control has learned
                            // the other, and both routes end in the SAME two functions, thus the
                            // button labels and the camera cannot disagree.
                            //
                            // The long press does nothing while the visible camera is live: the
                            // palette button is hidden then, and a colour change the pilot cannot
                            // see is worse than no action. It is announced, not silent.
                            "CUSTOM_BUTTON_SHORT_$IR_BUTTON" ->
                                runOnUiThread { onIrTapped() }
                            "CUSTOM_BUTTON_LONG_$IR_BUTTON" ->
                                runOnUiThread {
                                    if (irOn) onIrPaletteTapped()
                                    else showNotice("The thermal camera is off.")
                                }

                            // RIGHT ZOOM ROCKER — continuous zoom, confirmed on hardware
                            // 2026-08-06: deflecting it emits ZOOM_IN/ZOOM_OUT on THIS listener,
                            // about every 200ms, and the app was already receiving and ignoring
                            // them. The left control is the gimbal and emits nothing here, so
                            // there is no ambiguity between the two.
                            //
                            // keyValue IS THE DEFLECTION, 0 TO 225, AND ZERO MEANS RELEASED.
                            // The rocker keeps emitting while the spring returns it to centre,
                            // and those trailing events carry keyValue 0 — that is the whole
                            // release signal, and dropping them is what stops the zoom running
                            // on after the pilot lets go. Measured over 45 presses on hardware
                            // 2026-08-15: every one ended in two or three keyValue-0 events and
                            // NOT ONE had a zero among the held events, so the test is exact
                            // rather than a heuristic. Before this the app read only e.name and
                            // could not tell the two apart at all; replaying those 45 presses
                            // shows the zoom ran on by as much as 1.8x past the release.
                            //
                            // Deliberately NOT routed through applyZoom(step): the whole point
                            // is the values between the steps. See onZoomRocker.
                            "ZOOM_IN" -> if (keyValue > 0) runOnUiThread { onZoomRocker(+1) }
                            "ZOOM_OUT" -> if (keyValue > 0) runOnUiThread { onZoomRocker(-1) }
                        }
                    }
                    override fun onFailure(error: AutelError?) {
                        AppLog.w(TAG, "controller button listener error: ${error?.description}")
                    }
                })
            AppLog.i(TAG, "hardware quick-marker button armed (custom button $QUICK_MARKER_BUTTON)")
        }.onFailure { AppLog.w(TAG, "hardware button listener install failed: ${it.message}") }
    }

    /**
     * Stops the outbound stream when the flight screen is no longer on display.
     *
     * **This is a privacy control, not housekeeping.** The stream is a mirror of the whole
     * screen. Once the pilot leaves this activity — home screen, another app, Pre-Flight
     * Setup — a capture that kept running would broadcast whatever they do next to the entire
     * team. Nothing else in the app can leak like that, so it is stopped the moment this
     * screen stops being visible.
     *
     * onStop, not onPause: a dialog (markers list, RTH confirm, AR options) only pauses the
     * activity, and killing the stream every time a pilot opens a menu would make it unusable.
     * onStop fires only when the screen is genuinely no longer shown.
     *
     * The TAK connection and telemetry bridge deliberately keep running — those belong to the
     * foreground service and carry no screen contents.
     */
    override fun onStop() {
        super.onStop()
        if (VideoStreamerHolder.isActive) {
            AppLog.i(TAG, "flight screen no longer visible — stopping screen capture")
            ScreenCaptureService.stop(applicationContext)
            VideoStreamerHolder.stop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLog.v(TAG, "onDestroy")
        // Drop every pending delayed callback on this screen's handler. onPause removes the HUD
        // refresh loop, but the one-shot postDelayed lambdas (record-verify after a mode-switch
        // settle, the transient-notice auto-hide) capture the camera/context and would otherwise
        // fire against a destroyed activity if it is torn down inside their delay window. None
        // needs to survive the screen — they touch this screen's views.
        handler.removeCallbacksAndMessages(null)
        // Stop a Home-reset location request if one is still waiting for a fix.
        runCatching {
            stopHomeLocationUpdates(
                getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
            )
        }
        arOverlay.stop()
        VideoStreamerHolder.onStateChanged = null
        TakMapMarkers.onMapDestroyed()
        // Belt and braces on top of onStop() — a destroy without a stop shouldn't be possible,
        // but leaving a screen capture alive would be the one leak worth being paranoid about.
        ScreenCaptureService.stop(applicationContext)
        VideoStreamerHolder.stop()
        // NOTE: the bridge and TAK connection deliberately keep running — they belong to the
        // foreground service lifecycle, not this screen (same as TAKPilot2).
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
        // Local wall clock, 24-hour with seconds. Driven from the HUD tick (500ms), which is
        // twice the rate needed for a seconds display — so it never visibly skips a second.
        // Locale.US pins the digits and separators: some locales substitute their own
        // numerals, and a viewer comparing this against their own clock to measure stream
        // delay needs it to read the same way on every device.
        fpvClock.text = clockFormat.format(java.util.Date())

        // Obstacle arcs. Fed here, on the 500 ms HUD tick, NOT from the radar callback — the
        // sensor pushes several times a second per face and a full-screen invalidate at that
        // rate is wasted work. The view keeps each face's last real reading, so a slower
        // refresh loses nothing.
        obstacleEdges.update(AutelAvoidance.radar)

        // Aircraft warning banner (v1.5.9). Polled on the same tick for the same reason as
        // the arcs: the source repeats at 2Hz, and FlightWarnings owns the edge/hold logic,
        // so this is just "draw what it says".
        val warning = FlightWarnings.display()
        if (warning == null) {
            fpvWarningBanner.visibility = View.GONE
        } else {
            fpvWarningBanner.text = warning.text
            fpvWarningBanner.background?.setTint(androidx.core.content.ContextCompat.getColor(
                this, if (warning.red) R.color.tp_warn_banner_red else R.color.tp_warn_banner_amber))
            fpvWarningBanner.visibility = View.VISIBLE
        }

        val hud = TakBridgeHolder.hud()
        val takOk = TakManager.getInstance().isConnected
        val acOk = AutelProductHolder.isConnected

        // Instrument toolbar
        val takColor = if (takOk) androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_go) else androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_danger)
        toolbarTakIcon.alpha = if (takOk) 1.0f else 0.4f
        // setColorFilter on the SRC drawable, not the background. The dot is an ImageView with
        // android:src and no background at all, so the previous background-tinting version was
        // a no-op in both states and the dot rendered the shape's literal #FFFFFF — a white dot
        // that never went green or red. Matches the DJI blueprint and our own FieldGuideActivity,
        // both of which already do it this way.
        toolbarTakDot.setColorFilter(takColor)
        toolbarBattery.setPercent(hud?.batteryPct?.takeIf { hud.hasFix || it > 0 })
        toolbarGps.text = if (hud?.hasFix == true) hud.sats.toString() else "—"
        val signalPct = hud?.uplinkSignalPct
        toolbarSignal.setPercent(signalPct)
        toolbarSignalText.text = if (signalPct != null) "${bucketSignalPct(signalPct)}%" else "—%"
        toolbarSignalText.alpha = if (signalPct != null) 1.0f else 0.4f
        // "Waiting for aircraft…" cover. Gated on the product connection rather than on real
        // decoded frames: AutelCodecView gives no frame callback (the codec listener in
        // AutelVideoStreamer only fires while the RTSP push is running, which is a separate
        // thing a pilot may never turn on). So this can briefly clear a moment before the first
        // frame actually paints — deliberately worded "waiting for aircraft", not "waiting for
        // video", so it does not claim more than it knows.
        findViewById<View>(R.id.flightNoVideoCover).visibility =
            if (acOk) View.GONE else View.VISIBLE

        // Debug-only memory/CPU/GPU/contact overlay — see AppLog.resourceMonitor. Piggybacks on
        // the same slow cadence the old exposure poll used (500ms * 4 = ~2s): frequent enough to
        // actually watch a leak grow, cheap enough that the monitor itself is not a load source.
        //
        // Also written to the debug log (not just the on-screen row) — the whole point of this
        // overlay was chasing leaks that only became clear from a TREND over a flight, and the
        // screen shows only the current instant. Both media.swcodec (2026-08-04) and the
        // CotParser contact-retention bug (2026-08-03) were found by pulling numbers OUT of the
        // log after the fact; a resource monitor that never reaches the log can't be replayed
        // post-flight, only watched live. Same tag as the rest of this class (not TAK/radar), so
        // it is unaffected by AppLog.takLogging/radarLogging.
        if (AppLog.resourceMonitor && hudTickCount % 4 == 0) {
            val segments = ResourceMonitor.formattedSegments(this)
            segments.forEachIndexed { i, text -> resourceMonitorCells[i].text = text }
            AppLog.d(TAG, "RES  " + segments.joinToString("  "))
        }

        // REC shows the CAMERA's own reported state (MediaStatus events), not the last button
        // press — so a record that failed to start, or stopped itself (card full/removed),
        // shows truthfully within a tick.
        recordToggle.setRecording(AutelProductHolder.isRecording)
        // Shutter is locked out while recording. Shooting a still mid-record would drag the
        // camera through VIDEO -> SINGLE -> VIDEO underneath a running recording — which at best
        // jumps the picture the pilot and the whole TAK team are watching, and at worst
        // interrupts the recording itself. Greyed rather than hidden so the control does not
        // move around under the pilot's thumb.
        shootPhotoButton.isEnabled = !AutelProductHolder.isRecording
        shootPhotoButton.alpha = if (AutelProductHolder.isRecording) 0.4f else 1f
        if (AutelProductHolder.photoTakenFlag) {
            AutelProductHolder.photoTakenFlag = false
            showNotice("Photo Saved")
        }

        // Home point is independent of the CURRENT fix — once set it stays valid even if the
        // live fix drops momentarily, so this is not gated behind hasFix like the map work below.
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
        updateRthAltitude()

        fpvHomeDistance.text = if (hud != null && hud.hasFix && homeSet) {
            val dist = CameraSlantPoint.distanceMeters(hud.homeLat, hud.homeLon, hud.lat, hud.lon)
            val bearing = CameraSlantPoint.initialBearingDeg(hud.homeLat, hud.homeLon, hud.lat, hud.lon)
            "HOME %s  %03.0f°T".format(Units.feet(dist), bearing)
        } else {
            "HOME — ft  —°T"
        }

        updateAntennaAim(hud)

        // Same readout as the DJI blueprint, imperial throughout (see Units).
        fpvOverlayText.text = buildString {
            // LINE ORDER IS DELIBERATE (operator, 2026-08-02), most-glanced-at first:
            //   1 callsign + speed   2 AGL/MSL   3 lat/lon   4 home
            // Height moved up to second because it is the number a pilot checks constantly;
            // lat/lon and home are reference figures they look up only when asked for them.
            // The clock sits above this block in its own view — see fpvClock.
            append(TakManager.getInstance().callsign ?: "—")
            append(if (hud != null) "   ${Units.mph(hud.speedMs)}" else "   — mph")
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
            // AGL AND MSL GET THEIR OWN LINES, never "AGL · MSL" on one.
            //
            // They shared a line until the HUD readouts moved onto a fixed, map-width panel
            // (2026-08-04). At that width the pair no longer fits, and the wrap fell between a
            // number and its unit — "988 ft" on one line, "MSL" on the next, which reads as a
            // different quantity at a glance. Two lines cannot wrap that way, and stay safe at
            // five-digit altitudes where even a wider panel would break.
            append('\n')
            val msl = aglReading.mslMeters
            append(if (msl != null) "%s MSL".format(Units.feet(msl)) else "— ft MSL")
            append('\n')
            if (hud != null && hud.hasFix) {
                append("%.4f, %.4f".format(hud.lat, hud.lon))
            } else {
                append("—, —")
            }
            // HOME used to be a fourth line here. It moved to its own view in the BOTTOM block,
            // between RTH and the FAA ceiling (operator, 2026-08-04) — it reads as return-to-home
            // information, which is what the rest of that group is.
            // AC and TAK state used to be appended here and were removed (operator, 2026-07-31)
            // as redundant: the toolbar already carries both — the TAK badge's green/red dot,
            // and the aircraft through the battery gauge, GPS count and signal bars, which all
            // go blank without one. Losing the line also lets the FAA ceiling sit directly
            // above the mini-map, which is where a pilot looks for it.
            //
            // The "SPI ✓" line that used to live here was dropped too (operator, 2026-08-01).
            // The earlier argument for keeping it — that nothing else says whether the camera
            // look-point is being published — did not survive contact with the actual screen:
            // the pilot toggles SPI deliberately and the button carries its own state, so the
            // line was restating a thing the pilot had just done.
        }

        if (hud == null || !hud.hasFix) return

        // Aircraft marker
        val pos = GeoPoint(hud.lat, hud.lon)
        val mk = aircraftMarker ?: Marker(map).apply {
            title = "Aircraft"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            infoWindow = null
            // Heading arrow, not the app logo: this marker is ROTATED to the aircraft's
            // heading each tick (see mk.rotation below), and a logo cannot show a direction —
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
        // UNVERIFIED SIGN. osmdroid applies the marker's bearing to the canvas as -mBearing,
        // so this double negation should turn the chevron clockwise with increasing heading —
        // correct for compass heading. Traced in the 6.1.14 bytecode, never watched turning:
        // it needs a live aircraft, which this build has not had.
        //
        // The ADS-B symbols were confirmed correct against live traffic on 2026-08-01, but that
        // does NOT carry over — those bake rotation in with Canvas.rotate(+course) rather than
        // going through Marker.rotation, so they exercise a different sign convention.
        //
        // To check: point the aircraft north, confirm the chevron points up the screen, then
        // yaw right 90 degrees and confirm it points right. If it turns the wrong way, drop one
        // of the two minus signs (here or in osmdroid's convention) — not both.
        mk.rotation = -hud.headingDeg.toFloat()
        // The locked map's only camera movement: keep the aircraft centred, zoom untouched.
        map.controller.setCenter(pos)

        // Home→aircraft line: the pilot's "which way back" reference on a map that by design
        // cannot be panned around to look. Only meaningful once a home point exists.
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
        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Return to Home")
            .setMessage("Command the aircraft to return to home now?")
            .setPositiveButton("Return to Home") { _, _ ->
                val fc = AutelProductHolder.evo2?.flyController
                if (fc == null) {
                    toast("The aircraft is not connected.")
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
        toast("$name is not available in this build yet.")
    }

    /**
     * LIVE tapped with no stream running. Checks the stream is configured, then asks for the
     * screen-capture permission; [onActivityResult] starts the foreground service, which starts
     * the push.
     *
     * The configured check happens BEFORE the permission prompt on purpose — making a pilot
     * grant screen capture and only then telling them the video server is not set up would be
     * two wasted taps and a confusing order.
     */
    /**
     * Video quality tier picker — long-press on the LIVE badge.
     *
     * Writes the same `video_profile` pref Pre-Flight Setup writes, so the two stay in sync with
     * no second source of truth. Reachable in flight because the right tier depends on the link,
     * which is a thing a pilot learns AFTER taking off, not during setup.
     *
     * **A change while streaming applies immediately** — the pilot is choosing a tier because the
     * link is misbehaving now, so "it'll take effect next time" would be the wrong answer. The
     * encoder is configured once at stream start, so this restarts the push; it does NOT re-prompt
     * for screen capture, because the MediaProjection outlives the stream and
     * [ScreenCaptureService.restart] reuses it. Viewers see a brief reconnect.
     */
    private fun onVideoQualityTapped() {
        AppLog.v(TAG, "long-press: video quality")
        val prefs = getSharedPreferences("takpilot2_tak", MODE_PRIVATE)
        val current = TranscodeProfile.fromPref(prefs.getString("video_profile", null))

        val view = layoutInflater.inflate(R.layout.dialog_video_quality, null)
        val group = view.findViewById<android.widget.RadioGroup>(R.id.videoQualityGroup)

        // Built from the enum, not from XML — same reason as the AR category rows.
        val ids = TranscodeProfile.values().associateWith { profile ->
            val button = layoutInflater.inflate(R.layout.row_video_quality, group, false)
                as android.widget.RadioButton
            button.id = View.generateViewId()
            button.text = profile.label
            group.addView(button)
            button.id
        }
        group.check(ids.getValue(current))
        group.setOnCheckedChangeListener { _, checkedId ->
            val chosen = ids.entries.firstOrNull { it.value == checkedId }?.key ?: return@setOnCheckedChangeListener
            if (chosen == current) return@setOnCheckedChangeListener
            prefs.edit().putString("video_profile", chosen.prefValue).apply()
            AppLog.i(TAG, "video quality -> ${chosen.label}")
            if (VideoStreamerHolder.isActive) {
                ScreenCaptureService.restart(applicationContext)
                toast("Video quality: ${chosen.label} — restarting stream")
            } else {
                toast("Video quality: ${chosen.label}")
            }
        }

        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Video Quality")
            .setView(view)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun onStartStreamTapped() {
        val p = getSharedPreferences("takpilot2_tak", MODE_PRIVATE)
        if ((p.getString("video_host", "") ?: "").isEmpty() ||
            (p.getString("video_streamid", "") ?: "").isEmpty()) {
            toast("Set up the stream in Pre-Flight Setup first")
            return
        }
        AppLog.i(TAG, "tap: LIVE — requesting screen-capture permission")
        val mpm = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
            as android.media.projection.MediaProjectionManager
        toast("The video stream is starting.")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MEDIA_PROJECTION) return
        if (resultCode == RESULT_OK && data != null) {
            AppLog.i(TAG, "screen-capture permission GRANTED — starting ScreenCaptureService")
            ScreenCaptureService.start(this, resultCode, data)
        } else {
            AppLog.w(TAG, "screen-capture permission DENIED (resultCode=$resultCode)")
            toast("You did not allow screen capture. The stream did not start.")
        }
        refreshStreamToggle()
    }

    /**
     * Applies [mapWide] to the map and the button, optionally saving it.
     *
     * The button is labelled with what a tap WOULD DO, not with the state it is in — the
     * standard toggle convention (a media button shows "pause" while playing). At NEAR it
     * reads WIDE, and vice versa.
     *
     * Recentring is left to the HUD tick, which runs every 500ms and calls setCenter; there is
     * no need to re-centre here and doing so would fight it.
     */
    private fun applyMapZoom(persist: Boolean) {
        val zoom = if (mapWide) MAP_ZOOM_WIDE else MAP_ZOOM_NEAR
        map.controller.setZoom(zoom)
        mapZoomButton.text = if (mapWide) "NEAR" else "WIDE"
        if (persist) {
            getSharedPreferences("takpilot2_tak", MODE_PRIVATE)
                .edit().putBoolean(KEY_MAP_WIDE, mapWide).apply()
        }
        AppLog.i(TAG, "mini-map zoom = $zoom (${if (mapWide) "WIDE" else "NEAR"})")
    }

    /**
     * Double-tap the map to make it twice as wide and twice as tall; double-tap again to restore.
     * Operator's request, 2026-08-04.
     *
     * **It grows OVER the screen, not by pushing things aside.**
     *  - Width is free: [flightHudColumn] is end-aligned, so a wider child simply extends LEFT
     *    across the video.
     *  - Height needs the negative top margin. The column is a vertical LinearLayout, so without
     *    it the map would shove the readouts above it upward — and 2x here (480dp) plus the two
     *    HUD blocks exceeds the column's usable height, so something would be squeezed or
     *    clipped. The negative margin makes it grow upward across the readouts instead, which is
     *    what the operator asked for.
     *  - `elevation` puts it above its siblings in the draw order. `bringToFront()` would ALSO
     *    work visually but reorders the LinearLayout's children, which changes where everything
     *    else sits — exactly the layout damage this is avoiding.
     *
     * The zoom level does not change, so an expanded map shows FOUR TIMES the ground, not a
     * magnified version of the same ground. That is the useful direction: the reason to expand
     * is usually to see more of the picture.
     *
     * Marker sizes are in dp and do not scale, so they get relatively smaller when expanded —
     * which is also the right direction.
     */
    private fun toggleMapSize() {
        mapExpanded = !mapExpanded
        val w = resources.getDimensionPixelSize(R.dimen.flight_map_size)
        val h = resources.getDimensionPixelSize(R.dimen.flight_map_height)
        val lp = mapContainer.layoutParams as android.widget.LinearLayout.LayoutParams
        if (mapExpanded) {
            lp.width = w * 2
            lp.height = h * 2
            lp.topMargin = -h            // absorb the extra height upward, over the readouts
            mapContainer.elevation = 8f * resources.displayMetrics.density
        } else {
            lp.width = w
            lp.height = h
            lp.topMargin = 0
            mapContainer.elevation = 0f
        }
        mapContainer.layoutParams = lp
        // The doubled map earns the full-length scale bar; the small map keeps the short
        // one so the bar never reaches the zoom toggle (operator, 2026-08-13).
        mapScaleBar?.setMaxLength(if (mapExpanded) SCALE_BAR_CM_FULL else SCALE_BAR_CM_SMALL)
        map.invalidate()
        AppLog.i(TAG, "mini-map ${if (mapExpanded) "EXPANDED to ${w * 2}x${h * 2}px" else "restored"}")
    }

    /** Bucket raw signal % into coarse steps for display (operator's spec): 0-10% shows as
     *  0%, otherwise round to the nearest of 25/50/75/100%. Identical to the DJI blueprint's. */
    private fun bucketSignalPct(pct: Int): Int {
        if (pct <= 10) return 0
        val buckets = intArrayOf(25, 50, 75, 100)
        return buckets.minByOrNull { kotlin.math.abs(it - pct) } ?: 0
    }

    /**
     * Tap on the signal indicator — reports the uncoarsened figure, or explains its absence.
     *
     * The "close Autel Explorer" case is not a guess. The RC message channel that carries
     * signal strength and controller battery is SINGLE-CLIENT: while Explorer (or its
     * background service) holds it, our `setInfoDataListener` registers cleanly and then never
     * fires — no callback, no error. Aircraft telemetry keeps working throughout, because that
     * travels the RNDIS link rather than the controller's MCU bus, so the failure looks like
     * "only the signal bars are broken".
     *
     * Diagnosed on hardware 2026-08-02: with Explorer running, sig=-% indefinitely; force-stop
     * Explorer and the same build reports sig=100% rcBat=78%, matching what Explorer itself had
     * been showing. Worth stating in the toast because the symptom gives a pilot no way to
     * guess the cause.
     */
    private fun signalDetail() {
        val pct = TakBridgeHolder.hud()?.uplinkSignalPct
        AppLog.v(TAG, "tap: signal bars (pct=${pct ?: "-"})")
        toast(
            when {
                pct != null -> "Controller link: $pct%"
                AutelProductHolder.isConnected ->
                    "No link reading. Close Autel Explorer if it is open — only one app at a " +
                        "time can read the controller's signal."
                else -> "No link reading yet — connect the aircraft."
            }
        )
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
     * in review — worth the explicit note so it does not get "simplified" back.)
     *
     * Refuses rather than guesses when there's no controller fix: a stale or absent position
     * here is a genuine safety problem, not a cosmetic one.
     */
    private fun confirmResetHome() {
        val fc = AutelProductHolder.evo2?.flyController
        if (fc == null) {
            AppLog.w(TAG, "reset home point ignored — aircraft not connected")
            toast("The aircraft is not connected.")
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
        // The controller's GPS receiver stays OFF until an app asks for updates, so
        // getLastKnownLocation returns null forever on this hardware. Power it on and wait for a
        // fix (see acquireControllerLocation) rather than reading an empty cache.
        showNotice("Getting your location…")
        acquireControllerLocation { loc ->
            if (loc == null) {
                AppLog.w(TAG, "reset home point aborted — no controller GPS fix")
                toast("The controller has no GPS position. Make sure location is on, go " +
                    "outside with a clear view of the sky, and try again.")
                return@acquireControllerLocation
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
            AppLog.i(TAG, "location permission denied — cannot set home to controller position")
            toast("Location permission is needed to set the home point to your position.")
        }
    }

    /**
     * Delivers a fix from the CONTROLLER (the Smart Controller V3 has its own GPS), or null.
     *
     * `getLastKnownLocation()` alone is a trap on this hardware: it reads a cache that nothing
     * fills unless an app has called `requestLocationUpdates()`, so the receiver sits at
     * `mStarted=false` and the cache stays null — permanently, outdoors included (see
     * TakConnectActivity's note). So use a recent cached fix if we have one, otherwise POWER THE
     * RECEIVER ON for a single live update with a timeout. Asynchronous: the result comes back on
     * the main thread via [onResult].
     */
    @android.annotation.SuppressLint("MissingPermission")   // callers gate on hasLocationPermission()
    private fun acquireControllerLocation(onResult: (android.location.Location?) -> Unit) {
        val lm = getSystemService(android.content.Context.LOCATION_SERVICE)
            as android.location.LocationManager
        val cached = latestCachedLocation(lm)
        if (cached != null && System.currentTimeMillis() - cached.time < FRESH_FIX_MS) {
            onResult(cached); return
        }
        val provider = when {
            lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ->
                android.location.LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) ->
                android.location.LocationManager.NETWORK_PROVIDER
            else -> { onResult(cached); return }   // location services off — nothing to power on
        }
        stopHomeLocationUpdates(lm)   // clear any request still pending from a previous tap
        var done = false
        val timeout = Runnable {
            if (done) return@Runnable
            done = true
            stopHomeLocationUpdates(lm)
            onResult(cached)          // best we have when the receiver did not answer in time
        }
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: android.location.Location) {
                if (done) return
                done = true
                handler.removeCallbacks(timeout)
                stopHomeLocationUpdates(lm)
                onResult(location)
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("required by LocationListener on older APIs")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }
        homeLocListener = listener
        val ok = runCatching {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, android.os.Looper.getMainLooper())
        }.isSuccess
        if (!ok) { stopHomeLocationUpdates(lm); onResult(cached); return }
        handler.postDelayed(timeout, LOCATION_TIMEOUT_MS)
    }

    @android.annotation.SuppressLint("MissingPermission")   // callers gate on hasLocationPermission()
    private fun latestCachedLocation(lm: android.location.LocationManager): android.location.Location? =
        runCatching {
            listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER,
            ).mapNotNull { p -> if (lm.isProviderEnabled(p)) lm.getLastKnownLocation(p) else null }
                .maxByOrNull { it.time }
        }.getOrNull()

    private fun stopHomeLocationUpdates(lm: android.location.LocationManager) {
        homeLocListener?.let { runCatching { lm.removeUpdates(it) } }
        homeLocListener = null
    }

    // ---- AR overlay ----

    /** AR on/off.
     *
     *  ON by default every time the flight screen opens (operator, 2026-08-15). It was OFF by
     *  default until then, on the reasoning that an overlay drawn over the video should be
     *  switched on deliberately rather than inherited. The operator overruled that after flying
     *  it: the overlay is wanted on nearly every sortie, and starting each one by reaching for
     *  this button was the greater nuisance.
     *
     *  The accuracy limit stays where it was, in the AR options dialog and the Field Guide
     *  (operator's call, same day). A pilot can now see the overlay without having opened the
     *  dialog that states it — that is known and accepted for a trained fleet. */
    private fun onArToggleTapped() {
        if (arOverlay.isRunning) arOverlay.stop() else arOverlay.start()
        AppLog.v(TAG, "tap: AR overlay -> ${if (arOverlay.isRunning) "ON" else "OFF"}")
        refreshArButton()
    }

    /**
     * AR options — what the overlay may draw, and how far out it draws air traffic. One dialog
     * showing every switch at once; everything applies LIVE (the overlay reads ArSettings every
     * frame), which is what makes decluttering a usable in-flight action rather than a setup
     * step.
     */
    private fun onArOptionsTapped() {
        AppLog.v(TAG, "long-press: AR options")
        val view = layoutInflater.inflate(R.layout.dialog_ar_options, null)

        // Rows built from the enum, not written out in XML — a category added later cannot be
        // silently missing from the menu that controls it.
        val container = view.findViewById<android.widget.LinearLayout>(R.id.arCategoryContainer)
        for (category in ArSettings.Category.values()) {
            val row = layoutInflater.inflate(R.layout.row_ar_category, container, false)
                as android.widget.CheckBox
            row.text = category.label
            row.isChecked = ArSettings.isEnabled(this, category)
            row.setOnCheckedChangeListener { _, isChecked ->
                ArSettings.setEnabled(this, category, isChecked)
            }
            container.addView(row)
        }

        val group = view.findViewById<android.widget.RadioGroup>(R.id.arRangeGroup)
        val rangeIds = mapOf(
            ArSettings.AirRange.MI_2_5 to R.id.arRange25,
            ArSettings.AirRange.MI_5 to R.id.arRange5,
            ArSettings.AirRange.MI_15 to R.id.arRange15,
        )
        group.check(rangeIds.getValue(ArSettings.airRange(this)))
        group.setOnCheckedChangeListener { _, checkedId ->
            rangeIds.entries.firstOrNull { it.value == checkedId }?.let {
                ArSettings.setAirRange(this, it.key)
            }
        }

        val dialog = AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("AR Overlay")
            .setView(view)
            .setPositiveButton("Done", null)
            .setNeutralButton("Calibrate FOV…") { _, _ -> onArCalibrateTapped() }
            .setNegativeButton("Aim Offsets…") { _, _ -> onAimOffsetsTapped() }
            .show()

        // Straight to the AR entry, not the top of the guide — the pilot asked this question
        // from the AR menu and should not have to scroll a five-section document to find the
        // answer. Dismiss first so the dialog is not left behind the guide.
        view.findViewById<TextView>(R.id.arFieldGuideLink).setOnClickListener {
            dialog.dismiss()
            AppLog.v(TAG, "AR menu: opening field guide at the AR section")
            startActivity(FieldGuideActivity.intent(this, FieldGuideActivity.ANCHOR_AR))
        }
    }

    /**
     * SPI AIM CALIBRATION — corrects the bias between where the gimbal reports it is looking
     * and where the lens actually looks (mount tolerance, gimbal wear, a hard landing).
     *
     * NOT the same thing as FOV calibration, and one will not fix the other: an FOV error is
     * invisible at the frame centre and grows toward the edges, while an aim offset moves the
     * CENTRE — which is precisely what a marker drop uses.
     *
     * Why this exists as a pilot-facing control rather than a constant: it was a compile-time
     * `const val` sitting at 0.0, so tuning it cost a rebuild and in practice nobody ever did.
     * The DJI sibling's equivalent bearing offset was flight-tuned to +105; this port's had
     * never been measured, which is the most likely reason its marker accuracy trailed DJI's at
     * shallow look angles. It is airframe property, not a software constant — re-check it after
     * a gimbal strike, a repair, or swapping aircraft.
     *
     * Applied live on every tap so the pilot can hold the crosshair on a known object, watch
     * the SPI/AR marker walk onto it, and stop when it sits right. Steps are deliberately fine
     * (0.25°): at 200ft AGL a QUARTER of a degree is ~1ft of ground error at 54° down but ~80ft
     * at 6°, so a coarse step would be unusable for the shallow angles that actually need this.
     *
     * CALIBRATE SHALLOW. A bias hides at steep angles — if it looks perfect at 50° that proves
     * almost nothing. 25° down is the recommended angle (operator's call after calibrating
     * in flight): shallow enough that a real offset shows, and clear of the red-reticle gate
     * that blocks drops — with no DTED loaded the reticle turns red below 15°, which would
     * stop the pilot part-way through the procedure.
     */
    private fun onAimOffsetsTapped() {
        AppLog.v(TAG, "aim calibration opened")
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        var pitch = TakBridgeHolder.currentPitchOffset
        var bearing = TakBridgeHolder.currentBearingOffset

        val hint = TextView(this).apply {
            textSize = 13f
            setTextColor(androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
        }
        fun refreshHint() {
            // Both directions spelled out for BOTH rows, at the operator's request after using
            // this in flight. A pilot mid-calibration should not have to infer that "−" is the
            // opposite of the one direction the hint happened to name.
            hint.text = "Pitch +  sends the marker FARTHER from the aircraft, −  brings it " +
                "closer.\nBearing +  swings it clockwise, −  swings it counter-clockwise.\n\n" +
                "Aim at a known object with the gimbal 25° DOWN — a bias is nearly " +
                "invisible looking straight down.\n\nFastest with a second TAK device: watch the " +
                "camera point (name ends \"-SPI\") slide onto the target as you adjust.\n\n" +
                "Default is 0.00° / 0.00° (uncalibrated)."
        }
        refreshHint()

        // Built in code rather than XML: two near-identical stepper rows, and a layout file
        // would need its own ids for each without buying any clarity.
        fun stepperRow(label: String, get: () -> Double, set: (Double) -> Unit): android.view.View {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, pad / 2, 0, pad / 2)
            }
            val name = TextView(this).apply {
                text = label; textSize = 16f
                setTextColor(android.graphics.Color.WHITE)
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val value = TextView(this).apply {
                textSize = 18f; minWidth = (90 * resources.displayMetrics.density).toInt()
                gravity = android.view.Gravity.CENTER
                setTextColor(androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_accent))
            }
            fun show() { value.text = "%+.2f°".format(get()) }
            show()
            fun button(text: String, delta: Double) = android.widget.Button(this).apply {
                this.text = text
                setOnClickListener {
                    set(get() + delta)
                    ArSettings.saveAimOffsets(this@FlightActivity, pitch, bearing)
                    // Read back: the holder clamps, so the display must show what was ACCEPTED,
                    // not what was asked for — otherwise the pilot keeps tapping past the limit.
                    pitch = TakBridgeHolder.currentPitchOffset
                    bearing = TakBridgeHolder.currentBearingOffset
                    show()
                }
            }
            row.addView(name)
            row.addView(button("−", -0.25))
            row.addView(value)
            row.addView(button("+", 0.25))
            return row
        }

        root.addView(stepperRow("Pitch offset", { pitch }, { pitch = it }))
        root.addView(stepperRow("Bearing offset", { bearing }, { bearing = it }))
        root.addView(hint)

        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Aim Calibration")
            .setView(root)
            .setPositiveButton("Done", null)
            .setNeutralButton("Reset to 0") { _, _ ->
                ArSettings.resetAimOffsets(this)
                toast("Aim calibration reset")
            }
            .show()
    }

    /**
     * AR field-of-view calibration. The base FOV is published-spec, not measured, and the
     * projection is most sensitive to it at the FRAME EDGES — so the pilot puts a marker on a
     * known object near the edge and adjusts until the icon sits on it, watching it converge
     * live. Adjusts the 1x base; the zoom correction rides on top, so calibrating at 1x fixes
     * every zoom level at once.
     */
    private fun onArCalibrateTapped() {
        AppLog.v(TAG, "AR FOV calibration opened")
        val view = layoutInflater.inflate(R.layout.dialog_ar_fov, null)
        val hValue = view.findViewById<TextView>(R.id.arFovHValue)
        val vValue = view.findViewById<TextView>(R.id.arFovVValue)
        val hint = view.findViewById<TextView>(R.id.arFovHint)

        // One knob. The vertical is derived from this and the live video aspect, and is shown
        // read-only so the pilot can see it track — see ArSettings.loadFov for why it stopped
        // being independently adjustable.
        // Edits the PILOT's value, not the camera's. When the camera is reporting its own FOV
        // that value wins, and the steppers here only change the fallback — the hint says so
        // rather than letting the taps look inert.
        var h = TakBridgeHolder.calibratedHFovBase

        fun apply() {
            ArSettings.saveFov(this, h)
            h = TakBridgeHolder.calibratedHFovBase
            hValue.text = "%.1f°".format(h)
            vValue.text = "%.1f°".format(TakBridgeHolder.vFovFor(h))
            hint.text = when {
                TakBridgeHolder.hasLiveCameraFov ->
                    "Camera is reporting %.1f° × %.1f° — that is being used. This setting is the fallback."
                        .format(TakBridgeHolder.currentHFovBase, TakBridgeHolder.currentVFovBase)
                TakBridgeHolder.currentZoomFactor > 1.0 ->
                    "Effective at %.1fx zoom: %.1f° × %.1f°".format(
                        TakBridgeHolder.currentZoomFactor,
                        AutelTakBridge.hFovDeg(TakBridgeHolder.currentZoomFactor),
                        AutelTakBridge.vFovDeg(TakBridgeHolder.currentZoomFactor),
                    )
                else -> "Marker too far OUT from centre → reduce. Too far IN → increase."
            }
        }
        apply()

        view.findViewById<android.widget.Button>(R.id.arFovHMinus).setOnClickListener { h -= FOV_STEP_DEG; apply() }
        view.findViewById<android.widget.Button>(R.id.arFovHPlus).setOnClickListener { h += FOV_STEP_DEG; apply() }

        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Calibrate AR field of view")
            .setView(view)
            .setPositiveButton("Done", null)
            .setNeutralButton("Reset") { _, _ ->
                ArSettings.resetFov(this)
                toast("FOV reset to published specs")
            }
            .show()
    }

    /** AR pill on/off state: green pill when running (the colour this app already means
     *  "on/good" with), plain dimmed pill when off. Size unchanged between states — a control
     *  that grows on tap reads as a different control. */
    private fun refreshArButton() {
        val on = arOverlay.isRunning
        arButton.alpha = if (on) 1f else 0.45f
        arButton.setBackgroundResource(
            if (on) R.drawable.bg_ar_pill_active else R.drawable.bg_zoom_pill
        )
        arButton.setTextColor(
            if (on) androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_go) else Color.WHITE
        )
    }

    /**
     * The quick marker — place it, or move it if it already exists. ONE action, SHORT press only.
     *
     * THE SPLIT THAT WAS REMOVED. Short-press-places / long-press-re-aims used to be here and was
     * deleted at the operator's call after flying it. [TakDropMarkers.QUICK_NAME] is a SINGLETON,
     * thus "place" and "move" are the same intent — "the marker belongs where I look" — and the
     * only result of that split was a scolding toast when the pilot used the wrong gesture on a
     * marker that already existed.
     *
     * THE SPLIT THAT REPLACED IT IS NOT THE SAME SPLIT, and this note is here so that nobody
     * removes the new one for the old reason. The old split gave ONE marker two verbs. The new
     * one gives two different KINDS of marker one verb each:
     *   SHORT — this function. The one quick marker, re-aimed at whatever the camera looks at.
     *   LONG  — [onUnknownMarkerAction]. A NEW stationary Unknown marker, numbered, that stays.
     * The old wrong-gesture scolding cannot come back, because neither gesture refuses. They give
     * different results, and both are useful.
     *
     * Both controls reach both functions: the crosshair and the controller's C2 button each send
     * their short press here and their long press there. Same convention as the C1 button and the
     * zoom pill — one shared function for each action, never one copy for the touch route and
     * another for the hardware route.
     *
     * @param source only for the log, so a press can be traced back to the control that sent it.
     */
    private fun onQuickMarkerAction(source: String) {
        AppLog.v(TAG, "$source: quick marker")
        if (aimTooPoorToDrop()) { refuseDropForAim(); return }
        val look = TakBridgeHolder.lookPoint()
        if (look == null) {
            AppLog.w(TAG, "quick marker refused — no look point (GPS/gimbal not ready)")
            toast("Cannot place the marker yet. Wait for GPS and the gimbal.")
            return
        }
        val (lat, lon, elev) = look
        // Move first: moveQuick keeps the uid, so the marker slides in place on every other TAK
        // client rather than the team seeing a delete and a new contact.
        if (TakDropMarkers.moveQuick(lat, lon, elev)) {
            showNotice("${TakDropMarkers.QUICK_NAME} re-aimed")
        } else if (TakDropMarkers.placeQuick(lat, lon, elev)) {
            showNotice("${TakDropMarkers.QUICK_NAME} dropped")
        }
    }

    /**
     * Drop a NEW stationary marker of the type Unknown at the look point, and send it immediately.
     *
     * This is a SHORTCUT for the marker button plus "Unknown" in the type list, and nothing more.
     * The same name from the same shared counter ("<callsign>-NN"), the same entry in the markers
     * list, the same 60-second re-broadcast that catches a teammate whose ATAK connects late. It
     * is NOT the quick marker: it stays where it is put, and a second long press makes a second
     * marker.
     *
     * It sends with no Send / Do not Send question (operator, 2026-08-13). This gesture is for the
     * moment when the pilot cannot give a dialog any attention. The marker button keeps the
     * question, because there the pilot has already stopped to choose.
     *
     * The same gate as each other drop route: an aim that the marker button refuses must be
     * refused here too, or the controller button becomes a quiet way around it.
     *
     * @param source only for the log, so a press can be traced back to the control that sent it.
     */
    private fun onUnknownMarkerAction(source: String) {
        AppLog.v(TAG, "$source: Unknown marker")
        if (aimTooPoorToDrop()) { refuseDropForAim(); return }
        // Read the look-point HERE. The aircraft keeps flying, thus a position captured when the
        // press started is already old.
        val look = TakBridgeHolder.lookPoint()
        if (look == null) {
            AppLog.w(TAG, "Unknown marker refused — no look point (GPS/gimbal not ready)")
            toast("Cannot place the marker yet. Wait for GPS and the gimbal.")
            return
        }
        val (lat, lon, elev) = look
        val name = TakDropMarkers.placeAt(
            TakDropMarkers.Affiliation.UNKNOWN, lat, lon, elev, autoSend = true)
        // The notice reports the DROP and the toast from sendPin reports the SEND. Both are shown
        // on purpose: the send can fail on its own ("not connected to TAK"), and one message for
        // the two facts would hide the second.
        showNotice("$name dropped")
    }

    /**
     * Video re-sync — rebuilds the on-screen decode from scratch. The SDK offers no
     * request-keyframe hook (DJI's requestResync asks the encoder for an IDR); the contained
     * equivalent here is tearing the codec view down and constructing a fresh one, which
     * restarts the decode session and picks up clean at the next keyframe. Expect a brief
     * black gap, same as the blueprint's hard resync. Only affects the local picture — the
     * RTSP push reads its own frame tap and is untouched.
     */
    /**
     * Makes the FPV video fill the screen in the VISIBLE-LIGHT modes, aspect preserved, centred.
     * Thermal is the exception and is shown whole — see the scale decision in [applyVideoFill].
     *
     * THE PROBLEM THIS SOLVES. The camera emits a different shape per mode — photo 1280x960
     * (4:3), video 1280x720 (16:9), IR 640x512 (5:4) — and the SDK fits each one INSIDE the
     * widget, so the picture changed shape and grew black bars whenever the pilot took a photo,
     * started recording or switched to thermal. Those bars are not cosmetic: the TAK feed is a
     * screen capture, so the whole team saw them too. A pilot should not have to track aspect
     * ratios; the picture should just fill the screen and stay put.
     *
     * HOW. AutelCodecView is a TextureView underneath, so setTransform() can scale what the SDK
     * already rendered — no SDK internals touched, no second surface, no re-encode. The SDK fits
     * content CENTRED (verified from its own renderPos trace: 16:9 -> x:0 y:120, 4:3 -> x:96 y:0
     * — symmetric bars either way), so a centre-anchored uniform scale crops the bars off exactly
     * and leaves the image's true centre dead on the view's centre.
     *
     * The scale is just the ratio of the two aspects, whichever way round they sit:
     *
     *     content wider than view  -> letterboxed -> scale = contentAspect / viewAspect
     *     content taller than view -> pillarboxed -> scale = viewAspect / contentAspect
     *
     * which collapses to max(a/b, b/a). At most one of those is > 1, and the other case is the
     * reciprocal, so the same expression covers all three camera modes and anything Autel adds.
     *
     * WHY THE CENTRE MATTERS MORE THAN THE EDGES. [CrosshairView] is match_parent over the same
     * rect, so the reticle sits at the view centre. Because the scale is anchored at that exact
     * point, the optical centre stays under the reticle in every mode — which is what the
     * crosshair PROMISES, since it is the aiming reference for marker drops. Losing the edges to
     * the crop is an accepted trade (the pilot's call); losing the centre would silently corrupt
     * every marker placed through it.
     *
     * Re-applied on every frame-size change AND every layout change, because either can happen
     * without the other: the camera switches mode without the view resizing, and the map toggle
     * resizes the view without the camera changing.
     */
    private fun armVideoFill(view: AutelCodecView) {
        AutelCodecView.setOnRenderFrameInfoListener(object : com.autel.common.video.OnRenderFrameInfoListener {
            override fun onRenderFrameSizeChanged(w: Int, h: Int) {
                // Source or already-fitted dimensions — either is fine, we only use the RATIO,
                // and the SDK's fit preserves it.
                if (w <= 0 || h <= 0) return
                val aspect = w.toFloat() / h
                if (kotlin.math.abs(aspect - videoAspect) < 0.001f) return   // no real change
                videoAspect = aspect
                AppLog.i(TAG, "video frame size ${w}x$h (aspect ${"%.3f".format(aspect)}) — refilling")
                // The FOV model needs the frame's shape too: the vertical FOV is derived from the
                // horizontal and THIS aspect, so a camera mode change (16:9 video -> 4:3 photo ->
                // 5:4 IR) re-derives it instead of needing a per-mode recalibration.
                TakBridgeHolder.setVideoAspect(aspect.toDouble())
                runOnUiThread { applyVideoFill(view) }
            }
            override fun onRenderFrameTimestamp(ts: Long) { /* not used */ }
        })
        view.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) applyVideoFill(view)
        }
    }

    /** Centre-anchored scale that turns the SDK's fit-inside into a fill. See [armVideoFill]. */
    private fun applyVideoFill(view: AutelCodecView) {
        val vw = view.width.toFloat()
        val vh = view.height.toFloat()
        // Before first layout, or before any frame has arrived, leave the view alone — an
        // identity transform is the SDK's own behaviour and a safe resting state.
        if (vw <= 0f || vh <= 0f || videoAspect <= 0f) return
        val viewAspect = vw / vh
        // FILL for the visible-light modes, FIT for thermal (operator, 2026-08-30).
        //
        // The thermal sensor is 640x512 and there is no more of it to be had. Filling a
        // 2048x1536 view with it means a 1.067 magnification and, measured on the controller,
        // an AR rect of 2048x1638 — thus 102 rows of a 512-row sensor never reach the screen.
        // Giving up about 6% of a scarce sensor to remove a black bar is the wrong trade, so
        // thermal is shown whole and the view is filled at the sides with the flight screen's
        // own black.
        //
        // The visible-light modes are NOT changed. Their crop is far larger — 16:9 in a 4:3
        // view magnifies by 1.333 and cuts 25% off the sides — but that camera has resolution
        // to spare, the picture fills the screen, and the TAK feed is a capture of this screen,
        // thus a change here changes what the whole team sees. That is a separate decision and
        // it has not been made.
        //
        // A scale of exactly 1 is the SDK's own behaviour: it already fits content INSIDE the
        // widget and centres it, so the identity transform IS the fit, and the bars are the
        // root layout's tp_bg_flight (#000000) showing through the TextureView.
        val scale = if (irOn) 1f
            else maxOf(videoAspect / viewAspect, viewAspect / videoAspect)
        view.setTransform(android.graphics.Matrix().apply {
            setScale(scale, scale, vw / 2f, vh / 2f)   // anchor = view centre = reticle centre
        })
        view.invalidate()

        // TELL THE AR OVERLAY WHERE THE FULL VIDEO FRAME NOW LIVES.
        //
        // This was a real bug and it was mine: ArOverlayView projects angles onto its videoRect,
        // which defaults to the whole view — correct only while the video fills the view 1:1.
        // The centre-crop above MAGNIFIES the image by `scale`, so every marker was drawn too
        // close to the centre, with the error growing linearly outward. It looked fine near the
        // crosshair and badly wrong at the edges, which is why it showed up as soon as the gimbal
        // was pitched up and contacts sat near the frame edge.
        //
        // The rect handed over is where the WHOLE video frame would be if nothing were cropped:
        // fit-inside dimensions times the same scale, centred. Its overflow beyond the view is
        // the point — a target that has been cropped off screen then projects outside the view
        // bounds and is correctly treated as off-frame, instead of being squeezed back inside.
        val fitW: Float
        val fitH: Float
        if (videoAspect > viewAspect) { fitW = vw; fitH = vw / videoAspect }
        else { fitH = vh; fitW = vh * videoAspect }
        val fullW = fitW * scale
        val fullH = fitH * scale
        arOverlay.setVideoRect(android.graphics.RectF(
            vw / 2f - fullW / 2f, vh / 2f - fullH / 2f,
            vw / 2f + fullW / 2f, vh / 2f + fullH / 2f))
        AppLog.i(TAG, "AR video rect: ${fullW.toInt()}x${fullH.toInt()} in view ${vw.toInt()}x${vh.toInt()}")
        AppLog.i(TAG, "video ${if (irOn) "FIT (thermal)" else "FILL"}: " +
            "view ${vw.toInt()}x${vh.toInt()} (aspect ${"%.3f".format(viewAspect)}) " +
            "content aspect ${"%.3f".format(videoAspect)} -> scale ${"%.3f".format(scale)}")
    }

    private fun resyncVideo() {
        val container = findViewById<FrameLayout>(R.id.videoContainer)
        codecView?.let { container.removeView(it) }
        runCatching { AutelCodecView.stopCodec() }
            .onFailure { AppLog.w(TAG, "stopCodec during resync: ${it.message}") }
        codecView = AutelCodecView(this).also { container.addView(it); armVideoFill(it) }
        AppLog.i(TAG, "video resync: codec view rebuilt")
    }

    /**
     * Switches the FPV between the visible camera and the 640T's thermal sensor.
     *
     * `setDisplayMode` also offers PICTURE_IN_PICTURE and OVERLAP; this button is deliberately
     * a two-way VISIBLE/IR toggle (operator's spec). The other modes are a settings-screen
     * question, not something to cycle through blind in flight.
     *
     * State flips only in the success callback. If the camera rejects the change the buttons
     * keep showing what the camera is actually doing, rather than what we asked for.
     */
    private fun onIrTapped() {
        val cam = AutelProductHolder.xt706
        if (cam == null) {
            AppLog.w(TAG, "IR ignored — camera not connected (or not an XT70x)")
            toast("The camera is not connected.")
            return
        }
        val target = if (irOn) DisplayMode.VISIBLE else DisplayMode.IR
        cam.setDisplayMode(target, camCb("setDisplayMode($target)") {
            irOn = !irOn
            refreshIrButtons()
            AppLog.i(TAG, "display mode now ${if (irOn) "IR" else "VISIBLE"}")
        })
    }

    /** White hot → black hot → ironbow, then round again. Only reachable while [irOn] — the
     *  button is hidden otherwise. A palette outside the cycle (Explorer's leftovers) enters
     *  at WHITE HOT: indexOf returns -1, and -1 + 1 is index 0. */
    private fun onIrPaletteTapped() {
        val cam = AutelProductHolder.xt706 ?: return
        val target = irPaletteCycle[(irPaletteCycle.indexOf(irPalette) + 1) % irPaletteCycle.size]
        cam.setIrColor(target, camCb("setIrColor($target)") {
            irPalette = target
            refreshIrButtons()
            AppLog.i(TAG, "IR palette now $target")
        })
    }

    /** IR button highlighted when thermal is live; palette button shown only then. */
    private fun refreshIrButtons() {
        irButton.setBackgroundResource(
            if (irOn) R.drawable.bg_ar_pill_active else R.drawable.bg_zoom_pill
        )
        irButton.setTextColor(if (irOn) androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_go) else Color.WHITE)
        irPaletteButton.visibility = if (irOn) View.VISIBLE else View.GONE
        // Spelled out, not "WHOT"/"BHOT": in the HUD column it has the full column width, and
        // the abbreviations only existed to fit a narrow toolbar pill.
        irPaletteButton.text = when (irPalette) {
            IrColor.WhiteHot -> "WHITE HOT"
            IrColor.BlackHot -> "BLACK HOT"
            IrColor.IronBow -> "IRONBOW"
            // Not in the cycle (Explorer left the camera in Lava, Arctic, …): show the truth
            // rather than a wrong cycle label. Next tap moves to WHITE HOT.
            else -> irPalette.name.uppercase()
        }
    }

    /**
     * Reads the camera's ACTUAL display mode and palette so the buttons start truthful.
     *
     * Without this the toggle would assume VISIBLE/WhiteHot at every launch, and a camera left
     * in thermal by Autel's own app — or by a previous flight — would show an unlit IR button
     * over a thermal picture, with the first tap switching it to visible while the label
     * implied the opposite.
     */
    private fun syncIrStateFromCamera() {
        val cam = AutelProductHolder.xt706 ?: return
        // getDisplayMode / getIrColor / getISO / getShutter / getMediaMode are ONE-SHOT — the
        // XT706 impl routes them through CameraHttpRequest (an HTTP GET returns once). Verified in
        // the aar 2026-08-03; none is a repeating subscription, so calling them on demand is safe.
        cam.getDisplayMode(object : CallbackWithOneParam<DisplayMode> {
            override fun onSuccess(mode: DisplayMode?) {
                irOn = mode == DisplayMode.IR
                runOnUiThread { refreshIrButtons() }
                AppLog.i(TAG, "camera display mode at connect: $mode")
            }
            override fun onFailure(error: AutelError?) {
                AppLog.w(TAG, "getDisplayMode failed: ${error?.description}")
            }
        })
        cam.getIrColor(object : CallbackWithOneParam<IrColor> {
            override fun onSuccess(c: IrColor?) {
                irPalette = c ?: IrColor.WhiteHot
                runOnUiThread { refreshIrButtons() }
            }
            override fun onFailure(error: AutelError?) {
                AppLog.w(TAG, "getIrColor failed: ${error?.description}")
            }
        })
    }

    private fun applyZoom(level: Int) {
        val cam = AutelProductHolder.xt706
        if (cam == null) {
            AppLog.w(TAG, "zoom ${level}X ignored — camera not connected (or not an XT70x)")
            toast("The camera is not connected.")
            return
        }
        // ABSOLUTE, NOT RELATIVE TO A LEARNED BASELINE.
        //
        // The raw units are hundredths — 100 = 1.0x. Established 2026-08-04 from the camera's own
        // status push, where focalLength tracks zoomScale linearly: 100/0.47, 400/1.90, 800/3.79,
        // 1600/7.58. So "4X" is simply 400 and needs no baseline at all.
        //
        // ⚠ WHY THE OLD RELATIVE FORM WAS A BUG, not just a roundabout way to the same answer.
        // It multiplied a value read once at connect (zoomBaseRaw), on the assumption the camera
        // always connects at 1x. Reconnect while the camera is still zoomed and that assumption
        // breaks silently: the app banks the zoomed value as "1X" and every level is then off by
        // that factor. Observed for real — the app reattached at a true 4x, labelled it 1X, and
        // could no longer zoom out; "1X" returned to 4x and "4X" drove the camera to 16x. An
        // absolute scale has no baseline to capture wrong.
        applyZoomRaw(level * ZOOM_RAW_PER_X)
    }

    /**
     * Drives the camera to an absolute raw zoom and repaints the pill. The one place zoom is
     * written, whichever control asked — the pill or the zoom rocker.
     *
     * Clamped HERE because the SDK does not: `setDigitalZoomScale` passes the int straight to the
     * camera with no range check of its own (verified in the aar), so an unclamped accumulator
     * would happily send nonsense. The bounds are this project's own measured ones — 100 = 1x
     * through 1600 = 16x, from the focal-length/zoomScale calibration in [applyZoom].
     */
    private fun applyZoomRaw(rawTarget: Int) {
        val cam = AutelProductHolder.xt706 ?: return
        val target = rawTarget.coerceIn(ZOOM_RAW_MIN, ZOOM_RAW_MAX)
        // Stamped for seedZoomFromCamera's settle guard: for the next ZOOM_SEED_SETTLE_MS the
        // camera's own ~2Hz reading may still describe the zoom from BEFORE this write.
        lastZoomWriteMs = SystemClock.elapsedRealtime()
        // Record the intent SYNCHRONOUSLY, before the round-trip. This is what the ack below
        // checks itself against, and it is also where the rocker resumes from — so a pill tap
        // mid-flight leaves the rocker continuing from the value the pilot just
        // chose, not from wherever it had drifted to.
        pendingZoomRaw = target
        cam.setDigitalZoomScale(target, camCb("setDigitalZoomScale($target)") {
            // ⚠ A STALE ACK MUST NOT DRAG THE PILL BACKWARDS. The camera answers a round-trip
            // later, by which time a held rocker has already moved the accumulator on. Writing
            // this (older) target back unconditionally made the label and the AR zoom jump
            // backward mid-hold, then jump forward again on the next push — a visible stutter
            // on the one control whose whole purpose is smoothness. So the write-back happens
            // only when this ack IS still the latest intent.
            if (pendingZoomRaw != target) {
                AppLog.v(TAG, "zoom ack for $target superseded by $pendingZoomRaw — not repainting")
                return@camCb
            }
            zoomRaw = target
            zoomButton.text = zoomLabel(target)
            // The published FOV cone and the AR projection both narrow with zoom, so they must be
            // told — otherwise AR markers drift as the pilot zooms. The camera's status push also
            // reports zoomScale and corrects this within a tick; setting it here just avoids a
            // visible lag between the tap and the overlay catching up.
            TakBridgeHolder.setLiveZoom(target / ZOOM_RAW_PER_X.toDouble())
            AppLog.i(TAG, "zoom now ${zoomLabel(target)} (raw=$target)")
        })
    }

    /** "2X" for whole steps, "2.4X" for anything the rocker lands on between them. A pill reading
     *  "2X" while the camera sits at 2.4x would be the kind of small lie this HUD does not tell. */
    private fun zoomLabel(raw: Int): String {
        val x = raw / ZOOM_RAW_PER_X.toDouble()
        return if (raw % ZOOM_RAW_PER_X == 0) "${x.toInt()}X" else String.format("%.1fX", x)
    }

    /**
     * The right zoom rocker was deflected: +1 zooms in (tighter), -1 zooms out (wider).
     *
     * ⚠ THE ROCKER IS SPRING-LOADED, NOT A FREE-SPINNING DETENTED WHEEL (operator,
     * 2026-08-06). It is pushed against a stop and held there; the controller then REPEATS the
     * same event about every 200ms. Confirmed in the hardware capture: 8 × ZOOM_IN at 199-213ms
     * spacing, then 12 × ZOOM_OUT at the same cadence. So each event is a TICK OF HELD TIME,
     * not a countable detent, and the code below counts ticks because that is the only clock
     * this control has.
     *
     * EVERY TICK THAT REACHES THIS FUNCTION IS A HELD ONE. The rocker goes on emitting while
     * the spring returns it to centre, and for a while this function received those too and ran
     * the zoom on past where the pilot let go — 1.8x at worst, three trailing ticks. Those
     * events carry keyValue 0 and are dropped in installHardwareButtonListener, which is the
     * only place that may read that field. Do not re-add release detection here.
     *
     * FIXED LEVELS, NOT A CONTINUOUS RATE (operator, 2026-08-15). One press moves ONE level
     * along [ZoomLadder]; holding walks the levels and stops on whichever one it reaches. This
     * REPLACES the continuous accumulator — 0.6x a tick, with soft detents parking at 2x, 4x
     * and 8x — which was flown and judged the wrong model: a pilot frames a subject at a few
     * known magnifications and does not want the values between them. The detents existed only
     * to make a continuous rate usable, thus they went with it.
     *
     * Press and hold are told apart the way a keyboard does it: one level immediately, then
     * [ZOOM_LADDER_REPEAT_DELAY_TICKS] before the walk starts, then one level every
     * [ZOOM_LADDER_REPEAT_EVERY_TICKS]. An ordinary press is several ticks long, so without the
     * delay it would run up several levels before the pilot could let go.
     *
     * A new press — silence longer than [ZOOM_RELEASE_GAP_MS], or a reversal — RE-SEEDS from
     * the camera before it steps. See [seedZoomFromCamera] for why that matters to a ladder in
     * a way it never did to an accumulator.
     */
    private fun onZoomRocker(direction: Int) {
        if (AutelProductHolder.xt706 == null) return

        val now = SystemClock.elapsedRealtime()
        val newPress = now - lastZoomTickMs > ZOOM_RELEASE_GAP_MS ||
            direction != lastZoomDirection
        lastZoomTickMs = now
        lastZoomDirection = direction

        if (newPress) {
            // START OF A PRESS: take the camera's own zoom before deciding where "one level
            // up" goes. Nothing is in flight here — a new press follows at least
            // ZOOM_RELEASE_GAP_MS of silence and the push throttle is shorter than that — so
            // the reading is settled, and every press re-anchors the app to the aircraft
            // rather than to whatever it last believed.
            seedZoomFromCamera()
            zoomHoldTicks = 0
            stepZoomOneLevel(direction)
            return
        }

        // HELD. Wait out the repeat delay, then move one level per repeat period. This is a
        // keyboard's typematic behaviour, and it is here for the same reason: without the
        // delay, an ordinary press — which is several ticks long — would run up several levels
        // before the pilot could let go.
        zoomHoldTicks++
        if (zoomHoldTicks < ZOOM_LADDER_REPEAT_DELAY_TICKS) return
        if ((zoomHoldTicks - ZOOM_LADDER_REPEAT_DELAY_TICKS) % ZOOM_LADDER_REPEAT_EVERY_TICKS != 0)
            return
        stepZoomOneLevel(direction)
    }

    /**
     * Moves the zoom one level along [ZoomLadder], in [direction], and paints the result.
     *
     * The pill and the AR overlay follow IMMEDIATELY, ahead of the camera acknowledging, while
     * the camera itself is written on a [ZOOM_PUSH_MS] throttle. A held rocker walking the
     * ladder must not become a queue of commands the camera answers long after the pilot let
     * go; only the newest target matters, thus coalescing loses nothing.
     */
    private fun stepZoomOneLevel(direction: Int) {
        val next = ZoomLadder.next(pendingZoomRaw, direction)
            .coerceIn(ZOOM_RAW_MIN, ZOOM_RAW_MAX)
        if (next == pendingZoomRaw) return          // already against the end of the ladder
        pendingZoomRaw = next
        zoomRaw = next
        zoomButton.text = zoomLabel(next)
        TakBridgeHolder.setLiveZoom(next / ZOOM_RAW_PER_X.toDouble())
        if (!zoomPushScheduled) {
            zoomPushScheduled = true
            handler.postDelayed({
                zoomPushScheduled = false
                applyZoomRaw(pendingZoomRaw)
            }, ZOOM_PUSH_MS)
        }
    }

    /**
     * Points the app's idea of the zoom at what the CAMERA actually holds.
     *
     * [TakBridgeHolder.currentZoomFactor] is an absolute ratio off the camera's own ~2Hz status
     * push (AutelProductHolder), thus it needs NO new SDK listener — safety rule 2, listener
     * slots hold one client, and this screen must not take one.
     *
     * Why it matters more now than it did: with a continuous rocker, starting from a wrong
     * value only meant a wrong label, and the pilot zoomed until the picture looked right. With
     * a ladder, the wrong starting value sends "one level up" to the wrong level. Autel
     * Explorer leaves the camera zoomed, and on 2026-08-04 an app that assumed 1x at connect
     * called a true 4x "1X" and could not zoom out at all.
     *
     * ⚠ IT DEFERS TO OUR OWN VALUE WHILE A WRITE IS STILL SETTLING. The camera's reading is a
     * ~2Hz push, thus it lags a zoom this app just wrote by up to half a second. Without the
     * [ZOOM_SEED_SETTLE_MS] guard, tapping twice in quick succession would re-seed the second
     * press from the pre-first-press value and send the pilot to the SAME level twice — the
     * control would appear stuck exactly when it is being used the way a ladder invites, which
     * is a rapid tap. The guard costs nothing: the case this function exists for is a camera
     * some OTHER app left zoomed, and that is never within a second of our own write.
     *
     * Silently does nothing if the reading is not usable — before the bridge has ever run it is
     * the 1.0 default, which is also the honest answer when there is nothing better.
     */
    private fun seedZoomFromCamera() {
        if (SystemClock.elapsedRealtime() - lastZoomWriteMs < ZOOM_SEED_SETTLE_MS) return
        val ratio = TakBridgeHolder.currentZoomFactor
        if (!ratio.isFinite() || ratio < 1.0) return
        val raw = Math.round(ratio * ZOOM_RAW_PER_X).toInt().coerceIn(ZOOM_RAW_MIN, ZOOM_RAW_MAX)
        if (raw == pendingZoomRaw) return
        AppLog.i(TAG, "zoom re-seeded from the camera: ${zoomLabel(raw)} (raw=$raw)")
        pendingZoomRaw = raw
        zoomRaw = raw
        zoomButton.text = zoomLabel(raw)
    }

    /**
     * The TAK channels, from the flight screen.
     *
     * A pilot must be able to change the scope of this aircraft IN FLIGHT. Pre-Flight can do it,
     * but going there stops the video to the team, which is the wrong thing to do in the middle
     * of a sortie.
     *
     * THE SERVER HOLDS THE STATE. This screen reads it, writes to it and follows it. It is not
     * the control — an administrator can change the same thing from TAK Portal, and the rows
     * follow that within about a second because of the t-x-g-c listener below.
     *
     * LOCKED BY THE TAK CONFIGURATION LOCK. When the lock is on, the rows show the channels and
     * refuse a change. Reading is never locked: a pilot must always be able to SEE the scope of
     * the aircraft, and the lock exists to stop an accidental change and not to hide the truth
     * (operator, 2026-08-16).
     */
    /**
     * True when the pilot has entered the unlock password on THIS visit to the flight screen.
     *
     * SESSION ONLY — it is never written to the preferences. The pilot wants to change a channel
     * in flight, not to leave the TAK configuration unlocked after they land. Pre-Flight keeps
     * its own lock, and this does not touch it. Leaving the flight screen clears this.
     */
    private var takChannelsUnlockedThisSession = false

    /**
     * Asks for the unlock password, in the flight dialog.
     *
     * Going to Pre-Flight to unlock defeats the point of a control on the flight screen
     * (operator, 2026-08-16). The password is Pre-Flight's own constant, not a copy of it —
     * one password and one idea of "locked", with no second string to drift.
     *
     * A wrong password and Cancel do the same thing, the same as Pre-Flight: the only way out
     * with the rows editable is the right password.
     */
    private fun promptChannelUnlock(onUnlocked: () -> Unit) {
        // STYLED EXACTLY AS PRE-FLIGHT'S UNLOCK FIELD. A programmatic EditText takes the
        // PLATFORM's colours and no background at all, thus the first version was a bare line
        // of text across the full width of the dialog — hard to see and hard to hit. The
        // background and the padding are what make it look like a field, and the wrapper is
        // what keeps it off the edges. Do not simplify either away.
        val field = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Password"
            textSize = 15f
            setTextColor(androidx.core.content.ContextCompat.getColor(
                applicationContext, R.color.tp_text_primary))
            setHintTextColor(androidx.core.content.ContextCompat.getColor(
                applicationContext, R.color.tp_text_hint))
            setBackgroundResource(R.drawable.bg_dialog_field)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val wrap = android.widget.FrameLayout(this).apply {
            val padH = (16 * resources.displayMetrics.density).toInt()
            val padV = (8 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            addView(field)
        }
        // Destructive theme, as Pre-Flight's unlock uses: getting this wrong changes who sees
        // the aircraft.
        AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            // No body text. It is a password prompt, and the pilot already knows what they
            // touched. The first version explained the session rule here, which is a thing to
            // read in the air and not a thing to decide.
            .setTitle("Unlock channels")
            .setView(wrap)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Unlock") { _, _ ->
                if (field.text.toString() == TakConnectActivity.UNLOCK_PASSWORD) {
                    takChannelsUnlockedThisSession = true
                    AppLog.i(TAG, "channels unlocked for this flight-screen session")
                    onUnlocked()
                } else {
                    AppLog.w(TAG, "channel unlock refused — wrong password")
                    showNotice("The password is wrong.", refused = true)
                }
            }
            .show()
    }

    private fun onTakChannelsTapped() {
        if (!TakManager.getInstance().isConnected) {
            showNotice("TAK is not connected. The channels are on the server.", refused = true)
            return
        }
        val themed = android.view.ContextThemeWrapper(this, R.style.TakDialogTheme)
        val view = android.view.LayoutInflater.from(themed)
            .inflate(R.layout.dialog_tak_channels, null)
        val list = view.findViewById<android.widget.LinearLayout>(R.id.takChanList)
        val status = view.findViewById<TextView>(R.id.takChanStatus)
        val configLocked = getSharedPreferences(TakConnectActivity.PREFS, MODE_PRIVATE)
            .getBoolean(TakConnectActivity.KEY_TAK_LOCKED, false)
        // The session unlock is what the pilot just typed; the pref is what Pre-Flight holds.
        var locked = configLocked && !takChannelsUnlockedThisSession
        val lockedNote = view.findViewById<TextView>(R.id.takChanLocked)

        var channels: List<com.taklite.client.tak.TakMissionClient.Channel> = emptyList()
        var painting = false

        fun paint(chans: List<com.taklite.client.tak.TakMissionClient.Channel>) {
            channels = chans
            painting = true
            list.removeAllViews()
            if (chans.isEmpty()) {
                // Channels turned off on this server. Show nothing to change — a write to such
                // a server is reported to cause real trouble on it.
                status.text = "This server has no channels."
                painting = false
                return
            }
            for (ch in chans) {
                val row = android.widget.CheckBox(themed).apply {
                    // Two-way is the normal case and gets no label — a note on every row is
                    // noise, and the exception is what a pilot needs to see (operator,
                    // 2026-08-16).
                    text = when {
                        ch.canSend && ch.canReceive -> ch.name
                        ch.canReceive -> "${ch.name} - Rx Only"
                        ch.canSend -> "${ch.name} - Tx Only"
                        else -> "${ch.name} - no direction"
                    }
                    // ⚠ LOCKED IS NOT DISABLED — see the same note in TakConnectActivity.
                    // Disabling greys the tick, and the tick is what the pilot came to read.
                    setTextColor(androidx.core.content.ContextCompat.getColor(
                        applicationContext,
                        if (locked) R.color.tp_text_secondary else R.color.tp_text_primary))
                    isChecked = ch.active
                    isClickable = !locked
                    isFocusable = !locked
                    buttonTintList = android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(
                            applicationContext, R.color.tp_accent))
                    setOnCheckedChangeListener { _, checked ->
                        if (painting) return@setOnCheckedChangeListener
                        ch.active = checked
                        val bits = channels.filter { it.active && it.bitpos >= 0 }.map { it.bitpos }
                        status.text = "Sending ${bits.size} active channel(s)…"
                        // The COMPLETE set every time — activebits is absolute.
                        TakMissionManager.setActiveChannels(bits) { ok ->
                            status.text = if (ok) "The server has ${bits.size} active channel(s)."
                                          else "The server refused the change."
                        }
                    }
                }
                list.addView(row)
            }
            painting = false
        }

        fun reload() = TakMissionManager.listChannels { paint(it) }
        lockedNote.visibility = if (locked) View.VISIBLE else View.GONE
        reload()

        // Follow the server while the dialog is open, and stop when it closes.
        val onGroups = TakManager.GroupChangeListener {
            AppLog.i(TAG, "channels changed on the server — re-reading (flight screen)")
            reload()
        }
        TakManager.getInstance().addGroupChangeListener(onGroups)

        val dialog = AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("TAK Channels")
            .setView(view)
            .setNegativeButton("Close", null)
            // Unlock HERE. Going to Pre-Flight for it defeats the point of this dialog.
            .apply { if (locked) setNeutralButton("Unlock…", null) }
            .setOnDismissListener {
                TakManager.getInstance().removeGroupChangeListener(onGroups)
            }
            .create()
        dialog.show()
        // Set after show() so the dialog does NOT close when the password prompt opens over it.
        if (locked) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                promptChannelUnlock {
                    locked = false
                    lockedNote.visibility = View.GONE
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility = View.GONE
                    paint(channels)     // repaint the rows, now editable
                }
            }
        }
    }

    /** Log + toast-on-failure adapter for the camera's completion callbacks. */
    private fun camCb(opName: String, onOk: (() -> Unit)? = null) = object : CallbackWithNoParam {
        override fun onSuccess() {
            AppLog.i(TAG, "$opName: OK")
            onOk?.let { runOnUiThread { it() } }
        }
        override fun onFailure(error: AutelError?) {
            AppLog.w(TAG, "$opName failed: ${error?.description}")
            runOnUiThread { toast("$opName failed: ${error?.description ?: "unknown error"}") }
        }
    }

    /**
     * REC — start/stop recording to the aircraft's SD card. The pill's shown state is driven by
     * the camera's own MediaStatus push events (via [AutelProductHolder.isRecording]), not by
     * which button was pressed last — see updateHud().
     *
     * Recording needs the camera in VIDEO media mode; mirrored from the blueprint's mode dance
     * (DJI needed a flat-mode switch first for the same reason). Mode checked per press rather
     * than assumed, since a photo fallback (see [onShootPhotoTapped]) may have left it changed.
     */
    private fun onRecordToggleTapped() {
        val cam = AutelProductHolder.camera
        if (cam == null) {
            AppLog.w(TAG, "REC ignored — camera not connected")
            toast("The camera is not connected.")
            return
        }
        if (AutelProductHolder.isRecording) {
            // Nothing to restore: VIDEO is the resting mode, so stopping leaves the camera
            // exactly where it belongs and the picture does not move. See [onShootPhotoTapped].
            cam.stopRecordVideo(camCb("stopRecordVideo"))
            return
        }
        cam.getMediaMode(object : com.autel.common.CallbackWithOneParam<MediaMode> {
            override fun onSuccess(mode: MediaMode?) {
                AppLog.i(TAG, "media mode before record: $mode")
                if (mode == MediaMode.VIDEO) {
                    startRecordVerified(cam)
                } else {
                    cam.setMediaMode(MediaMode.VIDEO, camCb("setMediaMode(VIDEO)") {
                        // SETTLE DELAY — NOT superstition, measured on hardware 2026-08-01.
                        // Firing startRecordVideo straight out of this callback (which is what
                        // this code used to do, 2ms after the mode ack) gets StartRecording
                        // answered with status 0 and then SILENTLY IGNORED: no RECORD_START
                        // event, no file on the card. The camera acknowledges the mode change
                        // before it can actually act on it. With no mode switch needed,
                        // RECORD_START comes back in 1ms — so the delay is only needed here.
                        handler.postDelayed({ startRecordVerified(cam) }, MODE_SWITCH_SETTLE_MS)
                    })
                }
            }
            override fun onFailure(error: AutelError?) {
                // Cannot read the mode — try the record anyway rather than refusing; the
                // camera's own rejection (surfaced by camCb) beats a guess about why.
                AppLog.w(TAG, "getMediaMode failed (${error?.description}) — trying record directly")
                cam.startRecordVideo(camCb("startRecordVideo"))
            }
        })
    }

    /**
     * Issues StartRecording and then CHECKS WHETHER IT ACTUALLY WORKED.
     *
     * The camera answers StartRecording with status 0 even when it is not going to record —
     * observed on hardware 2026-08-01: a start issued 2ms after a Single->Record mode switch
     * was acked as success, produced no RECORD_START event, and left no file on the SD card.
     * A pilot who trusts the button in that state believes they have footage they do not have,
     * which is the worst possible failure for this control. So the SDK's "OK" is treated as a
     * request receipt, not as evidence.
     *
     * Truth comes from [AutelProductHolder.isRecording], which only goes true on the camera's
     * own RECORD_START push. If that has not arrived within [RECORD_CONFIRM_MS] we retry once
     * (the settle delay may simply have been too short for a slower card or a busy camera),
     * and if it still has not arrived we TELL THE PILOT rather than leaving a dark pill and a
     * false sense of recording.
     *
     * The window is generous: when the camera is genuinely ready, RECORD_START lands in ~1ms.
     */
    private fun startRecordVerified(cam: AutelBaseCamera, isRetry: Boolean = false) {
        // WATCHDOG ARMED BEFORE THE CALL, NOT AFTER. It used to be posted on the line below the
        // SDK call, which meant a SYNCHRONOUS THROW skipped the very safety net that exists to
        // catch a record that does not happen. That is not hypothetical: with the camera set to
        // internal flash, startRecordVideo throws NPE out of the SDK's own precondition check
        // (2026-08-06). The SDK swallowed it, no callback ever came, the watchdog was never
        // posted, and the pilot got a dark pill and complete silence — the exact outcome this
        // whole verify-don't-trust design was written to prevent. Posting first makes the net
        // independent of how the call fails.
        val watchdog = Runnable {
            if (AutelProductHolder.isRecording) return@Runnable   // camera confirmed it
            if (!isRetry) {
                AppLog.w(TAG, "no RECORD_START ${RECORD_CONFIRM_MS}ms after startRecordVideo — retrying once")
                startRecordVerified(cam, isRetry = true)
            } else {
                AppLog.e(TAG, "recording did not start: camera accepted StartRecording twice " +
                    "but never reported RECORD_START")
                toast(recordFailureReason() ?: "Recording did not start. The camera did not confirm.")
            }
        }
        handler.postDelayed(watchdog, RECORD_CONFIRM_MS)
        // Guarded for the same reason: a throw here must not escape into the SDK's dispatcher,
        // where it is logged as a "parse error" and never reaches the pilot. On a throw we
        // already know the outcome, so the watchdog is cancelled — BY REFERENCE, never
        // removeCallbacksAndMessages(null), which would also take out the HUD refresh loop and
        // the notice auto-hide that share this handler.
        runCatching {
            cam.startRecordVideo(camCb(if (isRetry) "startRecordVideo(retry)" else "startRecordVideo"))
        }.onFailure {
            AppLog.e(TAG, "startRecordVideo threw: $it")
            handler.removeCallbacks(watchdog)
            toast(recordFailureReason() ?: "Recording could not start. The camera refused.")
        }
    }

    /**
     * The reason recording is not going to work, in words a pilot can act on — or null when
     * nothing known is wrong and the generic "camera did not confirm" is the honest answer.
     *
     * Storage is checked first because it is the failure that looks like nothing at all: a card
     * in the slot that the camera is not writing to (see [AutelProductHolder.recordingToInternal]).
     */
    private fun recordFailureReason(): String? {
        val h = AutelProductHolder
        return when {
            h.recordingToInternal && h.mmcState == com.autel.common.camera.base.MMCState.CARD_FULL ->
                "The camera is recording to its internal memory, which is full. " +
                    "Change the camera storage to the SD card."
            h.recordingToInternal ->
                "The camera is recording to its internal memory, not the SD card."
            h.sdCardState != null &&
                h.sdCardState != com.autel.common.camera.base.SDCardState.CARD_READY ->
                "The SD card is not ready (${h.sdCardState})."
            else -> null
        }
    }

    /**
     * Photo — still to the aircraft's SD card. Confirmation comes from the camera's
     * PHOTO_TAKEN_DONE event (surfaced as a notice by updateHud), not from the call returning.
     *
     * ANSWERED ON HARDWARE 2026-08-01 (this used to read "unknown until hardware"): the XT709
     * does NOT accept startTakePhoto while in VIDEO mode — it rejects it, so from the resting
     * mode this always takes the SINGLE-mode fallback below. The direct attempt is kept anyway:
     * it costs one fast rejection (~100ms) and self-heals if a firmware update ever allows it.
     *
     * VIDEO IS THE RESTING MODE, and the restore below is what maintains that. The camera sends
     * a different frame shape per mode (photo 4:3, video 16:9) and nothing can change that —
     * setAspectRatio is ignored by this camera and VideoResolution has no 4:3 option, both
     * verified — so SOME action has to pay the visible shape change. The operator's call
     * (2026-08-01): recordings are far more frequent than stills on this aircraft, so the cost
     * belongs on the photo path, not on record start/stop. Recording is the thing that must
     * stay visually still, because that is when the TAK team is watching the screen capture.
     */
    private fun onShootPhotoTapped() {
        val cam = AutelProductHolder.camera
        if (cam == null) {
            AppLog.w(TAG, "photo ignored — camera not connected")
            toast("The camera is not connected.")
            return
        }
        cam.startTakePhoto(object : CallbackWithNoParam {
            override fun onSuccess() { AppLog.i(TAG, "startTakePhoto: OK (direct)") }
            override fun onFailure(error: AutelError?) {
                AppLog.i(TAG, "direct photo rejected (${error?.description}) — trying SINGLE-mode fallback")
                cam.setMediaMode(MediaMode.SINGLE, camCb("setMediaMode(SINGLE)") {
                    cam.startTakePhoto(camCb("startTakePhoto") {
                        // Back to the resting mode. Delayed for the same reason the record path
                        // waits: this camera acknowledges a mode change before it can act on it,
                        // and the still is still being written when the shutter call returns.
                        handler.postDelayed(
                            { cam.setMediaMode(MediaMode.VIDEO, camCb("setMediaMode(VIDEO rest)")) },
                            MODE_SWITCH_SETTLE_MS)
                    })
                })
            }
        })
    }

    /**
     * Drives the LIVE pill from what the stream is ACTUALLY doing, not from whether a streamer
     * object exists. LIVE means video is leaving the controller; amber means we are trying;
     * off means nothing is running. A pilot uses this to decide whether the team can see what
     * they see, so "an object was constructed" is not good enough.
     */
    private fun refreshStreamToggle() {
        streamToggle.setState(
            when (VideoStreamerHolder.state) {
                VideoStreamerHolder.State.LIVE -> LiveToggleView.State.LIVE
                VideoStreamerHolder.State.CONNECTING -> LiveToggleView.State.RECONNECTING
                VideoStreamerHolder.State.OFF -> LiveToggleView.State.OFF
            }
        )
    }

    /** Transient green notice over the video, upper-left (DJI's fpvNotice pattern). */
    /**
     * Transient notice over the video, auto-hidden.
     *
     * [refused] tells the pilot the app did NOT do the thing, and makes the text amber instead
     * of green. The colour is set here and never at a call site, so a refusal cannot reach the
     * screen wearing the acknowledgement colour — a pilot reads the colour before the words.
     *
     * This goes to the notice and never to a Toast, because the flight screen IS the TAK video
     * feed: a Toast is not in the screen capture, so a refused marker would leave the team
     * waiting for a mark that is never coming. Specification §4.8.
     */
    private fun showNotice(text: String, refused: Boolean = false) {
        fpvNotice.text = text
        fpvNotice.setTextColor(androidx.core.content.ContextCompat.getColor(this,
            if (refused) R.color.tp_state_caution else R.color.tp_state_go))
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
     * RESOLVED 2026-08-01. This used to warn that the sign convention was uncalibrated. It is
     * now settled: Autel reports pitch DOWN POSITIVE (opposite to DJI), and the bridge negates
     * it at ingest so everything downstream — this readout, the ring, the SPI look-point, the AR
     * overlay and the CoT pitch sent to TAK — shares one convention (down = negative).
     *
     * So the pitch arriving here is ALREADY normalised. Do not add another sign here; that would
     * put this cue back out of step with the SPI math, which is exactly what the old note was
     * trying to prevent.
     */
    /**
     * Why a marker may NOT be placed right now, or null if it may.
     *
     * Returns a REASON rather than a boolean so the pilot is told which rule stopped them.
     * "The app did nothing" is the failure mode that makes people press harder and then stop
     * trusting the control.
     *
     * Two independent rules, both about whether a computed ground point means anything:
     *
     *  1. **Look angle.** Asks [CrosshairView.accuracyColorFor] — the SAME call that tints the
     *     reticle — so "red reticle" and "drop refused" can never disagree. A separate threshold
     *     here would eventually drift and the pilot would see a red reticle accept a drop.
     *  2. **Height above ground.** Below [MIN_DROP_AGL_FT] the geometry is worthless: ground
     *     range is height / tan(pitch), so as height goes to zero the solved point collapses
     *     onto the aircraft's own position no matter where the camera looks. A marker placed on
     *     take-off or during landing would land on the pilot, and it would look deliberate to
     *     everyone receiving it.
     *
     * Uses the same AGL the HUD shows — terrain-corrected where DTED covers the aircraft,
     * otherwise height above the take-off point — so the number the pilot reads is the number
     * being judged.
     */
    private fun dropRefusalReason(): String? {
        val hud = TakBridgeHolder.hud() ?: return "waiting on GPS + gimbal"
        val pitch = hud.gimbalPitch ?: return "waiting on GPS + gimbal"

        val aglFt = Units.metersToFeet(TerrainAgl.reading(this, hud).meters)
        if (aglFt < MIN_DROP_AGL_FT) {
            return "too low — climb above ${MIN_DROP_AGL_FT.toInt()} ft AGL to place a marker"
        }

        val dtedAvailable = hud.hasFix &&
            DtedIndex.elevationAt(this, hud.lat, hud.lon) != null
        if (CrosshairView.accuracyColorFor(this, pitch, dtedAvailable) ==
            CrosshairView.accuracyPoorColor(this)) {
            return "look angle too shallow — tilt the gimbal down"
        }
        return null
    }

    /** True when a marker must not be placed. Kept as a predicate for the call sites that only
     *  need the yes/no; the reason itself comes from [dropRefusalReason]. */
    private fun aimTooPoorToDrop(): Boolean = dropRefusalReason() != null

    /** Shared refusal, so every drop route gives the pilot the same — and specific — reason. */
    private fun refuseDropForAim() {
        val why = dropRefusalReason() ?: return
        AppLog.w(TAG, "marker drop refused — $why")
        showNotice("Cannot place the marker. $why", refused = true)
    }

    /**
     * RTH altitude, as reported BY THE AIRCRAFT.
     *
     * Never the Pre-Flight value. On 2026-08-02 the operator flew two sorties believing RTH was
     * set to 50 ft: the write had been rejected as out-of-range, the aircraft kept a different
     * value, and nothing anywhere said so. Echoing the requested number on the flight screen
     * would have reproduced that failure in the one place it matters most.
     *
     * Grey "RTH --" until the aircraft answers. Unknown must look unknown.
     */
    /**
     * Draws the exterior-lights button from the AIRCRAFT's reported lamp state.
     *
     * Plain bulb = lit, slashed bulb = dark, matching the IR buttons' convention that the icon
     * shows the state of the hardware rather than what the next tap would do. When the aircraft
     * has not answered, the button is dimmed: a pilot must be able to tell "confirmed lit" from
     * "we do not know", because only one of those is safe to act on at night.
     */
    private fun renderLightsButton() {
        if (!::lightsButton.isInitialized) return
        val dark = AutelLights.isDark
        lightsButton.setImageResource(
            if (dark == true) R.drawable.ic_led_off else R.drawable.ic_led_on)
        lightsButton.alpha = if (dark == null) 0.45f else 1.0f
        lightsButton.contentDescription = when (dark) {
            true -> "Exterior lights are off"
            false -> "Exterior lights are on"
            null -> "Exterior lights, state unknown"
        }
    }

    private fun updateRthAltitude() {
        val known = FlightLimitsController.aircraftReturnHeightM != null
        fpvRthAltitude.text = FlightLimitsController.rthHudLabel()
        fpvRthAltitude.setTextColor(androidx.core.content.ContextCompat.getColor(applicationContext,
            if (known) R.color.tp_text_secondary else R.color.tp_state_unknown))
    }

    /**
     * BVLOS antenna aim (operator, 2026-08-13): the controller's antennas are directional,
     * and during authorized BVLOS work the pilot cannot see the aircraft to face it.
     *
     * The bearing is CONTROLLER→AIRCRAFT from the operator's own GPS fix — home point would
     * be wrong the moment the pilot walks. The bottom-centre arc ([AntennaAimView]) carries
     * the whole story: pilot dot at the centre, aircraft marker riding the arc, marker at
     * the top = antennas on target. No text fallback (operator's call): a bearing number
     * without an on-screen compass gives the pilot nothing to act on, and the RCPad's
     * rotation-vector sensor is verified present, so the arc is the indicator.
     */
    private fun updateAntennaAim(hud: AutelTakBridge.Hud?) {
        val fix = OperatorLocation.latest
        val facing = ControllerCompass.azimuthTrueDeg()
        if (hud == null || !hud.hasFix || fix == null || facing == null) {
            fpvAntennaArc.setRelativeBearing(null)
            return
        }
        val bearing = CameraSlantPoint.initialBearingDeg(
            fix.latitude, fix.longitude, hud.lat, hud.lon)
        // Signed relative turn, -180..+180: which way and how far the pilot must rotate.
        fpvAntennaArc.setRelativeBearing(((bearing - facing + 540.0) % 360.0) - 180.0)
    }

    private fun updateGimbalPitch(hud: AutelTakBridge.Hud?) {
        val pitch = hud?.gimbalPitch
        // Whether a marker dropped RIGHT NOW would get CameraSlantPoint's terrain-corrected
        // solve — DTED coverage at the aircraft's OWN position, not just "any DTED loaded".
        val dtedAvailable = hud != null && hud.hasFix &&
            DtedIndex.elevationAt(this, hud.lat, hud.lon) != null
        crosshairView.setGimbalPitch(pitch, dtedAvailable)
        // Look-point distance and bearing at the reticle's lower-right (operator,
        // 2026-08-13). Null — and no text — when the camera is at/above the horizon or
        // telemetry is not ready; Units.distance keeps the range in the HUD's imperial
        // convention (ft, then mi). The bearing is the camera's own true bearing, the same
        // model the SPI and a marker drop use, so the reticle cannot disagree with them.
        crosshairView.setRangeText(
            TakBridgeHolder.lookRangeMeters()?.let { range ->
                val brg = TakBridgeHolder.cameraPose()?.bearingDeg
                if (brg == null) Units.distance(range)
                else "%s  %03.0f°T".format(Units.distance(range), brg)
            })
        if (pitch == null) {
            fpvGimbalPitch.text = "GIMBAL —"
            fpvGimbalPitch.setTextColor(androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
            return
        }
        // Sign dropped in favour of an explicit DOWN/UP word: "-20" reads as a negative number
        // rather than as a look angle, and down is the only direction that matters for drops.
        fpvGimbalPitch.text = when {
            pitch <= -1.0 -> "GIMBAL %.0f° DOWN".format(-pitch)
            pitch >= 1.0 -> "GIMBAL %.0f° UP".format(pitch)
            else -> "GIMBAL LEVEL"
        }
        fpvGimbalPitch.setTextColor(CrosshairView.accuracyColorFor(this, pitch, dtedAvailable))
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
        // No UASFM data imported at all. This used to hide the line entirely, which left the
        // pilot unable to tell "no ceiling data loaded" from "this build has no FAA feature" —
        // a blank space says nothing. Dashes in the slot the ceiling would occupy say the
        // number is unknown (operator, 2026-07-31).
        if (!UasfmIndex.hasCoverage(this)) {
            showFaaUnknown()
            return
        }
        if (hud == null || !hud.hasFix) {
            fpvFaaCeiling.visibility = View.VISIBLE
            fpvFaaCeiling.text = "FAA — no fix"
            fpvFaaCeiling.setTextColor(androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
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
                    if (aglFt > ceiling) androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_btn_danger_dialog) else Color.WHITE
                )
            }
            // Inside what was downloaded but in no cell: the FAA publishes no facility map here,
            // which means uncontrolled airspace and the plain Part 107 ceiling. Grey + labelled
            // so it never reads as "the facility map says 400".
            cachedFaaWithinDownloadedArea -> {
                val part107 = UasfmIndex.PART_107_DEFAULT_CEILING_FT
                fpvFaaCeiling.text = "Class G · $part107 ft AGL$approx"
                fpvFaaCeiling.setTextColor(
                    if (aglFt > part107) androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_btn_danger_dialog) else androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary)
                )
            }
            // Outside the downloaded box entirely — we genuinely do not know. Shown identically
            // to "nothing downloaded" (operator, 2026-07-31): an earlier version made this
            // amber and worded it differently on the grounds that flying OUT of coverage is
            // more surprising than never having had it. But the pilot can do exactly the same
            // thing about both — nothing, in the air — so two treatments for one meaning was
            // noise. Both now read as an unknown ceiling.
            else -> showFaaUnknown()
        }
    }

    /** The ceiling is unknown: no data downloaded, or the aircraft is outside what was. Dashes
     *  in the number's own slot, grey — it must not read as a limit of any kind. */
    private fun showFaaUnknown() {
        fpvFaaCeiling.visibility = View.VISIBLE
        fpvFaaCeiling.text = "FAA --- ft AGL"
        fpvFaaCeiling.setTextColor(androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
    }

    // ---- Markers list (drop-pin long-press) ----

    /**
     * The dropped-markers panel. Rebuilt from [TakDropMarkers.listPins] each time it opens and
     * after every action, so it cannot show a stale list.
     *
     * Deliberately reachable with zero pins: Clear All is still meaningful right after a
     * delete, and a panel that refuses to open when empty just makes the pilot wonder whether
     * the long-press registered.
     */
    /**
     * One row of the marker list. Markers this pilot dropped and markers the team shared appear
     * TOGETHER (operator, 2026-08-04) — a pilot looking for "the marker by the north gate" does
     * not care who placed it, and a list that showed only their own made the shared ones look
     * absent when they were on the map the whole time.
     *
     * The two are not interchangeable, though: only an own marker can be moved, renamed or
     * retyped, because those edit the marker on every other client. A shared one can be removed
     * from this map and RE-SENT — re-broadcasting a received marker is ordinary TAK behaviour
     * and goes out under that marker's own uid and type. [ownPin] being null marks a row shared.
     */
    private data class MarkerRow(
        val label: String,
        val iconRes: Int,
        val ownPin: TakDropMarkers.PinInfo?,
        val sharedUid: String?,
    )

    private fun buildMarkerRows(): List<MarkerRow> {
        val hud = TakBridgeHolder.hud()
        // Range/bearing from the AIRCRAFT to each marker, so the list is orderable by "what's
        // near me" in the air rather than just drop order.
        fun range(lat: Double, lon: Double): String =
            if (hud != null && hud.hasFix) {
                val d = CameraSlantPoint.distanceMeters(hud.lat, hud.lon, lat, lon)
                val b = CameraSlantPoint.initialBearingDeg(hud.lat, hud.lon, lat, lon)
                // Units.distance (not .feet): a marker has no geofence bound the way the
                // aircraft's own position does, so this can run to five digits of feet where
                // miles read better.
                "  ·  %s @ %03.0f°".format(Units.distance(d), b)
            } else ""

        val own = TakDropMarkers.listPins().map {
            MarkerRow("${it.affiliation.label}: ${it.name}${range(it.lat, it.lon)}",
                it.affiliation.res, it, null)
        }
        val shared = TakMapMarkers.listShared().map {
            // "Team:" prefix rather than an affiliation word — the useful distinction in this
            // list is who can edit it, and the affiliation is already carried by the icon.
            MarkerRow("Team: ${it.name}${range(it.lat, it.lon)}",
                TakMapMarkers.milMarkerRes(it.type) ?: R.drawable.marker_unknown, null, it.uid)
        }
        return own + shared
    }

    /**
     * The markers list, with a check box on every row and bulk Delete / Resend
     * (operator, 2026-08-15).
     *
     * SHORT TAP TICKS THE BOX, LONG PRESS EDITS. The five-action edit menu is unchanged; it
     * simply moved from the tap to the long press, so the short tap could become the selection
     * gesture that a check-box list needs.
     *
     * This replaced an AlertDialog.setAdapter list. That call offers no long-press hook and no
     * room for a check box, thus the list had to become a custom view — see dialog_markers.xml.
     *
     * The rows are rebuilt in place after an action rather than the dialog being closed and
     * reopened, so a pilot clearing several markers keeps their place.
     */
    private fun onMarkersListTapped() {
        // Themed inflater, per specification §6.3. A view built with the ACTIVITY's context
        // inherits the activity theme, not the dialog's, and the row label lands white on white.
        val themed = android.view.ContextThemeWrapper(this, R.style.TakDialogTheme)
        val view = android.view.LayoutInflater.from(themed)
            .inflate(R.layout.dialog_markers, null)
        val container = view.findViewById<android.widget.LinearLayout>(R.id.markersContainer)
        val empty = view.findViewById<TextView>(R.id.markersEmpty)
        val resendButton = view.findViewById<android.widget.Button>(R.id.markersResendButton)
        val deleteButton = view.findViewById<android.widget.Button>(R.id.markersDeleteButton)

        // Selection lives only as long as the dialog. It holds the ROW KEY — a pin key for an
        // own marker, a CoT uid for a shared one — because a list position stops being valid
        // the moment a row is deleted underneath it.
        val selected = mutableSetOf<String>()

        fun rowKey(row: MarkerRow): String? = row.ownPin?.key ?: row.sharedUid

        fun refreshButtons() {
            val any = selected.isNotEmpty()
            for (b in listOf(resendButton, deleteButton)) {
                b.isEnabled = any
                b.alpha = if (any) 1f else 0.45f
            }
        }

        fun populate() {
            container.removeAllViews()
            val rows = buildMarkerRows()
            // A key that no longer exists — its marker was deleted — must not stay selected, or
            // the next Delete would act on nothing and the count would lie.
            selected.retainAll(rows.mapNotNull { rowKey(it) }.toSet())
            empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            for (row in rows) {
                val key = rowKey(row) ?: continue
                val item = android.view.LayoutInflater.from(themed)
                    .inflate(R.layout.row_marker_select, container, false)
                val check = item.findViewById<android.widget.CheckBox>(R.id.markerRowCheck)
                item.findViewById<android.widget.ImageView>(R.id.markerRowIcon)
                    .setImageResource(row.iconRes)
                item.findViewById<TextView>(R.id.markerRowLabel).text = row.label
                check.isChecked = selected.contains(key)
                item.setOnClickListener {
                    if (!selected.add(key)) selected.remove(key)
                    check.isChecked = selected.contains(key)
                    refreshButtons()
                }
                item.setOnLongClickListener {
                    if (row.ownPin != null) onMarkerRowTapped(row.ownPin)
                    else onSharedMarkerRowTapped(row)
                    true
                }
                container.addView(item)
            }
            refreshButtons()
        }
        populate()

        val dialog = AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Markers")
            .setView(view)
            .setNegativeButton("Close", null)
            .setNeutralButton("Clear All") { _, _ -> onClearAllMarkersTapped() }
            .create()

        // NO CONFIRMATION ON BULK DELETE (operator, 2026-08-15). It is local-only: there is no
        // delete CoT in this application, thus the markers stay on the server until they go
        // stale and a shared one the team sends again comes straight back. Nothing here reaches
        // another operator's screen.
        deleteButton.setOnClickListener {
            val rows = buildMarkerRows().filter { rowKey(it) in selected }
            AppLog.i(TAG, "markers: bulk delete of ${rows.size}")
            for (row in rows) {
                val pin = row.ownPin
                // deleteShared, NOT clearAllShared's path: the per-uid delete also marks the uid
                // hidden, without which the next inbound copy would put the marker straight back.
                if (pin != null) TakDropMarkers.delete(pin.key)
                else row.sharedUid?.let { TakMapMarkers.deleteShared(it) }
            }
            selected.clear()
            populate()
        }

        // SILENT (operator, 2026-08-15) — no notice, no toast.
        resendButton.setOnClickListener {
            val rows = buildMarkerRows().filter { rowKey(it) in selected }
            AppLog.i(TAG, "markers: bulk re-send of ${rows.size}")
            for (row in rows) {
                val pin = row.ownPin
                if (pin != null) TakDropMarkers.resend(pin.key)
                else row.sharedUid?.let { TakMapMarkers.resendShared(it) }
            }
        }

        dialog.show()

        // Clear All in red, matching every other destructive control in the app. AlertDialog has
        // no per-button style, so it is tinted after show() — the button does not exist before.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            ?.setTextColor(androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_btn_danger_dialog))
    }

    /**
     * A marker somebody else shared: remove it from this map, or send it again.
     *
     * RE-SENDING A RECEIVED MARKER IS NORMAL (operator, 2026-08-15) — every TAK client can do
     * it, and it is how a marker that went stale on one screen is brought back for the team.
     * This comment used to say re-sending "is not this pilot's to do", which was simply wrong.
     * It goes out under the marker's OWN uid and OWN CoT type, thus it updates that marker
     * rather than making a second one — see [TakMapMarkers.resendShared].
     *
     * Rename, retype and move are still absent: [TakMapMarkers] has no mutators for them, and
     * editing another operator's marker is a larger question than re-broadcasting one.
     */
    private fun onSharedMarkerRowTapped(row: MarkerRow) {
        val uid = row.sharedUid ?: return
        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle(row.label.substringAfter("Team: ").substringBefore("  ·"))
            .setItems(arrayOf("Re-send", "Remove from my map")) { _, index ->
                when (index) {
                    0 -> {
                        AppLog.i(TAG, "shared marker re-send: $uid")
                        TakMapMarkers.resendShared(uid)
                    }
                    1 -> {
                        TakMapMarkers.deleteShared(uid)
                        onMarkersListTapped()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onMarkerRowTapped(pin: TakDropMarkers.PinInfo) {
        val actions = arrayOf("Move to crosshair", "Rename", "Change type", "Re-send", "Delete")
        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle(pin.name)
            .setItems(actions) { _, index ->
                when (index) {
                    0 -> if (aimTooPoorToDrop()) refuseDropForAim() else onMoveMarkerTapped(pin)
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
            toast("Cannot move the marker yet. Wait for GPS and the gimbal.")
            return
        }
        val (lat, lon, elev) = look
        AppLog.i(TAG, "marker move: ${pin.key} -> $lat,$lon elev=$elev")
        TakDropMarkers.moveToLookPoint(pin.key, lat, lon, elev)
    }

    private fun onRenameMarkerTapped(pin: TakDropMarkers.PinInfo) {
        // Colours set explicitly: a setView() child is built with the ACTIVITY's context and
        // does NOT inherit TakDialogTheme, so it would otherwise render near-black on the dark
        // card. Same reason as DataSyncActivity's dialogEditText().
        val field = android.widget.EditText(this).apply {
            setText(pin.name)
            setSelection(pin.name.length)
            setTextColor(androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_primary))
            setHintTextColor(androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_hint_dialog))
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
            .setAdapter(iconRowAdapter(affiliationRows(affs))) { _, i ->
                TakDropMarkers.changeType(pin.key, affs[i])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onDeleteMarkerTapped(pin: TakDropMarkers.PinInfo) {
        AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            .setTitle("Delete Marker")
            .setMessage("Remove \"${pin.name}\" from your map? This is local-only — it stays " +
                "on the TAK server until it goes stale (3 days) and may still show on " +
                "other clients until then.")
            .setPositiveButton("Delete") { _, _ ->
                AppLog.i(TAG, "marker delete: ${pin.key}")
                TakDropMarkers.delete(pin.key)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Clears BOTH sets — the markers this pilot dropped and the markers the team shared
     * (operator, 2026-08-04). It used to clear only the pilot's own, which left a "cleared" map
     * still carrying every shared marker.
     *
     * The message states both counts before the pilot commits, because the two have different
     * consequences: an own marker stays on the team's screens until it goes stale, while a shared
     * one is only being removed from this aircraft.
     */
    private fun onClearAllMarkersTapped() {
        val ownCount = TakDropMarkers.listPins().size
        val sharedCount = TakMapMarkers.listShared().size
        AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            .setTitle("Clear All Markers")
            .setMessage(
                "Remove all markers from this map?\n\n" +
                    "· $ownCount that you dropped\n" +
                    "· $sharedCount that your team shared\n\n" +
                    "This changes this aircraft only. Your own markers stay on the screens of " +
                    "your team until they go stale, 3 days. A marker that your team " +
                    "shares again will come back.")
            .setPositiveButton("Clear All Markers") { _, _ ->
                AppLog.i(TAG, "markers: clear all confirmed ($ownCount own, $sharedCount shared)")
                TakDropMarkers.clearAll()
                TakMapMarkers.clearAllShared()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Drop-pin UI ----

    /**
     * Adapter for the affiliation pickers: MIL-STD shape + name, not name alone.
     *
     * Both pickers used `AlertDialog.setItems()`, which renders text ONLY — so
     * `Affiliation.res` was defined and never drawn, and the pilot chose an affiliation by
     * reading a word. The shape and colour are the whole point of these symbols; a pilot
     * scanning under time pressure recognises a red diamond faster than the string "Hostile",
     * and the icon here is the same drawable that ends up on the map, so what they pick is
     * literally what they get.
     */
    private fun iconRowAdapter(rows: List<Pair<Int, String>>):
        android.widget.ArrayAdapter<Pair<Int, String>> {
        // Themed context, so the row inflates against the dialog's dark background rather than
        // the activity's — otherwise the white label can land on white.
        val themed = android.view.ContextThemeWrapper(this, R.style.TakDialogTheme)
        return object : android.widget.ArrayAdapter<Pair<Int, String>>(
            themed, R.layout.item_marker_affiliation, R.id.affiliationLabel, rows
        ) {
            override fun getView(position: Int, convertView: android.view.View?,
                                 parent: android.view.ViewGroup): android.view.View {
                val row = super.getView(position, convertView, parent)
                val (iconRes, label) = getItem(position)!!
                row.findViewById<ImageView>(R.id.affiliationIcon).setImageResource(iconRes)
                row.findViewById<android.widget.TextView>(R.id.affiliationLabel).text = label
                return row
            }
        }
    }

    private fun affiliationRows(affs: Array<TakDropMarkers.Affiliation>) =
        affs.map { it.res to it.label }

    /**
     * The create-a-marker menu: pick an affiliation, then [then] places it.
     *
     * Opens even when the drop gate is closed, and says so in the title. Hiding the menu
     * whenever a marker could not be placed made the whole create flow vanish during the states
     * it is most often in — on the ground, or at a shallow gimbal angle — and left the pilot
     * with no way to see what the choices even were.
     */
    private fun pickAffiliationThen(then: (TakDropMarkers.Affiliation) -> Unit) {
        val affs = TakDropMarkers.Affiliation.values()
        val blocked = dropRefusalReason()
        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle(if (blocked == null) "Marker affiliation" else "Cannot place the marker: $blocked")
            .setAdapter(iconRowAdapter(affiliationRows(affs))) { _, which ->
                AppLog.v(TAG, "affiliation chosen: ${affs[which].label}")
                then(affs[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- TakDropMarkers.Ui ----

    override fun askSend(affiliationLabel: String, onChoice: (Boolean) -> Unit) {
        runOnUiThread {
            AlertDialog.Builder(this, R.style.TakDialogTheme)
                .setTitle("$affiliationLabel marker placed")
                .setMessage("Send this marker to the TAK server?")
                .setCancelable(false)
                .setPositiveButton("Send to TAK") { _, _ -> AppLog.i(TAG, "pin send: yes ($affiliationLabel)"); onChoice(true) }
                .setNegativeButton("Do not Send") { _, _ -> AppLog.v(TAG, "pin send: no ($affiliationLabel)"); onChoice(false) }
                .show()
        }
    }

    override fun pinMenu(title: String, onSend: () -> Unit, onDelete: () -> Unit, sendLabel: String?) {
        runOnUiThread {
            val b = AlertDialog.Builder(this, R.style.TakDialogTheme).setTitle(title)
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
        /** The at-connect settling time the camera and fly-controller calls all need. Matches
         *  the delay AutelProductHolder uses for the same reason. */
        private const val LAMP_READ_DELAY_MS = 4500L

        private const val TAG = "FlightActivity"

        /** Which SDK custom button drives the quick marker: "A" or "B". Set from the
         *  logged event after a press — see installHardwareButtonListener. */
        /** Controller custom buttons, in the SDK's vocabulary. It has no notion of the
         *  physical "C1"/"C2" labels — the mapping below was confirmed on hardware
         *  2026-08-02 by logging the events a press actually emits. */
        // C2 drives BOTH marker actions: a short press for the quick marker, a long press for a
        // new stationary Unknown marker. The name stays as it is — it is still the marker button.
        private const val QUICK_MARKER_BUTTON = "B"   // physical C2
        // C1 drives BOTH thermal actions: a short press changes the camera between visible and
        // thermal, a long press changes the thermal colours. It was the zoom button before
        // v1.6.0 (operator, 2026-08-15); zoom stays on the pill and the right zoom rocker.
        private const val IR_BUTTON = "A"             // physical C1

        /** Minimum height above ground for a marker drop, feet. Below this the slant
         *  solve degenerates onto the aircraft's own position — see dropRefusalReason. */
        private const val MIN_DROP_AGL_FT = 25.0

        /** 24-hour clock with seconds, for the HUD. Held as one instance rather than
         *  built per tick — updateHud runs twice a second for the whole flight. */
        private val clockFormat =
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

        /** Pause after a media-mode switch before commanding record — see [startRecordVerified].
         *  The failing case measured 2ms; this is deliberately far clear of it rather than tuned
         *  to the edge, since the cost of waiting is imperceptible and the cost of being early is
         *  a recording that silently never happens. */
        private const val MODE_SWITCH_SETTLE_MS = 800L

        /** How long to wait for the camera's RECORD_START before assuming the start was ignored.
         *  When the camera is ready this arrives in ~1ms, so this is ~1000x margin. */
        private const val RECORD_CONFIRM_MS = 1200L

        /**
         * Fixed mini-map zoom. The map is locked, so this is the zoom for the whole flight.
         *
         * **Not comparable to the DJI sibling's 15, and that is not a discrepancy.** Zoom
         * numbers only compare between maps with the same tile size. DJI's map is MapLibre with
         * 512px tiles; this is osmdroid on MAPNIK at 256px (verified against the bundled
         * osmdroid 6.1.14). A 512px tile at zoom z covers the same ground as a 256px tile at
         * z+1, so MapLibre 15 == osmdroid 16. Copying the blueprint's literal 15 made this map
         * one step wider than DJI's, which is what the operator saw on 2026-07-30.
         * If the two builds ever need re-matching, compare ground covered, not the number.
         *
         * **16.5, chosen on hardware** (operator, 2026-07-31), after the map grew to 180x240dp.
         * On the Smart Controller's xhdpi screen that covers about 293 x 390 m — roughly three
         * downtown blocks across — against 414 x 552 m at 16.
         *
         * The half-step is deliberate but not free: tiles exist only at integer zooms, so
         * osmdroid renders 16.5 by upscaling z16 tiles ~1.41x and street labels are softer than
         * at a native level. 17 would be sharp and tighter (~207 x 276 m). If labels ever read
         * as too soft, 17 is the fix, not 16.5 with sharper tiles — there is no such thing.
         *
         * **Two levels, toggled by the button on the map** (operator, 2026-07-31), replacing a
         * single compromise level. They serve different jobs and the numbers, on the Smart
         * Controller's 180x240dp map at xhdpi, are why these two:
         *
         *  - WIDE (15.5) covers ~586 x 781 m, so the home point stays on the map out to ~293 m
         *    laterally. Set by the operator on 2026-08-04 (13 -> 15 -> 15.5).
         *
         *    ⚠ **HOME LEAVES THE MAP BEFORE THE AIRCRAFT REACHES ITS DISTANCE LIMIT.** The
         *    max-distance limit is 1600 ft (488 m) and this view holds ~293 m. The original 13
         *    was chosen precisely to avoid that. It is now accepted, because DOUBLE-TAPPING THE
         *    MAP doubles both dimensions — an expanded WIDE covers ~1172 x 1562 m, so home is
         *    back on the map to ~586 m, past the limit. That is the trade: the compact view is
         *    tighter, and the expanded view is the one that answers "where is home".
         *    The HUD's numeric HOME distance and bearing stay correct at any range regardless.
         *
         *    ⚠ **15.5 IS A FRACTIONAL LEVEL, SO IT IS SOFTER THAN 15 OR 16.** Tiles exist only
         *    at integer zooms; osmdroid renders 15.5 by upscaling z15 tiles ~1.41x, and street
         *    labels blur. This build carried a 16.5 for the same reason once and the note then
         *    still applies: there is no fractional level that avoids this. If labels read as too
         *    soft, the fix is 15 or 16, not a different fraction.
         *  - NEAR (18) covers ~104 x 138 m: the "what is directly below the aircraft" view.
         *    Set by the operator on 2026-08-04, replacing 17 (~207 x 276 m). Home leaves this
         *    view past ~52 m, which is expected — that is what WIDE is for.
         *
         * Both are NATIVE zoom levels, so both render sharp. The single-level 16.5 this
         * replaced had to upscale z16 tiles ~1.41x and softened street labels; there is no
         * fractional level that avoids that, because tiles only exist at integers.
         *
         * At NEAR the home point and red way-back line leave the view past ~104 m — that is
         * expected, and the reason WIDE exists rather than something to fix. The HUD's numeric
         * HOME distance and bearing remain correct in both modes.
         *
         * Not comparable to the DJI sibling's single 15 — see the tile-size note above. This
         * toggle has no DJI counterpart at all; it is the first place this build is
         * deliberately ahead of the blueprint rather than catching up to it.
         */
        /** Mini-map scale-bar lengths, in on-screen centimetres (osmdroid's unit for
         *  maxLength). Short keeps the bar off the zoom toggle on the 180dp map; full is
         *  osmdroid's default inch for the doubled map. */
        private const val SCALE_BAR_CM_SMALL = 1.27f
        private const val SCALE_BAR_CM_FULL = 2.54f

        /** Within this many degrees of the aircraft bearing, the antenna-aim marker reads
         *  GREEN — close enough for the controller's antenna lobe. Read by [AntennaAimView]
         *  so the arc and this screen judge "aligned" identically. */
        const val ANTENNA_ALIGNED_DEG = 10.0

        private const val MAP_ZOOM_WIDE = 15.5
        private const val MAP_ZOOM_NEAR = 18.0
        private const val KEY_MAP_WIDE = "flight_map_wide"

        /** Where the mini-map centers before the aircraft has a GPS fix. Town Square Park in
         *  downtown Anchorage: a neutral public landmark, chosen deliberately so the default
         *  view is neither an operator's home area nor a public-safety facility. Same point as
         *  the DJI sibling's DEFAULT_CENTER and the UASFM center hint. */
        private const val DEFAULT_LAT = 61.2170
        private const val DEFAULT_LON = -149.8925

        /** How long a transient notice ("Home Point Set") stays up. */
        private const val NOTICE_MS = 3000L

        /** Screen-capture permission request. Android will not let this grant be persisted, so
         *  the pilot sees the system dialog on every stream start. */
        private const val REQUEST_MEDIA_PROJECTION = 3001

        /** One tap of the AR FOV calibration +/- buttons, degrees. */
        private const val FOV_STEP_DEG = 0.5

    /** Raw digital-zoom units per 1x. The SDK's scale is hundredths; see [applyZoom]. */
    private const val ZOOM_RAW_PER_X = 100

    /** Zoom limits in raw units, 1x to 16x. Enforced on OUR side: setDigitalZoomScale does no
     *  range checking and forwards whatever int it is given straight to the camera. */
    private const val ZOOM_RAW_MIN = 100
    private const val ZOOM_RAW_MAX = 1600

    /** How many ticks a held rocker waits before it starts walking the ladder. Six ticks at the
     *  rocker's ~200ms repeat is about 1.2 seconds.
     *
     *  THIS IS WHAT SEPARATES A PRESS FROM A HOLD, and the number comes from measurement, not
     *  from taste. The rocker is stiff and spring-loaded, and a press on it is NOT one tick: of
     *  44 presses captured on hardware 2026-08-15, the commonest length was 4 to 5 ticks (0.8
     *  to 1.0 second) and 19 of the 44 fell there. This was first written as 3 ticks, which
     *  would have made those 19 presses climb TWO levels for one push.
     *
     *  Six clears every press measured. The cost is 1.2s of dead time before a hold starts to
     *  walk, which is the right way round: waiting is a nuisance, an unasked-for second level
     *  is a wrong picture.
     *
     *  ⚠ THE MEASUREMENT IS BIASED LONG. It was taken while the rocker still drove a continuous
     *  zoom, thus the pilot was deliberately holding to watch the value park. Real taps on a
     *  ladder will be shorter. Re-measure before lowering this — the press length is in the log
     *  as the count of ZOOM_IN/ZOOM_OUT events with keyValue above 0. See [onZoomRocker]. */
    private const val ZOOM_LADDER_REPEAT_DELAY_TICKS = 6

    /** How many ticks between levels once a held rocker is walking: two, about 400ms, thus the
     *  nine levels of [ZoomLadder] take roughly four seconds end to end (operator, 2026-08-15).
     *  Fast enough to cross the range without the control feeling stuck, slow enough to let go
     *  on the level wanted. */
    private const val ZOOM_LADDER_REPEAT_EVERY_TICKS = 2

    /** A gap longer than this means a NEW press, rather than one continuous hold. Two rocker
     *  repeats' worth: long enough not to trip on the jitter in the hardware capture
     *  (199-213ms), short enough to catch a real gap between presses.
     *
     *  This is no longer how a release is detected — keyValue 0 is, at the listener. All this
     *  does now is mark where one press ends and the next begins, which is when [onZoomRocker]
     *  re-seeds from the camera and restarts the repeat delay. */
    private const val ZOOM_RELEASE_GAP_MS = 400L

    /** How long after this app writes a zoom the camera's own reading is treated as stale.
     *
     *  The reading is a ~2Hz push (AutelProductHolder), thus up to about 500ms behind a write
     *  we just made; 1500ms is three of those pushes and leaves room for the command round-trip
     *  in front of them. Inside this window [seedZoomFromCamera] keeps this app's own value.
     *  Outside it, the camera wins — which is the whole point, because some other application
     *  may have moved it. */
    private const val ZOOM_SEED_SETTLE_MS = 1500L

    /** How long rocker ticks are coalesced before the camera is written. Comfortably longer
     *  than the rocker's ~200ms repeat, because the point is to collapse a sustained HOLD — ten
     *  ticks in two seconds — into a few commands rather than ten. */
    private const val ZOOM_PUSH_MS = 150L

        private const val REQUEST_CODE_LOCATION = 4302

        /** A cached controller fix younger than this is used as-is; older forces a fresh read. */
        private const val FRESH_FIX_MS = 30_000L
        /** How long to wait for the controller's receiver to answer before giving up. */
        private const val LOCATION_TIMEOUT_MS = 12_000L
    }
}
