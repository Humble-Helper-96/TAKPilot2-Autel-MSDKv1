package com.autel.sdksample.tak

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.taklite.client.tak.CotBuilder
import com.taklite.client.tak.TakCertEnroller
import com.taklite.client.tak.TakMissionClient
import com.taklite.client.tak.TakManager
import com.autel.sdksample.R
import com.taklite.util.AppLog
import java.io.File
import java.util.UUID

/**
 * Minimal TAK enroll + connect screen for TAKPilot2.
 *
 * Reuses taklite's TakCertEnroller (cert enrollment over HTTPS) and TakManager
 * (TLS CoT client). On success it starts a DroneTakBridge that streams the M30's
 * position to the server as an air track. This is the fast path to verify
 * aircraft -> CoT -> TAK end-to-end; the full QR enrollment wizard comes later.
 */
class TakConnectActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onDestroy() {
        // A debounced write must not outlive the screen that scheduled it — leaving the pilot's
        // half-typed value to land on the aircraft after they navigated away.
        cancelPendingSettingPushes()
        // The listener holds this Activity. TakManager outlives the screen, thus leaving it
        // attached leaks the whole Activity and repaints views that are gone.
        runCatching { TakManager.getInstance().removeGroupChangeListener(groupChangeListener) }
        runCatching { TakManager.getInstance().removeListener(connectionListener) }
        super.onDestroy()
    }

    /** The action-bar menu button returns to the home screen, same as the flight toolbar's. */
    override fun onSupportNavigateUp(): Boolean {
        AppLog.v(TAG, "menu tapped — back to home")
        finish()
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tak_connect)
        AppLog.v(TAG, "onCreate")

        // Menu button on the left of the action bar, matching the flight screen's ic_menu — it
        // returns to the home screen (finish(), same as the flight toolbar's back button).
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_menu)
        }

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        setupDroneSettingsSection()
        setupDtedSection()
        setupUasfmSection()

        val host = findViewById<EditText>(R.id.takHost)
        val enrollPort = findViewById<EditText>(R.id.takEnrollPort)
        val cotPort = findViewById<EditText>(R.id.takCotPort)
        val username = findViewById<EditText>(R.id.takUsername)
        val password = findViewById<EditText>(R.id.takPassword)
        val callsign = findViewById<EditText>(R.id.takCallsign)
        status = findViewById(R.id.takStatus)

        // Restore last-used values (except password).
        host.setText(prefs.getString(KEY_HOST, ""))
        enrollPort.setText(prefs.getInt(KEY_ENROLL_PORT, 8446).toString())
        cotPort.setText(prefs.getInt(KEY_COT_PORT, 8089).toString())
        username.setText(prefs.getString(KEY_USERNAME, ""))
        callsign.setText(prefs.getString(KEY_CALLSIGN, "TAKPilot2-EVO2"))

        // Camera look-point toggle (applies live to the running bridge + persists).
        val cameraPoint = findViewById<android.widget.CheckBox>(R.id.takCameraPoint)
        wireAvoidanceSection()
        wireControlRatesSection()
        cameraPoint.isChecked = prefs.getBoolean(KEY_CAMERA_POINT, false)
        cameraPoint.setOnCheckedChangeListener { _, isOn ->
            AppLog.v(TAG, "camera point toggle -> $isOn")
            prefs.edit().putBoolean(KEY_CAMERA_POINT, isOn).apply()
            TakBridgeHolder.setCameraPointEnabled(isOn)
        }
        TakBridgeHolder.setCameraPointEnabled(cameraPoint.isChecked)

        // My Channels. The channels come from the server and go back to the server, and no
        // <dest group> goes on any message — that attribute is what made the server drop every
        // marker in v1.6.0. The evidence is in CHANNELS-FINDINGS.md.
        refreshChannels()
        // The server pushes t-x-g-c when the channels change, from this controller or from an
        // administrator in TAK Portal. Listening beats a timer: the screen follows in about a
        // second, and it asks the server nothing while nothing changes.
        TakManager.getInstance().addGroupChangeListener(groupChangeListener)
        // AND read them again when TAK connects. The refresh above needs a connection, so a
        // screen opened before TAK is up would otherwise show an empty list for ever — the
        // "Pull Channels" button used to be the only way out of that, and it is gone
        // (operator, 2026-08-16).
        TakManager.getInstance().addListener(connectionListener)

        // Reflect live state on open, and silently reconnect with saved certs if the
        // socket is not up — so the user never has to re-enter credentials / re-enroll.
        when {
            TakManager.getInstance().isConnected ->
                setStatus("Connected. Sending the aircraft position to TAK.", androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_go))
            prefs.getBoolean(KEY_LOGGED_OUT, false) ->
                setStatus("Logged out. Enter host, username and password to sign in.", androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
            hasSavedCerts(prefs) -> {
                setStatus("Reconnecting with saved enrollment …", androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
                reconnectFromSaved(prefs, callsign.text.toString().trim().ifEmpty { "TAKPilot2-EVO2" })
            }
            else -> setStatus("Not connected.", androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
        }

        findViewById<Button>(R.id.takConnectButton).setOnClickListener {
            AppLog.v(TAG, "Connect tapped")
            val h = host.text.toString().trim()
            val u = username.text.toString().trim()
            val p = password.text.toString()
            val cs = callsign.text.toString().trim().ifEmpty { "TAKPilot2-EVO2" }
            val ep = enrollPort.text.toString().trim().toIntOrNull() ?: 8446
            val cp = cotPort.text.toString().trim().toIntOrNull() ?: 8089

            if (TakManager.getInstance().isConnected) {
                setStatus("Already connected.", androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_go))
                return@setOnClickListener
            }
            // Fail here, in words, when the controller has no route out — BEFORE the enroller
            // turns the same fact into a generic TLS/socket error. Field reports (v1.5.9,
            // event 1) had pilots reading "enrollment failed" as a server or credential fault
            // when the controller simply had no network. Guards both paths below: fresh
            // enrollment and reconnect-from-saved both need the network.
            if (!NetworkStatus.hasInternet(this)) {
                AppLog.w(TAG, "Connect blocked: no validated network")
                setStatus("No network connection. Connect the controller to wifi first — " +
                    "check the WIFI line on the home screen.", androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_danger))
                return@setOnClickListener
            }
            prefs.edit()
                .putString(KEY_HOST, h.ifEmpty { prefs.getString(KEY_HOST, "") })
                .putInt(KEY_ENROLL_PORT, ep)
                .putInt(KEY_COT_PORT, cp)
                .putString(KEY_USERNAME, u.ifEmpty { prefs.getString(KEY_USERNAME, "") })
                .putString(KEY_CALLSIGN, cs)
                .apply()

            // If we already enrolled before, reconnect with saved certs — no password needed.
            if (hasSavedCerts(prefs) && p.isEmpty()) {
                reconnectFromSaved(prefs, cs)
                return@setOnClickListener
            }
            if (h.isEmpty() || u.isEmpty() || p.isEmpty()) {
                setStatus("Host, username and password are required for first enrollment.",
                    androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_danger))
                return@setOnClickListener
            }
            enrollAndConnect(h, ep, cp, u, p, cs)
        }

        findViewById<Button>(R.id.takDisconnectButton).setOnClickListener {
            AppLog.v(TAG, "Logout tapped")
            // Full LOG OUT: stop everything AND clear the saved enrollment so the app will not silently
            // reconnect the old user, and a different user can enroll cleanly. Each teardown step is
            // guarded — a throw from the closing socket must NOT abort the logout (that crash was
            // why logout never stuck). clearEnrollment + the logged-out flag always run.
            runCatching { VideoStreamerHolder.stop() }
            runCatching { TakBridgeHolder.stop() }
            runCatching { TakManager.getInstance().disconnect() }
            // NOT stop(): logging out of TAK does not mean the app is done. See releaseIfIdle.
            runCatching { TakForegroundService.releaseIfIdle(applicationContext) }
            runCatching { clearEnrollment(prefs) }
            // Reset the UI fields so it's clearly a fresh login.
            username.setText("")
            password.setText("")
            // Nothing local to clear: the channels live on the server now. Logging out does
            // not change them, which is correct — they belong to the certificate.
            latestChannels = emptyList()
            runCatching { findViewById<android.widget.LinearLayout>(R.id.takChannelsList).removeAllViews() }
            runCatching { findViewById<TextView>(R.id.takChannelsStatus).text = "" }
            setStatus("Logged out. Enter host, username and password to sign in as another user.",
                androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
        }

        setupVideoControls(prefs)

        // LAST, deliberately: every other setup call above populates or rebinds these fields,
        // and the lock has to be the final word on whether they are editable. Applying it
        // earlier would leave a later setText/rebind free to quietly re-enable a locked field.
        setupConfigLocks()
    }

    /**
     * Video server config. Mirrors the blueprint: NO Start/Stop here — the flight screen's LIVE
     * pill owns starting and stopping the stream; this screen only edits and SAVES the config.
     *
     * Persisting on every change matters more than it looks: the LIVE pill reads prefs, not this
     * screen's live state. The previous version only wrote prefs when its (now removed) "Start
     * Video" button was tapped, so edits made here and never "started" would have been silently
     * lost the moment the buttons went away.
     */
    /**
     * The video controls that change in the field: the quality, every flight, and which server
     * is active, per callout.
     *
     * ⚠ **Everything else moved to [VideoServersActivity]** (operator, 2026-08-30). Twenty
     * set-once controls in one column made the sub-sections easy to mix up, and the worst of
     * it was that every field belonged to whichever server the toggle above had selected —
     * nothing said which one once the pilot had scrolled into them. The servers screen shows
     * both at once and each field writes its own.
     *
     * The summary line is generated so it cannot go stale: it states where the video is pushed
     * and what the CoT will tell the team, which is what a pilot needs to read before a flight.
     */
    private fun setupVideoControls(prefs: android.content.SharedPreferences) {
        migrateVideoSlots(prefs)
        migrateVideoProtocolSplit(prefs)
        val vServerGroup = findViewById<android.widget.RadioGroup>(R.id.videoServerGroup)
        val vServer1 = findViewById<android.widget.RadioButton>(R.id.videoServer1)
        val vServer2 = findViewById<android.widget.RadioButton>(R.id.videoServer2)
        val vProfileGroup = findViewById<android.widget.RadioGroup>(R.id.videoProfileGroup)
        val vSummary = findViewById<TextView>(R.id.videoSummary)

        fun selectedProfile(): String = when (vProfileGroup.checkedRadioButtonId) {
            R.id.videoProfileLow -> "low"
            R.id.videoProfileHigh -> "high"
            else -> "standard"
        }

        // The quality belongs to the SLOT, so both servers keep their own — an external server
        // often wants a different one from an internal server. It is written straight through
        // because the flight screen's LIVE button reads the mirror, not this screen.
        vProfileGroup.setOnCheckedChangeListener { _, _ ->
            val slot = activeVideoSlot(prefs)
            prefs.edit()
                .putString(vKey(slot, "profile"), selectedProfile())
                .putString(KEY_V_PROFILE, selectedProfile())
                .apply()
            AppLog.v(TAG, "video profile -> ${selectedProfile()}")
        }

        /**
         * The active-server choice. Selecting one makes it live, which is acceptable here and
         * nowhere else: the flight screen stops the stream in onStop, so nothing can be
         * streaming while this screen is showing.
         */
        vServerGroup.setOnCheckedChangeListener { _, checkedId ->
            val slot = if (checkedId == R.id.videoServer2) 2 else 1
            if (slot == activeVideoSlot(prefs)) return@setOnCheckedChangeListener
            prefs.edit().putInt(KEY_V_ACTIVE_SLOT, slot).apply()
            mirrorActiveSlot(prefs)
            when (prefs.getString(vKey(slot, "profile"), "standard")) {
                "low" -> vProfileGroup.check(R.id.videoProfileLow)
                "high" -> vProfileGroup.check(R.id.videoProfileHigh)
                else -> vProfileGroup.check(R.id.videoProfileStandard)
            }
            paintVideoSummary(prefs)
            AppLog.i(TAG, "active video server -> slot $slot (${slotName(prefs, slot)})")
        }

        findViewById<android.widget.Button>(R.id.videoConfigureServers).setOnClickListener {
            startActivity(android.content.Intent(this, VideoServersActivity::class.java))
        }

        vServerGroup.check(
            if (activeVideoSlot(prefs) == 2) R.id.videoServer2 else R.id.videoServer1)
        when (prefs.getString(vKey(activeVideoSlot(prefs), "profile"), "standard")) {
            "low" -> vProfileGroup.check(R.id.videoProfileLow)
            "high" -> vProfileGroup.check(R.id.videoProfileHigh)
            else -> vProfileGroup.check(R.id.videoProfileStandard)
        }
        paintVideoSummary(prefs)
        mirrorActiveSlot(prefs)
    }

    /**
     * ⚠ The servers screen can rename a server, change its transport or change where it
     * advertises, and all three show in section 2. Without this the pilot comes back to a
     * summary line describing the configuration as it was BEFORE they edited it — which is
     * worse than no summary, because it reads as authoritative.
     */
    override fun onResume() {
        super.onResume()
        val p = getSharedPreferences("takpilot2_tak", MODE_PRIVATE)
        paintVideoSummary(p)
        mirrorActiveSlot(p)
        setupSdCardSection()
        sdHandler.post(sdTick)
    }

    override fun onPause() {
        super.onPause()
        sdHandler.removeCallbacks(sdTick)
    }


    // ---- 0. Memory Card ---------------------------------------------------------------

    private val sdHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Paints the card state and free space, and drives the Format button.
     *
     * READ-ONLY POLLING. Nothing here asks the aircraft for anything: [AutelProductHolder] already
     * receives the card state and the free space on the camera's own ~2 Hz push, thus this only
     * re-reads fields that are already being kept current. A tick is the simplest way to follow
     * them, and a read tick is not the timer the fly-controller rule forbids.
     *
     * ⚠ NO CAPACITY. The SDK reports free space for the SD card and no total — see the layout
     * comment for the search that established it.
     */
    private val sdTick = object : Runnable {
        override fun run() {
            renderSdCard()
            sdHandler.postDelayed(this, 1000)
        }
    }

    private fun setupSdCardSection() {
        findViewById<android.widget.Button>(R.id.sdCardFormatButton).setOnClickListener {
            confirmFormatSdCard()
        }
        renderSdCard()
    }

    /** Human text for the card state. Every value the SDK can report is named: an unnamed state
     *  would read as a fault when several of them are normal. */
    private fun sdStateText(s: com.autel.common.camera.base.SDCardState?): String = when (s) {
        null -> "Not known"
        com.autel.common.camera.base.SDCardState.CARD_READY -> "Ready"
        com.autel.common.camera.base.SDCardState.NO_CARD -> "No card"
        com.autel.common.camera.base.SDCardState.CARD_FULL -> "Full"
        com.autel.common.camera.base.SDCardState.CARD_ERROR -> "Error"
        com.autel.common.camera.base.SDCardState.CARD_PROTECT -> "Write protected"
        com.autel.common.camera.base.SDCardState.FORMATTING -> "Formatting"
        com.autel.common.camera.base.SDCardState.LOW_SPEED_CARD -> "Slow card"
        com.autel.common.camera.base.SDCardState.LOW_SPEED_CARD_STOP_RECORD -> "Slow card — recording stopped"
        com.autel.common.camera.base.SDCardState.UNKNOWN_FILE_SYSTEM_FAT -> "Unknown file system"
        else -> s.name
    }

    private fun freeText(mb: Long?): String = when {
        mb == null -> "Not known"
        mb >= 1024 -> String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)
        else -> "$mb MB"
    }

    /**
     * Why the Format button is not available, or null when it is.
     *
     * The reasons are separate strings because the pilot needs to know WHICH one applies — a
     * disabled button with no reason is the fault the exterior-lights button already taught us.
     */
    private fun formatBlockedReason(): String? {
        val state = AutelProductHolder.sdCardState
        return when {
            AutelProductHolder.xt706 == null -> "No aircraft"
            AutelTakBridge.airborne -> "Not while the aircraft is flying"
            AutelProductHolder.isRecording -> "Not while recording"
            state == null -> "Waiting for the camera"
            state == com.autel.common.camera.base.SDCardState.NO_CARD -> "No card"
            state == com.autel.common.camera.base.SDCardState.CARD_PROTECT -> "Card is write protected"
            state == com.autel.common.camera.base.SDCardState.FORMATTING -> "Formatting"
            else -> null
        }
    }

    private fun renderSdCard() {
        val stateView = findViewById<TextView>(R.id.sdCardState) ?: return
        val freeView = findViewById<TextView>(R.id.sdCardFree)
        val status = findViewById<TextView>(R.id.sdCardStatus)
        val button = findViewById<android.widget.Button>(R.id.sdCardFormatButton)

        stateView.text = sdStateText(AutelProductHolder.sdCardState)
        freeView.text = freeText(AutelProductHolder.sdFreeMb)

        val blocked = formatBlockedReason()
        button.isEnabled = blocked == null
        button.alpha = if (blocked == null) 1.0f else 0.45f
        // The status line carries the reason, and ALSO the fact that the camera is not writing
        // to this card — formatting an SD the camera is not recording to is legal but almost
        // never what the pilot meant.
        val notTarget = AutelProductHolder.storageTarget ==
            com.autel.common.camera.media.SaveLocation.FLASH_CARD
        val text = when {
            blocked != null -> blocked
            notTarget -> "The camera is recording to internal storage, not this card."
            else -> ""
        }
        status.text = text
        // GONE when it has nothing to say: an empty line still costs its height, and the height
        // is what sections 0 and 1 have none of. See the layout note.
        status.visibility = if (text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    /**
     * ⚠ IRREVERSIBLE, AND ON A PUBLIC-SAFETY AIRFRAME THE FILES MAY BE EVIDENCE.
     *
     * Thus: the aircraft is named, the free space is quoted so the pilot can see whether the card
     * holds anything, and the positive button says what it does rather than "OK". The result
     * reports what the CAMERA did — the same rule the exterior-lights button follows, because
     * this camera has answered success for things it did not do (see startRecordVerified).
     */
    private fun confirmFormatSdCard() {
        val cam = AutelProductHolder.xt706 ?: return
        android.app.AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            .setTitle("Format the memory card?")
            .setMessage(
                "This erases everything on the SD card in the aircraft. " +
                "Photographs and video cannot be recovered.\n\n" +
                "Free space now: ${freeText(AutelProductHolder.sdFreeMb)}")
            .setPositiveButton("Format") { _, _ -> doFormatSdCard(cam) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doFormatSdCard(cam: com.autel.sdk.camera.AutelXT706) {
        val button = findViewById<android.widget.Button>(R.id.sdCardFormatButton)
        val status = findViewById<TextView>(R.id.sdCardStatus)
        button.isEnabled = false
        button.alpha = 0.45f
        showSdStatus(status, "Formatting…")
        AppLog.i(TAG, "format SD card requested")
        runCatching {
            cam.formatSDCard(object : com.autel.common.CallbackWithNoParam {
                override fun onSuccess() {
                    AppLog.i(TAG, "format SD card accepted by the camera")
                    // NOT "done". The camera has taken the request; the CARD STATE is what says
                    // it finished, and it arrives on the 2 Hz push that renderSdCard() reads.
                    runOnUiThread { showSdStatus(status, "The camera accepted the request.") }
                }
                override fun onFailure(error: com.autel.common.error.AutelError?) {
                    AppLog.w(TAG, "format SD card refused: ${error?.description}")
                    runOnUiThread { showSdStatus(status, "The aircraft did not format the card.") }
                }
            })
        }.onFailure {
            AppLog.w(TAG, "format SD card threw: ${it.message}")
            runOnUiThread { showSdStatus(status, "The aircraft did not format the card.") }
        }
    }

    /** Sets the status line and makes it visible. It is GONE by default — see the layout. */
    private fun showSdStatus(v: TextView, text: String) {
        v.text = text
        v.visibility = android.view.View.VISIBLE
    }

    private fun slotName(prefs: android.content.SharedPreferences, slot: Int): String =
        prefs.getString(vKey(slot, "name"), "")?.takeIf { it.isNotBlank() } ?: "Server $slot"

    /**
     * The server buttons and the one-line summary — everything in section 2 that the servers
     * screen can change.
     *
     * Derived text only, so it is safe to call on every resume: it attaches no listener and
     * touches no field the pilot is editing.
     */
    private fun paintVideoSummary(prefs: android.content.SharedPreferences) {
        val summary = findViewById<TextView>(R.id.videoSummary) ?: return
        findViewById<android.widget.RadioButton>(R.id.videoServer1)?.text = slotName(prefs, 1)
        findViewById<android.widget.RadioButton>(R.id.videoServer2)?.text = slotName(prefs, 2)

        val slot = activeVideoSlot(prefs)
        val host = prefs.getString(vKey(slot, "host"), "") ?: ""
        if (host.isEmpty()) {
            summary.text = "No video server set. Touch Configure Video Servers."
            return
        }
        val transport = VideoTransport.fromPref(prefs.getString(vKey(slot, "transport"), null))
        val port = prefs.getInt(
            vKey(slot, if (transport == VideoTransport.SRT) "srt_port" else "rtsp_port"),
            transport.defaultPort)
        val other = if (slot == 1) 2 else 1
        val team = when (prefs.getString(vKey(slot, "advertise"), "self")) {
            "off" -> "the team gets no video address"
            "other" -> "team plays from ${prefs.getString(vKey(other, "host"), "") ?: ""}:" +
                    prefs.getInt(vKey(other, "rtsp_port"), VideoTransport.RTSP.defaultPort)
            else -> "team plays from $host:" +
                    prefs.getInt(vKey(slot, "rtsp_port"), VideoTransport.RTSP.defaultPort)
        }
        summary.text = "${slotName(prefs, slot)} · $host · ${transport.label} $port · $team"
    }

    /**
     * Copies the ACTIVE slot onto the plain `video_*` keys that [AutelVideoStreamer] reads.
     *
     * ⚠ It knows nothing about slots, so an edit that is not mirrored is an edit the stream
     * never sees. [VideoServersActivity.mirrorActiveSlot] does the same after an edit there;
     * this call covers a change of WHICH slot is active, which only this screen can make.
     */
    private fun mirrorActiveSlot(prefs: android.content.SharedPreferences) {
        val slot = activeVideoSlot(prefs)
        val advertise = prefs.getString(vKey(slot, "advertise"), "self")
        val other = if (slot == 1) 2 else 1
        val src = if (advertise == "other") other else slot
        prefs.edit()
            .putString(KEY_V_HOST, prefs.getString(vKey(slot, "host"), "") ?: "")
            .putString(KEY_V_STREAMID, prefs.getString(vKey(slot, "streamid"), "") ?: "")
            .putString(KEY_V_USER, prefs.getString(vKey(slot, "user"), "") ?: "")
            .putString(KEY_V_PASS, prefs.getString(vKey(slot, "pass"), "") ?: "")
            .putInt(KEY_V_RTSP_PORT,
                prefs.getInt(vKey(slot, "rtsp_port"), VideoTransport.RTSP.defaultPort))
            .putInt(KEY_V_SRT_PORT,
                prefs.getInt(vKey(slot, "srt_port"), VideoTransport.SRT.defaultPort))
            .putString(KEY_V_TRANSPORT, VideoTransport.fromPref(
                prefs.getString(vKey(slot, "transport"), null)).prefValue)
            .putString(KEY_V_SRT_PHRASE, prefs.getString(vKey(slot, "srt_phrase"), "") ?: "")
            .putString(KEY_V_CODEC, prefs.getString(vKey(slot, "codec"), null)
                ?: VideoCodec.H264.prefValue)
            .putString(KEY_V_PROFILE, prefs.getString(vKey(slot, "profile"), "standard")
                ?: "standard")
            .putBoolean(KEY_V_ADV_ON, advertise != "off")
            .putString(KEY_V_ADV_HOST, prefs.getString(vKey(src, "host"), "") ?: "")
            .putInt(KEY_V_ADV_PORT,
                prefs.getInt(vKey(src, "rtsp_port"), VideoTransport.RTSP.defaultPort))
            .putString(KEY_V_ADV_USER, prefs.getString(vKey(src, "user"), "") ?: "")
            .putString(KEY_V_ADV_PASS, prefs.getString(vKey(src, "pass"), "") ?: "")
            .apply()
    }

    /**
     * Second slot migration: ONE port became a port PER PROTOCOL.
     *
     * It needs its own flag because [migrateVideoSlots] has already run on every controller in
     * the fleet and will never run again.
     *
     * ⚠ **Which protocol the old port belonged to depends on the slot's transport.** A slot
     * left on RTSP had an RTSP port; a slot already switched to SRT had an SRT ingest port, and
     * its RTSP port was never asked for — the build used the constant 8554 for it, so that is
     * what goes in.
     *
     * The login is NOT split. One pair authorises the publish on either protocol: SRT has no
     * login of its own, and the media server reads the same credentials out of the stream id.
     *
     * ⚠ It also recovers from the SHORT-LIVED SPLIT LAYOUT that development builds 39 and 40
     * wrote (`rtsp_user`/`rtsp_pass`). Those keys are read back into the one login if the
     * original is empty, so a controller that ran that build does not come up with no
     * credentials.
     */
    private fun migrateVideoProtocolSplit(prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean(KEY_V_PROTO_SPLIT_MIGRATED, false)) return
        val e = prefs.edit()
        for (slot in 1..2) {
            val oldPort = prefs.getInt(vKey(slot, "port"), VideoTransport.RTSP.defaultPort)
            val onSrt = VideoTransport.fromPref(prefs.getString(vKey(slot, "transport"), null)) ==
                    VideoTransport.SRT
            if (!prefs.contains(vKey(slot, "rtsp_port"))) {
                e.putInt(vKey(slot, "rtsp_port"),
                    if (onSrt) VideoTransport.RTSP.defaultPort else oldPort)
            }
            if (!prefs.contains(vKey(slot, "srt_port"))) {
                e.putInt(vKey(slot, "srt_port"),
                    if (onSrt) oldPort else VideoTransport.SRT.defaultPort)
            }
            // Recover a login left behind by the split-layout builds.
            if ((prefs.getString(vKey(slot, "user"), "") ?: "").isEmpty()) {
                prefs.getString(vKey(slot, "rtsp_user"), null)?.takeIf { it.isNotEmpty() }
                    ?.let { e.putString(vKey(slot, "user"), it) }
            }
            if ((prefs.getString(vKey(slot, "pass"), "") ?: "").isEmpty()) {
                prefs.getString(vKey(slot, "rtsp_pass"), null)?.takeIf { it.isNotEmpty() }
                    ?.let { e.putString(vKey(slot, "pass"), it) }
            }
            // The passphrase only changed key name.
            if (!prefs.contains(vKey(slot, "srt_phrase"))) {
                e.putString(vKey(slot, "srt_phrase"),
                    prefs.getString(vKey(slot, "srtpass"), "") ?: "")
            }
        }
        e.putBoolean(KEY_V_PROTO_SPLIT_MIGRATED, true).apply()
        AppLog.i(TAG, "video config migrated to a port per protocol")
    }

    /** Preference key for one field of one video server slot. */
    private fun vKey(slot: Int, base: String) = "video_s${slot}_$base"

    /** The server the video goes to now: 1 or 2. */
    private fun activeVideoSlot(prefs: android.content.SharedPreferences): Int =
        if (prefs.getInt(KEY_V_ACTIVE_SLOT, 1) == 2) 2 else 1

    /**
     * Moves a single-server configuration into slot 1, once.
     *
     * Every controller in the fleet is upgrading from a build that had ONE video server, and its
     * settings are on the plain `video_*` keys. Copying them into slot 1 is what stops the
     * upgrade looking like the video configuration was wiped.
     *
     * It runs one time and marks itself done. It must not run again: after the first edit the
     * slot is the truth and the plain keys are only a mirror of it, so copying back would undo
     * whatever the pilot last did on the other server.
     */
    private fun migrateVideoSlots(prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean(KEY_V_SLOTS_MIGRATED, false)) return
        prefs.edit()
            .putString(vKey(1, "name"), "Server 1")
            .putString(vKey(1, "host"), prefs.getString(KEY_V_HOST, "") ?: "")
            .putInt(vKey(1, "port"), prefs.getInt(KEY_V_PORT, 8554))
            .putString(vKey(1, "user"), prefs.getString(KEY_V_USER, "") ?: "")
            .putString(vKey(1, "pass"), prefs.getString(KEY_V_PASS, "") ?: "")
            .putString(vKey(1, "streamid"), prefs.getString(KEY_V_STREAMID, "") ?: "")
            // The old `video_tcp` boolean is NOT read across. It could only say UDP or TCP,
            // and UDP no longer exists; every upgrading controller starts on RTSP, which is
            // what all of them were flying.
            .putString(vKey(1, "transport"), VideoTransport.RTSP.prefValue)
            .putString(vKey(1, "profile"), prefs.getString(KEY_V_PROFILE, "standard") ?: "standard")
            .putString(vKey(1, "codec"), prefs.getString(KEY_V_CODEC, null) ?: VideoCodec.H264.prefValue)
            // Slot 2 starts empty and inherits only the defaults. A half-filled second server
            // would be worse than an obviously blank one.
            .putString(vKey(2, "name"), "Server 2")
            .putInt(vKey(2, "port"), 8554)
            .putString(vKey(2, "transport"), VideoTransport.RTSP.prefValue)
            .putString(vKey(2, "profile"), "standard")
            .putString(vKey(2, "codec"), VideoCodec.H264.prefValue)
            .putInt(KEY_V_ACTIVE_SLOT, 1)
            .putBoolean(KEY_V_SLOTS_MIGRATED, true)
            .apply()
        AppLog.i(TAG, "video config migrated to slot 1")
    }

    private fun enrollAndConnect(
        host: String, enrollPort: Int, cotPort: Int,
        username: String, password: String, droneCallsign: String,
    ) {
        AppLog.v(TAG, "enrollAndConnect: host=$host enrollPort=$enrollPort cotPort=$cotPort user=$username")
        setStatus("Enrolling with $host:$enrollPort …", androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))

        // Stable operator uid persisted across sessions.
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        var uid = prefs.getString(KEY_UID, "") ?: ""
        if (uid.isEmpty()) {
            uid = "TAKPilot2-" + UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString(KEY_UID, uid).apply()
        }
        // The aircraft gets its own distinct uid so it shows as a separate air track.
        val droneUid = "$uid-DRONE"

        Thread {
            TakCertEnroller.enroll(host, enrollPort, username, password, uid, filesDir,
                object : TakCertEnroller.EnrollmentCallback {
                    override fun onSuccess(trustStorePath: String, clientCertPath: String) {
                        // Persist certs so we never have to re-enroll — future connects reuse these.
                        prefs.edit()
                            .putString(KEY_TRUSTSTORE, trustStorePath)
                            .putString(KEY_CLIENTCERT, clientCertPath)
                            .putBoolean(KEY_LOGGED_OUT, false)   // new enrollment → allow auto-reconnect again
                            .apply()
                        runOnUiThread { setStatus("Enrolled. Connecting …", androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary)) }
                        connectWithCerts(uid, username, droneUid, droneCallsign,
                            host, cotPort, trustStorePath, clientCertPath)
                    }

                    override fun onError(error: String) {
                        AppLog.w(TAG, "enrollment failed: $error")
                        runOnUiThread { setStatus("Error: $error", androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_danger)) }
                    }
                })
        }.start()
    }

    /** Connect using already-enrolled cert files (no re-enrollment / re-entry of password). */
    private fun connectWithCerts(
        uid: String, username: String, droneUid: String, droneCallsign: String,
        host: String, cotPort: Int, trustStorePath: String, clientCertPath: String,
    ) {
        val certPw = "atakatak"
        // 2nd arg is the CALLSIGN, not the username. Passing `username` here made the aircraft
        // appear on the TAK server (and in the flight HUD, which reads TakManager.callsign)
        // under the operator's login name instead of the callsign set in Pre-Flight Setup —
        // so a team saw "0009anc" where they expected the aircraft's name. The username still
        // identifies the account for enrollment; it is not what the team should see.
        TakManager.getInstance().connect(
            uid, droneCallsign, "Cyan", "Team Member",
            host, cotPort, trustStorePath, certPw, clientCertPath, certPw,
        )
        runOnUiThread {
            setStatus("Connected. Sending the aircraft position to TAK as \"$droneCallsign\".",
                androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_go))
            TakBridgeHolder.start(droneUid, droneCallsign)
            TakForegroundService.start(applicationContext, droneCallsign)
        }
    }

    /** Reconnect using saved certs + saved server settings, no UI entry needed. */
    private fun reconnectFromSaved(prefs: android.content.SharedPreferences, droneCallsign: String) {
        val host = prefs.getString(KEY_HOST, "") ?: ""
        val username = prefs.getString(KEY_USERNAME, "") ?: ""
        val cotPort = prefs.getInt(KEY_COT_PORT, 8089)
        val ts = prefs.getString(KEY_TRUSTSTORE, "") ?: ""
        val cc = prefs.getString(KEY_CLIENTCERT, "") ?: ""
        var uid = prefs.getString(KEY_UID, "") ?: ""
        if (uid.isEmpty()) {
            uid = "TAKPilot2-" + UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString(KEY_UID, uid).apply()
        }
        if (host.isEmpty() || ts.isEmpty() || cc.isEmpty()) {
            setStatus("Saved enrollment incomplete — enroll again.", androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_danger))
            return
        }
        Thread { connectWithCerts(uid, username, "$uid-DRONE", droneCallsign, host, cotPort, ts, cc) }.start()
    }

    /** Delete the saved enrollment (cert files + prefs) so a different user can sign in clean. */
    private fun clearEnrollment(prefs: android.content.SharedPreferences) {
        val ts = prefs.getString(KEY_TRUSTSTORE, "") ?: ""
        val cc = prefs.getString(KEY_CLIENTCERT, "") ?: ""
        if (ts.isNotEmpty()) { val f = java.io.File(ts); val ok = runCatching { f.delete() }.getOrDefault(false); AppLog.i(TAG, "delete truststore $ts -> $ok (exists=${f.exists()})") }
        if (cc.isNotEmpty()) { val f = java.io.File(cc); val ok = runCatching { f.delete() }.getOrDefault(false); AppLog.i(TAG, "delete clientcert $cc -> $ok (exists=${f.exists()})") }
        // Also nuke any cert files by their well-known names, in case the prefs paths drifted.
        listOf("tak_clientcert.p12", "tak_truststore.p12").forEach {
            val f = java.io.File(filesDir, it); if (f.exists()) { val ok = runCatching { f.delete() }.getOrDefault(false); AppLog.i(TAG, "delete $it -> $ok") }
        }
        prefs.edit()
            .remove(KEY_TRUSTSTORE)
            .remove(KEY_CLIENTCERT)
            .remove(KEY_UID)
            .remove(KEY_USERNAME)
            // Controllers that ran v1.6.0 or older still hold a stored channel list. Nothing
            // reads it — the channels live on the server — so clearing it stops dead state
            // outliving a logout.
            .remove(KEY_CHANNELS)
            .putBoolean(KEY_LOGGED_OUT, true)   // block auto-reconnect until a fresh enroll
            .apply()
        AppLog.i(TAG, "enrollment cleared")
    }

    /** True if we have saved cert files on disk from a previous enrollment. */
    private fun hasSavedCerts(prefs: android.content.SharedPreferences): Boolean {
        val ts = prefs.getString(KEY_TRUSTSTORE, "") ?: ""
        val cc = prefs.getString(KEY_CLIENTCERT, "") ?: ""
        return ts.isNotEmpty() && cc.isNotEmpty() &&
            java.io.File(ts).exists() && java.io.File(cc).exists()
    }

    private fun setStatus(text: String, color: Int) {
        status.text = text
        status.setTextColor(color)
    }

    // ---- My Channels ----


    /**
     * The channels, as the SERVER holds them.
     *
     * This is not a local preference any more. The check box shows the server's `active` state,
     * and a change PUTs the new set to the server — the method a real TAK client uses. Nothing
     * is stored on the controller, thus nothing here can disagree with the server.
     *
     * EVERY CHANNEL CAN BE SWITCHED ON AND OFF, including a receive-only one. The check box is
     * the `active` flag, and `active` governs RECEIVE as well as send. A first version disabled
     * the box on a receive-only channel, which confused "cannot publish to it" with "cannot use
     * it" — and left a channel that could be switched off from TAK Portal with no way to switch
     * it back on from the controller (operator, 2026-08-16). ADS-B is exactly the channel a
     * pilot wants to turn off and on: it is noisy, and switching it off stops the traffic.
     *
     * The direction is shown as text instead. It tells the pilot what the channel will and will
     * not carry, and it takes nothing away from them.
     */
    private fun renderChannels(channels: List<TakMissionClient.Channel>) {
        val list = findViewById<android.widget.LinearLayout>(R.id.takChannelsList)
        list.removeAllViews()
        latestChannels = channels
        if (channels.isEmpty()) {
            // A server with channels turned off returns none. Say so, and offer no control:
            // writing to such a server is reported to cause real trouble on it.
            findViewById<TextView>(R.id.takChannelsStatus).text =
                "This server has no channels."
            return
        }
        for (ch in channels) {
            val row = android.widget.CheckBox(this).apply {
                // Two-way is the normal case and gets no label — a note on every row is
                // noise, and the exception is what a pilot needs to see (operator,
                // 2026-08-16).
                text = when {
                    ch.canSend && ch.canReceive -> ch.name
                    ch.canReceive -> "${ch.name} - Rx Only"
                    ch.canSend -> "${ch.name} - Tx Only"
                    else -> "${ch.name} - no direction"
                }
                // Secondary text is the only hint that the row is locked. The tick stays
                // full contrast, because the tick is the information.
                setTextColor(androidx.core.content.ContextCompat.getColor(
                    applicationContext,
                    if (takConfigLocked()) R.color.tp_text_secondary else R.color.tp_text_primary))
                // Enabled for every channel. See the note above: the box is `active`, and a
                // receive-only channel is still one a pilot may want on or off.
                // ⚠ THE LOCK STOPS A CHANGE, NOT THE READING. The rows still follow the
                // server while locked — a pilot must always be able to SEE the scope of this
                // aircraft. The lock exists to stop an accidental change, not to hide the truth
                // (operator, 2026-08-16).
                //
                // ⚠ LOCKED IS NOT DISABLED. isEnabled=false greys the tick as well as the row,
                // and a pilot then cannot tell a checked box from an unchecked one — which
                // defeats the paragraph above. The row stays at full contrast and stops taking
                // touches instead. The check box keeps its own tint for the same reason.
                isChecked = ch.active
                isClickable = !takConfigLocked()
                isFocusable = !takConfigLocked()
                buttonTintList = android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(
                        applicationContext, R.color.tp_accent))
                setOnCheckedChangeListener { _, checked ->
                    if (updatingChannels) return@setOnCheckedChangeListener
                    ch.active = checked
                    pushActiveChannels()
                }
            }
            list.addView(row)
        }
    }

    /**
     * Sends the COMPLETE set of active channels to the server.
     *
     * ⚠ activebits is ABSOLUTE. Anything not in this list is switched off, thus the whole set
     * goes every time and never a change. ⚠ It applies to the CERTIFICATE — every controller
     * enrolled as this user gets this set.
     */
    private fun pushActiveChannels() {
        // ⚠ NEVER WRITE TO A SERVER THAT HAS NO CHANNELS. Cory Foy (TAK Aware) reported
        // 2026-08-16 that a channel change sent to a server which does not have channels
        // enabled can do real damage server side — days of debugging on one deployment. No row
        // exists when the list is empty, thus no toggle can fire this, but the guard is here
        // so that stays true if a caller is ever added.
        if (latestChannels.isEmpty()) {
            AppLog.w(TAG, "channel write refused — this server returned no channels")
            return
        }
        val bits = latestChannels.filter { it.active && it.bitpos >= 0 }.map { it.bitpos }
        val status = findViewById<TextView>(R.id.takChannelsStatus)
        status.text = "Sending ${bits.size} active channel(s) to the server…"
        TakMissionManager.setActiveChannels(bits) { ok ->
            status.text = if (ok) "Server accepted ${bits.size} active channel(s)."
                          else "The server refused the change. See the log."
            status.setTextColor(androidx.core.content.ContextCompat.getColor(applicationContext,
                if (ok) R.color.tp_state_go else R.color.tp_state_danger))
            // Read it back. The server is the truth, not what was just tapped.
            refreshChannels()
        }
    }

    /** Re-reads the channels from the server and repaints. The server can be changed from TAK
     *  Portal by an administrator, thus the screen must follow it and not a local copy. */
    private fun refreshChannels() {
        TakMissionManager.listChannels { chans ->
            updatingChannels = true
            renderChannels(chans)
            updatingChannels = false
        }
    }

    /** The server told us the channels changed. Read them again — the event carries a notice,
     *  not a list. */
    private val groupChangeListener = TakManager.GroupChangeListener {
        AppLog.i(TAG, "channels changed on the server — re-reading")
        refreshChannels()
        findViewById<TextView>(R.id.takChannelsStatus)?.text =
            "The server changed the channels. The list is up to date."
    }

    /** Reads the channels again when TAK connects. Nothing else here needs contact events. */
    private val connectionListener = object : TakManager.TakUserListener {
        override fun onTakUserUpdated(user: com.taklite.client.tak.TakUser) {}
        override fun onTakUserRemoved(uid: String) {}
        override fun onTakUserDeleted(uid: String) {}
        override fun onTakConnectionChanged(connected: Boolean) {
            if (connected) {
                AppLog.i(TAG, "TAK connected — reading the channels")
                refreshChannels()
            }
        }
    }

    /**
     * Locks the active-server toggle without hiding which server is active.
     *
     * LOCKED IS NOT DISABLED — the same rule the channel rows follow. The buttons keep full
     * contrast and their accent tint, and stop taking touches. A pilot must always be able to
     * SEE where the video is going; the lock exists to stop an accidental swap, not to hide the
     * destination.
     */
    private fun lockVideoServerToggle(locked: Boolean) {
        for (id in listOf(R.id.videoServer1, R.id.videoServer2)) {
            findViewById<android.widget.RadioButton>(id)?.apply {
                isClickable = !locked
                isFocusable = !locked
            }
        }
    }

    /** The TAK configuration lock. The channel rows read it each time they are painted, thus a
     *  lock or unlock takes effect without leaving the screen. */
    private fun takConfigLocked(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_TAK_LOCKED, false)

    private var latestChannels: List<TakMissionClient.Channel> = emptyList()
    /** True while the check boxes are being set from server data, so the listener does not
     *  treat a repaint as a pilot's tap and PUT it straight back. */
    private var updatingChannels = false

    // ---- 1. Aircraft Settings ----

    /**
     * Flight-safety limits, persisted here and pushed to the aircraft by
     * [FlightLimitsController.applyAtConnect] on the next AIRCRAFT connect. Blank leaves the
     * aircraft's own current setting alone.
     *
     * These used to be pushed from [AutelTakBridge], latched on the TAK session — which meant no
     * TAK server, no limits, and no re-apply after an aircraft reconnect. Flight controls are not
     * the TAK bridge's business; they now run from [AutelProductHolder] with the other
     * at-connect settings.
     *
     * ⚠ A value the aircraft rejects is NOT applied and it keeps its previous setting, so the
     * range check below is not cosmetic — see the `limitRangeStatus` wiring.
     */
    // ---- Applying settings to the aircraft ---------------------------------------------------
    //
    // TWO PATHS, BOTH DELIBERATE, NEITHER TIED TO TYPING:
    //   1. On AIRCRAFT CONNECT, automatically (FlightLimitsController.applyAtConnect, driven from
    //      AutelProductHolder).
    //   2. On demand, via "Apply to Aircraft" — resends everything and then READS BACK what the
    //      aircraft actually holds.
    //
    // Editing a field only saves it locally. An earlier revision pushed on a 2s debounce after
    // typing stopped; the operator called that correctly (2026-08-02) and it was removed. Typing
    // is not intent: a pause mid-edit would push a half-considered value, and a mid-flight change
    // to RTH or max altitude should be an explicit act. It also deletes a whole class of hazard —
    // these fields fire per keystroke, so "200" would have been three writes (2ft, 20ft, 200ft)
    // at the same fly-controller channel whose saturation put an aircraft into a wall.
    //
    // The read-back is the point. Today proved every layer can lie independently: a write can
    // report OK, the getter can report a value the aircraft does not fly, and an out-of-range
    // value is silently kept at the old setting. "Sent" is not "set".
    private val applyHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingVerify: Runnable? = null
    private var pendingTick: Runnable? = null

    private fun cancelPendingSettingPushes() {
        pendingVerify?.let { applyHandler.removeCallbacks(it) }
        pendingVerify = null
        pendingTick?.let { applyHandler.removeCallbacks(it) }
        pendingTick = null
    }

    /**
     * Resends every aircraft-bound Pre-Flight setting, then reports what the aircraft holds.
     *
     * The push-then-wait-11s-then-verify shape (see the class doc above) has no natural
     * "in progress" signal of its own — the writes are fire-and-forget, so there is nothing to
     * await except the clock. Without visible progress a pilot watching a static line of text
     * for 11 seconds has no way to tell that from a frozen screen, and the natural response is
     * to tap the button again or navigate away mid-push. The button is disabled and a determinate
     * progress bar fills across exactly [VERIFY_DELAY_MS] — a known, bounded wait — with a
     * live countdown in the status text, so the pilot can see it counting down to the verify
     * rather than just running to it. Operator request, 2026-08-03.
     */
    private fun applyAllToAircraft(button: Button, progress: android.widget.ProgressBar, status: TextView) {
        if (AutelProductHolder.evo2 == null) {
            status.text = "The aircraft is not connected. The settings are saved. " +
                "Connect the aircraft, then press the button again."
            status.setTextColor(androidx.core.content.ContextCompat.getColor(
                this, R.color.tp_state_caution))
            return
        }
        cancelPendingSettingPushes()
        button.isEnabled = false
        progress.visibility = android.view.View.VISIBLE
        progress.progress = 0
        status.setTextColor(0xFF909090.toInt())

        // One collector for THIS press. The setters below record their refusals into it and the
        // verify reads it 11s later. The writes are fire-and-forget, thus this is the only way a
        // refused write — and two of them have no getter at all — can reach the pilot.
        val cycle = FlightLimitsController.beginApply()

        FlightLimitsController.pushLimitsNow(this)
        FlightLimitsController.pushBatteryAndRfNow(this)

        // Verify AFTER the writes have had time to land. The wait used to be 11s because the
        // signal-loss write took a 10s timeout to fail; that write is gone (see
        // FlightLimitsController's note), and the remaining writes acknowledge in well under
        // a second, so the pilot waits about two.
        val startNs = android.os.SystemClock.elapsedRealtime()
        val tick = object : Runnable {
            override fun run() {
                val elapsedMs = android.os.SystemClock.elapsedRealtime() - startNs
                val pct = (elapsedMs * 100 / VERIFY_DELAY_MS).toInt().coerceIn(0, 100)
                progress.progress = pct
                // Ceiling division so the countdown starts at exactly VERIFY_DELAY_MS/1000
                // seconds and never overshoots by rounding up an extra whole second.
                val remainingMs = (VERIFY_DELAY_MS - elapsedMs).coerceAtLeast(0)
                val remainingSec = (remainingMs + 999) / 1000L
                status.text = "Sending the settings to the aircraft… confirming in ${remainingSec}s"
                if (elapsedMs < VERIFY_DELAY_MS) {
                    pendingTick = this
                    applyHandler.postDelayed(this, APPLY_TICK_MS)
                } else {
                    pendingTick = null
                }
            }
        }
        pendingTick = tick
        applyHandler.post(tick)

        val verify = Runnable {
            pendingVerify = null
            // STOP THE COUNTDOWN FIRST. Both runnables come due at VERIFY_DELAY_MS, and the
            // countdown's last frame is posted from the tick before it, so it lands a few
            // milliseconds AFTER this one and used to paint "confirming in 0s" straight over
            // the finished report. The screen then sat on a frozen countdown while the button
            // was live again and the text carried the report's colour — the apply had
            // succeeded and looked hung (bench, 2026-08-13).
            pendingTick?.let { applyHandler.removeCallbacks(it) }
            pendingTick = null
            // Says what is happening during the read-back, which is not instant: the getters
            // answer in their own time and have their own 12s watchdog.
            status.text = "Confirming the settings with the aircraft…"
            FlightLimitsController.readBack(this, cycle) { report ->
                runOnUiThread {
                    button.isEnabled = true
                    progress.visibility = android.view.View.GONE
                    status.text = report.text
                    // Three states, three colours. Amber is NOT a softer red: it says the
                    // aircraft did not answer, which is a different fact from a refusal and must
                    // not be shown as one.
                    status.setTextColor(androidx.core.content.ContextCompat.getColor(
                        this, when (report.state) {
                            FlightLimitsController.ReportState.CONFIRMED -> R.color.tp_state_go
                            FlightLimitsController.ReportState.UNKNOWN -> R.color.tp_state_unknown
                            FlightLimitsController.ReportState.PROBLEM -> R.color.tp_state_danger
                        }))
                }
            }
        }
        pendingVerify = verify
        applyHandler.postDelayed(verify, VERIFY_DELAY_MS)
    }

    private fun setupDroneSettingsSection() {
        val maxAlt = findViewById<EditText>(R.id.limitMaxAltitude)
        val maxRadius = findViewById<EditText>(R.id.limitMaxRadius)
        val rthAlt = findViewById<EditText>(R.id.limitRthAltitude)

        maxAlt.setText(FlightLimitsController.savedMaxAltitudeFt(this))
        maxRadius.setText(FlightLimitsController.savedMaxRadiusFt(this))
        rthAlt.setText(FlightLimitsController.savedRthAltitudeFt(this))

        // Auto-save on edit, no Save button — matching the blueprint. These are local prefs
        // only; FlightLimitsController pushes them to the aircraft on its next connect, which
        // is what the section's subtitle tells the pilot. (An earlier version of this screen
        // had a Save button that ALSO pushed immediately when connected; both were divergences
        // from the blueprint and are gone.)
        val save = {
            FlightLimitsController.save(
                this, maxAlt.text.toString(), maxRadius.text.toString(), rthAlt.text.toString(),
            )
        }
        // Live range check against the aircraft's own accepted limits. Without this the aircraft
        // rejects an out-of-range value, keeps its old one, and Pre-Flight goes on displaying the
        // number the pilot typed — which on 2026-08-02 meant 50 ft on screen and a 151 ft RTH in
        // the air. Only possible when an aircraft is connected; says so plainly when it is not.
        val rangeStatus = findViewById<android.widget.TextView>(R.id.limitRangeStatus)
        val refreshRanges = {
            val rth = FlightLimitsController.returnHeightRange()
            val alt = FlightLimitsController.maxHeightRange()
            val rad = FlightLimitsController.maxRangeRange()
            if (rth == null && alt == null && rad == null) {
                rangeStatus.setText("Connect the aircraft to see the limits it accepts.")
                rangeStatus.setTextColor(0xFF909090.toInt())
            } else {
                val problems = mutableListOf<String>()
                fun check(label: String, text: String, r: FlightLimitsController.RangeM?) {
                    r ?: return
                    val ft = text.trim().toIntOrNull() ?: return
                    val m = Math.round(ft / 3.28084).toInt()
                    if (!r.containsM(m)) {
                        problems += "$label $ft ft. It accepts ${r.fromFt} to ${r.toFt} ft"
                    }
                }
                check("Max altitude", maxAlt.text.toString(), alt)
                check("Max distance", maxRadius.text.toString(), rad)
                check("RTH altitude", rthAlt.text.toString(), rth)
                if (problems.isEmpty()) {
                    rangeStatus.setText("The aircraft accepts: " +
                        (alt?.let { "max altitude ${it.fromFt} to ${it.toFt} ft, " } ?: "") +
                        (rad?.let { "max distance ${it.fromFt} to ${it.toFt} ft, " } ?: "") +
                        (rth?.let { "RTH altitude ${it.fromFt} to ${it.toFt} ft" } ?: ""))
                    rangeStatus.setTextColor(0xFF909090.toInt())
                } else {
                    rangeStatus.setText("⚠ The aircraft will refuse " + problems.joinToString("; ") +
                        ". Correct the value. If you do not, the aircraft keeps the setting it has now.")
                    rangeStatus.setTextColor(0xFFFF6B6B.toInt())
                }
            }
        }
        refreshRanges()

        val watcher = object : android.text.TextWatcher {
            // Saves locally and re-checks the range. Does NOT push — typing is not intent.
            // "Apply to Aircraft" is what sends it. See applyAllToAircraft.
            override fun afterTextChanged(s: android.text.Editable?) {
                save()
                refreshRanges()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        listOf(maxAlt, maxRadius, rthAlt).forEach { it.addTextChangedListener(watcher) }

        setupBatteryLevels()

        val applyStatus = findViewById<TextView>(R.id.limitApplyStatus)
        val applyProgress = findViewById<android.widget.ProgressBar>(R.id.limitApplyProgress)
        val applyButton = findViewById<Button>(R.id.limitApplyButton)
        applyButton.setOnClickListener {
            applyAllToAircraft(applyButton, applyProgress, applyStatus)
        }


    }

    /**
     * Battery levels — the two percentages at which the AIRCRAFT acts on its own.
     *
     * Saved locally on edit and pushed by "Apply to Aircraft", exactly like the numeric limits
     * above. Until now these two had prefs, a push path and defaults of 15/10, but no UI at all:
     * the only way to change them was Autel Explorer, and a value the app pushes on every
     * connect but never shows is a setting the pilot cannot reason about.
     *
     * The validation is the point of this function. [FlightLimitsController.applyBatteryThresholds]
     * already REFUSES to push low <= critical — the aircraft would begin its return and force a
     * landing in the same moment — but refusing at Apply time, in a log line, is too late and
     * invisible. This says so while the pilot is typing, and says what will happen if they leave
     * it: the aircraft keeps what it already has.
     */
    private fun setupBatteryLevels() {
        val low = findViewById<EditText>(R.id.limitLowBattery)
        val crit = findViewById<EditText>(R.id.limitCriticalBattery)
        val status = findViewById<TextView>(R.id.limitBatteryStatus)

        low.setText(FlightLimitsController.savedLowBatteryPct(this))
        crit.setText(FlightLimitsController.savedCriticalBatteryPct(this))

        val refresh = {
            val l = low.text.toString().trim().toIntOrNull()
            val c = crit.text.toString().trim().toIntOrNull()
            when {
                l == null || c == null -> {
                    status.setText("Empty keeps the aircraft's current level.")
                    status.setTextColor(0xFF909090.toInt())
                }
                l <= c -> {
                    status.setText("⚠ Battery Warning ($l%) must be above Battery Critical " +
                        "($c%) — the aircraft would act on both at once. Not sent until corrected.")
                    status.setTextColor(0xFFFF6B6B.toInt())
                }
                // A valid pair says nothing: the two fields already show it. This line exists
                // only to report a problem.
                else -> status.setText("")
            }
        }
        refresh()

        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                FlightLimitsController.saveBatteryLevels(
                    this@TakConnectActivity, low.text.toString(), crit.text.toString(),
                )
                refresh()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        listOf(low, crit).forEach { it.addTextChangedListener(watcher) }
    }


    // ---- Configuration locks ----

    /**
     * What each lock covers.
     *
     * The TAK list includes **Log Out**, which is not a field: it clears the saved enrollment,
     * so it is destructive and belongs behind the lock (operator, 2026-07-31). Enroll & Connect
     * is deliberately NOT here — reconnecting is safe, and needing to reconnect is exactly when
     * a pilot must not be fighting a lock.
     */
    private val takLockedFields = listOf(
        R.id.takHost, R.id.takEnrollPort, R.id.takCotPort,
        R.id.takUsername, R.id.takPassword, R.id.takCallsign,
        R.id.takDisconnectButton,
    )
    /** Codec and transport are part of WHAT the stream is — the wrong codec breaks playback
     *  outright (CloudTAK cannot play H.265), so they lock with the server fields (operator,
     *  2026-08-06). The quality profile stays live; see [setupConfigLocks]. The two codec
     *  RadioButtons are listed individually because disabling a RadioGroup does not disable its
     *  children. */
    /**
     * ⚠ EMPTY ON PURPOSE. The video configuration fields moved to [VideoServersActivity]
     * (operator, 2026-08-30), so there is nothing on THIS screen for the generic lock to
     * disable. The lock still does its two jobs: `afterChange` stops the active-server toggle
     * taking touches, and the servers screen reads `video_config_locked` when it opens.
     */
    private val videoLockedFields = emptyList<Int>()

    private val batteryLockedFields = listOf(
        R.id.limitLowBattery, R.id.limitCriticalBattery,
    )

    /**
     * "Lock configuration" for the TAK server, video server and battery-level sections: a
     * working setup should not be one stray tap away from being edited on a tailgate.
     *
     * **Locks the FIELDS only.** Enroll & Connect, Log Out, the video QUALITY choice and
     * Apply to Aircraft stay live: needing to reconnect, or to drop to Low on a marginal
     * link, is exactly when a pilot must not be fighting a lock. The lock guards what the
     * configuration IS, not what you do with it. Codec and transport moved INSIDE the
     * video lock (operator, 2026-08-06): they are part of what the stream is — a codec the
     * server can't play is a dead stream, not a tuning choice.
     *
     * Unlocking asks for a password (operator, 2026-08-06); locking does not. The asymmetry is
     * deliberate — locking is the safe direction and gating it would just train people to
     * dismiss dialogs. The password is a shared, fixed one — see [UNLOCK_PASSWORD] for what it
     * is and is not protecting against.
     */
    private fun setupConfigLocks() {
        setupOneLock(
            R.id.takLockConfig, KEY_TAK_LOCKED, takLockedFields,
            "Unlock TAK server settings?",
            "The lock prevents an accidental change to a server that works. " +
                "A wrong value stops the aircraft sending data to your team.",
            // The channel rows are built in code, thus applyLock cannot reach them by id. They
            // are painted again instead, and each row reads the lock as it is built.
            afterChange = { renderChannels(latestChannels) },
        )
        setupOneLock(
            R.id.videoLockConfig, KEY_VIDEO_LOCKED, videoLockedFields,
            "Unlock video server settings?",
            "These fields are locked so a working stream configuration is not changed by " +
                "accident. Editing them can stop your team seeing the video.",
            // The toggle is a radio button pair built in the layout, but it must not be dimmed
            // — see the note on videoLockedFields.
            afterChange = { lockVideoServerToggle(it) },
        )
        setupOneLock(
            R.id.limitBatteryLock, KEY_BATTERY_LOCKED, batteryLockedFields,
            "Unlock battery levels?",
            "These are the levels at which the aircraft returns and lands on its own. " +
                "A wrong value can force a landing away from the pilot.",
        )
    }

    private fun setupOneLock(
        checkBoxId: Int,
        prefKey: String,
        fieldIds: List<Int>,
        confirmTitle: String,
        confirmBody: String,
        /** Run after the lock state settles, for controls that applyLock cannot reach by id. */
        afterChange: (Boolean) -> Unit = {},
    ) {
        val box = findViewById<android.widget.CheckBox>(checkBoxId)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        // Default LOCKED once a config exists, unlocked on a fresh install — a first-run pilot
        // must not have to discover a lock before they can type anything.
        val locked = prefs.getBoolean(prefKey, false)
        box.isChecked = locked
        applyLock(fieldIds, locked)
        afterChange(locked)

        box.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                prefs.edit().putBoolean(prefKey, true).apply()
                applyLock(fieldIds, true)
                afterChange(true)
                AppLog.v(TAG, "config locked: $prefKey")
                return@setOnCheckedChangeListener
            }
            // Unlocking: ask for the password, and put the box BACK unless it is right. Using
            // setOnCheckedChangeListener means our own revert would re-enter this listener,
            // so the listener is detached around it (inside revert()).
            //
            // A wrong password and Cancel take the same path on purpose: the only way OUT of
            // this dialog with the fields editable is the correct password.
            val revert = {
                box.setOnCheckedChangeListener(null)
                box.isChecked = true
                setupConfigLocks()
            }
            // Built in code rather than a layout: one field, three call sites, and a layout
            // file would imply this dialog can grow. It must not — it is a speed bump.
            // Styled to match the section fields (takFieldStyle), plus a bordered background —
            // see bg_dialog_field for why the flat fill was not enough here. A programmatic
            // EditText takes the PLATFORM's colours, not the app theme's, so every colour is
            // set explicitly.
            val pw = android.widget.EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                hint = "Password"
                textSize = 15f
                setTextColor(androidx.core.content.ContextCompat.getColor(
                    this@TakConnectActivity, R.color.tp_text_primary))
                setHintTextColor(androidx.core.content.ContextCompat.getColor(
                    this@TakConnectActivity, R.color.tp_text_hint))
                setBackgroundResource(R.drawable.bg_dialog_field)
                val pad = (12 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            val wrap = android.widget.FrameLayout(this).apply {
                val padH = (16 * resources.displayMetrics.density).toInt()
                val padV = (8 * resources.displayMetrics.density).toInt()
                setPadding(padH, padV, padH, padV)
                addView(pw)
            }
            android.app.AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
                .setTitle(confirmTitle)
                .setMessage(confirmBody)
                .setView(wrap)
                .setPositiveButton("Unlock") { _, _ ->
                    if (pw.text.toString() == UNLOCK_PASSWORD) {
                        prefs.edit().putBoolean(prefKey, false).apply()
                        applyLock(fieldIds, false)
                        afterChange(false)
                        // The entered text is never logged, right or wrong — same rule as
                        // every other credential in this app (security review 2026-08-03).
                        AppLog.i(TAG, "config UNLOCKED: $prefKey")
                    } else {
                        android.widget.Toast.makeText(this, "Wrong password",
                            android.widget.Toast.LENGTH_SHORT).show()
                        AppLog.i(TAG, "unlock refused (wrong password): $prefKey")
                        revert()
                    }
                }
                .setNegativeButton("Cancel") { _, _ -> revert() }
                .setOnCancelListener { revert() }
                .show()
        }
    }

    /**
     * Greys out and disables a set of views. `isEnabled = false` also makes them unfocusable, so
     * the keyboard cannot be raised on a locked field — read-only in the way a pilot means it —
     * and a disabled Button stops responding to taps.
     *
     * Typed as View, not EditText: the TAK lock covers the Log Out button as well as fields.
     */
    private fun applyLock(fieldIds: List<Int>, locked: Boolean) {
        for (id in fieldIds) {
            findViewById<android.view.View>(id)?.apply {
                isEnabled = !locked
                alpha = if (locked) 0.45f else 1.0f
            }
        }
    }

    // ---- 4. Elevation Data (DTED) ----

    /** DTED region management — import a region .zip via the system document picker (any file;
     *  DTED extensions are not a registered MIME type so we do not filter), list imported regions
     *  (one row each — never individual tiles, see [DtedStore]), allow deleting a whole region.
     *
     *  ACTION_OPEN_DOCUMENT deliberately: it's the Storage Access Framework, which needs no
     *  storage permission and is the supported path on the Smart Controller V3's Android 11
     *  (scoped storage) — a raw filesystem picker would not be. */
    private fun setupDtedSection() {
        findViewById<Button>(R.id.dtedUploadButton).setOnClickListener {
            AppLog.v(TAG, "tap: Import Region")
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, REQUEST_CODE_DTED_PICK)
        }
        findViewById<Button>(R.id.dtedCleanButton).setOnClickListener {
            AppLog.v(TAG, "tap: Clean Unused Tiles")
            val removed = DtedStore.cleanUnreferencedTiles(this)
            Toast.makeText(this, "Removed $removed unreferenced tile file(s)", Toast.LENGTH_SHORT).show()
        }
        renderDtedRegions()
    }

    private val dtedDateFormat = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)

    /** One compact row per imported region — name/date/file-count/size plus a delete button,
     *  never a per-tile listing (a full region is hundreds of tiles). */
    private fun renderDtedRegions() {
        val container = findViewById<LinearLayout>(R.id.dtedFileList)
        val dtedStatus = findViewById<TextView>(R.id.dtedStatus)
        container.orientation = LinearLayout.VERTICAL
        container.removeAllViews()
        val regions = DtedStore.listRegions(this)
        dtedStatus.text = if (regions.isEmpty()) "No terrain areas are imported."
            else "${regions.size} terrain area(s) imported."
        for (region in regions) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundColor(androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_surface_dialog))
                setPadding(12, 10, 12, 10)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 6 }
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = region.name
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            info.addView(TextView(this).apply {
                val mb = region.totalBytes / 1024.0 / 1024.0
                val sizeStr = if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
                text = "Imported ${dtedDateFormat.format(java.util.Date(region.importedAtMs))} · " +
                    "${region.fileCount} file(s) · $sizeStr"
                setTextColor(androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_tertiary))
                textSize = 12f
            })
            row.addView(info)
            row.addView(TextView(this).apply {
                text = "Delete"
                setTextColor(androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_btn_danger_dialog))
                textSize = 13f
                setPadding(20, 8, 4, 8)
                setOnClickListener {
                    AppLog.v(TAG, "tap: delete DTED region ${region.name} (#${region.id})")
                    DtedStore.deleteRegion(this@TakConnectActivity, region)
                    renderDtedRegions()
                }
            })
            container.addView(row)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE_DTED_PICK || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val name = queryDisplayName(uri) ?: "Region-${System.currentTimeMillis()}"
        val dtedStatus = findViewById<TextView>(R.id.dtedStatus)
        val result = DtedStore.import(this, uri, name)
        dtedStatus.text = when {
            result.error != null && result.importedCount == 0 ->
                "The app cannot import $name. ${result.error}"
            result.error != null ->
                "Imported ${result.importedCount} tile(s) from $name. ${result.error}"
            else -> "Imported ${result.importedCount} tile(s) from $name."
        }
        if (result.importedCount == 0) Toast.makeText(this, dtedStatus.text, Toast.LENGTH_SHORT).show()
        renderDtedRegions()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
    }

    // ---- 5. FAA Airspace Ceilings (UASFM) ----

    /** Download UASFM ceilings for an area. Deliberately a manual, explicit action on wifi
     *  rather than anything automatic in flight: the flight screen must never depend on having
     *  a network, and a silent background fetch would be exactly the wrong thing to discover
     *  had failed while airborne. */
    private fun setupUasfmSection() {
        val latField = findViewById<EditText>(R.id.uasfmLat)
        val lonField = findViewById<EditText>(R.id.uasfmLon)
        val radiusField = findViewById<EditText>(R.id.uasfmRadius)
        val uasfmStatus = findViewById<TextView>(R.id.uasfmStatus)
        val downloadBtn = findViewById<Button>(R.id.uasfmDownloadButton)
        val checkBtn = findViewById<Button>(R.id.uasfmCheckButton)

        radiusField.setText("50")
        renderUasfmStatus()

        /** Reads the three fields, or null (with a toast) if they do not make sense. */
        fun readBbox(): UasfmStore.Bbox? {
            val lat = latField.text.toString().trim().toDoubleOrNull()
            val lon = lonField.text.toString().trim().toDoubleOrNull()
            val radius = radiusField.text.toString().trim().toDoubleOrNull()
            if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                Toast.makeText(this, "Enter a valid centre latitude and longitude", Toast.LENGTH_SHORT).show()
                return null
            }
            if (radius == null || radius <= 0 || radius > 500) {
                Toast.makeText(this, "Enter a radius between 1 and 500 miles", Toast.LENGTH_SHORT).show()
                return null
            }
            return UasfmStore.bboxAround(lat, lon, radius)
        }

        findViewById<Button>(R.id.uasfmUseLocationButton).setOnClickListener {
            AppLog.v(TAG, "tap: UASFM Use My Location")
            // Ask for location up front rather than letting the read fail: the manifest declares
            // ACCESS_FINE/COARSE_LOCATION but nothing in this app had ever REQUESTED them at
            // runtime, and on the Smart Controller V3's Android 11 that means the read throws
            // and this button would silently report "no GPS fix" — blaming the hardware for a
            // permission problem. Requesting here (rather than at app start) keeps the prompt
            // attached to the one action that needs it.
            useMyLocationFor(R.id.uasfmLat, R.id.uasfmLon)
        }

        checkBtn.setOnClickListener {
            val bbox = readBbox() ?: return@setOnClickListener
            AppLog.v(TAG, "tap: UASFM Check Size")
            checkBtn.isEnabled = false
            uasfmStatus.text = "Checking…"
            UasfmStore.countAsync(bbox) { result ->
                checkBtn.isEnabled = true
                uasfmStatus.text = when {
                    result.error != null -> "The app cannot reach the FAA service. ${result.error}"
                    result.count == 0 ->
                        "There are no FAA cells in this area. The Part 107 limit of 400 ft applies."
                    else -> "${result.count} cell(s) in this area. Press Download to keep them."
                }
            }
        }

        downloadBtn.setOnClickListener {
            val bbox = readBbox() ?: return@setOnClickListener
            val label = "%.3f, %.3f  ·  %s mi".format(
                latField.text.toString().trim().toDoubleOrNull() ?: 0.0,
                lonField.text.toString().trim().toDoubleOrNull() ?: 0.0,
                radiusField.text.toString().trim(),
            )
            AppLog.i(TAG, "UASFM download starting for $label")
            downloadBtn.isEnabled = false
            uasfmStatus.text = "Downloading…"
            UasfmStore.downloadAsync(
                context = this,
                bbox = bbox,
                areaLabel = label,
                onProgress = { count -> uasfmStatus.text = "Downloading… $count cell(s)" },
                onDone = { result ->
                    downloadBtn.isEnabled = true
                    if (result.error != null) {
                        AppLog.w(TAG, "UASFM download failed: ${result.error}")
                        uasfmStatus.text = result.error
                    } else {
                        // Surface off-grid skips rather than burying them: a non-zero count means
                        // the FAA moved off the 1/120 degree grid this design assumes, and the
                        // pilot would otherwise have coverage holes with no hint why.
                        val warn = if (result.offGridSkipped > 0)
                            "\n⚠ ${result.offGridSkipped} cell(s) skipped — unexpected grid, report this."
                        else ""
                        renderUasfmStatus(extra = warn)
                        Toast.makeText(this, "FAA ceilings downloaded", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }

        findViewById<Button>(R.id.uasfmClearButton).setOnClickListener {
            AppLog.v(TAG, "tap: UASFM Clear Data")
            UasfmStore.clear(this)
            renderUasfmStatus()
            Toast.makeText(this, "FAA ceiling data cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderUasfmStatus(extra: String = "") {
        val uasfmStatus = findViewById<TextView>(R.id.uasfmStatus)
        val meta = UasfmStore.meta(this)
        uasfmStatus.text = if (meta == null) {
            "No FAA data is downloaded. The flight screen shows the Part 107 limit of 400 ft."
        } else {
            "${meta.cellCount} cell(s) for ${meta.areaLabel}\n" +
                "Downloaded ${dtedDateFormat.format(java.util.Date(meta.downloadedAtMs))}  ·  " +
                "FAA effective ${meta.effectiveLabel}$extra"
        }
    }

    private fun hasLocationPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Fills the UASFM centre fields from the controller's own fix, or explains why it cannot —
     *  distinguishing "no fix yet" from "permission denied", which are different problems with
     *  different fixes and used to look identical to the pilot. */
    /** Which section's "Use My Location" is waiting on the permission prompt. Needed because
     *  two sections share one request code, and filling the wrong pair of fields would look
     *  like the button did nothing. */
    private var pendingLocationTarget: Pair<Int, Int> = R.id.uasfmLat to R.id.uasfmLon

    /**
     * Fills a centre lat/lon from the best position available.
     *
     * **Aircraft first, controller second.** The aircraft is the better source whenever it is
     * connected: its GNSS is running by definition, it is the thing whose airspace you are
     * downloading, and on this hardware the controller's receiver is frequently not running at
     * all (see below).
     *
     * The controller's GPS is a genuine trap. `getLastKnownLocation()` only reads a CACHE, and
     * nothing populates that cache unless something has called `requestLocationUpdates()`. On a
     * Smart Controller where no app has done so, `dumpsys location` shows the gps provider with
     * `mStarted=false`, `request=OFF`, `last location=null` — permanently, outdoors included.
     * The old message here told the pilot to "go outside for a moment", which was confidently
     * wrong advice for a receiver that was never switched on (found with the aircraft connected
     * outdoors, 2026-08-02).
     */
    private fun fillCentreFromLocation(
        latId: Int = pendingLocationTarget.first,
        lonId: Int = pendingLocationTarget.second,
    ) {
        val hud = TakBridgeHolder.hud()
        val loc: Pair<Double, Double>? = when {
            hud != null && hud.hasFix -> {
                AppLog.i(TAG, "centre from AIRCRAFT position")
                hud.lat to hud.lon
            }
            else -> lastKnownPhoneLocation()?.also {
                AppLog.i(TAG, "centre from CONTROLLER position")
            }
        }
        if (loc == null) {
            Toast.makeText(
                this,
                "No position is available. Connect the aircraft, or type the centre.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        findViewById<EditText>(latId).setText("%.4f".format(loc.first))
        findViewById<EditText>(lonId).setText("%.4f".format(loc.second))
    }

    /** Shared by both "Use My Location" buttons: request the permission if needed, remembering
     *  which fields to fill when the answer comes back, otherwise fill immediately. */
    private fun useMyLocationFor(latId: Int, lonId: Int) {
        pendingLocationTarget = latId to lonId
        if (!hasLocationPermission()) {
            AppLog.i(TAG, "location permission not granted — requesting")
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                REQUEST_CODE_LOCATION,
            )
            return
        }
        fillCentreFromLocation(latId, lonId)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_LOCATION) return
        if (hasLocationPermission()) {
            AppLog.i(TAG, "location permission granted")
            fillCentreFromLocation()
        } else {
            AppLog.i(TAG, "location permission denied — centre must be entered manually")
            Toast.makeText(
                this,
                "Location permission denied. Enter the centre latitude/longitude manually.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** Most recent GPS/network fix from the controller, or null. The Smart Controller V3 has
     *  its own GPS, so unlike a phone-tethered RC this is normally available outdoors. */
    private fun lastKnownPhoneLocation(): Pair<Double, Double>? {
        val lm = getSystemService(android.content.Context.LOCATION_SERVICE)
            as android.location.LocationManager
        val loc = runCatching {
            listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER,
            ).mapNotNull { p -> if (lm.isProviderEnabled(p)) lm.getLastKnownLocation(p) else null }
                .maxByOrNull { it.time }
        }.getOrNull() ?: return null
        return loc.latitude to loc.longitude
    }

    companion object {
        private const val TAG = "TakConnectActivity"

        /** How long "Apply to aircraft" waits before reading back. Must clear the slowest write.
         *  Was 11s, set by the signal-loss write's 10s timeout; that write is gone (2026-08-13,
         *  it set nothing) and the remaining ones acknowledge in well under a second — the
         *  bench log has the whole batch answering within ~150ms. Two seconds is that with
         *  room to spare, and the read-back keeps its own 12s watchdog underneath. */
        private const val VERIFY_DELAY_MS = 2000L
        /** Progress-bar/countdown refresh rate while waiting for [VERIFY_DELAY_MS] to elapse. */
        private const val APPLY_TICK_MS = 200L
        private const val REQUEST_CODE_DTED_PICK = 4301
        private const val REQUEST_CODE_LOCATION = 4302
        /** Shared with FlightActivity, which reads the TAK lock to gate the channel dialog. */
        internal const val PREFS = "takpilot2_tak"

        /** Per-section configuration locks — see setupConfigLocks(). */
        internal const val KEY_TAK_LOCKED = "tak_config_locked"
        private const val KEY_VIDEO_LOCKED = "video_config_locked"
        private const val KEY_BATTERY_LOCKED = "battery_levels_locked"

        /**
         * The one password that unlocks any locked section.
         *
         * ⚠ **This is a speed bump, not security, and it must not be mistaken for it.** The
         * string ships in the APK in plain text — anyone with the file and `strings`, or with
         * adb, reads it in seconds. That is accepted (operator, 2026-08-06): the threat model
         * is an average user tapping into settings they should not adjust, not an adversary.
         * Do not "harden" this with hashing or per-device secrets — a stronger lock on the
         * front door of an unlocked house, and it would cost the field-recoverability that a
         * shared fixed password exists to provide.
         *
         * The entered attempt is never logged, right or wrong.
         */
        internal const val UNLOCK_PASSWORD = "takpilot"

        private const val KEY_HOST = "host"
        private const val KEY_ENROLL_PORT = "enroll_port"
        private const val KEY_COT_PORT = "cot_port"
        private const val KEY_USERNAME = "username"
        private const val KEY_CALLSIGN = "callsign"
        private const val KEY_CAMERA_POINT = "camera_point"
        private const val KEY_CHANNELS = "channels"          // CSV of selected channel names
        private const val KEY_LOGGED_OUT = "logged_out"      // true = user logged out; block auto-reconnect
        private const val KEY_UID = "uid"
        private const val KEY_TRUSTSTORE = "truststore_path"
        private const val KEY_CLIENTCERT = "clientcert_path"
        private const val KEY_V_HOST = "video_host"
        private const val KEY_V_PORT = "video_port"
        private const val KEY_V_USER = "video_user"
        /** Named constant, not a literal. The save site used a bare "video_pass" while the
         *  restore site did not exist at all — a constant makes the pair impossible to miss. */
        private const val KEY_V_PASS = "video_pass"
        private const val KEY_V_STREAMID = "video_streamid"
        /** ⚠ REPLACES `video_tcp`, which was a boolean and is now abandoned. A controller
         *  that had the old TCP box cleared moves to RTSP over TCP on upgrade: UDP is gone
         *  and SRT is the answer to the problem it was cleared for. See [VideoTransport]. */
        private const val KEY_V_TRANSPORT = "video_transport"
        // The port and the login belong to a PROTOCOL. These replace the single video_port /
        // video_user / video_pass trio, which are now read only by the slot migration.
        // ⚠ Must match the literals read in AutelVideoStreamer.startFromPrefs.
        private const val KEY_V_RTSP_PORT = "video_rtsp_port"
        private const val KEY_V_SRT_PORT = "video_srt_port"
        /** The SRT passphrase — the stream ENCRYPTION key, not the publish login. Must match
         *  the literal read in AutelVideoStreamer.startFromPrefs. */
        private const val KEY_V_SRT_PHRASE = "video_srt_passphrase"
        private const val KEY_V_PROFILE = "video_profile"
        /** Must match the literal read in AutelVideoStreamer.startFromPrefs. */
        private const val KEY_V_CODEC = "video_codec"

        /** Which of the two video servers is live: 1 or 2. The per-slot fields are keyed by
         *  [vKey] — "video_s1_host" and so on. */
        private const val KEY_V_ACTIVE_SLOT = "video_active_slot"

        /** Guard for [migrateVideoProtocolSplit]. Separate from the slot-migration flag, which
         *  has already run everywhere and cannot carry a second meaning. */
        private const val KEY_V_PROTO_SPLIT_MIGRATED = "video_port_per_protocol_migrated"

        /** Which server the CoT points the team at: [ADV_SELF], [ADV_OTHER] or [ADV_OFF]. */
        private const val ADV_SELF = "self"
        private const val ADV_OTHER = "other"
        private const val ADV_OFF = "off"
        // ⚠ Must match the literals read in AutelVideoStreamer.startFromPrefs.
        private const val KEY_V_ADV_ON = "video_adv_on"
        private const val KEY_V_ADV_HOST = "video_adv_host"
        private const val KEY_V_ADV_PORT = "video_adv_port"
        private const val KEY_V_ADV_USER = "video_adv_user"
        private const val KEY_V_ADV_PASS = "video_adv_pass"
        /** Set once the single-server configuration has been copied into slot 1. See
         *  migrateVideoSlots for why this must never run twice. */
        private const val KEY_V_SLOTS_MIGRATED = "video_slots_migrated"
    }

    /**
     * Section 7 — obstacle avoidance. Live state, explicit toggles, nothing persisted.
     *
     * Unlike every other section on this screen, NOTHING here is saved or replayed on connect.
     * The switches show what the aircraft currently reports and change it only when touched.
     * See the layout comment for why a stale saved value would be the wrong thing to push at a
     * safety system.
     *
     * Each toggle RE-READS the aircraft afterwards instead of trusting its own success callback.
     * This SDK has already been caught returning success for things it did not do (the camera's
     * setAspectRatio), and a switch that shows the state the pilot asked for rather than the
     * state the aircraft is in would be worse than no switch.
     */
    private fun wireAvoidanceSection() {
        val status = findViewById<android.widget.TextView>(R.id.avoidStatus)
        val system = findViewById<android.widget.CheckBox>(R.id.avoidSystem)
        val rth = findViewById<android.widget.CheckBox>(R.id.avoidRth)
        val landing = findViewById<android.widget.CheckBox>(R.id.avoidLanding)
        val boxes = listOf(system, rth, landing)

        fun render() {
            val connected = AutelProductHolder.isConnected
            val known = AutelAvoidance.systemEnabled != null
            // Simplified Technical English, as with the field guide: short sentences, active
            // voice, one idea each. A pilot reads this on the ground in a hurry.
            status.text = when {
                !connected -> "The aircraft is not connected."
                !known -> "Wait. The aircraft did not send the state yet."
                AutelAvoidance.systemEnabled == true -> "Obstacle avoidance is ON."
                else -> "Obstacle avoidance is OFF."
            }
            status.setTextColor(androidx.core.content.ContextCompat.getColor(this,
                when {
                    // Not connected, or the aircraft has not answered — the UNKNOWN amber,
                    // which is a different fact from OFF and from a refusal. §6.1.
                    !connected || !known -> R.color.tp_state_unknown
                    AutelAvoidance.systemEnabled == true -> R.color.tp_state_go
                    else -> R.color.tp_state_danger
                }))
            // Set the boxes WITHOUT firing their listeners, or rendering the aircraft's state
            // would look like a pilot toggle and be pushed straight back at it.
            boxes.forEach { it.setOnCheckedChangeListener(null) }
            system.isChecked = AutelAvoidance.systemEnabled == true
            rth.isChecked = AutelAvoidance.avoidDuringRth == true
            landing.isChecked = AutelAvoidance.landingProtect == true
            boxes.forEach { it.isEnabled = connected && known }
            attachListeners()
        }

        fun toggle(which: com.autel.common.flycontroller.visual.VisualSettingSwitchblade,
                   enabled: Boolean) {
            boxes.forEach { it.isEnabled = false }        // no double-taps mid-flight
            // Record the INTENT as well as applying it. This is what gets enforced on every
            // future connect — without it the pilot's choice would last only until Autel's app
            // changed it back.
            AutelAvoidance.saveIntent(this,
                if (which == com.autel.common.flycontroller.visual.VisualSettingSwitchblade.AVOIDANCE_SYSTEM) enabled else system.isChecked,
                if (which == com.autel.common.flycontroller.visual.VisualSettingSwitchblade.RETURN_TO_HOME_AVOIDANCE) enabled else rth.isChecked,
                if (which == com.autel.common.flycontroller.visual.VisualSettingSwitchblade.LANDING_PROTECT) enabled else landing.isChecked)
            AutelAvoidance.setSwitch(which, enabled) { ok ->
                runOnUiThread {
                    if (!ok) android.widget.Toast.makeText(this@TakConnectActivity,
                        "The aircraft did not accept the change.", android.widget.Toast.LENGTH_SHORT).show()
                    render()
                }
            }
        }

        attachListeners = {
            system.setOnCheckedChangeListener { _, v ->
                toggle(com.autel.common.flycontroller.visual.VisualSettingSwitchblade.AVOIDANCE_SYSTEM, v)
            }
            rth.setOnCheckedChangeListener { _, v ->
                toggle(com.autel.common.flycontroller.visual.VisualSettingSwitchblade.RETURN_TO_HOME_AVOIDANCE, v)
            }
            landing.setOnCheckedChangeListener { _, v ->
                toggle(com.autel.common.flycontroller.visual.VisualSettingSwitchblade.LANDING_PROTECT, v)
            }
        }

        render()
        // The state arrives asynchronously after the aircraft syncs, so re-render for a while
        // rather than leaving "waiting…" on screen until the pilot navigates away and back.
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        var ticks = 0
        val poll = object : Runnable {
            override fun run() {
                render()
                if (++ticks < 30 && !isFinishing) h.postDelayed(this, 1000)
            }
        }
        h.postDelayed(poll, 1000)
    }

    /**
     * Control response (Normal / Precision) and the read-only stick mode.
     *
     * Same discipline as the avoidance block above: live values, disabled until the controller
     * reports, changed only on an explicit tap, nothing persisted and nothing pushed at connect.
     *
     * The stick mode is DISPLAY ONLY. The SDK reports USA/CHINA/JAPAN and the mapping to
     * "Mode 1/2/3" is convention rather than anything confirmed on this aircraft — and a wrong
     * mode number in front of a pilot is the sort of error that swaps throttle and pitch.
     */
    private fun wireControlRatesSection() {
        val group = findViewById<android.widget.RadioGroup>(R.id.ratesGroup)
        val normal = findViewById<android.widget.RadioButton>(R.id.ratesNormal)
        val precision = findViewById<android.widget.RadioButton>(R.id.ratesPrecision)
        val stick = findViewById<android.widget.TextView>(R.id.stickModeStatus)

        val stickGroup = findViewById<android.widget.RadioGroup>(R.id.stickModeGroup)
        val stickIds = mapOf("1" to R.id.stickMode1, "2" to R.id.stickMode2, "3" to R.id.stickMode3)

        fun render() {
            val connected = AutelProductHolder.isConnected
            // SEED FROM THE AIRCRAFT the first time. The app must not invent a stick mode — that
            // would swap throttle and pitch for a pilot who never opened this screen — but once
            // seeded it is enforced on every connect so Autel's app cannot change it behind them.
            if (AutelControlRates.savedStickModeId(this).isEmpty() && AutelControlRates.stickMode != null) {
                AutelControlRates.saveStickModeId(this, AutelControlRates.idFor(AutelControlRates.stickMode))
            }
            val chosen = AutelControlRates.savedStickModeId(this)
            stickGroup.setOnCheckedChangeListener(null)
            stickIds[chosen]?.let { stickGroup.check(it) }
            stickIds.values.forEach { findViewById<android.widget.RadioButton>(it).isEnabled = connected }
            stick.text = if (chosen.isEmpty()) "Waiting for the controller."
                else "The controller reports ${AutelControlRates.stickModeLabel()}."
            stickGroup.setOnCheckedChangeListener { _, id ->
                val pick = stickIds.entries.firstOrNull { it.value == id }?.key ?: return@setOnCheckedChangeListener
                AutelControlRates.saveStickModeId(this, pick)
                AutelControlRates.pushStickMode(this) { ok ->
                    runOnUiThread {
                        if (!ok) android.widget.Toast.makeText(this,
                            "The controller did not accept the stick mode.",
                            android.widget.Toast.LENGTH_SHORT).show()
                        render()
                    }
                }
            }
            // Set the radio WITHOUT its listener, or drawing the controller's state would look
            // like a pilot tap and be sent straight back at it.
            group.setOnCheckedChangeListener(null)
            // THE RADIO SHOWS THE PILOT'S CHOICE, NOT THE CONTROLLER'S CURRENT STATE. TAKPilot
            // asserts this setting on every connect, so the saved choice IS what the airframe
            // will be flying — reflecting a read-back here would let a value Autel Explorer left
            // behind appear to be the selection, moments before the app overwrote it anyway.
            group.check(if (AutelControlRates.savedPrecision(this)) R.id.ratesPrecision
                        else R.id.ratesNormal)
            normal.isEnabled = connected
            precision.isEnabled = connected
            group.setOnCheckedChangeListener { _, id ->
                normal.isEnabled = false; precision.isEnabled = false
                val wantPrecision = id == R.id.ratesPrecision
                // Record the INTENT, exactly as the stick mode and the avoidance switches do.
                // Without this, saveSelection() was never called from anywhere: the preference
                // stayed at its default of false, and applyAtConnect then pushed NORMAL to the
                // controller on every connect. A pilot who chose Precision did not merely lose
                // the choice, the app actively undid it each launch (operator, 2026-08-02).
                AutelControlRates.saveSelection(this, wantPrecision)
                AutelControlRates.setPrecision(this, wantPrecision) { ok ->
                    runOnUiThread {
                        if (!ok) android.widget.Toast.makeText(this,
                            "The controller did not accept the change.",
                            android.widget.Toast.LENGTH_SHORT).show()
                        render()
                    }
                }
            }
        }

        render()
        AutelControlRates.refresh(this) { runOnUiThread { render() } }
        // Values arrive after the controller syncs, so keep re-reading for a short while rather
        // than leaving "wait…" on screen until the pilot navigates away and back.
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        var ticks = 0
        val poll = object : Runnable {
            override fun run() {
                if (AutelControlRates.precisionActive == null && AutelProductHolder.isConnected) {
                    AutelControlRates.refresh(this@TakConnectActivity) { runOnUiThread { render() } }
                }
                if (++ticks < 20 && !isFinishing) h.postDelayed(this, 1500)
            }
        }
        h.postDelayed(poll, 1500)
    }

    private var attachListeners: () -> Unit = {}
}

// NOTE: TakBridgeHolder lives in AutelTakBridge.kt in this port (it wraps AutelTakBridge).
