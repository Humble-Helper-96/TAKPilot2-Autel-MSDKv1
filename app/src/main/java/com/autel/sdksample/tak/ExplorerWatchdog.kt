package com.autel.sdksample.tak

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import com.taklite.util.AppLog

/**
 * Keeps Autel Explorer from seizing the aircraft link — WITHOUT any permanent change to the
 * controller.
 *
 * ## The problem
 *
 * `com.autelrobotics.explorer` is a preinstalled system app that starts itself (a Firebase
 * analytics job, a Mapbox alarm, connectivity/power/time changes) and, when it starts, seizes the
 * aircraft USB link. It killed a live flight on 2026-08-02 — 3.8 s from an analytics job to a lost
 * aircraft, with the pilot never having opened it.
 *
 * ## The mechanism — proven on hardware 2026-08-03
 *
 * `ActivityManager.killBackgroundProcesses(EXPLORER_PKG)` kills Explorer's background process.
 * Measured against every dangerous state:
 *  - its Firebase auto-wake (the flight-killer path): KILLED
 *  - even after it has grabbed the USB link: KILLED
 *  - a cached/background process: KILLED
 *  - **foreground (the pilot opened it): NOT killed — which is correct.** If a pilot is in
 *    Explorer on purpose (firmware, compass calibration, registration), leave it alone.
 *
 * It needs only the normal `KILL_BACKGROUND_PROCESSES` permission and makes **no permanent
 * change**: it kills a process that respawns on its next wake. So this is a WATCHDOG, not a
 * disable — it cannot stop Explorer waking, it cuts each wake short. That turns "3.8 s to a dead
 * aircraft" into a momentary hiccup.
 *
 * ⚠ This deliberately REPLACES the earlier device-owner design (`setApplicationHidden`). That
 * required `dpm set-device-owner`, a permanent/hard-to-reverse change to the operator's
 * controller, which is forbidden. Do not reintroduce it.
 *
 * ## Three triggers, all no-change
 *  1. On app launch — [onAppStart].
 *  2. Reactively, on Explorer's own `com.autel.maxifly.usb.attach`/`.reset` broadcasts — the
 *     instant it touches the link.
 *  3. A low-frequency poll while the app process is alive, as a backstop for wakes that do not
 *     broadcast.
 *
 * On by default: it is safe (no permanent change) and defends the link without the pilot needing
 * to know it exists. Turn it off from the Debug screen if it ever gets in the way.
 */
object ExplorerWatchdog {

    const val EXPLORER_PKG = "com.autelrobotics.explorer"
    private const val TAG = "TP2Explorer"
    private const val PREFS = "takpilot2_explorer"
    private const val KEY_ENABLED = "watchdog_enabled"
    private const val POLL_MS = 8_000L

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var started = false
    private var appContext: Context? = null

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, on).apply()
        AppLog.i(TAG, "Explorer watchdog ${if (on) "ENABLED" else "DISABLED"} by operator")
        if (on) killExplorer(context)
    }

    /**
     * Kills Explorer's BACKGROUND process. No-op if Explorer is foreground (the pilot is using it)
     * or not running. No permanent change — Explorer can start again on its next wake.
     */
    fun killExplorer(context: Context) {
        if (!isEnabled(context)) return
        runCatching {
            (context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .killBackgroundProcesses(EXPLORER_PKG)
        }.onFailure { AppLog.w(TAG, "killBackgroundProcesses failed: ${it.message}") }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Explorer is touching the aircraft USB link — knock its background process down now.
            AppLog.i(TAG, "Explorer USB broadcast (${intent.action}) — killing its background process")
            killExplorer(context.applicationContext)
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            appContext?.let { killExplorer(it) }
            handler.postDelayed(this, POLL_MS)
        }
    }

    /** Call once from `Application.onCreate`. Registers the reactive receiver and starts the poll. */
    fun onAppStart(context: Context) {
        if (started) return
        started = true
        val ctx = context.applicationContext
        appContext = ctx
        killExplorer(ctx)   // knock down any Explorer already awake when we launch
        runCatching {
            val filter = IntentFilter().apply {
                addAction("com.autel.maxifly.usb.attach")
                addAction("com.autel.maxifly.usb.reset")
            }
            ctx.registerReceiver(usbReceiver, filter)
        }.onFailure { AppLog.w(TAG, "USB receiver register failed: ${it.message}") }
        handler.postDelayed(poll, POLL_MS)
        AppLog.i(TAG, "Explorer watchdog started — kill-on-wake, no controller change")
    }

    /** One line for the Debug screen. */
    fun statusLine(context: Context): String =
        if (isEnabled(context))
            "On. The app closes Autel Explorer when it takes the aircraft link. You open " +
                "Explorer as usual."
        else
            "Off. Autel Explorer can take the aircraft link and stop the video."
}
