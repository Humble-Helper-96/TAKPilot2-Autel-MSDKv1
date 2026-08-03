package com.autel.sdksample.tak

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.taklite.util.AppLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Arms Autel Explorer suppression when the pilot actually launches the app.
 *
 * ## The invariant this exists to hold
 *
 * > **The foreground service must exist for exactly as long as an Explorer restore is owed.**
 *
 * The service is not something that merely happens to be running during suppression — it *is*
 * the restore guarantee, because `TakForegroundService.onTaskRemoved` is the only callback
 * Android gives for "the pilot swiped the app away", and Android only delivers it to a service
 * that is RUNNING. Hide Explorer without a running service and a swipe leaves it hidden with
 * nothing to put it back.
 *
 * ## Why first-activity-created, and not Application.onCreate
 *
 * `Application.onCreate` is earlier, and suppression used to live there. It is wrong, because
 * onCreate also runs when the process is started by a BROADCAST — this app has a
 * `BootRestoreReceiver` (BOOT_COMPLETED) and a `UsbBroadCastReceiver`. In those cases no pilot
 * launched anything, there is no task, and therefore no `onTaskRemoved` will ever fire: hiding
 * Explorer there stranded it until the next reboot. It also made BOOT_COMPLETED hide Explorer
 * and then immediately un-hide it, so the boot receiver only worked by ordering luck.
 *
 * The cost is a few hundred milliseconds on a cold launch (mostly `Autel.init`), and it is
 * affordable for one specific reason: `setApplicationHidden(true)` **force-stops** the package,
 * so an Explorer that woke moments earlier is killed rather than merely hidden. Late suppression
 * still recovers the situation. The 2026-08-02 incident took 3.8s from Explorer starting to the
 * aircraft dropping, so a few hundred ms of that budget is a real but small cost.
 *
 * ⚠ **Activity COUNTS are not a "closed" signal and must never be used as one.** Android
 * destroys and recreates activities on configuration change, and restoring Explorer whenever the
 * count hit zero would un-hide it mid-flight — straight back into the link contention this
 * exists to prevent. Only `onActivityCreated` has a body here. Closing is handled by
 * [AppTeardown], reached from Stop & Quit and from `onTaskRemoved`.
 */
object TakSessionAnchor {

    private const val TAG = "TP2Explorer"

    private val armed = AtomicBoolean(false)

    /** Registers the first-activity hook. Safe to call once, from `Application.onCreate`. */
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (armed.compareAndSet(false, true)) {
                    arm(activity.applicationContext, "first activity (${activity.javaClass.simpleName})")
                }
            }
            // Deliberately empty — see the class doc on why counting activities is unsafe.
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * Starts the anchor, then hides Explorer. Idempotent; also the Debug-toggle entry point.
     *
     * ⚠ **ORDER IS NOT NEGOTIABLE: service first, hide second.** Between the two there is a
     * window where the anchor exists but nothing is owed — harmless, a stray notification. The
     * reverse order opens a window where a restore is owed and no anchor exists, which strands
     * Explorer. This is the mirror of [AppTeardown]'s "restore before stopping the anchor" rule.
     */
    fun arm(context: Context, reason: String) {
        val ctx = context.applicationContext
        // The single gate. On an unprovisioned or switched-off controller this returns
        // immediately and NOTHING changes — no notification, no hide. That is what lets this
        // ship dark and be enabled per controller.
        if (!ExplorerSuppressor.isAvailable(ctx)) return

        AppLog.i(TAG, "arming Explorer suppression — $reason")
        TakForegroundService.start(ctx, TakForegroundService.callsignFor(ctx))
        ExplorerSuppressor.suppress(ctx)
    }
}
