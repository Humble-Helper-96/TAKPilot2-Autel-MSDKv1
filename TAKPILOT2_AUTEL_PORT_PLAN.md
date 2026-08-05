# TAKPilot2-Autel — project reference

**Written in Simplified Technical English (ASD-STE100).**

> **This document was restructured on 4 August 2026.** It was a working log of 2423 lines in date
> order. The same fault was frequently described two times: one time in a phase plan and one time
> in a session record. The phases are now complete, so the plans have no more use.
>
> This version keeps each technical fact, each measurement and each lesson. It puts them in order
> by subject. Three sections that gave instructions that are now wrong were corrected. Section 12
> lists them.
>
> **The original document is in the git history**, at commit `8203ccc` and before it.

## 1. How to use this document

This is the full reference for the project. Read `README.md` first for a shorter introduction.

Sections 2 and 3 give the rules that a person must know before they change the code. Sections 9 to
11 are the records of three events that damaged or almost damaged an aircraft. **Read section 3
before you write code that speaks to the SDK.**

## 2. What the application is

The application operates on the Autel Smart Controller V3. It replaces Autel Explorer during TAK
operations. It controls the SDK connection, it shows the live video, and it is the screen that the
pilot uses.

**The design rule from the operator:** a pilot must be able to change from the DJI Mini 2 to the
EVO II 640T V3 and fly TAKPilot2 with no need to learn a new interface. Therefore the screens of
the DJI MSDKv4 application are the template. Copy its layouts and its behaviour. Change only the
calls that get data from the aircraft.

**Build each function that the DJI application has.** If a function cannot operate on the Autel
side, ship it as a control in the correct position that shows a message. Do not omit it. A pilot
who moves between aircraft must find the same layout. A control that explains itself is better
than an empty space.

**The one exception is an indicator that a pilot can mistake for live data.** An indicator must
show clearly that it has no data. It must never show a number that looks correct.

## 3. Standing rules

These rules come from faults that occurred. Each one has a section that gives the evidence.

### Rule 1: in this SDK, a getter is not always one call

`getVisualSettingInfo` is a subscription with the name of a getter. It calls back approximately two
times each second, for ever. There is no method to stop it.

Before you give a continuation to any `get*(callback)` in this SDK, confirm how many times it
calls back. **Assume that it repeats until you prove that it does not.**

The behaviour is not the same for all getters. Confirm each call. Read section 9.

### Rule 2: the name "set…Listener" is not a reliable signal

The XStar version of `setFlyControllerInfoListener` COLLECTS listeners. The EVO2 version that this
application uses cleans itself, so a second call REPLACES the listener and is safe.

Confirm each implementation in the bytecode. Do not use the name.

### Rule 3: do not trust `onSuccess` from this camera

This camera answers "success" for operations that it does not do. There were three examples in one
session:

1. `StartRecording` two milliseconds after a change of mode answered `status: 0`, gave no
   `RECORD_START` event and wrote no file.
2. `setAspectRatio(Aspect_16_9)` answered success and changed nothing. The pictures were 4000 x
   3000 before and after.
3. `setMediaMode(VIDEO)` on the `UnknownCamera` object fails always. Each method on that object
   gives a communication error.

**Confirm the effect. Do not accept the callback as evidence.**

### Rule 4: read the AAR, and examine the call site

The public documentation does not give the field-level definitions. Decompile the AAR with `javap`.

Also examine the CALL SITE, not the default value of a parameter. `AutelVideoStreamer` has a
constructor with `mediaProjection = null` as a default. This makes it look as if the camera path is
the only path. It is not.

**Search the full SDK, not one part of it.** Three wrong statements of the form "the SDK cannot do
this" came from an examination of one subsystem.

### Rule 5: when one Autel value has the wrong sign, examine the others immediately

The gimbal pitch was corrected first. The altitude had the same fault and nobody saw it until a
pilot saw a negative altitude during a flight. Read section 6.

### Rule 6: correct a sign one time, where the data arrives

Do not correct a sign at the display and do not correct it in one consumer. Each value goes to
several consumers and they must agree.

### Rule 7: `com.taklite.client.tak` must not import an SDK

