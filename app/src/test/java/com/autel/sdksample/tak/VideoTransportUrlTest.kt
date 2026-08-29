package com.autel.sdksample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the three addresses the video configuration produces.
 *
 * Every one of them fails INVISIBLY from the controller, which is why they are tested here and
 * not left to the bench:
 *
 *  - The PUSH address wrong: the stream never starts and the reason is at the server.
 *  - The ADVERTISED address wrong: the push is perfect and every viewer gets nothing. This is
 *    the failure that took a flight to find in 2026-08-12.
 *  - The MASKED address wrong: the one line a pilot reads to check the configuration lies.
 *
 * The SRT cases hold the shape the media server needs. The credentials are IN the stream id
 * there, which is also why the masking test below is a security control and not cosmetic.
 */
class VideoTransportUrlTest {

    /**
     * One server, configured for both protocols with DIFFERENT ports and DIFFERENT logins, so
     * a test that reads the wrong pair cannot pass by coincidence.
     */
    private fun cfg(
        transport: VideoTransport,
        rtspPort: Int = VideoTransport.RTSP.defaultPort,
        srtPort: Int = VideoTransport.SRT.defaultPort,
        rtspUser: String = "rtspuser",
        rtspPass: String = "rtsppass",
        srtUser: String = "srtuser",
        srtPass: String = "srtpass",
    ) = AutelVideoStreamer.VideoConfig(
        host = "stream.example.com",
        streamId = "UAS-ALPHA",
        transport = transport,
        rtspPort = rtspPort, rtspUser = rtspUser, rtspPass = rtspPass,
        srtPort = srtPort, srtUser = srtUser, srtPass = srtPass,
    )

    // ---- RTSP: unchanged behaviour, credentials in the protocol ----

    @Test
    fun rtspPushCarriesNoCredentials() {
        // They go to the client with setAuthorization. A url with them in it would ALSO work,
        // and would put the password in every log line that prints the push address.
        assertEquals(
            "rtsp://stream.example.com:8554/UAS-ALPHA-Low",
            cfg(VideoTransport.RTSP).pushUrl())
    }

    @Test
    fun rtspAdvertisesItsOwnPortWithCredentials() {
        assertEquals(
            "rtsp://rtspuser:rtsppass@stream.example.com:8554/UAS-ALPHA-Low?tcp",
            cfg(VideoTransport.RTSP).advertiseUrl())
    }

    @Test
    fun rtspKeepsANonDefaultPortInBothAddresses() {
        val c = cfg(VideoTransport.RTSP, rtspPort = 8654)
        assertTrue(":8654/" in c.pushUrl())
        assertTrue(":8654/" in c.advertiseUrl())
    }

    // ---- SRT: credentials in the stream id, playback on another port ----

    @Test
    fun srtPutsThePublisherPathAndCredentialsInTheStreamId() {
        assertEquals(
            "srt://stream.example.com:8890/publish:UAS-ALPHA-Low:srtuser:srtpass",
            cfg(VideoTransport.SRT).pushUrl())
    }

    @Test
    fun srtWithNoUsernameSendsThePathAlone() {
        // Not "publish:path::" — a server reading an empty user and an empty password is a
        // different request from a server reading no credentials at all.
        assertEquals(
            "srt://stream.example.com:8890/publish:UAS-ALPHA-Low",
            cfg(VideoTransport.SRT, srtUser = "", srtPass = "").pushUrl())
    }

    /**
     * ⚠ THE ONE THAT MATTERS. An SRT push advertised as `srt://…` reaches no TAK client, and
     * the failure looks like a dead feed rather than a wrong address.
     */
    @Test
    fun srtIsStillAdvertisedAsRtspOnThePlaybackPort() {
        val url = cfg(VideoTransport.SRT).advertiseUrl()
        // ⚠ The RTSP login and the RTSP port, NOT the SRT ones — the SRT pair authorises a
        // publish and its port is an ingest port. Neither means anything to a viewer.
        assertEquals("rtsp://rtspuser:rtsppass@stream.example.com:8554/UAS-ALPHA-Low?tcp", url)
        assertFalse("SRT credentials leaked into the CoT address", "srtuser" in url)
        assertFalse("the CoT must never carry an srt address", url.startsWith("srt://"))
        assertFalse("8890 is the ingest port, not a playback port", "8890" in url)
    }

    @Test
    fun theIngestPortDoesNotFollowTheStreamToThePlaybackAddress() {
        // Ingest on a private port, playback on another: the two must not be confused.
        val c = cfg(VideoTransport.SRT, srtPort = 9999, rtspPort = 8654)
        assertTrue(":9999/" in c.pushUrl())
        assertTrue(":8654/" in c.advertiseUrl())
        assertFalse("the ingest port reached the CoT address", "9999" in c.advertiseUrl())
    }

    /**
     * ⚠ THE POINT OF SPLITTING THEM. An SRT push still advertises an RTSP address, so the RTSP
     * port and login are LIVE under SRT — they are not the unused half of a choice. This was a
     * hardcoded 8554 and a shared login until 2026-08-29, which meant a server serving RTSP on
     * another port could not be advertised at all.
     */
    @Test
    fun theRtspDetailsAreUsedWhileTheTransportIsSrt() {
        val c = cfg(VideoTransport.SRT, rtspPort = 8654, rtspUser = "viewer", rtspPass = "vpass")
        assertEquals(
            "rtsp://viewer:vpass@stream.example.com:8654/UAS-ALPHA-Low?tcp",
            c.advertiseUrl())
    }

    // ---- The masked form: what the screen and the log are allowed to show ----

