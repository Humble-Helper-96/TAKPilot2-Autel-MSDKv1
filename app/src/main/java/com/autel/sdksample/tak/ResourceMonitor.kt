package com.autel.sdksample.tak

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import com.taklite.client.tak.TakManager

/**
 * Live memory/contact-count diagnostics for the flight-screen overlay (see [AppLog.resourceMonitor]).
 *
 * Added 2026-08-03 while chasing a sequence of app-process OOM kills near busy airspace: the
 * "known contacts" count (see [TakManager.getTakUsers]) is included deliberately, since a stale
 * CoT-retention bug in [com.taklite.client.tak.CotParser] was found to be holding every distinct
 * ADS-B contact for a MINIMUM of ~10 minutes regardless of how briefly it was actually live — the
 * app showed 161 "known" contacts while a second TAK client showed a handful. That specific bug
 * is fixed, but the count stays on the overlay as the fastest way to see whether it (or a future
 * variant of it) is recurring, alongside the raw memory figures, right up to a crash.
 */
object ResourceMonitor {

    data class Snapshot(
        val sysAvailMb: Int,
        val sysTotalMb: Int,
        val lowMemory: Boolean,
        val appPssMb: Int,
        val heapUsedMb: Int,
        val heapMaxMb: Int,
        val contactCount: Int,
    )

    fun snapshot(context: Context): Snapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        runCatching { am.getMemoryInfo(memInfo) }

        // Process-wide PSS (Proportional Set Size) — what ActivityManager itself weighs an app's
        // footprint by, so this is the same number the OS's own low-memory decision is based on.
        val pssMb = runCatching {
            am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()
                ?.let { it.totalPss / 1024 } ?: 0
        }.getOrDefault(0)

        val rt = Runtime.getRuntime()
        val heapUsedMb = ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).toInt()
        val heapMaxMb = (rt.maxMemory() / (1024 * 1024)).toInt()

        val contactCount = runCatching { TakManager.getInstance().takUsers.size }.getOrDefault(-1)

        return Snapshot(
            sysAvailMb = (memInfo.availMem / (1024 * 1024)).toInt(),
            sysTotalMb = (memInfo.totalMem / (1024 * 1024)).toInt(),
            lowMemory = memInfo.lowMemory,
            appPssMb = pssMb,
            heapUsedMb = heapUsedMb,
            heapMaxMb = heapMaxMb,
            contactCount = contactCount,
        )
    }

    /** Compact multi-line text for the flight-screen overlay panel. */
    fun formatted(context: Context): String {
        val s = snapshot(context)
        val lowFlag = if (s.lowMemory) "  ⚠ LOW MEM" else ""
        return "SYS  ${s.sysAvailMb}/${s.sysTotalMb} MB free$lowFlag\n" +
            "APP  pss ${s.appPssMb}MB  heap ${s.heapUsedMb}/${s.heapMaxMb}MB\n" +
            "TAK  ${s.contactCount} known contacts"
    }
}
