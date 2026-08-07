package com.autel.sdksample.tak

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.autel.sdksample.BuildConfig
import com.autel.sdksample.R
import com.taklite.util.AppLog
import java.io.RandomAccessFile

/**
 * Debug screen (handoff §9): toggle file logging on/off, export/clear/delete the active
 * log, and watch it fill live. Only reads/writes AppLog's own file sink — no full logcat.
 */
class DebugActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var meta: TextView

    // Only re-render when the file actually changed, and tail it so a near-cap 1MB
    // file does not get re-laid-out into the TextView every tick.
    private var lastRenderedLength = -1L
    private val maxTailBytes = 500 * 1024L

    // Explicit, touch-driven "follow the tail" state — more robust than re-deriving it
    // from scroll geometry on every poll, which is sensitive to layout-pass timing.
    // The instant the user puts a finger down on the log, we stop auto-scrolling; we
    // only resume following once they've scrolled back to the bottom themselves.
    private var pinnedToBottom = true

    private val poll = object : Runnable {
        override fun run() {
            refreshLogView()
            handler.postDelayed(this, 1000)
        }
    }

    companion object { private const val TAG = "DebugActivity" }

    /** The action-bar menu button returns to the home screen. */
    override fun onSupportNavigateUp(): Boolean {
        AppLog.v(TAG, "menu tapped — back to home")
        finish()
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)

        AppLog.sweepExpiredLogs()
        AppLog.v(TAG, "onCreate")
        // Menu button on the left of the action bar, matching the flight screen and Pre-Flight —
        // returns to the home screen.
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_menu)
        }

        logText = findViewById(R.id.debugLogText)
        logScroll = findViewById(R.id.debugLogScroll)
        meta = findViewById(R.id.debugLogMeta)

        logScroll.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                pinnedToBottom = false
            } else if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                // Let the fling/settle finish, then check if they landed back at the bottom.
                logScroll.postDelayed({ pinnedToBottom = isScrolledToBottom() }, 300)
            }
            false   // do not consume — ScrollView still needs this to handle the drag/fling
        }

        val toggle = findViewById<CheckBox>(R.id.debugLoggingToggle)
        toggle.isChecked = AppLog.enabled
        toggle.setOnCheckedChangeListener { _, on ->
            AppLog.enabled = on
            AppLog.v(TAG, "logging ${if (on) "enabled" else "disabled"}")
        }

        val verboseToggle = findViewById<CheckBox>(R.id.debugVerboseToggle)
        verboseToggle.isChecked = AppLog.verbose
        verboseToggle.setOnCheckedChangeListener { _, on ->
            AppLog.verbose = on
            AppLog.v(TAG, "detail level set to ${if (on) "Detailed" else "Standard"}")
        }

        val takToggle = findViewById<CheckBox>(R.id.debugTakToggle)
        takToggle.isChecked = AppLog.takLogging
        takToggle.setOnCheckedChangeListener { _, on ->
            AppLog.takLogging = on
            // Logged from DebugActivity (an app-side tag), so this line survives either way —
            // it marks the point in the log where the filter changed.
            AppLog.i(TAG, "TAK/CoT logs ${if (on) "INCLUDED" else "HIDDEN"}")
        }

        val radarToggle = findViewById<CheckBox>(R.id.debugRadarToggle)
        radarToggle.isChecked = AppLog.radarLogging
        radarToggle.setOnCheckedChangeListener { _, on ->
            AppLog.radarLogging = on
            // Same reasoning as the TAK toggle above: logged from an app-side tag so the line
            // survives the filter it is describing, and marks where the log changed shape.
            AppLog.i(TAG, "obstacle radar logs ${if (on) "INCLUDED" else "HIDDEN"}")
        }

        val resourceMonitorToggle = findViewById<CheckBox>(R.id.debugResourceMonitorToggle)
        resourceMonitorToggle.isChecked = AppLog.resourceMonitor
        resourceMonitorToggle.setOnCheckedChangeListener { _, on ->
            AppLog.resourceMonitor = on
            AppLog.i(TAG, "flight-screen resource monitor ${if (on) "ENABLED" else "DISABLED"}")
        }

        setupExplorerControls()

        findViewById<android.widget.Button>(R.id.debugRfPowerProbe).setOnClickListener {
            AppLog.i(TAG, "RF power probe tapped")
            runRfPowerProbe()
        }
        findViewById<android.widget.Button>(R.id.debugExportButton).setOnClickListener {
            AppLog.v(TAG, "export tapped")
            exportLog()
        }
        findViewById<android.widget.Button>(R.id.debugClearButton).setOnClickListener {
            AppLog.clearActive()
            lastRenderedLength = -1
            pinnedToBottom = true
            refreshLogView()
            toast("Log cleared")
        }
        findViewById<android.widget.Button>(R.id.debugDeleteButton).setOnClickListener {
            AppLog.deleteAll()
            lastRenderedLength = -1
            pinnedToBottom = true
            refreshLogView()
            toast("All logs deleted")
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    private fun refreshLogView() {
        val file = AppLog.activeLogFile()
        if (!file.exists()) {
            if (lastRenderedLength != 0L) {
                logText.text = "(No log yet. Turn on Logging enabled to start.)"
                lastRenderedLength = 0
            }
            meta.text = ""
            return
        }
        val length = file.length()
        if (length == lastRenderedLength) return
        lastRenderedLength = length

        val tail = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val start = maxOf(0L, length - maxTailBytes)
                raf.seek(start)
                val bytes = ByteArray((length - start).toInt())
                raf.readFully(bytes)
                String(bytes)
            }
        }.getOrDefault("(failed to read log file)")

        logText.text = tail
        meta.text = "${file.name} — ${length / 1024} KB"

        // Only auto-scroll if the user hasn't manually scrolled away from the bottom —
        // otherwise a 1s poll would yank them back down mid scroll-back through history.
        if (pinnedToBottom) {
            logScroll.post { logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private fun isScrolledToBottom(): Boolean {
        if (logText.height == 0) return true   // nothing laid out yet — treat as "at bottom"
        val slop = (8 * resources.displayMetrics.density).toInt()
        val bottom = logScroll.scrollY + logScroll.height
        return bottom >= logText.height - slop
    }

    /**
     * RF transmit-power probe (2026-08-07), built because Autel support asked for logs on
     * whether the RC's transmit power can be changed from this SDK.
     *
     * What the SDK offers — established by sweeping the WHOLE aar surface (RC, DSP,
     * fly-controller, legacy sdk10), not one subsystem: exactly ONE power control exists,
     * `setRFPower(FCC | CE)` on the remote controller — a regulatory REGION toggle, not a
     * dBm value. The DSP's RFData get/set is the frequency-CHANNEL table (its second
     * parameter is dropped in Dsp20 bytecode), and SignalInfo's meanPower/gain are telemetry
     * readouts. There is no dBm-granular transmit-power API in this SDK.
     *
     * The probe exercises that one control with read-backs and logs each step under this
     * activity's tag: current region → set CE → read back → set FCC (the fleet's configured
     * region, so the sequence ENDS in the state applyRfPower pushes anyway) → read back.
     * If the read-back follows the set, the region knob works and any dBm lock is inside
     * the radio's own region tables; if it snaps back, the log shows the firmware refusing —
     * either way this file is the evidence Autel asked for.
     *
     * Uses only the request/response packet path (RemoteControllerManager2.sendPacket) — no
     * single-slot listeners touched, so the bridge's channels are safe (standing rule 2).
     */
    private fun runRfPowerProbe() {
        val rc = AutelProductHolder.evo2?.remoteController
        if (rc == null) {
            AppLog.w(TAG, "RF probe: no aircraft/RC — connect and retry")
            android.widget.Toast.makeText(this, "No aircraft connected.",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val h = android.os.Handler(mainLooper)
        AppLog.i(TAG, "RF probe START — sdk=${runCatching {
            com.autel.sdk.Autel.getSdkVersion() }.getOrNull()} " +
            "product=${AutelProductHolder.product?.type}")

        fun get(step: String, then: (() -> Unit)? = null) {
            rc.getRFPower(object :
                com.autel.common.CallbackWithOneParam<com.autel.common.remotecontroller.RFPower> {
                override fun onSuccess(p: com.autel.common.remotecontroller.RFPower?) {
                    AppLog.i(TAG, "RF probe $step: getRFPower = $p (value=${p?.value})")
                    then?.let { h.postDelayed(it, STEP_DELAY_MS) }
                }
                override fun onFailure(e: com.autel.common.error.AutelError?) {
                    AppLog.w(TAG, "RF probe $step: getRFPower FAILED: ${e?.description}")
                    then?.let { h.postDelayed(it, STEP_DELAY_MS) }
                }
            })
        }
        fun set(step: String, want: com.autel.common.remotecontroller.RFPower, then: () -> Unit) {
            rc.setRFPower(want, object : com.autel.common.CallbackWithNoParam {
                override fun onSuccess() {
                    AppLog.i(TAG, "RF probe $step: setRFPower($want) ACCEPTED")
                    h.postDelayed(then, STEP_DELAY_MS)
                }
                override fun onFailure(e: com.autel.common.error.AutelError?) {
                    AppLog.w(TAG, "RF probe $step: setRFPower($want) REFUSED: ${e?.description}")
                    h.postDelayed(then, STEP_DELAY_MS)
                }
            })
        }

        get("1/5 baseline") {
            set("2/5", com.autel.common.remotecontroller.RFPower.CE) {
                get("3/5 after-CE") {
                    set("4/5", com.autel.common.remotecontroller.RFPower.FCC) {
                        get("5/5 after-FCC-restore") {
                            AppLog.i(TAG, "RF probe DONE — export the log for Autel")
                            android.widget.Toast.makeText(this,
                                "RF probe done. Use Export Log.",
                                android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private val STEP_DELAY_MS = 2000L

    private fun exportLog() {
        val file = AppLog.activeLogFile()
        if (!file.exists() || file.length() == 0L) {
            toast("Nothing to export yet")
            return
        }
        val uri: Uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Export debug log"))
    }

    /**
     * Autel Explorer watchdog control — a single on/off toggle. The watchdog kills Explorer's
     * background process when it tries to take the aircraft link (no permanent change to the
     * controller; Explorer opens normally when the pilot opens it). See [ExplorerWatchdog].
     */
    private fun setupExplorerControls() {
        val status = findViewById<TextView>(R.id.debugExplorerStatus)
        val toggle = findViewById<CheckBox>(R.id.debugExplorerToggle)

        fun render() {
            status.text = ExplorerWatchdog.statusLine(this)
            toggle.setOnCheckedChangeListener(null)
            toggle.isChecked = ExplorerWatchdog.isEnabled(this)
            toggle.setOnCheckedChangeListener { _, on ->
                ExplorerWatchdog.setEnabled(this, on)
                render()
            }
        }
        render()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
