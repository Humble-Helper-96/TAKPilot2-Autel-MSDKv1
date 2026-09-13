package com.autel.sdksample.tak

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the spurious photo-failure predicate (2026-08-13: DONE, then "The take
 *  photo is failed" ~20 ms later for a capture that was on the card). */
class MediaCallbackPolicyTest {

    private val incidentString = "The take photo is failed"

    @Test
    fun duplicateFailureInsideWindowIsSpurious() {
        assertTrue(isSpuriousPhotoFailure(incidentString, sincePhotoDoneMs = 20L))
        assertTrue(isSpuriousPhotoFailure(incidentString, sincePhotoDoneMs = 2_999L))
    }

    @Test
    fun failureAfterWindowIsReal() {
        assertFalse(isSpuriousPhotoFailure(incidentString, sincePhotoDoneMs = 5_000L))
    }

    @Test
    fun failureWithNoPriorCaptureIsReal() {
        assertFalse(isSpuriousPhotoFailure(incidentString, sincePhotoDoneMs = Long.MAX_VALUE))
    }

    @Test
    fun unrelatedFailureInsideWindowIsReal() {
        assertFalse(isSpuriousPhotoFailure("SD card removed", sincePhotoDoneMs = 20L))
    }

    // ---- photoDoneNamesAFile: the 2026-09-12 flight, cross-referenced with the SD card ----

    @Test
    fun `a done carrying a thumbnail url names a file`() {
        // Still 0011, taken during a recording. MAX_0011.JPG is on the card.
        assertTrue(photoDoneNamesAFile(
            "http://127.0.0.1:8080/thumbnail?path=/DCIM/100MEDIA/MAX_0011.JPG&type=0"))
    }

    @Test
    fun `a done with no detail names nothing`() {
        // Stills 0021 and 0024 logged exactly this, and MAX_0021.JPG and MAX_0024.JPG do not
        // exist on the card. Treating this as a confirmed capture is what hid the loss.
        assertFalse(photoDoneNamesAFile(null))
        assertFalse(photoDoneNamesAFile(""))
        assertFalse(photoDoneNamesAFile("   "))
    }

    @Test
    fun `a failure 1ms after an UNCONFIRMED done is not spurious`() {
        // The 0021 case. The url-less done must not have moved the timestamp, so the window is
        // measured from the last CONFIRMED capture — 13 seconds earlier — and this is a real
        // failure, not the firmware's duplicate.
        assertFalse(isSpuriousPhotoFailure(incidentString, sincePhotoDoneMs = 13_091L))
    }

    @Test
    fun `a failure 27ms after a CONFIRMED done is still spurious`() {
        // The 0022 case: done with a url, then a url-less done, then the failure. Both halves
        // are on the card. This must stay suppressed.
        assertTrue(isSpuriousPhotoFailure(incidentString, sincePhotoDoneMs = 27L))
    }

    // ---- isPhotoFailure: the pilot is told, and an empty description does not silence it ----

    @Test
    fun `a failure that names a photo is a photo failure`() {
        assertTrue(isPhotoFailure(incidentString, sinceUnconfirmedDoneMs = Long.MAX_VALUE))
        // The firmware uses both words. A match on one only would be a coin toss.
        assertTrue(isPhotoFailure("Take picture failed", sinceUnconfirmedDoneMs = Long.MAX_VALUE))
    }

    @Test
    fun `a failure with NO description right after an unconfirmed done is a photo failure`() {
        // ⚠ THE REGRESSION THIS PINS. AutelError.description can be null, and the caller has
        // only "unknown" to put in its place. Word-matching alone called that "not a photo",
        // set no failed flag, and the flight screen fell through to "Photo Saved" for a still
        // that was never written. The url-less done 1 ms earlier is what identifies it.
        assertTrue(isPhotoFailure("unknown", sinceUnconfirmedDoneMs = 1L))
        assertTrue(isPhotoFailure("", sinceUnconfirmedDoneMs = 999L))
    }

    @Test
    fun `the url-less done that trails a real capture is an echo, not a lost still`() {
        // The 0022 sequence: done with a url, then a url-less done a few ms later. If that echo
        // armed the clock, a firmware duplicate failure carrying no description would be read
        // as a loss and the pilot would be told a SAVED photo was lost.
        assertTrue(isTrailingDoneOfAConfirmedCapture(sinceConfirmedDoneMs = 5L))
        // The 0021 sequence: a url-less done with no confirmed capture behind it, 13 s after
        // the last good one. That is a lost still and must arm the clock.
        assertFalse(isTrailingDoneOfAConfirmedCapture(sinceConfirmedDoneMs = 13_091L))
        assertFalse(isTrailingDoneOfAConfirmedCapture(sinceConfirmedDoneMs = Long.MAX_VALUE))
    }

    @Test
    fun `an unrelated failure long after any unconfirmed done is not a photo failure`() {
        // A recording fault must not put a photo notice on the flight screen.
        assertFalse(isPhotoFailure("SD card removed", sinceUnconfirmedDoneMs = Long.MAX_VALUE))
        assertFalse(isPhotoFailure("unknown", sinceUnconfirmedDoneMs = 1_000L))
    }
}
