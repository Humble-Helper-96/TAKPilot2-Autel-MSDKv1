package com.autel.sdksample.tak

import android.content.Context
import android.util.Log
import com.taklite.client.tak.TakManager
import java.io.File
import java.util.UUID

/**
 * Silent TAK reconnect using previously-saved enrollment certs — used on app boot so the
 * operator lands in the flight screen already connected (no menu, no re-entry).
 *
 * Shares the same SharedPreferences keys as [TakConnectActivity].
 */
object TakAutoConnect {
    private const val TAG = "TakAutoConnect"
    private const val PREFS = "takpilot2_tak"
    private const val KEY_HOST = "host"
    private const val KEY_COT_PORT = "cot_port"
    private const val KEY_USERNAME = "username"
    private const val KEY_CALLSIGN = "callsign"
    private const val KEY_UID = "uid"
    private const val KEY_TRUSTSTORE = "truststore_path"
    private const val KEY_CLIENTCERT = "clientcert_path"
    private const val KEY_CAMERA_POINT = "camera_point"
    private const val KEY_LOGGED_OUT = "logged_out"
    private const val KEY_CHANNELS = "channels"

    /** Reconnect in the background if we have saved certs and aren't already connected. */
    fun tryReconnect(context: Context) {
        if (TakManager.getInstance().isConnected) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LOGGED_OUT, false)) {
            Log.i(TAG, "User logged out — skipping auto-connect")
            startTelemetryOnlyBridge(prefs)
            return
        }
        if (!hasSavedCerts(prefs)) {
            Log.i(TAG, "No saved enrollment — skipping auto-connect")
            startTelemetryOnlyBridge(prefs)
            return
        }
        reconnect(context)
    }

    /**
     * Arms the telemetry side of the bridge with no TAK connection (v1.5.9). The flight path
     * logger feeds from the bridge's fly-controller callback. Before this function existed,
     * the two paths above left the bridge stopped, so a controller with no enrollment (or a
     * logged-out one) recorded no flights. The bridge operates safely without TAK: its CoT
     * push does nothing while TAK is disconnected, and its other work (subscribe, cache
     * telemetry) is exactly what the logger needs. The uid and callsign fall back to
     * defaults. They reach the network only after a real connect, and a real connect
     * restarts the bridge with the enrolled identity. That restart is a swap, not a
     * teardown (stop(finalizeFlight = false) in TakBridgeHolder.start), so an enrollment
     * during a flight continues the same track file.
     */
    private fun startTelemetryOnlyBridge(prefs: android.content.SharedPreferences) {
        if (TakBridgeHolder.isRunning) return
        val callsign = prefs.getString(KEY_CALLSIGN, "TAKPilot2-EVO2") ?: "TAKPilot2-EVO2"
        val uid = prefs.getString(KEY_UID, "") ?: ""
        TakBridgeHolder.start(
            (uid.ifEmpty { "TAKPilot2-local" }) + "-DRONE", callsign)
        Log.i(TAG, "Telemetry-only bridge started (flight logging without TAK)")
    }

    /**
     * Flight-screen TAK badge tap: disconnect if connected, otherwise reconnect. Ported from
     * the DJI blueprint's `TakAutoConnect.toggle`.
     *
     * Deliberately does NOT set [KEY_LOGGED_OUT]. That flag means "the pilot signed out in
     * Pre-Flight Setup, do not auto-connect again"; a badge tap is a temporary in-flight
     * action, and treating it as a sign-out would silently stop the next launch from
     * reconnecting.
     */
    fun toggle(context: Context, onResult: (ok: Boolean, msg: String) -> Unit) {
        if (TakManager.getInstance().isConnected) {
            Log.i(TAG, "TAK icon tap — disconnecting")
            runCatching { TakManager.getInstance().disconnect() }
            // NOT stop(): disconnecting TAK does not mean the app is done. The aircraft may
            // still be connected — AutelProductHolder started this service precisely so a swipe
            // tears the aircraft down — and an Explorer restore may be owed.
            runCatching { TakForegroundService.releaseIfIdle(context.applicationContext) }
            onResult(true, "TAK disconnected")
            return
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!hasSavedCerts(prefs)) {
            onResult(false, "No saved TAK enrollment — set up the server in Pre-Flight Setup first")
            return
        }
        Log.i(TAG, "TAK icon tap — reconnecting")
        reconnect(context.applicationContext)
        onResult(true, "Reconnecting to TAK…")
    }

    /** Enrollment present and the cert files still on disk. Checked before every connect —
     *  a saved host with a deleted truststore would otherwise fail deep inside the socket. */
    private fun hasSavedCerts(prefs: android.content.SharedPreferences): Boolean {
        val host = prefs.getString(KEY_HOST, "") ?: ""
        val ts = prefs.getString(KEY_TRUSTSTORE, "") ?: ""
        val cc = prefs.getString(KEY_CLIENTCERT, "") ?: ""
        return host.isNotEmpty() && ts.isNotEmpty() && cc.isNotEmpty() &&
            File(ts).exists() && File(cc).exists()
    }

    /** The actual connect, on a background thread. Shared by [tryReconnect] and [toggle] so
     *  the two paths can't drift — the channel routing and bridge startup below are easy to
     *  forget in a second copy. */
    private fun reconnect(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_HOST, "") ?: ""
        val username = prefs.getString(KEY_USERNAME, "") ?: ""
        val cotPort = prefs.getInt(KEY_COT_PORT, 8089)
        val ts = prefs.getString(KEY_TRUSTSTORE, "") ?: ""
        val cc = prefs.getString(KEY_CLIENTCERT, "") ?: ""
        val callsign = prefs.getString(KEY_CALLSIGN, "TAKPilot2-EVO2") ?: "TAKPilot2-EVO2"

        var uid = prefs.getString(KEY_UID, "") ?: ""
        if (uid.isEmpty()) {
            uid = "TAKPilot2-" + UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString(KEY_UID, uid).apply()
        }

        Thread {
            // 2nd arg is the CALLSIGN, not the username — see the same fix in
            // TakConnectActivity.connectWithCerts. Both connect paths had it wrong, so the
            // aircraft reported the operator's login name to the whole team either way.
            TakManager.getInstance().connect(
                uid, callsign, "Cyan", "Team Member",
                host, cotPort, ts, "atakatak", cc, "atakatak",
            )
            // NOTHING IS RE-APPLIED. A controller can still hold a channel list saved by an
            // older build, and feeding it to setChannels would put <dest group> back on every
            // message and destroy the markers again. The channels live on the server now.
            TakBridgeHolder.start("$uid-DRONE", callsign)
            TakBridgeHolder.setCameraPointEnabled(prefs.getBoolean(KEY_CAMERA_POINT, false))
            TakForegroundService.start(context.applicationContext, callsign)
            Log.i(TAG, "Auto-connected to $host:$cotPort as $callsign")
        }.start()
    }
}
