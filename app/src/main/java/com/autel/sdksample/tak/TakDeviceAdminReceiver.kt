package com.autel.sdksample.tak

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import com.taklite.util.AppLog

/**
 * Device-admin component. Exists for exactly ONE capability: letting [ExplorerSuppressor] call
 * `DevicePolicyManager.setApplicationHidden` on Autel Explorer.
 *
 * Explorer is a system app (uid 1000) that starts itself and seizes the aircraft USB link — it
 * killed a live flight on 2026-08-02. An ordinary APK cannot stop another app, and `pm hide`
 * needs shell. Device owner is the only route by which TAKPilot can do it, and undo it, itself.
 *
 * ⚠ NO OTHER POLICY IS DECLARED OR WANTED. `device_admin.xml` has an empty `<uses-policies>`.
 * This is not a fleet-management hook and must not grow into one: a public-safety controller
 * should not gain password, wipe or camera policy as a side effect of a link-contention fix.
 */
class TakDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: android.content.Intent) {
        AppLog.i(TAG, "device admin ENABLED — Explorer suppression is now available")
    }

    override fun onDisabled(context: Context, intent: android.content.Intent) {
        // Losing admin while Explorer is hidden would strand it hidden with no way back from
        // inside the app, so unhide on the way out. Best-effort: the call may already be denied
        // by the time this fires, which is exactly why the ADB escape hatch is documented.
        AppLog.w(TAG, "device admin DISABLED — attempting to restore Explorer before losing rights")
        runCatching { ExplorerSuppressor.restore(context, "device admin disabled") }
    }

    companion object {
        private const val TAG = "TP2DeviceAdmin"

        fun component(context: Context): ComponentName =
            ComponentName(context.applicationContext, TakDeviceAdminReceiver::class.java)
    }
}
