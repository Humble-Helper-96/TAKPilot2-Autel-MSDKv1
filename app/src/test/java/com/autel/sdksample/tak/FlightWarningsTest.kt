package com.autel.sdksample.tak

import com.autel.common.flycontroller.ARMWarning
import com.autel.common.flycontroller.FlyControllerStatus
import com.autel.common.flycontroller.FlyLimitAreaWarning
import com.autel.common.flycontroller.FlyMode
import com.autel.common.flycontroller.MainFlyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the warnings display policy (v1.6.0). Pure logic: a fake FlyControllerStatus
 * goes in through onStatus, and displayAt(now) gives the banner decision with a stepped
 * clock. No device, no SDK connection.
 */
class FlightWarningsTest {

    private val t0 = 1_000_000L

    /** A healthy status. Each test changes only the fields it examines. */
    private fun status(
        compassValid: Boolean = true,
        gpsValid: Boolean = true,
        mainFlyState: MainFlyState = MainFlyState.GPS,
        flyMode: FlyMode = FlyMode.GPS_FLIGHT,
        overheated: Boolean = false,
        windHigh: Boolean = false,
        reachMaxHeight: Boolean = false,
        reachMaxRange: Boolean = false,
        nearRangeLimit: Boolean = false,
        homePointValid: Boolean = true,
        limitArea: FlyLimitAreaWarning = FlyLimitAreaWarning.NORMAL,
    ): FlyControllerStatus = object : FlyControllerStatus {
        override fun getMainFlyState() = mainFlyState
        override fun getArmErrorCode() = ARMWarning.NORMAL
        override fun getFlyMode() = flyMode
        override fun isReachMaxHeight() = reachMaxHeight
        override fun isReachMaxRange() = reachMaxRange
        override fun isGpsValid() = gpsValid
        override fun isHomePointValid() = homePointValid
        override fun isCompassValid() = compassValid
        override fun isFlightControllerLostRemoteControllerSignal() = false
        override fun isFlightControllerOverHeated() = overheated
        override fun isOneClickTakeOffValid() = true
        override fun isTakeOffValid() = true
        override fun isWarmingUp() = false
        override fun isHomePointLocationAccurate() = true
        override fun isGoHomePending() = false
        override fun getFlyLimitAreaWarning() = limitArea
        override fun isStickLimited() = false
        override fun isNearRangeLimit() = nearRangeLimit
        override fun isWindTooHigh() = windHigh
        override fun isSupportRtk() = false
    }

    @Before
    fun resetState() {
        FlightWarnings.reset()
        FlightLimitsController.aircraftWarningPct = null
        FlightLimitsController.aircraftCriticalPct = null
    }

    @Test
    fun healthyStatusShowsNothing() {
        FlightWarnings.onStatus(status(), batteryPct = 80, airborne = true)
        assertNull(FlightWarnings.displayAt(t0))
    }

    @Test
    fun compassInterferenceIsRedAndImmediate() {
        FlightWarnings.onStatus(status(compassValid = false), batteryPct = 80, airborne = false)
        val d = FlightWarnings.displayAt(t0)!!
        assertEquals("COMPASS INTERFERENCE", d.text)
        assertTrue(d.red)
    }

    @Test
    fun gpsLossIsSilentOnTheGround() {
        FlightWarnings.onStatus(status(gpsValid = false), batteryPct = 80, airborne = false)
        assertNull(FlightWarnings.displayAt(t0))
    }

    @Test
    fun gpsLossShowsWhenAirborne() {
        FlightWarnings.onStatus(status(gpsValid = false), batteryPct = 80, airborne = true)
        assertEquals("GPS LOST — AIRCRAFT DRIFTS", FlightWarnings.displayAt(t0)!!.text)
    }

    @Test
    fun attitudeModeCountsAsGpsLoss() {
        FlightWarnings.onStatus(
            status(mainFlyState = MainFlyState.ATTITUDE), batteryPct = 80, airborne = true)
        assertEquals("GPS LOST — AIRCRAFT DRIFTS", FlightWarnings.displayAt(t0)!!.text)
    }

    @Test
    fun noFlyZoneStaysOffTheBanner() {
        // Log-only by operator decision (FAA exception). Active in the set, never displayed,
        // never counted in +N.
        FlightWarnings.onStatus(
            status(compassValid = false, limitArea = FlyLimitAreaWarning.AIRPORT_NO_FLY_ZONES),
            batteryPct = 80, airborne = true)
        val d = FlightWarnings.displayAt(t0)!!
        assertEquals("COMPASS INTERFERENCE", d.text)   // no "+1" from the no-fly zone
    }

    @Test
    fun worseWarningPreemptsImmediately() {
        FlightWarnings.onStatus(status(windHigh = true), batteryPct = 80, airborne = true)
        assertEquals("WIND TOO HIGH", FlightWarnings.displayAt(t0)!!.text)
        FlightWarnings.onStatus(
            status(windHigh = true, compassValid = false), batteryPct = 80, airborne = true)
        // 100 ms later, inside the hold window — the worse warning must still take the banner.
        val d = FlightWarnings.displayAt(t0 + 100)!!
        assertTrue(d.text.startsWith("COMPASS INTERFERENCE"))
        assertTrue(d.red)
    }

    @Test
    fun stackedWarningsShowACount() {
        FlightWarnings.onStatus(
            status(compassValid = false, windHigh = true), batteryPct = 80, airborne = true)
        assertEquals("COMPASS INTERFERENCE  +1", FlightWarnings.displayAt(t0)!!.text)
    }

    @Test
    fun clearedWarningHoldsThenHides() {
        FlightWarnings.onStatus(status(compassValid = false), batteryPct = 80, airborne = true)
        assertEquals("COMPASS INTERFERENCE", FlightWarnings.displayAt(t0)!!.text)
        FlightWarnings.onStatus(status(), batteryPct = 80, airborne = true)
        // Inside the 4 s hold: still visible, so a flicker cannot strobe the banner.
        assertEquals("COMPASS INTERFERENCE", FlightWarnings.displayAt(t0 + 2_000)!!.text)
        // After the hold: gone.
        assertNull(FlightWarnings.displayAt(t0 + 4_100))
    }

    @Test
    fun batteryUsesAircraftThresholdsAndIgnoresZero() {
        FlightLimitsController.aircraftCriticalPct = 10f
        FlightLimitsController.aircraftWarningPct = 25f
        // batteryPct 0 means "no battery frame yet", never a critical alarm.
        FlightWarnings.onStatus(status(), batteryPct = 0, airborne = true)
        assertNull(FlightWarnings.displayAt(t0))
        FlightWarnings.onStatus(status(), batteryPct = 20, airborne = true)
        assertEquals("BATTERY LOW", FlightWarnings.displayAt(t0 + 5_000)!!.text)
        FlightWarnings.onStatus(status(), batteryPct = 9, airborne = true)
        val d = FlightWarnings.displayAt(t0 + 10_000)!!
        assertEquals("BATTERY CRITICAL", d.text)
        assertTrue(d.red)
    }

    @Test
    fun autonomousReturnNamesItsReason() {
        FlightWarnings.onStatus(
            status(flyMode = FlyMode.RC_LOST_GO_HOME), batteryPct = 80, airborne = true)
        val d = FlightWarnings.displayAt(t0)!!
        assertEquals("RETURNING HOME — SIGNAL LOST", d.text)
        assertTrue(!d.red)
    }
}
