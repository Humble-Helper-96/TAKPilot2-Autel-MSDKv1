package com.autel.sdksample.tak

import android.content.Context
import android.graphics.Bitmap
import com.taklite.util.AppLog
import com.autel.sdksample.R
import com.taklite.client.tak.TakManager
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

/**
 * User-dropped MIL-STD-2525 pins on the flight map. Port of TAKPilot2's TakDropMarkers
 * from the DJI map kit to osmdroid; flow, persistence, and CoT behavior unchanged:
 *
 * Tap "Drop Pin" → pick affiliation → tap the map to place → Send-to-TAK / Don't-Send
 * popup. Tap an existing pin → Send / Delete. Pins persist across restarts. If a Data
 * Sync feed is joined, pins auto-publish to the feed (feed-scoped) with no prompt.
 */
object TakDropMarkers {
    private const val TAG = "TakDropMarkers"
    private const val PREFS = "takpilot2_dropped"
    private const val KEY = "pins"

    enum class Affiliation(val id: String, val label: String, val res: Int) {
        FRIENDLY("Friendly", "Friendly", R.drawable.marker_friendly),
        HOSTILE("Hostile", "Hostile", R.drawable.marker_hostile),
        NEUTRAL("Neutral", "Neutral", R.drawable.marker_neutral),
        UNKNOWN("Unknown", "Unknown", R.drawable.marker_unknown),
    }

    private class Pin(
        val key: String,
        val lat: Double,
        val lon: Double,
        val alt: Double,
        val affiliation: Affiliation,
        var name: String,
        var transmitted: Boolean,
        var marker: Marker? = null,
    )

    private var appContext: Context? = null
    private var map: MapView? = null
    private var tapOverlay: MapEventsOverlay? = null
    private val pins = LinkedHashMap<String, Pin>()

    /** Affiliation selected by the "Drop Pin" UI; the next map tap places a pin of this type. */
    @Volatile private var pendingAffiliation: Affiliation? = null

    /** UI callbacks the flight Activity supplies (it owns the dialogs/toasts). */
    interface Ui {
        fun askSend(affiliationLabel: String, onChoice: (Boolean) -> Unit)
        fun pinMenu(title: String, onSend: () -> Unit, onDelete: () -> Unit, sendLabel: String? = "Send to TAK")
        fun toast(msg: String)
    }
    @Volatile var ui: Ui? = null

    fun init(context: Context) { appContext = context.applicationContext; load() }

