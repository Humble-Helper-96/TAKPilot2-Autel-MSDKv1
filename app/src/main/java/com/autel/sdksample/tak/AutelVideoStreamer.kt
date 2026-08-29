package com.autel.sdksample.tak

import android.content.Context
import android.media.MediaCodec
import com.taklite.util.AppLog
import com.pedro.rtsp.rtsp.Protocol
import com.pedro.rtsp.rtsp.RtspClient
import com.pedro.rtsp.utils.ConnectCheckerRtsp
import com.pedro.srt.srt.SrtClient
import com.pedro.srt.srt.packets.control.handshake.EncryptionType
import java.nio.ByteBuffer

/**
 * AutelVideoStreamer — RTSP or SRT push of the flight screen. Port of TAKPilot2's
 * DroneVideoStreamer.
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
 * The encoder produces SPS/PPS/VPS before the client connects — the RTSP connect worker waits up
 * to 5 s for setVideoInfo — so the encoder is started first and connect follows immediately.
 *
 * ## Two transports, one leg
 *
 * The push leaves as RTSP or as SRT, the pilot's choice ([VideoTransport]). **What the TAK
 * clients are told does not change**: the advertised address is an RTSP address either way,
 * because no TAK client plays SRT. The media server ingests one and serves the other.
 *
 * The two clients are the same author's and their send API is identical, thus [PushClient] wraps
 * both and everything below the selection is transport-blind. The DIFFERENCES are all at setup:
 * SRT has no credential field (they go in the stream id) and no protocol selection.
 *
 * This class implements the callback interface of BOTH clients. The names do not collide
 * (`onConnectionSuccessRtsp` against `onConnectionSuccess`) and every one of them funnels into
 * the same private handler, so the state machine exists once.
 */
