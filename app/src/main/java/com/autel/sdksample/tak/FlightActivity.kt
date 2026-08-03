package com.autel.sdksample.tak

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
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

    private lateinit var exposureReadout: TextView
    private lateinit var fpvClock: TextView
    private lateinit var fpvOverlayText: TextView
    private lateinit var fpvGimbalPitch: TextView
    private lateinit var fpvFaaCeiling: TextView
    private lateinit var fpvRthAltitude: TextView
    private lateinit var lightsButton: ImageButton
    private lateinit var fpvNotice: TextView
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
     *  syncIrStateFromCamera(). The buttons must never claim a mode the camera isn't in. */
    private var irOn = false
    private var irBlackHot = false
    private lateinit var map: LockedMapView
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

    /** Zoom pill state. Screen-scoped like the blueprint's — reopening the flight screen
     *  re-reads reality via the connect-time baseline rather than trusting a saved flag. */
    /** Current digital zoom step: 1, 2 or 4. Not a boolean any more — 4X is reachable by
     *  long-press, so "zoomed in or not" can no longer describe the state. */
    private var zoomLevel = 1

    // ISO/shutter readout cache, refreshed by pollExposureReadout() every ~2s (no push
    // listener exists for these on this SDK).
    @Volatile private var lastIsoLabel: String? = null
    @Volatile private var lastShutterLabel: String? = null

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
        // osmdroid must be configured before the MapView inflates. Shared with Pre-Flight
        // Setup so the cache budget and paths can't drift between the two screens.
        MapTileCache.configure(this)
        setContentView(R.layout.activity_flight)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullScreen()

        exposureReadout = findViewById(R.id.exposureReadout)
        fpvClock = findViewById(R.id.fpvClock)
        fpvOverlayText = findViewById(R.id.fpvOverlayText)
        fpvGimbalPitch = findViewById(R.id.fpvGimbalPitch)
        fpvFaaCeiling = findViewById(R.id.fpvFaaCeiling)
        fpvRthAltitude = findViewById(R.id.fpvRthAltitude)
        fpvNotice = findViewById(R.id.fpvNotice)
        crosshairView = findViewById(R.id.flightCrosshair)
        arOverlay = findViewById(R.id.flightArOverlay)
        obstacleEdges = findViewById(R.id.flightObstacleEdges)
        // Load the calibrated FOV before the overlay draws anything with it.
        ArSettings.loadFov(this)
        ArSettings.loadAimOffsets(this)
        // Chrome insets so edge arrows can't be parked under the toolbar or the HUD column
        // where they're invisible — the exact case (aircraft directly overhead) the indicator
        // matters most. Measured from the real views after layout, re-read every pass, so a
        // toolbar/HUD/map-size change can't silently break it.
        val toolbarView = findViewById<View>(R.id.flightToolbar)
        val hudColumn = findViewById<View>(R.id.flightHudColumn)
        toolbarView.viewTreeObserver.addOnGlobalLayoutListener {
            arOverlay.setChromeInsets(
                top = toolbarView.height.toFloat(),
                right = hudColumn.width.toFloat(),
            )
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
        map.setTileSource(MapStyle.tileSource(this))
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
            outlinePaint.color = Color.parseColor("#F44336")
            outlinePaint.strokeWidth = 2.5f * resources.displayMetrics.density
            isVisible = false
            infoWindow = null
            map.overlays.add(this)
        }

        TakDropMarkers.ui = this
        TakMapMarkers.install(applicationContext)
        TakDropMarkers.init(applicationContext)
        // Point the battery gauge at what the AIRCRAFT will actually do. Red starts where
        // it begins returning home; amber is the pilot caution above that. Hard-coded band
        // edges here would drift from the thresholds every time they were retuned.
        val rthPct = FlightLimitsController.savedLowBatteryPct(this).toFloatOrNull() ?: 15f
        toolbarBattery.setBands(rthPct, rthPct + 10f)
        TakMapMarkers.onMapReady(map)

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
        // this airframe. Each needs an Autel-side subsystem that doesn't exist yet (camera
        // control, an AR overlay, a decoder-restart hook, or an RF-quality calibration). They
        // are deliberately VISIBLE rather than omitted, so the toolbar a pilot learns on the
        // Mini 2 is the same toolbar they see here — and each says plainly what it is when
        // pressed, rather than looking broken or doing nothing. See notImplemented().
        arButton.setOnClickListener { onArToggleTapped() }
        // Same long-press idiom as RTH (reset home) and drop-pin (markers list).
        arButton.setOnLongClickListener { onArOptionsTapped(); true }
        refreshArButton()
        shootPhotoButton = findViewById(R.id.flightShootPhotoButton)
        shootPhotoButton.setOnClickListener {
            AppLog.v(TAG, "tap: Photo")
            onShootPhotoTapped()
        }
        zoomButton.setOnClickListener {
            AppLog.v(TAG, "tap: Zoom (currently ${zoomLevel}X)")
            // Tap cycles 1X <-> 2X. From 4X it returns to 1X rather than stepping down,
            // so one tap always gets the pilot back to the widest view.
            applyZoom(if (zoomLevel == 1) 2 else 1)
        }
        zoomButton.setOnLongClickListener {
            AppLog.v(TAG, "long-press: Zoom (currently ${zoomLevel}X)")
            applyZoom(if (zoomLevel == 4) 1 else 4)
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
                    if (!confirmed) toast("The drone did not change the lights.")
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
        crosshairView.onReticleLongPress = { onQuickMarkerAction("long-press: reticle") }

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
                        toast("Can't drop: camera look-point not available (GPS/gimbal not ready)")
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
        // Re-read the camera's real thermal state each time this screen comes up. Autel's own
        // app can have left the camera in IR, and the buttons must reflect the camera rather
        // than a default. No-ops when no camera is attached.
        syncIrStateFromCamera()
        map.onResume()
        installHardwareButtonListener()
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        AppLog.v(TAG, "onPause")
        map.onPause()
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
     *   C1 (custom A) — zoom: short toggles 1X/2X, long goes to 4X and back to 1X
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
                        // Logged unconditionally — this is how the button mapping gets confirmed,
                        // and how anyone later finds out what the other controls emit.
                        AppLog.i(TAG, "controller button event: $e")
                        // SDK thread — everything below shows toasts and touches views.
                        when (e.name) {
                            // C2: the quick marker. Short and long do the SAME thing, because
                            // there is only ever one E419 — see onQuickMarkerAction.
                            "CUSTOM_BUTTON_SHORT_$QUICK_MARKER_BUTTON",
                            "CUSTOM_BUTTON_LONG_$QUICK_MARKER_BUTTON" ->
                                runOnUiThread { onQuickMarkerAction("controller C2") }

                            // C1: zoom, MIRRORING the on-screen zoom button exactly — short
                            // toggles 1X/2X, long goes to 4X and back to 1X. A pilot who has
                            // learned one control has learned the other, and both routes end in
                            // applyZoom() so the button label and the camera cannot disagree.
                            "CUSTOM_BUTTON_SHORT_$ZOOM_BUTTON" ->
                                runOnUiThread { applyZoom(if (zoomLevel == 1) 2 else 1) }
                            "CUSTOM_BUTTON_LONG_$ZOOM_BUTTON" ->
                                runOnUiThread { applyZoom(if (zoomLevel == 4) 1 else 4) }
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

        val hud = TakBridgeHolder.hud()
        val takOk = TakManager.getInstance().isConnected
        val acOk = AutelProductHolder.isConnected

        // Instrument toolbar
        val takColor = if (takOk) Color.parseColor("#4CAF50") else Color.parseColor("#F44336")
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
        // video", so it doesn't claim more than it knows.
        findViewById<View>(R.id.flightNoVideoCover).visibility =
            if (acOk) View.GONE else View.VISIBLE
        // Slow-cadence camera reads piggyback on the HUD tick (500ms * 4 = ~2s).
        if (hudTickCount % 4 == 0) pollExposureReadout()
        exposureReadout.text = "ISO ${lastIsoLabel ?: "—"}   ${lastShutterLabel ?: "—"}"

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
        updateRthAltitude()

        // Same five-line readout as the DJI blueprint, imperial throughout (see Units).
        fpvOverlayText.text = buildString {
            // LINE ORDER IS DELIBERATE (operator, 2026-08-02), most-glanced-at first:
            //   1 callsign + speed   2 AGL/MSL   3 lat/lon   4 home
            // Height moved up to second because it is the number a pilot checks constantly;
            // lat/lon and home are reference figures they look up only when asked for them.
            // The clock sits above this block in its own view — see fpvClock.
            append(TakManager.getInstance().callsign ?: "—")
            append(if (hud != null) "   ${Units.mph(hud.speedMs)}" else "   — MPH")
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
        AlertDialog.Builder(this, R.style.TakDialogTheme)
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
     * LIVE tapped with no stream running. Checks the stream is configured, then asks for the
     * screen-capture permission; [onActivityResult] starts the foreground service, which starts
     * the push.
     *
     * The configured check happens BEFORE the permission prompt on purpose — making a pilot
     * grant screen capture and only then telling them the video server is not set up would be
     * two wasted taps and a confusing order.
     */
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
        toast("Starting screen stream…")
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
            toast("Screen capture permission denied — no stream started")
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

    // ---- AR overlay ----

    /** AR on/off. Off by default every time the flight screen opens — it draws over the video,
     *  so it should be something the pilot switches on deliberately rather than something they
     *  inherit from a previous session and have to notice. */
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

        // Rows built from the enum, not written out in XML — a category added later can't be
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

        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("AR Overlay")
            .setView(view)
            .setPositiveButton("Done", null)
            .setNeutralButton("Calibrate FOV…") { _, _ -> onArCalibrateTapped() }
            .setNegativeButton("Aim Offsets…") { _, _ -> onAimOffsetsTapped() }
            .show()
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
            setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
        }
        fun refreshHint() {
            // Both directions spelled out for BOTH rows, at the operator's request after using
            // this in flight. A pilot mid-calibration should not have to infer that "−" is the
            // opposite of the one direction the hint happened to name.
            hint.text = "Pitch +  sends the marker FARTHER from the aircraft, −  brings it " +
                "closer.\nBearing +  swings it clockwise, −  swings it counter-clockwise.\n\n" +
                "Aim at a known object with the gimbal 25° DOWN — a bias is nearly " +
                "invisible looking straight down.\n\nDefault is 0.00° / 0.00° (uncalibrated)."
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
                setTextColor(android.graphics.Color.parseColor("#9AC4FF"))
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

        var h = TakBridgeHolder.currentHFovBase
        var v = TakBridgeHolder.currentVFovBase

        fun apply() {
            ArSettings.saveFov(this, h, v)
            h = TakBridgeHolder.currentHFovBase
            v = TakBridgeHolder.currentVFovBase
            hValue.text = "%.1f°".format(h)
            vValue.text = "%.1f°".format(v)
            hint.text = if (TakBridgeHolder.currentZoomFactor > 1.0) {
                "Effective at %.0fx zoom: %.1f° × %.1f°".format(
                    TakBridgeHolder.currentZoomFactor,
                    AutelTakBridge.hFovDeg(TakBridgeHolder.currentZoomFactor),
                    AutelTakBridge.vFovDeg(TakBridgeHolder.currentZoomFactor),
                )
            } else {
                "Marker too far OUT from centre → reduce. Too far IN → increase."
            }
        }
        apply()

        view.findViewById<android.widget.Button>(R.id.arFovHMinus).setOnClickListener { h -= FOV_STEP_DEG; apply() }
        view.findViewById<android.widget.Button>(R.id.arFovHPlus).setOnClickListener { h += FOV_STEP_DEG; apply() }
        view.findViewById<android.widget.Button>(R.id.arFovVMinus).setOnClickListener { v -= FOV_STEP_DEG; apply() }
        view.findViewById<android.widget.Button>(R.id.arFovVPlus).setOnClickListener { v += FOV_STEP_DEG; apply() }

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
            if (on) Color.parseColor("#4CAF50") else Color.WHITE
        )
    }

    /**
     * Tap the reticle — drop the one quick marker at the look point, no dialog. If it already
     * exists, say so rather than moving it: a tap that sometimes places and sometimes moves is
     * a gesture the pilot can't predict the result of.
     */
    /**
     * The quick marker — place it, or move it if it already exists. ONE action.
     *
     * There was a short-press-places / long-press-re-aims split. It was a distinction without a
     * difference: [TakDropMarkers.QUICK_NAME] is a SINGLETON, so "place" and "move" are the same
     * intent — "the marker belongs where I am looking" — and the only thing the split achieved
     * was a scolding toast when the pilot used the wrong gesture on a marker that already
     * existed. Simplified at the operator's call after flying it.
     *
     * Every route now calls this: reticle touch, reticle touch-and-hold, and both presses of the
     * controller's custom button. Which gesture the pilot uses genuinely does not matter, which
     * is the point — there is nothing left to remember or get wrong mid-flight.
     *
     * @param source only for the log, so a press can be traced back to the control that sent it.
     */
    private fun onQuickMarkerAction(source: String) {
        AppLog.v(TAG, "$source: quick marker")
        if (aimTooPoorToDrop()) { refuseDropForAim(); return }
        val look = TakBridgeHolder.lookPoint()
        if (look == null) {
            AppLog.w(TAG, "quick marker refused — no look point (GPS/gimbal not ready)")
            toast("Can't place the marker yet — waiting on GPS + gimbal")
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
     * Video re-sync — rebuilds the on-screen decode from scratch. The SDK offers no
     * request-keyframe hook (DJI's requestResync asks the encoder for an IDR); the contained
     * equivalent here is tearing the codec view down and constructing a fresh one, which
     * restarts the decode session and picks up clean at the next keyframe. Expect a brief
     * black gap, same as the blueprint's hard resync. Only affects the local picture — the
     * RTSP push reads its own frame tap and is untouched.
     */
    /**
     * Makes the FPV video FILL the screen in every camera mode, aspect preserved, centred.
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
        val scale = maxOf(videoAspect / viewAspect, viewAspect / videoAspect)
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
        AppLog.i(TAG, "video fill: view ${vw.toInt()}x${vh.toInt()} (aspect ${"%.3f".format(viewAspect)}) " +
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
     * Zoom — toggles the camera between 1X and 2X digital zoom. All writes are RELATIVE to the
     * raw value read at camera connect ([AutelProductHolder.zoomBaseRaw]), because the SDK's
     * int units are undocumented — baseline*2 means 2X whether the units are a multiplier or
     * x100. Changes the actual encoded feed, so remote viewers see the zoom too, and feeds
     * [TakBridgeHolder.setLiveZoom] so the published SPI FOV cone narrows to match.
     */
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
            toast("Aircraft camera not connected")
            return
        }
        val target = if (irOn) DisplayMode.VISIBLE else DisplayMode.IR
        cam.setDisplayMode(target, camCb("setDisplayMode($target)") {
            irOn = !irOn
            refreshIrButtons()
            AppLog.i(TAG, "display mode now ${if (irOn) "IR" else "VISIBLE"}")
        })
    }

    /** White hot / black hot. Only reachable while [irOn] — the button is hidden otherwise. */
    private fun onIrPaletteTapped() {
        val cam = AutelProductHolder.xt706 ?: return
        val target = if (irBlackHot) IrColor.WhiteHot else IrColor.BlackHot
        cam.setIrColor(target, camCb("setIrColor($target)") {
            irBlackHot = !irBlackHot
            refreshIrButtons()
            AppLog.i(TAG, "IR palette now ${if (irBlackHot) "BlackHot" else "WhiteHot"}")
        })
    }

    /** IR button highlighted when thermal is live; palette button shown only then. */
    private fun refreshIrButtons() {
        irButton.setBackgroundResource(
            if (irOn) R.drawable.bg_ar_pill_active else R.drawable.bg_zoom_pill
        )
        irButton.setTextColor(if (irOn) Color.parseColor("#4CAF50") else Color.WHITE)
        irPaletteButton.visibility = if (irOn) View.VISIBLE else View.GONE
        // Spelled out, not "WHOT"/"BHOT": in the HUD column it has the full column width, and
        // the abbreviations only existed to fit a narrow toolbar pill.
        irPaletteButton.text = if (irBlackHot) "BLACK HOT" else "WHITE HOT"
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
                irBlackHot = c == IrColor.BlackHot
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
            toast("Aircraft camera not connected")
            return
        }
        val base = AutelProductHolder.zoomBaseRaw
        if (base == null || base <= 0) {
            AppLog.w(TAG, "zoom ignored — baseline not learned yet (raw=$base)")
            toast("Camera still initialising — try again in a moment")
            return
        }
        // Everything is RELATIVE to the baseline read at connect, so the SDK's raw units
        // (measured as x100) cancel out and 4X needs no new assumption about them.
        val target = base * level
        cam.setDigitalZoomScale(target, camCb("setDigitalZoomScale($target)") {
            zoomLevel = level
            zoomButton.text = "${level}X"
            // The published FOV cone and the AR projection both narrow with zoom, so
            // they must be told — otherwise AR markers drift as the pilot zooms.
            TakBridgeHolder.setLiveZoom(level.toDouble())
            AppLog.i(TAG, "zoom now ${level}X (raw=$target)")
        })
    }

    /**
     * ISO/shutter readout (read-only; the EV slider itself stays unwired — postponed by the
     * operator until the camera's exposure behaviour is characterised on hardware). Polled,
     * because this SDK pushes no exposure events; every ~2s is fresh enough for a readout
     * whose job is "is the camera picking sane values".
     */
    private fun pollExposureReadout() {
        val cam = AutelProductHolder.xt706 ?: return
        cam.getISO(object : com.autel.common.CallbackWithOneParam<com.autel.common.camera.media.CameraISO> {
            override fun onSuccess(iso: com.autel.common.camera.media.CameraISO?) {
                // Prefer the RAW value; the enum cannot represent what this camera actually does.
                lastIsoLabel = rawIso()?.toString()
                    ?: iso?.name?.removePrefix("ISO_")?.takeIf { it != "UNKNOWN" }
            }
            override fun onFailure(error: AutelError?) { /* readout stays "—" */ }
        })
        cam.getShutter(object : com.autel.common.CallbackWithOneParam<com.autel.common.camera.media.ShutterSpeed> {
            override fun onSuccess(sp: com.autel.common.camera.media.ShutterSpeed?) {
                lastShutterLabel = sp?.let { shutterLabel(it.name) }
            }
            override fun onFailure(error: AutelError?) { /* readout stays "—" */ }
        })
    }

    /**
     * The camera's ACTUAL ISO as an integer, or null if the parsed settings aren't populated.
     *
     * WHY NOT JUST USE getISO(). That returns [com.autel.common.camera.media.CameraISO], an enum
     * holding only whole stops — 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600 (plus a few
     * odd high values). It has NO entry for the 1/3-stop values a camera in auto exposure
     * routinely picks: 125, 160, 250, 320, 500, 640, 1000... The SDK maps every one of those to
     * UNKNOWN, so the HUD read "ISO UNKNOWN" the moment auto-exposure stepped off a whole stop —
     * observed on hardware 2026-08-01, ISO 100 displayed fine and everything above did not.
     *
     * The raw integer is right there in the parsed settings the SDK already maintains
     * (CameraAllSettings.ImageISO.getISO()), it just isn't surfaced on the public camera
     * interface. Reading it loses nothing and survives whatever values future firmware picks,
     * where extending an enum mapping would not.
     *
     * Reaches into com.autel.camera.protocol.protocol20 (SDK internals). Read-only, wrapped, and
     * it degrades to the enum if the shape ever changes — but do not build control paths on it.
     */
    private fun rawIso(): Int? = runCatching {
        com.autel.camera.protocol.protocol20.entity.CameraAllSettingsWithParser.instance()
            ?.cameraAllSettings?.imageISO?.iso?.takeIf { it > 0 }
    }.getOrNull()

    /** "ShutterSpeed_1_60" -> "1/60", "ShutterSpeed_3dot2" -> "3.2\"", "ShutterSpeed_15" -> "15\"". */
    private fun shutterLabel(enumName: String): String? {
        val s = enumName.removePrefix("ShutterSpeed_")
        if (s == "UNKNOWN" || s == enumName) return null
        return if (s.startsWith("1_")) "1/" + s.removePrefix("1_").replace("dot", ".")
        else s.replace("dot", ".") + "\""
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
            toast("Aircraft camera not connected")
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
                // Can't read the mode — try the record anyway rather than refusing; the
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
        cam.startRecordVideo(camCb(if (isRetry) "startRecordVideo(retry)" else "startRecordVideo"))
        handler.postDelayed({
            if (AutelProductHolder.isRecording) return@postDelayed   // camera confirmed it
            if (!isRetry) {
                AppLog.w(TAG, "no RECORD_START ${RECORD_CONFIRM_MS}ms after startRecordVideo — retrying once")
                startRecordVerified(cam, isRetry = true)
            } else {
                AppLog.e(TAG, "recording did not start: camera accepted StartRecording twice " +
                    "but never reported RECORD_START")
                toast("Recording did not start — camera did not confirm")
            }
        }, RECORD_CONFIRM_MS)
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
            toast("Aircraft camera not connected")
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
        if (CrosshairView.accuracyColorFor(pitch, dtedAvailable) ==
            CrosshairView.accuracyPoorColor) {
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
        toast("Can't place a marker: $why")
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
        fpvRthAltitude.setTextColor(
            if (known) Color.parseColor("#B0B0B0") else Color.parseColor("#FFC107"))
    }

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
            // Outside the downloaded box entirely — we genuinely don't know. Shown identically
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
        fpvFaaCeiling.setTextColor(Color.parseColor("#B0B0B0"))
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
            .setAdapter(iconRowAdapter(pins.zip(labels) { p, l -> p.affiliation.res to l })) { _, i ->
                onMarkerRowTapped(pins[i])
            }
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
            toast("Can't move — waiting on GPS + gimbal")
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
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#8A93A0"))
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
            .setTitle(if (blocked == null) "Marker affiliation" else "Can't place yet: $blocked")
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
        private const val TAG = "FlightActivity"

        /** Which SDK custom button drives the quick marker: "A" or "B". Set from the
         *  logged event after a press — see installHardwareButtonListener. */
        /** Controller custom buttons, in the SDK's vocabulary. It has no notion of the
         *  physical "C1"/"C2" labels — the mapping below was confirmed on hardware
         *  2026-08-02 by logging the events a press actually emits. */
        private const val QUICK_MARKER_BUTTON = "B"   // physical C2
        private const val ZOOM_BUTTON = "A"           // physical C1

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
         *  - WIDE (13) covers ~3312 x 4416 m (about 2 x 2.7 miles), so the home point stays on
         *    the map out to ~1656 m laterally — more than three times the 1600 ft (488 m)
         *    max-distance limit. This is the "where am I relative to home and the team" view,
         *    and the default for that reason. Was 14 (~1656 x 2208 m) until 2026-07-31; the
         *    operator wanted more surrounding context, and 14 already cleared the distance
         *    limit so the extra margin costs nothing operationally. Anything at 15 or tighter
         *    loses home at full extension and would defeat the point of this mode.
         *  - NEAR (17) covers ~207 x 276 m: the "what is directly below the aircraft" view.
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
        private const val MAP_ZOOM_WIDE = 13.0
        private const val MAP_ZOOM_NEAR = 17.0
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

        private const val REQUEST_CODE_LOCATION = 4302
    }
}
