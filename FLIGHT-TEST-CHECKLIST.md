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

**For the channel tests (section 7B) you also need:**

3. A TAK server that HAS channels, and a login to TAK Portal for it.
4. A user with a minimum of two channels. Make one of them receive-only. `ADSB` is usually
   receive-only and is a good example.

> ⚠ **Do not do the channel tests against a server that does not have channels.** A channel
> change sent to such a server can damage it (Cory Foy, TAK Aware, 16 August 2026).

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

## 4A. Results of the v1.6.2 test (16 August 2026)

**Build 1.6.2, versionCode 33. About 30 minutes with the aircraft flying and ADS-B on.**

Passed on hardware: channels on both screens (C1 to C7), the in-flight unlock, obstacle
avoidance stopping the aircraft on all sides, RTH altitude set and enforced, exterior lights,
more than one controller on one certificate, the zoom ladder, and the marker height gate.

Measurements:

- **Contacts.** 50 at the start, 47 at the end, peak 64. `persistent` peaked at 1. No growth.
- **RC signal.** 784 reports, **the largest gap was 0 s** through 3 flight-screen exits. The
  listener teardown is clean.
- **Credentials.** 29 addresses in the log, each one `://tak:***@`. No leak.
- **Network.** A real DNS failure of about 100 seconds. TAK connected again 4 times with no help.
- **Channels.** 4 `t-x-g-c` events, each one followed by a re-read. One
  `PUT activebits` → HTTP 200.

Three faults were found and corrected the same day:

1. **A deleted marker did not come back when it was shared again.** A local delete suppressed
   the uid for ever. It now returns when a teammate shares it again. **Re-tested: passed.**
2. **A CloudTAK user drew as a 2525 marker.** CloudTAK reports its users as `a-f-G-E-V-C`, and
   the type test accepted it. A live client (`takv` or `endpoint`) is now always a team dot.
   **Re-tested: passed.**
3. **The Debug screen kept an Export Log button.** The logs are already in
   `Downloads/TAKPilot2 Logs`. Removed.

Two behaviours were CONFIRMED CORRECT and are not faults:

- The aircraft marker and the SPI stay after a channel change until they go stale. They are
  marker-type CoT. The pilot marker is a position report and goes at once.
- The video address goes on the wire when a push STARTS, not when it connects. The CoT carries
  the address the pilot entered.

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

- [ ] **R5 · RC buttons.** The buttons are mapped now, thus this test has a pass condition.
      Press C1. Then press and hold C1.
      **Pass:** A press changes the lens between IR and RGB. A press and hold moves to the next IR
      colour palette. The flight screen shows the name of the palette.
      **If you map a new button:** look at the Debug log for `onKeyDown` codes.

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

## 7B. Channels

**New in v1.6.2.** These checks are on the ground. Read section 2 first for what you need.

The server holds the channels. The controller shows them and changes them, but it does not hold
them. ⚠ The channels belong to the CERTIFICATE: if two controllers enroll as one user, a change
on one controller changes both.

- [ ] **C1 · The list is correct.** Open Pre-Flight. Look at My Channels. Compare it with TAK
      Portal.
      **Pass:** Each channel of your user is in the list, and the ticks agree with TAK Portal.
      A receive-only channel shows `- Rx Only`. A send-only channel shows `- Tx Only`. A two-way
      channel shows its name only.
      **If each channel shows `- Rx Only`:** the request lost its parameters. See
      `TakMissionClient.listChannels`.

- [ ] **C2 · A change on the controller goes to the server.** Switch one channel on. Look at TAK
      Portal.
      **Pass:** TAK Portal shows the change in approximately 2 seconds. The status line says the
      server accepted it.

- [ ] **C3 · A change on the server comes to the controller.** Keep Pre-Flight open. Change a
      channel in TAK Portal.
      **Pass:** The controller list agrees in approximately 2 seconds. You do not touch the
      controller. This is the `t-x-g-c` event. The log gives
      `channels changed on the server — re-reading`.

- [ ] **C4 · The channels control the markers.** ⚠ **This is the most important check in this
      section.** Select one two-way channel only. Drop a marker.
      **Pass:** The marker arrives on the second client.
      Now select a receive-only channel as well. Drop a second marker.
      **Pass:** The marker arrives. Before v1.6.1 it reached no one, and nothing said so.

- [ ] **C5 · The channels control the aircraft position.** Switch every channel off.
      **Pass:** The aircraft and the pilot go away on the second client in approximately 2
      seconds. **This is new in v1.6.2.** The old channel control never applied to the position.
      Switch one channel on again. **Pass:** They come back.

- [ ] **C6 · The lock stops a change and not the reading.** Set Lock configuration in the TAK
      section. Look at My Channels.
      **Pass:** You can READ which channels are on. The ticks are not grey and not faint. A touch
      on a channel does nothing.

- [ ] **C7 · Channels in flight.** On the flight screen, touch and hold the TAK connection icon.
      **Pass:** The channel screen opens over the video. The video does not stop.
      With the configuration locked, touch **Unlock…**, give the password, and change a channel.
      **Pass:** You change the channel WITHOUT leaving the flight screen.
      Leave the flight screen and come back. Touch and hold the icon again.
      **Pass:** It is locked again. The unlock is for one visit only.

- [ ] **C8 · A server with no channels.** Only if you have such a server.
      **Pass:** The screen says "This server has no channels." There is no control to touch, and
      the log has no channel write.

## 7C. Two video servers

**New in v1.6.2.**

- [ ] **V1 · The old configuration is not lost.** Install v1.6.2 over an older build. Open
      Pre-Flight.
      **Pass:** Your video settings are in "Server 1". Nothing is empty.

