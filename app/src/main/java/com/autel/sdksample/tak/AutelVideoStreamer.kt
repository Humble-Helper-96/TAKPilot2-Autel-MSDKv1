package com.autel.sdksample.tak

import android.content.Context
import com.taklite.util.AppLog
import com.pedro.rtsp.rtsp.Protocol
import com.pedro.rtsp.rtsp.RtspClient
import com.pedro.rtsp.utils.ConnectCheckerRtsp

/**
 * AutelVideoStreamer — RTSP push of the flight screen. Port of TAKPilot2's DroneVideoStreamer.
 *
 * **The path: MediaProjection screen capture → H.265 encode → RTSP.** `ScreenCaptureService`
 * obtains the projection and calls [VideoStreamerHolder.startScreenCapture]; [ScreenCaptureEncoder]
 * mirrors the whole flight screen (FPV *plus* HUD, AR markers and map, same as the DJI blueprint)
 * into an encoder input surface. This means the stream does NOT depend on the aircraft: a lost
 * link, a battery swap, or an aircraft that was never connected all leave the push running, and
 * viewers keep seeing the controller instead of the feed going dead.
 *
 * This matters when reasoning about what the team sees: anything on this screen is in their
 * feed. Black bars, aspect changes and overlays are not local cosmetics.
 *
 * (History: an aircraft-camera path once tapped the SDK's [com.autel.sdk.video.AutelCodecListener]
 * for encoded frames and transcoded them. It was superseded by screen capture and, being unreached
 * in production, removed 2026-08-03 — along with a latent H.264/H.265 mis-detection in its
 * parameter-set sniffer. Screen capture reads the already-composited screen, so it needs no second
 * codec tap and cannot contend with the on-screen `AutelCodecView`.)
 *
 * The encoder produces SPS/PPS/VPS before [RtspClient.connect] — connect()'s worker waits up to
 * 5 s for setVideoInfo — so the encoder is started first and connect follows immediately.
 *
 * Push URL is the operator's EXACT path (rtsp://host:port/<path>); creds via setAuthorization,
 * transport via setProtocol. The full UAS-tool-style URL (creds + ?tcp) is advertised in the
 * drone CoT — identical behavior to the DJI original.
 */
