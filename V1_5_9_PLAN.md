# TAKPilot2-Autel v1.5.9 — release plan

**Written in Simplified Technical English (ASD-STE100).**

**Status:** planned, 7 August 2026. v1.5.8 operates on 6 controllers in the fleet.

## 1. What this release contains

Four features. The operator requested each one after field use of v1.5.8:

1. Wifi signal on the Enter Flight card (section 3)
2. Aircraft warnings on the flight screen (section 4)
3. Flight path files in Downloads/TAKPilotFlights (section 5)
4. Configuration locks at the bottom of each section (section 6)

The features are independent. The build order is 4, 1, 3, 2 — from the smallest risk to the
largest. Section 7 gives the reason.

## 2. The field events that caused this release

**Event 1 — enrollment failures.** Pilots reported that TAK Server enrollment failed. The cause
was that the controller had no network connection. The application gave no indication of the
network state, so the pilots could not see the true fault. Feature 1 corrects this.

**Event 2 — compass interference.** An aircraft flew in strong magnetic interference. The
controller made the warning sounds, but TAKPilot2 showed no message. The pilot could not find
the cause until they closed TAKPilot2 and opened Explorer. Feature 2 corrects this.

## 3. Feature 1: wifi signal on the Enter Flight card

### What the pilot sees

One new status line on the home screen, next to the TAK status. Three states, in the standard
colours (green = good, amber = unsure, red = fault):

| State | Text | Colour |
|---|---|---|
| Connected, internet confirmed | `WIFI: <ssid> ▂▄▆█` (0–4 bars) | Green |
| Connected, no internet | `WIFI: CONNECTED, NO INTERNET` | Amber |
| Not connected | `WIFI: NOT CONNECTED` | Red |

The SSID is part of the line. A pilot on the wrong network sees the wrong name, not only a
weak signal.

**The amber state is the important one.** A controller can hold a wifi association to a hotspot
that has no upstream connection. The signal strength alone does not show this. The check for
internet is `ConnectivityManager` capability `NET_CAPABILITY_VALIDATED`, not only the wifi
association.

### Where the code goes

- `TakPilotHomeActivity.updateStatus()` — the 1.5-second refresh loop that already sets each
  status line. Add the wifi line there.
- Signal strength: `WifiManager.connectionInfo.rssi` through `calculateSignalLevel()`.
- The manifest already holds `ACCESS_WIFI_STATE`. Confirm at build time whether the SSID read
  needs location permission on API 29; if it does, show the bars without the SSID rather than
  add a permission request.

### Companion correction: the enrollment error message

When the pilot presses Enroll & Connect with no validated network, the application must fail
immediately with the message "No network connection." It must not show the generic socket or
TLS error. This is the exact diagnosis that the pilots could not make in event 1. The check
goes in `TakConnectActivity` before the enrollment call.

## 4. Feature 2: aircraft warnings on the flight screen

### The data already flows

The warning data does not need a new SDK subscription. `AutelTakBridge.subscribe()` already
receives `EvoFlyControllerInfo` at approximately 2 Hz. That object carries a
`FlyControllerStatus` that the bridge currently ignores. **Standing rule 2 of the project
reference applies: do not add a second `setFlyControllerInfoListener` — extend the callback
that exists.** Battery state and obstacle-avoidance state are also already cached
(`AutelTakBridge`, `AutelAvoidance`).

### What the SDK can report (confirmed in the aar with javap, 7 August 2026)

From `FlyControllerStatus`: `isCompassValid`, `isGpsValid`, `isWindTooHigh`,
`isFlightControllerOverHeated`, `isReachMaxHeight`, `isReachMaxRange`, `isNearRangeLimit`,
`isHomePointValid`, `isStickLimited`, `getFlyLimitAreaWarning()` (11 airport/no-fly states),
`getArmErrorCode()` (pre-takeoff blockers, each with its own description text),
`getMainFlyState()` (`ATTITUDE` = GPS hold lost). From the existing battery listener:
`BatteryWarning` LOW / CRITICAL. From `AutelAvoidance`: `VisualWarnState`.

### First-cut display set

**Red (act now):**

| Source | Message |
|---|---|
| `isCompassValid == false` | COMPASS INTERFERENCE |
| `isGpsValid == false` or `MainFlyState.ATTITUDE` | GPS LOST — AIRCRAFT DRIFTS |
| `isFlightControllerOverHeated` | FLIGHT CONTROLLER HOT |
| `BatteryWarning.CRITICAL` | BATTERY CRITICAL |
| `FlyLimitAreaWarning` no-fly states | NO-FLY ZONE |

**Amber (know it):**

| Source | Message |
|---|---|
| `isWindTooHigh` | WIND TOO HIGH |
| `isReachMaxHeight` / `isReachMaxRange` | AT ALTITUDE LIMIT / AT DISTANCE LIMIT |
| `BatteryWarning.LOW` | BATTERY LOW |
| `isHomePointValid == false` (in flight) | NO HOME POINT |
| `FlyLimitAreaWarning` vicinity states | NEAR AIRPORT |

