package com.autel.sdksample.tak

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.taklite.util.AppLog
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A small, durable, append-only record of Emergency Broadcast events — separate from the normal
 * verbose [AppLog] line [TakManager][com.taklite.client.tak.TakManager] already writes for the
 * same event.
 *
 * WHY THIS EXISTS, AND WHY IT IS NOT IN taklite-core: Emergency Broadcast overrides a channel
 * restriction that exists specifically to withhold sensitive video from a broader audience. On a
 * public-safety deployment, a timestamped record of who enabled it and when belongs somewhere a
 * verbose log (which can rotate off, and is not what a reviewer would think to open) does not
 * cover on its own. But `com.taklite.client.tak` is vendor-neutral and shared with the DJI
 * sibling apps — it must not import Android's MediaStore/Environment, and Emergency Broadcast is
 * (for now) an Autel-only feature. So `TakManager` only fires its
 * `EmergencyBroadcastListener` callback; THIS object, in the application layer, is what persists
 * the record, mirroring `AppLog`'s own MediaStore public-archive pattern (own subfolder, own
 * small size cap, legacy-API fallback below Android 10) rather than inventing a new one.
 *
 * One line per event, plain text, newest appended last:
 * `<ISO-8601 UTC timestamp> <event> callsign=<callsign> [expiresAt=<ISO-8601 UTC>]`
 */
object EmergencyBroadcastLog {
    private const val PUBLIC_SUBFOLDER = "TAKPilot2 Logs"
    private const val FILE_NAME = "emergency-broadcast-audit.log"
    // Generous relative to this file's own tiny per-event footprint — this caps a file that
    // grows one line per toggle, not one line per tick like AppLog's archive.
    private const val MAX_FILE_SIZE_BYTES = 256L * 1024

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Builds one audit line — pure formatting, no I/O, so
     * [EmergencyBroadcastLogFormatTest][com.autel.sdksample.tak.EmergencyBroadcastLogFormatTest]
     * can exercise it with plain JUnit (no Context/MediaStore).
     *
     * @param event one of "enabled", "cancelled", "expired", "reset-on-reconnect" — the same
     *              `reason`/`active` combination TakManager's EmergencyBroadcastListener reports.
     * @param callsign the pilot/operator's callsign at the time of the event.
     * @param nowEpochMs when this event happened.
     * @param expiresAtEpochMs the override's expiry time, or 0 when this event has none (every
     *                         event except "enabled").
     */
    fun formatLine(event: String, callsign: String, nowEpochMs: Long, expiresAtEpochMs: Long = 0L): String =
        buildString {
            append(timestampFormat.format(Date(nowEpochMs))).append(' ').append(event)
            append(" callsign=").append(callsign)
            if (expiresAtEpochMs > 0L) {
                append(" expiresAt=").append(timestampFormat.format(Date(expiresAtEpochMs)))
            }
            append('\n')
        }

    /** Appends one audit line — see [formatLine] for the line this writes. */
    fun record(context: Context, event: String, callsign: String, expiresAtEpochMs: Long = 0L) {
        val line = formatLine(event, callsign, System.currentTimeMillis(), expiresAtEpochMs)
        try {
            val appContext = context.applicationContext
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeMediaStore(appContext, line)
            } else {
                writeLegacy(line)
            }
        } catch (t: Throwable) {
            // Best-effort, like AppLog's own public archive: this record must never be able to
            // take down the feature it is merely auditing.
            AppLog.w("EmergencyBroadcastLog", "failed to write audit line: ${t.message}")
        }
    }

    private fun writeMediaStore(context: Context, line: String) {
        val resolver = context.contentResolver
        val relPath = "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_SUBFOLDER"
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        // Find today's file if one already exists and is under the cap; MediaStore has no
        // append-by-name lookup, so query by display name + path.
        var uri: android.net.Uri? = null
        resolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.SIZE),
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
            arrayOf(FILE_NAME, "$relPath/"),
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                if (c.getLong(sizeCol) < MAX_FILE_SIZE_BYTES) {
                    uri = ContentUris.withAppendedId(collection, c.getLong(idCol))
                }
            }
        }
        if (uri == null) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, relPath)
            }
            uri = resolver.insert(collection, values)
        }
        val target = uri ?: return
        resolver.openOutputStream(target, "wa")?.use { it.write(line.toByteArray()) }
    }

    private fun writeLegacy(line: String) {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), PUBLIC_SUBFOLDER)
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, FILE_NAME)
        FileWriter(f, true).use { it.append(line) }
    }
}
