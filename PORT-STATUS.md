# TAKPilot2-Autel — Port Status

**Base:** TAKPilot2 (DJI MSDK v5, M30) → Autel `AndroidAdvanceSample` (MSDK v1.5, EVO II V3 / Smart Controller V3)
**App ID:** `com.tak.uastoollite` · **App Key:** wired into `TestApplication.java` (registered 2026-07, Phase 0)
**Generated:** 2026-07-20

## ⚠️ Scope decision recorded

This port adopts the "app becomes the flight interface" model (Phase 0 tracker §12.2 —
previously listed as last-resort). Like TAKPilot2 on DJI, this app **replaces Autel
Explorer** during TAK operations: it owns the SDK connection, shows the live video, and is
the operator's screen. Consequences:

- The **0.6 Explorer-concurrency go/no-go is dissolved** — Explorer is not running.
- Sticks/RTH still work (stick input rides Skylink, not the app). But everything Explorer
  provided beyond raw sticks — camera settings UI, mission planning, firmware updates,
  warnings UX — is NOT in this app. Keep Explorer installed for pre-flight/config; fly
  TAK missions from TAKPilot2.

## What compiles (verified)

The entire new/ported code base was type-checked in this environment against the **real**
`autel-sdk-release.aar`, **real** osmdroid 6.1.14, **real** pedro `rtsp` 2.2.6, and
Android API 30 (`android.jar`): **0 errors**. (androidx + R were stubbed for the check —
Android Studio regenerates those for real.) What was NOT possible here: running aapt/full
Gradle assembly, so expect at most minor resource/manifest nits on first build, not code
rework.

## Component map

