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
}
