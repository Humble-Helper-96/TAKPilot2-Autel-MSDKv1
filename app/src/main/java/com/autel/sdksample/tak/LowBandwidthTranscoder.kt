package com.autel.sdksample.tak

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import com.taklite.util.AppLog
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue

/**
 * Video-quality transcode support: decodes the aircraft's native H.264/H.265 downlink and
 * re-encodes it at the pilot-selected [TranscodeProfile], entirely on its own
 * background thread. The on-screen [com.autel.sdk.widget.AutelCodecView] in FlightActivity
 * is untouched — it reads straight from the SDK, not from this class — so the pilot always
 * sees native-resolution video locally; only what gets pushed to the media server shrinks.
 *
 * Standard Android decode -> scale -> encode pipeline (MediaCodec + the Image/Plane API,
 * so no hand-rolled NV12/I420 byte-layout code), lazily creating the encoder once the
 * decoder reports the source's real dimensions. Best-effort throughout: any failure just
 * drops frames rather than taking the stream down.
 *
 * UNTESTED ON HARDWARE — built and code-reviewed without an aircraft attached (see the
 * project handoff's §7 calibration table for the same caveat on the rest of the video
 * path). The profiles' bitrates in particular want a real-network check before trusting them.
 */
class LowBandwidthTranscoder(
    private val isHevc: Boolean,
    /** Output size/rate/bitrate. Defaults to [TranscodeProfile.STANDARD] so existing callers
     *  keep the behaviour this class shipped with. */
    private val profile: TranscodeProfile = TranscodeProfile.STANDARD,
    private val onEncoded: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
    /** Encoder parameter sets. [vps] is non-null for H.265 and is what makes the RTSP client
     *  choose H265 packetisation — passing null there would silently produce an H.264 SDP for
     *  an H.265 stream, which players accept and then render as garbage. */
    private val onParamsReady: (sps: ByteBuffer, pps: ByteBuffer, vps: ByteBuffer?) -> Unit,
) {
    /**
     * The pilot-selectable video quality tiers.
     *
     * **Bitrates are identical to the DJI blueprint's, but each tier carries one resolution
     * step MORE** (480/720/1080 here vs 360/480/720 there). That is the whole point of
     * encoding H.265 on this airframe: it delivers roughly the same quality as H.264 at about
     * half the bitrate, so the saving is spent on resolution rather than on bandwidth. A pilot
     * on a marginal link gets the same number of bits either way — they just get a sharper
     * picture for them. Deliberately NOT value-for-value with DJI, so do not "restore" it.
     *
     * `maxHeight` is a CEILING on the vertical dimension, not a format: the source aspect
     * ratio is preserved and the width follows from it (see [ensureEncoder]). A 4:3 source at
     * the 720 tier is 960x720, not 1280x720.
     *
     * **Bitrates are set from bits-per-pixel-per-frame, not picked round.** At 16:9 (the worst
     * case, since 4:3 has fewer pixels for the same height) these give ~0.067 bpp for Low and
     * ~0.058 for Standard and High. For live H.265 at these sizes 0.06-0.08 is comfortable,
     * 0.04 workable, below 0.03 visibly soft in motion.
     *
     * The tuning that produced them (2026-07-31): the previous 275/550/1000 ladder was
     * INVERTED in quality — 0.067 / 0.040 / 0.032 bpp — so climbing a tier bought pixels while
     * losing per-pixel fidelity, and High looked mushier in motion than Standard despite
     * costing twice the bandwidth. Low was already correctly proportioned and is unchanged;
     * Standard and High were raised to match it.
     *
     * Screen capture (once ported) makes the top of this ladder matter more, not less: HUD
     * text, map labels and AR callouts are sharp high-frequency edges that cost far more bits
     * than camera video and smear first. An illegible altitude readout defeats the point of
     * streaming the screen at all.
     */
    enum class TranscodeProfile(val maxHeight: Int, val fps: Int, val bitrateBps: Int) {
        LOW(480, 10, 275_000),        // maximum survivability on marginal links
        STANDARD(720, 15, 800_000),   // default — ~3x Low's bitrate, noticeably better
        HIGH(1080, 15, 1_800_000);    // ~2x again, plus higher resolution

        companion object {
            fun fromPref(name: String?): TranscodeProfile = when (name) {
                "low" -> LOW
                "high" -> HIGH
                else -> STANDARD
            }
        }
    }

    private val thread = HandlerThread("LowBWTranscoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val queue = ArrayBlockingQueue<QueuedFrame>(QUEUE_CAPACITY)

    private var decoder: MediaCodec? = null
    private var encoder: MediaCodec? = null
    @Volatile private var released = false
    private var lastForwardedNs = 0L
    private var encFrameCount = 0
    private var encBytesSinceLog = 0L
    private val frameIntervalNs = 1_000_000_000L / profile.fps

    private class QueuedFrame(val bytes: ByteArray, val isIFrame: Boolean)

    /** Called from the SDK's frame-delivery thread — copies and hands off, never blocks it. */
    fun submit(buf: ByteArray, size: Int, isIFrame: Boolean) {
        if (released) return
        val copy = buf.copyOf(size)
        if (!queue.offer(QueuedFrame(copy, isIFrame))) {
            queue.poll()           // queue full: drop the oldest pending frame, not the newest
            queue.offer(QueuedFrame(copy, isIFrame))
        }
        handler.post { runCatching { processQueue() }.onFailure { AppLog.w(TAG, "transcode error: ${it.message}") } }
    }

    fun release() {
        if (released) return
        released = true
        handler.post {
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }
            decoder = null; encoder = null
        }
        thread.quitSafely()
    }

    // ---- Pipeline (all on the handler thread) ----

    private fun processQueue() {
        if (released) return
        ensureDecoder()
        var item = queue.poll()
        while (item != null) {
            decodeOne(item)
            item = queue.poll()
        }
        drainDecoder()
        drainEncoder()
    }

    private fun ensureDecoder() {
        if (decoder != null) return
        val mime = if (isHevc) "video/hevc" else "video/avc"
        // Placeholder dimensions — corrected via INFO_OUTPUT_FORMAT_CHANGED once the
        // decoder parses the real SPS out of the inline Annex-B stream we feed it.
        val format = MediaFormat.createVideoFormat(mime, 1920, 1080)
        decoder = MediaCodec.createDecoderByType(mime).apply {
            configure(format, null, null, 0)
            start()
        }
    }

    private fun decodeOne(item: QueuedFrame) {
        val dec = decoder ?: return
        val inIdx = dec.dequeueInputBuffer(10_000)
        if (inIdx < 0) return   // decoder backed up — drop this frame, best-effort
        val inBuf = dec.getInputBuffer(inIdx) ?: return
        inBuf.clear()
        inBuf.put(item.bytes)
        dec.queueInputBuffer(inIdx, 0, item.bytes.size, System.nanoTime() / 1000, 0)
    }

    private fun drainDecoder() {
        val dec = decoder ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = dec.dequeueOutputBuffer(info, 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = dec.outputFormat
                    ensureEncoder(fmt.getInteger(MediaFormat.KEY_WIDTH), fmt.getInteger(MediaFormat.KEY_HEIGHT))
                }
                idx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> { /* deprecated pre-API21 path, ignore */ }
                idx >= 0 -> {
                    if (info.size > 0) {
                        val image = runCatching { dec.getOutputImage(idx) }.getOrNull()
                        try {
                            image?.let { scaleAndForward(it) }
                        } finally {
                            image?.close()
                        }
                    }
                    dec.releaseOutputBuffer(idx, false)
                }
                else -> return
            }
        }
    }

    private fun ensureEncoder(srcW: Int, srcH: Int) {
        if (encoder != null || srcW <= 0 || srcH <= 0) return
        // never upscale: a 360p source stays 360p even on the HIGH profile
        var targetH = minOf(profile.maxHeight, srcH)
        var targetW = (srcW.toDouble() / srcH * targetH).toInt()
        targetW -= targetW % 2   // most encoders require even dimensions
        targetH -= targetH % 2
        // H.265 out, regardless of what the aircraft sent in. Verified on the Smart Controller:
        // OMX.qcom.video.encoder.hevc is a hardware encoder good to 3840x2160, and it measures
        // FASTER than the AVC one at our sizes (121fps vs 80-90 at 720x480), so this costs
        // nothing in CPU. The RTSP client selects H.265 packetisation and SDP automatically
        // once a non-null VPS reaches setVideoInfo — see handleCodecConfig.
        val format = MediaFormat.createVideoFormat(OUT_MIME, targetW, targetH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, profile.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, profile.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            runCatching { setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain) }
        }
        runCatching {
            encoder = MediaCodec.createEncoderByType(OUT_MIME).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            AppLog.i(TAG, "encoder [${profile.name}] H.265: ${srcW}x$srcH -> ${targetW}x$targetH " +
                    "@ ${profile.fps}fps ${profile.bitrateBps / 1000}kbps")
        }.onFailure { AppLog.w(TAG, "encoder setup failed: ${it.message}") }
    }

    /** Throttles to the profile's fps and nearest-neighbor downsamples each plane into the encoder's input. */
    private fun scaleAndForward(src: Image) {
        val enc = encoder ?: return
        val nowNs = System.nanoTime()
        if (nowNs - lastForwardedNs < frameIntervalNs) return
        lastForwardedNs = nowNs

        val inIdx = enc.dequeueInputBuffer(0)
        if (inIdx < 0) return   // encoder busy — drop this frame, best-effort
        val cap = runCatching { enc.getInputBuffer(inIdx)?.capacity() }.getOrNull() ?: 0
        val dstImage = runCatching { enc.getInputImage(inIdx) }.getOrNull()
        if (dstImage == null) {
            enc.queueInputBuffer(inIdx, 0, 0, 0, 0)
            return
        }
        // NOTE: unlike a decoder's *output* Image, an encoder's *input* Image (from
        // getInputImage()) is not meant to be closed here — it's invalidated by
        // queueInputBuffer() below, which is what actually submits it.
        val dstW = dstImage.width; val dstH = dstImage.height
        downsamplePlane(src.planes[0], dstImage.planes[0], src.width, src.height, dstW, dstH)
        downsamplePlane(src.planes[1], dstImage.planes[1], src.width / 2, src.height / 2, dstW / 2, dstH / 2)
        downsamplePlane(src.planes[2], dstImage.planes[2], src.width / 2, src.height / 2, dstW / 2, dstH / 2)

        val ptsUs = nowNs / 1000
        enc.queueInputBuffer(inIdx, 0, cap, ptsUs, 0)
        drainEncoder()
    }

    private fun downsamplePlane(src: Image.Plane, dst: Image.Plane, srcW: Int, srcH: Int, dstW: Int, dstH: Int) {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return
        val srcBuf = src.buffer
        val dstBuf = dst.buffer
        val srcRowStride = src.rowStride
        val srcPixStride = src.pixelStride
        val dstRowStride = dst.rowStride
        val dstPixStride = dst.pixelStride
        for (y in 0 until dstH) {
            val srcRowStart = (y * srcH / dstH) * srcRowStride
            val dstRowStart = y * dstRowStride
            for (x in 0 until dstW) {
                val srcPos = srcRowStart + (x * srcW / dstW) * srcPixStride
                val dstPos = dstRowStart + x * dstPixStride
                if (srcPos < srcBuf.capacity() && dstPos < dstBuf.capacity()) {
                    dstBuf.put(dstPos, srcBuf.get(srcPos))
                }
            }
        }
    }

    private fun drainEncoder() {
        val enc = encoder ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = enc.dequeueOutputBuffer(info, 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                idx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> continue
                idx >= 0 -> {
                    val outBuf = enc.getOutputBuffer(idx)
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            handleCodecConfig(outBuf, info)
                        } else {
                            onEncoded(outBuf, info)
                            encFrameCount++
                            encBytesSinceLog += info.size
                            if (encFrameCount % 150 == 0) {
                                AppLog.v(TAG, "low-bandwidth: $encFrameCount frames encoded, " +
                                        "${encBytesSinceLog / 1024}KB in last 150")
                                encBytesSinceLog = 0
                            }
                        }
                    }
                    enc.releaseOutputBuffer(idx, false)
                }
                else -> return
            }
        }
    }

    /**
     * Pulls the parameter sets out of the encoder's codec-config buffer.
     *
     * **H.265 emits THREE NALs (VPS + SPS + PPS), not H.264's two.** The previous version split
     * at the second start code and handed back two halves, which for HEVC would have silently
     * bundled VPS+SPS together as "sps" and produced an unplayable SDP. So this splits into
     * every NAL and classifies each by its header type rather than assuming a count or order.
     *
     * NAL type lives in different bits per codec: HEVC uses `(b0 >> 1) & 0x3F` (VPS 32, SPS 33,
     * PPS 34), H.264 uses `b0 & 0x1F` (SPS 7, PPS 8). Both are handled so flipping [OUT_MIME]
     * back to AVC does not quietly break this.
     *
     * NALs are passed on WITH their start codes — verified against the RTSP library, whose
     * `getData()` calls `getVideoStartCodeSize()` and strips them itself.
     */
    private fun handleCodecConfig(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        val bytes = ByteArray(info.size)
        buf.get(bytes)
        val nals = splitAnnexB(bytes)
        if (nals.isEmpty()) return

        var vps: ByteArray? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in nals) {
            val hdr = nal.getOrNull(startCodeLen(nal)) ?: continue
            if (OUT_MIME == "video/hevc") {
                when ((hdr.toInt() shr 1) and 0x3F) {
                    32 -> vps = nal
                    33 -> sps = nal
                    34 -> pps = nal
                }
            } else {
                when (hdr.toInt() and 0x1F) {
                    7 -> sps = nal
                    8 -> pps = nal
                }
            }
        }
        val s = sps; val p = pps
        if (s == null || p == null) {
            AppLog.w(TAG, "codec config had ${nals.size} NAL(s) but no SPS/PPS — not advertising")
            return
        }
        AppLog.i(TAG, "encoder params ready: " +
            (vps?.let { "vps=${it.size}B " } ?: "") + "sps=${s.size}B pps=${p.size}B")
        onParamsReady(
            ByteBuffer.wrap(s),
            ByteBuffer.wrap(p),
            vps?.let { ByteBuffer.wrap(it) },
        )
    }

    /** Length of the Annex-B start code at the head of [nal] — 4 for 00 00 00 01, else 3. */
    private fun startCodeLen(nal: ByteArray): Int =
        if (nal.size >= 4 && nal[0] == Z && nal[1] == Z && nal[2] == Z && nal[3] == O) 4 else 3

    /** Splits an Annex-B byte stream into its NALs, each still carrying its start code. */
    private fun splitAnnexB(bytes: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i < bytes.size - 3) {
            if (bytes[i] == Z && bytes[i + 1] == Z) {
                if (bytes[i + 2] == O) { starts.add(i); i += 3; continue }
                if (bytes[i + 2] == Z && bytes[i + 3] == O) { starts.add(i); i += 4; continue }
            }
            i++
        }
        if (starts.isEmpty()) return emptyList()
        return starts.mapIndexed { idx, from ->
            val to = if (idx + 1 < starts.size) starts[idx + 1] else bytes.size
            bytes.copyOfRange(from, to)
        }
    }

    companion object {
        private const val TAG = "LowBWTranscoder"
        private const val Z: Byte = 0
        private const val O: Byte = 1
        private const val QUEUE_CAPACITY = 6

        /** Output codec. H.265 on this airframe — see the profile table for why. Changing this
         *  back to "video/avc" is supported (handleCodecConfig classifies both), but the
         *  profile resolutions were chosen on the assumption of H.265 efficiency and should
         *  drop a step with it. */
        private const val OUT_MIME = "video/hevc"

    }
}