| TAKPilot2 (DJI) | This port | Status |
|---|---|---|
| `com.taklite.client.tak` (TLS CoT client, CotBuilder/Parser, cert enrollment, channels, Mission API) | copied **verbatim** | ✅ compiles |
| `DroneTakBridge` (KeyManager polling) | `AutelTakBridge` (listener-cache, per tracker §4.4 field map) | ✅ compiles |
| `CameraSlantPoint` | ported verbatim (pure math) | ✅ |
| `TakForegroundService`, `TakAutoConnect`, `TakMissionManager`, `TakConnectActivity`, `DataSyncActivity` | ported (package/import swaps) | ✅ |
| `DroneVideoStreamer` (surface → re-encode → RTSP) | `AutelVideoStreamer` — **raw H.264/H.265 frames from `AutelCodecListener` pushed directly, zero transcode** (better than the DJI original; matches the project guide's Phase-2 "passthrough" preference) | ✅ |
| `TakMapMarkers` / `TakDropMarkers` (DJI map kit) | ported to **osmdroid** (logic 1:1, incl. persistence, local-hide, feed-scoped sends) | ✅ |
| `DefaultLayoutActivity` (2,244-line DJI uxsdk flight screen) | `FlightActivity` — rebuilt: fullscreen `AutelCodecView`, telemetry HUD, TAK/video status, expandable TAK map, drop pin, pin-at-camera-look-point, video toggle | ✅ (feature subset — see below) |
| `TAKPilot2HomeActivity` | `TakPilotHomeActivity` (Button Mapping slot → "SDK Test Tools" opening the stock sample) | ✅ |

## Omitted (not portable / N/A on this hardware)

- **`RcButtonManager`** — DJI RC Plus physical-key mapping. Smart Controller V3 key events
  unknown; wire up later by logging `onKeyDown` keycodes on real hardware.
- **`SpeakerMegaphoneManager`, `PayloadAccessoryManager`** — M30 gimbal payloads (speaker
  etc.). No EVO II equivalent.
- **`ArOverlayView`** — deferred (marked experimental in TAKPilot2 itself). Its data feeds
  (bridge telemetry cache, `TakDropMarkers.pinsForAr()`, `TakMapMarkers.isHidden()`) are
  all ported and ready when you want it.
- **PIP dual-lens control, HSI strip, camera/lens on-screen controls** from the DJI flight
  screen — DJI-widget-specific. EO/IR switching for FOV purposes is exposed as
  `AutelTakBridge.activeLens` (not yet wired to a camera listener).

## First-build checklist (Android Studio)

1. Open the project root; let Gradle sync (AGP 7.2.2 / Gradle 7.3.3 — same as your working
   Phase-0 setup; JCenter fixes already applied).
2. New deps resolve from mavenCentral/JitPack: `osmdroid-android:6.1.14`,
   `com.github.pedroSG94.rtmp-rtsp-stream-client-java:rtsp:2.2.6`.
3. `./gradlew assembleDebug` → sideload `app-debug.apk`.
4. `takpilot2_logo` is a placeholder vector — drop the real PNG into
   `res/drawable-nodpi/` and delete the XML if you have the artwork.

## Bench-test items (no aircraft)

1. **TAK enroll + connect** (TakConnectActivity) against your server — this path is
   byte-identical to TAKPilot2's, so it should behave exactly as it does on DJI.
2. Channels pull, Data Sync feeds, drop pins from the map — all server-side features work
   without an aircraft.

## Flight-test / calibration items (marked `⚠` in code)

| Item | Where | Note |
|---|---|---|
| HAE altitude sanity | `AutelTakBridge` (`EvoGpsInfo.getAltitude()`) | Tracker §4.1: high-confidence structural inference; spot-check vs a known HAE |
| GPS accuracy units | `ACC_DIVISOR` (assumed mm→m) | Tracker §4.2 |
| Gimbal pitch sign | `PITCH_SIGN` | Tracker item #6; flip if SPI lands wrong side |
| Gimbal yaw frame + offset | `BEARING_MODE_RELATIVE`, `BEARING_OFFSET_DEG` | Tracker item #7; DJI needed +105°, Autel starts at 0. Both candidate bearings logged per SPI push — one flight resolves it |
| 640T FOV constants | `EO_HFOV/VFOV`, `IR_HFOV/VFOV` | Spec-sheet starting values; tune against the live cone in ATAK |
| ~~**Video: codec listener vs `AutelCodecView` concurrency**~~ | ~~`AutelVideoStreamer`~~ | **N/A (2026-08-03).** Only applied to the aircraft-camera raw-frame tap, now deleted. The shipping stream is a MediaProjection screen capture of the composited screen — no second codec tap, no contention with the display view. Verified on-device. |
| H.264 vs H.265 downlink | `AutelVideoStreamer.sniffParameterSets` | Handles both; logcat prints which was detected |
| `codec.cancel()` on stream stop | `AutelVideoStreamer.stop()` | If it also kills the display view's feed, remove the cancel and rely on the `stopped` guard |

## Divergences from the project guide (inherited from TAKPilot2, kept for least resistance)

- **Cert storage:** enrollment certs land as `.p12` files in app-private `filesDir` with
  the conventional passphrase — not Android Keystore (guide §4.2). App-private storage on
  a non-rooted controller is acceptable; Keystore migration is a contained TODO in
  `TakCertEnroller`.
- **Auto re-enrollment before expiry** (guide §4.2) is not implemented — TAKPilot2
  reconnects with saved certs and requires a manual re-enroll when they expire. Worth
  adding before fleet rollout if your server issues short-lived certs.
- **CoT type:** taklite's `CotBuilder` emits its own drone PLI schema (verify it uses
  `a-f-A-M-H-Q` — locked type per tracker §11.1 — during bench validation; adjust
  `CotBuilder.buildDronePLI` if not).
- **DTED terrain SPoI** (tracker §4.6): not in TAKPilot2; the port keeps its flat-ground
  slant model. `AutelTakBridge` already caches `heightMeanSeaLevel` and the code comment
  marks the exact splice point for the DTED ray-march when you're ready.

## Architecture notes

- `AutelProductHolder` owns the single global `Autel.setProductConnectListener` slot and
  re-installs on every Home/Flight `onResume`, because the stock sample's
  `ProductActivity` (reachable via "SDK Test Tools") overwrites it.
- Bridge/TAK/stream lifecycles are owned by the foreground service + process-wide holders,
  not any activity — identical to TAKPilot2. Leaving the flight screen does not stop
  telemetry or video.
