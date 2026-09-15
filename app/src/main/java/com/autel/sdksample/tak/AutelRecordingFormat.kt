package com.autel.sdksample.tak

import android.os.Handler
import android.os.Looper
import com.autel.common.CallbackWithNoParam
import com.autel.common.CallbackWithOneParam
import com.autel.common.camera.media.VideoEncodeFormat
import com.autel.common.camera.media.VideoEncoderConfiguration
import com.autel.common.camera.media.VideoFps
import com.autel.common.camera.media.VideoResolution
import com.autel.common.camera.media.VideoResolutionAndFps
import com.autel.common.error.AutelError
import com.autel.sdk.camera.AutelXT706
import com.taklite.util.AppLog

/**
 * What the aircraft WRITES TO THE CARD: 1920x1080 at 30 fps, in H.265.
 *
 * ⚠ **THIS IS THE CARD, NOT THE LIVE FEED.** The TAK stream is the screen-capture encoder at
 * 960x720 H.264 ([ScreenCaptureEncoder]) and nothing here touches it. The operator's standing
 * decision that H.265 stays off the WIRE until every client on the net can decode it is about
 * that stream and is not affected: a recording is played back later, on a workstation.
 *
 * ## Why
 *
 * The files from a 12.5-hour mission were huge (operator, 2026-09-14). The camera was left in
 * whatever Autel Explorer put it in, which for this airframe is up to 7680x4320. 1920x1080 is
 * about a quarter of 4K's pixels and H.265 is about half the bits of H.264 for the same
 * picture, and the two multiply.
 *
 * ⚠ **THERE IS NO SD RESOLUTION ON THIS CAMERA.** The operator asked for one. The XT706
 * supported table (`ResolutionFpsSupportUtil`, read from the aar) runs from 7680x4320 down to
 * `1280*720p24` and contains no 720x480 or 640x480. 1280x720 is the floor, and 1920x1080 was
 * chosen above it so the footage stays usable as evidence and can be cropped into.
 *
 * ⚠ **THE BITRATE IS NOT SETTABLE.** `VideoEncoderConfiguration` REPORTS bitrate, quality and
 * I-frame interval, and the whole aar has no setter for any of them. Size is controlled by the
 * resolution, the frame rate and the codec, and by nothing else. Do not look for a bitrate
 * control again.
 *
 * ## It is applied at EVERY connect, on purpose
 *
 * The operator's requirement (2026-09-14): the setting must survive a reboot and a swap between
 * this application and Autel Explorer. The camera keeps its own state across power cycles and
 * Explorer is free to change it, so the only way to hold it is to write it whenever a real
 * camera appears. That is what [AutelProductHolder]'s camera-change listener does.
 *
 * This is a CAMERA write, not a fly-controller write, thus safety rule 3 does not apply — but
 * it is still not on a timer. One write per camera session.
 *
 * ⚠ **REFUSED WHILE RECORDING.** The camera will not change what it is in the middle of
 * writing, and it is the camera that returns OK for things it did not do (safety rule 4, and
 * the lens-change case of 12 September). A write here during a recording could only be a lie.
 *
 * ## Verified by read-back, like everything else this camera is told
 *
 * `getVideoEncoderConfiguration` returns the codec AND the resolution/fps in one answer, so one
 * read checks both. ⚠ It is a single `CameraHttpRequest`, not one of this SDK's 2 Hz
 * subscription getters — confirmed in the bytecode before use (safety rule 1).
 */
object AutelRecordingFormat {

    private const val TAG = "TP2RecFormat"

    /** Chosen by the operator, 2026-09-14: "1920x1080 at 24 or 30fps". 30 is taken because it
     *  is in the XT706 supported table, it is the NTSC-region rate this fleet flies in, and it
     *  moves more smoothly on a pan than 24. */
    val RESOLUTION: VideoResolution = VideoResolution.Resolution_1920x1080
    val FPS: VideoFps = VideoFps.FrameRate_30ps
    val CODEC: VideoEncodeFormat = VideoEncodeFormat.H265

    /** Long enough for the camera to apply both writes before anything is read back. The lens
     *  verify uses the same figure for the same reason — see [LensFramePolicy]. */
    const val VERIFY_SETTLE_MS = 1500L

