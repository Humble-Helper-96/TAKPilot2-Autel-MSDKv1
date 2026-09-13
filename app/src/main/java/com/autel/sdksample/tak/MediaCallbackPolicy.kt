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

/** The window after an UNCONFIRMED PHOTO_TAKEN_DONE in which a failure with no usable
 *  description is attributed to that shutter. Observed gap 2026-09-12: ~1 ms. One second is
 *  three orders of magnitude of margin and stays far inside the interval between two manual
 *  shutter presses. See [isPhotoFailure]. */
internal const val UNCONFIRMED_DONE_FAILURE_WINDOW_MS = 1000L

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
 * True when a media-state failure is about a STILL, thus the pilot must be told the photo did
 * not save.
 *
 * ⚠ **A WORD MATCH ALONE IS NOT ENOUGH, BECAUSE THE DESCRIPTION CAN BE EMPTY.** The SDK gives
 * `AutelError.description`, and the caller has nothing to put in its place when that is null.
 * A failure with no words in it matched no word, thus it set no flag — and the flight screen
 * then fell through to the "Photo Saved" notice for a photo that was never written. That is
 * the 2026-09-12 fault in a second form, and it is why this takes a TIME as well as a string.
 *
 * `sinceUnconfirmedDoneMs` is measured from a PHOTO_TAKEN_DONE that named NO file — see
 * [photoDoneNamesAFile]. That event is the shape of a lost still: the camera reports done,
 * names nothing, and fails about 1 ms later. A failure that lands in that window belongs to
 * that shutter whatever it calls itself.
 *
 * Matches on words rather than the exact string, for the reason given on
 * [isSpuriousPhotoFailure], and on "picture" as well as "photo" because the firmware uses both.
 *
 * Accepted trade: an unrelated failure inside the window — a card removed at the same moment,
 * say — is reported to the pilot as a lost photo. With the card gone the photo is indeed lost,
 * so the notice is still true, and a false "the photo did not save" costs one repeated shutter
 * press. A false "Photo Saved" costs the frame.
 */
/**
 * True when a PHOTO_TAKEN_DONE that named no file is the TRAILING ECHO of a capture that just
 * succeeded, rather than the report of a lost still.
 *
 * Both look identical on their own — a done, carrying nothing. The sequence tells them apart:
 *
 *  - A real capture emits a done WITH a url and then a second, url-less done (stills 0022,
 *    0023 and 0025; both halves on the card).
 *  - A lost still emits the url-less done ALONE (stills 0021 and 0024; MAX_0021.JPG and
 *    MAX_0024.JPG never written).
 *
 * So a url-less done that follows a CONFIRMED one closely is the echo. It must not arm the
 * clock [isPhotoFailure] reads, or a firmware duplicate failure carrying no description would
 * be attributed to it and the pilot would be told a saved photo was lost — the 2026-09-12
 * fault with the sign reversed, which is no better.
 *
 * Reuses [SPURIOUS_PHOTO_FAIL_WINDOW_MS] rather than adding a fourth number: it is the same
 * question over the same measured gap of milliseconds, with the same two orders of magnitude
 * of margin.
 */
internal fun isTrailingDoneOfAConfirmedCapture(sinceConfirmedDoneMs: Long): Boolean =
    sinceConfirmedDoneMs < SPURIOUS_PHOTO_FAIL_WINDOW_MS

internal fun isPhotoFailure(description: String, sinceUnconfirmedDoneMs: Long): Boolean =
    description.contains("photo", ignoreCase = true) ||
    description.contains("picture", ignoreCase = true) ||
    sinceUnconfirmedDoneMs < UNCONFIRMED_DONE_FAILURE_WINDOW_MS

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
