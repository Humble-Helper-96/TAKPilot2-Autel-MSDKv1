package com.autel.sdksample.tak

import android.app.admin.DevicePolicyManager
import android.content.Context
import com.taklite.util.AppLog

/**
 * Keeps Autel Explorer out of the way while TAKPilot holds the aircraft.
 *
 * ## The problem
 *
 * `com.autelrobotics.explorer` is a preinstalled **system app** (uid 1000) that starts itself and
 * takes the aircraft USB link. It killed a live flight on 2026-08-02: a Firebase analytics job
 * started the process, and 3.8 seconds later the aircraft was gone. The pilot never opened it.
 * `am force-stop` recovers the link, but only until the next scheduled wake.
 *
 * Measured wake paths (manifest + live scheduler dump, 2026-08-02): a Firebase
 * `AppMeasurementJobService` job, a Mapbox flusher alarm every 3 minutes, `CONNECTIVITY_CHANGE`,
 * `BOOT_COMPLETED` x2, power connect/disconnect, battery low/okay, and time/timezone change.
 * **Disabling individual components is a losing game** — whole-package suppression is the only
 * shape that covers them. (Aircraft USB attach is NOT a wake path on this airframe: Explorer's
 * device filter lists `0x4b4:0x1004/0x1104`, `0x483:0x5710`, `0xaaaa:0xaa97`, and the EVO
 * enumerates as `18d1:5a55`. Re-check that if the airframe or the LC1881 module ever changes.)
 *
 * ## Why this mechanism
 *
 * `setApplicationHidden` is the same underlying mechanism as `pm hide`, which was validated on
 * this controller on 2026-08-02: hiding blocked launch, **cancelled the Mapbox alarm** and
 * **deregistered the Firebase job**; unhiding restored all three, job included. It needs device
 * owner, because an ordinary app cannot touch another package and `pm hide` needs shell.
 *
 * ## Safety
 *
 * Explorer is where a pilot does firmware updates, compass calibration and aircraft
 * registration. **Leaving it hidden is a worse failure than leaving it running.** So:
 *
 *  - Until the controller is provisioned as device owner, every call here is a NO-OP. Nothing
 *    changes on an unprovisioned device, which makes this safe to ship dark.
 *  - [restore] is called from [AppTeardown], from `BOOT_COMPLETED`, from the Debug screen, and
 *    from `onDisabled` if admin rights are ever revoked.
 *  - [deprovision] exists and is reachable from the Debug screen. **It was written before any
 *    provisioning step was documented, deliberately** — device owner without a removal path can
 *    mean a factory reset.
 *  - Even so there is one gap that no in-app code can close: if the app is killed while Explorer
 *    is hidden and never launched again, Explorer stays hidden until the next boot or a manual
 *    restore. Given the app was OOM-killed in flight on 2026-08-02 this is not hypothetical.
 *    The ADB escape hatch is `adb shell pm unhide com.autelrobotics.explorer`.
 */
object ExplorerSuppressor {

    private const val TAG = "TP2Explorer"
    const val EXPLORER_PKG = "com.autelrobotics.explorer"

    private const val PREFS = "takpilot2_explorer"
    private const val KEY_ENABLED = "suppression_enabled"
    private const val KEY_WE_HID_IT = "we_hid_explorer"

