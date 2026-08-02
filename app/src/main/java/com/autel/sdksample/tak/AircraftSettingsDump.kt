package com.autel.sdksample.tak

import com.autel.common.CallbackWithOneParam
import com.autel.common.error.AutelError
import com.taklite.util.AppLog

/**
 * Reads every aircraft/controller setting this SDK exposes and writes them to the log, once per
 * connect.
 *
 * WHY. Most of these values are configured in Autel's own app, not here, and until now nothing
 * recorded what they actually were. That left several questions unanswerable from this side:
 * what the stick-mode enum corresponds to on the controller's own "Mode 1/2/3" screen, what the
 * normal gimbal-dial and yaw values are for this airframe, and what the low-battery thresholds
 * are set to — the last of which is directly relevant to the unexplained RTH behaviour (one
 * descent began near 14%, another near 24%).
 *
 * The intended workflow is exactly the operator's: set everything in Autel's app, close it
 * completely, then open this app. What lands in the log is the aircraft's real configuration.
 *
 * STRICTLY READ-ONLY. Every call here is a getter. Nothing in this file writes to the aircraft
 * or the controller, so it can run automatically at connect without changing how anything flies.
 *
 * ⚠ Autel's channels are SINGLE-CLIENT. If Autel's app is still running, these reads will time
 * out or return stale values and the dump will be misleading rather than empty — close it fully
 * before trusting what appears here.
 */
object AircraftSettingsDump {
    private const val TAG = "AircraftSettings"

    @Volatile private var dumpedForThisConnect = false

    fun onProductDisconnected() { dumpedForThisConnect = false }

    /**
     * Runs the dump once per connect.
     *
     * Deliberately fire-and-forget with each value logged as it lands, rather than gathered into
     * one tidy block: several of these calls silently never call back on some firmware, and a
     * dump that waits for a full set would then print nothing at all. A partial answer that
     * arrives beats a complete one that does not.
     */
    fun dumpOnce() {
        if (dumpedForThisConnect) return
        dumpedForThisConnect = true
        val evo = AutelProductHolder.evo2 ?: run { dumpedForThisConnect = false; return }
        AppLog.i(TAG, "---- aircraft settings dump (read-only) ----")

        fun <T> read(name: String, call: (CallbackWithOneParam<T>) -> Unit) {
            runCatching {
                call(object : CallbackWithOneParam<T> {
                    override fun onSuccess(v: T?) { AppLog.i(TAG, "  $name = $v") }
                    override fun onFailure(error: AutelError?) {
                        AppLog.i(TAG, "  $name = <failed: ${error?.description}>")
                    }
                })
            }.onFailure { AppLog.i(TAG, "  $name = <threw: ${it.message}>") }
        }

        val fc = evo.flyController
        read<String>("aircraft.serial") { fc.getSerialNumber(it) }
        read<Float>("flight.maxHeight_m") { fc.getMaxHeight(it) }
        read<Float>("flight.maxRange_m") { fc.getMaxRange(it) }
        read<Float>("flight.returnHeight_m") { fc.getReturnHeight(it) }
        read<Float>("flight.maxHorizontalSpeed_ms") { fc.getMaxHorizontalSpeed(it) }
        read<Float>("flight.maxVZUp_ms") { fc.getMaxVZUp(it) }
        read<Float>("flight.maxVZDown_ms") { fc.getMaxVZDown(it) }

        // Stick-feel family. These are the AIRCRAFT's response curves, distinct from the
        // controller's dial/yaw values below — worth capturing both to see which one the pilot
        // actually notices.
        read<Float>("feel.yawStrokeSensitivity") { fc.getYawStrokeSensitivity(it) }
        read<Float>("feel.pitchSensitivity") { fc.getPitchSensitivity(it) }
        read<Float>("feel.rollSensitivity") { fc.getRollSensitivity(it) }
        read<Float>("feel.brakeSensitivity") { fc.getBrakeSensitivity(it) }
        read<Float>("feel.gasPedalSensitivity") { fc.getGasPedalSensitivity(it) }
        read<Float>("feel.attiSensitivity") { fc.getATTISensitivity(it) }

        val rc = evo.remoteController
        read<String>("rc.serial") { rc.getSerialNumber(it) }
        // The one that settles the Mode 1/2/3 question: whatever this reports IS whatever the
        // controller's own Command Stick Mode screen currently has selected.
        read<com.autel.common.remotecontroller.RemoteControllerCommandStickMode>(
            "rc.commandStickMode") { rc.getCommandStickMode(it) }
        read<Int>("rc.gimbalDialAdjustSpeed") { rc.getGimbalDialAdjustSpeed(it) }
        read<Float>("rc.yawCoefficient") { rc.getYawCoefficient(it) }
        read<com.autel.common.remotecontroller.RFPower>("rc.rfPower") { rc.getRFPower(it) }
        read<com.autel.common.remotecontroller.RemoteControllerParameterUnit>(
            "rc.lengthUnit") { rc.getLengthUnit(it) }

        // Battery thresholds — these are NOTIFY levels, not the forced-RTH trigger, but they are
        // the only battery policy the SDK exposes and worth having on record next to the two
        // low-battery events that behaved differently.
        val bat = evo.battery
        read<Float>("battery.lowNotifyThreshold") { bat.getLowBatteryNotifyThreshold(it) }
        read<Float>("battery.criticalNotifyThreshold") { bat.getCriticalBatteryNotifyThreshold(it) }
        read<String>("battery.serial") { bat.getSerialNumber(it) }

        // Avoidance settings come through AutelAvoidance's own listener, logged there.
        AppLog.i(TAG, "  (obstacle avoidance is logged by AutelAvoidance)")
    }
}