class AutelVideoStreamer(
    private val context: Context,
    private val config: VideoConfig,
    private val onStatus: (Boolean, String) -> Unit,
    /** The screen-capture projection. The stream is always a capture of the flight screen. */
    private val mediaProjection: android.media.projection.MediaProjection,
) : ConnectCheckerRtsp {

    data class VideoConfig(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val streamId: String,
        val tcp: Boolean,
        /** Pilot-selected video quality: "low" | "standard" | "high", matching the DJI
         *  blueprint's video_profile pref. Every profile is an on-device encode. */
        val profile: String = "standard",
        /** Pilot-selected codec: "h264" | "h265". See [VideoCodec] for why this is a field
         *  decision rather than a build-time constant. */
        val codec: String = "h264",
    ) {
        val transcodeProfile: TranscodeProfile
            get() = TranscodeProfile.fromPref(profile)

        val videoCodec: VideoCodec
            get() = VideoCodec.fromPref(codec)

        // -Low suffix flows through push/advertise/display URLs alike, so the CoT always
        // points at whichever stream is actually live — full-res and -Low are never both up.
        // Kept for every profile (not just "low") to match the blueprint: the suffix tells the
        // media server this path is already transcoded and should be passed through rather
        // than re-encoded, which is true of all three tiers.
        private fun path(): String = streamId.trim('/') + "-Low"
        fun pushUrl(): String = "rtsp://$host:$port/${path()}"
        fun advertiseUrl(): String {
            val cred = if (username.isNotEmpty()) "${enc(username)}:${enc(password)}@" else ""
            val q = if (tcp) "?tcp" else ""
            return "rtsp://$cred$host:$port/${path()}$q"
        }
        /**
         * The url with the password masked, for the screen and the log.
         *
         * ⚠ **It must distinguish "set" from "empty".** It used to print `user:***@` whenever the
         * USERNAME was non-empty, so a missing password looked identical to a present one — and
         * the Pre-Flight preview, the one place a pilot would check, could not answer the
         * question it exists to answer. A password really was empty on 2026-08-05 and the screen
         * showed stars for it.
         */
        fun urlSafe(): String {
            val who = when {
                username.isEmpty() -> ""
                password.isEmpty() -> "$username:(NO PASSWORD)@"
                else -> "$username:***@"
            }
            val q = if (tcp) "?tcp" else ""
            return "rtsp://$who$host:$port/${path()}$q"
        }
        private fun enc(s: String): String =
            java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }

    private val client = RtspClient(this)
    @Volatile private var streaming = false
    @Volatile private var stopped = false
    private var frameCount = 0

    private var screenEncoder: ScreenCaptureEncoder? = null

    /**
     * True only when the RTSP session is up **and** we have actually pushed video.
     *
     * Both halves are needed. [streaming] alone means the server accepted our session, which
     * says nothing about whether frames are flowing. A pilot reads the LIVE pill to decide
     * whether the team can see what they see, so it must mean bytes are leaving the controller,
     * not that a socket opened.
     */
    val isLive: Boolean get() = streaming && frameCount > 0

    /**
     * Returns false if the stream could not even be attempted, so the caller can drop this
     * instance instead of keeping a dead one. Without that, `streamer != null` stayed true
     * after a failed start and the flight screen's LIVE pill lit up while nothing was being
     * sent — the toolbar claiming the team had video when it did not.
     */
    fun start(): Boolean {
        // Screen capture deliberately does NOT require the aircraft. Mirroring the screen is what
        // makes the push survive a link drop or a battery change: the viewer keeps seeing the
        // controller instead of the feed going dead, and a stream can be brought up before the
        // aircraft is even powered.
        stopped = false

        client.setLogs(false)
        client.setProtocol(if (config.tcp) Protocol.TCP else Protocol.UDP)
        if (config.username.isNotEmpty()) client.setAuthorization(config.username, config.password)
        client.setOnlyVideo(true)
        client.setReTries(10)

        // The encoder must be producing parameter sets BEFORE connect — connect()'s worker waits
        // up to 5s for setVideoInfo.
        val enc = ScreenCaptureEncoder(
            context, mediaProjection, config.transcodeProfile, config.videoCodec,
            onEncoded = { buf, bufInfo ->
                client.sendVideo(buf, bufInfo)
                // isLive gates the LIVE pill on frameCount, so without this the pill would sit
                // on amber forever while a screen capture streamed perfectly well.
                frameCount++
            },
            onParamsReady = { spsB, ppsB, vpsB -> client.setVideoInfo(spsB, ppsB, vpsB) },
        )
        if (!enc.start()) {
            onStatus(false, "Screen capture failed to start")
            return false
        }
        screenEncoder = enc

        client.connect(config.pushUrl())
        AppLog.i(TAG, "push=${config.pushUrl()}  advertise=${config.urlSafe()}" +
                "  [${config.transcodeProfile.name}: screen capture]")
        onStatus(true, "Starting RTSP push → ${config.urlSafe()}")
        return true
    }

    fun stop() {
        stopped = true
        try { client.disconnect() } catch (t: Throwable) { AppLog.w(TAG, "disconnect: ${t.message}") }
        screenEncoder?.release()
        screenEncoder = null
        streaming = false
    }

    // ---- ConnectCheckerRtsp ----

    override fun onConnectionStartedRtsp(rtspUrl: String) { AppLog.i(TAG, "connecting ${config.urlSafe()}") }
    override fun onConnectionSuccessRtsp() {
        streaming = true
        AppLog.i(TAG, "RTSP push connected")
        // Arm the packetiser with a fresh IDR so a viewer joining now gets a picture without
        // waiting out the 2s GOP. Only the screen encoder can be asked on demand — the
        // aircraft's keyframe cadence is not ours to control.
        screenEncoder?.requestSyncFrame()
        onStatus(true, "Streaming → ${config.urlSafe()}")
    }
    override fun onConnectionFailedRtsp(reason: String) {
        AppLog.w(TAG, "connection failed: $reason")
        if (!stopped && client.shouldRetry(reason)) {
            client.reConnect(2000)
        } else {
            streaming = false
            onStatus(false, "Stream failed: $reason")
        }
    }
    override fun onDisconnectRtsp() { streaming = false; AppLog.i(TAG, "disconnected") }
    override fun onAuthErrorRtsp() { streaming = false; onStatus(false, "Stream auth error (check user/pass)") }
    override fun onAuthSuccessRtsp() { AppLog.i(TAG, "auth ok") }
    override fun onNewBitrateRtsp(bitrate: Long) {}

    companion object {
        private const val TAG = "AutelVideoStreamer"
    }
}