    /** What the last read-back said. Null until a camera has answered once. Read by nothing
     *  yet; it exists so the answer is in one place when a screen wants to show it. */
    @Volatile
    var lastVerified: Boolean? = null
        private set

    private val handler = Handler(Looper.getMainLooper())

    /**
     * True when the camera reports exactly what was asked for. Pure, so the comparison the
     * verify depends on can be tested without an aircraft.
     *
     * A null field is NOT a match: the camera answering "I do not know" is not the camera
     * answering "1080p H.265", and collapsing the two would report a silent revert as success.
     */
    fun agrees(
        resolution: VideoResolution?,
        fps: VideoFps?,
        codec: VideoEncodeFormat?,
    ): Boolean = resolution == RESOLUTION && fps == FPS && codec == CODEC

    /**
     * Write the format, then read it back.
     *
     * The codec goes first and the resolution second, DELIBERATELY. Both calls reach the camera
     * as a `setVideoEncoderConfiguration` carrying a configuration object whose other fields are
     * null — so if one write does clear the fields it does not carry, the resolution is the one
     * that lands last and survives. (`setVideoEncoder` and `setVideoEncodeFormat` are the same
     * call twice under two names; the bytecode of the two configuration objects is identical.)
     */
    fun applyAtConnect(camera: AutelXT706?) {
        camera ?: return
        if (AutelProductHolder.isRecording) {
            AppLog.i(TAG, "not applying the recording format: the camera is recording")
            return
        }
        AppLog.i(TAG, "applying recording format: $RESOLUTION $FPS $CODEC")
        camera.setVideoEncodeFormat(CODEC, object : CallbackWithNoParam {
            override fun onSuccess() {
                AppLog.i(TAG, "setVideoEncodeFormat($CODEC): OK")
                setResolution(camera)
            }
            override fun onFailure(error: AutelError?) {
                AppLog.w(TAG, "setVideoEncodeFormat($CODEC) failed: ${error?.description}")
                // The resolution is worth setting even with the codec refused — it is the
                // larger half of the size saving.
                setResolution(camera)
            }
        })
    }

    private fun setResolution(camera: AutelXT706) {
        val want = VideoResolutionAndFps(RESOLUTION, FPS)
        camera.setVideoResolutionAndFrameRate(want, object : CallbackWithNoParam {
            override fun onSuccess() {
                AppLog.i(TAG, "setVideoResolutionAndFrameRate($RESOLUTION $FPS): OK")
                handler.postDelayed({ verify(camera) }, VERIFY_SETTLE_MS)
            }
            override fun onFailure(error: AutelError?) {
                AppLog.w(TAG, "setVideoResolutionAndFrameRate failed: ${error?.description}")
                // Verify anyway. An OK is not proof and a failure is not proof either — what
                // the camera HOLDS is the only fact, and the pilot's card fills according to
                // that and not according to a callback.
                handler.postDelayed({ verify(camera) }, VERIFY_SETTLE_MS)
            }
        })
    }

    private fun verify(camera: AutelXT706) {
        camera.getVideoEncoderConfiguration(
            object : CallbackWithOneParam<VideoEncoderConfiguration> {
                override fun onSuccess(cfg: VideoEncoderConfiguration?) {
                    val res = cfg?.videoResolutionAndFps?.resolution
                    val fps = cfg?.videoResolutionAndFps?.fps
                    val codec = cfg?.encoding
                    val ok = agrees(res, fps, codec)
                    lastVerified = ok
                    // The bitrate is logged although it cannot be set: it is the number that
                    // says what an hour of card actually costs, and it is the only way to check
                    // the saving was real rather than assumed.
                    AppLog.i(TAG, "recording format read-back: $res $fps $codec " +
                        "bitrate=${cfg?.bitrate ?: -1} agrees=$ok")
                    if (!ok) {
                        AppLog.w(TAG, "THE CAMERA DID NOT TAKE THE RECORDING FORMAT. " +
                            "Asked for $RESOLUTION $FPS $CODEC, it holds $res $fps $codec")
                    }
                }
                override fun onFailure(error: AutelError?) {
                    lastVerified = null
                    AppLog.w(TAG, "recording format read-back failed: ${error?.description}")
                }
            })
    }
}
