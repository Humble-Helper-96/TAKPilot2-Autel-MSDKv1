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
 * True when a media-state failure is the firmware's known duplicate report of a capture
 * that already succeeded: on 2026-08-13 the camera delivered PHOTO_TAKEN_DONE (photo on
 * the card) and fired "The take photo is failed" ~20 ms later for the same shutter.
 *
 * Matches on words, not the exact string, so a firmware wording change does not break it.
 * Accepted trade: a genuine second-shot failure inside the window logs at INFO instead of
 * WARN — the description still reaches the log, and the shutter flow in FlightActivity
 * logs its own per-call failures, so a real rejection is never silent.
 */
internal fun isSpuriousPhotoFailure(description: String, sincePhotoDoneMs: Long): Boolean =
    sincePhotoDoneMs < SPURIOUS_PHOTO_FAIL_WINDOW_MS &&
    description.contains("photo", ignoreCase = true) &&
    description.contains("fail", ignoreCase = true)
