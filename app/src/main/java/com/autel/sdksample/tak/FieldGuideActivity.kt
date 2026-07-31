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
 * section 4, which exists as a single pre-flight scan rather than making a pilot re-read
 * section 3. Section 4 has no counterpart in the DJI guide — that build has no gaps left to
 * list — so keep it whenever the two files are reconciled.
 */
class FieldGuideActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_field_guide)
        AppLog.v(TAG, "field guide opened")
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        content = findViewById(R.id.fieldGuideContent)

        title("TAKPilot2 Field Guide")
        lede("This guide shows what the app does. It also shows what each control on the " +
            "flight screen does. Read it before you fly. This is the EVO II build.")

        sectionOne()
        sectionTwo()
        sectionThree()
        sectionFour()

        divider()
        body("If this guide does not agree with the aircraft, obey the aircraft. Then tell " +
            "the person who maintains the app.")
        spacer(24)
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

        note("You fly the aircraft with the sticks on the controller, as usual. This app does " +
            "not change the sticks. It does not change the RTH button on the controller.")

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

        sub("1. Drone Settings")
        body("The app sends these safety limits to the aircraft at each connection. All " +
            "values are in feet.")
        bullet("Max altitude - the maximum height the aircraft lets you fly.")
        bullet("Max distance - the maximum distance from the home point. At this limit the " +
            "aircraft stops and holds its position. It does not come back without your " +
            "command.")
        bullet("RTH altitude - the height the aircraft climbs to before it flies home. Set " +
            "this height more than the highest obstacle between you and the aircraft.")
        bullet("If the signal is lost - the action of the aircraft if it loses the " +
            "controller: Return Home, Hover or Land. The aircraft does this action without " +
            "the app. It works if your controller stops during the flight. Usually, select " +
            "Return Home.")
        note("To keep the value that is already in the aircraft, leave the field empty.")

        sub("2. Map Display")
        body("This sets the map type for the small map on the flight screen. Select Street, " +
            "or a custom map of your team. Then touch Save Map Display. The new map shows " +
            "when you go to the flight screen again.")
        note("This build has no satellite or hybrid map. For images, set Custom to a map " +
            "source that your team has a licence to use.")

        body("The app keeps the map images that it shows. Thus the map operates again in the " +
            "same area without a connection. The app keeps 2 GB of images. When the space is " +
            "full, the app removes the oldest images first.")
        body("The lower part of this section downloads an area before you fly. Use it for " +
            "ground that the aircraft did not fly over. Type a center point and a radius, or " +
            "touch Use My Location. Touch Check Size, then touch Download Area.")
        note("Download the area on a wifi connection before you go to the flight area. Check " +
            "the size first. If you make the radius two times larger, the app downloads four " +
            "times more data.")
        note("For the Street map, the app asks you to confirm first. OpenStreetMap gives " +
            "their maps at no cost and asks apps not to download large areas. If they stop " +
            "this controller, the Street map does not operate here until they permit it " +
            "again. Download only the area that you fly. A custom map of your team has no " +
            "such limit.")
        warn("Do not fly in a new area with no connection and no downloaded map. The map " +
            "shows empty squares where it has no images. The aircraft flies correctly, but " +
            "you do not see your position on a map.")

        sub("3. TAK Server Connection")
        body("These fields set the address of the TAK server of your team and your identity " +
            "on it. Type the address, the two ports, your username and your password. Type " +
            "the callsign for your aircraft. Then touch Enroll & Connect. Usually you do this " +
            "one time for each server.")
        body("The channel list is below these fields. These are the groups for your login. " +
            "The channels you select receive the position of the aircraft and your markers. " +
            "If you select no channel, the server selects the channels.")

        sub("4. Video Streaming")
        body("This section is optional. If your team has a video server, type its address, " +
            "its port, the video name for this aircraft, and the login. Then select the " +
            "quality: Low, Standard or High. A low quality works better on a weak connection. " +
            "Usually, select Standard. If the connection is weak, select Low.")
        note("These settings do not start the video. Use the LIVE button in flight to start " +
            "and stop the video.")

        sub("5. Elevation Data (DTED)")
        body("This is the terrain data for your flight area. You import one file for each " +
            "region. The data tells the app the height of the ground below the aircraft.")
        body("The terrain data improves two functions:")
        bullet("Markers go to the correct position. Without the data, a marker on a slope " +
            "can be too near or too far.")
        bullet("The altitude shows the true height above the ground. Without the data, it " +
            "shows the height above your takeoff point.")

        sub("6. FAA Airspace Ceilings (UASFM)")
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
                "aircraft. Touch the icon to see the current state.",
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
                "If you move, rename or change the type of a marker, the app changes the " +
                    "same marker on the screens of your team. It does not make a second one.",
                "If you delete a marker, the app removes it from your screen only. It stays " +
                    "on the screens of your team for about 14 hours. Clear All is the same.",
            ),
        )

        entry(
            listOf(arPill(on = false) to "Off", arPill(on = true) to "On"),
            "AR: markers on the video",
            "This function draws the markers on the live image at their true positions. You " +
                "can then see which building, vehicle or hill a marker identifies. The button " +
                "becomes green when the function is on.\n\n" +
                "A marker outside the camera image shows as a small arrow at the edge of the " +
                "image. The arrow shows the direction to turn the camera.\n\n" +
                "Touch and hold the button to select what the app draws:\n" +
                "- My Markers\n" +
                "- Team Markers\n" +
                "- Team Positions\n" +
                "- Air Traffic\n" +
                "- Weather\n\n" +
                "You can also set the range for air traffic to 2.5, 5 or 15 miles. If you " +
                "set an item to off, the app removes it from the image immediately. The app " +
                "always shows ground markers to 5 miles.",
            listOf(
                "If you move the camera quickly, the markers move on the image. They become " +
                    "correct when you stop. This is normal, because the position data and the " +
                    "video do not arrive at the same time.",
                "This function shows which object a marker identifies. It does not give an " +
                    "accurate position. For an accurate position, put the crosshair on the " +
                    "object and put a marker.",
                "No person measured the camera direction and the field of view of the EVO II " +
                    "in flight. The markers can show away from their targets. To correct " +
                    "this, use the Calibrate FOV control in the touch-and-hold menu.",
            ),
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
            "This button changes the camera between the normal view and the 2X view. It " +
                "changes the camera image. Your team sees the same view in the video.",
        )

        entry(
            listOf(image(R.drawable.ic_resync) to "Re-sync"),
            "Video re-sync",
            "This button corrects the video image. Blocks or marks can occur in the image, " +
                "usually when the camera looks at the same scene for a long time. If this " +
                "occurs, touch the button. The image is black for a moment, then it becomes " +
                "correct. This changes your image only. The aircraft continues to fly and the " +
                "video to your team does not stop.",
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
                "RED: less than the yellow angle. Do not put a marker. Point the camera down " +
                "more, or fly nearer.\n\n" +
                "When the camera is near horizontal, a small error in the angle moves the " +
                "marker a long distance on the ground. A steep angle is more accurate than a " +
                "view from a long distance. If the position of a marker is important, fly " +
                "nearer and point the camera down. Do not use the zoom from a long distance. " +
                "Without terrain data, the app must calculate with flat ground, and this " +
                "adds more error.",
            listOf(
                "These values are correct only with a good GPS position. A weak GPS position " +
                    "or large metal structures near the aircraft cause more error at all " +
                    "angles.",
                "The app uses the terrain data at the current position of the aircraft. If " +
                    "you fly out of the area of your data, the ring changes to the angles for " +
                    "no terrain data.",
                "A person measured these angles on a Mini 2 aircraft. No person measured them " +
                    "on the EVO II. Use them as a guide until a person measures them again.",
            ),
        )

        entry(
            emptyList(),
            "Quick marker: touch the crosshair",
            "Touch the crosshair to put a marker immediately. The app does not ask you " +
                "questions. The type is always Unknown and the name is always " +
                "${TakDropMarkers.QUICK_NAME}. Your team can identify it quickly.\n\n" +
                "There is only one quick marker. To move it, point the camera at the new " +
                "target and touch and hold the crosshair. The marker moves on the screens of " +
                "all your team. If you touch the crosshair again, the app does not put a " +
                "second marker.\n\n" +
                "Use the quick marker to show your team what you look at now. To keep a " +
                "record of a position, use the marker button. With that button you can set a " +
                "name and a type.",
            listOf(
                "To remove the quick marker, delete it from the marker list. Touch and hold " +
                    "the marker button to open the list. Then you can put a new quick marker.",
                "The quick marker has the same rules as other markers. If you delete it, the " +
                    "app removes it from your screen only. It stays on the screens of your " +
                    "team.",
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
                "- the angle of the camera\n" +
                "- the state of the aircraft and of the TAK connection",
            listOf(
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
            "This line shows only if you downloaded the FAA data. It shows the published " +
                "ceiling at the position of the aircraft. It becomes red if you fly above " +
                "the ceiling.\n\n" +
                "The line shows AGL, because FAA ceilings are always heights above the " +
                "ground. Compare this value with the AGL line of the readout. Do not compare " +
                "it with the MSL line.\n\n" +
                "Grey \"Class G\" shows that the FAA has no facility map at this position. " +
                "The usual limit of 400 ft is applicable. Yellow \"no data here\" shows that " +
                "you flew out of the area of your data. The app does not know the limit. " +
                "This is not an approval to fly.",
        )

        entry(
            emptyList(),
            "The map",
            "This is the small map in the bottom right corner. North is always at the top " +
                "and the aircraft is always in the center. The map does not move and does " +
                "not zoom. Thus it always shows the same view and you do not adjust it. The " +
                "red line goes from the home point to the aircraft, and shows your route " +
                "back. The map also shows the TAK markers of other operators. Touch a marker " +
                "to remove it from your map only.",
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
    private fun sectionFour() {
        section("4. What this build cannot do")
        body("All the controls on the flight screen operate on the EVO II. One function is " +
            "not available:")

        bullet("Satellite and hybrid map images (Pre-Flight Setup, Map Display). You can use " +
            "the street map or one custom map source.")

        warn("Examine each control carefully on the first flight. No person flew these " +
            "controls with an aircraft: the photo, the zoom, the record function, the " +
            "exposure, the signal bars, the video re-sync, the AR image and the quick marker.")
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
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#202024"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
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
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        // Same values the flight screen tints these with, so a state shown here is the state
        // the pilot will actually see.
        private val CONNECTED_GREEN = 0xFF4CAF50.toInt()
        private val DISCONNECTED_RED = 0xFFF44336.toInt()
        private val NO_FIX_GREY = 0xFFAAAAAA.toInt()
    }
}
