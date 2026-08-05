# TAKPilot2-Autel — Flight-Test Checklist

**Written in Simplified Technical English (ASD-STE100).**

## 1. Purpose

This checklist closes the items that you can only test when the aircraft is in the air. These are
the calibration values (gimbal, bearing, field of view, GPS units, HAE), the picture on a second TAK
client, and the recovery functions (RTH, link loss, background operation, RC buttons).

Each test tells you what to do. It tells you what to look at. It tells you the pass condition. If
the test does not pass, it tells you which control to change.

You can adjust most controls when you fly. Touch and hold the AR button on the flight screen. A menu
opens. It has "Aim Offsets…" and "Calibrate FOV…". The application keeps these values. You do not
build the application again.

## 2. Before you start

**Set the log controls.** Go to Debug Log. Set *Logging enabled*. Set *Detailed*. Set *Include
obstacle radar logs* to OFF. The radar logs fill the log file. Set them to ON only when you examine
the avoidance function.

**You must have two items in operation:**

1. The aircraft on the Smart Controller.
2. A second TAK client. Use ATAK on a telephone or WinTAK. It must use the same server. You then
   see what the team sees.

> **Safety.** Fly in an open area. Keep the aircraft in sight. Obey the limits that you set in
> Pre-Flight. The RTH test and the link-loss test put the aircraft into an automatic mode. Keep your
> hands at the sticks. You can then take control again. This checklist does not replace your
> judgement as the pilot in command.

## 3. Results of flight 1 (3 August 2026)

**The full checklist passed.** All the items passed: ground G1 to G6, airborne A1 to A7, and
recovery R1 to R6.

- **G6 (GPS units).** The raw `GPSInfo` showed 31 satellites, a 3D fix and a horizontal accuracy of
  approximately 0.7 m. Therefore `ACC_DIVISOR = 1000` is correct.
- **A4 (bearing).** A fixed aim offset stayed correct through a yaw of 180 degrees and then a
  further 90 degrees. Therefore the bearing does not change with the heading, and
  `BEARING_MODE_RELATIVE = false` is correct.
- **A3 (pitch sign).** Correct. Down gives a negative value. The SPI is in front of the aircraft and
  below it.
- **Aircraft-side observation.** The aircraft moved slowly in the hover. The raw GPS position was
  good, but the velocity data was noisy. `GPSSpeed` went to approximately 1 m/s when the aircraft
  almost did not move. This is multipath near buildings. It makes the position-hold loop unstable.
  The application reported the stable position correctly during this time. Test the position hold
  again in an open area.

## 4. Results of flight 2 (4 August 2026)

**The bearing offset is not a constant.** The camera aimed at one target from three headings. These
are the results:

| Heading | Range | Bearing error |
|---|---|---|
| 292 degrees | 225 m | 6.00 degrees |
| 264 degrees | 349 m | 4.49 degrees |
| 089 degrees | 358 m | 0.87 degrees |

The spread is 5.13 degrees. This is too large for an error of aim by the pilot. The error changes
with the heading of the aircraft. This is the signature of magnetometer error.

**Therefore one fixed bearing offset cannot correct this error.** Do a compass calibration of the
aircraft. Then do test A4 again.

The projection mathematics is correct. The drawn position of three markers was calculated again from
known coordinates. The result agreed with the application to one pixel.

## 5. Ground checks

Do these checks when the aircraft has power and is on the ground.

- [ ] **G1 · Start the application correctly.** Open TAKPilot from the home screen of the controller.
      Do not use a shortcut that goes directly to the flight screen. A direct entry gives a dead
      link. This is a strict rule.
      **Pass:** The home screen shows **TAK: Connected** and **AIRCRAFT: EVO_2**.

- [ ] **G2 · Callsign.** Open the contacts list on the second TAK client.
      **Pass:** Your callsign is in the list. Your login name is not.
      **If this fails:** Examine `TakForegroundService.callsignFor` and the `callsign` value in the
      `takpilot2_tak` preferences. Make sure that Pre-Flight saved the callsign.

- [ ] **G3 · Aircraft symbol.** Look at the map on the second client.
      **Pass:** The aircraft has an air-track symbol. It does not have a ground symbol. The CoT type
      `a-f-A-M-H-Q` controls this.

- [ ] **G4 · Limits.** In Pre-Flight, set the RTH altitude, the maximum altitude and the maximum
      distance. Then touch **Apply to Drone**. The minimum RTH altitude is 82 ft (25 m). This is a
      limit of the aircraft. The aircraft refuses a smaller value.
      **Pass:** The values that the aircraft reports agree with the values that you entered. The
      flight HUD shows the RTH altitude as a number. It does not show amber `RTH --`.

- [ ] **G5 · Exterior lights.** Set the exterior-lights button to off. Then set it to on.
      **Pass:** The navigation lights go off and then come on. Only the navigation lights have
      control. The auxiliary LED is read-only.

