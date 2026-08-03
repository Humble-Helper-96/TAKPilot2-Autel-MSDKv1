package com.autel.sdksample.tak

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.taklite.util.AppLog

/**
 * Unhides Autel Explorer at boot.
 *
 * This is the crash net. [ExplorerSuppressor] hides Explorer while TAKPilot holds the aircraft
 * and unhides it from [AppTeardown] on the way out — but a process that is killed never runs its
 * exit path, and this app was OOM-killed in flight on 2026-08-02, so that is a real case rather
 * than a theoretical one. Without this, a pilot could reach for Explorer to do a firmware update
 * or a compass calibration and find it simply gone, with no indication why.
 *
 * Restoring at boot rather than re-suppressing is deliberate: the safe resting state for the
 * controller is Explorer AVAILABLE. Suppression re-applies when TAKPilot next starts and takes
 * the aircraft, which is the only time it is wanted.
 */
class BootRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        AppLog.init(context.applicationContext)
        AppLog.i(TAG, "boot completed — making sure Explorer is not left hidden")
        runCatching { ExplorerSuppressor.restore(context, "boot") }
    }

    companion object {
        private const val TAG = "TP2Explorer"
    }
}