That package uses only the JDK, the Android framework, JSON and XML. This separation made the port
possible in a short time. It would also make a third manufacturer possible.

### Rule 8: make no permanent change to the controller

Do not use device-owner provisioning or any change to the system that a person cannot easily
reverse. Read section 11.

### Rule 9: test the hardware before you make a design around its limits

Read section 8. Three of five stated hardware limits were written as expected blockers. The first
one that a person tested was false.

## 4. Build environment

- **Use JDK 17.** This document said JDK 11 until 1 August 2026. JDK 11 cannot build this tree: the
  `rtsp-2.2.6-api.jar` file contains Java 17 class files (major version 61), so `kaptDebugKotlin`
  fails with "class file has wrong version 61.0, should be 55.0".
- Toolchain: Gradle 7.3.3, AGP 7.2.2, Kotlin 1.7.20, compileSdk 33, minSdk 21, targetSdk 29.
- Run `chmod +x gradlew` after each unzip or synchronization. The zip extraction and Synology Drive
  remove the execute permission. This has stopped the build two times.
- Build: `./gradlew assembleDebug`. Install: `adb install -r <apk>`.
- The application ID is `com.tak.uastoollite`. The manifest package is `com.autel.sdksample`. They
  are different. This is valid Android. **The FileProvider authority must use the application ID.**

### 4.1 The build uses a patched SDK, not the file from Autel

**This is essential. Without it the camera does not enumerate and no camera command operates.**

`app/build.gradle` uses `autel-sdk-release-xl726.aar`. The script
`buildsystem/patch-autel-sdk-camera-id.py` makes that file from the unchanged
`autel-sdk-release.aar`.

Make the patched file again after each change of SDK. The script stops with a clear message if the
shape of the vendor file changes. Section 5 gives the reason.

### 4.2 Target hardware

The Smart Controller V3 has Android 11 (API 30). Design for that version.

- Scoped storage applies. Prefer application-scoped paths or `MediaStore`.
- The system can remove a permission if the application is not used.
- The rules for a foreground service are the API 30 rules.

Do not increase `targetSdkVersion` above 30 without a new examination of the storage behaviour and
the permission behaviour on the real hardware.

**Two test devices, and they are not the same.** The OUKITEL RT3 (Android 13, 533 x 853 dp) is for
convenience. The Autel Smart Controller (Android 11, 1024 x 720 dp) is the real target. Always give
`-s <serial>` when both are connected. **A decision about a layout on the RT3 says nothing about
the controller.**

## 5. The camera enumeration fault and its correction

All of this was confirmed on the real aircraft with the SDK logging on.

### 5.1 The symptom

The SDK reported success with `CameraProduct.UNKNOWN` and gave back an `UnknownCamera` object.
Therefore `AutelProductHolder.camera` was not null, so the photo and record controls reached the
SDK and failed. The `xt706` value was null, so the IR, zoom and exposure controls said that the
camera was not connected. The live video operated, because it uses a different path.

### 5.2 The cause

**The camera gives its identity as `XL726`. No published Autel SDK contains that text.**

Each step was read from the bytecode and confirmed in the log:

1. The `SystemStatus` message of the camera carries `CameraType: "XL726"`.
2. `CameraMessageDisPatcher.transferType()` changes `XL720` and `XL705` to `XT705`, and changes
   `XL719`, `XL729`, `XK729`, `XL709` and `XL725` to `XT709`. **`XL726` is in neither group.**
   Therefore it passes through with no change. It is different by one digit.
3. `CameraProduct.find("XL726")` gives `UNKNOWN`.
4. `CameraMessageDisPatcher.notifyConnected()` tests for `UNKNOWN` and stops the CONNECTED message.
   That test is correct.
5. Therefore `CameraManager.connectStateChanged()` never gets a real product, and the SDK puts an
   `UnknownCamera` object in its place. **That object was never a camera.**
6. **The latch:** the 3-second retry of the SDK operates only while `isConnected` is false, and
   `setCameraCurrentData()` calls `notifyConnected()` BEFORE the callback that sets `isConnected` to
   true. Therefore the SDK tests the product one time, gets UNKNOWN, and then stops its own retry
   for ever.

### 5.3 No SDK update corrects this

