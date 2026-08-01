package com.autel.sdksample.tak

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import com.taklite.util.AppLog
import java.nio.ByteBuffer

/**
 * Screen-capture H.265 encoder for the outbound RTSP push. Ported from the DJI blueprint's
 * `ScreenCaptureEncoder`, re-targeted to H.265 to match [LowBandwidthTranscoder.TranscodeProfile].
 *
 * MediaProjection mirrors the whole flight screen — FPV, HUD, map, AR markers, toolbar — into a
 * [VirtualDisplay] sized to the profile, straight into the encoder's input Surface. Two
 * consequences matter operationally:
 *
 *  1. **The team sees what the pilot sees**, not just the camera. That was the point of the
 *     DJI design and the reason this exists.
 *  2. **The stream does not depend on the aircraft.** It mirrors the screen, so a lost link,
 *     a battery swap, or an aircraft that was never connected all leave the push running —
 *     viewers keep seeing the controller (map, last telemetry, whatever the screen shows)
 *     instead of the feed going dead. The aircraft-frame path this replaces could not do that:
 *     no aircraft meant no frames meant no stream at all.
 *
 * It is also cheaper than the decode→scale→encode transcoder it supersedes: there is no second
 * decoder (the screen is already composited pixels) and the scaling happens on the GPU in the
 * VirtualDisplay rather than in a CPU downsample loop.
 */
