package com.autel.sdksample.tak

/**
 * Which horizontal field of view goes on the wire for the lens that is live.
 *
 * ⚠ **IN PIP THE CAMERA REPORTS THE THERMAL FIELD, AND THE TEAM SEES THE VISIBLE ONE.** Measured
 * on the bench 2026-09-14: with the camera in PictureInPicture the 2 Hz status push carried
 * `fov=33.0`, the thermal lens's figure, and the bridge published a 33° cone over a picture
 * whose frame is the visible lens's 66.8°. The camera point sits at the frame centre, inside
 * the thermal window either way, but the CONE told every client the aircraft was looking
 * through a lens half as wide as the picture it was streaming. The operator's decision
 * (2026-09-14): a blend publishes the VISIBLE cone.
 *
 * Pure, no SDK import, so the choice is pinned by a unit test rather than by a flight.
 *
 * @param liveHFov the horizontal field the camera itself reports, null before it has spoken.
 * @param calibratedHFov the application's calibratable visible-lens figure.
 * @param irHFov the thermal constant, the fallback for a camera that has not reported yet.
 */
internal fun publishedHFov(
    lens: AutelTakBridge.Lens,
    liveHFov: Double?,
    calibratedHFov: Double,
    irHFov: Double,
): Double = when (lens) {
    // The camera's live figure says "thermal" while the picture is the visible frame: the
    // calibrated visible figure wins, whether or not the camera has spoken.
    AutelTakBridge.Lens.BLEND -> calibratedHFov
    // The camera reports the field for whatever lens is live, thermal included, so when it is
    // talking the lens is not consulted. The constants are the fallback for a camera that has
    // not reported yet.
    AutelTakBridge.Lens.IR -> liveHFov ?: irHFov
    AutelTakBridge.Lens.EO -> liveHFov ?: calibratedHFov
}
