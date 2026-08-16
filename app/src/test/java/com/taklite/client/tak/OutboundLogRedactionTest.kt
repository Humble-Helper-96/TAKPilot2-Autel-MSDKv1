package com.taklite.client.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the redaction applied to every CoT this application logs on its way out (v1.6.2).
 *
 * THIS IS A SECURITY TEST, not a formatting one. The pilot PLI carries the video url and that
 * url carries the media-server password. The security review of 2026-08-03 recorded this
 * application as not writing a credential to the log or to logcat, and the outbound CoT log
 * added on 2026-08-15 would have undone that on its own. If these tests fail, the log is
 * leaking a password.
 */
class OutboundLogRedactionTest {

    @Test
    fun credentialsInAVideoUrlAreMasked() {
        val out = TakManager.redactCredentials(
            "rtsp://tak:hunter2@anchortak.link:8554/EVO2-B2-Low?tcp")
        assertEquals("rtsp://<user>:<pass>@anchortak.link:8554/EVO2-B2-Low?tcp", out)
        assertFalse("hunter2" in out)
        assertFalse("tak:hunter2" in out)
    }

    /** The real shape: a whole PLI with the url inside an XML attribute. */
    @Test
    fun credentialsAreMaskedInsideAFullCotEvent() {
        val xml = """<event version="2.0" type="a-f-G-U-C" uid="PILOT-1"><detail>""" +
            """<__video uid="v1" sensor="EVO2-B2-Pilot" """ +
            """url="rtsp://tak:hunter2@anchortak.link:8554/EVO2-B2-Low?tcp">""" +
            """<ConnectionEntry address="anchortak.link" port="8554"/></__video>""" +
            """</detail></event>"""
        val out = TakManager.redactCredentials(xml)
        assertFalse("the password must not survive", "hunter2" in out)
        assertTrue("the rest of the event must be intact", "uid=\"PILOT-1\"" in out)
        assertTrue("the host must stay readable", "anchortak.link" in out)
        assertTrue("<ConnectionEntry" in out)
    }

    /** A url with no credentials must come through untouched — over-redacting would make the
     *  log useless for the thing it was added to diagnose. */
    @Test
    fun aUrlWithoutCredentialsIsUnchanged() {
        val plain = "rtsp://anchortak.link:8554/EVO2-B2-Low?tcp"
        assertEquals(plain, TakManager.redactCredentials(plain))
        val marker = """<event type="a-u-G" uid="marker-cc742cef"><point lat="61.3" lon="-149.5"/></event>"""
        assertEquals(marker, TakManager.redactCredentials(marker))
    }

    @Test
    fun nullIsSafe() {
        assertEquals(null, TakManager.redactCredentials(null))
    }
}
