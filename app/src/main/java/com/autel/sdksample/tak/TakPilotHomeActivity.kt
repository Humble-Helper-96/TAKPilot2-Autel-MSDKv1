package com.autel.sdksample.tak

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.autel.sdk.Autel
import com.autel.sdksample.ProductActivity
import com.autel.sdksample.R
import com.taklite.client.tak.TakManager
import com.taklite.util.AppLog

/**
 * TAKPilot2-Autel home screen — port of TAKPilot2HomeActivity. Aircraft/SDK + TAK status
 * and quick toggles on the left; the large "Enter Flight" card on the right. TAK
 * auto-connects in the background before the operator even enters flight.
 *
 * Differences vs the DJI original:
 *  - "Button Mapping" (DJI RC Plus physical keys) is repurposed as "SDK Test Tools",
 *    opening the stock Autel sample's ProductActivity — invaluable for bench debugging.
 *  - Aircraft status comes from [AutelProductHolder] instead of DJI KeyManager.
 */
class TakPilotHomeActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var aircraft: TextView
    private lateinit var sdk: TextView
    private lateinit var battery: TextView
    private lateinit var takStatus: TextView
    private lateinit var takDot: android.view.View

    private val refresh = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_takpilot2_home)
        AppLog.v(TAG, "onCreate")

        // Wire the aircraft-connection singleton once, then silently reconnect TAK with
        // saved enrollment so the operator lands here already connected.
        AutelProductHolder.install()
        TakAutoConnect.tryReconnect(applicationContext)

        aircraft = findViewById(R.id.homeAircraft)
        sdk = findViewById(R.id.homeSdk)
        battery = findViewById(R.id.homeBattery)
        takStatus = findViewById(R.id.homeTakStatus)
        takDot = findViewById(R.id.homeTakDot)

        findViewById<android.view.View>(R.id.homeEnterFlightCard).setOnClickListener {
            AppLog.v(TAG, "Enter Flight card tapped")
            startActivity(Intent(this, FlightActivity::class.java))
        }
        findViewById<android.view.View>(R.id.homeEnterFlight).setOnClickListener {
            AppLog.v(TAG, "Enter Flight button tapped")
            startActivity(Intent(this, FlightActivity::class.java))
        }
        findViewById<Button>(R.id.homeTakSetup).setOnClickListener {
            AppLog.v(TAG, "TAK Setup tapped")
            startActivity(Intent(this, TakConnectActivity::class.java))
        }

        val prefs = getSharedPreferences("takpilot2_tak", MODE_PRIVATE)

        val videoToggle = findViewById<CheckBox>(R.id.homeVideoToggle)
        videoToggle.isChecked = VideoStreamerHolder.isActive
        videoToggle.setOnCheckedChangeListener { _, on ->
            AppLog.v(TAG, "Stream video toggle -> $on")
            if (on) {
                val ok = VideoStreamerHolder.startFromPrefs(applicationContext) { _, _ -> }
                if (!ok) {
                    videoToggle.isChecked = false
                    toast("Set up the stream in TAK Setup first")
                }
            } else {
                VideoStreamerHolder.stop()
            }
        }

        val cpToggle = findViewById<CheckBox>(R.id.homeCameraPointToggle)
        cpToggle.isChecked = prefs.getBoolean("camera_point", false)
        cpToggle.setOnCheckedChangeListener { _, on ->
            AppLog.v(TAG, "Camera FOV / look-point toggle -> $on")
            prefs.edit().putBoolean("camera_point", on).apply()
            TakBridgeHolder.setCameraPointEnabled(on)
        }

        // DJI's "Button Mapping" slot → Autel SDK sample test tools (bench debugging).
        findViewById<Button>(R.id.homeButtonMapping).apply {
            text = "SDK Test Tools"
            setOnClickListener {
                AppLog.v(TAG, "SDK Test Tools tapped")
                startActivity(Intent(this@TakPilotHomeActivity, ProductActivity::class.java))
            }
        }
        findViewById<Button>(R.id.homeDataSync).setOnClickListener {
            AppLog.v(TAG, "Data Sync tapped")
            startActivity(Intent(this, DataSyncActivity::class.java))
        }
        findViewById<Button>(R.id.homeDebug).setOnClickListener {
            AppLog.v(TAG, "Debug tapped")
            startActivity(Intent(this, DebugActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        AppLog.v(TAG, "onResume")
        AutelProductHolder.install()   // reclaim the global product listener (see holder docs)
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        AppLog.v(TAG, "onPause")
        handler.removeCallbacks(refresh)
    }

    private fun updateStatus() {
        val product = AutelProductHolder.product
        aircraft.text = product?.type?.toString() ?: "Not connected"
        sdk.text = "Autel MSDK " + (runCatching { Autel.getSdkVersion() }.getOrNull() ?: "1.5")
        val batt = TakBridgeHolder.hud()?.batteryPct ?: -1
        battery.text = if (batt > 0) "Battery $batt%" else "Battery —"

        val connected = TakManager.getInstance().isConnected
        val color = if (connected) Color.parseColor("#4CAF50") else Color.parseColor("#F44336")
        takStatus.text = if (connected) "TAK: Connected" else "TAK: Disconnected"
        takStatus.setTextColor(color)
        (takDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
            ?: takDot.background?.setTint(color)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object { private const val TAG = "TakPilotHomeActivity" }
}
