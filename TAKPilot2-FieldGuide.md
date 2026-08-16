<!--
Verbatim copy of the in-app Pilot Field Guide (Home > FIELD GUIDE).

GENERATED FROM SOURCE. The source of truth is
app/src/main/java/com/autel/sdksample/tak/FieldGuideActivity.kt.

Regenerate with:
    python3 tools/generate_field_guide_md.py

TO EDIT THE WORDING: mark this file up freely, then have the edits applied back to
FieldGuideActivity.kt and regenerate. Edits made here alone do NOT reach the
aircraft - this file is a print of the app, not the app.

The guide text is written in ASD-STE100 Simplified Technical English. See the
KDoc on FieldGuideActivity for the rule set to hold edits to.

The quick-marker name shows as E419 here. The app draws one callsign per install
from a pool of 24, so each controller shows its own.

App version: 1.6.0
-->

# TAKPilot2 Field Guide

*What each control does, on the screen and on the controller. Read it before you fly. This is the EVO II build.*

## 1. What this app is for

TAKPilot2 flies your Autel EVO II. At the same time it puts the aircraft on the shared TAK map of your team.

- Your team sees the position, the heading and the altitude of the aircraft.
- Your team sees the point on the ground where the camera looks.
- You put markers on what you see. Your team gets them in a few seconds.
- The quick marker is a single marker that is dropped at the press of a button and moves to a new location upon each subsequent press of the button.
- The app can send live video to a server of your team.

> **NOTE** — Do the firmware updates, the compass calibration, the gimbal calibration and the aircraft registration with the Autel app first. Do not do them here.

## 2. Pre-Flight Setup

Set these on the ground prior to flight. Change them for a new area, a new server or a new task.

### 1. Aircraft Settings

Max altitude, max distance and RTH altitude, in feet. The app sends them to the aircraft at each connection.

### 2. Video Streaming

Optional. Type the address of the video server of your team, the port, the broadcast ID and the login. Then select the quality. Select Standard. If the connection is weak, select Low. Select the codec H.264 for compatibility.

### 3. TAK Server Connection

Type the address of the TAK server, your username, your password and the callsign of the aircraft. Then touch Enroll & Connect.

Select the "Pull Channels" button to adjust the channels the aircraft data is published to.

### 4. Elevation Data (DTED)

The terrain data for your area. Import one file for each region. It improves two things:

- Marker accuracy.
- The altitude shows the true height of the aircraft above the ground. Without the data, it shows the height above your takeoff point.

### 5. FAA Airspace Ceilings (UASFM)

This downloads the FAA ceiling data for an area. The flight screen then shows the ceiling at the position of the aircraft.

## 3. The controller buttons

These buttons do the same functions as the buttons on the screen. They are the most commonly used features.

#### C1

- **Press** — Changes the camera between the normal camera and the thermal camera.
- **Press and hold** — Changes the thermal colours.

#### C2

- **Press** — Puts the quick marker. If the quick marker already exists, it MOVES to what the camera looks at now. The app sends it to the TAK server immediately.
- **Press and hold** — Puts a NEW static marker. This marker does not move. Each additional press and hold puts a new static marker.

#### Zoom rocker (right side)

- **Press** — Push it and release it. The zoom moves one level: 1X, 2X, 3X, 4X, 6X, 8X, 10X, 12X, 16X.
- **Press and hold** — Push it and hold it. The zoom moves through the levels. Release it to stop.

#### RTH button

- **Press** — Sends the aircraft home. This is a function of the controller.

## 4. The Flight Screen

The live camera image fills the screen. The toolbar is across the top. The status icons are on the left and the function buttons are on the right.

### Toolbar: left side (status)

#### Menu

*Icon states shown: Menu*

This button closes the flight screen and shows the home screen. The aircraft continues to fly and stays connected to TAK.

#### TAK connection

*Icon states shown: Connected · Not connected*

A green dot shows that your aircraft is on the TAK map of your team. A red dot shows that it is not on the map. Touch the icon to connect or disconnect.

#### Battery

*Icon states shown: 85% · 24% · 9%*

The charge in the battery of the aircraft. Land the aircraft when the ring is yellow. Do not wait for red.

#### Controller signal

*Icon states shown: Strong · Medium · Weak*

The strength of the signal between the controller and the aircraft. If the bars decrease, fly the aircraft nearer or move away from other sources of radio signals. If the aircraft loses the signal, it returns to home.

The bars are grey at "—%" until the aircraft connects. If they stay grey when the aircraft is connected, close the Autel Explorer app. Only one app at a time can read the signal.

#### GPS satellites

*Icon states shown: Position · No position*

The quantity of satellites that the aircraft receives. Green shows that the aircraft has its position. Wait for green before you take off. Without a position the aircraft cannot hold its position, cannot set a home point, and cannot come home correctly.

#### Return to Home

*Icon states shown: Home set · No home*

Touch: sends the aircraft home. The app asks you to confirm.

Touch and hold: moves the home point to your position. The app uses the GPS of the controller. Use this if you moved away from the takeoff point. The app shows the coordinates and asks you to confirm.

The house is green when the home point is set.

### Toolbar: right side (buttons)

#### Put a marker