/** Process-wide holder so the stream survives screen navigation (1:1 with TAKPilot2). */
object VideoStreamerHolder {
    private var streamer: AutelVideoStreamer? = null

    /** Notified on every start/stop so UI (e.g. the flight-screen play button) refreshes. */
    @JvmField
    var onStateChanged: Runnable? = null
    private fun notifyState() {
        android.os.Handler(android.os.Looper.getMainLooper()).post { onStateChanged?.run() }
    }

    /**
     * What the flight screen's LIVE pill should show.
     *
     *  - [OFF] — nothing running, including a start that failed outright.
     *  - [CONNECTING] — a start was accepted and the RTSP client is connecting or retrying,
     *    but no video has gone out yet.
     *  - [LIVE] — video is genuinely being pushed.
     */
    enum class State { OFF, CONNECTING, LIVE }

    /** Returns false if the stream could not be started at all. */
    fun start(
        context: Context,
        config: AutelVideoStreamer.VideoConfig,
        onStatus: (Boolean, String) -> Unit,
        projection: android.media.projection.MediaProjection,
    ): Boolean {
        streamer?.stop()
        val s = AutelVideoStreamer(context.applicationContext, config, onStatus, projection)
        // Keep the instance ONLY if it started. A failed start used to leave a dead streamer
        // parked here, which made isActive true and lit the LIVE pill with nothing streaming.
        val ok = s.start()
        streamer = if (ok) s else null
        notifyState()
        return ok
    }

    fun stop() {
        streamer?.stop()
        streamer = null
        TakBridgeHolder.setVideoUrl(null)
        notifyState()
    }

    val state: State
        get() {
            val s = streamer ?: return State.OFF
            return if (s.isLive) State.LIVE else State.CONNECTING
        }

    /** True once video is actually going out — what the HUD's "VID" indicator means. */
    val isRunning: Boolean get() = streamer?.isLive == true

    /** A stream is set up and should be torn down by a second tap. NOT "is it working" —
     *  use [state] for anything the pilot reads. */
    val isActive: Boolean get() = streamer != null

    /**
     * Why a start attempt did not result in a stream. Three outcomes, not a boolean: the
     * caller must not tell a pilot to "set up the stream in Pre-Flight Setup" when the stream
     * IS set up and it simply failed to start.
     */
    enum class StartResult { STARTED, NOT_CONFIGURED, FAILED }

    /**
     * Start streaming using the video settings saved by TakConnectActivity, capturing the flight
     * screen via [projection]. Reached from [ScreenCaptureService] once it holds a granted
     * projection (the flight-screen LIVE button requests the projection, which routes here).
     */
    fun startFromPrefs(
        context: Context,
        onStatus: (Boolean, String) -> Unit,
        projection: android.media.projection.MediaProjection,
    ): StartResult {
        val p = context.getSharedPreferences("takpilot2_tak", Context.MODE_PRIVATE)
        val host = p.getString("video_host", "") ?: ""
        val streamId = p.getString("video_streamid", "") ?: ""
        if (host.isEmpty() || streamId.isEmpty()) return StartResult.NOT_CONFIGURED
        val cfg = AutelVideoStreamer.VideoConfig(
            host = host,
            port = p.getInt("video_port", 8554),
            username = p.getString("video_user", "") ?: "",
            // Key must match TakConnectActivity.KEY_V_PASS. Kept as a literal here only because
            // this file has no access to that private constant; if either moves, move both.
            password = p.getString("video_pass", "") ?: "",
            streamId = streamId,
            tcp = p.getBoolean("video_tcp", true),
            profile = p.getString("video_profile", "standard") ?: "standard",
            codec = p.getString("video_codec", VideoCodec.H264.prefValue)
                ?: VideoCodec.H264.prefValue,
        )
        // Only advertise the URL in the drone CoT if the push actually started — telling the
        // team where to watch a stream that never began is worse than saying nothing.
        val ok = start(context, cfg, { st, msg ->
            if (st) TakBridgeHolder.setVideoUrl(cfg.advertiseUrl())
            onStatus(st, msg)
        }, projection)
        return if (ok) StartResult.STARTED else StartResult.FAILED
    }

    /** Entry point for [ScreenCaptureService] once it holds a granted projection. */
    fun startScreenCapture(
        context: Context,
        projection: android.media.projection.MediaProjection,
        onStatus: (Boolean, String) -> Unit,
    ): StartResult = startFromPrefs(context, onStatus, projection)
}