All three public Autel repositories were examined on 1 August 2026. None contains `XL726`. The
`AndroidAdvanceSample` AAR is byte-identical to the file in this project. `MSDK2.0` has no `XT7xx`
classes at all and is a different product generation.

External evidence confirms that `XL726` belongs with `XL725`: a third-party tool lists "XL725,
XL726" as the camera identities of the same airframe, the Autel EVO II Dual 640T, for the visible
camera and the thermal camera. `XL725` already changes to `XT709`.

### 5.4 The correction

The script changes the text `XL719` to `XL726` in all of `classes.jar`. Both are 5 bytes, so the
positions, the constant pool and the class lengths do not change.

`XL719` is the donor because it is the only alias in BOTH lists that need a correction. One change
therefore corrects both. The two lists read different fields, so a correction to one alone would
not have corrected the other.

**The change must be global. It must not be only in `CameraProduct.class`.** A class file keeps one
copy of a text value, so the name of the enum constant, its value and the name of the field are one
entry. The other classes reach that field by its name. A change to `CameraProduct` alone gives
`NoSuchFieldError` when the application operates.

**Warning:** this build can no longer recognise genuine `XL719` hardware, and
`CameraProduct.XL719.name()` now returns "XL726". This is a deliberate exchange: an alias that this
airframe will never use, for one that it cannot fly without. Examine this again if you use this
repository with a different Autel camera. The correct solution is an Autel SDK that knows `XL726`.

### 5.5 SDK logging is off, and this hid the evidence

The most useful messages of the SDK go through `com.autel.log.AutelLog`, which does nothing while
its `mLogger` field is null. Nothing installs that logger.

`TestApplication.initAutelSdkLog()` now calls `com.autel.log.AutelLog.init(true, …)`. The value
`true` selects `DebugLog`, which also writes to `android.util.Log`. The value `false` selects a
path that writes nothing to logcat.

**Two classes have the same name.** `com.autel.util.log.AutelLog` is a thin wrapper and is NOT the
one that matters.

Useful tags: `ConnectDebug`, `MessageDisPatcher`, `AutelCameraConnectManager`, `camera_connect1`.

### 5.6 The camera file server is the ground truth

The camera file server at `http://192.168.1.11/DCIM/100MEDIA/` is reachable from the controller.
Use `adb shell curl`. This answers the question "did the camera actually record or take a picture".

## 6. Sign conventions

**Autel uses the opposite sign to DJI. Two times.** One negation where the data arrives corrected
each one.

**1. Gimbal pitch: Autel reports DOWN as POSITIVE.** A downward tilt read "GIMBAL n° UP". The
negation is in `setAngleListener`.

This value goes to three places: the HUD and the accuracy ring, the CoT `pitch=` value that goes to
TAK, and the SPI and AR calculations. A change to `PITCH_SIGN` would have corrected only the third
and left the HUD and the data of the team inverted. **`PITCH_SIGN` is a calibration scale. It is not
the correction for the sign.**

**2. Relative altitude: `LocalCoordinateInfo` uses NED, so DOWN is POSITIVE.** At +198 ft the HUD
read −198 ft. The test that settles a question of sign is this: a climb made the value more
negative, a descent moved it toward zero, and it was zero on the ground.

That fault was not cosmetic. `aglMeters()` returns `relAlt` only when it is positive, and 0 in all
other conditions. Therefore the slant-point calculation put each marker as if the aircraft were on
the ground. **That was the true cause of "the markers are not accurate".** It was not a calibration
problem. The value `isFlying` was also false for full flights.

## 7. Accuracy and calibration

### 7.1 The geometry

Measured against CloudTAK at approximately 200 ft AGL:

| Gimbal angle down | Result |
|---|---|
| 54 degrees | Accurate to less than 1 m |
| 30 degrees | Very accurate |
| 21 degrees | Approximately 10 ft of error |
| 6 degrees | Approximately 200 m of error |

**The ground error changes as 1/sin²(pitch).** At 200 ft AGL, one degree of aim error gives 5 ft at
54 degrees, 27 ft at 21 degrees and **323 ft at 6 degrees**. One metre of terrain error gives 0.4 m
at 54 degrees and 9.5 m at 6 degrees.

