package com.autel.sdksample.tak

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import com.taklite.util.AppLog
import com.autel.sdksample.R
import com.taklite.client.tak.TakManager
import com.taklite.client.tak.TakUser
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * Draws inbound TAK CoT contacts/markers on the flight map. Port of TAKPilot2's
 * TakMapMarkers from the DJI uxsdk map kit to osmdroid.
 *
 * Structural change vs the DJI original: there is no cross-module MapMarkerHook needed —
 * [FlightActivity] owns the osmdroid [MapView] and hands it here directly via [onMapReady].
 * Everything else (listener flow, icon generation, persistence of received 2525 markers,
 * local-hide of inbound markers) is a 1:1 port.
 */
object TakMapMarkers {
    private const val TAG = "TakMapMarkers"

    /** Air-track course is rounded to this before it reaches the icon cache — see [courseBucket]. */
    private const val COURSE_BUCKET_DEG = 15.0

    /** Air-track symbol size. 24dp — 25% smaller than the 32dp used for ground MIL markers
     *  (operator, 2026-07-31): traffic is context, not something the pilot acts on directly,
     *  and at 32dp it crowded the mini-map. Kept separate from the MIL size on purpose so
     *  tuning one does not silently move the other. */
    private const val AIR_ICON_DP = 24f

    private var map: MapView? = null
    private val markers = HashMap<String, Marker>()
    private val iconKeys = HashMap<String, String>()
    private val hidden = HashSet<String>()
    private var listenerRegistered = false
    private val iconCache = HashMap<String, BitmapDrawable>()
    private var appContext: Context? = null

    // Received 2525 MARKERS (a-{f,h,n,u}-G) we persist so they survive restarts. PLI contacts
    // are NOT persisted — they re-broadcast live and would otherwise ghost. Keyed by uid.
    private val savedMarkers = LinkedHashMap<String, SavedMarker>()
    private data class SavedMarker(
        val uid: String, val lat: Double, val lon: Double, val alt: Double,
        val type: String, val callsign: String, val team: String,
    )

    /** Call once (e.g. on app/TAK init) so inbound contacts accumulate before the map opens. */
    fun install(context: Context) {
        appContext = context.applicationContext
        loadSavedMarkers()
        registerListener()
    }

    /** Called by FlightActivity when its osmdroid map is created. */
    fun onMapReady(readyMap: MapView) {
        map = readyMap
        resyncExisting()
        TakDropMarkers.onMapReady(readyMap)
    }

    /** Called by FlightActivity in onDestroy — marker handles belong to the dead map. */
    fun onMapDestroyed() {
        markers.clear()
        iconKeys.clear()
        iconCache.clear()
        map = null
        TakDropMarkers.onMapDestroyed()
    }

    private fun registerListener() {
        if (listenerRegistered) return
        listenerRegistered = true
        TakManager.getInstance().addListener(object : TakManager.TakUserListener {
            override fun onTakUserUpdated(user: TakUser) = upsert(user)
            override fun onTakUserRemoved(uid: String) = remove(uid)
            override fun onTakConnectionChanged(connected: Boolean) {}
        })
    }

    private fun SavedMarker.toUser(): TakUser =
        TakUser(uid, callsign, lat, lon, alt, team, "", Long.MAX_VALUE).also { it.type = type }

    private fun resyncExisting() {
        if (map == null) return
        markers.clear()
        iconKeys.clear()
        try {
            for (s in savedMarkers.values) if (!hidden.contains(s.uid)) upsert(s.toUser())
            for (user in TakManager.getInstance().takUsers) upsert(user)
        } catch (e: Exception) {
            AppLog.w(TAG, "resync failed: ${e.message}")
        }
    }

    private fun upsert(user: TakUser) {
        val m = map ?: return
        if (user.lat == 0.0 && user.lon == 0.0) return
        if (hidden.contains(user.uid)) return
        val pos = GeoPoint(user.lat, user.lon, user.alt)
        val iconKey = iconKeyFor(user)
        try {
            val existing = markers[user.uid]
            if (existing != null) {
                existing.position = pos
                if (iconKeys[user.uid] != iconKey) {
                    existing.icon = iconFor(iconKey, user)
                    iconKeys[user.uid] = iconKey
                }
                existing.title = user.callsign
            } else {
                val marker = Marker(m).apply {
                    position = pos
                    title = user.callsign
                    icon = iconFor(iconKey, user)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { _, _ -> onInboundMarkerTap(user.uid) }
                    infoWindow = null
                }
                m.overlays.add(marker)
                markers[user.uid] = marker
                iconKeys[user.uid] = iconKey
                AppLog.v(TAG, "new inbound marker: ${user.uid} (${user.callsign}) type=${user.type}")
            }
            m.invalidate()
            if (milMarkerRes(user.type) != null && !hidden.contains(user.uid)) {
                savedMarkers[user.uid] = SavedMarker(
                    user.uid, user.lat, user.lon, user.alt,
                    user.type ?: "", user.callsign ?: user.uid, user.team ?: "Cyan")
                saveSavedMarkers()
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "upsert ${user.uid} failed: ${e.message}")
        }
    }