- [ ] **G6 · GPS accuracy.** Get a good fix. Read the GPS accuracy on the HUD.
      **Pass:** The value is in metres and is between approximately 0.3 m and 5 m.
      **If the value is very large or very small:** The raw units are not millimetres. Change
      `ACC_DIVISOR` in `AutelTakBridge.kt`. You must build the application again.

## 6. Airborne checks: calibration

First go to a stable hover at 10 ft to 20 ft. Confirm the basic functions. Then climb.

- [ ] **A1 · Live position.** Move the aircraft. Watch its marker on the second client.
      **Pass:** The marker follows the aircraft smoothly at the 2-second rate. The HUD altitude and
      the map agree.

- [ ] **A2 · HAE altitude.** Go to a position with a known altitude. Compare the `hae` value of the
      aircraft marker on the second client with the known value.
      **Pass:** The difference is a few metres.
      **Note:** The HUD shows AGL. The log shows `relAlt`. These are different from HAE and MSL by
      the terrain height. Compare the same type of value.

- [ ] **A3 · Gimbal pitch direction.** Move the gimbal fully down (−90 degrees). Then move it level
      (0 degrees).
      **Pass:** The HUD gimbal pitch is negative when the camera looks down. It is approximately 0
      when the camera is level. The SPI point on the second client is in front of the aircraft and
      below it. It is not behind the aircraft.
      **If the SPI is wrong vertically but the HUD pitch is correct:** Touch and hold the AR button.
      Open **Aim Offsets…**. Change the pitch value. Judge this at approximately 25 degrees down.
      **Do not change `PITCH_SIGN`.** That control inverts the SPI and the AR overlay but leaves the
      HUD correct. The two then disagree, and nothing shows you this.

- [ ] **A4 · Gimbal bearing.** Put the aircraft on a known heading. Aim the camera at a landmark with
      a known position. Set the gimbal to approximately 25 degrees down.
      **Pass:** The SPI point on the second client is on that landmark.
      **Read section 4 first.** The bearing error changes with the heading of the aircraft. Do this
      test from a minimum of three headings. Use headings that are far apart. If the results
      disagree by more than approximately 1 degree, the cause is the compass of the aircraft. Do a
      compass calibration. One fixed offset cannot correct it.
      **Best method:** Aim the crosshair at the landmark. Then read the `spi=` position in the log
      and compare it with the known position of the landmark. This gives you the error in metres. It
      is more accurate than a comparison of two icons by eye.

- [ ] **A5 · Field of view.** Put two landmarks at the top edge and the bottom edge of the video.
      Compare the published `<sensor>` cone in ATAK with the picture.
      **Pass:** The cone agrees with the edges of the picture.
      **Note:** The camera now supplies the field of view. You do not usually calibrate it. The
      application logs the reported value as `camera-reported hFov`.
      **Use the top edge and the bottom edge, not the sides.** The picture is cut at the sides to
      fill the 4:3 screen. Approximately 25 % of the width is not on the screen. Therefore the side
      edge of the screen is not the edge of the picture. The top edge and the bottom edge are.
      Then set the zoom to 2x and 4x. Confirm that the cone becomes narrower correctly.

- [ ] **A6 · Video in flight.** Start the LIVE stream. Look at a viewer or a media server.
      **Pass:** The screen copy (video, HUD and map) stays in operation through some manoeuvres and a
      change of lens.

- [ ] **A7 · Thermal lens.** Change to IR.
      **Pass:** The picture changes to thermal. The cone changes to the thermal field of view. The
      camera reports 33.0 x 26.0 degrees for this lens.
      **Note:** `IR_HFOV` in `AutelTakBridge.kt` is a fallback value only. The camera supplies the
      operational value.

## 7. Airborne checks: recovery

- [ ] **R1 · Return to home.** Go to a safe distance and altitude. Command **Return to Home**.
      **Pass:** The aircraft returns. It climbs to the commanded RTH altitude and holds it. The
      altitude agrees with the HUD. Look for a "process timed out" message on the command. Report
      this message if it occurs.

- [ ] **R2 · Link-loss failsafe.** In an open area, cause a short loss of the controller link.
      **Pass:** The aircraft does its own failsafe. It hovers for approximately 10 seconds. Then it
      climbs. Then it returns. The application cannot set or read the emergency action. You test the
      behaviour of the AIRCRAFT. You do not test application control. The sticks stay live. You can
      take control at any time.

- [ ] **R3 · Background operation.** In the hover, lock the screen for approximately 30 seconds. Then
      go back to the application.
      **Pass:** TAK stays connected. The position reports continue on the second client during this
      time. The video starts again. The HUD continues.

- [ ] **R4 · Network interruption.** If TAK uses a hotspot, stop the network and start it again.
      **Pass:** TAK connects again without help. The aircraft link stays up. You do not start the
      application again.

- [ ] **R5 · RC button codes.** Press each button on the Smart Controller V3 that you can map.
      **Do:** Look at the Debug log for `onKeyDown` codes. Write down which button gives which code.
      This test collects data. It does not have a pass condition.

