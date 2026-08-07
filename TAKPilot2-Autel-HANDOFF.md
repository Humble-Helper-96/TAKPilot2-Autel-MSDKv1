# TAKPilot2-Autel — Handoff and working context

**Written in Simplified Technical English (ASD-STE100).**

**Purpose:** give the intent, the decisions and the knowledge from the previous sessions to a new
session. This is the internal working document.

**Version:** v1.5.2. **This document was not updated after v1.5.2.** For the current state,
read `PORT-STATUS.md` (updated at each release) and the class documentation in the source.
The decisions and the lessons in this document stay valid.
**Status:** Tested in flight on the EVO II Dual 640T V3 and the Smart Controller V3 on 3 August 2026
and 4 August 2026. Phases 0 to 5 are complete.

## 1. What the application does

The application operates on the Autel Smart Controller V3. It has two functions at the same time:

1. It is the flight interface for the aircraft. It gives the live video, the telemetry HUD and the
   map.
2. It is a TAK gateway. It sends the aircraft position to a TAK server as CoT over TLS. The aircraft
   is then a live track for each other TAK client, and its video is linked from the marker.

### 1.1 Why the design is this way

The first plan was a small background application that operated at the same time as Autel Explorer.
Explorer was to stay as the interface for the pilot.

That plan had one unproven item: can the Autel MSDK receive telemetry while Explorer holds the
connection to the aircraft? Manufacturer SDKs are frequently one application at a time. No example
anywhere had shown a manufacturer flight application and a telemetry application together on one
Android device.

**The decision was to port the DJI application.** A working application already existed: TAKPilot2
for DJI. It did this task.

**The result:** this application replaces Autel Explorer during TAK operations. This removes the
question about Explorer completely.

**The cost:** the application does not have the other Explorer functions. It has no camera settings
screen, no mission planning, no firmware update and no warnings screen. Keep Explorer installed for
configuration before a flight. Use TAKPilot2 for TAK missions.

The sticks and the RTH button operate at all times. Stick data goes on the Skylink radio link. It
does not go through the application.

## 2. Structure

The application is built on the Autel `AndroidAdvanceSample` repository. The TAKPilot2 code is added
to it.

```
com.taklite.client.tak/     TAK layer (Java). It does not know the manufacturer.
                            TakManager, TakClient, CotBuilder, CotParser,
                            TakCertEnroller, TakGroupAssigner, TakMissionClient, TakUser

com.autel.sdksample.tak/    Aircraft layer and user interface (Kotlin).
```

**The most important architectural rule:** `com.taklite.client.tak` has no import from a
manufacturer SDK. It uses only the JDK, the Android framework, JSON and XML.

That separation made this port possible in a short time. It would also make a third manufacturer
possible. **Do not put an SDK import in that package.**

## 3. Technical knowledge

### 3.1 The SDK uses listeners only

DJI gives synchronous polling with `KeyManager.getValue()`. Autel MSDK v1.5 does not.

`AutelTakBridge` registers the listeners one time. It keeps the most recent values in `@Volatile`
fields. A 2-second timer reads the values and sends one CoT event.

| Listener | Data |
|---|---|
| `Evo2FlyController.setFlyControllerInfoListener` | GPS position, altitude, satellites, accuracy, local coordinates, home point, attitude |
| `EvoBattery.setBatteryStateListener` | Percent remaining, voltage, capacity |
| `EvoGimbal.setAngleListener` | Gimbal pitch, roll and yaw |

**The listeners do not continue after the aircraft connects again.** `AutelProductHolder` registers
them again at each `productConnected` event.

### 3.2 The altitude trap

This is the most important correctness item.

| Source | Meaning | Use |
|---|---|---|
| `EvoGpsInfo.getAltitude()` | Height above the WGS-84 ellipsoid (HAE) | Correct for the CoT `hae` value |
| `EvoGpsInfo.getHeightMeanSeaLevel()` | Height above MSL | Terrain calculations only |
| `LocalCoordinateInfo.getAltitude()` | Height above the takeoff point | **Never send this as `hae`.** HUD only. |

If you send the takeoff-relative altitude as HAE, the track is at the wrong altitude on each other
client. This is a known fault in drone-to-CoT applications.

