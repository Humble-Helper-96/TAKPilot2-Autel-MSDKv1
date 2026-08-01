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
            return
        }
        if (!hasSavedCerts(prefs)) {
            Log.i(TAG, "No saved enrollment — skipping auto-connect")
            return
        }
        reconnect(context)
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
            runCatching { TakForegroundService.stop(context.applicationContext) }
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
            TakManager.getInstance().connect(
                uid, username, "Cyan", "Team Member",
                host, cotPort, ts, "atakatak", cc, "atakatak",
            )
            // Re-apply the saved channel routing selection.
            (prefs.getString(KEY_CHANNELS, "") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                .let { if (it.isNotEmpty()) TakManager.getInstance().setChannels(it) }
            TakBridgeHolder.start("$uid-DRONE", callsign)
            TakBridgeHolder.setCameraPointEnabled(prefs.getBoolean(KEY_CAMERA_POINT, false))
            TakForegroundService.start(context.applicationContext, callsign)
            Log.i(TAG, "Auto-connected to $host:$cotPort as $callsign")
        }.start()
    }
}