- [ ] **V2 · The names label the buttons.** Give each server a name.
      **Pass:** The names are on the two Active server buttons as you type them.

- [ ] **V3 · The video goes to the selected server.** Configure both servers. Select the first.
      Start the stream. Then stop it, select the second, and start it again.
      **Pass:** Each stream arrives at the correct media server.

- [ ] **V4 · Each server keeps its own settings.** Give the two servers a different quality and a
      different codec. Change between them.
      **Pass:** Every field changes with the server, and the encoding as well.

- [ ] **V5 · The team gets the correct address.** With the stream in operation, look at the
      aircraft marker on the second client.
      **Pass:** The video plays from the marker, and the address is the ACTIVE server. Swap the
      server, start the stream again, and look again. **Pass:** The address changed.

- [ ] **V6 · The lock stops a swap and not the reading.** Set Lock configuration in the video
      section.
      **Pass:** You can SEE which server is selected. The buttons do not answer a touch.

## 7D. Zoom, markers and the screen

- [ ] **Z1 · The zoom ladder.** Push the zoom rocker and release it.
      **Pass:** The zoom moves ONE step: 1, 2, 3, 4, 6, 8, 10, 12, 16x. It does not go past the
      step when you release the rocker.
      Hold the rocker. **Pass:** It moves through the steps and stops on a step when you release.

- [ ] **Z2 · Augmented reality starts on.** Start the application and open the flight screen.
      **Pass:** The AR overlay is on. You do not have to switch it on.

- [ ] **Z3 · The markers menu.** Open the markers list. Select more than one marker.
      **Pass:** You can delete them together and send them together. Touch and hold one marker.
      **Pass:** The edit screen opens.

- [ ] **Z4 · A refused marker.** Try to drop a marker below 25 ft above the ground.
      **Pass:** The flight screen shows an amber notice. There is no Toast. The notice is in the
      screen capture, thus the team sees the same thing.

## 7E. Stress test — the full session

Do this test before a release. It looks for the faults that only a long session finds: memory
growth, a listener that was lost, and state that drifts.

**Run the application for a minimum of 90 minutes with TAK connected and the ADS-B feed on.**

- [ ] **S1 · The contact count does not grow.** Read `contacts held:` in the log at the start, at
      45 minutes and at the end.
      **Pass:** `total` moves up and down. It does not increase for the full time. `persistent`
      does not increase. See M4.

- [ ] **S2 · Change the channels 20 times.** Use both screens: Pre-Flight and the flight-screen
      dialog.
      **Pass:** Each change reaches the server. The list never disagrees with TAK Portal at the
      end. No channel write goes out with an empty list.

- [ ] **S3 · Change the video server 10 times, with a stream between the changes.**
      **Pass:** Each stream arrives at the correct server. The advertised address is never the
      previous server.

- [ ] **S4 · Go in and out of the flight screen 10 times.**
      **Pass:** The video stops and starts each time. TAK stays connected. The RC signal reading
      on the HUD is never lost. ⚠ **Watch this one.** A null listener on this channel kills the
      RC signal for the whole process, and only a restart brings it back.

- [ ] **S5 · Lock and unlock every section 5 times.**
      **Pass:** The channel rows and the server buttons stay readable at every step. Nothing
      becomes grey and unreadable.

- [ ] **S6 · Interrupt the network 5 times.**
      **Pass:** TAK connects again each time without help.
      **Note:** the channels are read again on a connection ONLY when a channel screen is open.
      The listener belongs to those screens. No re-read with the screens closed is correct, not
      a fault — nothing is displaying them, and the server enforces the channels anyway.

- [ ] **S7 · Read the log at the end.**
      **Pass:** There is no password and no `user:pass@` in the file. Search for `://` and read
      each result. `OutboundLogRedactionTest` protects this, and this check confirms it on real
      data.

- [ ] **S8 · Memory.** Look for a stop of the application by the system.
      **Pass:** The application is in operation at the end. If the system stopped it, keep the log
      and see R6.

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
| A marker does not arrive, and nothing says so | A channel you cannot SEND to. Look for `- Rx Only`. | Pre-Flight, My Channels | No |
| The aircraft is not on the team map | Every channel is off. | Pre-Flight or touch and hold the TAK icon | No |
| Each channel shows `- Rx Only` | The request lost its parameters. | `TakMissionClient.listChannels` | Yes |
| A channel change does not reach the server | Another controller shares this certificate, or an administrator stopped the change. | TAK Portal | No |
| The video goes to the wrong media server | The wrong Active server. | Pre-Flight, section 2 | No |
| The video settings look empty after an update | The move to Server 1 did not run. Look for `video config migrated to slot 1`. | `migrateVideoSlots` | Yes |
| The RC signal reading goes away and does not come back | A listener was set to null. Start the application again. | `AutelTakBridge` | Yes |

## 10. Items that this checklist does not cover

These items are settled:

- Obstacle avoidance braking. Verified in flight on 2 August 2026.
- RTH altitude set, read and flown at 200 ft.
- The exterior "go dark" function.
- The video codec concurrency question. The stream is a screen capture. There is no second tap on
  the codec.
- RF power. A regulatory refusal was corrected in the region settings of Explorer. It is not an
  application fault.

⚠ **These items are NOT settled and are NOT in this checklist:**

- More than one aircraft on one certificate. Section 7B says what happens. Nobody tested it.
- The behaviour when the server REFUSES a channel write. Only HTTP 200 was seen.
- A server that does not have channels. C8 is written, but no such server was available.
