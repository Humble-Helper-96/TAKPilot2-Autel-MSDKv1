# TAKPilot2-Autel — Port Status

**Written in Simplified Technical English (ASD-STE100).**

**Base:** TAKPilot2 (DJI MSDK v5, M30) to Autel `AndroidAdvanceSample` (MSDK v1.5, EVO II V3 and
Smart Controller V3)
**Application ID:** `com.tak.uastoollite`
**Application key:** Set in `TestApplication.java`. Registered in July 2026.
**Updated:** 4 August 2026 (v1.5.3)

## 1. Scope

This application is the flight interface. It replaces Autel Explorer during TAK operations. It
controls the SDK connection. It shows the live video. It is the screen that the pilot uses.

These are the results of that decision:

- Explorer does not operate at the same time as this application.
- The sticks and the RTH button continue to operate. Stick data goes on the Skylink radio link.
  It does not go through the application.
- This application does not have the other Explorer functions. It has no camera settings screen,
  no mission planning, no firmware update and no warnings screen.

Keep Explorer installed. Use Explorer for pre-flight configuration. Use TAKPilot2 for TAK
missions.

## 2. Component map

| TAKPilot2 (DJI) | This port | Status |
|---|---|---|
| `com.taklite.client.tak` (TLS CoT client, CotBuilder, CotParser, certificate enrollment, channels, Mission API) | Copied without change | Operational |
| `DroneTakBridge` (KeyManager polling) | `AutelTakBridge` (listener and cache) | Operational |
| `CameraSlantPoint` | Copied without change (mathematics only) | Operational |
| `TakForegroundService`, `TakAutoConnect`, `TakMissionManager`, `TakConnectActivity`, `DataSyncActivity` | Ported. Package and import names changed. | Operational |
| `DroneVideoStreamer` | `AutelVideoStreamer`. The stream is a MediaProjection screen capture of the composited screen. | Operational |
| `TakMapMarkers`, `TakDropMarkers` (DJI map kit) | Ported to osmdroid. The logic is the same. | Operational |
| `DefaultLayoutActivity` (DJI uxsdk flight screen) | `FlightActivity`. Written again for this hardware. | Operational |
| `TAKPilot2HomeActivity` | `TakPilotHomeActivity` | Operational |
| `ArOverlayView` | Ported and operational. Read section 4 for the accuracy limit. | Operational |

## 3. Functions that this port does not have

- **`SpeakerMegaphoneManager` and `PayloadAccessoryManager`.** These control M30 gimbal payloads.
  The EVO II does not have an equivalent payload.
- **Picture-in-picture dual-lens control and the HSI strip.** These use DJI widgets.

## 4. Augmented reality: accuracy limit

**The AR overlay is for general awareness of an area. It is not accurate for a point.**

A flight test on 4 August 2026 aimed the camera at one target from three headings. The bearing
error was between 1 degree and 6 degrees. At 350 m this moved a marker up to 27 m to the side.

The cause is the magnetometer of the aircraft. The error changes with the direction that the
aircraft faces. One fixed offset in the application cannot correct an error that changes with
direction. A compass calibration of the aircraft makes the error smaller.

The projection mathematics is correct. On 4 August 2026 the drawn position of three markers was
calculated again from known coordinates. The result agreed with the application to one pixel.
The remaining error is sensor calibration on the aircraft. It is not a software fault.

Tell the pilot to put the crosshair on the object and to put a marker. This gives an accurate
position. The AR overlay does not.

## 5. Field of view: the camera supplies it

The camera reports its own field of view. `XT706CameraInfo.getHorizontalFOV()` and
`getVerticalFOV()` give degrees. The application uses these values. It does not use a calibration
constant when the camera reports a value.

Measured on the aircraft on 4 August 2026:

| Lens | Reported field of view | Implied aspect | Video stream aspect |
|---|---|---|---|
| Visible (EO) | 65.8 x 39.9 degrees | 1.782 | 1.778 |
| Thermal (IR) | 33.0 x 26.0 degrees | 1.283 | 1.250 |

The implied aspect agrees with the video stream. Therefore the camera reports the field of view of
the STREAM. It does not report the field of view of the sensor.

**Do not make the vertical field of view a second calibration control.** A rectilinear lens holds
the two axes together:

    tan(hFov / 2) / tan(vFov / 2) = frame width / frame height

The application calculates the vertical value from the horizontal value and the live frame aspect.
Two independent controls can give a combination that no camera can have. The old default of
79 x 62 degrees implied an aspect of 1.372 against a stream of 1.778. This was an error of 23 %.
The value 79 is the DIAGONAL specification of Autel. It is not the horizontal value.

The reported field of view does NOT include the digital zoom. The application applies the zoom
correction to the reported value.

## 6. Zoom scale

The raw digital-zoom units are hundredths. The value 100 is 1.0x. This was established from
`focalLength`, which changes with `zoomScale` in a linear manner: 100/0.47, 400/1.90, 800/3.79,
1600/7.58.

The application sets an absolute value. It does not multiply a baseline that it reads at connect.
A baseline is not correct if the application connects when the camera is already at a zoom
position.

## 7. Contact altitude

Contacts that have a GROUND CoT type use DTED terrain data for their altitude. Other contacts use
the altitude that they report.

A contact reports `hae`. This is the height above the WGS84 ellipsoid. The aircraft altitude is
MSL. If you subtract one from the other, the result contains the geoid separation. The measured
error on 4 August 2026 was 12.2 m. This put a contact icon 2.3 degrees too high.

