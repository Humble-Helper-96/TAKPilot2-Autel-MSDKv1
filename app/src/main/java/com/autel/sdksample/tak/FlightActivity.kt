package com.autel.sdksample.tak

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.autel.sdk.widget.AutelCodecView
import com.autel.sdksample.R
import com.taklite.client.tak.TakManager
import com.taklite.util.AppLog
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File

/**
 * FlightActivity — the Autel rebuild of TAKPilot2's flight screen.
 *
 * The DJI original is a 2,200-line customization of DJI's uxsdk DefaultLayoutActivity
 * (FPV widget + DJI map widget + HSI strip). None of that widget framework exists for
 * Autel, so this screen is rebuilt from primitives with the same feature set where it
 * transfers: fullscreen live video ([AutelCodecView]), telemetry HUD, TAK/video status,
 * an expandable TAK map with inbound markers + drop pins ([TakMapMarkers] /
 * [TakDropMarkers]), a drop-pin-at-camera-look-point action, and the video-stream toggle.
 *
 * Flying is done with the Smart Controller's physical sticks (stick/RTH input rides the
 * Skylink link, not this app). Camera/gimbal control beyond what's on the RC hardware is
 * NOT provided here yet — see PORT-STATUS.md.
 */
class FlightActivity : AppCompatActivity(), TakDropMarkers.Ui {

    private lateinit var hudTop: TextView
    private lateinit var hudBottom: TextView
    private lateinit var map: MapView
    private var codecView: AutelCodecView? = null
    private var aircraftMarker: Marker? = null
    private var mapExpanded = false
    private var followAircraft = true

    private val handler = Handler(Looper.getMainLooper())
    private var hudTickCount = 0
    private val refresh = object : Runnable {
        override fun run() {
            updateHud()
            // Every ~5s, not every 500ms tick, so Detailed mode stays readable in flight.
            if (++hudTickCount % 10 == 0) logHudSnapshot()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.v(TAG, "onCreate")
        // osmdroid must be configured before the MapView inflates.
        val osmBase = File(filesDir, "osmdroid").apply { mkdirs() }
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = osmBase
            osmdroidTileCache = File(osmBase, "tiles").apply { mkdirs() }
        }
        setContentView(R.layout.activity_flight)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        hudTop = findViewById(R.id.hudTop)
        hudBottom = findViewById(R.id.hudBottom)
        map = findViewById(R.id.flightMap)

        // Live video, full screen behind everything.
        val container = findViewById<FrameLayout>(R.id.videoContainer)
        codecView = AutelCodecView(this).also { container.addView(it) }

        // TAK map
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)
        TakDropMarkers.ui = this
        TakMapMarkers.install(applicationContext)
        TakDropMarkers.init(applicationContext)
        TakMapMarkers.onMapReady(map)

        findViewById<Button>(R.id.btnBack).setOnClickListener { AppLog.v(TAG, "Back tapped"); finish() }

        val btnVideo = findViewById<Button>(R.id.btnVideo)
        VideoStreamerHolder.onStateChanged = Runnable { refreshVideoButton(btnVideo) }
        refreshVideoButton(btnVideo)
        btnVideo.setOnClickListener {
            if (VideoStreamerHolder.isActive) {
                AppLog.v(TAG, "Video button tapped: stopping")
                VideoStreamerHolder.stop()
                toast("Video stream stopped")
            } else {
                AppLog.v(TAG, "Video button tapped: starting")
                val ok = VideoStreamerHolder.startFromPrefs(applicationContext) { _, msg ->
                    runOnUiThread { toast(msg) }
                }
                if (!ok) toast("Set up the stream in TAK Setup first")
            }
        }

        findViewById<Button>(R.id.btnDropPin).setOnClickListener {
            AppLog.v(TAG, "Drop Pin tapped")
            showAffiliationPicker()
        }

        findViewById<Button>(R.id.btnLookPin).setOnClickListener {
            AppLog.v(TAG, "Pin @ Cam tapped")
            val gp = TakBridgeHolder.lookPoint()
            if (gp == null) {
                toast("Camera look-point not available (GPS/gimbal not ready)")
            } else {
                pickAffiliationThen { aff -> TakDropMarkers.placeAt(aff, gp.first, gp.second, gp.third) }
            }
        }

