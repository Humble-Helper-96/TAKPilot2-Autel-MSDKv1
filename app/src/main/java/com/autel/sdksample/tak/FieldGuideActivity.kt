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
 * **The icon examples are live views, not pictures.** Each one is the real toolbar widget —
 * [BatteryGaugeView], [LiveToggleView], the TAK badge with its status dot — constructed here and
 * driven into the state being described. Screenshots or hand-drawn copies would silently go
 * stale the next time an icon changes; these can't, because they ARE the icons.
 *
 * **Every control the blueprint has is documented, including the ones that don't work yet.**
 * The flight screen deliberately carries the full DJI toolbar so a pilot isn't learning two
 * layouts, with the not-yet-connected controls giving an explanatory toast when pressed. This
 * guide mirrors that: each such control keeps its own entry, marked "NOT WORKING YET" in the
 * heading and describing what it will do, and section 4 repeats them as one pre-flight scan
 * list. Two of them (the greyed signal bars and the inert REC badge) can actively mislead if
 * mistaken for live readings, so those carry a warning rather than a note.
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
        lede("How this app works and what every control on the flight screen does. " +
            "Written to be read on the tailgate, not at a desk. This is the EVO II build.")

        sectionOne()
        sectionTwo()
        sectionThree()
        sectionFour()

        divider()
        body("Anything in this guide that doesn't match what you see on the aircraft — trust " +
            "the aircraft, then tell whoever maintains the app.")
        spacer(24)
    }

    // ---------------------------------------------------------------- Section 1

    private fun sectionOne() {
        section("1. What this app is for")
        body("TAKPilot2 flies your Autel EVO II while putting what it sees onto your team's " +
            "shared TAK map, live. Three things are happening at once:")

        bullet("Your aircraft appears on everyone's TAK map as it flies, with its position, " +
            "heading and altitude updating continuously.")
        bullet("Where the camera is pointing is also shared — so the team can see not just " +
            "where the drone is, but what it's looking at on the ground.")
        bullet("You can drop markers on what you're seeing, and they appear on everyone " +
            "else's screen within seconds.")

        spacer(10)
        body("On top of that it can push live video to a server your team can watch, show " +
            "other operators' TAK markers on your own map, and warn you about FAA altitude " +
            "limits where you're flying.")

        note("This app does not replace Autel's own app for firmware updates, compass or " +
            "gimbal calibration, or aircraft registration. Do those first, in Autel's app.")

        note("Flying itself is done with the controller's sticks, exactly as normal — this " +
            "app does not sit between you and the aircraft for stick input or the controller's " +
            "own RTH button.")

        note("The whole point is the shared picture. If the TAK badge on the flight screen is " +
            "showing red, you are flying blind to your team — the aircraft still flies normally, " +
            "but nothing you do is reaching anyone else.")
    }

    // ---------------------------------------------------------------- Section 2

    private fun sectionTwo() {
        section("2. Pre-Flight Setup")
        body("Everything here is set on the ground and remembered between flights. You " +
            "normally touch it once when you set the aircraft up, then only when something " +
            "changes — a new area, a new server, a new job.")

        sub("1 · Drone Settings")
        body("Safety limits pushed to the aircraft each time it connects. All in feet.")
        bullet("Max altitude — how high the aircraft will let you go.")
        bullet("Max distance — how far from the home point it will let you go. At the " +
            "boundary the aircraft stops and holds. It does not turn around by itself.")
        bullet("RTH altitude — the height it climbs to before flying home. Set this above " +
            "the tallest thing between you and the aircraft.")
        bullet("If the signal is lost — what the aircraft does on its own if it loses the " +
            "controller: Return Home, Hover, or Land. This is the aircraft's own behaviour, " +
            "so it still works even if the controller dies mid-flight. Return Home is the " +
            "normal choice.")
        note("Leave a number blank to keep whatever the aircraft is already set to.")

        sub("2 · Map Display")
        body("Which map the small map on the flight screen draws: Street, or a custom map " +
            "source if your team runs its own. Press Save Map Display after changing it; it " +
            "takes effect next time you enter the flight screen.")
        note("There is no satellite/hybrid option in this build. If you want imagery, point " +
            "Custom at a tile source your team is licensed to use.")

        sub("3 · TAK Server Connection")
        body("Where your team's TAK server is and who you are on it. Fill in the address, " +
            "the two ports, your username and password, and the callsign your aircraft should " +
            "show up as, then press Enroll & Connect. You should only need to do this once " +
            "per server.")
        body("Below that is the channel list — the groups your login belongs to. Whichever " +
            "you tick are the ones that receive this aircraft's position and your markers. " +
            "Tick none and the server decides.")

        sub("4 · Video Streaming")
        body("Optional. If your team runs a video server, this is where its address, port, " +
            "the broadcast name for this aircraft, and the login go. There's a low-bandwidth " +
            "option that sends a smaller, smoother picture — turn it on if the link is " +
            "marginal.")
        note("Setting this up here doesn't start streaming. You start and stop it in flight " +
            "with the LIVE button.")

        sub("5 · Elevation Data (DTED)")
        body("Terrain data for your flight area, imported as a file per region. This is what " +
            "lets the app know how high the ground is under the aircraft.")
        body("It matters for two things you'll actually notice: markers you drop land in the " +
            "right place instead of being thrown long or short over sloping ground, and your " +
            "altitude readout becomes true height above the ground rather than height above " +
            "where you took off.")

        sub("6 · FAA Airspace Ceilings")
        body("Downloads the FAA's published UAS Facility Map altitudes for an area so the " +
            "flight screen can show you the ceiling where you're flying. Enter a centre point " +
            "and a radius — or press Use My Location — check the size, then download.")
        note("Do this at home on wifi. It is read from the controller in flight and needs no " +
            "signal once downloaded.")
        warn("This is advisory. It shows the altitude the FAA is likely to approve, which is " +
            "NOT the same as having an approval. It also goes out of date as the FAA revises " +
            "the maps. You are still responsible for your own airspace authorisation.")
    }

    // ---------------------------------------------------------------- Section 3

    private fun sectionThree() {
        section("3. The Flight Screen")
        body("Live camera fills the screen. The toolbar runs across the top: status on the " +
            "left, things you press on the right. Below, each control in the order you'll " +
            "find it.")

        sub("Toolbar — left side (status)")

        entry(
            listOf(icon(R.drawable.ic_menu) to "Menu"),
            "Menu",
            "Leaves the flight screen and goes back to the home screen. The aircraft keeps " +
                "flying and stays connected to TAK — this only closes the screen.",
        )

        entry(
            listOf(
                takBadge(connected = true) to "Connected",
                takBadge(connected = false) to "Not connected",
            ),
            "TAK connection",
            "Green dot means your aircraft is on the team's TAK map right now. Red means it " +
                "isn't — you're flying, but nobody else can see it. Tap it to check the " +
                "current state.",
        )

        entry(
            listOf(
                battery(85) to "85%",
                battery(24) to "24%",
                battery(9) to "9%",
            ),
            "Battery",
            "Charge left in the aircraft, as a ring that empties as you fly. The ring is " +
                "banded like a fuel gauge: green down to about a third, amber below that, red " +
                "under 15%. Land on amber, not on red.",
        )

        entry(
            listOf(
                signal(90) to "Strong",
                signal(60) to "Usable",
                signal(null) to "No data",
            ),
            "Controller signal  — NOT WORKING YET",
            "On the Mini 2 build this shows the strength of the link between the controller " +
                "and the aircraft. On the EVO II it currently sits greyed out with \"—%\" " +
                "beside it, because this app can't yet read a signal strength from the " +
                "aircraft. Tap it and it will tell you the same.",
            listOf(
                "Use the controller's OWN signal indicator to judge your link. Do not read " +
                    "the greyed-out bars here as \"no signal\" — they mean \"not measured\".",
            ),
        )

        entry(
            listOf(
                gps(hasFix = true) to "Fix",
                gps(hasFix = false) to "No fix",
            ),
            "GPS satellites",
            "How many satellites the aircraft can see. Wait for a healthy count before taking " +
                "off — without a fix the aircraft can't hold position, can't set a home point, " +
                "and won't come home reliably.",
        )

        entry(
            listOf(
                image(R.drawable.ic_rth_home_set) to "Home set",
                image(R.drawable.ic_rth) to "No home yet",
            ),
            "Return to Home",
            "Tap to send the aircraft home; it asks you to confirm first.\n\n" +
                "The house turns green once a home point has been set — that's your " +
                "confirmation the aircraft actually has somewhere to return to.\n\n" +
                "Press and hold to move the home point to where YOU are standing now — it " +
                "uses the controller's own GPS, not the aircraft's. Useful if you've walked " +
                "or driven away from where you took off. It shows you the coordinates and " +
                "asks first, because this changes where the aircraft will fly when it comes " +
                "home.",
            listOf(
                "It refuses if the controller has no GPS fix rather than guessing a position. " +
                    "If it does that, get a fix before relying on RTH — the aircraft will " +
                    "still return to its ORIGINAL home point, which may not be where you are.",
                "Check the coordinates in the confirmation against where you actually are. A " +
                    "stale controller fix would send the aircraft to where you were, not " +
                    "where you now stand.",
                "The controller's own RTH button works as it always has, independently of " +
                    "this one.",
            ),
        )

        sub("Toolbar — right side (actions)")

        entry(
            listOf(image(R.drawable.ic_drop_pin) to "Drop marker"),
            "Drop a marker",
            "Puts a marker on the ground at the centre of the camera view — aim the aircraft " +
                "at what you want to mark, then tap. You pick the type (Friendly, Hostile, " +
                "Neutral, Unknown) and it goes out to the whole team.\n\n" +
                "Press and hold to open your list of dropped markers. Each one shows how far " +
                "and in which direction it is from the aircraft, and tapping it lets you move " +
                "it to wherever the camera is now pointing, rename it, change its type, send " +
                "it again, or remove it. Clear All removes the lot.",
            listOf(
                "If the aircraft doesn't have GPS and gimbal position yet, it refuses to " +
                    "drop rather than guess a location.",
                "Moving, renaming or re-typing a marker UPDATES it on everyone else's screen " +
                    "— it doesn't leave the old one behind.",
                "Removing a marker only clears it from YOUR screen. It stays on everyone " +
                    "else's until it ages out on its own, about 14 hours. Same for Clear All.",
            ),
        )

        entry(
            listOf(arPill(on = false) to "Off", arPill(on = true) to "On"),
            "AR — markers on the video  — NOT WORKING YET",
            "On the Mini 2 build this draws markers onto the live picture where the things " +
                "themselves are, so you can see which building or vehicle a marker refers to " +
                "instead of working it out from the map.\n\n" +
                "The button is here on the EVO II so the toolbar matches, but pressing it just " +
                "tells you it isn't wired up yet.",
        )

        entry(
            listOf(image(R.drawable.ic_camera_shutter) to "Photo"),
            "Photo  — NOT WORKING YET",
            "On the Mini 2 build this takes a still photo to the card in the aircraft. Not " +
                "wired up on the EVO II yet — pressing it says so.",
        )

        entry(
            listOf(zoomPill("1X") to "Normal", zoomPill("2X") to "Zoomed"),
            "Zoom  — NOT WORKING YET",
            "On the Mini 2 build this switches the camera between normal and 2x, changing the " +
                "actual picture everyone watching sees. Not wired up on the EVO II yet — it " +
                "stays showing 1X and pressing it says so.",
        )

        entry(
            listOf(image(R.drawable.ic_resync) to "Re-sync"),
            "Video re-sync  — NOT WORKING YET",
            "On the Mini 2 build this cleans up a video picture that has built up smearing or " +
                "blocky patches. Not wired up on the EVO II yet — pressing it says so.",
            listOf(
                "If the picture does go bad on this build, leaving and re-entering the flight " +
                    "screen rebuilds the video.",
            ),
        )

        entry(
            listOf(
                live(LiveToggleView.State.OFF) to "Off",
                live(LiveToggleView.State.LIVE) to "Streaming",
            ),
            "LIVE — video streaming",
            "Starts and stops sending live video to your team's video server. Needs the " +
                "server details filled in under Pre-Flight Setup first. This one works.",
        )

        entry(
            listOf(
                rec(recording = false) to "Stopped",
                rec(recording = true) to "Recording",
            ),
            "REC — record to the aircraft  — NOT WORKING YET",
            "On the Mini 2 build this records full-quality video to the card in the aircraft, " +
                "separately from streaming. Not wired up on the EVO II yet — it stays showing " +
                "stopped and pressing it says so.",
            listOf(
                "Because it can't read the aircraft's real recording state either, do not " +
                    "treat \"stopped\" here as proof the aircraft isn't recording. Use Autel's " +
                    "own app or the controller to check.",
            ),
        )

        sub("On the video itself")

        entry(
            emptyList(),
            "The crosshair",
            "Marks the centre of the camera view — the exact spot a dropped marker will land " +
                "on. Think of it as where the aircraft is looking.\n\n" +
                "The ring in the middle changes colour to tell you how accurate a marker " +
                "dropped right now would be. It follows how steeply the camera is tilted down, " +
                "which you can also read on the GIMBAL line in the readout. The exact angles " +
                "depend on whether you've loaded terrain data (DTED) for where you're flying:\n\n" +
                "WITH terrain data loaded —\n" +
                "GREEN: 25° down or steeper. Roughly ±10 ft on the ground.\n" +
                "YELLOW: 10° to 25° down. Roughly ±50 ft.\n\n" +
                "WITHOUT terrain data —\n" +
                "GREEN: 30° down or steeper. Roughly ±50 ft on the ground.\n" +
                "YELLOW: 15° to 30° down. Roughly ±100 ft.\n\n" +
                "RED — shallower than the yellow range either way. Too flat to trust; get " +
                "steeper or fly closer before dropping a marker.\n\n" +
                "The reason is geometry: the flatter the camera looks, the further along the " +
                "ground a small aiming error slides the marker. Looking steeply down at " +
                "something is far more precise than marking it from across the valley — so if " +
                "a marker's position matters, fly closer and tilt down rather than viewing it " +
                "from a distance.",
            listOf(
                "Those figures assume a good GPS fix. A weak fix, or hovering near large " +
                    "metal structures, will be worse than that at any angle.",
                "It's the terrain data at your aircraft's CURRENT position that matters here, " +
                    "not just whether you've loaded any for the area — flying past the edge of " +
                    "your downloaded coverage switches the ring to the without-terrain-data " +
                    "thresholds.",
                "Those angle figures were measured on the Mini 2. They are the right shape " +
                    "for this aircraft too, but they have not yet been re-checked against the " +
                    "EVO II's own camera and gimbal — treat them as a guide, not a promise, " +
                    "until they have.",
            ),
        )

        entry(
            emptyList(),
            "Quick marker — tap the crosshair  — NOT WORKING YET",
            "On the Mini 2 build, tapping the crosshair itself drops a single reusable " +
                "\"what I'm looking at right now\" marker, moved by pressing and holding rather " +
                "than dropping a second one.\n\n" +
                "Not wired up on the EVO II yet — tapping the crosshair says so. Use the " +
                "drop-marker button in the toolbar instead; it works, lets you set the type, " +
                "and its press-and-hold list can move a marker to wherever you're now looking.",
        )

        entry(
            emptyList(),
            "Exposure slider (top right)  — NOT WORKING YET",
            "On the Mini 2 build this makes the picture brighter or darker when the camera's " +
                "automatic exposure gets it wrong, with the camera's chosen ISO and shutter " +
                "shown underneath.\n\n" +
                "The slider is here on the EVO II so the screen matches, but it doesn't yet " +
                "change anything — sliding it says so, and the ISO/shutter line stays at " +
                "\"—\".",
        )

        entry(
            emptyList(),
            "The readout (right-hand side)",
            "Top to bottom: your aircraft's callsign and speed; its latitude and longitude; " +
                "how far and in which direction it is from the home point; its height above " +
                "the ground and above sea level; how far the camera is tilted down; and the " +
                "aircraft and TAK connection state.",
            listOf(
                "The height line reads AGL when terrain data covers where you are, meaning " +
                    "true height above the ground below the aircraft.",
                "It reads ALT instead when there's no terrain data — that's height above " +
                    "where you took off, which is a different number as soon as the ground " +
                    "rises or falls beneath you.",
                "MSL is height above sea level, the figure aviation charts and airspace " +
                    "floors use. It needs terrain data for your takeoff point, so it shows " +
                    "\"—\" until that's loaded. It can be showing a number while the line " +
                    "above still says ALT — the two are worked out separately.",
            ),
        )

        entry(
            emptyList(),
            "FAA ceiling line",
            "Shown only if you've downloaded FAA data. It tells you the published ceiling " +
                "where the aircraft currently is, and turns red if you climb above it.\n\n" +
                "It is labelled AGL because FAA ceilings are always height above the ground — " +
                "not above sea level. Compare it against the AGL line in the readout above, " +
                "never against the MSL line.\n\n" +
                "\"Class G\" in grey means the FAA publishes no facility map there, so the " +
                "ordinary 400 ft limit applies. Amber \"no data here\" means you have flown " +
                "outside the area you downloaded and the app genuinely doesn't know — don't " +
                "read that as permission.",
        )

        entry(
            emptyList(),
            "The map",
            "Small map in the bottom right, always north-up and centred on the aircraft. It " +
                "does not pan or zoom — that's deliberate, so it always shows the same thing " +
                "without needing attention. The red line runs from the home point to the " +
                "aircraft: that's your way back. Other operators' TAK markers appear here too; " +
                "tap one to clear it off your own map without affecting anyone else's.",
        )
    }

    // ---------------------------------------------------------------- Section 4

    /**
     * Consolidated "not working yet" list. Each of these is also flagged on its own entry
     * above; repeating them in one place gives a pilot a single thing to scan before a flight
     * rather than re-reading the whole of section 3.
     */
    private fun sectionFour() {
        section("4. What isn't working yet")
        body("The screen deliberately shows the same controls as the Mini 2 version so you " +
            "aren't learning two different layouts. These ones are present but not yet " +
            "connected on the EVO II — pressing any of them tells you so:")

        bullet("Controller signal bars — greyed out, showing \"—%\".")
        bullet("AR — markers drawn onto the live video.")
        bullet("Photo.")
        bullet("Zoom — stays on 1X.")
        bullet("Video re-sync.")
        bullet("REC — stays showing stopped.")
        bullet("Exposure slider, and the ISO/shutter line under it.")
        bullet("Tapping the crosshair for a quick marker.")
        bullet("Satellite/hybrid map imagery (Pre-Flight Setup → Map Display).")

        warn("Two of those can mislead you if you forget they're inert. The greyed signal " +
            "bars are NOT telling you the link is bad — they're telling you nothing at all; " +
            "judge your link on the controller. And REC showing stopped is NOT proof the " +
            "aircraft isn't recording.")

        note("Nothing in that list affects flying the aircraft or the shared TAK picture — " +
            "position, camera look-point, marker drops, video streaming, the terrain-corrected " +
            "altitude and the FAA ceilings all work here.")
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