*Icon states shown: Marker*

Touch: puts a marker at the center of the camera image. Point the camera at the target first. Select the type (Friendly, Hostile, Neutral or Unknown). The app names the marker and sends it to your team.

Touch and hold: opens the "Markers" list, with your markers and the markers of your team. Here you can change them or remove them. Clear All removes all your markers.

DELETE REMOVES A MARKER FROM THIS AIRCRAFT ONLY. It stays on the screens of your team for about 3 days.

#### AR: markers on the video

*Icon states shown: Off · On*

This draws the markers on the live image near their positions. The button is green when it is on. It is ON when you open the flight screen.

A marker outside the camera image shows as an arrow at the edge. The arrow shows the direction to turn the camera.

Touch and hold: adjust the filters and the range where markers show.

> **!** THE AR VIEW IS NOT ACCURATE FOR A POINT. It shows the general area of a marker. Do not use it to choose between objects that are close together, such as one house in a tight row of houses.

#### Photo

*Icon states shown: Photo*

This button takes a photo. The app saves the photo to the card in the aircraft, not to the controller.

#### Zoom

*Icon states shown: Normal · 2X view*

Touch: changes between 1X and 2X. Touch and hold: 4X. From 4X, a touch or a touch and hold goes back to 1X, so one touch always gets you to the widest view.

#### IR: the thermal camera

*Icon states shown: Thermal*

This changes the image between the normal camera and the thermal camera.

#### Exterior lights

*Icon states shown: Lights on · Lights off*

This turns the navigation lights of the aircraft on and off. Turn them off to make the aircraft difficult to see at night.

#### LIVE: video to your team

*Icon states shown: Off · Video on · Connects again*

This button starts and stops the live video to the video server of your team. First, set the server data in Pre-Flight Setup.

A yellow button that flashes shows that the connection stopped. The app tries to connect again without your command. Do not touch the button. If you touch it, the app stops and does not try again.

#### REC: record to the aircraft

*Icon states shown: Off · Records*

This button records video to the card in the aircraft. It is independent of the live video. You can use one function, both functions, or no function. The card keeps the full quality video without the HUD.

### On the video image

#### The crosshair

The crosshair is the center of the camera image, and the position where a marker goes. The colour of the ring shows the approximate accuracy. It changes with the angle of the camera.

WITH terrain data:  
GREEN: 25° down or more. The error is about 25 ft.  
YELLOW: 10° to 25° down. The error is about 50 ft.

WITHOUT terrain data:  
GREEN: 30° down or more. The error is about 50 ft.  
YELLOW: 15° to 30° down. The error is about 100 ft.

#### Quick marker: touch the crosshair

Touch the crosshair to put a marker immediately, with no questions. The name is always E419.

THERE IS ONLY ONE QUICK MARKER. Point the camera at a new location and touch the crosshair again: the marker MOVES to the new location, on the screens of all your team. Press the C2 button for the same function.

#### Static marker: touch and hold the crosshair

Touch the crosshair and hold it to put a static marker of the type Unknown. THIS MARKER DOES NOT MOVE. A second touch and hold puts a SECOND marker.

The name of the static marker is the callsign of the aircraft and a number, for example EVO2-07. Press and hold the C2 button for the same function.

#### FAA ceiling line

This line is above the small map. It shows the published ceiling at the position of the aircraft, and it becomes red if you fly above the ceiling.

FAA 300 ft AGL - the published ceiling here.  
Class G - no facility map here. The usual 400 ft limit is applicable.  
FAA --- ft AGL - the app does not know. You did not download the data, or you flew out of the area you downloaded.  
FAA - no fix - the aircraft has no position yet.

#### The map

The small map in the bottom right corner. The red line goes from the home point to the aircraft. The map also shows the markers of your team. There are two zoom levels, near and wide. Touch the map two times to make the map larger, and two times again to make it small.

## 5. How to correct the position of a marker

If the aim of the camera has a small error, the markers go to a position that is not correct. "Aim Offsets" corrects this. The error belongs to the aircraft, not to the app: a gimbal can move after a hard landing or a repair.

Do this one time for each aircraft. Do it again after a hard landing, after a repair of the gimbal or the camera, or when you use a different aircraft.

### Before you start

- Select a target that you see clearly in the video and can find on the map. Center the reticle on it. A mark on a road or a corner of a building is good. The aircraft sends its camera look point to TAK about two times each second. On a second TAK device, find that point (its name ends with "-SPI") and watch it while you change the offsets. Change the offsets until the SPI on the map lines up with the object the reticle points at in the video.
- The point moves a few seconds after each change. Change one control at a time. Stop when the point is on the target.

## 6. Flight path records

The app records the path of each flight automatically. There is no switch, and nothing to start or stop. A TAK server and a network are not necessary.

The recording starts when the aircraft leaves the ground. It stops when the aircraft is on the ground for 10 seconds, thus a short touch on the ground does not divide the flight into two records.

Open Downloads/TAKPilotFlights on the controller. Each flight makes two files:

- .gpx — the track. Import it into ATAK or Google Earth.
- .csv — one row each second: time, position, altitude, speed, heading, battery and satellite count. Open it in a spreadsheet.

---