**The same trap has a second form.** A contact reports `hae`, which is an ellipsoid height. The
aircraft altitude is MSL. If you subtract one from the other, the result contains the geoid
separation. This was measured at 12.2 m on 4 August 2026. It put AR contact icons 2.3 degrees too
high. Contacts that have a GROUND CoT type now use DTED terrain instead.

### 3.3 Video

The stream is a **MediaProjection screen capture** of the full flight screen. The application
encodes it to H.265 and sends it with RTSP.

The first design took the encoded frames directly from the aircraft with
`AutelCodecListener.onFrameStream()`. That design had no transcode and almost no CPU cost, but it
could not change the resolution or the bitrate.

The screen capture design has two advantages. The stream continues through a link loss or a battery
change. There is no second tap on the codec, so there is no competition with the view that shows the
video to the pilot.

The encoder must use **VBR**, not CBR. The HEVC encoder of this chip declares VBR=true and
CBR=false. A request for CBR made the I-frames too small and gave a pulse in the picture every 2
seconds. `VIDEO-STREAM-VBR-FIX.md` has the full record.

### 3.4 The position comes from the aircraft

`AutelTakBridge` reads the position only from `EvoGpsInfo`. This is correct. The CoT track answers
the question "where is the aircraft". A position from the controller would show the pilot on the
ground.

### 3.5 No screen controls the network functions

The TAK connection, the telemetry bridge and the video stream are in process-wide objects:
`TakManager`, `TakBridgeHolder` and `VideoStreamerHolder`.

If the pilot leaves the flight screen, these functions continue. `TakForegroundService` keeps the
process alive. Doze mode can stop a telemetry sender during a flight. That fault is very difficult
to find.

**One exception:** the screen capture stops when the flight screen is no longer visible. This is a
privacy control. The stream is a copy of the full screen.

### 3.6 The global product-listener position is contested

`Autel.setProductConnectListener` is one global position. The `ProductActivity` of the sample
application writes over it. `AutelProductHolder.install()` is called at each `onResume` of the Home
screen and the Flight screen to get it again.

### 3.7 The camera supplies the field of view

`XT706CameraInfo.getHorizontalFOV()` and `getVerticalFOV()` give degrees. The application uses these
values. The constants are a fallback only.

Do not make the vertical field of view a second calibration control. A rectilinear lens holds the
two axes together through the frame aspect. Read `PORT-STATUS.md` section 5.

## 4. Build environment

- **Use JDK 17.** An earlier version of this document said JDK 11. That is wrong. The project builds
  correctly with JDK 17.
- **Run `chmod +x gradlew` after each unzip.** The zip extraction and the Synology Drive
  synchronization remove the execute permission. This has caused a failure two times.
- Build: `./gradlew assembleDebug`. Install: `adb install -r <apk>`. The `-r` option keeps the
  settings.
- Toolchain: Gradle 7.3.3, AGP 7.2.2, Kotlin 1.7.20, compileSdk 33, minSdk 21, targetSdk 29.
- The Autel SDK is an AAR file in the repository at `app/libs/`. It is not a remote artifact.
- The application ID is `com.tak.uastoollite`. The application key is in `TestApplication.java`.
- The Java package (`com.autel.sdksample`) and the application ID are different. This is valid
  Android. It causes confusion if you forget it. **The FileProvider authority must use the
  application ID.**

**To find an SDK method, decompile the AAR with `javap`.** The public documentation does not give
the field-level definitions of the telemetry classes. This method found
`XT706CameraInfo.getHorizontalFOV()`, the units of the zoom scale and `EvoAngleInfo.getRoll()`. Use
it for each gap in the documentation.

**Search the full SDK, not one part of it.** Three wrong statements of the form "the SDK cannot do
this" came from an examination of one subsystem only.

## 5. Augmented reality: the accuracy limit

**The AR overlay is for general awareness of an area. It is not accurate for a point.**

A flight test on 4 August 2026 aimed at one target from three headings:

| Heading | Range | Implied bearing offset |
|---|---|---|
| 292 degrees | 225 m | 6.00 degrees |
| 264 degrees | 349 m | 4.49 degrees |
| 089 degrees | 358 m | 0.87 degrees |