    /** Called by TakMapMarkers when the osmdroid map is ready. Wires tap handling + redraws. */
    fun onMapReady(readyMap: MapView) {
        map = readyMap
        val overlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                p ?: return false
                return onMapTap(p)
            }
            override fun longPressHelper(p: GeoPoint?): Boolean = false
        })
        tapOverlay = overlay
        readyMap.overlays.add(0, overlay)   // index 0: markers' own click handlers win first
        redraw()
    }

    fun onMapDestroyed() {
        pins.values.forEach { it.marker = null }
        tapOverlay = null
        map = null
    }

    /** True if the flight screen should treat the next map tap as a pin placement. */
    val isPlacing: Boolean get() = pendingAffiliation != null

    fun beginDrop(affiliation: Affiliation) { pendingAffiliation = affiliation }
    fun cancelDrop() { pendingAffiliation = null }

    /** Dropped pins as (lat, lon, alt, icon-res, label) — for the AR overlay (when ported). */
    fun pinsForAr(): List<ArPin> = pins.values.map {
        ArPin(it.lat, it.lon, it.alt, it.affiliation.res, it.name)
    }
    data class ArPin(val lat: Double, val lon: Double, val alt: Double, val iconRes: Int, val label: String)

    private fun onMapTap(p: GeoPoint): Boolean {
        val aff = pendingAffiliation ?: return false
        pendingAffiliation = null
        placeAt(aff, p.latitude, p.longitude, if (p.altitude.isFinite()) p.altitude else 0.0)
        return true
    }

    /**
     * Place a marker at an explicit position (also used by hot keys, which drop at the
     * camera look-point). Draws + persists, then Send/Don't-Send auto-shows — unless a
     * Data Sync feed is joined, in which case it auto-publishes.
     */
    fun placeAt(aff: Affiliation, lat: Double, lon: Double, alt: Double) {
        val pin = Pin(
            key = "${aff.id}-${System.nanoTime()}",
            lat = lat, lon = lon, alt = alt,
            affiliation = aff, name = "${aff.label} Marker", transmitted = false,
        )
        pins[pin.key] = pin
        AppLog.v(TAG, "pin placed: ${pin.key} (${aff.label}) @ $lat,$lon alt=$alt")
        draw(pin)
        save()
        if (TakMissionManager.joinedFeed != null && TakManager.getInstance().isConnected) {
            sendPin(pin)
        } else {
            ui?.askSend(aff.label) { send -> if (send) sendPin(pin) }
        }
    }

    private fun onPinTap(pin: Pin): Boolean {
        ui?.pinMenu(pin.affiliation.label,
            onSend = { sendPin(pin) },
            onDelete = { deletePin(pin) })
        return true
    }

    private fun sendPin(pin: Pin) {
        val tak = TakManager.getInstance()
        if (!tak.isConnected) { ui?.toast("Not connected to TAK"); return }
        val feed = TakMissionManager.joinedFeed
        if (feed != null) {
            // Feed-scoped send (mission-dest tag), then register in the feed via /contents.
            val markerUid = tak.sendMarkerToMission(pin.lat, pin.lon, pin.alt, pin.affiliation.id, pin.name, "", feed)
            if (markerUid != null) TakMissionManager.publishUid(markerUid)
            pin.transmitted = true
            save()
            AppLog.i(TAG, "pin sent to feed '$feed': ${pin.key}")
            ui?.toast("Sent ${pin.affiliation.label} pin to feed '$feed'")
        } else {
            tak.sendMarker(pin.lat, pin.lon, pin.alt, pin.affiliation.id, pin.name, "")
            pin.transmitted = true
            save()
            AppLog.i(TAG, "pin sent to TAK: ${pin.key}")
            ui?.toast("Sent ${pin.affiliation.label} pin to TAK")
        }
    }

    private fun deletePin(pin: Pin) {
        pin.marker?.let { map?.overlays?.remove(it) }
        pins.remove(pin.key)
        map?.invalidate()
        save()
        AppLog.v(TAG, "pin deleted: ${pin.key}")
        ui?.toast("Pin deleted")
    }

    private fun redraw() {
        for (pin in pins.values) { pin.marker = null; draw(pin) }
    }

    private fun draw(pin: Pin) {
        val m = map ?: return
        try {
            val bmp: Bitmap = TakMapMarkers.makeMilIcon(pin.affiliation.res, pin.name)
            val marker = Marker(m).apply {
                position = GeoPoint(pin.lat, pin.lon, pin.alt)
                title = pin.name
                icon = android.graphics.drawable.BitmapDrawable(appContext?.resources, bmp)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setOnMarkerClickListener { _, _ -> onPinTap(pin) }
                infoWindow = null
            }
            m.overlays.add(marker)
            pin.marker = marker
            m.invalidate()
        } catch (e: Exception) {
            AppLog.w(TAG, "draw ${pin.key} failed: ${e.message}")
        }
    }

    // ---- Persistence ----
    private fun save() {
        val ctx = appContext ?: return
        try {
            val arr = JSONArray()
            for (p in pins.values) {
                arr.put(JSONObject().apply {
                    put("key", p.key); put("lat", p.lat); put("lon", p.lon); put("alt", p.alt)
                    put("aff", p.affiliation.id); put("name", p.name); put("tx", p.transmitted)
                })
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, arr.toString()).apply()
        } catch (e: Exception) { AppLog.w(TAG, "save failed: ${e.message}") }
    }

    private fun load() {
        val ctx = appContext ?: return
        try {
            val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
            val arr = JSONArray(json)
            pins.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val aff = Affiliation.values().firstOrNull { it.id == o.getString("aff") } ?: Affiliation.FRIENDLY
                val key = o.getString("key")
                pins[key] = Pin(key, o.getDouble("lat"), o.getDouble("lon"), o.optDouble("alt", 0.0),
                    aff, o.optString("name", "Marker"), o.optBoolean("tx", false))
            }
        } catch (e: Exception) { AppLog.w(TAG, "load failed: ${e.message}") }
    }
}