A contact that has a GROUND type is on the terrain. Therefore DTED is the better source. DTED is
also consistent in MSL. It does not mix two datums.

## 8. Markers that other users share

**A shared marker stays. It does not go away on a timeout.**

Before v1.5.3 a shared marker was removed after approximately 10 minutes. CloudTAK sends a marker
with a `stale` time only **3.6 seconds** after its `start` time. The parser increased that to 5
minutes, and the stale sweep deleted the marker 5 minutes later. The sweep tested neither the CoT
type nor the `archived` marker, so it removed shared markers, team positions and ADS-B tracks in
the same way.

### 8.1 How the application identifies a marker

**It uses the `archived` marker that the sender puts on the wire.** It does not use the CoT type.

A capture of 605 inbound events on the live net on 4 August 2026 gave this:

| Count | Type | archived | What it is |
|---|---|---|---|
| 552 | `a-f-A-C-F` | false | ADS-B aircraft |
| 31 | `a-f-G-E-V-C` | false | CloudTAK users |
| 10 | `a-f-G-E-V` | false | ADS-B ground vehicles |
| 2 | `a-f-G-U-C` | false | team positions |
| 1 | `a-u-G` | **true** | a placed marker |
| 1 | `a-f-G` | **true** | a placed marker |

Only the two placed markers have the flag, and they come from two different clients.

**A test of the CoT type alone cannot do this work.** CloudTAK sends its own users as
`a-f-G-E-V-C`, which each type rule reads as a marker. A type rule made 152 of the 155 stored
entries permanent, which included each user who had connected.

Air tracks, position reports and sensor points (`b-m-p-s-p-*`) are refused BEFORE the `archived`
test. Therefore a sender cannot make one of them permanent.

**The cost:** a client that does not set `archived` gets no persistence. Its markers go away as
before. This is the safe direction. The opposite default is retention with no limit, which stopped
the application in the air on 3 August 2026.

### 8.2 The limits on the store

- A marker goes away after **72 hours** with no update.
- The application keeps a maximum of **1000** markers. It removes the oldest first.
- A marker that comes back from the disk does not change its `lastSeen` time. If it did, the 72
  hours could never end.
- Entries that a build before v1.5.3 saved are tested again. That build also saved platforms and
  live clients.

### 8.3 METAR weather stations

**The application removes them when they arrive.** A pilot cannot use them: their content is in
`<remarks>`, which this application does not show.

They were also kept on the disk. **136 of the 155 saved entries were weather stations.** A control
that only hides them at the screen does not remove that cost. The Weather control is deleted from
the AR menu.

### 8.4 How to see a problem

The log gives this line every 30 seconds:

```
contacts held: 38 total, 3 persistent
```

- `total` must move up and down with the real picture. It must not increase for the full session.
  This number was 161 when the application stopped for memory on 3 August 2026.
- `persistent` must be the number of markers that your team has shared. If it increases with air
  traffic, the rule has a fault.

Measured on 4 August 2026 across 30 minutes: `total` moved between 36 and 47, `persistent` stayed
at 3, and the memory stayed level.

## 9. Calibration items

The flight-test checklist (`FLIGHT-TEST-CHECKLIST.md`) was flown and passed on 3 August 2026.

| Item | Location | Note |
|---|---|---|
| GPS accuracy units | `ACC_DIVISOR` | Confirmed at 1000 (millimetres to metres). 31 satellites gave approximately 0.7 m. |
| Gimbal yaw frame | `BEARING_MODE_RELATIVE` | Confirmed as `false`. The gimbal yaw is referenced to true north. |
| Gimbal pitch sign | `PITCH_SIGN` | Confirmed. The Autel SDK reports down as positive. The bridge changes the sign when it reads the value. |
| Aim offsets | `TakBridgeHolder`, saved in preferences | Pitch and bearing. These are properties of the airframe. Examine them again after a gimbal impact, a repair, or a change of aircraft. Read section 4 first: a fixed bearing offset cannot correct the compass error. |
| Field of view | `EO_HFOV`, `IR_HFOV` | Fallback values only. The camera supplies the operational value. Read section 5. |
| HAE altitude | `AutelTakBridge` | Compare with a known HAE value. |

## 10. Divergences from the project guide

- **Certificate storage.** Enrollment certificates are `.p12` files in the application-private
  `filesDir`. They are not in the Android Keystore. Application-private storage on a controller
  that is not rooted is satisfactory. `TakCertEnroller` contains the migration task.
- **Automatic re-enrollment before expiry** is not implemented. The application reconnects with
  the saved certificates. A person must do the re-enrollment when the certificates expire. Add
  this function before a fleet deployment if your server issues certificates with a short life.

## 11. Architecture notes

- `AutelProductHolder` controls the one global `Autel.setProductConnectListener` slot. It installs
  the listener again at each `onResume` of the Home screen and the Flight screen. The
  `ProductActivity` of the sample application writes over this slot.
- The foreground service and the process-wide holders control the lifecycle of the bridge, the TAK
  connection and the stream. No activity controls them. If the pilot leaves the flight screen, the
  telemetry and the video continue.
- The screen capture stops when the flight screen is no longer visible. This is a privacy control.
  The stream is a copy of the full screen. It must not continue when the pilot goes to a different
  screen.