**Therefore calibrate at a shallow angle (15 to 25 degrees).** An error is almost invisible at a
steep angle. A test at 50 degrees proves almost nothing.

**The calculation is correct.** At a 21-degree angle the solved point was 0.1 degrees from the
camera bearing and 0.1 degrees from the camera pitch. On 4 August 2026 the drawn position of three
markers was calculated again from known coordinates and agreed with the application to one pixel.
**The error is in the INPUTS, not in the calculation.**

### 7.2 The AR overlay is not accurate for a point

A flight test on 4 August 2026 aimed at one target from three headings:

| Heading | Range | Implied bearing offset |
|---|---|---|
| 292 degrees | 225 m | 6.00 degrees |
| 264 degrees | 349 m | 4.49 degrees |
| 089 degrees | 358 m | 0.87 degrees |

The spread is 5.13 degrees. This is too large for an error of aim by the pilot. The error changes
with the heading. This is magnetometer error on the aircraft.

**One fixed offset cannot correct an error that changes with direction.** The correction is a
compass calibration of the aircraft.

The application says this in the AR menu and in the Field Guide.

### 7.3 Do not correct an altitude error with the pitch offset

The pitch offset also controls the SPI and the marker drop. If you change it to put an icon under
the crosshair, the icons look correct and the dropped markers become wrong. Nothing shows this to
the pilot.

Calibrate the pitch against terrain that the camera aims at.

### 7.4 Other measured items

- **The relative altitude changes between flights.** Ground readings were −1.56 m and −0.42 m on
  two flights. This is a change for each flight, from the barometer and the takeoff reference. It is
  not a fixed value that a person can calibrate. At 21 degrees, an altitude error of 1.5 m alone
  gives approximately 13 ft of marker error.
- **The coarse DTED tile was winning.** `DtedIndex.elevationAt()` returns the FIRST tile that covers
  a point, and `DtedStore.listFiles()` put them in order by file name. The name `.dt0` comes before
  `.dt2`, so the 900 m data won each time and the 30 m data that the pilot imported was never read.
  The correction sorts by `DtedTile.postSpacingDeg`. Confirm from the log: "finest post spacing
  2.7777E-4°" is DTED2. A value of "8.33E-3°" means DTED0.
- **The camera supplies the field of view.** Read `PORT-STATUS.md` section 5.
- **The zoom units are hundredths.** The value 100 is 1.0x.

## 8. Video

### 8.1 The stream is a screen capture

The stream is a MediaProjection screen capture of the full flight screen. It includes the camera
picture, the HUD, the map and the AR overlay.

**Therefore everything on the flight screen is in the feed of the team.**

An earlier version of this document said: "don't rebuild it as a screen-capture pipeline to match
DJI — the current approach is objectively better." **That instruction is now wrong.** The raw-frame
path was deleted. Section 12 gives the reason.

The screen capture was confirmed in flight on 4 August 2026: **the stream continued through a long
battery change with the aircraft not powered.** This is the reason that the screen capture replaced
the camera tap. Viewers continue to see the controller instead of a dead picture. **Treat this as a
test for each change that touches `ScreenCaptureService` or `VideoStreamerHolder`.** A change that
makes the stream depend on the aircraft has broken a property that was proved in the field.

### 8.2 The encoder must use VBR

**The application was asking this encoder for a bitrate mode that it does not have.** A capability
test settled it:

```
OMX.qcom.video.encoder.hevc   VBR=true  CBR=false  CQ=false   <- the hardware encoder
c2.android.hevc.encoder       VBR=true  CBR=true   CQ=true    <- the software encoder
```

`ScreenCaptureEncoder` was asking for `BITRATE_MODE_CBR`. Therefore the encoder made the I-frames
smaller to keep to a limit for each frame. That is the pulse in the picture.

Measured on the outgoing stream at the same 1600k target:

| | I-frame average | P-frame average | Ratio | Bitrate |
|---|---|---|---|---|
| CBR (before) | 28.7 KB | 11.3 KB | **2.53** | 1466 kbit/s |
| VBR (after) | 152.4 KB | 10.8 KB | **14.14** | 1607 kbit/s |

