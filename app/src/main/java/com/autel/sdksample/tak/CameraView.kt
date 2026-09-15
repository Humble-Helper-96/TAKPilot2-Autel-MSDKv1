package com.autel.sdksample.tak

/**
 * What the camera SHOWS: the visible lens, the thermal lens, or the thermal lens drawn into
 * the centre of the visible picture. One value drives the wire, the fill rule and the pill.
 *
 * ⚠ **THIS REPLACED A BOOLEAN (v2.2.0).** `irOn` was the whole lens model, and a blend mode is
 * neither true nor false. Every consumer that asked "is IR on" now asks the question it really
 * had: [expectsThermalFrame] for the frame-shape check, [fitsWhole] for the fill rule,
 * [hasThermal] for the palette, [lens] for the bridge.
 *
 * ⚠ **PIP IS THE ONLY BLEND THIS FIRMWARE ACCEPTS** (XL726, firmware V5.0.6.49, measured on the
 * bench 2026-09-14). `SetDisplayMode Overlap` answers status -1 in every spelling, while
 * `PictureInPicture` answers 0 and reads back. The SDK enum still lists OVERLAP; a camera that
 * reports it (another firmware, Autel Explorer) is treated as this view, because it is a
 * blend with the video's shape and the visible lens's field, which is all this application
 * needs to know about it.
 *
 * **What PIP is on this camera, measured.** The thermal picture is drawn INTO THE CENTRE of
 * the visible frame at the visible lens's angular scale — the fence line, the tree trunks and
 * the utility boxes in the window line up with the same objects outside it. That is what
 * makes it a "meshed" view rather than a corner box. The registration is the camera's; this
 * application writes no offset (`SetIrPosition` exists, is clamped to ±20 px, and was 0,0 and
 * correct on the bench). The downlink stays one 1280x720 stream, so the application cannot
 * composite this itself — it can only ask the camera to.
 *
 * No SDK import on purpose, so the mapping is testable without the aar's Android half — see
 * [LensFramePolicy] for the same convention.
 */
enum class CameraView(
    /** The SDK `DisplayMode` constant name this view is written and read as. */
    val displayModeName: String,
    /** The pill label. Short, because the pill is 54dp — see specification §4.2. */
    val label: String,
    /** The bridge's lens: what the published camera-point cone is based on. */
    val lens: AutelTakBridge.Lens,
) {
    VISIBLE("VISIBLE", "IR", AutelTakBridge.Lens.EO),
    IR("IR", "IR", AutelTakBridge.Lens.IR),
    /** Thermal drawn into the centre of the visible picture, by the camera. */
    PIP("PICTURE_IN_PICTURE", "PIP", AutelTakBridge.Lens.BLEND);

    /** The thermal sensor is on screen, thus its palette applies. In PIP the palette colours
     *  the centre window (white hot on the bench). */
    val hasThermal: Boolean get() = this != VISIBLE

    /** Shown whole (FIT) rather than filled: only the bare thermal sensor, which has no
     *  resolution to give away. PIP is a 16:9 composite and fills like the visible modes. */
    val fitsWhole: Boolean get() = this == IR

    /** The shape the frame-size witness expects — see [lensAgreesWithFrame]. PIP arrives as
     *  the 1280x720 composite, measured 2026-09-14 (`MAX_0004` 1280x720p25). */
    val expectsThermalFrame: Boolean get() = this == IR

    /** The pill is lit (green) when the view is anything but the plain visible camera. */
    val active: Boolean get() = this != VISIBLE

    /** The next view on a tap of the IR pill or the C1 key: visible → PIP → thermal → visible
     *  (operator, 2026-09-14). PIP sits between the two plain cameras. */
    val next: CameraView get() = when (this) {
        VISIBLE -> PIP
        PIP -> IR
        IR -> VISIBLE
    }

    companion object {
        /**
         * The view for a `DisplayMode` the camera reported, by the constant's name.
         * OVERLAP maps to [PIP] (see the class note); UNKNOWN and null map to [VISIBLE], the
         * state the buttons showed before the camera answered, so a failed read changes nothing.
         */
        fun fromDisplayModeName(name: String?): CameraView = when (name) {
            "IR" -> IR
            "PICTURE_IN_PICTURE", "OVERLAP" -> PIP
            else -> VISIBLE
        }
    }
}