- [ ] **R6 · Recovery after a memory failure.** You cannot cause this condition. If the system stops
      the application for memory during a flight, look at the result.
      **Pass:** The application starts again at the Home screen. It does not start at a frozen flight
      screen. If you see a flight screen with a dead HUD, keep the log.

## 7A. Markers that other users share

You can do these checks on the ground. The aircraft does not have to fly. You need a second client
(CloudTAK, ATAK or iTAK) on the same server.

- [ ] **M1 · A shared marker stays.** Send a marker from the second client. Keep the flight screen
      open for a minimum of 15 minutes.
      **Pass:** The marker is still on the map. Before v1.5.3 it went away after approximately 10
      minutes.

- [ ] **M2 · A shared marker comes back.** Stop the application. Start it again. Open the flight
      screen.
      **Pass:** The marker is on the map again. The log gives
      `map ready: restoring N saved marker(s)` and one `restored saved marker:` line for each one.

- [ ] **M3 · A team position still goes away.** Let a team member connect and then disconnect.
      **Pass:** Their symbol becomes grey and then it goes away. A marker that stays must not make
      the positions of people stay.

- [ ] **M4 · The number of contacts does not increase.** With ADS-B traffic, keep the application
      open for a minimum of 30 minutes. Look at this line in the log:
      ```
      contacts held: 38 total, 3 persistent
      ```
      **Pass:** `total` moves up and down with the real picture. It does not increase for the full
      time. `persistent` is the number of markers that your team has shared, and it does not
      increase.
      **This is the most important check in this section.** This number was 161 when the
      application was stopped for memory in the air on 3 August 2026.

- [ ] **M5 · METAR does not come.** With the ADS-B feed on, look at the map and the saved-marker
      file.
      **Pass:** There is no weather station. The AR menu has no Weather control.

- [ ] **M6 · A delete from the network removes the marker.** Put a marker in a Data Sync mission
      from the second client, then delete it there.
      **Pass:** The marker goes away from the aircraft, and it does not come back after you start
      the application again.
      **⚠ NOT YET TESTED (4 August 2026).** A delete of a marker that is NOT in a mission does not
      go on the network — CloudTAK removes it only from itself — so that method cannot test this.

- [ ] **M7 · The 72-hour limit.** You cannot wait 72 hours. Test it in this way instead:
      1. Stop the application.
      2. Change the `seen` value of one marker in
         `shared_prefs/takpilot2_recv_markers.xml` to 73 hours in the past.
      3. Start the application and open the flight screen.
      **Pass:** The log gives `evicted 1 marker(s) unseen for 72h`, that marker goes away, and the
      others stay.

## 8. After the flight

- [ ] Go to Debug Log. Touch **Export**. The application also puts a copy in
      `Downloads/TAKPilot2 Logs`. Keep this file. It contains the SPI positions, the gimbal angles,
      the reported field of view and the GPS accuracy.
- [ ] Write down the values that you used: the bearing offset and the pitch offset. These are
      properties of the airframe. Do this test again for each aircraft.

## 9. Quick reference: symptom to control

| Symptom | Control | Location | Build again? |
|---|---|---|---|
| The SPI bearing is wrong, and the error changes with the heading | Compass calibration of the AIRCRAFT | Not in this application | No |
| The SPI bearing is wrong by the same amount at all headings | Aim Offsets bearing | Touch and hold AR, then Aim Offsets… | No |
| The SPI is wrong vertically, but the HUD pitch is correct | Aim Offsets pitch | Touch and hold AR, then Aim Offsets… | No |
| The HUD gimbal pitch is inverted | Examine the ingest code. Do NOT change `PITCH_SIGN`. | `AutelTakBridge.kt`, `setAngleListener` | — |
| The cone is wider or narrower than the picture | The camera supplies this value. Examine `camera-reported hFov` in the log. | `AutelProductHolder.kt` | No |
| The GPS accuracy value is not possible | `ACC_DIVISOR` | `AutelTakBridge.kt` | Yes |
| The aircraft has a ground symbol | `DRONE_TYPE` (`a-f-A-M-H-Q`) | `CotBuilder.java` | Yes |
| The RTH altitude will not go below 82 ft | This is the aircraft floor of 25 m. It is not a fault. | — | — |
| The zoom level is not correct after a reconnect | This is corrected in v1.5.2. The zoom scale is now absolute. | — | — |
| A marker that your team shared goes away | The sender must set `archived`. Look for `persistent=` in the `rx type=` line of the log. | `CotParser.isPersistentType` | Yes |
| The number of contacts increases for the full session | A retention fault. Read `contacts held:` in the log. | `CotParser.isPersistentType` | Yes |
| A METAR weather station is on the map | It must not arrive. Examine the uid prefix test. | `CotParser` | Yes |

## 10. Items that this checklist does not cover

These items are settled:

- Obstacle avoidance braking. Verified in flight on 2 August 2026.
- RTH altitude set, read and flown at 200 ft.
- The exterior "go dark" function.
- The video codec concurrency question. The stream is a screen capture. There is no second tap on
  the codec.
- RF power. A regulatory refusal was corrected in the region settings of Explorer. It is not an
  application fault.
