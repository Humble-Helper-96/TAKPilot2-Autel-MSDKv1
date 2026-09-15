package com.autel.sdksample.tak

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutelBlendFormatTest {

    @Test
    fun `the camera holding exactly what was asked agrees`() {
        assertTrue(AutelBlendFormat.agrees("IR", 32768, 32767))
    }

    @Test
    fun `the factory values do not agree`() {
        // What the camera held before the first write: base None, ratio 65535 / 1.
        assertFalse(AutelBlendFormat.agrees("None", 65535, 1))
    }

    @Test
    fun `a different base does not agree even with the right ratio`() {
        assertFalse(AutelBlendFormat.agrees("Visible", 32768, 32767))
    }

    @Test
    fun `a null answer is not a match`() {
        assertFalse(AutelBlendFormat.agrees(null, 32768, 32767))
        assertFalse(AutelBlendFormat.agrees("IR", null, null))
    }
}
