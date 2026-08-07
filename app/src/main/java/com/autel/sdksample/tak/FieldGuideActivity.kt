package com.autel.sdksample.tak

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
 * every one of them now works.** Anything that regresses to a placeholder should get a line in
 * section 6 ("What this build cannot do"), which exists as a single pre-flight scan rather
 * than making a pilot re-read section 3. Section 6 has no counterpart in the DJI guide — that
 * build has no gaps left to list — so keep it whenever the two files are reconciled. Section 5
 * (flight path records, v1.5.9) is also this build's own; port it TO the DJI guide if that
 * app ever gets the logger.
 *
 * Section 4 is a PROCEDURE, not a control reference: the aim calibration is periodic
 * maintenance the pilot performs, like a compass calibration, so it gets steps in order
 * rather than a description of each button.
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
        lede("This guide shows what the app does. It also shows what each control on the " +
            "flight screen does. Read it before you fly. This is the EVO II build.")

        sectionOne()
        sectionTwo()
        sectionThree()
        sectionFour()
        sectionFlightRecords()
        sectionSix()

        divider()
        body("If this guide does not agree with the aircraft, obey the aircraft. Then tell " +
            "the person who maintains the app.")
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
        body("TAKPilot2 flies your Autel EVO II aircraft. At the same time, it sends what the " +
            "aircraft sees to the shared TAK map of your team. The app does these three things " +
            "together:")

        bullet("Your aircraft shows on the TAK map of all your team. Its position, heading " +
            "and altitude change as it flies.")
        bullet("The app also sends the point on the ground where the camera looks. Your team " +
            "sees where the aircraft is and what it looks at.")
        bullet("You can put markers on what you see. The markers show on the screens of your " +
            "team in a few seconds.")

        spacer(10)
        body("The app can also send live video to a server, and your team can look at this " +
            "video. It shows the TAK markers of other operators on your map. It shows the FAA " +
            "altitude limit where you fly.")

        note("Do not use this app for firmware updates, compass calibration, gimbal " +
            "calibration or aircraft registration. Do these tasks first with the Autel app.")

        note("You fly the aircraft with the sticks on the controller, as usual. It does not " +
            "change the RTH button on the controller.")

        note("If the TAK icon on the flight screen is red, the app does not send data to your " +
            "team. The aircraft flies correctly, but your team cannot see the aircraft or " +
            "your markers.")
    }

    // ---------------------------------------------------------------- Section 2

    private fun sectionTwo() {
        section("2. Pre-Flight Setup")
        body("You set these items on the ground. The app keeps them for the next flight. " +
            "Usually you set them one time. Change them only for a new area, a new server or " +
            "a new task.")

        sub("1. Aircraft Settings")
        body("The app sends these safety limits to the aircraft at each connection. All " +
            "values are in feet.")
        bullet("Max altitude - the maximum height the aircraft lets you fly.")
        bullet("Max distance - the maximum distance from the home point. At this limit the " +
            "aircraft stops and holds its position. It does not come back without your " +
            "command.")
        bullet("RTH altitude - the height the aircraft climbs to before it flies home. Set " +
            "this height more than the highest obstacle between you and the aircraft.")
        bullet("If the signal is lost - the aircraft returns to home if it loses the " +
            "controller. It does this without the app. It works if your controller stops " +
            "during the flight.")
        bullet("Obstacle avoidance - the switches show the state in the aircraft. Connect " +
            "the aircraft first. If you touch a switch, the app changes the aircraft " +
            "immediately.")
        note("To keep the value that is already in the aircraft, leave the field empty.")
        note("The other settings in this section go to the aircraft at each connection. " +
            "The obstacle avoidance switches do not. The app does not change obstacle " +
            "avoidance without your command.")
        warn("Make sure that obstacle avoidance is ON before you fly. A different app can " +
            "turn it off, and the aircraft keeps that state. The ENTER FLIGHT card on the " +
            "home screen shows the state.")

        sub("2. Video Streaming")
        body("This section is optional. If your team has a video server, type its address, " +
            "its port, the video name for this aircraft, and the login. Then select the " +
            "quality: Low, Standard or High. A low quality works better on a weak connection. " +
            "Usually, select Standard. If the connection is weak, select Low.")
        note("These settings do not start the video. Use the LIVE button in flight to start " +
            "and stop the video.")

        sub("3. TAK Server Connection")
        body("These fields set the address of the TAK server of your team and your identity " +
            "on it. Type the address, the two ports, your username and your password. Type " +
            "the callsign for your aircraft. Then touch Enroll & Connect. Usually you do this " +
            "one time for each server.")
        body("The channel list is below these fields. These are the groups for your login. " +
            "The channels you select receive the position of the aircraft and your markers. " +
            "If you select no channel, the server selects the channels.")

        sub("4. Elevation Data (DTED)")
        body("This is the terrain data for your flight area. You import one file for each " +
            "region. The data tells the app the height of the ground below the aircraft.")
        body("The terrain data improves two functions:")
        bullet("Markers go to the correct position. Without the data, a marker on a slope " +
            "can be too near or too far.")
        bullet("The altitude shows the true height above the ground. Without the data, it " +
            "shows the height above your takeoff point.")

        sub("5. FAA Airspace Ceilings (UASFM)")
        body("This downloads the FAA UAS Facility Map altitudes for an area. The flight " +
            "screen then shows the ceiling at your position. Type a center point and a " +
            "radius, or touch Use My Location. Check the size, then download the data.")
        note("Download this data on a wifi connection before you go to the flight area. In " +
            "flight, the app reads the data from the controller and does not need a signal.")
        warn("Do not use this data as an approval to fly. It shows the altitude that the FAA " +
            "usually approves, but it is not an approval. The FAA changes these maps and the " +
            "data can become out of date. You must get your own airspace approval.")
    }

    // ---------------------------------------------------------------- Section 3

    private fun sectionThree() {
        section("3. The Flight Screen")
        body("The live camera image fills the screen. The toolbar is across the top. The " +
            "status icons are on the left and the buttons are on the right. This section " +
            "shows each control in sequence.")

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
                "shows that it is not on the map. You can fly, but your team cannot see the " +
                "aircraft. Touch the icon to connect or disconnect.",
        )

        entry(
            listOf(
                battery(85) to "85%",
                battery(24) to "24%",
                battery(9) to "9%",
            ),
            "Battery",
            "This ring shows the charge in the battery of the aircraft. The ring becomes " +
                "empty as you fly. Green shows more than one third of the charge. Yellow " +
                "shows less than one third, and red shows less than 15%. Land the aircraft " +
                "when the ring is yellow. Do not wait for red.",
        )

        entry(
            listOf(
                signal(90) to "Strong",
                signal(60) to "Medium",
                signal(20) to "Weak",
            ),
            "Controller signal",
            "These bars show the strength of the signal between the controller and the " +
                "aircraft. This is the same value that the signal indicator of the controller " +
                "shows. The percentage is next to the bars. Touch the bars to see the exact " +
                "percentage. The bars are grey at \"—%\" until the aircraft connects.",
            listOf(
                "Look at the bars as the aircraft flies away from you. If the bars decrease, " +
                    "fly the aircraft nearer. If the aircraft loses the signal, it does the " +
                    "failsafe action and you cannot control it.",
                "If the bars stay grey when the aircraft is connected, close the Autel " +
                    "Explorer app. Only one app at a time can read the signal from the " +
                    "controller. Everything else continues to operate.",
            ),
        )

        entry(
            listOf(
                gps(hasFix = true) to "Position",
                gps(hasFix = false) to "No position",
            ),
            "GPS satellites",
            "This shows the quantity of satellites that the aircraft receives. Green shows " +
                "that the aircraft has its position. Grey shows that it does not have its " +
                "position. Wait for green before you take off. Without a position, the " +
                "aircraft cannot hold its position, cannot set a home point, and cannot come " +
                "home correctly.",
        )

        entry(
            listOf(
                image(R.drawable.ic_rth_home_set) to "Home set",
                image(R.drawable.ic_rth) to "No home",
            ),
            "Return to Home",
            "Touch this button to send the aircraft home. The app asks you to confirm.\n\n" +
                "The house becomes green when the home point is set. This shows that the " +
                "aircraft has a position to return to.\n\n" +
                "Touch and hold the button to move the home point to your position. The app " +
                "uses the GPS of the controller, not the GPS of the aircraft. Use this " +
                "function if you moved away from the takeoff point. The app shows the " +
                "coordinates and asks you to confirm, because this changes where the aircraft " +
                "flies.",
            listOf(
                "If the controller does not have a GPS position, the app does not move the " +
                    "home point. The aircraft keeps its first home point, which can be far " +
                    "from your position. Get a GPS position before you use RTH.",
                "Compare the coordinates in the window with your true position. An old " +
                    "controller position sends the aircraft to where you were before.",
                "The RTH button on the controller operates as usual. This button does not " +
                    "change it.",
            ),
        )

        sub("Toolbar: right side (buttons)")

        entry(
            listOf(image(R.drawable.ic_drop_pin) to "Marker"),
            "Put a marker",
            "This button puts a marker on the ground at the center of the camera image. " +
                "Point the camera at the target, then touch the button. The app opens the " +
                "\"Drop Marker at Crosshair\" window. Select the type (Friendly, Hostile, " +
                "Neutral or Unknown) and type a name. The app then sends the marker to your " +
                "team.\n\n" +
                "Touch and hold the button to open the \"Dropped Markers\" list. Each marker " +
                "shows its distance and direction from the aircraft. Touch a marker to move " +
                "it to the camera position, change its name, change its type, send it again, " +
                "or delete it. Clear All deletes all the markers.",
            listOf(
                "If the aircraft does not have a GPS position and a gimbal position, the app " +
                    "does not put the marker.",
                "If the ring of the crosshair is red, the app does not put the marker. The " +
                    "angle of the camera is too small for an accurate position. Point the " +
                    "camera down more.",
                "If the aircraft is less than 25 ft above the ground, the app does not put " +
                    "the marker. Near the ground the app cannot calculate a position: the " +
                    "marker would go to the position of the aircraft. Climb higher.",
                "If you move, rename or change the type of a marker, the app changes the " +
                    "same marker on the screens of your team. It does not make a second one.",
                "If you delete a marker, the app removes it from your screen only. It stays " +
                    "on the screens of your team for about 14 hours. Clear All is the same.",
                "MARKERS THAT YOUR TEAM SENDS TO YOU STAY ON YOUR MAP. The app keeps them for " +
                    "72 hours after the last time it receives them, and it keeps them when you " +
                    "start the app again.\n\n" +
                    "To remove one, touch it on the small map and select Delete. This removes " +
                    "it from your aircraft only. It stays on the screens of your team.\n\n" +
                    "A person who deletes a marker in a Data Sync mission also removes it from " +
                    "your aircraft. A person who deletes a marker that is NOT in a mission does " +
                    "not: that marker stays with you until the 72 hours end, or until you " +
                    "delete it.",
            ),
        )

        entry(
            listOf(arPill(on = false) to "Off", arPill(on = true) to "On"),
            "AR: markers on the video",
            "This function draws the markers on the live image near their positions. You " +
                "can then see which general area a marker is in. The button becomes green " +
                "when the function is on.\n\n" +
                "USE THIS FOR GENERAL AWARENESS OF AN AREA. Do not use it for an exact " +
                "point. See the first note below.\n\n" +
                "A marker outside the camera image shows as a small arrow at the edge of the " +
                "image. The arrow shows the direction to turn the camera.\n\n" +
                "Touch and hold the button to select what the app draws:\n" +
                "- My Markers\n" +
                "- Team Markers\n" +
                "- Team Positions\n" +
                "- Air Traffic\n\n" +
                "You can also set the range for air traffic to 2.5, 5 or 15 miles. If you " +
                "set an item to off, the app removes it from the image immediately. The app " +
                "always shows ground markers to 5 miles.\n\n" +
                "The app does not show METAR weather stations. Their content is in the " +
                "remarks, which this app does not show, so a station is only a dot that you " +
                "cannot read.",
            listOf(
                "THE AR VIEW IS NOT ACCURATE FOR A POINT. It shows you the general area of " +
                    "a marker. It does not show an exact object.\n\n" +
                    "Do not use it to choose between objects that are close together, such " +
                    "as one house in a row of houses, or one vehicle in a parking area. Use " +
                    "it to know where to look.\n\n" +
                    "For an exact position, put the crosshair on the object and put a " +
                    "marker. Fly nearer to the object.",
                "THE ERROR CHANGES WITH THE DIRECTION THE AIRCRAFT FACES. This is the " +
                    "compass of the aircraft. It is not the app.\n\n" +
                    "A flight test on 4 August 2026 aimed at the same target from three " +
                    "directions. The direction error was between 1 and 6 degrees. At 350 m " +
                    "this moved a marker up to 27 m to the side.\n\n" +
                    "A compass calibration of the aircraft makes this smaller. The Aim " +
                    "Offsets control cannot remove it, because one fixed number cannot " +
                    "correct an error that changes with direction.",
                "If you move the camera quickly, the markers move on the image. They become " +
                    "correct when you stop. This is normal, because the position data and the " +
                    "video do not arrive at the same time.",
                "The touch-and-hold menu has two controls. They correct different errors:\n" +
                    "- Calibrate FOV: use it if the markers are correct in the center of " +
                    "the image but not correct near the edges. The app now reads the field " +
                    "of view from the camera, so you do not usually need this.\n" +
                    "- Aim Offsets: use it if the markers are not correct in the CENTER. " +
                    "This also moves the position of a marker that you put.",
            ),
            anchor = ANCHOR_AR,
        )

        entry(
            listOf(image(R.drawable.ic_camera_shutter) to "Photo"),
            "Photo",
            "This button takes a photo. The app saves the photo to the card in the aircraft, " +
                "not to the controller. A \"Photo Saved\" message shows when the camera " +
                "confirms the photo.",
        )

        entry(
            listOf(zoomPill("1X") to "Normal", zoomPill("2X") to "2X view"),
            "Zoom",
            "This button changes the zoom of the camera. Touch it to change between 1X " +
                "and 2X. Touch and hold it for 4X. From 4X, a touch or a touch-and-hold " +
                "goes back to 1X.\n\n" +
                "The C1 button on the controller does the same thing. A short press " +
                "changes between 1X and 2X. A long press gives 4X.\n\n" +
                "The zoom changes the camera image. Your team sees the same view in the " +
                "video.",
            listOf(
                "This zoom is digital. It makes the image larger, but it does not add " +
                    "detail. For an accurate marker, fly nearer. Do not use the zoom from " +
                    "a long distance.",
            ),
        )

        entry(
            listOf(zoomPill("IR") to "Thermal"),
            "IR: the thermal camera",
            "This button changes the image between the normal camera and the thermal camera. " +
                "The button becomes green when the thermal camera is on. The image changes for " +
                "your team also, because they see the same image.\n\n" +
                "The thermal camera shows heat, not light. Thus it finds a person or a hot " +
                "vehicle in the dark, in smoke, or in vegetation.\n\n" +
                "When the thermal camera is on, a second button shows under the exposure " +
                "control. Each press changes the heat colours, in this order:\n" +
                "WHITE HOT - hot is white, cold is black.\n" +
                "BLACK HOT - hot is black, cold is white.\n" +
                "IRONBOW - hot is yellow and white, cold is purple and black. This is the " +
                "colour view many pilots know from Explorer.",
            listOf(
                "The buttons show the state of the CAMERA. If you start the app when the " +
                    "camera is already in thermal, the button is green immediately.",
                "The thermal camera does not see through glass or water.",
            ),
        )

        entry(
            listOf(
                image(R.drawable.ic_led_on) to "Lights on",
                image(R.drawable.ic_led_off) to "Lights off",
            ),
            "Exterior lights",
            "This button turns the navigation lights of the aircraft on and off. Press it to " +
                "change them. The icon shows the state of the aircraft: a plain bulb when the " +
                "lights are on, a bulb with a line when they are off. A dim icon means the " +
                "aircraft did not report the state yet.\n\n" +
                "Turn the lights off to make the aircraft difficult to see at night.\n\n" +
                "⚠ The lights make the aircraft visible to other aircraft. FAA rules require " +
                "anti-collision lights at night. Turn them off only when your authority permits " +
                "it.",
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
                "card keeps the full quality, but the live video to your team has a lower " +
                "quality.\n\n" +
                "The button shows the state of the camera. If the record function stops " +
                "without your command, the button changes to off. This occurs if the card is " +
                "full or if a person removes the card.",
        )

        sub("On the video image")

        entry(
            emptyList(),
            "The crosshair",
            "The crosshair shows the center of the camera image. This is the position where " +
                "a marker goes.\n\n" +
                "The ring in the center changes color. The color shows the accuracy of a " +
                "marker at this moment. The accuracy changes with the angle of the camera. " +
                "You can read this angle on the GIMBAL line of the readout. The angles are " +
                "different if you loaded terrain data (DTED) for your area.\n\n" +
                "WITH terrain data:\n" +
                "GREEN: 25° down or more. The error is about 10 ft.\n" +
                "YELLOW: 10° to 25° down. The error is about 50 ft.\n\n" +
                "WITHOUT terrain data:\n" +
                "GREEN: 30° down or more. The error is about 50 ft.\n" +
                "YELLOW: 15° to 30° down. The error is about 100 ft.\n\n" +
                "RED: less than the yellow angle. THE APP DOES NOT PUT A MARKER. Point the " +
                "camera down more, or fly nearer. This applies to the marker button, the " +
                "crosshair and the button on the controller.\n\n" +
                "The app also does not put a marker if the aircraft is less than 25 ft " +
                "above the ground.\n\n" +
                "When the camera is near horizontal, a small error in the angle moves the " +
                "marker a long distance on the ground. A steep angle is more accurate than a " +
                "view from a long distance. If the position of a marker is important, fly " +
                "nearer and point the camera down. Without terrain data, the app must " +
                "calculate with flat ground, and this adds more error.",
            listOf(
                "These values are correct only with a good GPS position. A weak GPS position " +
                    "or large metal structures near the aircraft cause more error at all " +
                    "angles.",
                "The app uses the terrain data at the current position of the aircraft. If " +
                    "you fly out of the area of your data, the ring changes to the angles for " +
                    "no terrain data.",
                "If markers go to a position that is not correct at all angles, the aim of " +
                    "the camera can have an error. See section 4 to correct it.",
            ),
        )

        entry(
            emptyList(),
            "Quick marker: touch the crosshair",
            "Touch the crosshair to put a marker immediately. The app does not ask you " +
                "questions. The type is always Unknown and the name is always " +
                "${TakDropMarkers.QUICK_NAME}. Your team can identify it quickly.\n\n" +
                "There is only one quick marker. Point the camera at a new target and " +
                "touch the crosshair again: the marker MOVES to the new target. It moves " +
                "on the screens of all your team. The app does not put a second marker.\n\n" +
                "You can also touch and hold the crosshair, or press the C2 button on the " +
                "controller. All of them do the same thing.\n\n" +
                "Use the quick marker to show your team what you look at now. To keep a " +
                "record of a position, use the marker button. With that button you can set a " +
                "name and a type.",
            listOf(
                "To remove the quick marker, delete it from the marker list. Touch and hold " +
                    "the marker button to open the list.",
                "The quick marker follows the same rules as other markers.",
            ),
        )

        entry(
            emptyList(),
            "The readout (right side)",
            "The readout shows this data from the top to the bottom:\n" +
                "- the callsign of your aircraft and its speed\n" +
                "- its latitude and longitude\n" +
                "- the distance and the direction from the home point\n" +
                "- its height above the ground and above sea level\n" +
                "- the angle of the camera\n\n" +
                "To see the state of the aircraft and of the TAK connection, look at the " +
                "toolbar at the top of the screen.",
            listOf(
                "The camera angle line shows DOWN, UP or LEVEL. The camera can look above " +
                    "the horizon. If it does, the app cannot calculate a position on the " +
                    "ground: it does not put a marker and it does not send the camera " +
                    "position to your team. Point the camera down to continue.",
                "The height shows AGL if terrain data covers your position. AGL is the true " +
                    "height above the ground below the aircraft.",
                "The height shows ALT if there is no terrain data. ALT is the height above " +
                    "your takeoff point. This value is different if the ground below the " +
                    "aircraft is higher or lower.",
                "MSL is the height above sea level. Aviation charts and airspace limits use " +
                    "this value. MSL needs terrain data for your takeoff point only. If there " +
                    "is no data, MSL shows a dash. MSL can show a value when the line above " +
                    "shows ALT, because the app calculates the two values separately.",
            ),
        )

        entry(
            emptyList(),
            "FAA ceiling line",
            "This line is above the small map. It shows the published ceiling at the position " +
                "of the aircraft. It becomes red if you fly above the ceiling.\n\n" +
                "The line shows AGL, because FAA ceilings are always heights above the " +
                "ground. Compare this value with the AGL line of the readout. Do not compare " +
                "it with the MSL line.\n\n" +
                "The line shows one of these:\n" +
                "FAA 300 ft AGL - the published ceiling at this position.\n" +
                "Class G - the FAA has no facility map here. The usual limit of 400 ft is " +
                "applicable.\n" +
                "FAA --- ft AGL - the app does not know the limit. Either you did not download " +
                "FAA data, or you flew out of the area that you downloaded.\n" +
                "FAA - no fix - the aircraft does not have its position yet.\n\n" +
                "If the app does not know the limit, this is not an approval to fly. Get the " +
                "limit for your area before you fly.",
        )

        entry(
            emptyList(),
            "The map",
            "This is the small map in the bottom right corner. North is always at the top " +
                "and the aircraft is always in the center. You cannot move the map with your " +
                "finger. The red line goes from the home point to the aircraft, and shows " +
                "your route back. The map also shows the TAK markers of other operators. " +
                "Touch a marker to remove it from your map only.\n\n" +
                "The button on the map changes between two views. WIDE shows more ground. " +
                "NEAR shows the ground below the aircraft.\n\n" +
                "Touch the map two times to make it two times larger. It then covers a part " +
                "of the video and the data at the right side. Touch it two times again to " +
                "make it small. It is always small when you go to the flight screen.",
            listOf(
                "The larger map shows four times more ground. It does not make the same " +
                    "ground larger.",
                "AT THE WIDE VIEW, THE HOME POINT LEAVES THE MAP BEFORE THE AIRCRAFT IS AT " +
                    "ITS DISTANCE LIMIT. The wide view shows about 828 m across, and the " +
                    "limit is 488 m from the home point. The HOME distance and direction at " +
                    "the top right of the screen stay correct at each distance. Use them to " +
                    "find your way back when the home point is not on the map.",
            ),
        )

        entry(
            emptyList(),
            "Exposure slider (top right)",
            "This slider makes the image brighter or darker. The camera adjusts the exposure " +
                "automatically. Use the slider when the automatic exposure is not correct. " +
                "Examples are a dark object against snow, or a bright sky above dark ground. " +
                "The range is two stops brighter and two stops darker.\n\n" +
                "The app keeps your value. It sets the value again each time the camera " +
                "connects. Thus you do not set it again after you change the battery.\n\n" +
                "The numbers below the slider show the values of the camera.",
            listOf(
                "Move the slider to the center when the light changes. A dark value from a " +
                    "snow field makes a black image in a forest.",
            ),
        )
    }

    // ---------------------------------------------------------------- Section 4

    /**
     * Consolidated "not working yet" list. Each of these is also flagged on its own entry
     * above; repeating them in one place gives a pilot a single thing to scan before a flight
     * rather than re-reading the whole of section 3.
     */
    /**
     * The aim calibration procedure.
     *
     * Written as ordered steps because it is a task the pilot DOES, not a control they read
     * about. The two rules that decide whether it works — do it at a small camera angle, and
     * move one control at a time — are stated as warnings, because a pilot who calibrates at a
     * steep angle will see no change, conclude the control is broken, and stop.
     */
    private fun sectionFour() {
        section("4. How to correct the position of a marker")

        body("The app puts a marker at the center of the camera image. If the aim of the " +
            "camera has a small error, the markers go to a position that is not correct. The " +
            "\"Aim Offsets\" control corrects this error.")

        body("The error is a property of the aircraft. It is not a property of the app. A " +
            "gimbal can move a small quantity after a hard landing or a repair.")

        note("Two controls correct two different errors. If the markers are not correct in the " +
            "CENTER of the image, use \"Aim Offsets\". If the markers are correct in the center " +
            "but not correct near the EDGES, use \"Calibrate FOV\".")

        sub("When to do this")
        body("Do this one time for each aircraft. Do it again after these events:")
        bullet("The aircraft has a hard landing.")
        bullet("A person repairs or replaces the gimbal or the camera.")
        bullet("You use the app with a different aircraft.")

        sub("Before you start")
        bullet("Select a target that you can see clearly in the video image. A mark on a road " +
            "or a corner of a building is good. A tree is not good.")
        bullet("Make sure that you can find the same target on the map.")
        bullet("Load terrain data (DTED) for your area. See section 2.")
        bullet("Put the aircraft in a hover at about 200 ft above the ground.")

        warn("Do this procedure at the camera 25° down. At a steep angle a " +
            "small error moves the marker only a short distance. You cannot see the error, and " +
            "you cannot correct it.")

        sub("The procedure")
        bullet("1. Point the camera at the target. Read the GIMBAL line. Make the angle 25° " +
            "down.")
        bullet("2. Touch the crosshair. The app puts the quick marker on the target.")
        bullet("3. Look at the marker on your map or on the map of your team. Compare its " +
            "position with the true position of the target.")
        bullet("4. If the marker is correct, the calibration is complete. Stop here.")
        bullet("5. Touch and hold the AR button. Then select \"Aim Offsets\".")
        bullet("6. If the marker is too far from the aircraft, touch the minus button of " +
            "\"Pitch offset\".")
        bullet("7. If the marker is too near to the aircraft, touch the plus button of " +
            "\"Pitch offset\".")
        bullet("8. On the map, look from the aircraft to the target. If the marker is " +
            "clockwise from the target, touch the minus button of \"Bearing offset\".")
        bullet("9. If the marker is counter-clockwise from the target, touch the plus button " +
            "of \"Bearing offset\".")
        bullet("10. Touch \"Done\".")
        bullet("11. Touch and hold the crosshair. The marker moves to the new position.")
        bullet("12. Do steps 3 to 11 again until the marker is correct.")

        warn("Change one control at a time. If you change both controls together, you cannot " +
            "see which control corrects the error.")

        sub("A faster way, if you have a second TAK device")
        body("The aircraft sends its camera point to TAK about two times each second. On a " +
            "second TAK device you can watch this point while you change the offset, and see it " +
            "move onto the target. You do not drop a marker and compare each time — you tune the " +
            "offset and watch.")
        bullet("1. Point the camera at the target. Make the gimbal 25° down.")
        bullet("2. On the second TAK device, find the aircraft camera point. Its name ends with " +
            "\"-SPI\".")
        bullet("3. Touch and hold the AR button. Select \"Aim Offsets\".")
        bullet("4. Change Bearing until the point is on the correct line from the aircraft to " +
            "the target. Change Pitch until the point is at the correct distance. The point " +
            "moves a few seconds after each change.")
        bullet("5. Change one control at a time. Stop when the point is on the target.")
        note("This is often the fastest method. A small, clear target makes it easy to see the " +
            "point line up.")

        note("The app keeps these values. You do not do this procedure again for each flight. " +
            "To remove the correction, open \"Aim Offsets\" and touch \"Reset to 0\".")

        sub("After the calibration")
        body("Do a test at a different angle. Point the camera 40° to 50° down and put a " +
            "marker. The marker must also be correct at this angle. If it is correct at one " +
            "angle but not correct at the other angle, the correction is not complete.")

        warn("Do not use a value of more than 2°. A large value shows a mechanical problem. " +
            "Examine the gimbal and the camera before you fly again.")

        note("If the crosshair ring is red, the app does not put a marker. Point the camera " +
            "down more.")
    }

    // Flight path records (v1.5.9). A DESCRIPTION of automatic behaviour, not a control
    // reference — there is no switch, and the section says so first, because a pilot who
    // reads about a new function will otherwise go looking for its setting.
    private fun sectionFlightRecords() {
        section("5. Flight path records")
        body("The app records the path of each flight. The recording is automatic. There is " +
            "no switch, and there is nothing to start or stop.")

        sub("When the app records")
        bullet("The recording starts when the aircraft leaves the ground.")
        bullet("The recording stops when the aircraft is on the ground for 10 seconds. A " +
            "short touch on the ground does not divide the flight into two records.")
        bullet("A TAK server is not necessary. A network is not necessary. The app records " +
            "each flight also when the controller is fully offline.")

        sub("Where the records are")
        body("Open Downloads/TAKPilotFlights on the controller. Each flight makes two files " +
            "with the same name:")
        bullet(".gpx — the track. Import it into ATAK or Google Earth to see the flight " +
            "path on a map.")
        bullet(".csv — a table with one row each second: time, position, altitude, speed, " +
            "heading, battery and satellite count. Open it in a spreadsheet.")

        note("The folder keeps approximately 50 MB — months of flights. When it is full, " +
            "the app deletes the oldest files. Copy a record to a different location if you " +
            "must keep it permanently.")
        note("If the app stops during a flight, the record is safe. The track file appears " +
            "the next time you start the app.")
        bullet("No GPS, no points. When the aircraft flies without a GPS position, the app " +
            "records nothing for that time. It does not write a false position.")
    }

    private fun sectionSix() {
        section("6. What this build cannot do")
        body("All the controls on the flight screen operate on the EVO II. One function is " +
            "not available:")

        bullet("A different map. The small map is the street map only. You cannot select a " +
            "satellite image or a different map source.")
        bullet("A download of the map before you fly. The app keeps only the map that it has " +
            "shown you. Fly the area one time with a connection, or keep a connection.")

        warn("Examine each control on your first flight with a new aircraft. The controls " +
            "were flown and passed a full flight test on 3 August 2026.")
    }

    // ------------------------------------------------------- content builders

    private fun title(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE); textSize = 24f
        setTypeface(null, android.graphics.Typeface.BOLD)
    })

    private fun lede(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#B0B0B0")); textSize = 14f
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
        setTextColor(Color.parseColor("#9AC4FF")); textSize = 15f
        setTypeface(null, android.graphics.Typeface.BOLD)
        letterSpacing = 0.03f
        setPadding(0, dp(18), 0, dp(6))
    })

    private fun body(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#CFCFCF")); textSize = 14f
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(0, 0, 0, dp(8))
    })

    private fun bullet(text: String) = content.addView(TextView(this).apply {
        this.text = "•  $text"
        setTextColor(Color.parseColor("#CFCFCF")); textSize = 14f
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(dp(8), 0, 0, dp(6))
    })

    /** Neutral aside — worth knowing, not a hazard. */
    private fun note(text: String) = calloutView(text, "#9AC4FF", "#14202C")

    /** Something that can bite you in the air or on the ground. */
    private fun warn(text: String) = calloutView(text, "#EF5350", "#2A1616")

    private fun calloutView(text: String, barColor: String, bgColor: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor(bgColor))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(4); bottomMargin = dp(10)
            }
        }
        row.addView(View(this).apply {
            setBackgroundColor(Color.parseColor(barColor))
            layoutParams = LinearLayout.LayoutParams(dp(3), MATCH)
        })
        row.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#D8D8D8")); textSize = 13f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        })
        content.addView(row)
    }

    private fun divider() = content.addView(View(this).apply {
        setBackgroundColor(Color.parseColor("#333333"))
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
            setBackgroundColor(Color.parseColor("#202024"))
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
                    setTextColor(Color.parseColor("#9A9A9A")); textSize = 11f
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
                    setBackgroundColor(Color.parseColor("#101014"))
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
            setTextColor(Color.parseColor("#CFCFCF")); textSize = 13f
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        for (c in caveats) {
            card.addView(TextView(this).apply {
                text = "!  $c"
                setTextColor(Color.parseColor("#E8B04B")); textSize = 12f
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