class AutelVideoStreamer(
    private val context: Context,
    private val config: VideoConfig,
    private val onStatus: (Boolean, String) -> Unit,
    /** The screen-capture projection. The stream is always a capture of the flight screen. */
    private val mediaProjection: android.media.projection.MediaProjection,
) : ConnectCheckerRtsp, com.pedro.common.ConnectChecker {

    data class VideoConfig(
        val host: String,
        val streamId: String,

        // ---- RTSP: the PLAYBACK details, and the push when RTSP is the transport ----
        //
        // ⚠ ALWAYS USED, WHICHEVER TRANSPORT IS SELECTED. [advertiseUrl] is an RTSP address
        // because no TAK client plays SRT, so these three describe what the team receives even
        // when the video leaves the controller over SRT. They were one shared set with the SRT
        // credentials until 2026-08-29, and the playback port was the constant 8554 — which
        // meant a server serving RTSP on any other port could not be advertised at all.
        val rtspPort: Int,
        val rtspUser: String,
        val rtspPass: String,

        // ---- SRT: the PUSH details, used only when SRT is the transport ----
        //
        // SRT has a PORT and a PASSPHRASE. It has no login of its own: the username and the
        // password belong to RTSP (operator, 2026-08-29). The stream id still carries them,
        // because that is the only place SRT can put a credential, but they are the RTSP
        // pair — one login for the server, not two.
        val srtPort: Int = VideoTransport.SRT.defaultPort,
        /** How the video leaves the controller. See [VideoTransport]. */
        val transport: VideoTransport = VideoTransport.RTSP,
        /** SRT only, and MILLISECONDS. The evidence for the number, the units trap and the
         *  field override are all at [VideoTransport.SRT_LATENCY_DEFAULT_MS]. */
        val srtLatencyMs: Int = VideoTransport.SRT_LATENCY_DEFAULT_MS,
        /**
         * SRT only. The media server's `srtPublishPassphrase`, or empty when the server does
         * not use one. This ENCRYPTS THE STREAM and it is a separate secret from the video
         * user and password: those authorise the publish, this one is the key.
         *
         * ⚠ **A server that sets a passphrase refuses an unencrypted publisher outright** —
         * MediaMTX answers `SRT_REJ_PEER` at the handshake, before any credential is looked at,
         * so a missing passphrase looks exactly like wrong credentials. Confirmed against
         * MediaMTX v1.20.0 on 2026-08-29.
         *
         * ⚠ **NEVER LOG THIS AND NEVER PUT IT IN A URL.** It is not part of the stream id, it
         * does not appear in [pushUrl] or [urlSafe], and it must not go in the CoT.
         */
        val srtPassphrase: String = "",
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

        /**
         * Where the video goes OUT. The two transports do not agree on where the credentials
         * live, thus this is the one place that knows the difference.
         *
         *  - RTSP: `rtsp://host:port/<path>`. The user and the password are NOT here — they go
         *    to the client with `setAuthorization` and the protocol carries them.
         *  - SRT: `srt://host:port/publish:<path>:<user>:<password>`. SRT has no credential
         *    field of its own. Everything after the port is the STREAM ID, one string, and the
         *    media server reads the publisher, the path and the credentials out of it. With no
         *    username it is `publish:<path>` and the server decides whether to accept that.
         *
         * ⚠ **A colon in the video password breaks the SRT form**, because the colon is what
         * separates the four parts of the stream id. There is no escape for it. [start] logs a
         * warning; the stream will be refused by the server. RTSP is unaffected.
         */
        fun pushUrl(): String = when (transport) {
            VideoTransport.RTSP -> "rtsp://$host:$rtspPort/${path()}"
            VideoTransport.SRT ->
                if (rtspUser.isEmpty()) "srt://$host:$srtPort/publish:${path()}"
                else "srt://$host:$srtPort/publish:${path()}:$rtspUser:$rtspPass"
        }

        /** The port the PUSH uses. The login is [rtspUser] either way. */
        val pushPort: Int get() =
            if (transport == VideoTransport.SRT) srtPort else rtspPort

        /**
         * The address that goes in the CoT, for the team to play.
         *
         * ⚠ **ALWAYS RTSP, on the media server's RTSP port** — see [VideoTransport]. An SRT push
         * is not playable by any TAK client, so advertising `srt://…` would put an address on
         * the wire that every viewer would fail to open, and the failure would look like a dead
         * feed rather than a wrong address.
         *
         * It is built from the RTSP fields ALWAYS — never from the SRT ones, whose port is an
         * ingest port and whose login authorises a publish.
         */
        fun advertiseUrl(): String {
            val cred = if (rtspUser.isNotEmpty()) "${enc(rtspUser)}:${enc(rtspPass)}@" else ""
            return "rtsp://$cred$host:$rtspPort/${path()}?tcp"
        }
        /**
         * The url with the password masked, for the screen and the log.
         *
         * ⚠ **It must distinguish "set" from "empty".** It used to print `user:***@` whenever the
         * USERNAME was non-empty, so a missing password looked identical to a present one — and
         * the Pre-Flight preview, the one place a pilot would check, could not answer the
         * question it exists to answer. A password really was empty on 2026-08-05 and the screen
         * showed stars for it.
         *
         * **This is the PUSH address, not the playback address.** The two were the same string
         * until SRT arrived. The push address is the one built from the fields on the screen, so
         * it is the one that can show a pilot that a field is wrong; the playback address is
         * derived and goes out in the CoT, and nobody types it anywhere (operator, 2026-08-29).
         */
        fun urlSafe(): String {
            val secret = when {
                rtspUser.isEmpty() -> ""
                rtspPass.isEmpty() -> "(NO PASSWORD)"
                else -> "***"
            }
            return when (transport) {
                VideoTransport.RTSP -> {
                    val who = if (rtspUser.isEmpty()) "" else "$rtspUser:$secret@"
                    "rtsp://$who$host:$rtspPort/${path()}?tcp"
                }
                VideoTransport.SRT ->
                    if (rtspUser.isEmpty()) "srt://$host:$srtPort/publish:${path()}"
                    else "srt://$host:$srtPort/publish:${path()}:$rtspUser:$secret"
            }
        }
        private fun enc(s: String): String =
            java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }

    /**
     * The two push clients behind one set of calls.
     *
     * Only [start] knows which one is in use. Everything after it — the encoder callback, the
     * connection handlers, the teardown — works through this, because the transport is a
     * property of the link and not of the video.
     *
     * [droppedVideoFrames] is the counter that says the SEND QUEUE overflowed, which means the
     * encoder is producing faster than the link can carry. It is the one number that separates
     * "this link is lossy" from "this link is too slow", and it is why the two are logged
     * together. See the frame log in [countFrame].
     */
    private interface PushClient {
        fun connect(url: String)
        fun disconnect()
        fun reConnect(delayMs: Long)
        fun shouldRetry(reason: String): Boolean
        fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer, vps: ByteBuffer?)
        fun sendVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
        val droppedVideoFrames: Long
    }

    private class RtspPushClient(private val c: RtspClient) : PushClient {
        override fun connect(url: String) = c.connect(url)
        override fun disconnect() = c.disconnect()
        override fun reConnect(delayMs: Long) = c.reConnect(delayMs)
        override fun shouldRetry(reason: String) = c.shouldRetry(reason)
        override fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer, vps: ByteBuffer?) =
            c.setVideoInfo(sps, pps, vps)
        override fun sendVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) =
            c.sendVideo(buffer, info)
        override val droppedVideoFrames: Long get() = c.droppedVideoFrames
    }

    private class SrtPushClient(private val c: SrtClient) : PushClient {
        override fun connect(url: String) = c.connect(url)
        override fun disconnect() = c.disconnect()
        override fun reConnect(delayMs: Long) = c.reConnect(delayMs)
        override fun shouldRetry(reason: String) = c.shouldRetry(reason)
        override fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer, vps: ByteBuffer?) =
            c.setVideoInfo(sps, pps, vps)
        override fun sendVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) =
            c.sendVideo(buffer, info)
        override val droppedVideoFrames: Long get() = c.droppedVideoFrames
    }

    private var client: PushClient? = null
    @Volatile private var streaming = false
    @Volatile private var stopped = false
    private var frameCount = 0

    // ---- What the link is actually doing (see countFrame) ----
    private var lastRateLogMs = 0L
    private var framesAtLastLog = 0
    private var bytesSinceLastLog = 0L
    @Volatile private var lastReportedBitrate = 0L

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

        // ⚠ SRT carries the credentials inside the stream id, and the colon separates its
        // parts. A password with a colon in it produces a stream id the server reads wrongly
        // and refuses, and the pilot sees only "the stream will not start". Say so in the log.
        if (config.transport == VideoTransport.SRT && config.rtspPass.contains(':')) {
            AppLog.w(TAG, "the video password contains a colon — SRT cannot carry it, " +
                    "the server will refuse this stream. Change the password or use RTSP.")
        }

        // ⚠ SRT REFUSES A PASSPHRASE OUTSIDE 10–79 CHARACTERS BY THROWING. Caught here rather
        // than at the client, because an exception out of start() takes the LIVE tap with it
        // and the pilot gets no reason at all. Told, not logged: this is a pilot-fixable
        // mistake on the screen they just left.
        if (config.transport == VideoTransport.SRT && config.srtPassphrase.isNotEmpty() &&
            config.srtPassphrase.length !in SRT_PASSPHRASE_MIN..SRT_PASSPHRASE_MAX) {
            AppLog.w(TAG, "SRT passphrase is ${config.srtPassphrase.length} characters — " +
                    "must be $SRT_PASSPHRASE_MIN–$SRT_PASSPHRASE_MAX")
            onStatus(false, "SRT passphrase must be $SRT_PASSPHRASE_MIN–$SRT_PASSPHRASE_MAX characters")
            return false
        }

        val push: PushClient = when (config.transport) {
            VideoTransport.RTSP -> RtspPushClient(RtspClient(this).apply {
                setLogs(false)
                // TCP always. The UDP option went with the checkbox — see [VideoTransport.RTSP].
                setProtocol(Protocol.TCP)
                if (config.rtspUser.isNotEmpty()) setAuthorization(config.rtspUser, config.rtspPass)
                setOnlyVideo(true)
                setReTries(10)
            })
            VideoTransport.SRT -> SrtPushClient(SrtClient(this).apply {
                setLogs(false)
                // Clamped again here, not because the pref path does not clamp, but because
                // this is the last point before the value reaches a 16-bit wire field.
                latencyMs = VideoTransport.clampLatencyMs(config.srtLatencyMs)
                // Encryption. AES-128 is what a passphrase alone selects at the other end:
                // the key length travels in the key-material message, so the publisher picks
                // it and the server follows. Empty means no encryption, which is correct for
                // a server that sets no passphrase and REFUSED BY ONE THAT DOES.
                if (config.srtPassphrase.isNotEmpty()) {
                    setPassphrase(config.srtPassphrase, EncryptionType.AES128)
                }
                // ⚠ NO setAuthorization CALL. It throws in this client — the credentials are
                // already in the url that pushUrl() built. See the note on that function.
                setOnlyVideo(true)
                setReTries(10)
                // The MPEG-TS mux has to know the codec; the default is H.264, so an H.265
                // stream sent without this is muxed under the wrong stream type and no
                // viewer decodes it. RTSP learns the codec from the VPS instead.
                setVideoCodec(
                    if (config.videoCodec.isHevc) com.pedro.common.VideoCodec.H265
                    else com.pedro.common.VideoCodec.H264
                )
            })
        }
        client = push

        // The encoder must be producing parameter sets BEFORE connect — connect()'s worker waits
        // up to 5s for setVideoInfo.
        val enc = ScreenCaptureEncoder(
            context, mediaProjection, config.transcodeProfile, config.videoCodec,
            onEncoded = { buf, bufInfo ->
                val size = bufInfo.size
                push.sendVideo(buf, bufInfo)
                // isLive gates the LIVE pill on frameCount, so without this the pill would sit
                // on amber forever while a screen capture streamed perfectly well.
                frameCount++
                countFrame(size)
            },
            onParamsReady = { spsB, ppsB, vpsB -> push.setVideoInfo(spsB, ppsB, vpsB) },
        )
        if (!enc.start()) {
            onStatus(false, "Screen capture failed to start")
            client = null
            return false
        }
        screenEncoder = enc

        push.connect(config.pushUrl())
        // ⚠ NEVER LOG pushUrl(). With SRT the credentials are IN that string — the stream id
        // carries them — so logging it would write the video password to app.log, which goes to
        // the operator with the flight records. urlSafe() is the same address with the password
        // masked, and it is the only form that may be logged or shown.
        val lat = if (config.transport == VideoTransport.SRT)
            ", ${VideoTransport.clampLatencyMs(config.srtLatencyMs)}ms buffer" else ""
        AppLog.i(TAG, "push=${config.urlSafe()}  advertise=rtsp://…:${config.rtspPort}" +
                "  [${config.transport.label}$lat, ${config.transcodeProfile.name}: screen capture]")
        onStatus(true, "Starting ${config.transport.label} push → ${config.urlSafe()}")
        return true
    }

    fun stop() {
        stopped = true
        try { client?.disconnect() } catch (t: Throwable) { AppLog.w(TAG, "disconnect: ${t.message}") }
        client = null
        screenEncoder?.release()
        screenEncoder = null
        streaming = false
    }

    /**
     * WHAT THE LINK IS DOING, every [RATE_LOG_INTERVAL_MS], at INFO so it is in `app.log`
     * without Detailed logging on.
     *
     * ⚠ **This exists to answer one question, and only these two numbers together answer it.**
     * A stream that looks bad in the field fails in one of two ways, and they need opposite
     * fixes:
     *
     *  - `drops` climbing means the SEND QUEUE overflowed. The encoder is making more video
     *    than the link can carry. That is BANDWIDTH, and no transport fixes it — the answer is
     *    a lower video quality.
     *  - `drops` at zero with the rate at target, but a torn picture at the far end, means
     *    packets are being LOST on the way. That is what SRT recovers and RTSP does not.
     *
     * Before this line existed neither number left the process: the library counted the drops
     * and nothing read the counter, and the bitrate callback was an empty function. A stream
     * that degraded in flight was invisible afterwards, which is why the question of whether
     * this fleet needs SRT could not be answered from the flight records.
     */
    private fun countFrame(size: Int) {
        // ⚠ ONLY WHILE CONNECTED. The encoder runs from the moment LIVE is tapped, thus a push
        // that never connected still had frames to count, and this line reported "199kbps
        // 7fps" for a stream the server had refused — the most misleading thing it could say
        // to somebody reading the log to find out why there was no video (2026-08-29).
        if (!streaming) return
        bytesSinceLastLog += size
        val now = System.currentTimeMillis()
        if (lastRateLogMs == 0L) { lastRateLogMs = now; framesAtLastLog = frameCount; return }
        val elapsed = now - lastRateLogMs
        if (elapsed < RATE_LOG_INTERVAL_MS) return

        val frames = frameCount - framesAtLastLog
        val kbps = (bytesSinceLastLog * 8L) / elapsed          // bytes/ms*8 == kbit/s
        val fps = frames * 1000L / elapsed
        // The library's own measure includes the packet headers and the reports, thus it reads
        // a little above the payload rate calculated here. It is 0 until the first callback.
        val wire = if (lastReportedBitrate > 0) " wire=${lastReportedBitrate / 1000}kbps" else ""
        AppLog.i(TAG, "link [${config.transport.label}]: ${kbps}kbps$wire  ${fps}fps  " +
                "drops=${client?.droppedVideoFrames ?: 0}  frames=$frameCount")

        lastRateLogMs = now
        framesAtLastLog = frameCount
        bytesSinceLastLog = 0
    }

    // ---- Connection handlers, shared by both transports ----
    //
    // The two clients report the same events under different names. Each override below is one
    // line into one of these, so the behaviour cannot drift between transports.

    private fun handleConnected() {
        streaming = true
        AppLog.i(TAG, "${config.transport.label} push connected")
        // Arm the packetiser with a fresh IDR so a viewer joining now gets a picture without
        // waiting out the 2s GOP. Only the screen encoder can be asked on demand — the
        // aircraft's keyframe cadence is not ours to control.
        screenEncoder?.requestSyncFrame()
        onStatus(true, "Streaming → ${config.urlSafe()}")
    }

    private fun handleFailed(reason: String) {
        AppLog.w(TAG, "connection failed: $reason")
        // ⚠ A REFUSAL IS NOT A DROPPED LINK, so retrying it only hammers the server. On
        // 2026-08-29 a missing SRT passphrase produced SRT_REJ_PEER twelve times over 24
        // seconds, and every attempt was answered the same way — the server had already
        // decided. The RTSP side has always given up at once on its auth error; this is the
        // same rule for the transport that reports the same thing under another name.
        if (NON_RETRYABLE.any { it in reason }) {
            AppLog.w(TAG, "the server refused this stream — not retrying")
            streaming = false
            onStatus(false, "Stream refused by the server — check the passphrase and the login")
            return
        }
        val c = client
        if (!stopped && c != null && c.shouldRetry(reason)) {
            c.reConnect(2000)
        } else {
            streaming = false
            onStatus(false, "Stream failed: $reason")
        }
    }

    // ---- ConnectCheckerRtsp (the RTSP client) ----

    override fun onConnectionStartedRtsp(rtspUrl: String) { AppLog.i(TAG, "connecting ${config.urlSafe()}") }
    override fun onConnectionSuccessRtsp() = handleConnected()
    override fun onConnectionFailedRtsp(reason: String) = handleFailed(reason)
    override fun onDisconnectRtsp() { streaming = false; AppLog.i(TAG, "disconnected") }
    override fun onAuthErrorRtsp() { streaming = false; onStatus(false, "Stream auth error (check user/pass)") }
    override fun onAuthSuccessRtsp() { AppLog.i(TAG, "auth ok") }
    override fun onNewBitrateRtsp(bitrate: Long) { lastReportedBitrate = bitrate }

    // ---- ConnectChecker (the SRT client) ----

    override fun onConnectionStarted(url: String) { AppLog.i(TAG, "connecting ${config.urlSafe()}") }
    override fun onConnectionSuccess() = handleConnected()
    override fun onConnectionFailed(reason: String) = handleFailed(reason)
    override fun onDisconnect() { streaming = false; AppLog.i(TAG, "disconnected") }
    /** SRT has no credential exchange of its own — a bad user or password comes back as a
     *  refused connection, not as this. Kept because the interface has it. */
    override fun onAuthError() { streaming = false; onStatus(false, "Stream auth error (check user/pass)") }
    override fun onAuthSuccess() { AppLog.i(TAG, "auth ok") }
    override fun onNewBitrate(bitrate: Long) { lastReportedBitrate = bitrate }

    companion object {
        private const val RATE_LOG_INTERVAL_MS = 10_000L

        /** The library's own limits — outside these [SrtClient.setPassphrase] throws. */
        private const val SRT_PASSPHRASE_MIN = 10
        private const val SRT_PASSPHRASE_MAX = 79

        /**
         * Failures where the far end has DECIDED, thus a retry changes nothing.
         *
         * `SRT_REJ_PEER` is the server refusing the publish — a missing or wrong passphrase, a
         * credential it does not accept, or a path it will not take. All three are fixed on a
         * screen, never by waiting.
         */
        private val NON_RETRYABLE = listOf(
            "SRT_REJ_PEER", "Endpoint malformed", "access denied",
        )
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
        val transport = VideoTransport.fromPref(p.getString("video_transport", null))
        val cfg = AutelVideoStreamer.VideoConfig(
            host = host,
            streamId = streamId,
            // ⚠ Keys must match the KEY_V_* constants in TakConnectActivity. They are literals
            // here only because this file has no access to those private constants; if one
            // moves, move both.
            //
            // The RTSP set is read whichever transport is selected — it is what the CoT
            // advertises. The SRT set is read too and simply goes unused under RTSP.
            rtspPort = p.getInt("video_rtsp_port", VideoTransport.RTSP.defaultPort),
            rtspUser = p.getString("video_rtsp_user", "") ?: "",
            rtspPass = p.getString("video_rtsp_pass", "") ?: "",
            srtPort = p.getInt("video_srt_port", VideoTransport.SRT.defaultPort),
            transport = transport,
            // Read on every start, so a change on the Debug screen takes effect at the next
            // LIVE and not at the next application launch.
            srtLatencyMs = VideoTransport.srtLatencyMs(p),
            // Key must match TakConnectActivity.KEY_V_SRT_PASS, same as the password above.
            srtPassphrase = p.getString("video_srt_passphrase", "") ?: "",
            profile = p.getString("video_profile", "standard") ?: "standard",
            codec = p.getString("video_codec", VideoCodec.H264.prefValue)
                ?: VideoCodec.H264.prefValue,
        )
        // Advertise the CONFIGURED address once a start is attempted.
        //
        // ⚠ This fires on the ATTEMPT, not on a connected stream: start() reports
        // onStatus(true, "Starting RTSP push") immediately after client.connect(), and a
        // failing connection then retries without reporting false. So the address is on the
        // wire while the push is still trying, and it stays there through the retries.
        //
        // THIS IS DELIBERATE (operator, 2026-08-16). The CoT carries the address the pilot
        // entered, which is what it should carry, and an RTSP viewer retries against a server
        // that comes up a moment later. An earlier version of this comment claimed the URL was
        // published only after the push succeeded. It never was, and the behaviour it described
        // is not the behaviour that is wanted.
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
