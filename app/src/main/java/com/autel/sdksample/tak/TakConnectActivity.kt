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
 * drone -> CoT -> TAK end-to-end; the full QR enrollment wizard comes later.
 */
class TakConnectActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onDestroy() {
        // A debounced write must not outlive the screen that scheduled it — leaving the pilot's
        // half-typed value to land on the aircraft after they navigated away.
        cancelPendingSettingPushes()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tak_connect)
        AppLog.v(TAG, "onCreate")

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        setupDroneSettingsSection()
        setupMapDisplaySection()
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

        // My Channels: restore saved selection → apply to TakManager, wire the Pull button.
        selectedChannels = loadChannels(prefs).toMutableSet()
        TakManager.getInstance().setChannels(selectedChannels.toList())
        renderChannels(selectedChannels.toList())   // show saved selection immediately
        findViewById<Button>(R.id.takPullChannels).setOnClickListener { pullChannels(prefs) }

        // Reflect live state on open, and silently reconnect with saved certs if the
        // socket isn't up — so the user never has to re-enter credentials / re-enroll.
        when {
            TakManager.getInstance().isConnected ->
                setStatus("Connected. Drone PLI streaming.", Color.parseColor("#4CAF50"))
            prefs.getBoolean(KEY_LOGGED_OUT, false) ->
                setStatus("Logged out. Enter host, username and password to sign in.", Color.parseColor("#B0B0B0"))
            hasSavedCerts(prefs) -> {
                setStatus("Reconnecting with saved enrollment …", Color.parseColor("#B0B0B0"))
                reconnectFromSaved(prefs, callsign.text.toString().trim().ifEmpty { "TAKPilot2-EVO2" })
            }
            else -> setStatus("Not connected.", Color.parseColor("#B0B0B0"))
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
                setStatus("Already connected.", Color.parseColor("#4CAF50"))
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
                    Color.parseColor("#F44336"))
                return@setOnClickListener
            }
            enrollAndConnect(h, ep, cp, u, p, cs)
        }

        findViewById<Button>(R.id.takDisconnectButton).setOnClickListener {
            AppLog.v(TAG, "Logout tapped")
            // Full LOG OUT: stop everything AND clear the saved enrollment so the app won't silently
            // reconnect the old user, and a different user can enroll cleanly. Each teardown step is
            // guarded — a throw from the closing socket must NOT abort the logout (that crash was
            // why logout never stuck). clearEnrollment + the logged-out flag always run.
            runCatching { VideoStreamerHolder.stop() }
            runCatching { TakBridgeHolder.stop() }
            runCatching { TakManager.getInstance().disconnect() }
            runCatching { TakManager.getInstance().setChannels(emptyList()) }
            // NOT stop(): logging out of TAK does not mean the app is done. See releaseIfIdle.
            runCatching { TakForegroundService.releaseIfIdle(applicationContext) }
            runCatching { clearEnrollment(prefs) }
            // Reset the UI fields so it's clearly a fresh login.
            username.setText("")
            password.setText("")
            selectedChannels.clear()
            runCatching { findViewById<android.widget.LinearLayout>(R.id.takChannelsList).removeAllViews() }
            runCatching { findViewById<TextView>(R.id.takChannelsStatus).text = "" }
            setStatus("Logged out. Enter host, username and password to sign in as another user.",
                Color.parseColor("#B0B0B0"))
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
    private fun setupVideoControls(prefs: android.content.SharedPreferences) {
        val vHost = findViewById<EditText>(R.id.videoHost)
        val vPort = findViewById<EditText>(R.id.videoPort)
        val vUser = findViewById<EditText>(R.id.videoUser)
        val vPass = findViewById<EditText>(R.id.videoPassword)
        val vStreamId = findViewById<EditText>(R.id.videoStreamId)
        val vTcp = findViewById<android.widget.CheckBox>(R.id.videoTcp)
        val vProfileGroup = findViewById<android.widget.RadioGroup>(R.id.videoProfileGroup)
        val vFullUrl = findViewById<TextView>(R.id.videoFullUrl)

        vHost.setText(prefs.getString(KEY_V_HOST, ""))
        vPort.setText(prefs.getInt(KEY_V_PORT, 8554).toString())
        vUser.setText(prefs.getString(KEY_V_USER, ""))
        vStreamId.setText(prefs.getString(KEY_V_STREAMID, ""))
        vTcp.isChecked = prefs.getBoolean(KEY_V_TCP, true)
        when (prefs.getString(KEY_V_PROFILE, "standard")) {
            "low" -> vProfileGroup.check(R.id.videoProfileLow)
            "high" -> vProfileGroup.check(R.id.videoProfileHigh)
            else -> vProfileGroup.check(R.id.videoProfileStandard)
        }

        fun selectedProfile(): String = when (vProfileGroup.checkedRadioButtonId) {
            R.id.videoProfileLow -> "low"
            R.id.videoProfileHigh -> "high"
            else -> "standard"
        }

        fun buildConfig(): AutelVideoStreamer.VideoConfig = AutelVideoStreamer.VideoConfig(
            host = vHost.text.toString().trim(),
            port = vPort.text.toString().trim().toIntOrNull() ?: 8554,
            username = vUser.text.toString().trim(),
            password = vPass.text.toString(),
            streamId = vStreamId.text.toString().trim(),
            tcp = vTcp.isChecked,
            profile = selectedProfile(),
        )

        val refreshAndSave = {
            val cfg = buildConfig()
            vFullUrl.text = if (cfg.host.isEmpty() || cfg.streamId.isEmpty())
                "rtsp://…  (enter host + identifier)" else cfg.urlSafe()
            prefs.edit()
                .putString(KEY_V_HOST, cfg.host)
                .putInt(KEY_V_PORT, cfg.port)
                .putString(KEY_V_USER, cfg.username)
                .putString("video_pass", cfg.password)
                .putString(KEY_V_STREAMID, cfg.streamId)
                .putBoolean(KEY_V_TCP, cfg.tcp)
                .putString(KEY_V_PROFILE, cfg.profile)
                .apply()
        }
        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = refreshAndSave()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        listOf(vHost, vPort, vUser, vPass, vStreamId).forEach { it.addTextChangedListener(watcher) }
        vTcp.setOnCheckedChangeListener { _, _ -> refreshAndSave() }
        // Persist the profile the moment it changes, so the flight-screen LIVE button (which
        // reads prefs, not this screen's live state) always uses the pilot's current choice.
        vProfileGroup.setOnCheckedChangeListener { _, _ ->
            AppLog.v(TAG, "video profile -> ${selectedProfile()}")
            prefs.edit().putString(KEY_V_PROFILE, selectedProfile()).apply()
        }
        refreshAndSave()
    }

    private fun enrollAndConnect(
        host: String, enrollPort: Int, cotPort: Int,
        username: String, password: String, droneCallsign: String,
    ) {
        AppLog.v(TAG, "enrollAndConnect: host=$host enrollPort=$enrollPort cotPort=$cotPort user=$username")
        setStatus("Enrolling with $host:$enrollPort …", Color.parseColor("#B0B0B0"))

        // Stable operator uid persisted across sessions.
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        var uid = prefs.getString(KEY_UID, "") ?: ""
        if (uid.isEmpty()) {
            uid = "TAKPilot2-" + UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString(KEY_UID, uid).apply()
        }
        // The drone gets its own distinct uid so it shows as a separate air track.
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
                        runOnUiThread { setStatus("Enrolled. Connecting …", Color.parseColor("#B0B0B0")) }
                        connectWithCerts(uid, username, droneUid, droneCallsign,
                            host, cotPort, trustStorePath, clientCertPath)
                    }

                    override fun onError(error: String) {
                        AppLog.w(TAG, "enrollment failed: $error")
                        runOnUiThread { setStatus("Error: $error", Color.parseColor("#F44336")) }
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
            setStatus("Connected. Streaming drone PLI as \"$droneCallsign\".",
                Color.parseColor("#4CAF50"))
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
            setStatus("Saved enrollment incomplete — enroll again.", Color.parseColor("#F44336"))
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
    private var selectedChannels: MutableSet<String> = mutableSetOf()

    private fun loadChannels(prefs: android.content.SharedPreferences): List<String> =
        (prefs.getString(KEY_CHANNELS, "") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private fun saveChannels(prefs: android.content.SharedPreferences) {
        prefs.edit().putString(KEY_CHANNELS, selectedChannels.joinToString(",")).apply()
        TakManager.getInstance().setChannels(selectedChannels.toList())
    }

    /** Pull the channels the logged-in user belongs to from the TAK server (needs a connection). */
    private fun pullChannels(prefs: android.content.SharedPreferences) {
        AppLog.v(TAG, "Pull channels tapped")
        val chanStatus = findViewById<TextView>(R.id.takChannelsStatus)
        if (!TakManager.getInstance().isConnected) {
            chanStatus.text = "Connect to TAK first, then pull channels."
            chanStatus.setTextColor(Color.parseColor("#F44336"))
            return
        }
        chanStatus.text = "Pulling channels…"
        chanStatus.setTextColor(Color.parseColor("#B0B0B0"))
        TakMissionManager.listMyChannels { chans ->
            if (chans.isEmpty()) {
                chanStatus.text = "No channels found for this login."
            } else {
                chanStatus.text = "${chans.size} channel(s). Check the ones to publish to."
            }
            // Keep any previously-selected channels even if not returned this pull.
            val all = (chans + selectedChannels).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
            renderChannels(all)
        }
    }

    /** Render a checkbox per channel; toggling saves the selection + applies it to routing. */
    private fun renderChannels(channels: List<String>) {
        val list = findViewById<android.widget.LinearLayout>(R.id.takChannelsList)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        list.removeAllViews()
        for (name in channels) {
            val cb = android.widget.CheckBox(this).apply {
                text = name
                setTextColor(Color.WHITE)
                isChecked = selectedChannels.contains(name)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedChannels.add(name) else selectedChannels.remove(name)
                    AppLog.v(TAG, "channel '$name' ${if (checked) "selected" else "deselected"}")
                    saveChannels(prefs)
                }
            }
            list.addView(cb)
        }
    }

    // ---- 1. Drone Settings ----

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
    //   2. On demand, via "Apply to Drone" — resends everything and then READS BACK what the
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

    private fun cancelPendingSettingPushes() {
        pendingVerify?.let { applyHandler.removeCallbacks(it) }
        pendingVerify = null
    }

    /** Resends every aircraft-bound Pre-Flight setting, then reports what the aircraft holds. */
    private fun applyAllToAircraft(status: TextView) {
        if (AutelProductHolder.evo2 == null) {
            status.text = "The drone is not connected. The settings are saved. " +
                "Connect the drone, then press the button again."
            status.setTextColor(0xFFFFC107.toInt())
            return
        }
        status.text = "Sending the settings to the drone…"
        status.setTextColor(0xFF909090.toInt())

        FlightLimitsController.pushLimitsNow(this)
        FlightLimitsController.pushBatteryAndRfNow(this)
        FlightLimitsController.pushFailsafeNow(this)

        // Verify AFTER the writes have had time to land. The failsafe write in particular takes a
        // 10s timeout to fail on this firmware, so a verify any sooner would read mid-flight.
        cancelPendingSettingPushes()
        val verify = Runnable {
            pendingVerify = null
            FlightLimitsController.readBack(this) { report ->
                runOnUiThread {
                    status.text = report.text
                    status.setTextColor(if (report.allMatched) 0xFF4CAF50.toInt() else 0xFFFF6B6B.toInt())
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
                rangeStatus.setText("Connect the drone to see the limits it accepts.")
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
                    rangeStatus.setText("The drone accepts: " +
                        (alt?.let { "max altitude ${it.fromFt} to ${it.toFt} ft, " } ?: "") +
                        (rad?.let { "max distance ${it.fromFt} to ${it.toFt} ft, " } ?: "") +
                        (rth?.let { "RTH altitude ${it.fromFt} to ${it.toFt} ft" } ?: ""))
                    rangeStatus.setTextColor(0xFF909090.toInt())
                } else {
                    rangeStatus.setText("⚠ The drone will refuse " + problems.joinToString("; ") +
                        ". Correct the value. If you do not, the drone keeps the setting it has now.")
                    rangeStatus.setTextColor(0xFFFF6B6B.toInt())
                }
            }
        }
        refreshRanges()

        val watcher = object : android.text.TextWatcher {
            // Saves locally and re-checks the range. Does NOT push — typing is not intent.
            // "Apply to Drone" is what sends it. See applyAllToAircraft.
            override fun afterTextChanged(s: android.text.Editable?) {
                save()
                refreshRanges()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        listOf(maxAlt, maxRadius, rthAlt).forEach { it.addTextChangedListener(watcher) }

        val applyStatus = findViewById<TextView>(R.id.limitApplyStatus)
        findViewById<android.widget.Button>(R.id.limitApplyButton).setOnClickListener {
            applyAllToAircraft(applyStatus)
        }

        setupFailsafe()
    }

    /**
     * Signal-loss failsafe picker. Like the numeric limits above, saved locally and pushed to
     * the aircraft on its next connect via
     * [FlightLimitsController.applyDefaults] → `doEmergencyAction` (the policy setter behind a
     * misleading name — see that class's doc).
     *
     * The status line spells out that it applies on next connect AND that this SDK can't read
     * the value back, because "I picked Return to Home" and "the aircraft is set to Return to
     * Home" are different claims — and this is the setting where assuming the first means the
     * second is exactly the wrong habit.
     */
    private fun setupFailsafe() {
        val group = findViewById<android.widget.RadioGroup>(R.id.limitFailsafeGroup)
        val status = findViewById<TextView>(R.id.limitFailsafeStatus)

        // Return to Home is the only option, so this is effectively a labelled statement of what
        // the aircraft does rather than a choice. It stays as a checked radio (not plain text) so
        // the pref, the push path and the pilot's mental model all keep working unchanged, and so
        // re-adding an option later is a layout edit rather than a rewrite.
        group.check(R.id.failsafeGoHome)
        FlightLimitsController.saveFailsafe(this, FlightLimitsController.Failsafe.GO_HOME)
        status.visibility = android.view.View.GONE
    }

    // ---- 2. Map Display ----

    /** Flight mini-map tile source. Street or a custom XYZ template — see [MapStyle] for why
     *  there's no Hybrid/satellite option on this airframe. Takes effect next time the flight
     *  screen opens (it reads [MapStyle.tileSource] in onCreate). */
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
    private val videoLockedFields = listOf(
        R.id.videoHost, R.id.videoPort, R.id.videoStreamId,
        R.id.videoUser, R.id.videoPassword,
    )

    /**
     * "Lock configuration" for the TAK and video server sections: a working server setup should
     * not be one stray tap away from being edited on a tailgate.
     *
     * **Locks the TEXT FIELDS only.** Enroll & Connect, Log Out and the video quality/transport
     * choices stay live: needing to reconnect, or to drop to Low on a marginal link, is exactly
     * when a pilot must not be fighting a lock. The lock guards what the server IS, not what
     * you do with it.
     *
     * Unlocking asks for confirmation; locking does not. The asymmetry is deliberate — locking
     * is the safe direction and gating it would just train people to dismiss dialogs.
     */
    private fun setupConfigLocks() {
        setupOneLock(
            R.id.takLockConfig, KEY_TAK_LOCKED, takLockedFields,
            "Unlock TAK server settings?",
            "The lock prevents an accidental change to a server that works. " +
                "A wrong value stops the drone sending data to your team.",
        )
        setupOneLock(
            R.id.videoLockConfig, KEY_VIDEO_LOCKED, videoLockedFields,
            "Unlock video server settings?",
            "These fields are locked so a working stream configuration is not changed by " +
                "accident. Editing them can stop your team seeing the video.",
        )
    }

    private fun setupOneLock(
        checkBoxId: Int,
        prefKey: String,
        fieldIds: List<Int>,
        confirmTitle: String,
        confirmBody: String,
    ) {
        val box = findViewById<android.widget.CheckBox>(checkBoxId)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        // Default LOCKED once a config exists, unlocked on a fresh install — a first-run pilot
        // must not have to discover a lock before they can type anything.
        val locked = prefs.getBoolean(prefKey, false)
        box.isChecked = locked
        applyLock(fieldIds, locked)

        box.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                prefs.edit().putBoolean(prefKey, true).apply()
                applyLock(fieldIds, true)
                AppLog.v(TAG, "config locked: $prefKey")
                return@setOnCheckedChangeListener
            }
            // Unlocking: confirm first, and put the box BACK if they decline. Using
            // setOnCheckedChangeListener means our own revert would re-enter this listener,
            // so the listener is detached around it.
            android.app.AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
                .setTitle(confirmTitle)
                .setMessage(confirmBody)
                .setPositiveButton("Unlock") { _, _ ->
                    prefs.edit().putBoolean(prefKey, false).apply()
                    applyLock(fieldIds, false)
                    AppLog.i(TAG, "config UNLOCKED: $prefKey")
                }
                .setNegativeButton("Cancel") { _, _ ->
                    box.setOnCheckedChangeListener(null)
                    box.isChecked = true
                    setupConfigLocks()
                }
                .setOnCancelListener {
                    box.setOnCheckedChangeListener(null)
                    box.isChecked = true
                    setupConfigLocks()
                }
                .show()
        }
    }

    /**
     * Greys out and disables a set of views. `isEnabled = false` also makes them unfocusable, so
     * the keyboard can't be raised on a locked field — read-only in the way a pilot means it —
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

    private fun setupMapDisplaySection() {
        val group = findViewById<android.widget.RadioGroup>(R.id.mapStyleGroup)
        val customUrl = findViewById<EditText>(R.id.mapCustomUrl)

        when (MapStyle.savedStyleChoice(this)) {
            MapStyle.CUSTOM -> group.check(R.id.mapStyleCustom)
            else -> group.check(R.id.mapStyleStreet)
        }
        customUrl.setText(MapStyle.savedCustomUrl(this))

        findViewById<Button>(R.id.mapDisplaySaveButton).setOnClickListener {
            val choice = when (group.checkedRadioButtonId) {
                R.id.mapStyleCustom -> MapStyle.CUSTOM
                else -> MapStyle.STREET
            }
            val url = customUrl.text.toString().trim()
            // Validate BEFORE saving rather than discovering it in flight: a bad template
            // silently falls back to street tiles at map-load time, which the pilot would only
            // notice as "my imagery didn't work" with no explanation.
            if (choice == MapStyle.CUSTOM && !MapStyle.isUsableTemplate(url)) {
                Toast.makeText(
                    this,
                    "Custom URL must be http(s) and contain {z}, {x} and {y}",
                    Toast.LENGTH_LONG,
                ).show()
                return@setOnClickListener
            }
            AppLog.v(TAG, "tap: Save Map Display -> $choice")
            MapStyle.saveStyleChoice(this, choice, url)
            Toast.makeText(this, "Map display saved — applies next time you enter Flight",
                Toast.LENGTH_SHORT).show()
            // Re-render the cache status: the download restriction depends on which source is
            // selected, so a save can change what that line says.
            renderMapCacheStatus()
        }

        setupMapCacheSection()
    }

    // ---- Offline map tiles ----

    /**
     * Region download for map tiles, deliberately shaped like the UASFM download below: centre,
     * radius, check the size, then download. A pilot who has done one already knows this one.
     *
     * The automatic caching this sits alongside needs no UI at all — osmdroid keeps every tile
     * the flight map draws, within the budget [MapTileCache] configures. This section exists
     * only for ground the aircraft has NOT been over yet.
     */
    private fun setupMapCacheSection() {
        val latField = findViewById<EditText>(R.id.mapCacheLat)
        val lonField = findViewById<EditText>(R.id.mapCacheLon)
        val radiusField = findViewById<EditText>(R.id.mapCacheRadius)
        val checkBtn = findViewById<Button>(R.id.mapCacheCheckButton)
        val downloadBtn = findViewById<Button>(R.id.mapCacheDownloadButton)
        val clearBtn = findViewById<Button>(R.id.mapCacheClearButton)
        val status = findViewById<TextView>(R.id.mapCacheStatus)

        radiusField.setText("10")
        renderMapCacheStatus()

        /** Reads the three fields, or null (with a toast) if they don't make sense. */
        fun readArea(): org.osmdroid.util.BoundingBox? {
            val lat = latField.text.toString().trim().toDoubleOrNull()
            val lon = lonField.text.toString().trim().toDoubleOrNull()
            val radius = radiusField.text.toString().trim().toDoubleOrNull()
            if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                Toast.makeText(this, "Enter a valid centre latitude and longitude",
                    Toast.LENGTH_SHORT).show()
                return null
            }
            if (radius == null || radius <= 0 || radius > 50) {
                Toast.makeText(this, "Enter a radius between 1 and 50 miles",
                    Toast.LENGTH_SHORT).show()
                return null
            }
            return MapTileCache.bboxAround(lat, lon, radius)
        }

        findViewById<Button>(R.id.mapCacheUseLocationButton).setOnClickListener {
            AppLog.v(TAG, "tap: Map cache Use My Location")
            useMyLocationFor(R.id.mapCacheLat, R.id.mapCacheLon)
        }

        checkBtn.setOnClickListener {
            val bbox = readArea() ?: return@setOnClickListener
            val (tiles, bytes) = MapTileCache.estimate(bbox)
            AppLog.v(TAG, "tap: Map cache Check Size -> $tiles tiles, ${MapTileCache.human(bytes)}")
            status.text = "$tiles tiles, about ${MapTileCache.human(bytes)}." +
                if (bytes > MapTileCache.MAX_BYTES)
                    "\nThis is too large. The limit is " +
                        "${MapTileCache.human(MapTileCache.MAX_BYTES)}. Use a smaller radius."
                else "\nPress Download Area to keep them."
        }

        downloadBtn.setOnClickListener {
            val bbox = readArea() ?: return@setOnClickListener
            val source = MapStyle.tileSource(this)
            val (tiles, bytes) = MapTileCache.estimate(bbox)
            if (bytes > MapTileCache.MAX_BYTES) {
                status.text = "$tiles tiles is about ${MapTileCache.human(bytes)}. " +
                    "This is too large. The limit is " +
                    "${MapTileCache.human(MapTileCache.MAX_BYTES)}. Use a smaller radius."
                return@setOnClickListener
            }
            // The street map needs an explicit go-ahead: OSM's usage policy asks apps not to
            // bulk-download from their donated servers. The operator's call is that an offline
            // map is a life-safety item on a public-safety aircraft, so the app allows it —
            // but as a decision the pilot makes each time, not a silent default, because the
            // consequence (OSM blocking this address) lands on them and would land mid-job.
            if (!MapTileCache.allowsBulkDownload(source)) {
                android.app.AlertDialog.Builder(this, R.style.TakDialogTheme)
                    .setTitle("Download street map area?")
                    .setMessage(
                        "OpenStreetMap asks apps not to download their maps in bulk. Their " +
                            "servers are donated.\n\n" +
                            "This will fetch $tiles tiles (about ${MapTileCache.human(bytes)}) " +
                            "as slowly as the normal map does, two at a time.\n\n" +
                            "If OpenStreetMap blocks this address, the street map stops " +
                            "working here until they unblock it. Keep the radius to the area " +
                            "you will actually fly.")
                    .setPositiveButton("Download") { _, _ ->
                        startMapDownload(source, bbox, tiles, downloadBtn, checkBtn, status)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return@setOnClickListener
            }
            startMapDownload(source, bbox, tiles, downloadBtn, checkBtn, status)
        }

        clearBtn.setOnClickListener {
            AppLog.v(TAG, "tap: Clear map cache")
            android.app.AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
                .setTitle("Clear map cache?")
                .setMessage("Delete all stored map tiles (${MapTileCache.human(
                    MapTileCache.usedBytes(this))})? The map will need a connection again " +
                    "until it re-caches.")
                .setPositiveButton("Clear") { _, _ ->
                    MapTileCache.clear(this)
                    renderMapCacheStatus()
                    Toast.makeText(this, "Map cache cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun startMapDownload(
        source: org.osmdroid.tileprovider.tilesource.ITileSource,
        bbox: org.osmdroid.util.BoundingBox,
        tiles: Int,
        downloadBtn: Button,
        checkBtn: Button,
        status: TextView,
    ) {
        AppLog.i(TAG, "map region download starting: $tiles tiles")
        downloadBtn.isEnabled = false
        checkBtn.isEnabled = false
        status.text = "Downloading 0 of $tiles  (0%)"
        MapTileCache.downloadRegion(this, source, bbox, object : MapTileCache.Progress {
                override fun onProgress(done: Int) {
                    // Percentage as well as the raw counts: on a several-thousand-tile job the
                    // counts alone give a pilot no sense of whether this finishes before they
                    // need to leave.
                    val pct = if (tiles > 0) (done * 100 / tiles).coerceIn(0, 100) else 0
                    runOnUiThread { status.text = "Downloading $done of $tiles  ($pct%)" }
                }
                override fun onDone(downloaded: Int) {
                    runOnUiThread {
                        downloadBtn.isEnabled = true
                        checkBtn.isEnabled = true
                        renderMapCacheStatus(extra = "\nDownloaded $downloaded tiles.")
                        Toast.makeText(this@TakConnectActivity, "Map area downloaded",
                            Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onFailed(reason: String) {
                    runOnUiThread {
                        downloadBtn.isEnabled = true
                        checkBtn.isEnabled = true
                        renderMapCacheStatus(extra = "\n$reason")
                    }
                }
            })
    }

    private fun renderMapCacheStatus(extra: String = "") {
        val status = findViewById<TextView>(R.id.mapCacheStatus) ?: return
        val used = MapTileCache.usedBytes(this)
        val needsOverride = !MapTileCache.allowsBulkDownload(MapStyle.tileSource(this))
        status.text = buildString {
            append("Stored: ${MapTileCache.human(used)} of ")
            append(MapTileCache.human(MapTileCache.MAX_BYTES))
            append(". The app removes the oldest tiles when the space is full.")
            if (needsOverride) {
                append("\n\nThe app asks you to confirm an area download of the Street map.")
            }
            append(extra)
        }
    }

    // ---- 5. Elevation Data (DTED) ----

    /** DTED region management — import a region .zip via the system document picker (any file;
     *  DTED extensions aren't a registered MIME type so we don't filter), list imported regions
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
                setBackgroundColor(Color.parseColor("#202020"))
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
                setTextColor(Color.parseColor("#909090"))
                textSize = 12f
            })
            row.addView(info)
            row.addView(TextView(this).apply {
                text = "Delete"
                setTextColor(Color.parseColor("#EF5350"))
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

    // ---- 6. FAA Airspace Ceilings (UASFM) ----

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

        /** Reads the three fields, or null (with a toast) if they don't make sense. */
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

    /** Fills the UASFM centre fields from the controller's own fix, or explains why it can't —
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
                "No position is available. Connect the drone, or type the centre.",
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

        /** How long "Apply to aircraft" waits before reading back. Must clear the slowest write:
         *  the failsafe takes a full 10s to time out on this firmware, so verifying sooner would
         *  read while writes are still in flight and report a false mismatch. */
        private const val VERIFY_DELAY_MS = 11000L
        private const val REQUEST_CODE_DTED_PICK = 4301
        private const val REQUEST_CODE_LOCATION = 4302
        private const val PREFS = "takpilot2_tak"

        /** Per-section configuration locks — see setupConfigLocks(). */
        private const val KEY_TAK_LOCKED = "tak_config_locked"
        private const val KEY_VIDEO_LOCKED = "video_config_locked"

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
        private const val KEY_V_STREAMID = "video_streamid"
        private const val KEY_V_TCP = "video_tcp"
        private const val KEY_V_PROFILE = "video_profile"
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
                !connected -> "The drone is not connected."
                !known -> "Wait. The drone did not send the state yet."
                AutelAvoidance.systemEnabled == true -> "Obstacle avoidance is ON."
                else -> "Obstacle avoidance is OFF."
            }
            status.setTextColor(
                when {
                    !connected || !known -> android.graphics.Color.parseColor("#FFB300")
                    AutelAvoidance.systemEnabled == true -> android.graphics.Color.parseColor("#4CAF50")
                    else -> android.graphics.Color.parseColor("#F44336")
                })
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
                        "The drone did not accept the change.", android.widget.Toast.LENGTH_SHORT).show()
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
        val status = findViewById<android.widget.TextView>(R.id.ratesStatus)
        val stick = findViewById<android.widget.TextView>(R.id.stickModeStatus)

        val stickGroup = findViewById<android.widget.RadioGroup>(R.id.stickModeGroup)
        val stickIds = mapOf("1" to R.id.stickMode1, "2" to R.id.stickMode2, "3" to R.id.stickMode3)

        fun render() {
            val connected = AutelProductHolder.isConnected
            val known = AutelControlRates.precisionActive != null
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
            group.check(if (AutelControlRates.precisionActive == true) R.id.ratesPrecision else R.id.ratesNormal)
            normal.isEnabled = connected && known
            precision.isEnabled = connected && known
            status.text = when {
                !connected -> "The drone is not connected."
                !known -> "Wait. The controller did not send the values yet."
                else -> "Gimbal wheel ${AutelControlRates.dialSpeed}, yaw ${"%.2f".format(AutelControlRates.yawCoefficient)}."
            }
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
