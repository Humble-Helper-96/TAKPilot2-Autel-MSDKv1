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
}
