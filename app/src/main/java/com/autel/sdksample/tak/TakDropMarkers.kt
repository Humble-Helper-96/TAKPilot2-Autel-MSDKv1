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
        var lat: Double,
        var lon: Double,
        var alt: Double,
        var affiliation: Affiliation,
        var name: String,
        var transmitted: Boolean,
        /**
         * CoT uid from the FIRST send — the marker's identity on TAK. Null until sent.
         *
         * Re-using it is what makes move/rename/retype/re-send update the existing marker on
         * every other client instead of scattering duplicates: in CoT the uid *is* the marker.
         * See [TakManager.sendMarkerWithUid].
         */
        var cotUid: String? = null,
        var marker: Marker? = null,
    )

    /** Read-only snapshot for the markers list panel. */
    data class PinInfo(
        val key: String, val name: String, val affiliation: Affiliation,
        val lat: Double, val lon: Double, val alt: Double,
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

    /**
     * Tap-to-place state. **Currently dormant: nothing calls [beginDrop] since the flight
     * mini-map was locked (2026-07-30).** With no pan/zoom there's nothing to aim with, so
     * [FlightActivity]'s drop-pin button places at the camera look-point instead — matching the
     * DJI sibling. [onMapTap] therefore always short-circuits to false and map taps fall through
     * to the markers' own click handlers, which is why the [MapEventsOverlay] registration above
     * is harmless rather than a broken affordance.
     *
     * Kept rather than deleted because the machinery is correct and the marker-suite/AR work
     * still outstanding in Phase 2 may want an explicit placement mode again. If it's still
     * unused when that lands, delete it then.
     */
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

    /**
     * Sends (or RE-sends) a pin, always under its own [Pin.cotUid].
     *
     * Reusing the uid is the whole mechanism behind move/rename/retype/re-send: every other TAK
     * client updates the marker it already has rather than drawing a second one. Minting a
     * fresh uid per send — which this used to do via `sendMarker`/`sendMarkerToMission` — would
     * turn a single moved marker into a trail of duplicates on everyone else's picture.
     */
    private fun sendPin(pin: Pin) {
        val tak = TakManager.getInstance()
        if (!tak.isConnected) {
            AppLog.w(TAG, "pin ${pin.key} not sent — TAK not connected")
            ui?.toast("Pin saved locally — not connected to TAK")
            return
        }
        val uid = pin.cotUid ?: TakManager.newMarkerUid()
        val feed = TakMissionManager.joinedFeed
        val sent = tak.sendMarkerWithUid(
            uid, pin.lat, pin.lon, pin.alt, pin.affiliation.id, pin.name, "", feed)
        if (sent == null) {
            AppLog.w(TAG, "pin ${pin.key} send failed")
            ui?.toast("Pin saved locally — send failed")
            return
        }
        val isFirstSend = pin.cotUid == null
        pin.cotUid = sent
        pin.transmitted = true
        save()
        if (feed != null) {
            // Register the uid in the feed's /contents so it shows for feed subscribers. Only
            // on first send — re-registering an existing uid is a no-op at best.
            if (isFirstSend) TakMissionManager.publishUid(sent)
            AppLog.i(TAG, "pin sent to feed '$feed': ${pin.key} uid=$sent")
            ui?.toast("Sent ${pin.name} to feed '$feed'")
        } else {
            AppLog.i(TAG, "pin sent to TAK: ${pin.key} uid=$sent")
            ui?.toast("Sent ${pin.name} to TAK")
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

    // ---- Markers-list management API (drop-pin long-press panel) ----

    /** Newest first, matching the DJI blueprint's list order. */
    fun listPins(): List<PinInfo> = pins.values.reversed().map {
        PinInfo(it.key, it.name, it.affiliation, it.lat, it.lon, it.alt)
    }

    /** Moves a pin to the camera look-point and re-sends it under its existing uid, so it
     *  MOVES on every other client rather than spawning a second marker. */
    fun moveToLookPoint(key: String, lat: Double, lon: Double, alt: Double) {
        val pin = pins[key] ?: return
        pin.lat = lat; pin.lon = lon; pin.alt = alt
        AppLog.i(TAG, "pin moved: $key -> $lat,$lon alt=$alt")
        redrawPin(pin)
        save()
        if (pin.cotUid != null) sendPin(pin)
    }

    fun rename(key: String, newName: String) {
        val pin = pins[key] ?: return
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        pin.name = trimmed
        AppLog.i(TAG, "pin renamed: $key -> $trimmed")
        redrawPin(pin)   // the label is drawn into the icon bitmap, so it must be rebuilt
        save()
        if (pin.cotUid != null) sendPin(pin)
    }

    fun changeType(key: String, aff: Affiliation) {
        val pin = pins[key] ?: return
        pin.affiliation = aff
        AppLog.i(TAG, "pin retyped: $key -> ${aff.label}")
        redrawPin(pin)
        save()
        if (pin.cotUid != null) sendPin(pin)
    }

    fun resend(key: String) {
        val pin = pins[key] ?: return
        sendPin(pin)
    }

    fun delete(key: String) {
        val pin = pins[key] ?: return
        deletePin(pin)
    }

    /**
     * Clears every dropped pin from THIS map. Local-only, deliberately: there is no CoT
     * "delete" that reliably removes a marker from other clients, so each one stays on the
     * server until it goes stale (14h — see CotBuilder's MARKER_STALE_DURATION_MS). The
     * confirmation dialog says so rather than implying a team-wide wipe.
     */
    fun clearAll() {
        val n = pins.size
        for (pin in pins.values) pin.marker?.let { map?.overlays?.remove(it) }
        pins.clear()
        map?.invalidate()
        save()
        AppLog.i(TAG, "cleared all $n dropped pin(s) (local only)")
        ui?.toast("Cleared $n marker(s) from your map")
    }

    /** Rebuilds one pin's map marker in place — needed after anything that changes its
     *  position, label or affiliation, since the icon bitmap bakes in the name and shape. */
    private fun redrawPin(pin: Pin) {
        pin.marker?.let { map?.overlays?.remove(it) }
        pin.marker = null
        draw(pin)
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
                    // Persisted so a restart doesn't orphan the marker's TAK identity — without
                    // it, the next re-send/move would mint a new uid and duplicate the marker
                    // on every other client instead of updating the one already there.
                    p.cotUid?.let { put("uid", it) }
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
                    aff, o.optString("name", "Marker"), o.optBoolean("tx", false),
                    cotUid = o.optString("uid", "").takeIf { it.isNotEmpty() })
            }
        } catch (e: Exception) { AppLog.w(TAG, "load failed: ${e.message}") }
    }
}
