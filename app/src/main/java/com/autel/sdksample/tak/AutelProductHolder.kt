package com.autel.sdksample.tak

import com.taklite.util.AppLog
import com.autel.common.CallbackWithNoParam
import com.autel.common.CallbackWithTwoParams
import com.autel.common.camera.CameraProduct
import com.autel.common.camera.base.MediaMode
import com.autel.common.camera.base.MediaStatus
import com.autel.common.error.AutelError
import com.autel.sdk.Autel
import com.autel.sdk.ProductConnectListener
import com.autel.sdk.camera.AutelBaseCamera
import com.autel.sdk.camera.AutelXT706
import com.autel.sdk.product.BaseProduct
import com.autel.sdk.product.Evo2Aircraft
import com.autel.sdk.video.AutelCodec

/**
 * Process-wide holder for the connected Autel aircraft (the Autel-side replacement for
 * DJI's KeyManager singleton pattern used by TAKPilot2).
 *
 * Install once (from [TakPilotHomeActivity] / app start); every TAK component reads the
 * product from here. On connect the telemetry listeners in [AutelTakBridge] are (re)armed.
 */
object AutelProductHolder {
    private const val TAG = "AutelProductHolder"

    /** Attempts to park the camera in VIDEO mode at connect, and the gap between them.
     *  Sized from hardware: the third attempt was the one that stuck, ~3.7s after the camera
     *  first announced itself, so this covers roughly twice that with room to spare. */
    private const val VIDEO_MODE_ATTEMPTS = 8
    private const val VIDEO_MODE_RETRY_MS = 1000L

    @Volatile var product: BaseProduct? = null
        private set

    /**
     * The product we have already run the connect-time arming sequence for.
     *
     * ⚠ Idempotency guard for a flood hazard. `productConnected` can fire more than once on the
     * SAME product — camera-enumeration churn, or the global product listener being re-registered
     * on every `onResume` (Home and Flight both call [install]). The arming sequence includes
     * subscriptions that cannot be de-registered (e.g. AutelAvoidance's visual-setting feed), so
     * re-running it stacks another ~2Hz stream on the fly-controller channel each time — the exact
     * shape of the 2026-08-02 wall strike, through a different door. Arm once per product; a
     * re-fire on the same instance only refreshes observers.
     */
    @Volatile private var armedForProduct: BaseProduct? = null

    /** The EVO II V3 view of the product, or null if not connected / different airframe. */
    val evo2: Evo2Aircraft? get() = product as? Evo2Aircraft

    val codec: AutelCodec? get() = product?.codec

    val isConnected: Boolean get() = product != null

    // ---- Camera (Step 0 of the flight-screen activation plan) ----
    //
    // No synchronous "get camera" exists on this SDK; the camera arrives via
    // AutelCameraManager.setCameraChangeListener, some time after productConnected (the camera
    // boots separately from the flight controller). Cached here, product-scoped, so the flight
    // screen's photo/zoom/REC controls can just read it — same pattern as [product] itself.

    @Volatile var camera: AutelBaseCamera? = null
        private set

    /** The XT70x-family view of the camera (zoom/exposure/display-mode live here — the 640T's
     *  XT709 extends XT706), or null if not connected / an unexpected camera model. */
    val xt706: AutelXT706? get() = camera as? AutelXT706

    /**
     * Live recording state, driven by the camera's own MediaStatus push events
     * (RECORD_START/RECORD_STOP/RECORD_FAILED_*) rather than by what button the pilot last
     * pressed — so the REC pill shows what the CAMERA says it's doing.
     */
    @Volatile var isRecording: Boolean = false
        private set

    /** Set when the camera reports a photo actually saved — lets the flight screen confirm
     *  a shutter press did something. Cleared by the reader. */
    @Volatile var photoTakenFlag: Boolean = false

