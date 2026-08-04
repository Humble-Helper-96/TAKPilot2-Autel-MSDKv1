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
 * **Bitrates are set from bits-per-pixel-per-frame, not picked round.** STANDARD and HIGH sit at
 * the same ~0.077 bits/pixel/frame, so moving between those two trades resolution and frame rate
 * WITHOUT changing per-pixel quality. LOW is the deliberate exception at ~0.12 — see the note on
 * the constants. Screen capture makes the top of this ladder matter: HUD text,
 * map labels and AR callouts are sharp high-frequency edges that cost far more bits than camera
 * video and smear first, and an illegible altitude readout defeats the point of streaming the
 * screen at all.
 */
enum class TranscodeProfile(val maxHeight: Int, val fps: Int, val bitrateBps: Int) {
    // STANDARD and HIGH were doubled on 2026-08-01 to buy keyframe headroom against a 2-SECOND
    // PIXELATED PULSE. That was treating the symptom: the real cause was that the encoder was
    // being asked for CBR, which this chip's HEVC encoder does not even declare (probe:
    // VBR=true CBR=false), so IDRs were quantised down to fit a per-frame budget. Switching to
    // VBR on 2026-08-04 fixed it outright — I/P frame-size ratio 2.53x -> 14x, pulse confirmed
    // gone by the operator on the live stream — so the bought headroom is no longer needed and
    // STANDARD/HIGH are back at their original targets. The bandwidth was the point: this is a
    // shared tactical hotspot.
    //
    // LOW IS NOT ON THE SAME CURVE AS THE OTHER TWO, ON PURPOSE. It sits at ~0.12 bits/pixel/frame
    // against STANDARD/HIGH at ~0.077 — richer per pixel, but far lower in TOTAL bitrate, because
    // what makes it the marginal-link tier is the total: it is the one that survives a poor
    // cellular uplink. Small frames at 10fps are cheap enough to afford good pixels.
    //
    // History: 275k originally, raised to 475k on 2026-08-01 when the operator judged 275k
    // "really bad" (video too degraded to read is not survivability, it is just a smaller
    // stream). Trimmed to 375k on 2026-08-04 to buy back cellular headroom now that VBR spends
    // the budget properly — 275k was NOT retried, since VBR redistributes bits rather than
    // creating them and would not rescue it.
    //
    // ⚠ So the three tiers no longer share bits/pixel, and LOW can look BETTER per pixel than
    // STANDARD. That is the design, not a bug — do not "fix" it by flattening the curve.
    LOW(480, 10, 375_000),        // marginal/cellular links — lowest total bitrate, see above
    STANDARD(720, 15, 800_000),   // default — original target, restored once VBR fixed the pulse
    HIGH(1080, 15, 1_800_000);    // same bits/pixel as STANDARD, at 1080p

    /** The `video_profile` pref value. Derived, so a new tier cannot be saved under a typo. */
    val prefValue: String get() = name.lowercase()

    /**
     * What the pilot sees in a picker. Deliberately JUST the tier name — no resolution, fps or
     * bitrate. Those numbers change (they have three times now), and a menu that restates them
     * is one more place to forget to update; the tiers are already ordered, which is the only
     * thing a pilot needs to choose between them.
     */
    val label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }

    companion object {
        fun fromPref(name: String?): TranscodeProfile =
            values().firstOrNull { it.prefValue == name } ?: STANDARD
    }
}