    private fun dpm(context: Context): DevicePolicyManager? =
        context.applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE)
            as? DevicePolicyManager

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True once the controller has been provisioned (see the provisioning note in the plan doc). */
    fun isDeviceOwner(context: Context): Boolean =
        runCatching { dpm(context)?.isDeviceOwnerApp(context.packageName) == true }.getOrDefault(false)

    /**
     * Operator's switch. Default **off**: suppression is opt-in even after provisioning, so a
     * provisioned controller does not silently change behaviour when a build lands.
     */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /** The single gate for the whole feature. False on an unprovisioned or switched-off
     *  controller, where nothing anywhere should change behaviour. */
    fun isAvailable(context: Context): Boolean = isEnabled(context) && isDeviceOwner(context)

    /**
     * True iff WE hid Explorer and have not yet put it back.
     *
     * A pure SharedPreferences read — no binder call — so it is safe from any thread and from
     * notification-building. Deliberately not [isHidden], which is a round trip to the framework
     * and would also return true for a package hidden by something else.
     *
     * This is the exact predicate for "the foreground-service anchor must stay alive", and it
     * survives process death, so a restore owed before an OOM kill is still owed afterwards.
     */
    fun isRestoreOwed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WE_HID_IT, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        AppLog.i(TAG, "Explorer suppression ${if (on) "ENABLED" else "DISABLED"} by operator")
        if (!on) restore(context, "operator turned suppression off")
    }

    /** Whether the package is hidden right now, as reported by the framework. */
    fun isHidden(context: Context): Boolean = runCatching {
        val admin = TakDeviceAdminReceiver.component(context)
        dpm(context)?.isApplicationHidden(admin, EXPLORER_PKG) == true
    }.getOrDefault(false)

    /**
     * Hides Explorer, if provisioned and enabled. No-op otherwise.
     *
     * Records that WE hid it, so [restore] never unhides a package that was already hidden for
     * some other reason — un-hiding something we did not hide is its own surprise.
     */
    fun suppress(context: Context): Boolean {
        if (!isEnabled(context)) return false
        if (!isDeviceOwner(context)) {
            AppLog.i(TAG, "suppression on but this controller is not provisioned — nothing to do")
            return false
        }
        if (isHidden(context)) return true
        val ok = runCatching {
            dpm(context)?.setApplicationHidden(
                TakDeviceAdminReceiver.component(context), EXPLORER_PKG, true) == true
        }.getOrElse {
            AppLog.w(TAG, "hiding Explorer threw: ${it.message}"); false
        }
        if (ok) {
            prefs(context).edit().putBoolean(KEY_WE_HID_IT, true).apply()
            AppLog.i(TAG, "Explorer HIDDEN — its Firebase job and Mapbox alarm are deregistered")
        } else {
            AppLog.w(TAG, "could not hide Explorer — it may still take the aircraft link")
        }
        return ok
    }

    /**
     * Unhides Explorer. Called from every exit path, and safe to call at any time.
     *
     * Deliberately does NOT check [isEnabled] — turning suppression off must still restore, and
     * so must a boot after a crash, whatever the current setting says.
     */
    fun restore(context: Context, reason: String): Boolean {
        if (!isDeviceOwner(context)) return false
        val weHidIt = prefs(context).getBoolean(KEY_WE_HID_IT, false)
        if (!weHidIt && !isHidden(context)) return true
        val ok = runCatching {
            dpm(context)?.setApplicationHidden(
                TakDeviceAdminReceiver.component(context), EXPLORER_PKG, false) == true
        }.getOrElse {
            AppLog.w(TAG, "restoring Explorer threw: ${it.message}"); false
        }
        if (ok) {
            prefs(context).edit().putBoolean(KEY_WE_HID_IT, false).apply()
            AppLog.i(TAG, "Explorer RESTORED ($reason)")
        } else {
            AppLog.w(TAG, "COULD NOT RESTORE EXPLORER ($reason) — " +
                "recover with: adb shell pm unhide $EXPLORER_PKG")
        }
        return ok
    }

    /**
     * Gives up device-owner status, restoring Explorer first.
     *
     * The order is not negotiable: clearing device owner first would leave us hidden with no
     * rights to unhide, and no way back without adb.
     */
    fun deprovision(context: Context): Boolean {
        if (!isDeviceOwner(context)) return true
        restore(context, "deprovisioning")
        return runCatching {
            dpm(context)?.clearDeviceOwnerApp(context.packageName)
            AppLog.i(TAG, "device owner CLEARED")
            true
        }.getOrElse {
            AppLog.w(TAG, "clearDeviceOwnerApp failed: ${it.message}")
            false
        }
    }

    /** One line for the Debug screen. */
    fun statusLine(context: Context): String = when {
        !isDeviceOwner(context) ->
            "Not provisioned. Explorer suppression is unavailable and nothing is being changed."
        !isEnabled(context) -> "Provisioned, suppression off. Explorer runs normally."
        isHidden(context) -> "Explorer is HIDDEN. It cannot start and cannot take the aircraft."
        else -> "Provisioned and on. Explorer is visible right now."
    }
}