    // ---- Camera storage ----
    //
    // WHY THIS EXISTS: on 2026-08-06 REC did nothing at all — no error, no toast, no log beyond
    // the tap. The camera was recording to its ~4 GB INTERNAL eMMC, which was full (253 MB of
    // 4084 free), while a 128 GB SD card sat Ready and empty beside it. Having a card in the
    // slot is not the same as the camera USING it, and nothing on either screen said so.
    //
    // Worse, the SDK turns that state into a crash rather than an error: with the target set to
    // the flash card, CameraXT709PreconditionProxy.startRecordVideo dereferences
    // CameraXB015Data.getFlashCardStatus() — populated ONLY by setFlashMemoryCardStateListener,
    // which nothing here registered — and throws NPE straight back out of startRecordVideo. See
    // [armCameraStorage] and FlightActivity.startRecordVerified.

    /** Where the camera actually writes: SD card or internal flash. Null until asked. */
    @Volatile var storageTarget: com.autel.common.camera.media.SaveLocation? = null
        private set
    @Volatile var sdCardState: com.autel.common.camera.base.SDCardState? = null
        private set
    @Volatile var mmcState: com.autel.common.camera.base.MMCState? = null
        private set
    /** Free/total space, MEGABYTES, as the camera reports them. */
    @Volatile var sdFreeMb: Long? = null
        private set
    @Volatile var mmcFreeMb: Long? = null
        private set
    @Volatile var mmcTotalMb: Long? = null
        private set

    /** True when the camera is pointed at internal flash rather than the SD card — the state
     *  that silently loses footage, so the Enter Flight card calls it out. */
    val recordingToInternal: Boolean
        get() = storageTarget == com.autel.common.camera.media.SaveLocation.FLASH_CARD

    private val flashCardStateListener =
        object : com.autel.common.CallbackWithOneParam<com.autel.common.camera.base.MMCState> {
            override fun onSuccess(state: com.autel.common.camera.base.MMCState?) {
                if (state != null && state != mmcState) AppLog.i(TAG, "flash card state: $state")
                mmcState = state
            }
            override fun onFailure(error: AutelError?) {
                AppLog.w(TAG, "flash card state listener error: ${error?.description}")
            }
        }

    /**
     * Registers the flash-card listener and reads the storage target, once per camera session.
     *
     * ⚠ THE LISTENER IS NOT OPTIONAL, and not merely informational. Registering it is what fills
     * the SDK's own FlashCardStatus cache (CameraXT709Impl.setFMCardStateListener →
     * CameraXB015Data.setFlashCardStatus, verified in the aar 2026-08-06). Without it that cache
     * stays null and every startRecordVideo against internal flash throws NPE instead of failing
     * with the real reason. Registering it converts a silent dead button into "card full".
     */
    private fun armCameraStorage(cam: AutelXT706) {
        runCatching { cam.setFlashMemoryCardStateListener(flashCardStateListener) }
            .onFailure { AppLog.w(TAG, "flash card listener install failed: ${it.message}") }
        // Also populates CameraXB015Data.StorageType, which is the flag the record precondition
        // branches on — so this read is load-bearing too, not just for the UI.
        runCatching {
            cam.getAlbumLocation(object :
                com.autel.common.CallbackWithOneParam<com.autel.common.camera.media.SaveLocation> {
                override fun onSuccess(loc: com.autel.common.camera.media.SaveLocation?) {
                    storageTarget = loc
                    AppLog.i(TAG, "camera album location: $loc " +
                        "(sd ${sdCardState ?: "—"} ${sdFreeMb ?: "—"}MB free, " +
                        "internal ${mmcState ?: "—"} ${mmcFreeMb ?: "—"}/${mmcTotalMb ?: "—"}MB)")
                }
                override fun onFailure(error: AutelError?) {
                    AppLog.w(TAG, "getAlbumLocation failed: ${error?.description}")
                }
            })
        }.onFailure { AppLog.w(TAG, "getAlbumLocation threw: ${it.message}") }
    }