The operator confirmed on the live stream that the pulse had stopped. The average bitrate stayed
inside the budget. VBR moved the bits. It did not add bits.

`getOutputFormat()` on this encoder never reports `KEY_BITRATE_MODE`. **Therefore the ratio of the
frame sizes is the only measurement available.** If the pulse returns, examine the `frame mix:`
line in the log. A return to approximately 2.5 means that the encoder stopped using VBR.

### 8.3 The bitrate levels

`I_FRAME_INTERVAL_S` stays at 2 seconds. A value of 4 seconds was protection while VBR was not
proved. It made the join time and the loss-recovery time two times longer for no gain.

The bitrates are LOW 375k, STANDARD 800k and HIGH 1.8M.

**LOW is not on the same curve as the other two, and this is deliberate.** LOW is approximately 0.12
bits for each pixel for each frame. STANDARD and HIGH are approximately 0.077. LOW is the level for
a weak link because of its TOTAL bitrate, not because of the quality of each pixel. Small frames at
10 frames each second are cheap, so the application can give each pixel more bits.

**Therefore LOW can look better for each pixel than STANDARD. Do not "correct" this.** Do not
increase LOW toward 275k. The operator refused that picture.

**Do not use the software encoder.** `PREFER_SOFTWARE_ENCODER` stays false. It leaks memory.

These were refused by the operator. Do not propose them again: intra-refresh, and a longer group of
pictures.

### 8.4 The camera sends three different shapes

| Mode | Stream | Aspect |
|---|---|---|
| Photo | 1280 x 960 | 4:3 |
| Video | 1280 x 720 | 16:9 |
| IR | 640 x 512 | 5:4 |

Photo and video have the same width and a different height. Therefore video has less vertical field
of view.

**The camera cannot make them the same.** `setAspectRatio` is ignored, and `VideoResolution` has no
4:3 option.

`FlightActivity.applyVideoFill()` makes the video fill the screen in each mode. It keeps the aspect
ratio and cuts the edges. **The true centre stays on the reticle.** The centre is the fixed point on
purpose: the crosshair is the aiming reference for a marker drop, so a loss of the edges is
acceptable but a centre that moves would make each marker wrong.

**VIDEO is the resting mode, set AT CONNECT.** The camera keeps its mode through a power cycle, and
this application previously changed the mode only when a pilot pressed a button. Therefore it
started in the mode that the camera was left in. Setting it at connect is what makes "recording
never moves the picture" true.

**Still open:** the three modes have different apparent framing, because a fixed screen cuts each
shape differently. The multipliers are 4:3 x 1.103, 5:4 x 1.177 and 16:9 x 1.208. To make them the
same, use the real field of view from the camera and scale each mode so that the same real angle
fills the screen. **Measure the values and show them before you make this change**, because a
change of the visible camera to the much narrower thermal view could waste most of the picture.

## 9. Event: an aircraft hit a wall (2 August 2026)

**This is the most serious fault in this project. An aircraft hit a building.**

### 9.1 What happened

TAKPilot was the only connected client. The aircraft would not hold a hover. It moved in a circle.
The obstacle avoidance did not stop an impact with the wall of a shed.

The operator then did the test that settled it: they closed TAKPilot, opened Autel Explorer, and
flew the same aircraft in the same place minutes later. Explorer held a stable hover, drew live
obstacle distances, and refused to let the aircraft come nearer than 6 ft to the wall.

The logs prove that Explorer was not the cause. The two applications did not operate at the same
time: TAKPilot stopped at 14:48:26 and Explorer started at 14:49:59, which is 93 seconds later.

### 9.2 The cause, in this application

`AutelAvoidance.refresh()` gave its completion function directly to
`getVisualSettingInfo(callback)`. The assumption was that a getter calls back one time. **It does
not.** On this firmware that callback operates approximately two times each second, for ever.

**The same file already documented this, twelve lines above the code that ignored it.**

Two effects made it worse:

1. `applyAtConnect` had a guard at its entry, but the function that it gave to `refresh` then
   operated at 2 Hz for the full flight.
2. Each `setSwitch` completion called `refresh()` again. This left another permanent 2 Hz reader.
   **Each write made more readers, with no limit.**

