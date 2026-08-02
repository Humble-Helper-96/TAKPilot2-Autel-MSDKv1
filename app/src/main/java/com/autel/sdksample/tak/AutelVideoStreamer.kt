package com.autel.sdksample.tak

import android.content.Context
import android.media.MediaCodec
import android.os.SystemClock
import com.taklite.util.AppLog
import com.autel.common.error.AutelError
import com.autel.sdk.video.AutelCodec
import com.autel.sdk.video.AutelCodecListener
import com.pedro.rtsp.rtsp.Protocol
import com.pedro.rtsp.rtsp.RtspClient
import com.pedro.rtsp.utils.ConnectCheckerRtsp
import java.nio.ByteBuffer

/**
 * AutelVideoStreamer — RTSP push of the EVO II camera. Port of TAKPilot2's
 * DroneVideoStreamer, but architecturally better on Autel:
 *
 * **Current path: aircraft frames → decode → scale → H.265 re-encode → RTSP.** Autel MSDK
 * v1.5's [AutelCodecListener] hands us the aircraft's encoded Annex-B frames directly
 * ([AutelCodecListener.onFrameStream]) rather than only a decode surface, but every quality
 * profile still transcodes them down to a link-friendly size via [LowBandwidthTranscoder].
 *
 * (An earlier revision of this class was a true passthrough and this doc still claimed "zero
 * transcode, zero quality loss, near-zero CPU" long after profiles made that false. Corrected
 * 2026-07-31. Do not trust that claim if it reappears.)
 *
 * **STALE COMMENT REMOVED (2026-08-01).** This used to say screen capture was a "known gap"
 * still to be ported. It is NOT a gap — it is DONE and it is the live path: `ScreenCaptureService`
 * obtains the MediaProjection and calls [VideoStreamerHolder.startScreenCapture], so the team
 * sees the FPV *plus* HUD, AR markers and map, same as the DJI blueprint. The camera-feed path
 * below (`mediaProjection == null`) is the fallback, not the norm.
 *
 * This matters when reasoning about what the team sees: anything on this screen is in their
 * feed. Black bars, aspect changes and overlays are not local cosmetics. The comment cost real
 * debugging time on 2026-08-01 because the constructor's `mediaProjection = null` DEFAULT reads
 * like the camera path is the only one — check the CALL SITE, not the default.
 *
 * SPS/PPS (and VPS if the feed turns out to be H.265) are sniffed out of the byte stream;
 * [RtspClient.connect] blocks its worker up to 5 s waiting for them, so we register the
 * codec listener first and connect immediately after.
 *
 * Push URL is the operator's EXACT path (rtsp://host:port/<path>); creds via
 * setAuthorization, transport via setProtocol. The full UAS-tool-style URL (creds + ?tcp)
 * is advertised in the drone CoT — identical behavior to the DJI original.
 */
