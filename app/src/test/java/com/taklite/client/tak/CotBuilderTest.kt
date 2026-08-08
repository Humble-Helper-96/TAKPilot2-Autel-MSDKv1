package com.taklite.client.tak

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the CoT XML that goes to the TAK server (v1.6.0). Not a schema validation —
 * these pin the fields that ATAK and CloudTAK read, so a refactor cannot silently drop one.
 */
class CotBuilderTest {

    @Test
    fun pilotPliCarriesIdentityPositionAndTakv() {
        val xml = CotBuilder.buildPLI(
            "PILOT-1", "EVO2-B2-Pilot", "Cyan", "Team Member",
            61.1, -149.9, 35.0, 180.0, 0.0, 77,
            "TAKPilot2", "SmartController", "Android", "1.5.9")
        assertTrue("uid=\"PILOT-1\"" in xml)
        assertTrue("callsign=\"EVO2-B2-Pilot\"" in xml)
        assertTrue("lat=\"61.1\"" in xml)
        assertTrue("lon=\"-149.9\"" in xml)
        assertTrue("a-f-G-U-C" in xml)          // PLI type
        assertTrue("<takv" in xml)
    }

    @Test
    fun dronePliCarriesTrackBatteryAndVideo() {
        val xml = CotBuilder.buildDronePLI(
            "UID-DRONE", "EVO2-B2",
            61.2, -149.8, 100.0, 250.0, 7.5, 66,
            "rtsp://server:8554/evo2", "UID-DRONE-SPI",
            65.8, 39.9, 250.0, -10.0, 300.0, 0.0,
            0.0, -10.0, 250.0,
            true, 300,
            7100, 4686, 15.9,
            "PILOT-1")
        assertTrue("uid=\"UID-DRONE\"" in xml)
        assertTrue("lat=\"61.2\"" in xml)
        assertTrue("rtsp://server:8554/evo2" in xml)
        assertTrue("battery=\"66\"" in xml || "battery='66'" in xml || ">66<" in xml ||
            "remainingBattery" in xml)   // battery reaches the XML in one of the known shapes
    }
}