    private val mediaStateListener = object : CallbackWithTwoParams<MediaStatus, String> {
        override fun onSuccess(status: MediaStatus?, detail: String?) {
            status ?: return
            AppLog.i(TAG, "camera media status: $status${detail?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""}")
            when (status) {
                MediaStatus.RECORD_START -> isRecording = true
                MediaStatus.RECORD_STOP,
                MediaStatus.RECORD_FAILED_WRITE_ERROR,
                MediaStatus.RECORD_FAILED_SDCARD_REMOVED,
                MediaStatus.RECORD_BUFFER_FULL -> isRecording = false
                MediaStatus.PHOTO_TAKEN_DONE -> {
                    photoTakenFlag = true
                    lastPhotoDoneMs = android.os.SystemClock.elapsedRealtime()
                }
                else -> { /* mode/update chatter — logged above, no state change */ }
            }
        }
        override fun onFailure(error: AutelError?) {
            val desc = error?.description ?: "unknown"
            val doneMs = lastPhotoDoneMs
            val sincePhotoMs =
                if (doneMs == 0L) Long.MAX_VALUE
                else android.os.SystemClock.elapsedRealtime() - doneMs
            if (isSpuriousPhotoFailure(desc, sincePhotoMs)) {
                // This firmware double-reports: DONE, then a failure ~20 ms later for the
                // same capture. The photo is on the card. INFO, not WARN — a WARN here
                // sends someone hunting for a photo that was never lost.
                AppLog.i(TAG, "spurious SDK photo-failure callback ${sincePhotoMs}ms after " +
                    "successful capture (ignored): $desc")
                return
            }
            AppLog.w(TAG, "media state listener error: $desc")
        }
    }

    /** When the camera last reported PHOTO_TAKEN_DONE (elapsedRealtime), 0 = never. See
     *  [isSpuriousPhotoFailure]. */
    @Volatile private var lastPhotoDoneMs = 0L

    /**
     * The digital-zoom value the camera reported AT CONNECT, in the SDK's raw int units.
     *
     * ⚠ DIAGNOSTIC ONLY — nothing computes with this any more. It used to be the baseline that
     * every zoom level multiplied ("2X" = baseline*2), which was necessary while the raw units
     * were undocumented, and which carried the assumption that the camera always connects at 1x.
     * That assumption failed exactly as its old warning predicted: reconnect while zoomed and the
     * zoomed value gets banked as "1X", after which no level is what it says. Observed 2026-08-04
     * at a true 4x.
     *
     * The units are now known — HUNDREDTHS, 100 = 1.0x, established from focalLength tracking
     * zoomScale linearly (100/0.47, 400/1.90, 800/3.79, 1600/7.58) — so [FlightActivity.applyZoom]
     * sets an absolute value and the live ratio comes from the camera's own status push. Kept
     * because the connect-time value is still worth having in the log when zoom misbehaves.
     */
    @Volatile var zoomBaseRaw: Int? = null
        private set

    /**
     * Live camera FOV as REPORTED BY THE CAMERA, degrees at the current zoom/lens — or null until
     * the camera has pushed a status packet (or if this firmware never populates it).
     *
     * Verified in the aar, not assumed: `XT706CameraInfo.getHorizontalFOV()` is backed by
     * `CameraSystemStatus.FovH` (an int in TENTHS of a degree) and `getFovH()` compiles to
     * `i2f; ldc 10.0f; fdiv` — so the SDK already divides and these arrive as degrees. The field is
     * Gson-populated from the camera's pushed `SystemStatus` JSON; nothing in the SDK ever calls
     * `setFovH`, so it is real telemetry rather than a stub.
     *
     * ⚠ OBSERVATION ONLY for now. Nothing reads these into the AR projection yet — the point of
     * this pass is to find out what the firmware actually reports on THIS airframe before the
     * hand-calibrated constants are retired in favour of it. Two things to learn from the log:
     * whether the values are sane and non-zero, and whether they already track digital zoom (if
     * they do, the zoom narrowing in [AutelTakBridge.zoomedFov] must not be applied on top).
     */
    @Volatile var liveHFovDeg: Float? = null
        private set
    @Volatile var liveVFovDeg: Float? = null
        private set

    /** Last camera-info values we logged, so the ~2Hz push feed does not flood the log. */
    private var lastLoggedCamInfo: String? = null

