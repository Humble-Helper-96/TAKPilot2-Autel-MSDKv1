package com.autel.sdksample.tak

import android.content.Context
import com.taklite.client.tak.TakManager
import com.taklite.util.AppLog

/**
 * The single "shut everything down" path, shared by every way the app can be closed.
 *
 * **Why this exists:** a pilot who swipes the app away expects it to be closed, and until
 * 2026-08-02 it wasn't. `TakForegroundService.onTaskRemoved` disconnected TAK and stopped the
 * service, but nothing released the Autel SDK — and the SDK's connection lives at PROCESS
 * scope. Android keeps a swiped-away process cached, so the app went on holding the aircraft's
 * camera and video channels after the pilot believed it was gone. Those channels are
 * single-client: Autel Explorer came up to a grey screen because our invisible, TAK-less,
 * service-less process still owned the camera. Neither app said anything.
 *
 * Two exit paths used to do different subsets of the work — Stop & Quit tore down four things
 * and killed the process, task removal tore down one and killed nothing. Both now call
 * [releaseAll], so they cannot drift apart again.
 *
 * Order matters: consumers of the aircraft link go first (video capture, then the telemetry
 * bridge, then TAK), and the aircraft link itself last, so nothing is mid-write when the
 * transport disappears.
 */
object AppTeardown {
    private const val TAG = "AppTeardown"

    /**
     * Stops everything this app owns. Safe to call more than once and from any thread; every
     * step is individually guarded so one failure cannot abort the rest — that guarding is not
     * defensive padding, it is why logout used to fail (a throw from the closing TAK socket
     * skipped the steps after it).
     */
    fun releaseAll(context: Context) {
        AppLog.i(TAG, "releaseAll: shutting down video, bridge, TAK, services and the SDK")
        val ctx = context.applicationContext
        runCatching { ScreenCaptureService.stop(ctx) }
        runCatching { VideoStreamerHolder.stop() }
        runCatching { TakBridgeHolder.stop() }
        runCatching { TakManager.getInstance().disconnect() }
        runCatching { TakForegroundService.stop(ctx) }
        runCatching { AutelProductHolder.release() }
        AppLog.i(TAG, "releaseAll: done")
    }
}
