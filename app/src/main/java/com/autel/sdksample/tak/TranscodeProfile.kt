package com.autel.sdksample.tak

/**
 * The pilot-selectable video-quality tiers for the outbound RTSP push.
 *
 * Consumed by [ScreenCaptureEncoder], which caps the captured screen to [maxHeight] (a CEILING
 * on the vertical dimension, NOT a format — the screen's aspect ratio is preserved and the width
 * follows from it, so a 4:3 controller at the 720 tier is 960x720, not 1280x720) and encodes at
 * [fps] / [bitrateBps].
 *
 * **Each tier carries one resolution step MORE than the DJI blueprint** (480/720/1080 here vs
 * 360/480/720 there). That extra step is bought with H.265, which delivers roughly the same
 * quality as H.264 at about half the bitrate — the saving spent on resolution rather than
 * bandwidth. (H.264 was tried briefly on 2026-08-02 to chase a keyframe pulse and reverted the
 * same day: the pulse went, but at the same bitrate the picture was visibly worse. See
 * [ScreenCaptureEncoder]'s `OUT_MIME` for the full history.)
 *
 * **Bitrates are set from bits-per-pixel-per-frame, not picked round.** All three now sit at the
 * same bits/pixel/frame (~0.14 at the flight screen's 1.47:1 aspect), so a profile change trades
 * resolution and frame rate WITHOUT changing per-pixel quality — which is what makes them
 * comparable choices for a pilot. Screen capture makes the top of this ladder matter: HUD text,
 * map labels and AR callouts are sharp high-frequency edges that cost far more bits than camera
 * video and smear first, and an illegible altitude readout defeats the point of streaming the
 * screen at all.
 */
enum class TranscodeProfile(val maxHeight: Int, val fps: Int, val bitrateBps: Int) {
    // Bitrates raised for STANDARD/HIGH on 2026-08-01 to kill a 2-SECOND PIXELATED PULSE visible
    // to stream viewers (never on the controller — the artifact is created by the re-encode, so it
    // exists only in the outgoing stream). Cause: a full IDR every I_FRAME_INTERVAL_S (2s) under
    // FORCED CBR, with no keyframe headroom. STANDARD/HIGH now sit near 0.115 bits/pixel/frame,
    // roughly double, which is the headroom a periodic IDR needs at these sizes.
    //
    // LOW was raised to match, at the operator's call (2026-08-01): at 275k it looked "really bad",
    // and video too degraded to read is not survivability, it is just a smaller stream.
    // ⚠ Note what this trades: LOW is no longer the minimum-bandwidth floor it was designed as. If
    // a genuinely marginal link ever needs one, add a new profile below this rather than pushing
    // LOW back down and re-breaking the picture.
    LOW(480, 10, 475_000),        // marginal links — now matched to STANDARD's bits/pixel
    STANDARD(720, 15, 1_600_000), // default — keyframe headroom over the old 800k
    HIGH(1080, 15, 3_600_000);    // same bits/pixel as STANDARD, at 1080p

    companion object {
        fun fromPref(name: String?): TranscodeProfile = when (name) {
            "low" -> LOW
            "high" -> HIGH
            else -> STANDARD
        }
    }
}