That channel is not a side channel for settings. **It is the visual interface of the fly
controller — the same path that carries the vision positioning and the obstacle data.** To fill it
is one cause for both symptoms.

### 9.3 The correction and its proof

- `refresh` was replaced by `readOnce`, which uses an `AtomicBoolean` and calls its continuation
  exactly one time.
- `setSwitch` no longer starts a read when it completes.
- `refreshUnused` was deleted. It was dead code and an exact copy of the pattern that caused this.
- `applyAtConnect` now REFUSES to operate when the aircraft is in the air. **A safety switch must
  never be written under a flying aircraft.**

Confirmed in flight on 2 August 2026. The report of the operator was clear: "It works great! It
didn't let me crash."

| | Before | After |
|---|---|---|
| `enforcing LANDING_PROTECT` writes | 5 | **0** |
| Avoidance switch writes | 3 accepted, 1 timed out | **0** |
| State reads | repeating at 2 Hz | **1** |
| Hover | moving in a circle | stable |
| Obstacle braking | none, hit a wall | stops the aircraft |

### 9.4 Which getters repeat

The behaviour is not the same for all getters. This was measured:

- `getCommandStickMode`, `getGimbalDialAdjustSpeed`, `getYawCoefficient`: **one call, measured.**
- `getVisualSettingInfo`: **repeats at 2 Hz, measured.** This is the one that caused the event.
- `getDisplayMode`, `getIrColor`: the callbacks only set the user interface. A repeat would waste
  work but would not cause damage.

**Therefore the rule is "confirm each call". It is not "all getters repeat".**

### 9.5 A trap for the next person

**"It lands softly" is not evidence that LANDING_PROTECT operates.** That function uses the
downward vision sensors to REFUSE ground that is not suitable, such as water or a slope. It makes
the aircraft hover or move instead of descending. A soft landing also occurs when the function is
off. **The readback is the only confirmation.**

## 10. Event: Android stopped the application during a flight (2 August 2026)

At 19:52:34 the aircraft was at 200 ft, 7.5 m/s, approximately 250 m away.

```
19:52:34.307  lowmemorykiller: Kill 'com.tak.uastoollite' (18657), oom_adj 0, free 21672kB
19:52:34.423  Process com.tak.uastoollite has died: fg  TOP
```

`oom_adj 0` is the foreground application. It is the last thing that the system stops. The system
spent 22 seconds stopping more than 20 other processes first, and finally the launcher, before it
took the flight application. **This was exhaustion of the full device. It was not a leak in
TAKPilot.**

There was a **16-second period with no telemetry**. Stick control was never lost, because the radio
link is hardware and does not depend on the application. That is the only reason that this was
recoverable.

### 10.1 The application restarts at the Home screen, and this is correct

The first reading of this event said that the application must return to the flight screen to save
the pilot one action. **That instinct is wrong.**

A flight screen in a new process comes up with a dead aircraft link and a HUD frozen on old values
**that looks live**. The rule "start from the home screen" exists for this reason: a direct entry to
the flight screen never arms `AutelProductHolder.install()`.

Therefore the safe result is the Home screen. It arms the product listener and connects to TAK
again. The pilot then touches one control to enter the flight screen. **One deliberate touch on a
screen that operates is better than a screen that is frozen.**

`TakPilotHomeActivity.visitedThisProcess` and a test of `savedInstanceState` in
`FlightActivity.onCreate` now force this result even when Android has saved state.

### 10.2 Memory

The device has 3.76 GB. TAKPilot uses approximately 232 MB, of which approximately 86 MB is
graphics: the video decode, the map and the AR overlay.

`com.airdata.uav.app` also operates on the controller. **It cannot be removed from this
controller.** Do not propose that again.

Autel also ships `/system/bin/logrecord.sh`, a continuous log recorder that a person cannot stop
from a shell.

**The durable correction is less memory pressure**, so that the system does not select the flight
application.

## 11. Event: Autel Explorer takes the aircraft link by itself

**This is not a fault in TAKPilot. It is a behaviour of the platform that this application must
defend against.**

### 11.1 What happens

Autel Explorer is a preinstalled system application. **It can start without the pilot opening it**,
and when it starts it takes the aircraft USB link.

