package com.autel.sdksample.tak

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.autel.sdk.Autel
import com.autel.sdksample.R
import com.taklite.client.tak.TakManager
import com.taklite.util.AppLog

/**
 * TAKPilot2-Autel home screen — mirrors the DJI blueprint's TAKPilot2GoHomeActivity:
 * logo + TAK status and a Quick Controls card on the left, the large "Enter Flight" card on
 * the right. TAK auto-connects in the background before the operator even enters flight.
 *
 * Kept deliberately identical to the blueprint (operator, 2026-07-30). Three Autel-only
 * extras were REMOVED to get there: an "SDK Test Tools" button (opened the stock Autel
 * sample's ProductActivity — bench-debug only, and reachable by other means), and two Quick
 * Controls checkboxes for video streaming and camera look-point. Neither checkbox owned its
 * setting: look-point lives in Pre-Flight Setup and is applied on auto-connect by
 * [TakAutoConnect], and streaming is started from the flight screen's LIVE badge — so
 * removing them dropped no functionality. The Enter Flight card's battery line went too;
 * battery is on the flight toolbar's gauge.
 *
 * Only real difference left: aircraft status comes from [AutelProductHolder] instead of
 * DJI's KeyManager.
 */
class TakPilotHomeActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var aircraft: TextView
    private lateinit var sdk: TextView
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
        takStatus = findViewById(R.id.homeTakStatus)
        takDot = findViewById(R.id.homeTakDot)

        // One tap target: the whole card. The separate "Enter Flight" button that used to sit
        // below it was removed — it duplicated the card's own click and cost ~56dp of a
        // height budget that was already over on shorter screens (see the layout comment).
        findViewById<android.view.View>(R.id.homeEnterFlight).setOnClickListener {
            AppLog.v(TAG, "Enter Flight tapped")
            startActivity(Intent(this, FlightActivity::class.java))
        }
        findViewById<Button>(R.id.homeTakSetup).setOnClickListener {
            AppLog.v(TAG, "TAK Setup tapped")
            startActivity(Intent(this, TakConnectActivity::class.java))
        }

        findViewById<Button>(R.id.homeDataSync).setOnClickListener {
            AppLog.v(TAG, "Data Sync tapped")
            startActivity(Intent(this, DataSyncActivity::class.java))
        }
        findViewById<Button>(R.id.homeDebug).setOnClickListener {
            AppLog.v(TAG, "Debug tapped")
            startActivity(Intent(this, DebugActivity::class.java))
        }
        findViewById<Button>(R.id.homeQuit).setOnClickListener {
            AppLog.v(TAG, "STOP/QUIT tapped")
            confirmQuit()
        }
        findViewById<Button>(R.id.homeFieldGuide).setOnClickListener {
            AppLog.v(TAG, "Field Guide tapped")
            startActivity(Intent(this, FieldGuideActivity::class.java))
        }
    }

    /** The "nuclear option": tear down every long-lived TAKPilot2-Autel process (video
     *  stream, TAK connection + its foreground service, telemetry bridge) and then kill this
     *  process outright, so a relaunch starts completely clean — for clearing out any stuck
     *  state found mid-operation without having to know which subsystem is wedged. Ported
     *  from the DJI sibling's identical STOP/QUIT. */
    private fun confirmQuit() {
        // Themed, unlike the DJI sibling's otherwise-identical confirmQuit — that one is
        // unthemed and renders as a white card on a dark screen, which is the blueprint being
        // inconsistent with itself rather than a decision worth copying.
        AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            .setTitle("Stop & Quit")
            .setMessage("Force-stop TAKPilot2-Autel and all its background processes (video stream, TAK connection, telemetry)? You'll need to relaunch the app.")
            .setPositiveButton("Stop & Quit") { _, _ -> doQuit() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doQuit() {
        AppLog.i(TAG, "STOP/QUIT — tearing down and killing process")
        runCatching { VideoStreamerHolder.stop() }
        runCatching { TakBridgeHolder.stop() }
        runCatching { TakManager.getInstance().disconnect() }
        runCatching { TakForegroundService.stop(applicationContext) }
        handler.removeCallbacksAndMessages(null)
        finishAffinity()
        Handler(Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 200)
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

        val connected = TakManager.getInstance().isConnected
        val color = if (connected) Color.parseColor("#4CAF50") else Color.parseColor("#F44336")
        takStatus.text = if (connected) "TAK: Connected" else "TAK: Disconnected"
        takStatus.setTextColor(color)
        (takDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
            ?: takDot.background?.setTint(color)
    }

    companion object { private const val TAG = "TakPilotHomeActivity" }
}
