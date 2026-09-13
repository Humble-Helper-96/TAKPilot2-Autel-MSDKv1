package com.autel.sdksample.tak

/**
 * Media-callback policy that must stay pure: [AutelProductHolder]'s class init touches a
 * Handler and cannot load in a JVM test, so this predicate lives alone.
 */

/** The window after PHOTO_TAKEN_DONE in which a photo-failure report is treated as the
 *  firmware's duplicate. Observed gap 2026-08-13: ~20 ms. Three seconds gives two orders
 *  of magnitude of margin and stays short enough that an unrelated later failure is very
 *  unlikely to land inside it. */
internal const val SPURIOUS_PHOTO_FAIL_WINDOW_MS = 3000L

/**
 * True when a PHOTO_TAKEN_DONE names a file the camera actually wrote.
 *
 * ⚠ **A PHOTO_TAKEN_DONE IS NOT BY ITSELF PROOF THAT A PHOTO WAS SAVED** (measured in flight
 * 2026-09-12, then checked against the SD card). The camera reports DONE for a shutter whose
 * VISIBLE capture failed, and in that case the event carries no detail. When the capture
 * really did produce a file, the detail is the thumbnail url naming it.
 *
 * The evidence, from one flight cross-referenced with the card:
 *
 *  - Stills 0022, 0023 and 0025 each logged a DONE **with** a url, and both halves are on the
 *    card (MAX_xxxx.JPG and the IRX_xxxx triplet).
 *  - Stills 0021 and 0024 logged a DONE with **no** url, and **MAX_0021.JPG and MAX_0024.JPG do
 *    not exist**. Those two shutters lost their visible frame.
 *
 * A successful capture also emits a SECOND, url-less DONE right after the first. That is why
 * this asks "did THIS event name a file", and why the caller must not let the second event
 * overwrite the timestamp of the first — see [isSpuriousPhotoFailure].
 */
internal fun photoDoneNamesAFile(detail: String?): Boolean = !detail.isNullOrBlank()

/**
 * True when a media-state failure is the firmware's known duplicate report of a capture
 * that already succeeded: on 2026-08-13 the camera delivered PHOTO_TAKEN_DONE (photo on
 * the card) and fired "The take photo is failed" ~20 ms later for the same shutter.
 *
 * Matches on words, not the exact string, so a firmware wording change does not break it.
 *
 * ⚠ **`sincePhotoDoneMs` MUST BE MEASURED FROM A DONE THAT NAMED A FILE** — see
 * [photoDoneNamesAFile]. It was measured from ANY done until 2026-09-12, and that silently
 * suppressed REAL failures: a failed shutter emits a url-less DONE and then its failure about
 * 1 ms later, which lands inside this window and looks exactly like the firmware's duplicate.
 * Still 0021 was lost that way and the log called it spurious. Feeding this the confirmed
 * timestamp instead puts that failure 13 s outside the window, where it belongs.
 *
 * Accepted trade: a genuine second-shot failure inside the window logs at INFO instead of
 * WARN — the description still reaches the log, and the shutter flow in FlightActivity
 * logs its own per-call failures, so a real rejection is never silent.
 */
internal fun isSpuriousPhotoFailure(description: String, sincePhotoDoneMs: Long): Boolean =
    sincePhotoDoneMs < SPURIOUS_PHOTO_FAIL_WINDOW_MS &&
    description.contains("photo", ignoreCase = true) &&
    description.contains("fail", ignoreCase = true)
