# TAKPilot2 — Autel Mobile SDK v1 port

TAKPilot2 for the **Autel EVO II Dual 640T V3 / Smart Controller V3**, built on **Autel Mobile
SDK v1.x**. The app flies the aircraft, streams live position/attitude/battery to a TAK server as
CoT, pushes the flight screen to a media server as RTSP, drops and manages TAK markers, and
projects markers onto the live video as an AR overlay.

This is the Autel-hardware sibling of the [DJI MSDK v4 port](https://github.com/Humble-Helper-96/TAKPilot2-DJI-MSDKv4).
Rather than a literal port, the SDK-agnostic core (TAK client, CoT build/parse, cert enrollment,
channel scoping) is reused unchanged, and every aircraft-touching piece was written against Autel
MSDK v1.x — a **listener-only** SDK with no synchronous polling, which drove a different telemetry,
camera and video design from the DJI side.

**Flight-validated on hardware, 2026-08-03** (see `FLIGHT-TEST-CHECKLIST.md`).

## Where the code is

This tree is Autel's stock EVO II MSDK sample app with the TAKPilot2 work layered on top.
Everything outside the two packages below is Autel's sample and largely untouched.

| Package | What |
|---|---|
| `com/autel/sdksample/tak/` | The port — flight screen, home screen, custom HUD views (crosshair, AR overlay, obstacle arcs, battery/signal/toggle widgets), the Autel↔TAK bridge, CoT push, DTED terrain, FAA UASFM, markers, screen-capture video, flight-limits / control-rates / avoidance controllers, Explorer watchdog, field guide |
| `com/taklite/` | SDK-agnostic TAK core — reused from TAKPilot2 essentially unchanged (TLS CoT client, CotBuilder/Parser, cert enrollment, channels) |

Third-party libraries are pulled as Gradle dependencies, **not** vendored: the RootEncoder RTSP
client (`com.github.pedroSG94...:rtsp:2.2.6`, Apache-2.0) and osmdroid (`org.osmdroid:osmdroid-android:6.1.14`,
Apache-2.0). The Autel MSDK itself is the `.aar` under `app/libs/`.

## Read the docs first

The Markdown docs carry the full design record — not just what was built but why, including the
dead ends, the field-measured findings, and the things that look like bugs but aren't.

| Doc | Read when |
|---|---|
| `TAKPILOT2_AUTEL_PORT_PLAN.md` | **Start here.** The full project reference — architecture, phase status, calibration constants, and the release-blocker post-mortems |
| `PORT-STATUS.md` | The component map (DJI → Autel), what's ported, and the flight-test/calibration knobs |
| `FLIGHT-TEST-CHECKLIST.md` | Before flying — the ordered ground/air/recovery checklist and the symptom→knob table |
| `REVIEW_2026-08-03_*.md` | Code-soundness, UI, language and security review records, with dispositions |
| `TAKPilot2-Autel-HANDOFF.md` | Resuming the work in a new session |

They are snapshots, not a live view. `git log --oneline` is the reliable changelog; re-verify any
claim against the source before writing code against it.

## Building

Pinned toolchain — **these versions matter**:

- Gradle **7.3.3**, AGP **7.2.2**
- JDK **17**
- Kotlin **1.7.20**
- compileSdk **33**, minSdk **21**, targetSdk **29**

```bash
JAVA_HOME=<your-jdk-17> ./gradlew assembleDebug
```

The app is signed with the **public AOSP platform test key** (`platform.keystore`, alias/pass
`android`/`android`) so it can run with system privileges on the Smart Controller. That key is
public — it is not a secret, and it is why the build signs cleanly out of the box.

### You need your own map API keys

`app/src/main/AndroidManifest.xml` carries an **AMap** key and a **Google Maps** key. They are
registered to this project and will be rate-limited or rejected for you — register your own and
replace them. The app does not need Google/AMap services for its core TAK function (osmdroid draws
the flight-screen map); the keys are only for the stock-sample map screens.

## Runtime configuration

No server details, certificates or credentials are in this repo. TAK enrollment, server host,
channels, video destination, DTED terrain tiles and FAA airspace data are all configured in-app
under **Pre-Flight Setup** and stored on the device. A fresh install starts empty.

## Hardware notes

Developed and field-tested against an **EVO II Dual 640T V3 on a Smart Controller V3** (Android 11,
1024×720dp). Two things differ sharply from the DJI port:

- **Video is a MediaProjection screen capture** of the whole flight screen (FPV + HUD + map + AR),
  re-encoded to H.265 and pushed over RTSP — so the stream survives a link drop or battery swap and
  needs no second codec tap. There is no custom per-frame decoder like the DJI build's.
- **The camera zoom is digital only** (the 640T's visual lens is fixed; the SDK exposes only
  `setDigitalZoomScale`).

The EVO II does not hold a rock-solid hover — a slow few-degree yaw/drift is aircraft-side GNSS
velocity noise (multipath near structures), not an app fault; the app reports the aircraft's stable
position faithfully throughout.

## Status

Phases 0–5 complete and **field-confirmed** (2026-08-03): live PLI/SPI on a second TAK client,
gimbal bearing resolved to the absolute model, GPS/HAE/FOV/IR/pitch calibration, RTH, link-loss
failsafe, backgrounding recovery, and the screen-capture video path. Aim offsets are **per-airframe**
(re-run the calibration in `FLIGHT-TEST-CHECKLIST.md` §A4 for each aircraft).

Consciously accepted / deferred, documented in the review files: the standard TAK auto-enrollment
trust model (`REVIEW_2026-08-03_SECURITY.md` #1–#3), Android Keystore migration for the client key,
cert auto-renewal before expiry, and minor UI polish. `git log --oneline` is the changelog.

## License

The TAKPilot2 additions in this tree (`com/autel/sdksample/tak/`, `com/taklite/`, and the docs) are
the author's work. The surrounding tree is Autel's EVO II MSDK sample app, subject to Autel's
[developer terms](https://developer.autelrobotics.com); the bundled Autel MSDK `.aar` is Autel's and
separately licensed. Gradle-resolved third-party libraries (RootEncoder RTSP, osmdroid) are
Apache-2.0. `README_CN.md` is the original Autel sample readme, kept for reference.
