package com.autel.sdksample.tak

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/**
 * One answer to "does this controller have a usable network?". The home screen's wifi line
 * and the Pre-Flight enrollment check share it, so the two can never disagree.
 *
 * THREE STATES, NOT TWO. The middle state is the important one. A controller can hold a wifi
 * association to a hotspot that has no upstream connection. That state caused the field
 * reports of "TAK enrollment failed" (v1.5.9, event 1): the wifi icon looked correct, but
 * the network went nowhere. Thus CONNECTED means the system CONFIRMED internet access
 * (NET_CAPABILITY_VALIDATED — Android's own reachability probe). An attached SSID alone is
 * not sufficient. Association without validation is its own state. Its text tells the pilot
 * that the network is the problem, not the TAK server.
 *
 * Polled, not listener-driven. The only consumers are Home's 1.5 s refresh loop and one
 * check when the pilot presses Enroll & Connect. A registered NetworkCallback would only be
 * one more object to release. A capability read is cheap.
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
