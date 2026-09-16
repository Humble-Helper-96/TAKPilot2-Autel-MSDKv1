# TAKPilot2 — Autel Mobile SDK v1 port

**Written in Simplified Technical English (ASD-STE100).**

TAKPilot2 for the Autel EVO II Dual 640T V3 and the Smart Controller V3. It uses Autel Mobile SDK
v1.x.

The application does these functions:

- It flies the aircraft.
- It sends the position, the attitude and the battery state to a TAK server as CoT.
- It sends the flight screen to a media server as RTSP or SRT.
- It puts TAK markers on the map and controls them.
- It draws markers on the live video as an augmented-reality (AR) overlay.
- It shows the visible camera, the thermal camera, or the thermal picture in a window on the
  visible picture (PIP). The camera makes the composite.

It is one of three TAKPilot2 applications, with the
[DJI MSDK v4 port](https://github.com/Humble-Helper-96/TAKPilot2-DJI-MSDKv4) for a phone and the
[DJI MSDK v5 port](https://github.com/Humble-Helper-96/TAKPilot2-DJI-MSDKv5) for the DJI RC Plus 2.
A pilot changes airframe and finds the same screens, the same controls in the same places, and the
same words. One user-interface specification, kept beside the three trees, is the source of truth
for all three.

The parts that do not touch the SDK are the same in the three trees: the TAK client, the CoT build
and parse functions, the certificate enrollment and the channel control (`com/taklite/`). Each part
that touches the aircraft was written for Autel MSDK v1.x. That SDK uses listeners only. It has no
synchronous polling. This gave a different design for the telemetry, the camera and the video.

**The current release is v2.3.2 (16 September 2026).** Each release is bench-tested and flown by
the operator before it goes to the fleet. `FLIGHT-TEST-CHECKLIST.md` gives the checks. The version
numbers and the record of each version are in `app/build.gradle`.

## 1. Augmented reality: read this first

**The AR overlay is for general awareness of an area. It is not accurate for a point.**

The overlay was audited on 14 September 2026 and seven of ten faults were corrected and flown.
The overlay now projects with the field of view of the lens that is on the screen, it rotates a
target into the camera frame before it projects (a pitched gimbal put a target up to 15 degrees
to the side), and it uses the terrain data for the height of a pin, for the height of the aircraft
above sea level and for the geoid.

The error that remains is the magnetometer of the aircraft. A flight test measured a bearing error
between 1 degree and 6 degrees. At 350 m this moved a marker up to 27 m to the side. The error
changes with the direction that the aircraft faces. One fixed offset in the application cannot
correct it, and the application does not try to (operator, 15 September 2026).

Do not use the AR overlay to select one building from a row of buildings. Use it to know where to
look. To get an accurate position, put the crosshair on the object and put a marker.

`PORT-STATUS.md` section 4 gives the early measurements. The v2.2.0 entry in `app/build.gradle`
gives the audit list and the measurements of the corrections.

## 2. Markers that your team shares

**A marker that another user sends to this aircraft stays.** The application keeps it for 72 hours
after the last time it receives it, and it keeps it when you start the application again.

**A shared marker keeps its colour.** A sender puts a short stale time on a marker (TAK Aware ten
minutes, CloudTAK a few seconds). The application does not remove the marker at that time and does
not draw it grey at that time (v2.3.2). Live clients and aircraft tracks still go grey when their
report is stale.

To remove one, touch it on the map and select Delete. This removes it from this aircraft only. A
delete from the network removes it too.

The application identifies a marker by the `archived` mark that the sender puts on the wire. A
client that does not set it gets no persistence: its markers go away as before. `PORT-STATUS.md`
section 8 gives the measurements and the reason.

**The application does not show METAR weather stations.** Their content is in the remarks, which
this application does not show.

## 3. Where the code is

This tree is the standard Autel EVO II MSDK sample application. The TAKPilot2 work is on top of it.
The code outside the two packages below is the Autel sample. It has almost no changes.

| Package | Contents |
|---|---|
| `com/autel/sdksample/tak/` | The port. This includes the flight screen, the home screen, the HUD views (crosshair, AR overlay, obstacle arcs, battery, signal and toggle widgets), the camera views (visible, thermal, PIP) and the blend format, the bridge between Autel and TAK, the CoT push, the DTED terrain data, the FAA UASFM data, the markers, the screen-capture video for RTSP and SRT, the controllers for the flight limits, the control rates and the avoidance function, the Explorer watchdog, the debug log screen and the field guide. |
| `com/taklite/` | The TAK core. It does not depend on the SDK. It is the same in the three trees: the TLS CoT client, CotBuilder, CotParser, the certificate enrollment and the channels. The master copy is `taklite-core` beside the three trees, and `check-taklite.sh` there shows a tree that has drifted. |
| `com/pedro/srt/`, `com/pedro/common/` | The SRT transport, taken from RootEncoder 2.4.7 (Apache-2.0). It is in the tree because the published module cannot be used as a dependency here; `app/build.gradle` gives the reason. |

Gradle supplies the other third-party libraries. They are not in this tree. These are the
RootEncoder RTSP client (`com.github.pedroSG94...:rtsp:2.2.6`, Apache-2.0) and osmdroid
(`org.osmdroid:osmdroid-android:6.1.14`, Apache-2.0). The Autel MSDK is the `.aar` file in
`app/libs/`.

## 4. Documents

The Markdown documents contain the design record. They give the reasons for the design. They also
give the methods that did not work, the values measured in flight, and the conditions that look like
faults but are not.

| Document | Read it when |
|---|---|
| `CLAUDE.md` | You start any work. The rules, the safety rules from real incidents, and the state of each release. |
| `app/build.gradle` | You want the record of each version. Every version has an entry with what changed and what was measured. |
| `TAKPILOT2_AUTEL_PORT_PLAN.md` | You want the full project reference. |
| `PORT-STATUS.md` | You want the component map, the accuracy limits and the calibration items. |
| `FLIGHT-TEST-CHECKLIST.md`, `FLIGHT-TEST-v2.0.*-ACTIONS.md` | You prepare to fly, or you want the results of a flight. |
| `CHANNELS-FINDINGS.md`, `CHANNELS-FOR-OTHER-DEVS.md` | You touch the TAK channels. The first is the evidence; the second is what went to the other developers. |
| `TAKPilot2-LowBandwidthVideo-DevNotes.md`, `VIDEO-STREAM-VBR-FIX.md` | You touch the video encoder or the transport. |
| `TAKPilot2-DebugLogging-DevNotes.md` | You touch the debug log. |
| `REVIEW_2026-08-03_*.md`, `REVIEW_2026-08-07_AUDIT.md` | You want the review records for the code, the UI, the language and the security. |
| `TAKPilot2-Autel-HANDOFF.md`, `V1_5_9_PLAN.md` | You want the history of the early work. |

The user-interface specification (`TAKPILOT2-UI-SPEC.md`) and the conformance ledger
(`TAKPILOT2-UI-CONFORMANCE.md`) are beside the three trees, not in this one. The specification
outranks every UI note in this tree.

These documents are records of one time. They are not a live view of the code. Use
`git log --oneline` for the list of changes. Examine the source code before you write new code
against a statement in a document.

## 5. How to build

Use these versions. **The versions are important.**

- Gradle 7.3.3 and AGP 7.2.2
- JDK 17
- Kotlin 1.7.20
- compileSdk 33, minSdk 21, targetSdk 29

```bash
JAVA_HOME=<your-jdk-17> ./gradlew assembleDebug
```

`./gradlew :app:assembleRelease` makes the signed release. `./gradlew :app:testDebugUnitTest` runs
the unit tests for the pure-logic core.

The application uses the public AOSP platform test key (`platform.keystore`, alias `android`,
password `android`). The application then operates with system privileges on the Smart Controller.
This key is public. It is not a secret. This is the reason that the build signs correctly with no
more steps.

**A release on GitHub is the tag and the notes only.** No APK is on GitHub. The signed APK goes to
the fleet from a folder beside the tree.

### 5.1 You must supply your own map keys

The file `app/src/main/AndroidManifest.xml` contains an AMap key and a Google Maps key. These keys
belong to this project. Your build will get a rate limit or a refusal. Register your own keys and
put them in the file.

The core TAK functions do not need Google services or AMap services. osmdroid draws the map on the
flight screen. The keys are only for the map screens of the sample application.

## 6. Configuration

This repository contains no server addresses, no certificates and no credentials.

Configure these items in the application under **Pre-Flight Setup**: the TAK enrollment, the server
address, the channels, the video servers (RTSP or SRT, two servers, a port and a passphrase for
each), the DTED terrain tiles and the FAA airspace data. The application keeps them on the device. A
new installation starts with no data.

## 7. Hardware notes

The application was developed and tested with an EVO II Dual 640T V3 on a Smart Controller V3
(Android 11, 1024 x 720 dp).

Items that are different from the DJI ports:

- **The video is a MediaProjection screen capture** of the full flight screen. This includes the
  camera picture, the HUD, the map and the AR overlay. The application encodes it again and sends it
  with RTSP or SRT. H.264 (High profile) is the default. H.265 is a choice on the Video Servers
  screen; the operator does not use it until every client on the net can decode it. The stream
  continues through a link loss or a battery change. There is no per-frame decoder.
- **The camera zoom is digital only.** The visible lens of the 640T is fixed. The SDK gives only
  `setDigitalZoomScale`. The raw units are hundredths: the value 100 is 1.0x. Zoom is refused in
  PIP: the camera ignores it and reports success.
- **The camera makes the PIP composite.** The controller receives one stream. A tap on the PIP
  pill or the C1 key changes between visible and PIP; a second pill makes the thermal picture full
  screen. The camera does not change lens while it records, and it reports success anyway, so the
  application refuses the change while it records.
- **The controller has a hardware shutter and a hardware record button.** The shutter puts the
  camera in stills mode and it stays there. REC records from any mode.

The EVO II does not hold a fully stable hover. It moves slowly by a few degrees. This is GNSS
velocity noise from the aircraft. Buildings cause multipath. It is not an application fault. The
application reports the stable position correctly during this time.

## 8. Status

The application is on a six-controller public-safety fleet. The releases are the tags on this
repository; `CLAUDE.md` gives the state of each one.

These functions are confirmed in flight: live position and SPI on a second TAK client, the gimbal
bearing model, the GPS, HAE, field-of-view, IR and pitch calibration, RTH and cancel RTH, the
link-loss failsafe, recovery from background operation, the screen-capture video on RTSP and SRT,
the TAK channels by the server's own method, shared markers, PIP and full thermal, and the AR
corrections of September 2026.

The camera supplies the field of view. The application does not need a calibration value for it.
The aim offsets are properties of each airframe. Do the calibration in `FLIGHT-TEST-CHECKLIST.md`
section A4 for each aircraft. Read section 1 first: a fixed bearing offset cannot correct the
compass error.

Open items, in order:

- The magnetometer bias by heading (section 1). Not corrected by decision.
- The telemetry-to-video latency in the AR overlay, and gimbal roll.
- ADS-B heights are pressure altitude from the gateway and draw high or low by the day's
  altimeter setting.
- The RF power region of the controller cannot be set. It is an Autel limit.

These items are accepted or delayed. The review files give the details:

- The standard TAK automatic-enrollment trust model (`REVIEW_2026-08-03_SECURITY.md`, items 1 to 3).
- The move of the client key to the Android Keystore.
- The automatic renewal of certificates before they expire.

## 9. License

The TAKPilot2 work in this tree is the work of the author. This is `com/autel/sdksample/tak/`,
`com/taklite/` and the documents.

The tree around it is the Autel EVO II MSDK sample application. It is subject to the
[developer terms](https://developer.autelrobotics.com) of Autel. The Autel MSDK `.aar` file belongs
to Autel and has a separate license.

The third-party libraries that Gradle supplies (RootEncoder RTSP and osmdroid) and the RootEncoder
SRT code in the tree are Apache-2.0. `NOTICE.md` gives the attributions.

`README_CN.md` is the original readme of the Autel sample. It is kept for reference.