The cause is ordinary background work. Android starts the application PROCESS to run a Firebase
analytics job. That runs the `Application.onCreate` of Explorer, which starts its full aircraft
stack. **An analytics upload brings the flight stack with it.** A second waker, a Mapbox alarm,
operates every 3 minutes.

### 11.2 It killed a live flight

This was watched from beginning to end while the operator was flying:

```
13:42:21.971  Start proc 19230:com.autelrobotics.explorer  (analytics job)
13:42:25.764  camera changed: UNKNOWN (UnknownCamera)
13:42:25.770  AutelProductHolder: productDisconnected      <- 3.8 s after Explorer started
```

**3.8 seconds from an analytics job to a lost aircraft.** The pilot never opened Explorer and had no
method to know what happened. The symptom is only that the video stops.

A useful difference: **a timeout means that nothing answered** (two applications competing). **An
error means that the camera answered and refused** (a real fault).

### 11.3 The current defence: `ExplorerWatchdog`

`ActivityManager.killBackgroundProcesses()` stops the background process of Explorer. This was
measured against each dangerous condition:

- The Firebase wake, which is the path that killed the flight: **stopped.**
- After Explorer has taken the USB link: **stopped.**
- A foreground process, because the pilot opened Explorer: **not stopped, which is correct.** If a
  pilot is in Explorer for a purpose, such as a firmware update or a compass calibration, do not
  interrupt them.

It needs only the usual `KILL_BACKGROUND_PROCESSES` permission and **makes no permanent change**. It
stops a process that starts again at its next wake.

**Therefore this is a watchdog, not a disable.** It cannot stop Explorer waking. It makes each wake
short. It changes "3.8 seconds to a dead aircraft" into a short interruption.

It has three triggers: at application start, when Explorer sends its own USB broadcasts, and a slow
poll as a backstop.

### 11.4 The device-owner design was replaced. Do not build it again.

An earlier design used `dpm set-device-owner` and `setApplicationHidden` to hide Explorer.

**That design is dead.** It needed a permanent change to the controller of the operator, which rule
8 forbids. Removal of device-owner rights can need a factory reset. The design also had a gap that
no code in the application can close: if the application stopped while Explorer was hidden, Explorer
stayed hidden until the next start of the device. The application WAS stopped for memory during a
flight, so that gap was real.

`ExplorerWatchdog.kt` records this decision at the top of the file.

## 12. Instructions in the previous version that were wrong

1. **"Do not rebuild the video as a screen-capture pipeline."** The screen capture is now the
   shipping design, and it is confirmed better: the stream continues through a battery change.
   Section 8 gives the current design.
2. **The device-owner design for Explorer.** Approximately 150 lines described it as the chosen
   design and as built. It was replaced by `ExplorerWatchdog`. Section 11.4 gives the reason.
3. **"`XT706CameraInfo` push feed — found, not yet acted on."** This was done on 4 August 2026. The
   camera now supplies the field of view. Read `PORT-STATUS.md` section 5.

## 13. Open items

- **The zoom button label** starts at 1X and the application does not read the zoom from the camera
  at connect. Therefore the label can be wrong until the first touch. The zoom calculation is
  correct.
- **`IR_HFOV = 33.0`** comes from one measurement. The lens was identified from the aspect ratio and
  the change of focal length, not from a lens name. It is a fallback that the live value replaces.
- **Framing between camera modes** is not the same. Section 8.4 gives the method to correct it.
- **The failsafe events are not visible.** Two low-battery events occurred (at approximately 14 %
  and approximately 24 %, so the threshold of Autel is calculated, not fixed) and the application
  did not know about either one. It cannot tell the pilot or the team that the aircraft has taken
  control. On a situational-awareness screen this is a real omission.
- **The controller GPS** uses `getLastKnownLocation()`, which reads a cache that nothing fills. It
  needs a real `requestLocationUpdates()`.
- **IR changed DURING a recording** is not tested. IR changes the stream to 640 x 512. It is not
  known if the camera divides the file, damages it, or handles it correctly.
- **Certificate renewal before expiry** is not implemented.
- **Wi-Fi handoff** between hotspots is not observed during a flight.
