package com.autel.sdksample.tak

import com.taklite.util.AppLog
import com.autel.common.CallbackWithTwoParams
import com.autel.common.camera.CameraProduct
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

    @Volatile var product: BaseProduct? = null
        private set

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
                MediaStatus.PHOTO_TAKEN_DONE -> photoTakenFlag = true
                else -> { /* mode/update chatter — logged above, no state change */ }
            }
        }
        override fun onFailure(error: AutelError?) {
            AppLog.w(TAG, "media state listener error: ${error?.description}")
        }
    }

    /**
     * The digital-zoom value the camera reported AT CONNECT, in the SDK's raw int units —
     * which are undocumented (could be a plain multiplier, could be x100). Everything zoom
     * does is relative to this ("2X" = baseline*2), so the unknown units cancel out.
     *
     * ⚠ Assumption to verify on hardware (QC list): the camera connects at 1x. If the app
     * restarts while the camera is still zoomed, this captures the zoomed value as "1X".
     * The connect-time value is logged loudly for exactly that check.
     */
    @Volatile var zoomBaseRaw: Int? = null
        private set

    private val cameraChangeListener = object : CallbackWithTwoParams<CameraProduct, AutelBaseCamera> {
        override fun onSuccess(type: CameraProduct?, cam: AutelBaseCamera?) {
            AppLog.i(TAG, "camera changed: $type (${cam?.javaClass?.simpleName ?: "null"})")
            camera = cam
            isRecording = false   // new camera session — state re-learned from its events
            zoomBaseRaw = null
            cam?.setMediaStateListener(mediaStateListener)
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
            // Put the camera into a known auto-exposure mode and re-apply the pilot's saved EV.
            // Done here rather than in FlightActivity so it happens once per camera session
            // regardless of which screen is up — and so a camera that reconnects mid-flight
            // comes back with the pilot's EV rather than silently reverting to Explorer's.
            com.autel.sdksample.TestApplication.getInstance()?.let { ctx ->
                AutelExposureController.applyDefaults(ctx, cam as? AutelXT706)
            }
        }
        override fun onFailure(error: AutelError?) {
            AppLog.w(TAG, "camera change listener error: ${error?.description}")
        }
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
                // Re-arm telemetry subscriptions on every (re)connect — Autel listener
                // registrations don't survive a product cycle.
                TakBridgeHolder.onProductConnected()
                installCameraListener()
                // Bring the foreground service up as soon as we hold the aircraft, whether or
                // not TAK is connected. This is NOT about keeping anything alive: Android only
                // delivers onTaskRemoved to RUNNING services, and that callback is the only
                // hook for "the pilot swiped the app away". Without a service here, holding the
                // aircraft without TAK meant a swipe released nothing and the cached process
                // kept the camera and video channels — see AppTeardown.
                com.autel.sdksample.TestApplication.getInstance()?.let { ctx ->
                    val callsign = ctx.getSharedPreferences("takpilot2_tak", android.content.Context.MODE_PRIVATE)
                        .getString("callsign", "TAKPilot2-EVO2") ?: "TAKPilot2-EVO2"
                    runCatching { TakForegroundService.start(ctx, callsign) }
                        .onFailure { AppLog.w(TAG, "foreground service start failed: ${it.message}") }
                }
                notifyAll(true)
            }

            override fun productDisconnected() {
                AppLog.i(TAG, "productDisconnected")
                product = null
                camera = null
                isRecording = false
                TakBridgeHolder.onProductDisconnected()
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
