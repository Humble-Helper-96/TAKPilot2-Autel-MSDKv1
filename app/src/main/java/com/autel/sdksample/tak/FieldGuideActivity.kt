package com.autel.sdksample.tak

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.autel.sdksample.R
import com.taklite.util.AppLog

/**
 * Pilot field guide — a read-in-the-field explanation of what every control does. Ported from
 * the DJI blueprint's `FieldGuideActivity`, same structure, voice and content builders.
 *
 * **Audience is the pilot, not a developer.** No class names, no file paths, no SDK talk. Where
 * a limitation matters to a flight decision it IS stated plainly (what the FAA layer can't tell
 * you, when the altitude readout is approximate, what a local marker delete does and doesn't
 * do), because a guide that only lists happy paths is the kind that gets someone in trouble.
 *
 * **Every pilot-facing string in this file is written in ASD-STE100 Simplified Technical
 * English. Keep it that way when you edit.** STE is the aerospace controlled-language standard
 * (approved dictionary + 53 writing rules); it exists so that a reader who is tired, rushed, or
 * reading in a second language cannot mis-parse a safety-relevant instruction. The rules this
 * text is held to:
 *  - **One word, one meaning, one part of speech.** A short screen press is always "touch",
 *    never tap/press/hit. A long press is always "touch and hold". The airframe is always the
 *    "aircraft", never the drone. A marker is always a "marker", never a pin.
 *  - **Approved vocabulary.** "make sure" not ensure, "about" not approximately, "use" not
 *    utilize, "let" not allow, "get" not obtain, "but" not however, "because of" not due to.
 *  - **Active voice, simple tenses, no -ing forms.** "The app sends the marker", not "the
 *    marker is sent" or "sending the marker".
 *  - **Sentence length**: 20 words max for an instruction, 25 for description. Six sentences
 *    max per paragraph, one topic each.
 *  - **Conditions come first**: "If the signal is weak, select Low" — never the reverse.
 *  - **No idiom, metaphor, or humour.** They are the first thing to fail a tired reader and the
 *    first thing to fail a translator.
 *  - **Warnings open with the command**, then the reason.
 *
 * The one deliberate exception: **on-screen control labels are quoted verbatim** even when they
 * are not STE ("Drop Marker at Crosshair", "Enroll & Connect"). STE treats these as technical
 * names, and a guide that renames the button a pilot is hunting for is worse than useless.
 *
 * **The icon examples are live views, not pictures.** Each one is the real toolbar widget —
 * [BatteryGaugeView], [SignalBarsView], [LiveToggleView], [RecordToggleView], the TAK badge with
 * its status dot — constructed here and driven into the state being described. Screenshots or
 * hand-drawn copies would silently go stale the next time an icon changes; these can't, because
 * they ARE the icons.
 *
 * **Every control the blueprint has is documented, and as of the Phase 2.5 activation pass
 * every one of them now works.** There WAS a "What this build cannot do" section listing the
 * two map gaps; the operator cut it in the v1.6.1 pass, so a regression to a placeholder now
 * has no standing home — put it back as a final section if that ever happens again. Section 6
 * (flight path records, v1.5.9) is this build's own; port it TO the DJI guide if that app
 * ever gets the logger.
 *
 * Section 5 is a PROCEDURE, not a control reference: the aim calibration is periodic
 * maintenance the pilot performs, like a compass calibration, so it gets steps in order
 * rather than a description of each button.
 *
 * ## The v1.6.1 rewrite
 *
 * The guide was cut by roughly half (operator, 2026-08-15). It had grown to the length where
 * a pilot did not read it, which makes a safety document worse than a short one that omits
 * something. The rule applied: **keep every fact that changes a flight decision, delete the
 * explanation of why.** A pilot needs "land when the ring is yellow", not a paragraph on how
 * the gauge is computed.
 *
 * Section 3 (the controller buttons) is NEW and is the reason for the pass. Nothing in it is
 * new INFORMATION — C1, C2 and the zoom rocker were all documented — but the facts were
 * scattered through section 4's prose, so the operator read the guide and concluded the
 * hardware was undocumented. That is a findability bug, and the fix is an index, not more
 * words. When a hardware mapping changes, section 3 is the one place to change it, and
 * `FlightActivity.installHardwareButtonListener` is the ground truth to check it against.
 *
 * Section numbers moved (3-6 became 4-7). The cross-references inside the text moved with
 * them; [ANCHOR_AR] is a tag, not a number, so the deep link from the flight screen is
 * unaffected.
 */
class FieldGuideActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_field_guide)
        AppLog.v(TAG, "field guide opened")
        // Menu button on the left of the action bar, matching the flight screen and Pre-Flight
        // (was a back arrow) — returns to the home screen.
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_menu)
        }
        content = findViewById(R.id.fieldGuideContent)

        title("TAKPilot2 Field Guide")
        lede("What each control does, on the screen and on the controller. Read it before " +
            "you fly. This is the EVO II build.")

        sectionOne()
        sectionTwo()
        sectionControllerButtons()
        sectionThree()
        sectionFour()
        sectionFlightRecords()

        divider()
        spacer(24)

        scrollToAnchor(intent?.getStringExtra(EXTRA_SCROLL_TO))
    }

    /**
     * Jumps to the entry tagged [anchor], for a deep link from elsewhere in the app.
     *
     * Posted rather than called directly: the guide is built in onCreate and nothing has been
     * measured or laid out yet, so the card's y position is still 0 and an immediate scroll
     * would silently do nothing. Runs after the first layout pass instead.
     *
     * Fails OPEN — an absent or unrecognised anchor simply leaves the guide at the top, which
     * is what a reader wants if a link ever goes stale.
     */
    private fun scrollToAnchor(anchor: String?) {
        if (anchor.isNullOrEmpty()) return
        val scroll = findViewById<android.widget.ScrollView>(R.id.fieldGuideScroll) ?: return
        val target = content.findViewWithTag<View>(anchor)
        if (target == null) {
            AppLog.w(TAG, "field guide anchor '$anchor' not found — opening at the top")
            return
        }
        scroll.post {
            // Offset up by the section heading's worth so the reader lands with the entry's
            // title in view, not with the card's top edge flush against the action bar.
            scroll.scrollTo(0, (target.top - dp(12)).coerceAtLeast(0))
            AppLog.v(TAG, "field guide opened at '$anchor'")
        }
    }

    // ---------------------------------------------------------------- Section 1

    private fun sectionOne() {
        section("1. What this app is for")
        body("TAKPilot2 flies your Autel EVO II. At the same time it puts the aircraft on the " +
            "shared TAK map of your team.")

        bullet("Your team sees the position, the heading and the altitude of the aircraft.")
        bullet("Your team sees the point on the ground where the camera looks.")
        bullet("You put markers on what you see. Your team gets them in a few seconds.")
        bullet("The quick marker is a single marker that is dropped at the press of a button " +
            "and moves to a new location upon each subsequent press of the button.")
        bullet("The app can send live video to a server of your team.")

        note("Do the firmware updates, the compass calibration, the gimbal calibration and " +
            "the aircraft registration with the Autel app first. Do not do them here.")
    }

    // ---------------------------------------------------------------- Section 2

    private fun sectionTwo() {
        section("2. Pre-Flight Setup")
        body("Set these on the ground prior to flight. Change them for a new area, a new " +
            "server or a new task.")

        sub("1. Aircraft Settings")
        body("Max altitude, max distance and RTH altitude, in feet. The app sends them to " +
            "the aircraft at each connection.")

        sub("2. Video Streaming")
        body("Optional. Type the address of the video server of your team, the port, the " +
            "broadcast ID and the login. Then select the quality. Select Standard. If the " +
            "connection is weak, select Low. Select the codec H.264 for compatibility.")
        body("The video has two protocols: RTSP and SRT. RTSP gives the lowest delay on a " +
            "reliable network. SRT gives a low delay on a less reliable network, for example " +
            "a cellular network.")

        sub("3. TAK Server Connection")
        body("Type the address of the TAK server, your username, your password and the " +
            "callsign of the aircraft. Then touch Enroll & Connect.")
        body("Select the active channels here, or in flight with a touch and hold on the TAK " +
            "connection icon.")

        sub("4. Elevation Data (DTED)")
        body("The terrain data for your area. Import one file for each region. It improves " +
            "two things:")
        bullet("Marker accuracy.")
        bullet("The altitude shows the true height of the aircraft above the ground. Without " +
            "the data, it shows the height above your takeoff point.")

        sub("5. FAA Airspace Ceilings (UASFM)")
        body("This downloads the FAA ceiling data for an area. The flight screen then shows " +
            "the ceiling at the position of the aircraft.")
    }

    // ------------------------------------------------- Section 3 (controller)

    /**
     * The hardware controls, in one place.
     *
     * Added in v1.6.1 (operator). Every fact here was already in the guide, but it was spread
     * across three unrelated entries in section 4 — C1 inside the IR entry, the two halves of
     * C2 under two different crosshair entries — so a pilot who wanted "what does C2 do" had to
     * read the whole of section 4 and know the answer was in there. Coverage was never the
     * problem; findability was.
     *
     * The prose entries keep the DETAIL and lose their hardware paragraph, so a fact still has
     * exactly one home. This section is the index; section 4 is the reference.
     *
     * Ground truth is `FlightActivity.installHardwareButtonListener` and [ZoomLadder.RUNGS_RAW],
     * NOT the old guide text. Check it there when the mapping changes — the SDK names these
     * CUSTOM_BUTTON_{SHORT,LONG}_{A,B}, and which letter is which physical key is a constant in
     * that file.
     */
    private fun sectionControllerButtons() {
        section("3. The controller buttons")
        body("These buttons do the same functions as the buttons on the screen. They are the " +
            "most commonly used features.")

        keyEntry(
            "C1",
            "Changes the camera between the normal camera and the thermal camera.",
            "Changes the thermal colours.",
        )

        keyEntry(
            "C2",
            "Puts the quick marker. If the quick marker already exists, it MOVES to what the " +
                "camera looks at now. The app sends it to the TAK server immediately.",
            "Puts a NEW static marker. This marker does not move. Each additional press and " +
                "hold puts a new static marker.",
        )

        // The ladder moved INTO the press line when the caveat lines came out (operator,
        // 2026-08-15). It is the one fact in this section the operator asked for by name, so
        // it does not leave the guide with the callout that carried it.
        keyEntry(
            "Zoom rocker (right side)",
            "Push it and release it. The zoom moves one level: 1X, 2X, 3X, 4X, 6X, 8X, 10X, " +
                "12X, 16X.",
            "Push it and hold it. The zoom moves through the levels. Release it to stop.",
        )

        keyEntry(
            "RTH button",
            "Sends the aircraft home. This is a function of the controller.",
            null,
        )
    }

    // ---------------------------------------------------------------- Section 4

    private fun sectionThree() {
        section("4. The Flight Screen")
        body("The live camera image fills the screen. The toolbar is across the top. The " +
            "status icons are on the left and the function buttons are on the right.")

        sub("Toolbar: left side (status)")

        entry(
            listOf(icon(R.drawable.ic_menu) to "Menu"),
            "Menu",
            "This button closes the flight screen and shows the home screen. The aircraft " +
                "continues to fly and stays connected to TAK.",
        )

        entry(
            listOf(
                takBadge(connected = true) to "Connected",
                takBadge(connected = false) to "Not connected",
            ),
            "TAK connection",
            "A green dot shows that your aircraft is on the TAK map of your team. A red dot " +
                "shows that it is not on the map. Touch the icon to connect or disconnect.",
        )

        entry(
            listOf(
                battery(85) to "85%",
                battery(24) to "24%",
                battery(9) to "9%",
            ),
            "Battery",
            "The charge in the battery of the aircraft. Land the aircraft when the ring is " +
                "yellow. Do not wait for red.",
        )

        entry(
            listOf(
                signal(90) to "Strong",
                signal(60) to "Medium",
                signal(20) to "Weak",
            ),
            "Controller signal",
            "The strength of the signal between the controller and the aircraft. If the bars " +
                "decrease, fly the aircraft nearer or move away from other sources of radio " +
                "signals. If the aircraft loses the signal, it returns to home.\n\n" +
                "The bars are grey at \"—%\" until the aircraft connects. If they stay grey " +
                "when the aircraft is connected, close the Autel Explorer app. Only one app " +
                "at a time can read the signal.",
        )

        entry(
            listOf(
                gps(hasFix = true) to "Position",
                gps(hasFix = false) to "No position",
            ),
            "GPS satellites",
            "The quantity of satellites that the aircraft receives. Green shows that the " +
                "aircraft has its position. Wait for green before you take off. Without a " +
                "position the aircraft cannot hold its position, cannot set a home point, and " +
                "cannot come home correctly.",
        )

        entry(
            listOf(
                image(R.drawable.ic_rth_home_set) to "Home set",
                image(R.drawable.ic_rth) to "No home",
            ),
            "Return to Home",
            "Touch: sends the aircraft home. The app asks you to confirm.\n\n" +
                "Touch and hold: moves the home point to your position. The app uses the GPS " +
                "of the controller. Use this if you moved away from the takeoff point. The " +
                "app shows the coordinates and asks you to confirm.\n\n" +
                "The house is green when the home point is set.",
        )

        sub("Toolbar: right side (buttons)")

        entry(
            listOf(image(R.drawable.ic_drop_pin) to "Marker"),
            "Put a marker",
            "Touch: puts a marker at the center of the camera image. Point the camera at the " +
                "target first. Select the type (Friendly, Hostile, Neutral or Unknown). The " +
                "app names the marker and sends it to your team.\n\n" +
                "Touch and hold: opens the \"Markers\" list, with your markers and the markers " +
                "of your team. Here you can change them or remove them. Clear All removes all " +
                "your markers.\n\n" +
                "DELETE REMOVES A MARKER FROM THIS AIRCRAFT ONLY. It stays on the screens of " +
                "your team for about 3 days.",
        )

        entry(
            listOf(arPill(on = false) to "Off", arPill(on = true) to "On"),
            "AR: markers on the video",
            "This draws the markers on the live image near their positions. The button is " +
                "green when it is on. It is ON when you open the flight screen.\n\n" +
                "A marker outside the camera image shows as an arrow at the edge. The arrow " +
                "shows the direction to turn the camera.\n\n" +
                "Touch and hold: adjust the filters and the range where markers show.",
            listOf(
                "THE AR VIEW IS NOT ACCURATE FOR A POINT. It shows the general area of a " +
                    "marker. Do not use it to choose between objects that are close together, " +
                    "such as one house in a tight row of houses.",
            ),
            anchor = ANCHOR_AR,
        )

        entry(
            listOf(image(R.drawable.ic_camera_shutter) to "Photo"),
            "Photo",
            "This button takes a photo. The app saves the photo to the card in the aircraft, " +
                "not to the controller.",
        )

        entry(
            listOf(zoomPill("1X") to "Normal", zoomPill("2X") to "2X view"),
            "Zoom",
            "Touch: changes between 1X and 2X. Touch and hold: 4X. From 4X, a touch or a " +
                "touch and hold goes back to 1X, so one touch always gets you to the widest " +
                "view.",
        )

        entry(
            listOf(zoomPill("IR") to "Thermal"),
            "IR: the thermal camera",
            "This changes the image between the normal camera and the thermal camera.",
        )

        entry(
            listOf(
                image(R.drawable.ic_led_on) to "Lights on",
                image(R.drawable.ic_led_off) to "Lights off",
            ),
            "Exterior lights",
            "This turns the navigation lights of the aircraft on and off. Turn them off to " +
                "make the aircraft difficult to see at night.",
        )

        entry(
            listOf(
                live(LiveToggleView.State.OFF) to "Off",
                live(LiveToggleView.State.LIVE) to "Video on",
                live(LiveToggleView.State.RECONNECTING) to "Connects again",
            ),
            "LIVE: video to your team",
            "This button starts and stops the live video to the video server of your team. " +
                "First, set the server data in Pre-Flight Setup.\n\n" +
                "A yellow button that flashes shows that the connection stopped. The app " +
                "tries to connect again without your command. Do not touch the button. If you " +
                "touch it, the app stops and does not try again.",
        )

        entry(
            listOf(
                rec(recording = false) to "Off",
                rec(recording = true) to "Records",
            ),
            "REC: record to the aircraft",
            "This button records video to the card in the aircraft. It is independent of the " +
                "live video. You can use one function, both functions, or no function. The " +
                "card keeps the full quality video without the HUD.",
        )

        sub("On the video image")

        entry(
            emptyList(),
            "The crosshair",
            "The crosshair is the center of the camera image, and the position where a marker " +
                "goes. The colour of the ring shows the approximate accuracy. It changes " +
                "with the angle of the camera.\n\n" +
                "WITH terrain data:\n" +
                "GREEN: 25° down or more. The error is about 25 ft.\n" +
                "YELLOW: 10° to 25° down. The error is about 50 ft.\n\n" +
                "WITHOUT terrain data:\n" +
                "GREEN: 30° down or more. The error is about 50 ft.\n" +
                "YELLOW: 15° to 30° down. The error is about 100 ft.",
        )

        entry(
            emptyList(),
            "Quick marker: touch the crosshair",
            "Touch the crosshair to put a marker immediately, with no questions. The name is " +
                "always ${TakDropMarkers.QUICK_NAME}.\n\n" +
                "THERE IS ONLY ONE QUICK MARKER. Point the camera at a new location and touch " +
                "the crosshair again: the marker MOVES to the new location, on the screens of " +
                "all your team. Press the C2 button for the same function.",
        )

        entry(
            emptyList(),
            "Static marker: touch and hold the crosshair",
            "Touch the crosshair and hold it to put a static marker of the type Unknown. " +
                "THIS MARKER DOES NOT MOVE. A second touch and hold puts a SECOND marker.\n\n" +
                "The name of the static marker is the callsign of the aircraft and a number, " +
                "for example EVO2-07. Press and hold the C2 button for the same function.",
        )

        entry(
            emptyList(),
            "FAA ceiling line",
            "This line is above the small map. It shows the published ceiling at the position " +
                "of the aircraft, and it becomes red if you fly above the ceiling.\n\n" +
                "FAA 300 ft AGL - the published ceiling here.\n" +
                "Class G - no facility map here. The usual 400 ft limit is applicable.\n" +
                "FAA --- ft AGL - the app does not know. You did not download the data, or " +
                "you flew out of the area you downloaded.\n" +
                "FAA - no fix - the aircraft has no position yet.",
        )

        entry(
            emptyList(),
            "The map",
            "The small map in the bottom right corner. The red line goes from the home point " +
                "to the aircraft. The map also shows the markers of your team. There are two " +
                "zoom levels, near and wide. Touch the map two times to make the map larger, " +
                "and two times again to make it small.",
        )

    }

    // ---------------------------------------------------------------- Section 4

    /**
     * The aim calibration.
     *
     * This WAS an ordered 8-step procedure with two warnings. The operator replaced it in the
     * v1.6.1 edit pass with the second-TAK-device method only, as two bullets: watch the SPI
     * point on another device and tune the offsets until it sits on the target. The steps, the
     * hover height, the 25°-down rule and the after-calibration check all went with it.
     */
    private fun sectionFour() {
        section("5. How to correct the position of a marker")

        body("If the aim of the camera has a small error, the markers go to a position that " +
            "is not correct. \"Aim Offsets\" corrects this. The error belongs to the " +
            "aircraft, not to the app: a gimbal can move after a hard landing or a repair.")

        body("Do this one time for each aircraft. Do it again after a hard landing, after a " +
            "repair of the gimbal or the camera, or when you use a different aircraft.")

        body("You need a second device with iTAK, ATAK, TAK Aware or CloudTAK that can see " +
            "the markers of the aircraft.")

        sub("Before you start")
        bullet("Select a target that you see clearly in the video and can find on the map of " +
            "the second device. Center the reticle of the controller on that object. A mark " +
            "on a road or a corner of a building is good. The aircraft sends its camera look " +
            "point to TAK about two times each second. On the second TAK device, find that " +
            "point. Its name ends with \"-SPI\" and its icon looks like the reticle. Watch " +
            "the SPI while you change the offsets on the controller. Change the offsets until " +
            "the SPI on the map lines up with the object the reticle points at in the video.")
        bullet("The point moves a few seconds after each change. Change one control at a " +
            "time. Stop when the point is on the target.")
    }

    // Flight path records (v1.5.9). A DESCRIPTION of automatic behaviour, not a control
    // reference — there is no switch, and the section says so first, because a pilot who
    // reads about a new function will otherwise go looking for its setting.
    private fun sectionFlightRecords() {
        section("6. Flight path records")
        body("The app records the path of each flight automatically. There is no switch, and " +
            "nothing to start or stop. A TAK server and a network are not necessary.")

        body("The recording starts when the aircraft leaves the ground. It stops when the " +
            "aircraft is on the ground for 10 seconds, thus a short touch on the ground does " +
            "not divide the flight into two records.")

        body("Open Downloads/TAKPilotFlights on the controller. Each flight makes two files:")
        bullet(".gpx — the track. Import it into ATAK or Google Earth.")
        bullet(".csv — one row each second: time, position, altitude, speed, heading, " +
            "battery and satellite count. Open it in a spreadsheet.")
    }

    // ------------------------------------------------------- content builders

    private fun title(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE); textSize = 24f
        setTypeface(null, android.graphics.Typeface.BOLD)
    })

    private fun lede(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_secondary)); textSize = 14f
        setPadding(0, dp(6), 0, dp(4))
    })

    private fun section(text: String) {
        divider()
        content.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE); textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(8))
        })
    }

    private fun sub(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_accent)); textSize = 15f
        setTypeface(null, android.graphics.Typeface.BOLD)
        letterSpacing = 0.03f
        setPadding(0, dp(18), 0, dp(6))
    })

    private fun body(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_light)); textSize = 14f
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(0, 0, 0, dp(8))
    })

    private fun bullet(text: String) = content.addView(TextView(this).apply {
        this.text = "•  $text"
        setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_light)); textSize = 14f
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(dp(8), 0, 0, dp(6))
    })

    /** Neutral aside — worth knowing, not a hazard. */
    private fun note(text: String) =
        calloutView(text, R.color.tp_accent, R.color.tp_surface_guide_note)

    // A `warn()` callout — a red tint bar on tp_surface_guide_warn — lived here until the
    // v1.6.1 edit pass removed the last warning from the guide. It was one line over
    // [calloutView], the same shape as [note]. Put it back the same way if a hazard callout
    // is ever wanted again; the two colour tokens it used are still in takpilot_colors.xml.

    /**
     * Callout row: a coloured tint bar against a low-saturation background of the same hue.
     *
     * Takes colour RESOURCES, not hex strings. These four values were literals here until
     * 2026-08-14 (conformance X1) — a literal in Kotlin is easy to reach for and easy to miss
     * in review, which is why §6.1 puts this file inside the token rule.
     */
    private fun calloutView(text: String, @ColorRes barColor: Int, @ColorRes bgColor: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ContextCompat.getColor(applicationContext, bgColor))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(4); bottomMargin = dp(10)
            }
        }
        row.addView(View(this).apply {
            setBackgroundColor(ContextCompat.getColor(applicationContext, barColor))
            layoutParams = LinearLayout.LayoutParams(dp(3), MATCH)
        })
        row.addView(TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_dim)); textSize = 13f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        })
        content.addView(row)
    }

    private fun divider() = content.addView(View(this).apply {
        setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.tp_border))
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply {
            topMargin = dp(20); bottomMargin = dp(12)
        }
    })

    private fun spacer(heightDp: Int) = content.addView(View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(heightDp))
    })

    /**
     * One control: its icon in each state worth recognising, its name, what it does, and any
     * caveats. [icons] may be empty for parts of the screen that aren't a button.
     */
    private fun entry(
        icons: List<Pair<View, String>>,
        name: String,
        what: String,
        caveats: List<String> = emptyList(),
        anchor: String? = null,
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.tp_surface_guide))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
            // Deep-link target — see EXTRA_SCROLL_TO. A tag rather than a generated view id
            // because the whole guide is built in code and ids would have to be kept unique
            // by hand against a resource file that has no other reason to exist.
            if (anchor != null) tag = anchor
        }

        if (icons.isNotEmpty()) {
            val strip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    bottomMargin = dp(10)
                }
            }
            for ((view, caption) in icons) {
                val captionView = TextView(this@FieldGuideActivity).apply {
                    text = caption
                    setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_muted)); textSize = 11f
                    gravity = Gravity.CENTER
                    setSingleLine(true)
                    setPadding(0, dp(5), 0, 0)
                    layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
                }
                // Size the chip from the MEASURED caption rather than leaving it to wrap_content.
                // Letting the layout work it out doesn't survive here: the icon is a fixed-width
                // child, so the cell settles on the icon's width and a longer caption
                // ("Not connected") gets silently cut to something that reads as a different
                // state ("Not conn"). Measuring the text and flooring the chip to it is the only
                // version that can't clip.
                val captionWidth = captionView.paint.measureText(caption).toInt()
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    // Toolbar-dark chip behind each example: these icons are drawn to sit on
                    // the flight toolbar, and judging them against a lighter card would be
                    // misleading about how they actually read in the air.
                    setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.tp_surface_guide_code))
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    minimumWidth = captionWidth + dp(20)
                    layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                        rightMargin = dp(8)
                    }
                }
                cell.addView(view)
                cell.addView(captionView)
                strip.addView(cell)
            }
            card.addView(strip)
        }

        card.addView(TextView(this).apply {
            text = name
            setTextColor(Color.WHITE); textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(5))
        })
        card.addView(TextView(this).apply {
            text = what
            setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_light)); textSize = 13f
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        for (c in caveats) {
            card.addView(TextView(this).apply {
                text = "!  $c"
                setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_warn)); textSize = 12f
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, dp(8), 0, 0)
            })
        }
        content.addView(card)
    }

    /**
     * One hardware control: its name, what a press does, what a press-and-hold does.
     *
     * A fixed two-row shape rather than a paragraph, because this section exists to be SCANNED.
     * The labels are always in the same place, thus a pilot reads down the "Press and hold"
     * column and does not parse a sentence to find out whether a long press is mentioned.
     * [long] is null for a control that has no long press — the row is then omitted rather than
     * filled with "nothing", which would read as a documented behaviour.
     */
    private fun keyEntry(name: String, short: String, long: String?, note: String? = null) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.tp_surface_guide))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
        }
        card.addView(TextView(this).apply {
            text = name
            setTextColor(Color.WHITE); textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })

        fun row(label: String, text: String) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    bottomMargin = dp(4)
                }
            }
            line.addView(TextView(this@FieldGuideActivity).apply {
                this.text = label
                setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_accent))
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                // Fixed width so the two labels line up and the descriptions start on one
                // column. wrap_content would step the text in and out by the label's length.
                layoutParams = LinearLayout.LayoutParams(dp(104), WRAP)
            })
            line.addView(TextView(this@FieldGuideActivity).apply {
                this.text = text
                setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_light))
                textSize = 13f
                setLineSpacing(dp(3).toFloat(), 1f)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            })
            card.addView(line)
        }

        row("Press", short)
        if (long != null) row("Press and hold", long)

        if (note != null) {
            card.addView(TextView(this).apply {
                text = "!  $note"
                setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_warn))
                textSize = 12f
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, dp(8), 0, 0)
            })
        }
        content.addView(card)
    }

    // ------------------------------------------------- live icon examples

    private fun iconParams(sizeDp: Int = 34) = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))

    private fun icon(res: Int) = image(res)

    private fun image(res: Int): View = ImageView(this).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = iconParams()
    }

    /** The TAK badge exactly as the toolbar builds it, dot tinted to the state described. */
    private fun takBadge(connected: Boolean): View {
        val frame = android.widget.FrameLayout(this).apply { layoutParams = iconParams() }
        frame.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_tak_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(34), dp(34))
        })
        frame.addView(ImageView(this).apply {
            setImageResource(R.drawable.bg_status_dot)
            setColorFilter(if (connected) CONNECTED_GREEN else DISCONNECTED_RED)
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(12), dp(12)).apply {
                gravity = Gravity.BOTTOM or Gravity.START
            }
        })
        return frame
    }

    private fun battery(pct: Int): View =
        BatteryGaugeView(this).apply { layoutParams = iconParams(); setPercent(pct) }

    private fun signal(pct: Int?): View =
        SignalBarsView(this).apply { layoutParams = iconParams(); setPercent(pct) }

    private fun rec(recording: Boolean): View =
        RecordToggleView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(74), dp(34))
            setRecording(recording)
        }

    private fun zoomPill(label: String): View = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.bg_zoom_pill)
        setTextColor(Color.WHITE); textSize = 12f
        setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(26))
    }

    /** The AR pill in either state, built from the same drawables the toolbar uses. */
    private fun arPill(on: Boolean): View = TextView(this).apply {
        text = "AR"
        gravity = Gravity.CENTER
        setBackgroundResource(if (on) R.drawable.bg_ar_pill_active else R.drawable.bg_zoom_pill)
        setTextColor(if (on) CONNECTED_GREEN else Color.WHITE)
        alpha = if (on) 1f else 0.45f
        textSize = 12f
        setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(26))
    }

    private fun gps(hasFix: Boolean): View = ImageView(this).apply {
        setImageResource(R.drawable.ic_gps)
        setColorFilter(if (hasFix) CONNECTED_GREEN else NO_FIX_GREY)
        layoutParams = iconParams()
    }

    private fun live(state: LiveToggleView.State): View =
        LiveToggleView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(82), dp(34))
            setState(state)
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Action-bar back arrow behaves the same as the system back gesture. */
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        private const val TAG = "TP2Guide"

        /**
         * Optional intent extra: open the guide scrolled to one entry rather than at the top.
         * Value is one of the ANCHOR_* constants, matched against the tag [entry] puts on its
         * card. An unknown or absent value opens at the top, which is the safe default — a
         * deep link that stops matching must not open a blank-looking screen.
         */
        const val EXTRA_SCROLL_TO = "scrollTo"

        /** The AR overlay entry, reached from the flight screen's AR menu. */
        const val ANCHOR_AR = "ar"

        /** Opens the guide at [anchor] (or the top if null). */
        @JvmStatic
        fun intent(context: android.content.Context, anchor: String? = null) =
            android.content.Intent(context, FieldGuideActivity::class.java).apply {
                if (anchor != null) putExtra(EXTRA_SCROLL_TO, anchor)
            }
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        // Same values the flight screen tints these with, so a state shown here is the state
        // the pilot will actually see.
        private val CONNECTED_GREEN = 0xFF4CAF50.toInt()
        private val DISCONNECTED_RED = 0xFFF44336.toInt()
        private val NO_FIX_GREY = 0xFFAAAAAA.toInt()
    }
}