class AutelVideoStreamer(
    private val context: Context,
    private val config: VideoConfig,
    private val onStatus: (Boolean, String) -> Unit,
    /** When present, the stream is a capture of the flight screen rather than the aircraft's
     *  camera feed. See [screenMode]. */
    private val mediaProjection: android.media.projection.MediaProjection? = null,
) : ConnectCheckerRtsp {

    data class VideoConfig(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val streamId: String,
        val tcp: Boolean,
        /** Pilot-selected video quality: "low" | "standard" | "high", matching the DJI
         *  blueprint's video_profile pref. Every profile is an on-device transcode. */
        val profile: String = "standard",
    ) {
        val transcodeProfile: LowBandwidthTranscoder.TranscodeProfile
            get() = LowBandwidthTranscoder.TranscodeProfile.fromPref(profile)

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
        fun urlSafe(): String {
            val who = if (username.isNotEmpty()) "$username:***@" else ""
            val q = if (tcp) "?tcp" else ""
            return "rtsp://$who$host:$port/${path()}$q"
        }
        private fun enc(s: String): String =
            java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }

    private val client = RtspClient(this)
    private var codec: AutelCodec? = null
    @Volatile private var streaming = false
    @Volatile private var paramsSet = false
    @Volatile private var stopped = false
    private var frameCount = 0
    private var frameBytesSinceLog = 0L
    private var startNs = 0L

    // Only present in low-bandwidth mode — decodes the source stream and re-encodes it
    // as a small H.264 stream instead of passing the source bytes straight through.
    private var transcoder: LowBandwidthTranscoder? = null
    private var screenEncoder: ScreenCaptureEncoder? = null

    // Sniffed parameter sets (kept WITH their Annex-B start codes; RootEncoder strips them).
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var vps: ByteArray? = null   // non-null only if the feed is H.265
    private var sawHevcNal = false

    /**
     * True only when the RTSP session is up **and** we have actually pushed video.
     *
     * Both halves are needed. [streaming] alone means the server accepted our session, which
     * says nothing about whether the aircraft is producing frames — the transcoder does not
     * even start until SPS/PPS have been sniffed out of the aircraft's stream. A pilot reads
     * the LIVE pill to decide whether the team can see what they see, so it must mean bytes
     * are leaving the controller, not that a socket opened.
     */
    val isLive: Boolean get() = streaming && frameCount > 0

    /** Screen capture whenever a projection was granted; aircraft-camera passthrough otherwise. */
    private val screenMode: Boolean get() = mediaProjection != null

    /**
     * Returns false if the stream could not even be attempted, so the caller can drop this
     * instance instead of keeping a dead one. Without that, `streamer != null` stayed true
     * after a failed start and the flight screen's LIVE pill lit up while nothing was being
     * sent — the toolbar claiming the team had video when it did not.
     */
    fun start(): Boolean {
        // Screen mode deliberately does NOT require the aircraft. Mirroring the screen is what
        // makes the push survive a link drop or a battery change: the viewer keeps seeing the
        // controller instead of the feed going dead, and a stream can be brought up before the
        // aircraft is even powered. Requiring a codec here would throw that away.
        val c = AutelProductHolder.codec
        if (!screenMode && c == null) {
            onStatus(false, "Aircraft not connected (no video source)")
            return false
        }
        codec = c
        stopped = false
        startNs = System.nanoTime()

        client.setLogs(false)
        client.setProtocol(if (config.tcp) Protocol.TCP else Protocol.UDP)
        if (config.username.isNotEmpty()) client.setAuthorization(config.username, config.password)
        client.setOnlyVideo(true)
        client.setReTries(10)

        // Whichever source is in play, it must be producing parameter sets BEFORE connect —
        // connect()'s worker waits up to 5s for setVideoInfo.
        if (screenMode) {
            val enc = ScreenCaptureEncoder(
                context, mediaProjection!!, config.transcodeProfile,
                onEncoded = { buf, bufInfo ->
                    client.sendVideo(buf, bufInfo)
                    // Count here too, not just on the aircraft path: isLive gates the LIVE
                    // pill on frameCount, so without this the pill would sit on amber forever
                    // while a screen capture streamed perfectly well.
                    frameCount++
                },
                onParamsReady = { spsB, ppsB, vpsB -> client.setVideoInfo(spsB, ppsB, vpsB) },
            )
            if (!enc.start()) {
                onStatus(false, "Screen capture failed to start")
                return false
            }
            screenEncoder = enc
        } else {
            c!!.setCodecListener(codecListener, null)
        }
        client.connect(config.pushUrl())
        AppLog.i(TAG, "push=${config.pushUrl()}  advertise=${config.urlSafe()}" +
                "  [${config.transcodeProfile.name}: ${if (screenMode) "screen capture" else "transcoding"}]")
        onStatus(true, "Starting RTSP push → ${config.urlSafe()}")
        return true
    }

    fun stop() {
        stopped = true
        try { codec?.cancel() } catch (t: Throwable) { AppLog.w(TAG, "codec cancel: ${t.message}") }
        codec = null
        try { client.disconnect() } catch (t: Throwable) { AppLog.w(TAG, "disconnect: ${t.message}") }
        transcoder?.release()
        transcoder = null
        screenEncoder?.release()
        screenEncoder = null
        streaming = false
        paramsSet = false
        sps = null; pps = null; vps = null; sawHevcNal = false
    }

    // ---- Frame path ----

    private val codecListener = object : AutelCodecListener {
        override fun onFrameStream(videoBuffer: ByteArray?, isIFrame: Boolean, size: Int, pts: Long) {
            if (stopped || videoBuffer == null || size <= 4) return
            try {
                if (!paramsSet) {
                    sniffParameterSets(videoBuffer, size)
                    val s = sps; val p = pps
                    if (s != null && p != null && (!sawHevcNal || vps != null)) {
                        run {
                            // The -Low stream's SPS/PPS come from OUR encoder, not the
                            // source's — client.setVideoInfo() is called from onParamsReady
                            // once the transcoder's encoder actually produces them.
                            transcoder = LowBandwidthTranscoder(
                                isHevc = sawHevcNal,
                                profile = config.transcodeProfile,
                                onEncoded = { buf, bufInfo -> client.sendVideo(buf, bufInfo) },
                                // The VPS is what tells RtspClient this is H.265 — it picks
                                // H265Packet + the matching SDP only when this is non-null.
                                onParamsReady = { spsB, ppsB, vpsB ->
                                    client.setVideoInfo(spsB, ppsB, vpsB)
                                },
                            )
                            AppLog.i(TAG, "transcoder started [${config.transcodeProfile.name}] (source " +
                                    "${if (sawHevcNal) "H.265" else "H.264"})")
                        }
                        paramsSet = true
                        AppLog.i(TAG, "parameter sets found (${if (vps != null) "H.265" else "H.264"}); " +
                            "sps=${s.size}B pps=${p.size}B" + (vps?.let { " vps=${it.size}B" } ?: ""))
                    } else return   // keep waiting for a keyframe carrying the params
                }

                // Every profile is a transcode now (Low/Standard/High), matching the DJI
                // blueprint's video-quality choice — so frames always go through the
                // transcoder rather than being pushed straight out. The raw-passthrough branch
                // that used to live here is gone with the old lowBandwidth boolean; if a
                // full-resolution passthrough tier is ever wanted back, it belongs as a fourth
                // profile rather than a parallel code path.
                transcoder?.submit(videoBuffer, size, isIFrame)

                // Periodic throughput summary (not per-frame — this callback can run at 30fps).
                // Counts SOURCE frames received; the transcoder logs its own (lower-rate)
                // output throughput separately.
                frameCount++
                frameBytesSinceLog += size
                if (frameCount % 150 == 0) {
                    AppLog.v(TAG, "video: $frameCount frames pushed, ${frameBytesSinceLog / 1024}KB in last 150")
                    frameBytesSinceLog = 0
                }
            } catch (t: Throwable) {
                AppLog.w(TAG, "frame push failed: ${t.message}")
            }
        }

        override fun onCanceled() { AppLog.i(TAG, "codec listener canceled") }

        override fun onFailure(error: AutelError?) {
            AppLog.w(TAG, "codec failure: ${error?.description}")
            onStatus(false, "Video source error: ${error?.description}")
        }
    }

    /**
     * Walk Annex-B NAL units in [buf] and stash SPS/PPS (H.264 types 7/8) or VPS/SPS/PPS
     * (H.265 types 32/33/34). Each stored WITH a 4-byte start code. Parameter sets ride
     * along with every I-frame on drone downlinks, so this resolves within ~1 GOP.
     */
    private fun sniffParameterSets(buf: ByteArray, size: Int) {
        var i = 0
        while (i < size - 4) {
            // find next start code (00 00 01 or 00 00 00 01)
            val sc = when {
                buf[i] == Z && buf[i + 1] == Z && buf[i + 2] == O -> 3
                buf[i] == Z && buf[i + 1] == Z && buf[i + 2] == Z && i + 3 < size && buf[i + 3] == O -> 4
                else -> { i++; continue }
            }
            val nalStart = i + sc
            if (nalStart >= size) break
            // find the END of this NAL (next start code or end of buffer)
            var j = nalStart + 1
            var nalEnd = size
            while (j < size - 3) {
                if (buf[j] == Z && buf[j + 1] == Z &&
                    (buf[j + 2] == O || (buf[j + 2] == Z && j + 3 < size && buf[j + 3] == O))) {
                    nalEnd = j; break
                }
                j++
            }
            val h264Type = (buf[nalStart].toInt() and 0x1F)
            val h265Type = (buf[nalStart].toInt() shr 1) and 0x3F
            when (h264Type) {
                7 -> sps = withStartCode(buf, nalStart, nalEnd)
                8 -> pps = withStartCode(buf, nalStart, nalEnd)
            }
            // H.265 signature: VPS(32)/SPS(33)/PPS(34). Only trust once we've seen a VPS —
            // h265Type aliases h264 values otherwise.
            if (h265Type == 32) { sawHevcNal = true; vps = withStartCode(buf, nalStart, nalEnd) }
            if (sawHevcNal) when (h265Type) {
                33 -> sps = withStartCode(buf, nalStart, nalEnd)
                34 -> pps = withStartCode(buf, nalStart, nalEnd)
            }
            i = nalEnd
        }
    }

    private fun withStartCode(buf: ByteArray, from: Int, to: Int): ByteArray {
        val out = ByteArray(4 + (to - from))
        out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 1
        System.arraycopy(buf, from, out, 4, to - from)
        return out
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
        private const val Z: Byte = 0
        private const val O: Byte = 1
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
        projection: android.media.projection.MediaProjection? = null,
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
     * IS set up and the aircraft simply isn't connected.
     */
    enum class StartResult { STARTED, NOT_CONFIGURED, FAILED }

    /**
     * Start streaming using the video settings saved by TakConnectActivity. Used by the
     * flight-screen LIVE button.
     */
    fun startFromPrefs(
        context: Context,
        onStatus: (Boolean, String) -> Unit,
        projection: android.media.projection.MediaProjection? = null,
    ): StartResult {
        val p = context.getSharedPreferences("takpilot2_tak", Context.MODE_PRIVATE)
        val host = p.getString("video_host", "") ?: ""
        val streamId = p.getString("video_streamid", "") ?: ""
        if (host.isEmpty() || streamId.isEmpty()) return StartResult.NOT_CONFIGURED
        val cfg = AutelVideoStreamer.VideoConfig(
            host = host,
            port = p.getInt("video_port", 8554),
            username = p.getString("video_user", "") ?: "",
            password = p.getString("video_pass", "") ?: "",
            streamId = streamId,
            tcp = p.getBoolean("video_tcp", true),
            profile = p.getString("video_profile", "standard") ?: "standard",
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
