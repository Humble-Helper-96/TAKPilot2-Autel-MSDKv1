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
 * DJI MSDK v5 only exposes a decode surface, so the original had to RE-ENCODE the feed
 * (surface → MediaCodec encoder → RTSP). Autel MSDK v1.5's [AutelCodecListener] hands us
 * the aircraft's raw encoded Annex-B frames directly ([AutelCodecListener.onFrameStream]),
 * so we inject them straight into RootEncoder's [RtspClient] — zero transcode, zero
 * quality loss, near-zero CPU. This is also exactly the "passthrough" preference from the
 * project guide's Phase 2 capacity plan.
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
) : ConnectCheckerRtsp {

    data class VideoConfig(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val streamId: String,
        val tcp: Boolean,
        val lowBandwidth: Boolean = false,
    ) {
        // -Low suffix flows through push/advertise/display URLs alike, so the CoT always
        // points at whichever stream is actually live — full-res and -Low are never both up.
        private fun path(): String = streamId.trim('/') + if (lowBandwidth) "-Low" else ""
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

    // Sniffed parameter sets (kept WITH their Annex-B start codes; RootEncoder strips them).
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var vps: ByteArray? = null   // non-null only if the feed is H.265
    private var sawHevcNal = false

    val isStreaming: Boolean get() = streaming

    fun start() {
        val c = AutelProductHolder.codec
        if (c == null) {
            onStatus(false, "Aircraft not connected (no video source)")
            return
        }
        codec = c
        stopped = false
        startNs = System.nanoTime()

        client.setLogs(false)
        client.setProtocol(if (config.tcp) Protocol.TCP else Protocol.UDP)
        if (config.username.isNotEmpty()) client.setAuthorization(config.username, config.password)
        client.setOnlyVideo(true)
        client.setReTries(10)

        // Register the frame tap BEFORE connect — connect()'s worker waits (≤5 s) for
        // setVideoInfo, which fires as soon as we sniff SPS/PPS from the stream.
        c.setCodecListener(codecListener, null)
        client.connect(config.pushUrl())
        AppLog.i(TAG, "push=${config.pushUrl()}  advertise=${config.urlSafe()}" +
                (if (config.lowBandwidth) "  [low-bandwidth: transcoding]" else ""))
        onStatus(true, "Starting RTSP push → ${config.urlSafe()}")
    }

    fun stop() {
        stopped = true
        try { codec?.cancel() } catch (t: Throwable) { AppLog.w(TAG, "codec cancel: ${t.message}") }
        codec = null
        try { client.disconnect() } catch (t: Throwable) { AppLog.w(TAG, "disconnect: ${t.message}") }
        transcoder?.release()
        transcoder = null
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
                        if (config.lowBandwidth) {
                            // The -Low stream's SPS/PPS come from OUR encoder, not the
                            // source's — client.setVideoInfo() is called from onParamsReady
                            // once the transcoder's encoder actually produces them.
                            transcoder = LowBandwidthTranscoder(
                                isHevc = sawHevcNal,
                                onEncoded = { buf, bufInfo -> client.sendVideo(buf, bufInfo) },
                                onParamsReady = { spsB, ppsB -> client.setVideoInfo(spsB, ppsB, null) },
                            )
                            AppLog.i(TAG, "low-bandwidth transcoder started (source " +
                                    "${if (sawHevcNal) "H.265" else "H.264"})")
                        } else {
                            client.setVideoInfo(ByteBuffer.wrap(s), ByteBuffer.wrap(p),
                                vps?.let { ByteBuffer.wrap(it) })
                        }
                        paramsSet = true
                        AppLog.i(TAG, "parameter sets found (${if (vps != null) "H.265" else "H.264"}); " +
                            "sps=${s.size}B pps=${p.size}B" + (vps?.let { " vps=${it.size}B" } ?: ""))
                    } else return   // keep waiting for a keyframe carrying the params
                }

                if (config.lowBandwidth) {
                    transcoder?.submit(videoBuffer, size, isIFrame)
                } else {
                    val info = MediaCodec.BufferInfo()
                    // Monotonic synthesized pts — the SDK's pts units are undocumented, and
                    // RTSP timestamps only need to be monotonic at the right rate.
                    val ptsUs = (System.nanoTime() - startNs) / 1000
                    info.set(0, size, ptsUs, if (isIFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    client.sendVideo(ByteBuffer.wrap(videoBuffer, 0, size), info)
                }

                // Periodic throughput summary (not per-frame — this callback can run at 30fps).
                // Counts source frames received either way; the transcoder logs its own
                // (lower-rate) output throughput separately when low-bandwidth is active.
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

    fun start(
        context: Context,
        config: AutelVideoStreamer.VideoConfig,
        onStatus: (Boolean, String) -> Unit,
    ) {
        streamer?.stop()
        streamer = AutelVideoStreamer(context.applicationContext, config, onStatus).also { it.start() }
        notifyState()
    }

    fun stop() {
        streamer?.stop()
        streamer = null
        TakBridgeHolder.setVideoUrl(null)
        notifyState()
    }

    val isRunning: Boolean get() = streamer?.isStreaming == true
    val isActive: Boolean get() = streamer != null

    /**
     * Start streaming using the video settings saved by TakConnectActivity. Returns false
     * if no stream is configured. Used by the flight-screen Start Video button.
     */
    fun startFromPrefs(context: Context, onStatus: (Boolean, String) -> Unit): Boolean {
        val p = context.getSharedPreferences("takpilot2_tak", Context.MODE_PRIVATE)
        val host = p.getString("video_host", "") ?: ""
        val streamId = p.getString("video_streamid", "") ?: ""
        if (host.isEmpty() || streamId.isEmpty()) return false
        val cfg = AutelVideoStreamer.VideoConfig(
            host = host,
            port = p.getInt("video_port", 8554),
            username = p.getString("video_user", "") ?: "",
            password = p.getString("video_pass", "") ?: "",
            streamId = streamId,
            tcp = p.getBoolean("video_tcp", true),
            lowBandwidth = p.getBoolean("video_low_bw", false),
        )
        start(context, cfg) { ok, msg ->
            if (ok) TakBridgeHolder.setVideoUrl(cfg.advertiseUrl())
            onStatus(ok, msg)
        }
        return true
    }
}
