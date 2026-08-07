package com.autel.sdksample.tak

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/**
 * One answer to "does this controller have a usable network?", shared by the home screen's
 * wifi line and the Pre-Flight enrollment check so the two can never disagree.
 *
 * THREE STATES, NOT TWO — the middle one is the whole point. A controller can hold a wifi
 * association to a hotspot that has no upstream, and that is exactly the state behind the
 * field reports of "TAK enrollment failed" (v1.5.9, event 1): the wifi icon looked fine, the
 * network went nowhere. So CONNECTED means the system CONFIRMED internet access
 * (NET_CAPABILITY_VALIDATED — Android's own captive-portal/reachability probe), not that an
 * SSID is attached. Association without validation is its own state, worded so the pilot
 * reads the network as the problem, not the TAK server.
 *
 * Polled, not listener-driven: the only consumers are Home's existing 1.5s refresh loop and
 * a one-shot check on Enroll & Connect, so a registered NetworkCallback would just be one
 * more thing to leak. Reading capabilities is cheap.
 */
object NetworkStatus {

    enum class State {
        /** Wifi attached AND the system validated internet access through it. */
        CONNECTED,
        /** Wifi attached but not validated — a hotspot with no upstream, a captive portal. */
        NO_INTERNET,
        /** No wifi association at all. */
        OFF,
    }

    data class Snapshot(
        val state: State,
        /** Bare network name, quotes stripped; null when unknown (no association, or the OS
         *  withheld it — SSID needs runtime location permission on this API level). */
        val ssid: String?,
        /** Signal strength 0..4, WifiManager's own bucketing; -1 when not associated. */
        val level: Int,
    ) {
        /** `▂▄▆█` at full strength; always at least one bar while associated, because a pilot
         *  reading an EMPTY meter next to a green dot sees a contradiction. */
        fun bars(): String =
            if (level < 0) "" else BAR_GLYPHS.substring(0, (level + 1).coerceAtMost(4))
    }

    private const val BAR_GLYPHS = "▂▄▆█"

    /** True when any network (wifi or not) has validated internet — the enrollment
     *  precondition. Kept separate from [read] so the enroll path does not care HOW the
     *  controller reaches the server, only that it can. */
    fun hasInternet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun read(context: Context): Snapshot {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = if (wifi.isWifiEnabled) wifi.connectionInfo else null
        // networkId == -1 is the framework's "not associated"; BSSID null likewise.
        val associated = info != null && info.networkId != -1 && info.bssid != null
        if (!associated) return Snapshot(State.OFF, null, -1)

        val level = WifiManager.calculateSignalLevel(info!!.rssi, 5)
        val ssid = info.ssid?.trim('"')
            ?.takeUnless { it.isEmpty() || it == "<unknown ssid>" || it == "0x" }

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val validated = caps != null &&
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        return Snapshot(if (validated) State.CONNECTED else State.NO_INTERNET, ssid, level)
    }
}