    /** For the AR overlay / dedupe checks: is this uid locally deleted? */
    fun isHidden(uid: String): Boolean = hidden.contains(uid)

    /**
     * Tapping an INBOUND marker (from another operator) → offer a LOCAL delete (removes it
     * from THIS map only; stays on the server / other clients).
     */
    fun onInboundMarkerTap(uid: String): Boolean {
        val title = markers[uid]?.title ?: uid
        AppLog.v(TAG, "inbound marker tapped: $uid ($title)")
        TakDropMarkers.ui?.pinMenu(title,
            onSend = { /* received markers aren't re-sent from here */ },
            onDelete = { hideInbound(uid) },
            sendLabel = null)
        return true
    }

    private fun hideInbound(uid: String) {
        AppLog.v(TAG, "inbound marker hidden locally: $uid")
        hidden.add(uid)
        markers.remove(uid)?.let { mk -> map?.overlays?.remove(mk) }
        iconKeys.remove(uid)
        savedMarkers.remove(uid)
        saveSavedMarkers()
        map?.invalidate()
        TakDropMarkers.ui?.toast("Marker removed from your map")
    }

    private fun remove(uid: String) {
        try {
            markers.remove(uid)?.let { mk -> map?.overlays?.remove(mk); map?.invalidate() }
        } catch (e: Exception) {
            AppLog.w(TAG, "remove $uid failed: ${e.message}")
        }
        iconKeys.remove(uid)
    }

    // ---- Persistence of received 2525 markers (+ locally-deleted uids) across restarts ----
    private const val PREFS = "takpilot2_recv_markers"

    private fun saveSavedMarkers() {
        val ctx = appContext ?: return
        try {
            val arr = org.json.JSONArray()
            for (s in savedMarkers.values) {
                arr.put(org.json.JSONObject().apply {
                    put("uid", s.uid); put("lat", s.lat); put("lon", s.lon); put("alt", s.alt)
                    put("type", s.type); put("cs", s.callsign); put("team", s.team)
                })
            }
            val hid = org.json.JSONArray().apply { hidden.forEach { put(it) } }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("markers", arr.toString())
                .putString("hidden", hid.toString())
                .apply()
        } catch (e: Exception) { AppLog.w(TAG, "saveSavedMarkers failed: ${e.message}") }
    }