    @Test
    fun theMaskedUrlNeverCarriesThePasswordOnEitherTransport() {
        for (t in VideoTransport.values()) {
            val safe = cfg(t).urlSafe()
            assertFalse("password leaked into $t preview: $safe", "rtsppass" in safe)
            assertFalse("password leaked into $t preview: $safe", "srtpass" in safe)
            assertTrue("$t preview lost the mask: $safe", "***" in safe)
        }
    }

    /**
     * A set password and an empty one must not look the same. They did once, and a stream was
     * flown with no password while the screen showed stars for it (2026-08-05).
     */
    @Test
    fun anEmptyPasswordSaysSoRatherThanShowingStars() {
        for (t in VideoTransport.values()) {
            val safe = cfg(t, rtspPass = "", srtPass = "").urlSafe()
            assertTrue("$t hid an empty password: $safe", "(NO PASSWORD)" in safe)
        }
    }

    @Test
    fun theMaskedUrlIsThePushAddressAndUsesThePushScheme() {
        assertTrue(cfg(VideoTransport.SRT).urlSafe().startsWith("srt://"))
        assertTrue(cfg(VideoTransport.RTSP).urlSafe().startsWith("rtsp://"))
    }

    // ---- The pref round trip ----

    @Test
    fun anUnknownOrMissingTransportPrefFallsBackToTheOneTheFleetFlies() {
        assertEquals(VideoTransport.RTSP, VideoTransport.fromPref(null))
        assertEquals(VideoTransport.RTSP, VideoTransport.fromPref(""))
        assertEquals(VideoTransport.RTSP, VideoTransport.fromPref("quic"))
        assertEquals(VideoTransport.SRT, VideoTransport.fromPref("srt"))
    }

    @Test
    fun everyTransportSurvivesItsOwnPrefValue() {
        for (t in VideoTransport.values()) {
            assertEquals(t, VideoTransport.fromPref(t.prefValue))
        }
    }

    // ---- The passphrase is a KEY, and it belongs in no address ----

    /**
     * ⚠ A SECURITY CONTROL, not a formatting test. The SRT passphrase encrypts the stream. It
     * is not part of the stream id, thus it must never reach the push url, the masked preview
     * that goes in the log, or the address that goes on the wire in the CoT.
     */
    @Test
    fun thePassphraseNeverAppearsInAnyAddress() {
        val c = cfg(VideoTransport.SRT).copy(srtPassphrase = "AnEncryptionKey")
        for (url in listOf(c.pushUrl(), c.advertiseUrl(), c.urlSafe())) {
            assertFalse("passphrase leaked into: $url", "AnEncryptionKey" in url)
        }
    }

    /** An empty passphrase is the correct configuration for a server that sets none, so it
     *  must not change the address either. */
    @Test
    fun noPassphraseChangesNothingAboutTheAddresses() {
        val withOut = cfg(VideoTransport.SRT)
        val with = withOut.copy(srtPassphrase = "AnEncryptionKey")
        assertEquals(withOut.pushUrl(), with.pushUrl())
        assertEquals(withOut.advertiseUrl(), with.advertiseUrl())
    }

    // ---- The latency budget, and the units trap ----

    /**
     * ⚠ THE ONE THAT CATCHES THE UNIT MISTAKE. SRT latency is quoted in MICROSECONDS in an
     * `srt://…?latency=` url, so half a second reads as 500000 in the places people copy from.
     * This value goes into a 16-BIT MILLISECOND field in the handshake, and the writer keeps the
     * low 16 bits silently: 500000 would go out as 41248 ms — a stream forty seconds behind, from
     * a number that looks right. The clamp is what makes that impossible.
     */
    @Test
    fun theMicrosecondFormIsRefusedRatherThanTruncated() {
        assertEquals(
            VideoTransport.SRT_LATENCY_DEFAULT_MS,
            VideoTransport.clampLatencyMs(500_000))
        // What the truncation WOULD have produced, if the clamp were ever removed.
        assertEquals(41_248, 500_000 and 0xFFFF)
    }

    @Test
    fun theDefaultIsFiveHundredMilliseconds() {
        // Three to four times the 116 ms RTT measured on the ground path, conservative end.
        // A change here is a change to what every pilot flies — see the class doc on the
        // constant for the evidence and for how to tell whether it is still right.
        assertEquals(500, VideoTransport.SRT_LATENCY_DEFAULT_MS)
    }

    @Test
    fun aSaneOverrideIsKept() {
        assertEquals(350, VideoTransport.clampLatencyMs(350))
        assertEquals(VideoTransport.SRT_LATENCY_MIN_MS,
            VideoTransport.clampLatencyMs(VideoTransport.SRT_LATENCY_MIN_MS))
        assertEquals(VideoTransport.SRT_LATENCY_MAX_MS,
            VideoTransport.clampLatencyMs(VideoTransport.SRT_LATENCY_MAX_MS))
    }

    @Test
    fun anImpossibleOverrideFallsBackToTheDefault() {
        for (bad in listOf(0, -1, 119, 4_001, Int.MAX_VALUE)) {
            assertEquals("$bad should not have been accepted",
                VideoTransport.SRT_LATENCY_DEFAULT_MS, VideoTransport.clampLatencyMs(bad))
        }
    }

    /** The budget has to hold several round trips or a repair lands after its own deadline —
     *  which is the 250 ms failure the ground test measured. */
    @Test
    fun theDefaultIsAtLeastThreeRoundTripsOnTheMeasuredPath() {
        val measuredRttMs = 116
        assertTrue(VideoTransport.SRT_LATENCY_DEFAULT_MS >= 3 * measuredRttMs)
    }
}
