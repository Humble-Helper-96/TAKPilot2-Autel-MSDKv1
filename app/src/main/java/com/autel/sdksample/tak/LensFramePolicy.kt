package com.autel.sdksample.tak

/**
 * Does the PICTURE agree with the lens this application believes is live?
 *
 * ⚠ **THE CAMERA WILL NOT CHANGE LENS WHILE IT IS RECORDING, AND IT RETURNS `OK` ANYWAY**
 * (measured in flight 2026-09-12). [FlightActivity.onIrTapped] refuses the request while the
 * camera records, which closes the KNOWN trigger. It does not close the general case: any lens
 * change that reports success and does not take leaves this application believing the wrong
 * lens is live, and the damage is on the wire — the camera point goes to the whole TAK team
 * tagged thermal, with the thermal field of view, over visible video.
 *
 * ⚠ **THE PROOF WAS ALREADY IN THE LOG AND NOTHING COMPARED IT.** On 12 September the log held
 * eight `setDisplayMode: OK` lines over 22 s beside a frame size that never moved from
 * 1920x1080. Five seconds after RECORD_STOP the same button gave `video frame size 640x512`
 * **13 ms** later. This application had both numbers and put neither next to the other; an SD
 * card was needed to settle what one comparison would have said at the time.
 *
 * **The frame shape is the independent witness.** The camera emits a different shape per mode
 * and [FlightActivity.armVideoFill] already receives it:
 *
 *  - thermal    640x512   = 1.25   (5:4)
 *  - video      1280x720  = 1.778  (16:9)
 *  - still      1280x960  = 1.333  (4:3)
 *
 * ⚠ **A STILL IS 4:3 AND THAT IS WHY THIS ANSWERS THREE WAYS, NOT TWO.** 1.333 sits between the
 * thermal 1.25 and the video 1.778, and it is nearer to the thermal one. A two-way rule with a
 * boundary between them would call a photo-mode frame "thermal" and raise a false alarm on a
 * lens that changed correctly. So the middle band answers [Verdict.INCONCLUSIVE] and says
 * nothing at all. This can prove the lens did NOT change; it does not pretend to prove more.
 */
internal enum class Verdict {
    /** The picture has the shape the believed lens produces. */
    AGREES,
    /** The picture has the shape the OTHER lens produces. The belief is wrong. */
    CONTRADICTS,
    /** Neither shape. No frame yet, or a still's 4:3. Say nothing. */
    INCONCLUSIVE,
}

/** Below this, the frame is the thermal sensor's 5:4 and nothing else. Half way between the
 *  thermal 1.25 and a still's 1.333, thus either shape needs to be about 3 % out before it is
 *  read as the other. */
internal const val THERMAL_FRAME_ASPECT_MAX = 1.29f

/** Above this, the frame is the 16:9 visible stream and nothing else. Half way between a
 *  still's 1.333 and the video 1.778. */
internal const val VIDEO_FRAME_ASPECT_MIN = 1.55f

/**
 * How long to wait after the camera reports the lens changed before the frame is believed.
 *
 * The measured turn-round when the change really takes is **13 ms**, so this is three orders of
 * magnitude of margin. It is generous on purpose: a slow answer costs a late warning, and a
 * hasty one costs a false alarm on the control a pilot uses to find people at night.
 */
internal const val LENS_VERIFY_SETTLE_MS = 1500L

/**
 * Compares the lens this application believes is live against the shape of the picture.
 *
 * @param believeIr what this application currently tells the bridge and the buttons.
 * @param frameAspect the live frame's width/height, 0 when no frame has arrived.
 */
internal fun lensAgreesWithFrame(believeIr: Boolean, frameAspect: Float): Verdict {
    if (!frameAspect.isFinite() || frameAspect <= 0f) return Verdict.INCONCLUSIVE
    val looksThermal = frameAspect < THERMAL_FRAME_ASPECT_MAX
    val looksVideo = frameAspect > VIDEO_FRAME_ASPECT_MIN
    return when {
        believeIr && looksThermal -> Verdict.AGREES
        believeIr && looksVideo -> Verdict.CONTRADICTS
        !believeIr && looksVideo -> Verdict.AGREES
        !believeIr && looksThermal -> Verdict.CONTRADICTS
        else -> Verdict.INCONCLUSIVE
    }
}