    private val cameraInfoListener = object : com.autel.common.CallbackWithOneParam<
        com.autel.common.camera.XT706.XT706CameraInfo> {
        override fun onSuccess(info: com.autel.common.camera.XT706.XT706CameraInfo?) {
            info ?: return
            val h = info.horizontalFOV
            val v = info.verticalFOV
            liveHFovDeg = h.takeIf { it.isFinite() && it > 0f }
            liveVFovDeg = v.takeIf { it.isFinite() && it > 0f }

            // Storage, off the same ~2Hz push that already carries the FOV — no extra polling for
            // it. Only the free/used figures and card health come from here; WHICH storage the
            // camera writes to is not in this object and has to be read separately, see
            // [armCameraStorage].
            runCatching {
                sdCardState = info.sdCardState
                mmcState = info.mmcState
                sdFreeMb = info.sDcardFreeSpace
                mmcFreeMb = info.mmcFreeSpace
                mmcTotalMb = info.mmcTotalSpace
            }

            // Hand the camera's own numbers to the FOV model. Sanity-gated inside — a camera that
            // has not finished booting reports 0, and an FOV of 0 sends every marker to infinity.
            //
            // The FOV reported here is the LENS's field at 1x and does NOT include digital zoom
            // (verified 2026-08-04: fov held at 65.8x39.9 while zoomScaleRaw ran 100/400/800/1600),
            // so the zoom narrowing still has to be applied on top of it.
            //
            // It DOES change with the lens — the thermal reports 33.0x26.0 at aspect 1.283 (5:4).
            // That makes the EO/IR constants and the activeLens plumbing redundant: the camera
            // says which field it is actually showing, whatever is on screen.
            TakBridgeHolder.setLiveCameraFov(h.toDouble())

            // ZOOM, FROM AN ABSOLUTE SOURCE. zoomScale is in HUNDREDTHS (100 = 1.0x) — established
            // from focalLength tracking it linearly: 100/0.47, 400/1.90, 800/3.79, 1600/7.58.
            //
            // This replaces learning a baseline at connect (see zoomBaseRaw), which was only ever
            // necessary because the units were undocumented, and which BREAKS whenever the app
            // restarts while the camera is still zoomed: it captures the zoomed value as "1X" and
            // every label is then off by that factor. Hit for real 2026-08-04 — the app reconnected
            // at a true 4x, called it 1X, and could no longer zoom out. An absolute ratio has no
            // baseline to get wrong.
            val ratio = info.zoomScale / 100.0
            if (ratio.isFinite() && ratio >= 1.0) TakBridgeHolder.setLiveZoom(ratio)

            // Aspect implied by the reported pair, in TANGENT space — the only comparison that
            // means anything for a rectilinear lens. If this matches the live video aspect the
            // camera is describing the STREAM; if it does not, it is describing the SENSOR and
            // the vertical has to be re-derived from the horizontal on our side.
            val implied = if (h > 0f && v > 0f)
                Math.tan(Math.toRadians(h / 2.0)) / Math.tan(Math.toRadians(v / 2.0)) else Double.NaN

            val line = "cam info: fov=%.1fx%.1f (implied aspect %.3f) zoomScaleRaw=%d focal=%.2f px=%.2f mode=%s"
                .format(h, v, implied, info.zoomScale, info.focalLength, info.pixelSize, info.mediaMode)
            if (line != lastLoggedCamInfo) {
                lastLoggedCamInfo = line
                AppLog.i(TAG, line)
            }
        }
        override fun onFailure(error: AutelError?) {
            AppLog.w(TAG, "camera info listener error: ${error?.description}")
        }
    }

