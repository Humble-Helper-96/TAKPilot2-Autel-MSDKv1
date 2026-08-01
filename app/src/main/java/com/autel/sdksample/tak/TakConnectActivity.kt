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
            runCatching { TakForegroundService.stop(applicationContext) }
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
     * [FlightLimitsController] on the next connect (via [AutelTakBridge]'s one-shot). Blank
     * leaves the aircraft's own current setting alone.
     *
     * No signal-loss failsafe control, unlike the DJI blueprint — the Autel SDK exposes none.
     * See [FlightLimitsController]'s doc for the audit.
     */
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
        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = save()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        listOf(maxAlt, maxRadius, rthAlt).forEach { it.addTextChangedListener(watcher) }

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

        val idFor = { f: FlightLimitsController.Failsafe ->
            when (f) {
                FlightLimitsController.Failsafe.GO_HOME -> R.id.failsafeGoHome
                FlightLimitsController.Failsafe.HOVER -> R.id.failsafeHover
                FlightLimitsController.Failsafe.LAND -> R.id.failsafeLand
            }
        }
        group.check(idFor(FlightLimitsController.savedFailsafe(this)))

        status.text = "Sent to the aircraft the next time it connects."

        group.setOnCheckedChangeListener { _, checkedId ->
            val choice = when (checkedId) {
                R.id.failsafeHover -> FlightLimitsController.Failsafe.HOVER
                R.id.failsafeLand -> FlightLimitsController.Failsafe.LAND
                else -> FlightLimitsController.Failsafe.GO_HOME
            }
            AppLog.i("TP2LimitsAutel",
                "signal-loss failsafe set to '${choice.label}' (applies on next connect)")
            FlightLimitsController.saveFailsafe(this, choice)
        }
    }

    // ---- 2. Map Display ----

    /** Flight mini-map tile source. Street or a custom XYZ template — see [MapStyle] for why
     *  there's no Hybrid/satellite option on this airframe. Takes effect next time the flight
     *  screen opens (it reads [MapStyle.tileSource] in onCreate). */
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
                    "\nThat is more than the ${MapTileCache.human(MapTileCache.MAX_BYTES)} " +
                        "cache holds. Use a smaller radius."
                else "\nTouch Download Area to store them."
        }

        downloadBtn.setOnClickListener {
            val bbox = readArea() ?: return@setOnClickListener
            val source = MapStyle.tileSource(this)
            val (tiles, bytes) = MapTileCache.estimate(bbox)
            if (bytes > MapTileCache.MAX_BYTES) {
                status.text = "$tiles tiles is about ${MapTileCache.human(bytes)}, more than " +
                    "the ${MapTileCache.human(MapTileCache.MAX_BYTES)} cache holds. " +
                    "Use a smaller radius."
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
            append("Cached: ${MapTileCache.human(used)} of ")
            append(MapTileCache.human(MapTileCache.MAX_BYTES))
            append(". Oldest tiles are removed when it is full.")
            if (needsOverride) {
                append("\n\nArea download of the Street map will ask you to confirm — " +
                    "OpenStreetMap asks apps not to bulk-download from their donated servers.")
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
        dtedStatus.text = if (regions.isEmpty()) "No terrain regions imported."
            else "${regions.size} region(s) imported."
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
            result.error != null && result.importedCount == 0 -> "Failed to import $name: ${result.error}"
            result.error != null -> "Imported ${result.importedCount} tile(s) from $name (${result.error})"
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
                    result.error != null -> "Couldn't reach the FAA service: ${result.error}"
                    result.count == 0 ->
                        "No facility-map cells in that area — it's likely all uncontrolled " +
                            "airspace, where the Part 107 400 ft limit applies."
                    else -> "${result.count} cell(s) in that area. Tap Download to store them."
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
            "No FAA ceiling data downloaded — the flight HUD will show the Part 107 400 ft default."
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

    private fun fillCentreFromLocation(
        latId: Int = pendingLocationTarget.first,
        lonId: Int = pendingLocationTarget.second,
    ) {
        val loc = lastKnownPhoneLocation()
        if (loc == null) {
            Toast.makeText(
                this,
                "No GPS fix yet — go outside for a moment, or type the centre manually",
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
        private const val REQUEST_CODE_DTED_PICK = 4301
        private const val REQUEST_CODE_LOCATION = 4302
        private const val PREFS = "takpilot2_tak"
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
}

// NOTE: TakBridgeHolder lives in AutelTakBridge.kt in this port (it wraps AutelTakBridge).