class ScreenCaptureEncoder(
    context: Context,
    private val mediaProjection: MediaProjection,
    private val profile: LowBandwidthTranscoder.TranscodeProfile,
    private val onEncoded: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
    private val onParamsReady: (sps: ByteBuffer, pps: ByteBuffer, vps: ByteBuffer?) -> Unit,
) {
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var drainThread: Thread? = null
    @Volatile private var running = false
    private var encFrameCount = 0
    private var encBytesSinceLog = 0L

    private val screenW: Int
    private val screenH: Int
    private val densityDpi: Int

    init {
        @Suppress("DEPRECATION")
        val metrics = DisplayMetrics().also {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.getRealMetrics(it)
        }
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        densityDpi = metrics.densityDpi
    }

    /** Registered with the projection so a stop from the system side tears this down too. */
    val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { AppLog.i(TAG, "media projection stopped by system"); release() }
    }

    fun start(): Boolean {
        // Cap the HEIGHT to the profile and let the width follow, preserving the screen's
        // aspect ratio. The controller is 4:3 (2048x1536), so the Standard tier is 960x720 —
        // NOT 1280x720. maxHeight is a ceiling, not a format.
        var targetH = minOf(profile.maxHeight, screenH)
        var targetW = (screenW.toDouble() / screenH * targetH).toInt()
        targetW -= targetW % 2   // even dims for the encoder
        targetH -= targetH % 2

        return runCatching {
            val (enc, variant) = configureEncoder(targetW, targetH)
                ?: throw IllegalStateException("no usable encoder configuration")
            val surface = enc.createInputSurface()
            enc.start()
            encoder = enc
            inputSurface = surface
            AppLog.i(TAG, "encoder configured with variant: $variant")

            mediaProjection.registerCallback(projectionCallback, null)
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "TAKPilot2Stream",
                targetW, targetH, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null,
            )

            running = true
            drainThread = Thread({ drainLoop() }, "ScreenCaptureEncoder").apply { start() }
            AppLog.i(TAG, "screen capture [${profile.name}] H.265: ${screenW}x$screenH -> " +
                "${targetW}x$targetH @ ${profile.fps}fps ${profile.bitrateBps / 1000}kbps CBR, " +
                "${I_FRAME_INTERVAL_S}s IDR")
            true
        }.onFailure {
            AppLog.e(TAG, "screen capture start failed: ${it.message}", it)
            release()
        }.getOrDefault(false)
    }

    /**
     * Configures the encoder, dropping optional format keys until one is accepted.
     *
     * **This is not defensive padding — the controller's encoder genuinely rejects the full
     * format.** `OMX.qcom.video.encoder.hevc` on this build is a legacy OMX component (not
     * Codec2), and it failed `configureCodec` with error -38 when handed KEY_PROFILE and
     * KEY_MAX_FPS_TO_ENCODER. Legacy OMX components commonly reject KEY_PROFILE unless a
     * matching KEY_LEVEL is supplied, and often do not implement the API-30 max-fps key at all.
     *
     * The order below drops the least important first, so a device that accepts everything
     * still gets CBR and an explicit profile. Which variant won is logged, so a future device
     * tells us what it supports instead of us guessing again.
     */
    private fun configureEncoder(w: Int, h: Int): Pair<MediaCodec, String>? {
        data class Variant(val name: String, val apply: (MediaFormat) -> Unit)
        val variants = listOf(
            Variant("full (profile+level, CBR, max-fps)") { f ->
                f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                f.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4)
                if (Build.VERSION.SDK_INT >= 30) {
                    f.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, profile.fps.toFloat())
                }
            },
            Variant("no max-fps") { f ->
                f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                f.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4)
            },
            Variant("CBR only (no profile/level)") { f ->
                f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            },
            Variant("minimal (encoder defaults)") { },
        )

        for (v in variants) {
            val format = MediaFormat.createVideoFormat(OUT_MIME, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, profile.bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, profile.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
                v.apply(this)
            }
            val enc = runCatching { MediaCodec.createEncoderByType(OUT_MIME) }.getOrNull() ?: continue
            val ok = runCatching {
                enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }.isSuccess
            if (ok) return enc to v.name
            AppLog.w(TAG, "encoder rejected variant '${v.name}' — trying simpler")
            runCatching { enc.release() }
        }
        return null
    }

    /** Ask the encoder for an IDR now — arms the RTSP packetiser on connect, heals viewers. */
    fun requestSyncFrame() {
        runCatching {
            encoder?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }.onFailure { AppLog.w(TAG, "requestSyncFrame failed: ${it.message}") }
    }

    fun release() {
        running = false
        runCatching { mediaProjection.unregisterCallback(projectionCallback) }
        runCatching { virtualDisplay?.release() }; virtualDisplay = null
        drainThread?.let { runCatching { it.join(500) } }; drainThread = null
        runCatching { encoder?.stop() }; runCatching { encoder?.release() }; encoder = null
        runCatching { inputSurface?.release() }; inputSurface = null
    }

    private fun drainLoop() {
        val enc = encoder ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val idx = enc.dequeueOutputBuffer(info, 100_000)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
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
                                    AppLog.v(TAG, "[${profile.name}] $encFrameCount frames encoded, " +
                                        "${encBytesSinceLog / 1024}KB in last 150")
                                    encBytesSinceLog = 0
                                }
                            }
                        }
                        enc.releaseOutputBuffer(idx, false)
                    }
                }
            }
        } catch (t: Throwable) {
            if (running) AppLog.w(TAG, "drain loop error: ${t.message}")
        }
    }

    /**
     * Pulls VPS/SPS/PPS out of the encoder's codec-config buffer.
     *
     * H.265 emits THREE parameter-set NALs where H.264 emits two, so this splits every NAL and
     * classifies by header type rather than assuming a count — the same reasoning (and the same
     * trap) as [LowBandwidthTranscoder.handleCodecConfig]. NALs keep their start codes; the
     * RTSP library strips them itself.
     */
    private fun handleCodecConfig(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        val bytes = ByteArray(info.size)
        buf.get(bytes)
        var vps: ByteArray? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in splitAnnexB(bytes)) {
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
            AppLog.w(TAG, "codec config had no SPS/PPS — not advertising")
            return
        }
        AppLog.i(TAG, "encoder params ready: " +
            (vps?.let { "vps=${it.size}B " } ?: "") + "sps=${s.size}B pps=${p.size}B")
        onParamsReady(ByteBuffer.wrap(s), ByteBuffer.wrap(p), vps?.let { ByteBuffer.wrap(it) })
    }

    private fun startCodeLen(nal: ByteArray): Int =
        if (nal.size >= 4 && nal[0] == Z && nal[1] == Z && nal[2] == Z && nal[3] == O) 4 else 3

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
            bytes.copyOfRange(from, if (idx + 1 < starts.size) starts[idx + 1] else bytes.size)
        }
    }

    companion object {
        private const val TAG = "ScreenCaptureEncoder"
        private const val Z: Byte = 0
        private const val O: Byte = 1
        private const val I_FRAME_INTERVAL_S = 2
        /** Kept in step with [LowBandwidthTranscoder]'s OUT_MIME. */
        private const val OUT_MIME = "video/hevc"
    }
}
