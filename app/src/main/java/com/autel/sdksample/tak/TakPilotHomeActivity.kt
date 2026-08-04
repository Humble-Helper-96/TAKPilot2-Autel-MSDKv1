package com.autel.sdksample.tak

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.autel.sdk.Autel
import com.autel.sdksample.BuildConfig
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
    private lateinit var aircraftImage: ImageView
    private lateinit var avoidance: TextView
    private lateinit var stickMode: TextView
    private lateinit var controlResponse: TextView
    private lateinit var batteryLevels: TextView
    private lateinit var initializing: TextView
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
        // Mark that this process has passed through Home. Read by FlightActivity to tell a normal
        // Home→Flight entry from Android resurrecting the flight screen into a cold process after
        // an OOM kill (see FlightActivity.onCreate's cold-restart guard).
        visitedThisProcess = true

        // Wire the aircraft-connection singleton once, then silently reconnect TAK with
        // saved enrollment so the operator lands here already connected.
        AutelProductHolder.install()
        TakAutoConnect.tryReconnect(applicationContext)

        aircraft = findViewById(R.id.homeAircraft)
        aircraftImage = findViewById(R.id.homeAircraftImage)
        avoidance = findViewById(R.id.homeAvoidance)
        stickMode = findViewById(R.id.homeStickMode)
        controlResponse = findViewById(R.id.homeControlResponse)
        batteryLevels = findViewById(R.id.homeBatteryLevels)
        initializing = findViewById(R.id.homeInitializing)
        sdk = findViewById(R.id.homeSdk)
        takStatus = findViewById(R.id.homeTakStatus)
        takDot = findViewById(R.id.homeTakDot)
        // Fixed at build time, not runtime state — set once, no need to touch it in updateStatus().
        // VERSION_NAME is real semver (see build.gradle); VERSION_CODE is Android's own internal
        // update-ordering integer and has no semver meaning, so it is deliberately not shown here
        // — BUILD_TIME already identifies an exact build more precisely than that number could.
        findViewById<TextView>(R.id.homeVersion).text =
            "v${BuildConfig.VERSION_NAME}  ·  built ${BuildConfig.BUILD_TIME}"

        // One tap target: the whole card. The separate "Enter Flight" button that used to sit
        // below it was removed — it duplicated the card's own click and cost ~56dp of a
        // height budget that was already over on shorter screens (see the layout comment).
        findViewById<android.view.View>(R.id.homeEnterFlight).setOnClickListener {
            if (initializingUntilMs > System.currentTimeMillis()) {
                AppLog.v(TAG, "Enter Flight tapped during initialise — ignored")
                android.widget.Toast.makeText(this,
                    "Wait. The app is setting up the aircraft.",
                    android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
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
            .setMessage("Stop TAKPilot and close the video, the TAK connection, and telemetry? You must start the app again to fly.")
            .setPositiveButton("Stop & Quit") { _, _ -> doQuit() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doQuit() {
        AppLog.i(TAG, "STOP/QUIT — tearing down and killing process")
        // Same teardown a swipe now performs — see AppTeardown. These two paths previously did
        // different subsets of the work, which is how the SDK ended up never being released on
        // task removal.
        runCatching { AppTeardown.releaseAll(applicationContext) }
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

    /**
     * Holds the pilot on this screen while the connect-time pushes land.
     *
     * The settings that decide how the aircraft handles — stick mode, control response,
     * obstacle avoidance — are enforced a few seconds AFTER the product connects. A pilot who
     * taps straight through lands on the flight screen mid-push and cannot know what was
     * applied. Five seconds is not about the pushes needing exactly that long; it is about the
     * pilot's eyes being on the card that states what they are about to fly with.
     *
     * The word PULSES rather than sitting still on purpose: a frozen label on a screen that
     * refuses taps reads as a crash, and a pilot who thinks the app has hung will force-quit it.
     */
    private fun startInitializing() {
        initializingUntilMs = System.currentTimeMillis() + INITIALIZING_MS
        initializing.visibility = View.VISIBLE
        initializing.alpha = 1f
        initializing.animate().cancel()
        pulseInitializing()
        handler.removeCallbacks(endInitializing)
        handler.postDelayed(endInitializing, INITIALIZING_MS)
    }

    private fun pulseInitializing() {
        if (initializingUntilMs <= System.currentTimeMillis()) return
        initializing.animate().alpha(0.25f).setDuration(700L).withEndAction {
            if (initializingUntilMs <= System.currentTimeMillis()) return@withEndAction
            initializing.animate().alpha(1f).setDuration(700L)
                .withEndAction { pulseInitializing() }.start()
        }.start()
    }

    private val endInitializing = Runnable {
        initializingUntilMs = 0L
        initializing.animate().cancel()
        initializing.visibility = View.GONE
        AppLog.v(TAG, "initialise hold released")
    }

    /** Long enough for the connect-time pushes AND for the pilot to read the card. */
    private val INITIALIZING_MS = 5000L

    private var initializingUntilMs = 0L
    private var sawProduct = false

    private fun updateStatus() {
        val product = AutelProductHolder.product
        aircraft.text = product?.type?.toString() ?: "Not connected"
        // Shown only once an aircraft is actually connected, so the picture is a status
        // cue rather than decoration. INVISIBLE rather than GONE — see the layout note:
        // collapsing it would move "TAP TO ENTER" every time the aircraft comes and goes.
        aircraftImage.visibility = if (product != null) View.VISIBLE else View.INVISIBLE

        // Obstacle avoidance, reported the moment the aircraft syncs.
        //
        // THREE STATES, NOT TWO. "Unknown" is shown as its own thing rather than collapsed into
        // "off", because a pilot reading "AVOIDANCE OFF" will act on it, and telling them the
        // system is off when we simply have not been told yet would be a lie with consequences.
        // Amber for unknown, red for genuinely disabled, green for on.
        avoidance.text = when {
            product == null -> ""
            AutelAvoidance.systemEnabled == true -> "OBSTACLE AVOIDANCE: ON"
            AutelAvoidance.systemEnabled == false -> "OBSTACLE AVOIDANCE: OFF"
            else -> "OBSTACLE AVOIDANCE: —"
        }
        avoidance.setTextColor(
            when (AutelAvoidance.systemEnabled) {
                true -> Color.parseColor("#4CAF50")
                false -> Color.parseColor("#F44336")
                null -> Color.parseColor("#FFB300")
            })

        // Sticks and control feel. Both are enforced from Pre-Flight at connect, so these state
        // what the aircraft WILL do rather than what it happens to be set to.
        // The hold starts when the aircraft first appears — that is when the connect-time
        // pushes are scheduled, so that is when there is something to wait for.
        if (product != null && !sawProduct) {
            sawProduct = true
            startInitializing()
        } else if (product == null) {
            sawProduct = false
        }

        stickMode.text = if (product == null) "" else "STICKS: ${AutelControlRates.stickModeLabel()}"
        controlResponse.text = when {
            product == null -> ""
            AutelControlRates.precisionActive == true -> "CONTROL RESPONSE: PRECISION"
            AutelControlRates.precisionActive == false -> "CONTROL RESPONSE: NORMAL"
            else -> "CONTROL RESPONSE: —"
        }
        // Battery levels, straight from the aircraft — never the saved preference. If Apply to
        // Aircraft did not take, this is where it shows, so falling back to what the pilot typed
        // would hide the one failure this line exists to catch. Amber until they arrive, matching
        // the "unknown is not the same as known" rule the avoidance line above follows.
        val warn = FlightLimitsController.aircraftWarningPct
        val crit = FlightLimitsController.aircraftCriticalPct
        batteryLevels.text = when {
            product == null -> ""
            warn != null && crit != null ->
                "BATTERY: WARN ${Math.round(warn)}% · CRIT ${Math.round(crit)}%"
            else -> "BATTERY: —"
        }
        batteryLevels.setTextColor(
            if (warn != null && crit != null) Color.parseColor("#9AC4FF")
            else Color.parseColor("#FFB300"))

        val settled = AutelControlRates.precisionActive != null
        val infoColor = if (settled) Color.parseColor("#9AC4FF") else Color.parseColor("#FFB300")
        stickMode.setTextColor(infoColor)
        controlResponse.setTextColor(infoColor)
        sdk.text = "Autel MSDK " + (runCatching { Autel.getSdkVersion() }.getOrNull() ?: "1.5")

        val connected = TakManager.getInstance().isConnected
        val color = if (connected) Color.parseColor("#4CAF50") else Color.parseColor("#F44336")
        takStatus.text = if (connected) "TAK: Connected" else "TAK: Disconnected"
        takStatus.setTextColor(color)
        (takDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
            ?: takDot.background?.setTint(color)
    }

    companion object {
        private const val TAG = "TakPilotHomeActivity"

        /**
         * True once Home has run in the current process. Survives config changes (same process);
         * reset to false whenever the OS recreates the process. FlightActivity reads it to
         * distinguish a deliberate Home→Flight entry from Android resurrecting the flight screen
         * directly into a cold process after an OOM kill — in the cold-process case the Autel SDK
         * was never armed ([AutelProductHolder.install] runs from Home), so the flight screen would
         * otherwise come up with a dead aircraft link and a frozen HUD.
         */
        @Volatile var visitedThisProcess = false
            private set
    }
}
