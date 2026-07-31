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
    private val onParamsReady: (sps: ByteBuffer, pps: ByteBuffer) -> Unit,
) {
    /**
     * The pilot-selectable video quality tiers, matching the DJI blueprint's
     * `StreamTranscoder.TranscodeProfile` value-for-value so a given choice means the same
     * thing on either airframe.
     */
    enum class TranscodeProfile(val maxHeight: Int, val fps: Int, val bitrateBps: Int) {
        LOW(360, 10, 275_000),        // maximum survivability on marginal links
        STANDARD(480, 15, 550_000),   // default — ~2x Low's bitrate, noticeably better
        HIGH(720, 15, 1_000_000);     // ~2x again, plus higher resolution

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
        val format = MediaFormat.createVideoFormat("video/avc", targetW, targetH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, profile.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, profile.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            runCatching { setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline) }
        }
        runCatching {
            encoder = MediaCodec.createEncoderByType("video/avc").apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            AppLog.i(TAG, "encoder [${profile.name}]: ${srcW}x$srcH -> ${targetW}x$targetH " +
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

    /** Splits the encoder's SPS+PPS codec-config buffer at the second Annex-B start code. */
    private fun handleCodecConfig(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        val bytes = ByteArray(info.size)
        buf.get(bytes)
        var splitAt = -1
        var i = 4
        while (i < bytes.size - 3) {
            if (bytes[i] == Z && bytes[i + 1] == Z &&
                (bytes[i + 2] == O || (bytes[i + 2] == Z && bytes[i + 3] == O))) {
                splitAt = i; break
            }
            i++
        }
        if (splitAt <= 0) return
        val sps = bytes.copyOfRange(0, splitAt)
        val pps = bytes.copyOfRange(splitAt, bytes.size)
        AppLog.i(TAG, "low-bandwidth encoder params ready: sps=${sps.size}B pps=${pps.size}B")
        onParamsReady(ByteBuffer.wrap(sps), ByteBuffer.wrap(pps))
    }

    companion object {
        private const val TAG = "LowBWTranscoder"
        private const val Z: Byte = 0
        private const val O: Byte = 1
        private const val QUEUE_CAPACITY = 6

    }
}