        findViewById<Button>(R.id.btnMapSize).setOnClickListener {
            AppLog.v(TAG, "Map size tapped")
            toggleMapSize(it as Button)
        }
    }

    override fun onResume() {
        super.onResume()
        AppLog.v(TAG, "onResume")
        AutelProductHolder.install()   // reclaim the global product listener (see holder docs)
        map.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        AppLog.v(TAG, "onPause")
        map.onPause()
        handler.removeCallbacks(refresh)
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLog.v(TAG, "onDestroy")
        VideoStreamerHolder.onStateChanged = null
        TakMapMarkers.onMapDestroyed()
        // NOTE: bridge + TAK connection + video stream deliberately keep running — they
        // belong to the foreground service lifecycle, not this screen (same as TAKPilot2).
    }

    private fun logHudSnapshot() {
        val hud = TakBridgeHolder.hud()
        if (hud == null || !hud.hasFix) {
            AppLog.v(TAG, "hud: no GPS fix, battery=${hud?.batteryPct ?: "—"}%")
        } else {
            AppLog.v(TAG, "hud: lat=${hud.lat} lon=${hud.lon} alt=${hud.relAlt}m " +
                    "spd=${hud.speedMs}m/s hdg=${hud.headingDeg}deg bat=${hud.batteryPct}% sat=${hud.sats}")
        }
    }

    // ---- HUD ----

    private fun updateHud() {
        val hud = TakBridgeHolder.hud()
        val takOk = TakManager.getInstance().isConnected
        val vidOn = VideoStreamerHolder.isRunning
        val acOk = AutelProductHolder.isConnected

        val top = buildString {
            append(if (acOk) "AIRCRAFT ✓" else "AIRCRAFT ✗")
            append("   TAK ").append(if (takOk) "✓" else "✗")
            append("   VIDEO ").append(if (vidOn) "● LIVE" else "—")
            if (TakBridgeHolder.isCameraPointEnabled) append("   SPI ✓")
        }
        hudTop.text = top
        hudTop.setTextColor(if (acOk && takOk) Color.WHITE else Color.parseColor("#FFB74D"))

        if (hud == null || !hud.hasFix) {
            hudBottom.text = "ALT — | SPD — | HDG — | BAT ${hud?.batteryPct ?: "—"}% | SAT ${hud?.sats ?: "—"} | NO GPS FIX"
            return
        }
        hudBottom.text = "ALT %.0fm | SPD %.1fm/s | HDG %03.0f° | BAT %d%% | SAT %d"
            .format(hud.relAlt, hud.speedMs, hud.headingDeg, hud.batteryPct, hud.sats)

        // Aircraft marker + optional follow
        val pos = GeoPoint(hud.lat, hud.lon)
        val mk = aircraftMarker ?: Marker(map).apply {
            title = "Aircraft"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            infoWindow = null
            TakMapMarkers.drawableToBitmap(this@FlightActivity, R.drawable.takpilot2_logo, 48)?.let {
                icon = BitmapDrawable(resources, it)
            }
            map.overlays.add(this)
            aircraftMarker = this
        }
        mk.position = pos
        mk.rotation = -hud.headingDeg.toFloat()
        if (followAircraft) map.controller.setCenter(pos)
        map.invalidate()
    }

    private fun refreshVideoButton(btn: Button) {
        btn.text = if (VideoStreamerHolder.isActive) "■ Video" else "▶ Video"
    }

    private fun toggleMapSize(btn: Button) {
        mapExpanded = !mapExpanded
        followAircraft = !mapExpanded   // expanded map = user browsing; don't recenter
        val lp = map.layoutParams as FrameLayout.LayoutParams
        val d = resources.displayMetrics.density
        if (mapExpanded) {
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            lp.setMargins((8 * d).toInt(), (40 * d).toInt(), (140 * d).toInt(), (40 * d).toInt())
            btn.text = "🗺 Map －"
        } else {
            lp.width = (300 * d).toInt()
            lp.height = (200 * d).toInt()
            lp.setMargins((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
            btn.text = "🗺 Map ＋"
        }
        map.layoutParams = lp
    }

    // ---- Drop-pin UI ----

    private fun showAffiliationPicker() {
        pickAffiliationThen { aff ->
            TakDropMarkers.beginDrop(aff)
            toast("Tap the map to place the ${aff.label} pin")
            if (!mapExpanded) toggleMapSize(findViewById(R.id.btnMapSize))
        }
    }

    private fun pickAffiliationThen(then: (TakDropMarkers.Affiliation) -> Unit) {
        val affs = TakDropMarkers.Affiliation.values()
        AlertDialog.Builder(this)
            .setTitle("Marker affiliation")
            .setItems(affs.map { it.label }.toTypedArray()) { _, which ->
                AppLog.v(TAG, "affiliation chosen: ${affs[which].label}")
                then(affs[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- TakDropMarkers.Ui ----

    override fun askSend(affiliationLabel: String, onChoice: (Boolean) -> Unit) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("$affiliationLabel pin placed")
                .setMessage("Send this pin to the TAK server?")
                .setCancelable(false)
                .setPositiveButton("Send to TAK") { _, _ -> AppLog.i(TAG, "pin send: yes ($affiliationLabel)"); onChoice(true) }
                .setNegativeButton("Don't Send") { _, _ -> AppLog.v(TAG, "pin send: no ($affiliationLabel)"); onChoice(false) }
                .show()
        }
    }

    override fun pinMenu(title: String, onSend: () -> Unit, onDelete: () -> Unit, sendLabel: String?) {
        runOnUiThread {
            val b = AlertDialog.Builder(this).setTitle(title)
            if (sendLabel != null) b.setPositiveButton(sendLabel) { _, _ -> AppLog.i(TAG, "pin menu: $sendLabel ($title)"); onSend() }
            b.setNegativeButton("Delete") { _, _ -> AppLog.i(TAG, "pin menu: delete ($title)"); onDelete() }
            b.setNeutralButton("Cancel", null)
            b.show()
        }
    }

    override fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    companion object { private const val TAG = "FlightActivity" }
}