    private val cameraChangeListener = object : CallbackWithTwoParams<CameraProduct, AutelBaseCamera> {
        override fun onSuccess(type: CameraProduct?, cam: AutelBaseCamera?) {
            AppLog.i(TAG, "camera changed: $type (${cam?.javaClass?.simpleName ?: "null"})")
            camera = cam
            isRecording = false   // new camera session — state re-learned from its events
            zoomBaseRaw = null
            liveHFovDeg = null; liveVFovDeg = null; lastLoggedCamInfo = null
            storageTarget = null; sdCardState = null; mmcState = null
            sdFreeMb = null; mmcFreeMb = null; mmcTotalMb = null
            cam?.setMediaStateListener(mediaStateListener)
            // Storage FIRST among the XT706 calls: until this has run, a REC press against
            // internal flash throws inside the SDK rather than reporting anything. See
            // [armCameraStorage].
            (cam as? AutelXT706)?.let { armCameraStorage(it) }
            // Real FOV straight from the camera — see [liveHFovDeg]. Registered here rather than
            // from the flight screen so the numbers are in the log for a bench check, with no
            // aircraft flown and no UI open.
            (cam as? AutelXT706)?.setInfoListener(cameraInfoListener)
            // Learn the zoom units by READING before anything ever writes — see zoomBaseRaw.
            (cam as? AutelXT706)?.getDigitalZoomScale(
                object : com.autel.common.CallbackWithOneParam<Int> {
                    override fun onSuccess(raw: Int?) {
                        zoomBaseRaw = raw
                        AppLog.i(TAG, "digital zoom at connect (raw units, treated as 1X): $raw")
                    }
                    override fun onFailure(error: AutelError?) {
                        AppLog.w(TAG, "getDigitalZoomScale failed: ${error?.description}")
                    }
                })
            // PUT THE CAMERA IN VIDEO MODE AT CONNECT, before the pilot touches anything.
            //
            // This is what makes "the picture never moves when you record" actually true. The
            // camera REMEMBERS its last mode across power cycles, and this app used to only ever
            // change mode in reaction to a button press — so the flight screen came up in
            // whatever mode the camera happened to be left in. If that was photo mode (4:3), the
            // first REC press switched to video (16:9) and the picture visibly zoomed, which is
            // exactly the thing the resting-mode design was supposed to prevent.
            //
            // Recording is the frequent action and the one the TAK team watches (their feed is a
            // screen capture of this display), so the camera starts where REC needs it and stays
            // there. The photo path dips to SINGLE and restores VIDEO — see FlightActivity.
            // GUARDED on the camera being real. This listener fires first with UnknownCamera —
            // the SDK's null-placeholder, delivered while the camera is still being identified
            // (it is the same object behind this port's original "camera enumerates as UNKNOWN"
            // bug). EVERY method on it fails with "the communication to the aircraft has not
            // been built up", so calling through it is never useful and only produces log noise
            // that looks like a fault. Measured 2026-08-01: two placeholder callbacks failed,
            // then the real CameraXT709 arrived and the very same call succeeded first try.
            //
            // The retry behind this is for GENUINE transient failures, not for the placeholder.
            (cam as? AutelXT706)?.let { setVideoModeWithRetry(it, attempt = 1) }

            // DO NOT re-add setAspectRatio(Aspect_16_9) here. Tried 2026-08-01 to make photo
            // mode send the same 16:9 shape as video mode, so the picture would stop changing
            // when the pilot shoots or records. The SDK reported SUCCESS and the camera ignored
            // it completely: the preview stayed 1280x960, and stills before and after the call
            // were both 4000x3000 (measured off the camera's own file server, not inferred).
            // This camera answers status 0 for things it does not do — the same way it accepts
            // StartRecording it will not honour (see FlightActivity.startRecordVerified).
            //
            // There is also no 4:3 option in VideoResolution to attack it from the other side.
            // Equalising the modes has to be done on OUR side, off real FOV numbers from
            // XT706CameraInfo.setInfoListener — see the flight screen's video-fill note.

            // Put the camera into a known auto-exposure mode and re-apply the pilot's saved EV.
            // Done here rather than in FlightActivity so it happens once per camera session
            // regardless of which screen is up — and so a camera that reconnects mid-flight
            // comes back with the pilot's EV rather than silently reverting to Explorer's.
            com.autel.sdksample.TestApplication.getInstance()?.let { ctx ->
                AutelExposureController.applyDefaults(ctx, cam as? AutelXT706)
            }

            // TELL THE SCREENS THE CAMERA IS REAL NOW.
            //
            // A screen that wants to know what mode the camera is IN cannot ask at onResume: on
            // a cold start the flight screen is up long before the aircraft is. Measured on
            // 2026-08-31 — onResume at 09:51:31, productConnected at 10:02:25, eleven minutes
            // later. The read ran against no camera, returned, and nothing asked again, so the
            // buttons said "visible" while the aircraft streamed thermal until the pilot cycled
            // the mode by hand.
            //
            // productConnected is NOT the right moment either: this same listener fires first
            // with UnknownCamera (measured 3 s before the real one), and every call on that
            // placeholder fails. This point is the first at which a camera read is worth making.
            (cam as? AutelXT706)?.let { notifyCameraReady() }
        }
        override fun onFailure(error: AutelError?) {
            AppLog.w(TAG, "camera change listener error: ${error?.description}")
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Unlocks UPWARD gimbal tilt, so the pilot can look above the horizon.
     *
     * The EVO II ships with the upward pitch limit closed, capping the gimbal at level. This
     * sends the SDK's `setGimbalLimitUpward(true)`.
     *
     * THE ARGUMENT SENSE IS VERIFIED, NOT ASSUMED. The public method name is ambiguous — "limit
     * upward" could plausibly mean "apply a limit". Traced through the aar 2026-08-01: the SDK's
     * own internal parameter is `isOpen`, and GimbalManager2 sends GimbalCmdType
     * .CMD_SET_PITCH_LIMIT_UPWARD with data 1 for true / 0 for false. So true = limit OPEN =
     * looking up allowed.
     *
     * ⚠ CONSEQUENCE FOR MARKER DROPS: above the horizon the camera ray never meets the ground,
     * so there is no look-point to compute. CameraSlantPoint falls back to a FIXED 300m range
     * along the bearing (see its `depression > 1.0` guard) — a placeholder, not a real solution.
     * A marker or SPI published while looking up is therefore fiction. The crosshair does turn
     * red (accuracyColorFor treats any near-level pitch as POOR), but nothing currently BLOCKS
     * the drop. Worth suppressing SPI publication above the horizon rather than sending the TAK
     * team a look-point that does not exist.
     */
    private fun unlockUpwardGimbal(attempt: Int = 1) {
        val gimbal = evo2?.gimbal ?: return
        gimbal.setGimbalLimitUpward(true, object : CallbackWithNoParam {
            override fun onSuccess() {
                AppLog.i(TAG, "gimbal upward tilt unlocked (attempt $attempt)")
            }
            override fun onFailure(error: AutelError?) {
                // Same not-ready-yet behaviour as the camera calls, so same treatment.
                if (attempt >= VIDEO_MODE_ATTEMPTS) {
                    AppLog.w(TAG, "gimbal upward unlock failed after $attempt attempts " +
                        "(${error?.description}) — the gimbal will stop at level")
                    return
                }
                mainHandler.postDelayed({ unlockUpwardGimbal(attempt + 1) }, VIDEO_MODE_RETRY_MS)
            }
        })
    }

    /**
     * Parks the camera in VIDEO mode, retrying if it genuinely refuses.
     *
     * Only ever called with a REAL camera — see the guard at the call site. On hardware the real
     * camera accepted this on the first attempt; the retry exists for transient refusals, not
     * for the placeholder-camera case, which the guard handles instead.
     *
     * Gives up after [VIDEO_MODE_ATTEMPTS] rather than retrying forever: if the camera is still
     * refusing after ~8s something else is wrong, and a retry loop that never ends would hide it.
     * Bails early if the camera has been swapped or disconnected underneath us.
     */
    private fun setVideoModeWithRetry(cam: AutelBaseCamera, attempt: Int) {
        if (cam !== camera) return                          // stale attempt, camera moved on
        cam.setMediaMode(MediaMode.VIDEO, object : CallbackWithNoParam {
            override fun onSuccess() {
                AppLog.i(TAG, "camera set to VIDEO mode at connect (attempt $attempt)")
            }
            override fun onFailure(error: AutelError?) {
                if (attempt >= VIDEO_MODE_ATTEMPTS) {
                    // The pilot will see the picture jump on their first REC. Say so loudly
                    // rather than leaving it to be rediscovered in the field.
                    AppLog.e(TAG, "camera would not go to VIDEO mode after $attempt attempts " +
                        "(${error?.description}) — the picture will jump on first REC")
                    return
                }
                AppLog.i(TAG, "setMediaMode(VIDEO) attempt $attempt not ready " +
                    "(${error?.description}) — retrying")
                mainHandler.postDelayed(
                    { setVideoModeWithRetry(cam, attempt + 1) }, VIDEO_MODE_RETRY_MS)
            }
        })
    }

    /** Hooks the camera-change listener on the current product. Called from [install] and on
     *  every productConnected — the camera manager belongs to the product, so a new product
     *  means a new registration. */
    private fun installCameraListener() {
        runCatching { product?.cameraManager?.setCameraChangeListener(cameraChangeListener) }
            .onFailure { AppLog.w(TAG, "camera listener install failed: ${it.message}") }
    }

    private val listeners = ArrayList<(Boolean) -> Unit>()
    // Single listener instance — re-registering it is harmless, and re-registration is
    // REQUIRED: Autel.setProductConnectListener is a global single slot, and the stock
    // sample's ProductActivity (reachable via "SDK Test Tools") overwrites it. Home and
    // Flight re-install on every onResume to reclaim the slot.
    private val connectListener = object : ProductConnectListener {
            override fun productConnected(baseProduct: BaseProduct?) {
                AppLog.i(TAG, "productConnected: ${baseProduct?.type}")
                product = baseProduct
                // Arm ONCE per product. A re-fire on the same instance (camera churn, or the
                // global listener being re-registered on onResume) must not re-run the arming
                // sequence — see armedForProduct. Observers are still refreshed so the UI stays
                // current; only the subscription/enforcement work is skipped.
                if (baseProduct != null && baseProduct === armedForProduct) {
                    AppLog.i(TAG, "productConnected re-fired for the same product — already armed")
                    notifyAll(true)
                    return
                }
                armedForProduct = baseProduct
                // Re-arm telemetry subscriptions on every (re)connect — Autel listener
                // registrations don't survive a product cycle.
                TakBridgeHolder.onProductConnected()
                installCameraListener()
                unlockUpwardGimbal()
                AutelAvoidance.onProductConnected()
                // Push the pilot's saved control response and stick mode. THIS is what
                // was missing: the values were only applied when the Pre-Flight toggle was
                // touched, so a fresh boot flew with whatever the controller happened to
                // hold. Delayed for the same not-ready-yet window the camera calls hit.
                com.autel.sdksample.TestApplication.getInstance()?.let { ctx ->
                    mainHandler.postDelayed({
                        AutelControlRates.applyAtConnect(ctx)
                        AutelAvoidance.applyAtConnect(ctx)
                        // Flight-safety limits belong here, with the other at-connect settings —
                        // NOT in AutelTakBridge where they used to live. Latched on the TAK
                        // session, they were never applied at all without a TAK server, and never
                        // re-applied after an aircraft reconnect. See
                        // FlightLimitsController.applyAtConnect for what that cost on 2026-08-02.
                        FlightLimitsController.applyAtConnect(ctx)
                    }, 4500)
                    // Then ask the aircraft what RTH altitude it ACTUALLY holds, for the flight
                    // HUD. Deliberately after the writes above have had time to land, and read
                    // once rather than polled — the fly-controller channel is the one that must
                    // stay quiet. See FlightLimitsController.aircraftReturnHeightM.
                    mainHandler.postDelayed({ FlightLimitsController.refreshReturnHeight() }, 9000)
                }
                // Read-only snapshot of everything the SDK exposes, logged once per
                // connect. Delayed so the fly controller and RC are actually answering —
                // the same not-ready-yet window the camera calls hit.
                //
                // 15s, not 4s. MEASURED 2026-08-02: at 4s this lands mid camera-enumeration
                // (XT709 came up at T+4.1s, gimbal unlock at T+11s, the camera's own
                // getDigitalZoomScale timed out at T+14s) and every fly-controller read in the
                // dump expired together on the shared 10s timeout. The vision and RC reads,
                // which do not share that path, answered in under 200ms. Waiting until camera
                // init is done is the whole fix; AircraftSettingsDump also retries once.
                mainHandler.postDelayed({ AircraftSettingsDump.dumpOnce() }, 15000)
                // Bring the foreground service up as soon as we hold the aircraft, whether or
                // not TAK is connected. This is NOT about keeping anything alive: Android only
                // delivers onTaskRemoved to RUNNING services, and that callback is the only
                // hook for "the pilot swiped the app away". Without a service here, holding the
                // aircraft without TAK meant a swipe released nothing and the cached process
                // kept the camera and video channels — see AppTeardown.
                com.autel.sdksample.TestApplication.getInstance()?.let { ctx ->
                    runCatching { TakForegroundService.start(ctx, TakForegroundService.callsignFor(ctx)) }
                        .onFailure { AppLog.w(TAG, "foreground service start failed: ${it.message}") }
                }
                notifyAll(true)
            }

            override fun productDisconnected() {
                AppLog.i(TAG, "productDisconnected")
                product = null
                armedForProduct = null
                camera = null
                isRecording = false
                TakBridgeHolder.onProductDisconnected()
                AutelAvoidance.onProductDisconnected()
                AutelControlRates.onProductDisconnected()
                AircraftSettingsDump.onProductDisconnected()
                FlightLimitsController.onProductDisconnected()
                AutelLights.onProductDisconnected()
                notifyAll(false)
            }
        }

    /** Wires (or re-wires) [Autel.setProductConnectListener]. Call from onResume. */
    @Synchronized
    fun install() {
        Autel.setProductConnectListener(connectListener)
        installCameraListener()
    }

    /**
     * Releases the aircraft link: drops every listener and tears the SDK down via
     * [Autel.destroy], which calls SDKInitHelper.detach() internally.
     *
     * **This is what makes "closed" mean closed.** The SDK's connection lives at PROCESS scope,
     * not activity scope, so an app whose task has been swiped away keeps holding the aircraft's
     * camera and video channels while Android caches the process. Those channels are
     * single-client: Autel Explorer showed a grey screen because our swiped-away app still owned
     * the camera (observed 2026-08-02). Nothing on either screen tells the pilot why.
     */
    @Synchronized
    fun release() {
        AppLog.i(TAG, "releasing aircraft link (listeners + SDK)")
        runCatching { product?.cameraManager?.setCameraChangeListener(null) }
        runCatching { camera?.setMediaStateListener(null) }
        runCatching { Autel.setProductConnectListener(null) }
        runCatching { Autel.destroy() }
        camera = null
        product = null
        zoomBaseRaw = null
        isRecording = false
        synchronized(listeners) { listeners.clear() }
        synchronized(cameraReadyListeners) { cameraReadyListeners.clear() }
    }

    /**
     * Observers of "a real camera is now attached and answering". See the call to
     * [notifyCameraReady] for why this is a different event from [addListener]'s connect.
     */
    private val cameraReadyListeners = ArrayList<() -> Unit>()

    fun addCameraReadyListener(l: () -> Unit) {
        synchronized(cameraReadyListeners) { cameraReadyListeners.add(l) }
    }

    fun removeCameraReadyListener(l: () -> Unit) {
        synchronized(cameraReadyListeners) { cameraReadyListeners.remove(l) }
    }

    private fun notifyCameraReady() {
        val copy = synchronized(cameraReadyListeners) { cameraReadyListeners.toList() }
        mainHandler.post { copy.forEach { runCatching { it() } } }
    }

    fun addListener(l: (Boolean) -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: (Boolean) -> Unit) { synchronized(listeners) { listeners.remove(l) } }

    private fun notifyAll(connected: Boolean) {
        val copy = synchronized(listeners) { listeners.toList() }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            copy.forEach { runCatching { it(connected) } }
        }
    }
}
