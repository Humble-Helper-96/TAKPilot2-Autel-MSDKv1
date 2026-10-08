package com.autel.sdksample.tak

import android.content.Context
import com.taklite.client.tak.TakManager
import com.taklite.util.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What the APPLICATION does when an Emergency Broadcast starts, renews or ends (v2.3.9).
 *
 * Installed once at application start, so it runs whichever screen is open, or none — a
 * broadcast that expires while the pilot is on Pre-Flight Setup must still be recorded and
 * must still have its stream path rotated. [FlightActivity] only paints; it holds no policy.
 *
 * Two things, in this order:
 *
 *  1. **The flight record.** One line in the active flight's events file for every start,
 *     renew, stop and expiry — see [FlightPathLogger.event]. No "who" (operator, 2026-10-08).
 *  2. **The take-back.** When a broadcast ENDS by stop or expiry, and Random Path is on for the
 *     video server, the stream-path token is replaced and the push restarted under the new
 *     path. The Elevated audience is told the new path on the next position report and its
 *     alias updates in place; everyone who was given the link during the broadcast is left
 *     holding a name the server no longer serves. With Random Path off nothing is rotated and
 *     a link already saved keeps working until the next application launch — the operator's
 *     accepted trade (2026-10-08). The two options stay independent pre-flight choices.
 *
 * A `reset-on-reconnect` end does not rotate: the application is reconnecting or restarting,
 * and a restart makes a new token by itself.
 */
object EmergencyBroadcastPolicy {
    private const val TAG = "EmergencyBroadcastPolicy"

    private var appContext: Context? = null

    private val isoUtcFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val listener = TakManager.EmergencyBroadcastListener { active, expiresAtEpochMs, reason ->
        val ctx = appContext ?: return@EmergencyBroadcastListener
        FlightPathLogger.event(eventText(reason, expiresAtEpochMs))
        if (!active && (reason == "cancelled" || reason == "expired")) rotateStreamPath(ctx)
    }

    /** Call once, where the other application-wide singletons are initialised. */
    @JvmStatic
    fun install(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        TakManager.getInstance().addEmergencyBroadcastListener(listener)
        AppLog.i(TAG, "installed")
    }

    /** The flight-record line for one event. Pure, so `EmergencyBroadcastPolicyFormatTest`
     *  can pin it. `expiresAtEpochMs` is 0 for every event but a start or a renew. */
    internal fun eventText(reason: String, expiresAtEpochMs: Long): String =
        if (expiresAtEpochMs > 0L) {
            "emergency-broadcast $reason (expires ${isoUtcFormat.format(Date(expiresAtEpochMs))})"
        } else {
            "emergency-broadcast $reason"
        }

    private fun rotateStreamPath(ctx: Context) {
        val p = ctx.getSharedPreferences("takpilot2_tak", Context.MODE_PRIVATE)
        if (!p.getBoolean(StreamPath.PREF_RANDOMIZE, false)) {
            AppLog.i(TAG, "random path is off — the stream path is not rotated")
            return
        }
        StreamPath.rotateToken()
        if (VideoStreamerHolder.isActive) {
            // Rebuilds the config from the preferences, publishes under the new path and
            // re-advertises it — the same restart the quality picker uses mid-stream.
            ScreenCaptureService.restart(ctx)
            FlightPathLogger.event("stream path rotated (random path on) — stream restarted")
        } else {
            FlightPathLogger.event("stream path rotated (random path on)")
        }
    }
}
