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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)

        AppLog.sweepExpiredLogs()
        AppLog.v(TAG, "onCreate")

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

        setupExplorerControls()

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
                logText.text = "(no log yet — enable logging to start capturing)"
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
     * Autel Explorer suppression controls.
     *
     * The restore button is deliberately ALWAYS enabled when provisioned, regardless of the
     * checkbox. It is the escape hatch: if the app ever dies with Explorer hidden, a pilot needs
     * one obvious way to get Explorer back without adb, and it must not be gated behind the
     * setting that caused the problem.
     */
    private fun setupExplorerControls() {
        val status = findViewById<TextView>(R.id.debugExplorerStatus)
        val toggle = findViewById<CheckBox>(R.id.debugExplorerToggle)
        val restoreBtn = findViewById<android.widget.Button>(R.id.debugExplorerRestoreButton)
        val deprovisionBtn = findViewById<android.widget.Button>(R.id.debugExplorerDeprovisionButton)

        fun render() {
            val provisioned = ExplorerSuppressor.isDeviceOwner(this)
            status.text = ExplorerSuppressor.statusLine(this)
            toggle.isEnabled = provisioned
            restoreBtn.isEnabled = provisioned
            deprovisionBtn.isEnabled = provisioned
            toggle.setOnCheckedChangeListener(null)
            toggle.isChecked = ExplorerSuppressor.isEnabled(this)
            toggle.setOnCheckedChangeListener { _, on ->
                // setEnabled FIRST: arm() gates on isAvailable(), which reads this very pref.
                // Reversed, the toggle is a silent no-op — an invisible failure.
                ExplorerSuppressor.setEnabled(this, on)
                if (on) {
                    // We are an activity, so a task provably exists and onTaskRemoved can fire.
                    TakSessionAnchor.arm(this, "operator enabled suppression")
                } else {
                    // setEnabled(false) already restored; drop the anchor unless something else
                    // still needs it.
                    TakForegroundService.releaseIfIdle(applicationContext)
                }
                render()
            }
        }
        render()

        restoreBtn.setOnClickListener {
            AppLog.v(TAG, "tap: Restore Explorer")
            val ok = ExplorerSuppressor.restore(this, "operator pressed Restore")
            // Nothing is owed now, so the anchor can go unless the aircraft or TAK still need it.
            TakForegroundService.releaseIfIdle(applicationContext)
            toast(if (ok) "Explorer is available again." else "Could not restore Explorer.")
            render()
        }

        // Confirmed, because device-owner status cannot be granted back from inside the app —
        // re-provisioning needs adb, and on a device with accounts it may not be possible at all.
        deprovisionBtn.setOnClickListener {
            AppLog.v(TAG, "tap: Remove device-owner rights")
            android.app.AlertDialog.Builder(this)
                .setTitle("Remove device-owner rights?")
                .setMessage("Explorer will be restored and this app will no longer be able to " +
                    "keep it closed. To turn this back on, the controller must be set up again " +
                    "over USB from a computer.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove") { _, _ ->
                    val ok = ExplorerSuppressor.deprovision(this)
                    toast(if (ok) "Device-owner rights removed." else "Could not remove rights.")
                    render()
                }
                .show()
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