The spread is 5.13 degrees. This is too large for an error of aim by the pilot. The error changes
with the heading. This is magnetometer error on the aircraft.

**One fixed offset cannot correct an error that changes with direction.** The correction is a compass
calibration of the aircraft.

The projection mathematics is correct. The drawn position of three markers was calculated again from
known coordinates. The result agreed with the application to one pixel.

**Do not correct the vertical error with the pitch offset.** The pitch offset also controls the SPI
and the marker drop. If you change it to put an icon under the crosshair, the icons look correct and
the dropped markers become wrong. Nothing shows this to the pilot. Calibrate the pitch against
terrain that the camera aims at.

## 6. Deferred work

1. **Certificate storage.** The certificates are `.p12` files in the application-private `filesDir`.
   They are not in the hardware-backed Android Keystore. This is satisfactory on a controller that
   is not rooted.
2. **No automatic certificate renewal.** The application reconnects with the stored certificates. It
   does not enroll again before they expire. An expired certificate gives a TLS handshake error with
   no clear cause. Add this function before a fleet deployment.
3. **Wi-Fi handoff.** Controllers move between hotspots. The client reconnects after a socket
   failure. A callback for a change of connectivity would make the reconnection faster.
4. **The zoom button label** starts at 1X. The application does not read the zoom from the camera at
   connect. Therefore the label can be wrong until the first touch. The zoom mathematics is correct.
5. **`IR_HFOV = 33.0`** comes from one measurement. The lens was identified from the aspect ratio of
   1.283 and the change of focal length. It was not identified from a lens name. This is a fallback
   value that the live value replaces.

## 7. Server contract

| Port | Protocol | Purpose |
|---|---|---|
| 8089 | TLS, both ends authenticate | CoT stream. One long connection. The application writes CoT XML when an event occurs. There is no HTTP and no framing. The same connection carries the inbound data. |
| 8446 | HTTPS | Certificate enrollment. Authenticate one time with a name and a password, make a key pair, send a CSR, receive the signed certificate and the CA truststore. |
| 8443 | HTTPS REST | Data Sync and the Mission API. These feeds have names and are permanent. |

- **Identity.** The server finds the user from the Common Name of the client certificate. The group
  membership decides the channel routing. This is fully on the server. The application can make the
  routing narrower with explicit destination channels.
- **A track identity is not a connection identity.** The map separates entities by the CoT `uid`. It
  does not use the certificate. Several devices with one certificate give separate markers if the
  `uid` values are different.
- **The `uid` must not change between sessions.** It comes from the callsign that the operator
  enters. The same airframe is then the same track at all times. Never make it random.
- **The `stale` time is short (10 to 15 seconds).** A fast track that loses its link must disappear.
  It must not stay on the map.
- **Load.** At 0.5 Hz for each aircraft, six aircraft give approximately 3 events each second. This
  is very small for the server.

## 8. Deployment

- **Wi-Fi only.** The Smart Controller V3 has no SIM slot and no cellular modem. It joins a hotspot
  on site. Therefore the application must continue through a change of network.
- The fleet is the Autel EVO II Dual 640T V3 and the Smart Controller V3 (not the SE), Android 11,
  controller firmware 1.3.9.23 or higher.
- **The EVO II V3 has no laser rangefinder.** The application calculates the look point with
  geometry. There is no measured distance.
- The configuration for each device is the callsign only. A person enters the other values when the
  application operates. The application keeps them. Nothing is in the build.
- To provision a controller: install the APK, then enter the host, the user, the password and the
  callsign one time.

## 9. Related documents

| File | Contents |
|---|---|
| `README.md` | The start point. |
| `PORT-STATUS.md` | The component map, the accuracy limits and the calibration items. |
| `FLIGHT-TEST-CHECKLIST.md` | The ordered test procedure. |
| `TAKPILOT2_AUTEL_PORT_PLAN.md` | The full project reference. |
| `VIDEO-STREAM-VBR-FIX.md` | The record of the video pulse and its correction. |
| `TAKPilot2-DebugLogging-DevNotes.md` | The logging design, and how to move it to the DJI application. |
| `REVIEW_2026-08-03_*.md` | The review records for the code, the UI, the language and the security. |
