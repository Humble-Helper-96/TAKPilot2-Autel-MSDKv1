# TAKPilot2 — Autel Mobile SDK v1 port

**Written in Simplified Technical English (ASD-STE100).**

TAKPilot2 for the Autel EVO II Dual 640T V3 and the Smart Controller V3. It uses Autel Mobile SDK
v1.x.

The application does these functions:

- It flies the aircraft.
- It sends the position, the attitude and the battery state to a TAK server as CoT.
- It sends the flight screen to a media server as RTSP.
- It puts TAK markers on the map and controls them.
- It draws markers on the live video as an augmented-reality (AR) overlay.

This application is related to the [DJI MSDK v4 port](https://github.com/Humble-Helper-96/TAKPilot2-DJI-MSDKv4).
It is not a direct copy. The parts that do not touch the SDK are the same: the TAK client, the CoT
build and parse functions, the certificate enrollment and the channel control. Each part that
touches the aircraft was written again for Autel MSDK v1.x. That SDK uses listeners only. It has no
synchronous polling. This gave a different design for the telemetry, the camera and the video.

**Tested in flight on 3 August 2026 and 4 August 2026.** Read `FLIGHT-TEST-CHECKLIST.md`.

## 1. Augmented reality: read this first

**The AR overlay is for general awareness of an area. It is not accurate for a point.**

A flight test measured a bearing error between 1 degree and 6 degrees. At 350 m this moved a marker
up to 27 m to the side. The cause is the magnetometer of the aircraft. The error changes with the
direction that the aircraft faces. One fixed offset in the application cannot correct it.

Do not use the AR overlay to select one building from a row of buildings. Use it to know where to
look. To get an accurate position, put the crosshair on the object and put a marker.

`PORT-STATUS.md` section 4 gives the measurements.

## 2. Markers that your team shares

**A marker that another user sends to this aircraft stays.** The application keeps it for 72 hours
after the last time it receives it, and it keeps it when you start the application again.

To remove one, touch it on the map and select Delete. This removes it from this aircraft only.

The application identifies a marker by the `archived` marker that the sender puts on the wire. A
client that does not set it gets no persistence: its markers go away as before. `PORT-STATUS.md`
section 8 gives the measurements and the reason.

**The application does not show METAR weather stations.** Their content is in the remarks, which
this application does not show.

## 3. Where the code is

This tree is the standard Autel EVO II MSDK sample application. The TAKPilot2 work is on top of it.
The code outside the two packages below is the Autel sample. It has almost no changes.

| Package | Contents |
|---|---|
| `com/autel/sdksample/tak/` | The port. This includes the flight screen, the home screen, the HUD views (crosshair, AR overlay, obstacle arcs, battery, signal and toggle widgets), the bridge between Autel and TAK, the CoT push, the DTED terrain data, the FAA UASFM data, the markers, the screen-capture video, the controllers for the flight limits, the control rates and the avoidance function, the Explorer watchdog and the field guide. |
| `com/taklite/` | The TAK core. It does not depend on the SDK. It is the same as in TAKPilot2: the TLS CoT client, CotBuilder, CotParser, the certificate enrollment and the channels. |

Gradle supplies the third-party libraries. They are not in this tree. These are the RootEncoder RTSP
client (`com.github.pedroSG94...:rtsp:2.2.6`, Apache-2.0) and osmdroid
(`org.osmdroid:osmdroid-android:6.1.14`, Apache-2.0). The Autel MSDK is the `.aar` file in
`app/libs/`.

## 4. Documents

The Markdown documents contain the design record. They give the reasons for the design. They also
give the methods that did not work, the values measured in flight, and the conditions that look like
faults but are not.

| Document | Read it when |
|---|---|
| `TAKPILOT2_AUTEL_PORT_PLAN.md` | You start work. This is the full project reference. |
| `PORT-STATUS.md` | You want the component map, the accuracy limits and the calibration items. |
| `FLIGHT-TEST-CHECKLIST.md` | You prepare to fly. |
| `REVIEW_2026-08-03_*.md` | You want the review records for the code, the UI, the language and the security. |
| `TAKPilot2-Autel-HANDOFF.md` | You continue the work in a new session. |

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

The application uses the public AOSP platform test key (`platform.keystore`, alias `android`,
password `android`). The application then operates with system privileges on the Smart Controller.
This key is public. It is not a secret. This is the reason that the build signs correctly with no
more steps.

### 5.1 You must supply your own map keys

The file `app/src/main/AndroidManifest.xml` contains an AMap key and a Google Maps key. These keys
belong to this project. Your build will get a rate limit or a refusal. Register your own keys and
put them in the file.

The core TAK functions do not need Google services or AMap services. osmdroid draws the map on the
flight screen. The keys are only for the map screens of the sample application.

## 6. Configuration

This repository contains no server addresses, no certificates and no credentials.

Configure these items in the application under **Pre-Flight Setup**: the TAK enrollment, the server
address, the channels, the video destination, the DTED terrain tiles and the FAA airspace data. The
application keeps them on the device. A new installation starts with no data.

## 7. Hardware notes

The application was developed and tested with an EVO II Dual 640T V3 on a Smart Controller V3
(Android 11, 1024 x 720 dp).

Two items are very different from the DJI port:

- **The video is a MediaProjection screen capture** of the full flight screen. This includes the
  camera picture, the HUD, the map and the AR overlay. The application encodes it again to H.265 and
  sends it with RTSP. Therefore the stream continues through a link loss or a battery change. It
  does not need a second tap on the codec. There is no per-frame decoder.
- **The camera zoom is digital only.** The visible lens of the 640T is fixed. The SDK gives only
  `setDigitalZoomScale`. The raw units are hundredths: the value 100 is 1.0x.

The EVO II does not hold a fully stable hover. It moves slowly by a few degrees. This is GNSS
velocity noise from the aircraft. Buildings cause multipath. It is not an application fault. The
application reports the stable position correctly during this time.

## 8. Status

Phases 0 to 5 are complete and confirmed in flight.

These functions are confirmed: live position and SPI on a second TAK client, the gimbal bearing
model, the GPS, HAE, field-of-view, IR and pitch calibration, RTH, the link-loss failsafe, recovery
from background operation, and the screen-capture video.

The camera now supplies the field of view. The application does not need a calibration value for it.

The aim offsets are properties of each airframe. Do the calibration in `FLIGHT-TEST-CHECKLIST.md`
section A4 for each aircraft. Read section 1 first: a fixed bearing offset cannot correct the
compass error.

These items are accepted or delayed. The review files give the details:

- The standard TAK automatic-enrollment trust model (`REVIEW_2026-08-03_SECURITY.md`, items 1 to 3).
- The move of the client key to the Android Keystore.
- The automatic renewal of certificates before they expire.
- Small improvements to the user interface.

## 9. License

The TAKPilot2 work in this tree is the work of the author. This is `com/autel/sdksample/tak/`,
`com/taklite/` and the documents.

The tree around it is the Autel EVO II MSDK sample application. It is subject to the
[developer terms](https://developer.autelrobotics.com) of Autel. The Autel MSDK `.aar` file belongs
to Autel and has a separate license.

The third-party libraries that Gradle supplies (RootEncoder RTSP and osmdroid) are Apache-2.0.

`README_CN.md` is the original readme of the Autel sample. It is kept for reference.