    private fun loadSavedMarkers() {
        val ctx = appContext ?: return
        try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            p.getString("hidden", null)?.let {
                val h = org.json.JSONArray(it)
                for (i in 0 until h.length()) hidden.add(h.getString(i))
            }
            p.getString("markers", null)?.let {
                val arr = org.json.JSONArray(it)
                savedMarkers.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val uid = o.getString("uid")
                    if (hidden.contains(uid)) continue
                    savedMarkers[uid] = SavedMarker(uid, o.getDouble("lat"), o.getDouble("lon"),
                        o.optDouble("alt", 0.0), o.getString("type"),
                        o.optString("cs", uid), o.optString("team", "Cyan"))
                }
            }
        } catch (e: Exception) { AppLog.w(TAG, "loadSavedMarkers failed: ${e.message}") }
    }

    // ---- Icon resolution — matches taklite's createTakMarkerIcon exactly ----

    private val density get() = (appContext?.resources?.displayMetrics?.density ?: 2.5f)

    private fun iconKeyFor(user: TakUser): String {
        val team = (user.team ?: "Cyan").lowercase()
        val stale = if (user.isStale) "S" else "A"
        val drone = if (user.isDrone) "D" else "U"
        val mil = milMarkerRes(user.type) ?: 0
        // Air tracks bake their course into the bitmap (see makeAirIcon), so the key has to
        // include it — but BUCKETED to COURSE_BUCKET_DEG. Keying on the raw course would mint a
        // fresh bitmap on every position report, since ADS-B course jitters by fractions of a
        // degree; that is an unbounded cache of near-identical bitmaps on the HUD tick.
        val air = when {
            !isAirTrack(user.type) -> "-"
            user.hasCourse() -> "A${courseBucket(user.course)}"
            else -> "AN"
        }
        return "$team|$stale|$drone|$mil|$air|${user.callsign}"
    }

    /**
     * An inbound contact that is airborne — the CoT type's third field is `A` (air) rather than
     * `G` (ground), e.g. `a-f-A-C-F` from an ADS-B gateway.
     *
     * Same discriminator [ArSettings.categoryFor] uses for the AR overlay's Air Traffic layer,
     * so a contact drawn as an aircraft on the map is the same set the overlay calls air
     * traffic. If one of these changes, change both.
     */
    fun isAirTrack(type: String?): Boolean {
        val parts = type?.split("-").orEmpty()
        return parts.size >= 3 && parts[0] == "a" && parts[2] == "A"
    }

    /** Course rounded to [COURSE_BUCKET_DEG], for the icon cache key. 15 degrees is finer than
     *  a 32px symbol can express and caps the cache at 24 rotations per callsign. */
    private fun courseBucket(course: Double): Int =
        (Math.round(course / COURSE_BUCKET_DEG) * COURSE_BUCKET_DEG).toInt() % 360

    /**
     * MIL-STD-2525 affiliation MARKERS (a-{f,h,n,u}-G, NOT the …-G-U-… unit/PLI form) →
     * frame drawable. Null for PLI/units/drones (those keep the team-colored dot).
     */
    fun milMarkerRes(type: String?): Int? {
        if (type == null) return null
        val parts = type.split("-")
        if (parts.size < 3 || parts[0] != "a" || parts[2] != "G") return null
        val isUnit = parts.size >= 4 && parts[3] == "U"
        if (isUnit) return null
        return when (parts[1]) {
            "f" -> R.drawable.marker_friendly
            "h" -> R.drawable.marker_hostile
            "n" -> R.drawable.marker_neutral
            "u" -> R.drawable.marker_unknown
            else -> null
        }
    }

    /** TAK team-name → color, identical to taklite's getTeamColor(). */
    fun teamColor(team: String?): Int {
        if (team == null) return Color.GREEN
        return when (team.lowercase()) {
            "cyan" -> Color.parseColor("#00BCD4")
            "red" -> Color.parseColor("#F44336")
            "blue" -> Color.parseColor("#2196F3")
            "green" -> Color.parseColor("#4CAF50")
            "yellow" -> Color.parseColor("#FFEB3B")
            "white" -> Color.WHITE
            "orange" -> Color.parseColor("#FF9800")
            "magenta" -> Color.parseColor("#E91E63")
            "maroon" -> Color.parseColor("#880E4F")
            "purple" -> Color.parseColor("#9C27B0")
            "dark green" -> Color.parseColor("#2E7D32")
            "teal" -> Color.parseColor("#009688")
            "dark blue" -> Color.parseColor("#1565C0")
            "brown" -> Color.parseColor("#795548")
            else -> Color.GREEN
        }
    }

    private fun iconFor(key: String, user: TakUser): BitmapDrawable {
        iconCache[key]?.let { return it }
        val res = milMarkerRes(user.type)
        val bmp = when {
            // Checked BEFORE milMarkerRes so an air track can never fall through to the plain
            // team dot, which is what made ADS-B traffic indistinguishable from a TAK client.
            isAirTrack(user.type) -> makeAirIcon(
                if (user.hasCourse()) R.drawable.ic_air_track
                else R.drawable.ic_air_track_nocourse,
                user.callsign ?: user.uid,
                if (user.hasCourse()) courseBucket(user.course).toDouble() else null,
            )
            res != null -> makeMilIcon(res, user.callsign ?: user.uid)
            else -> makeIcon(user.callsign ?: user.uid, user.team, user.isStale)
        }
        val d = BitmapDrawable(appContext?.resources, bmp)
        iconCache[key] = d
        return d
    }

    /** Render any drawable resource (incl. vectors) to a square bitmap. */
    fun drawableToBitmap(ctx: Context, resId: Int, sizePx: Int): Bitmap? = try {
        val dr = androidx.core.content.ContextCompat.getDrawable(ctx, resId)
        dr?.let {
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            it.setBounds(0, 0, sizePx, sizePx)
            it.draw(c)
            bmp
        }
    } catch (e: Exception) { null }

    /** MIL-STD-2525 affiliation frame + callsign label below. */
    /**
     * Air-track icon: the aircraft glyph turned to [courseDeg], with the callsign label left
     * UPRIGHT beneath it.
     *
     * The rotation is baked into the bitmap rather than applied with `Marker.rotation`, because
     * the marker's rotation would turn the whole bitmap — including the label, which would read
     * upside down for anything on a southerly heading. Rotating only the glyph inside the
     * bitmap keeps the text horizontal at every course.
     *
     * [courseDeg] null means the sender reported no course; the caller passes the ringed
     * non-directional drawable in that case and nothing is rotated.
     */
    fun makeAirIcon(resId: Int, callsign: String, courseDeg: Double?): Bitmap {
        val ctx = appContext
        val d = density
        val size = (AIR_ICON_DP * d).toInt()
        val icon = ctx?.let { drawableToBitmap(it, resId, size) }

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 10 * d; typeface = Typeface.DEFAULT_BOLD
        }
        val tw = text.measureText(callsign)
        val fm = text.fontMetrics
        val th = fm.descent - fm.ascent
        val gap = (d * 3).toInt(); val padH = (4 * d).toInt(); val padV = (d * 2).toInt()
        val labelW = tw.toInt() + padH * 2
        val labelH = th.toInt() + padV * 2
        // A rotated square needs its diagonal to avoid clipping the wingtips at 45 degrees.
        val glyphBox = if (courseDeg != null) (size * 1.42f).toInt() else size
        val w = maxOf(glyphBox, labelW)
        val h = glyphBox + gap + labelH

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (icon != null) {
            if (courseDeg != null) {
                c.save()
                c.rotate(courseDeg.toFloat(), w / 2f, glyphBox / 2f)
                c.drawBitmap(icon, (w - size) / 2f, (glyphBox - size) / 2f, null)
                c.restore()
            } else {
                c.drawBitmap(icon, (w - size) / 2f, 0f, null)
            }
        }

        val labelLeft = (w - labelW) / 2f
        val labelTop = (glyphBox + gap).toFloat()
        c.drawRoundRect(labelLeft, labelTop, labelLeft + labelW, labelTop + labelH, d * 3, d * 3,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 0, 0, 0) })
        c.drawText(callsign, labelLeft + padH, labelTop + padV - fm.ascent, text)
        return bmp
    }

    fun makeMilIcon(resId: Int, callsign: String): Bitmap {
        val ctx = appContext
        val d = density
        val size = (32 * d).toInt()
        val icon = ctx?.let { drawableToBitmap(it, resId, size) }

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 10 * d; typeface = Typeface.DEFAULT_BOLD
        }
        val tw = text.measureText(callsign)
        val fm = text.fontMetrics
        val th = fm.descent - fm.ascent
        val gap = (d * 3).toInt(); val padH = (4 * d).toInt(); val padV = (d * 2).toInt()
        val labelW = tw.toInt() + padH * 2
        val labelH = th.toInt() + padV * 2
        val w = maxOf(size, labelW)
        val h = size + gap + labelH

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (icon != null) c.drawBitmap(icon, (w - size) / 2f, 0f, null)

        val labelLeft = (w - labelW) / 2f
        val labelTop = (size + gap).toFloat()
        c.drawRoundRect(labelLeft, labelTop, labelLeft + labelW, labelTop + labelH, d * 3, d * 3,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 0, 0, 0) })
        c.drawText(callsign, labelLeft + padH, labelTop + padV - fm.ascent, text)
        return bmp
    }

    /** Colored dot + callsign label — 1:1 port of taklite's createTakMarkerIcon. */
    private fun makeIcon(callsign: String, team: String?, isStale: Boolean): Bitmap {
        val color = if (isStale) Color.GRAY else teamColor(team)
        val d = density
        val iconSize = (14 * d).toInt()
        val r = iconSize / 2f

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = 10 * d
            typeface = Typeface.DEFAULT_BOLD
        }
        val textWidth = textPaint.measureText(callsign)
        val fm = textPaint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        val gap = (d * 3).toInt()
        val textPadH = (d * 3).toInt()
        val textPadV = (d * 1.5f).toInt()
        val labelW = textWidth.toInt() + textPadH * 2
        val labelH = textHeight.toInt() + textPadV * 2
        val bmpWidth = maxOf(iconSize, labelW)
        val bmpHeight = iconSize + gap + labelH

        val bmp = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = bmpWidth / 2f
        val cr = r - 1

        canvas.drawCircle(cx, r, cr, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.FILL
        })
        canvas.drawCircle(cx, r, cr, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = d * 1.5f
        })

        val labelLeft = (bmpWidth - labelW) / 2f
        val labelTop = (iconSize + gap).toFloat()
        canvas.drawRoundRect(labelLeft, labelTop, labelLeft + labelW, labelTop + labelH,
            d * 3, d * 3, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(140, 0, 0, 0) })
        canvas.drawText(callsign, labelLeft + textPadH, labelTop + textPadV - fm.ascent, textPaint)
        return bmp
    }
}
