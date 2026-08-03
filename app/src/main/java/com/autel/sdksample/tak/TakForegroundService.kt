package com.autel.sdksample.tak

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.taklite.client.tak.TakManager
import com.taklite.util.AppLog
import androidx.core.app.NotificationCompat
import com.autel.sdksample.R

/**
 * Keeps the TAK connection + drone PLI bridge alive while TAKPilot2 is backgrounded
 * or the screen is off. TakClient already auto-reconnects on socket drop; this service
 * just prevents Android from throttling/killing the process so the 2s PLI loop and the
 * RTSP push keep running during flight.
 *
 * The service doesn't own the connection — TakManager/TakBridgeHolder are process-wide
 * singletons. It just holds a foreground notification for as long as the bridge runs.
 */
class TakForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callsign = intent?.getStringExtra(EXTRA_CALLSIGN) ?: "TAKPilot2"
        // startForeground FIRST, before anything that could throw or take time: Android gives a
        // started foreground service ~5s to post its notification or it ANRs the app.
        startForeground(NOTIF_ID, buildNotification(callsign))

        // A null intent means START_STICKY resurrected us, not that somebody started us. If there
        // is also no task, this process has no activity, no recents entry, and therefore no
        // onTaskRemoved will EVER be delivered — so an Explorer restore owed at this point would
        // never be paid, and the notification would sit there permanently with no way to clear
        // it. This is reachable: the app was OOM-killed in flight on 2026-08-02, and a pilot
        // swiping the dead task away afterwards produces exactly this state.
        if (intent == null) {
            val tasks = runCatching {
                (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).appTasks
            }.getOrDefault(emptyList())
            if (tasks.isEmpty()) {
                AppLog.w("TP2Explorer", "sticky restart with no task — restoring Explorer, stopping")
                runCatching { ExplorerSuppressor.restore(applicationContext, "sticky restart, no task") }
                stopSelf()
                return START_NOT_STICKY
            }
        }

        AppLog.i(TAG, "TAK foreground service started ($callsign)")
        // Restart if the system kills us while flying — the bridge/connection persist.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLog.i(TAG, "TAK foreground service stopped")
    }

    /**
     * Fires ONLY when the whole app task is swiped away from recents — not on ordinary
     * backgrounding, not on screen-off, both of which this service exists specifically to
     * survive (see the class doc). A task removal is a much stronger signal: it is the one
     * thing short of the Home screen's explicit STOP/QUIT that unambiguously means "the pilot
     * is done with this app."
     *
     * Ported from the DJI sibling app (field-reported 2026-07-27): without this, removing the
     * app from recents did not disconnect TAK at all — [START_STICKY] just let Android restart
     * the service, silently re-establishing the very connection the pilot thought they'd closed,
     * and the operator's own presence stayed showing as connected on the TAK server indefinitely.
     * STOP/QUIT was the only thing that actually disconnected, and it is a separate,
     * easy-to-miss button under a "nuclear option" label — not what most pilots would reach for
     * or expect to need.
     *
     * stopSelf() after disconnecting so the notification doesn't linger claiming "connected"
     * once it no longer is, and so START_STICKY has nothing left to restart.
     */
    /**
     * The pilot swiped the app away. Treat that as CLOSED, not backgrounded.
     *
     * This used to disconnect TAK and stop the service, which left the Autel SDK connected —
     * and the SDK holds the aircraft at process scope, so the cached process went on owning the
     * camera and video channels after the app was gone from the screen. Those channels are
     * single-client, so Autel Explorer could not get video until the process was force-stopped.
     *
     * Killing the process is deliberate rather than lazy. [AppTeardown.releaseAll] calls
     * Autel.destroy() first, but the SDK owns native threads and sockets whose release we
     * cannot verify from here, and a cached process holding an aircraft link is exactly the
     * failure this is meant to prevent. A swipe should leave nothing behind. The short delay
     * lets the teardown above actually run before the process goes.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        AppLog.i(TAG, "app removed from recents — full teardown, then exit")
        runCatching { AppTeardown.releaseAll(applicationContext) }
        stopSelf()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            AppLog.i(TAG, "task removed — exiting process")
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 400)
    }

    private fun buildNotification(callsign: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "TAKPilot2 Link",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = "Keeps the TAK connection and drone feed alive" }
                )
            }
        }
        // Describes what is ACTUALLY connected, read at build time. The service now also runs
        // when only the aircraft is connected (see the companion's start()), and claiming
        // "Streaming to TAK" in that state would be a lie sitting in the notification shade.
        val aircraft = AutelProductHolder.isConnected
        val tak = runCatching { TakManager.getInstance().isConnected }.getOrDefault(false)
        val base = when {
            aircraft && tak -> "Aircraft connected · streaming $callsign to TAK"
            aircraft -> "Aircraft connected · not connected to TAK"
            tak -> "Streaming $callsign to TAK"
            else -> "Holding the link"
        }
        // Tell the pilot how to get Explorer back. Hiding Explorer removes its launcher icon, so
        // someone who needs a firmware update, compass calibration or aircraft registration finds
        // it simply GONE with no explanation. Without this line the only answers are the Debug
        // screen or adb — neither of which is discoverable in a field. The shade is where they
        // are already looking when they wonder what this app is doing.
        val text = if (ExplorerSuppressor.isRestoreOwed(this)) {
            "$base\nExplorer paused · swipe TAKPilot away to restore it"
        } else base
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentTitle("TAKPilot2 running")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "TakForegroundService"
        private const val CHANNEL_ID = "takpilot2_link"
        private const val NOTIF_ID = 4201
        private const val EXTRA_CALLSIGN = "callsign"

        /**
         * Starts (or refreshes) the service.
         *
         * Called on TAK connect AND on aircraft connect. The aircraft case is not about keeping
         * a bridge alive — it is about [onTaskRemoved] existing at all: Android only delivers
         * that callback to services that are RUNNING. Without a service, an app holding the
         * aircraft could be swiped away with no teardown whatsoever, leaving the SDK owning the
         * camera and video channels in a cached process (the Explorer grey-screen failure,
         * 2026-08-02). Connect the aircraft, never connect TAK, swipe — that was the hole.
         *
         * Repeat calls just re-deliver onStartCommand and refresh the notification text, which
         * is how the wording catches up when TAK connects after the aircraft.
         */
        fun start(context: Context, callsign: String) {
            val i = Intent(context, TakForegroundService::class.java)
                .putExtra(EXTRA_CALLSIGN, callsign)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        /**
         * The pilot's callsign, from the one place it is stored.
         *
         * Hoisted because three call sites had their own copy of the same
         * prefs-file / key / default triple, and a fourth was about to be added.
         */
        fun callsignFor(context: Context): String =
            context.applicationContext
                .getSharedPreferences("takpilot2_tak", Context.MODE_PRIVATE)
                .getString("callsign", "TAKPilot2-EVO2") ?: "TAKPilot2-EVO2"

        /**
         * Stops the service. **Teardown only.**
         *
         * Named to be awkward on purpose. This service is the delivery mechanism for
         * [onTaskRemoved], which is the only hook for "the pilot swiped the app away" — and that
         * is now what guarantees Autel Explorer gets un-hidden. Stopping it from anywhere except
         * [AppTeardown] silently removes that guarantee. If you want "my subsystem is finished
         * with this", use [releaseIfIdle] instead.
         */
        fun stopForTeardown(context: Context) {
            context.stopService(Intent(context, TakForegroundService::class.java))
        }

        /**
         * "This subsystem no longer needs the service — stop it only if nobody else does."
         *
         * Replaces the bare `stop()` calls that used to sit on TAK disconnect and Logout. Those
         * were already wrong before Explorer suppression existed: [AutelProductHolder] starts
         * this service on aircraft connect precisely so a swipe tears the aircraft down, and
         * tapping the TAK badge to disconnect destroyed that anchor while the aircraft was still
         * held. With suppression on it is worse — it would strand Explorer hidden.
         *
         * Keeps the service alive while the aircraft is connected, TAK is connected, or an
         * Explorer restore is owed. Otherwise stops it, which is byte-for-byte the old behaviour
         * on a controller with suppression off and nothing connected.
         */
        fun releaseIfIdle(context: Context) {
            val ctx = context.applicationContext
            val aircraft = AutelProductHolder.isConnected
            val tak = runCatching { TakManager.getInstance().isConnected }.getOrDefault(false)
            val owed = ExplorerSuppressor.isRestoreOwed(ctx)
            if (aircraft || tak || owed) {
                // Refresh rather than stop — this also corrects the notification wording for
                // whatever just disconnected.
                start(ctx, callsignFor(ctx))
            } else {
                stopForTeardown(ctx)
            }
        }
    }
}