Each other signal is written to the log but not shown. The set is one `when` block, ordered by
priority, so field feedback changes it with a small edit.

### Display mechanics

- A banner strip at the top of the flight screen, under the toolbar, driven from the existing
  500 ms HUD tick. Red background for red warnings, amber for amber.
- The source repeats at 2 Hz. The banner must react to the CHANGE of a state, not to each
  callback. Minimum display time approximately 4 seconds, so a short pulse is readable.
- One warning shows at a time: the worst active one, with a count (for example `+2`) when more
  are active.
- Each state transition also goes to `AppLog`, so the flight log shows when the compass became
  bad and when it recovered.

### Bench test before field use

Put a magnet near the aircraft compass on the bench and confirm that `isCompassValid` becomes
false and the banner shows. Do not trust the field with an unverified signal path. (The
project reference, section "lessons": test the hardware before you design around it.)

## 5. Feature 3: flight path files in Downloads/TAKPilotFlights

### The requirement

Pilots want a record of each flight path. The files must survive an application crash. The
folder must not fill the controller storage.

### Design: append CSV in flight, convert to GPX after

**In flight:** append one CSV row each second, only while `isFlying` is true. Flush every few
seconds. An append-only CSV is a valid file at every moment — a crash loses at most the last
few seconds, never the file. Columns:

```
utc_time, lat, lon, alt_msl_m, alt_above_takeoff_m, speed_ms, heading_deg, battery_pct, satellites
```

Every value already sits fresh in `AutelTakBridge` at 2 Hz. The logger reads the bridge
fields; it adds no SDK subscription.

**At landing or normal exit:** convert the CSV to a GPX track file next to it. GPX carries a
timestamp and an elevation for each point and imports into ATAK, Google Earth and standard GIS
tools. The CSV stays: it holds battery, heading and speed, which GPX cannot carry.

**At the next application start:** find CSV files that have no GPX partner (a crashed
session) and complete the conversion. This closes the crash case with no attempt to keep a
structured XML file valid during writes.

**File names:** `flight-YYYY-MM-DD-HH-mm-ss.csv` / `.gpx` — the same timestamp format that
`AppLog` uses.

### Storage bound

Reuse the `AppLog` public-archive pattern: MediaStore Downloads, a total-size cap, delete the
oldest files first when a new file would push the folder over the cap. Folder
`Downloads/TAKPilotFlights`, cap 50 MB. A 30-minute flight at 1 Hz is approximately 200 KB, so
the cap holds months of flying. Size is the correct bound, not age — the same reason `AppLog`
gives: idle days produce no data, so age is a bad measure of volume.

### Where the code goes

- New file `FlightPathLogger.kt` in the `tak` package. Start/stop follows `isFlying` from the
  bridge. The MediaStore write and rotation code follows `AppLog`'s public archive block.
- The orphan sweep runs once from `TakPilotHomeActivity.onCreate`, off the main thread.

## 6. Feature 4: configuration locks at the bottom of each section

### The fault

The three lock checkboxes sit at different positions inside their sections in
`activity_tak_connect.xml`. The video lock sits ABOVE the codec and TCP fields, but since
v1.5.7/1.5.8 it LOCKS those fields — the checkbox is above part of what it controls.

### The change (layout only — no logic changes)

| Section | Checkbox | New position |
|---|---|---|
| 1. Aircraft Settings | `limitBatteryLock` | Bottom of the section, after Apply to Aircraft |
| 2. Video Streaming | `videoLockConfig` | Bottom of the section, after the stream URL |
| 3. TAK Server Connection | `takLockConfig` | After Log Out, before My Channels |

The `setupOneLock` logic, the field lists and the unlock password do not change.

**One judgment call, decided:** the Section 1 lock covers only the two battery percentages,
not the whole section. It still moves to the bottom for consistency. Its label already states
its true scope ("Lock battery levels (fields read-only)"), so the position does not mislead.
The other two keep "Lock configuration (fields read-only)".

## 7. Build order and risk

| Order | Feature | Risk | Reason for position |
|---|---|---|---|
| 1 | Locks (§6) | None — layout only | Immediate, zero-risk win |
| 2 | Wifi card + enroll message (§3) | Low — framework APIs only | No SDK contact |
| 3 | Flight path logger (§5) | Low — reads cached bridge fields | Proven pattern (AppLog) |
| 4 | Warnings (§4) | Medium — extends the flight-controller callback | Needs the bench test; most design-sensitive |

Rules that bound this work, from the project reference and the incident records:

- Do not add SDK subscriptions. Every needed signal already arrives through the bridge or
  `AutelAvoidance`. (Standing rules 1 and 2; the RC-listener incident.)
- Do not change `applicationId` or the SDK connection sequence.
- No permanent changes to the controller. Everything here lives inside the application and its
  Downloads folders.

## 8. Open items

- Confirm on the bench that `isCompassValid` trips under magnet interference (section 4).
- Confirm whether the SSID read needs location permission on this controller (section 3).
- The warning display set (section 4) is a first cut. Expect one field cycle of tuning.
