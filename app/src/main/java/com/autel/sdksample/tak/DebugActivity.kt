package com.autel.sdksample.tak

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.autel.sdksample.BuildConfig
import com.autel.sdksample.R
import com.taklite.util.AppLog
import java.io.RandomAccessFile

/**
 * Debug screen (handoff §9): toggle file logging on/off, clear/delete the active
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

    /** Commits the SRT latency box. Set in [setupSrtLatencyControl]; called from the Done key,
     *  from focus loss, and from [onPause] so leaving the screen does not discard the value. */
    private var commitSrtLatency: (() -> Unit)? = null

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
        setupSrtLatencyControl()

        findViewById<android.widget.Button>(R.id.debugRfPowerProbe).setOnClickListener {
            AppLog.i(TAG, "RF power probe tapped")
            runRfPowerProbe()
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
        // Leaving the screen is a commit point: a pilot who types a value and taps the menu
        // button has made a decision, and losing it silently is worse than storing it.
        commitSrtLatency?.invoke()
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
        //
        // ⚠ **This MUST NOT be `fullScroll(FOCUS_DOWN)`** (fixed 2026-08-30). That method does
        // not only scroll: it calls `scrollAndFocus`, which finds a focusable view at the new
        // position and calls `requestFocus()` on it. Focus is window-wide, thus a scroll of
        // the LOG pane took the focus off the SRT latency box in the options column — once a
        // second, for as long as the screen was open.
        //
        // The result was a field that could not be typed into at all. The keyboard opened, then
        // the next poll took the focus away, the focus-loss handler wrote the SAVED value back
        // into the box, and the pilot saw the number snap back to 500 with every keystroke. It
        // read as a read-only control; it was a fight with a timer.
        //
        // `scrollTo` moves the same distance and leaves the focus alone. Keep it that way: any
        // ScrollView method with `Focus` in the name will bring the fault back.
        if (pinnedToBottom) {
            logScroll.post {
                val child = logScroll.getChildAt(0) ?: return@post
                logScroll.scrollTo(0, maxOf(0, child.bottom - logScroll.height))
            }
        }
    }

    private fun isScrolledToBottom(): Boolean {
        if (logText.height == 0) return true   // nothing laid out yet — treat as "at bottom"
        val slop = (8 * resources.displayMetrics.density).toInt()
        val bottom = logScroll.scrollY + logScroll.height
        return bottom >= logText.height - slop
    }

    /**
     * RF transmit-power probe (2026-08-07). Autel support asked for logs that show whether
     * this SDK can change the RC transmit power.
     *
     * What the SDK offers, found by a sweep of the FULL aar surface (RC, DSP,
     * fly-controller, legacy sdk10), not one subsystem: one power control exists,
     * `setRFPower(FCC | CE)` on the remote controller. It selects a regulatory REGION, not
     * a dBm value. The DSP's RFData get/set is the frequency-CHANNEL table (Dsp20 bytecode
     * drops its second parameter). SignalInfo's meanPower and gain are telemetry readouts.
     * This SDK has no API that sets a dBm value.
     *
     * The probe operates that one control with read-backs and logs each step under this
     * activity's tag. If the read-back follows the set, the region control works, and any
     * dBm limit lives in the radio's own region tables. If the value does not change, the
     * log shows the refusal from the firmware. In both cases the log file is the evidence
     * that Autel asked for. The sequence ends at FCC, the region the fleet configuration
     * pushes at each connect.
     *
     * The probe uses only the request/response packet path. It touches no single-client
     * listener slots, so the bridge's channels are safe (standing rule 2).
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

        // Phase 2 (2026-08-07, operator approved): the RC refused each public setRFPower,
        // including CE to CE. Autel Explorer's own binary uses a DIFFERENT path:
        // DspRFManager2.enableFCCMode sends FCCModePacket to the AIRCRAFT fly-controller
        // channel (AU_PHONE_CTRL_FCC_MODE_REQ). We found this path when we decompiled
        // Explorer. The internal API has no callback, so the read-backs are the only
        // confirmation. If the region changes, the sequence keeps it at FCC — the region
        // that the fleet configuration pushes, and that applyRfPower could not reach.
        get("1/6 baseline") {
            AppLog.i(TAG, "RF probe 2/6: enableFCCMode(1) via DspRFManager2 (Explorer's path) — sent")
            runCatching {
                com.autel.AutelNet2.dsp.controller.DspRFManager2.getInstance().enableFCCMode(1)
            }.onFailure { AppLog.w(TAG, "RF probe 2/6: enableFCCMode threw: $it") }
            h.postDelayed({
                get("3/6 3s-after-fccMode") {
                    h.postDelayed({
                        get("4/6 10s-after-fccMode") {
                            set("5/6 setRFPower(FCC) retry", com.autel.common.remotecontroller.RFPower.FCC) {
                                get("6/6 final") {
                                    AppLog.i(TAG, "RF probe DONE — the log is in Downloads/TAKPilot2 Logs")
                                    android.widget.Toast.makeText(this,
                                        "RF probe done. The log is in Downloads/TAKPilot2 Logs.",
                                        android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }, 7000L)
                }
            }, 3000L)
        }
    }

    private val STEP_DELAY_MS = 2000L


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

    /**
     * The SRT latency override, in MILLISECONDS.
     *
     * Here rather than on Pre-Flight because it is a property of the network, not a flight
     * decision — and on a screen at all rather than a constant because 500 ms comes from one
     * ground test, and the fleet must be able to act on field evidence without a new build
     * reaching an aircraft that is flying. See [VideoTransport.SRT_LATENCY_DEFAULT_MS] for the
     * measurements and for how to tell whether the value is right.
     *
     * ⚠ **The status line under the box states what was SAVED, never what was typed.** A value
     * outside the sane range is refused and the default is stored, thus a pilot who types the
     * microsecond form (500000) is told that the value is 500 and not left believing the box.
     *
     * The value is read at every stream start, so a change here takes effect at the next LIVE.
     *
     * ## Three commit points, because focus loss alone is not reachable
     *
     * The value was saved ONLY when the box lost the focus. This is the one text field on the
     * screen, and every other control (check boxes, buttons) is `focusableInTouchMode=false`
     * by default — thus in touch mode there is NOTHING for the focus to move to. The only
     * thing that ever took it was the log pane's own auto-scroll, which is the fault that made
     * the box unusable (see [refreshLogView]). With that corrected, focus loss became
     * unreachable, and a save that waits for it would never run.
     *
     * So the value commits on the keyboard's Done key, on leaving the screen, AND on focus
     * loss if it ever happens. [commitSrtLatency] is idempotent, thus more than one of them
     * firing is harmless.
     */
    private fun setupSrtLatencyControl() {
        val field = findViewById<android.widget.EditText>(R.id.debugSrtLatency)
        val status = findViewById<TextView>(R.id.debugSrtLatencyStatus)
        val prefs = getSharedPreferences("takpilot2_tak", MODE_PRIVATE)

        fun renderStatus() {
            val saved = VideoTransport.srtLatencyMs(prefs)
            status.text = "In use: ${saved} ms" +
                    if (saved == VideoTransport.SRT_LATENCY_DEFAULT_MS) " (default)" else ""
        }

        field.setText(VideoTransport.srtLatencyMs(prefs).toString())
        renderStatus()

        // Not saved on each keystroke: a partly typed "5" out of "500" is inside the sane
        // range and would be stored as a real value.
        commitSrtLatency = commit@{
            val typed = field.text.toString().trim().toIntOrNull()
            val use = VideoTransport.clampLatencyMs(typed ?: VideoTransport.SRT_LATENCY_DEFAULT_MS)
            // Idempotent: a second commit with nothing changed writes nothing and says nothing.
            if (typed == use && use == VideoTransport.srtLatencyMs(prefs)) return@commit
            prefs.edit().putInt(VideoTransport.KEY_SRT_LATENCY_MS, use).apply()
            if (typed != null && typed != use) {
                toast("$typed ms is outside ${VideoTransport.SRT_LATENCY_MIN_MS}–" +
                        "${VideoTransport.SRT_LATENCY_MAX_MS} ms — using $use ms")
            }
            field.setText(use.toString())
            renderStatus()
            AppLog.i(TAG, "SRT latency -> $use ms")
        }

        field.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitSrtLatency?.invoke()
        }

        // The Done key. This is the commit point a pilot will actually reach.
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitSrtLatency?.invoke()
                field.clearFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(field.windowToken, 0)
                true
            } else false
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
