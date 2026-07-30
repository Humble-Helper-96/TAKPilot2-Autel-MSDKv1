Sandbox is back and the working tree survived intact. Writing the handoff doc now.The sandbox is flapping — it came back briefly (long enough to confirm your project tree survived intact at `/home/claude/takpilot-autel`), then dropped again mid-write. I don't want to keep you waiting on it, so here's the handoff document inline. Copy it into a file named `TAKPilot2-Autel-HANDOFF.md` alongside your source.

---

# TAKPilot2-Autel — Project Handoff / Working Context

**Purpose:** carry the intent, decisions, and hard-won knowledge from the porting session into a fresh session. This is the *internal* working doc. `TAKPilot2-Autel-Overview.md` is the polished functional explainer for outside developers and deliberately contains no internal references. This one has everything.

**Current version:** v1.2
**Status:** Builds. Installs. Runs on a Pixel 8 Pro test device. TAK server connection confirmed. Never yet run on the real Smart Controller V3 or with an aircraft attached.

## 1. What we're building and why

An Android app that runs **on the Autel Smart Controller V3** and acts simultaneously as the drone's flight interface (live video, telemetry HUD, map) and as a **TAK gateway** — publishing the aircraft's position to a TAK Server as CoT over TLS so the drone appears as a live track on the shared operating picture for every other TAK client, with its video linked from the marker.

**How we got here:** the original plan was a lightweight background "gateway only" app running *beside* Autel Explorer, with Explorer remaining the pilot's interface. That plan had one unproven link: whether the Autel MSDK can receive telemetry while Explorer holds the aircraft connection. Forum evidence suggested vendor SDKs are often one-app-at-a-time, and no reference implementation anywhere had demonstrated "vendor flight app + telemetry app concurrently on the same Android box."

**Decision made this session: we ported the existing DJI app instead.** We already had a working application — **TAKPilot2**, built for DJI (MSDK v5, M30-series) — doing exactly this job. Porting was chosen as the path of least resistance to a working APK.

**Consequence:** this app *replaces* Autel Explorer during TAK operations, exactly as TAKPilot2 replaces the DJI flight app. This dissolves the Explorer-concurrency question entirely. The tradeoff: everything Explorer provided beyond raw stick input — camera settings UI, mission planning, firmware updates, warnings UX — is not in this app. Keep Explorer installed for pre-flight/config; fly TAK missions from TAKPilot2. Sticks and RTH work regardless, since stick input rides the Skylink RF link directly, not through the app.

## 2. Project layout and provenance

Built on the Autel `AndroidAdvanceSample` repo, with TAKPilot2's code grafted in.

```
com.taklite.client.tak/     ← vendor-neutral TAK layer (Java) — COPIED VERBATIM
    TakManager, TakClient, CotBuilder, CotParser,
    TakCertEnroller, TakGroupAssigner, TakMissionClient, TakUser

com.autel.sdksample.tak/    ← drone-facing + UI layer (Kotlin)
    AutelProductHolder.kt     connected-aircraft singleton              [NEW]
    AutelTakBridge.kt         telemetry → CoT (+TakBridgeHolder)        [REWRITTEN]
    AutelVideoStreamer.kt     raw frames → RTSP (+VideoStreamerHolder)  [REWRITTEN]
    CameraSlantPoint.kt       look-point geometry                       [verbatim]
    TakMapMarkers.kt          inbound entities on map                   [re-impl. osmdroid]
    TakDropMarkers.kt         user-placed markers                       [re-impl. osmdroid]
    TakMissionManager.kt      Data Sync orchestration                   [package swap]
    TakAutoConnect.kt         silent reconnect at startup               [package swap]
    TakForegroundService.kt   process survival                          [package swap]
    TakPilotHomeActivity.kt   landing screen                            [ported]
    FlightActivity.kt         flight screen                             [REBUILT]
    TakConnectActivity.kt     configuration screen                      [package swap]
    DataSyncActivity.kt       feed management                           [package swap]
```

**The load-bearing architectural fact:** `com.taklite.client.tak` has zero drone-vendor imports — only JDK, Android framework, JSON/XML. That separation is what made this port tractable in a single session, and what would make a third vendor tractable. **Do not introduce SDK dependencies into that package.**

**What did NOT port:** `RcButtonManager` (Smart Controller V3 key codes unknown — log `onKeyDown` on hardware); `SpeakerMegaphoneManager`/`PayloadAccessoryManager` (DJI M30 payloads, no EVO II equivalent); `ArOverlayView` (was experimental in TAKPilot2 itself — deferred, but its data sources `TakDropMarkers.pinsForAr()`, `TakMapMarkers.isHidden()`, and the bridge telemetry cache are all ported and ready); PIP dual-lens control, HSI strip, on-screen camera controls (DJI widget-specific; EO/IR selection exists as `AutelTakBridge.activeLens` but isn't wired to a camera listener).

## 3. Key technical decisions and findings

**3.1 The SDK is listener-only** — DJI offers synchronous `KeyManager.getValue()` polling; Autel MSDK v1.5 does not. `AutelTakBridge` registers three listeners once, caches latest values in `@Volatile` fields, and a 2-second timer reads the cache and emits one CoT event.

| Listener | Provides |
|---|---|
| `Evo2FlyController.setFlyControllerInfoListener` | GPS (lat/lon/alt/sats/accuracy), local coords (alt above takeoff, ground speed, home point), attitude |
| `EvoBattery.setBatteryStateListener` | Remaining %, voltage, capacity |
| `EvoGimbal.setAngleListener` | Gimbal pitch/roll/yaw |

Listener registrations **do not survive an aircraft reconnect** — `AutelProductHolder` re-arms them on every `productConnected`.

**3.2 The altitude trap (most important correctness detail):**

| Source | Meaning | Use |
|---|---|---|
| `EvoGpsInfo.getAltitude()` | Height above WGS-84 **ellipsoid** (HAE) | ✅ What CoT `hae` requires |
| `EvoGpsInfo.getHeightMeanSeaLevel()` | Height above **MSL** | Terrain math only; cached for future DTED work |
| `LocalCoordinateInfo.getAltitude()` | Height above **takeoff point** | ❌ Never send as `hae`. HUD display only |

Sending takeoff-relative altitude as HAE is a well-known drone-to-CoT bug that makes tracks appear at wrong altitudes on every other client. Enforced in the bridge and commented at the call site. Still wants an empirical spot-check against a known surveyed HAE.

**3.3 Video: Autel is better than DJI here.** DJI only exposes a decoded surface, forcing a re-encode. Autel's `AutelCodecListener.onFrameStream()` hands over already-encoded H.264/H.265 Annex-B frames, so `AutelVideoStreamer` injects them straight into the RTSP client with **zero transcode** — near-zero CPU, no quality loss, and the media server can relay rather than transcode. Tradeoff: can't change resolution/bitrate since we never encode. RTSP needs SPS/PPS (plus VPS for H.265) before streaming, so the streamer sniffs those NAL units from the byte stream; drone downlinks repeat parameter sets every keyframe, so it resolves within ~1 GOP. H.264 vs H.265 auto-detected and logged.

**3.4 GPS comes from the aircraft, never the controller.** `AutelTakBridge` reads position exclusively from `EvoGpsInfo`. Correct, since the CoT track answers "where is the aircraft," and a controller position would show the pilot on the ground. *Future note:* an operator ground PLI would be a new telemetry source that doesn't exist in TAKPilot2 today.

**3.5 Network activity is not owned by any screen.** TAK connection, telemetry bridge, and video stream live in process-wide singletons (`TakManager`, `TakBridgeHolder`, `VideoStreamerHolder`). Leaving the flight screen or backgrounding doesn't interrupt them. `TakForegroundService` (typed `dataSync` FGS, `START_STICKY`, persistent notification) keeps the process alive — Doze silently throttling a telemetry sender mid-flight is a real, hard-to-diagnose failure.

**3.6 The global product-listener slot is contested.** `Autel.setProductConnectListener` is a single global slot, and the stock sample's `ProductActivity` (reachable from our "SDK Test Tools" button) overwrites it. `AutelProductHolder.install()` is called from `onResume` on both Home and Flight to reclaim it.

## 4. Build environment knowledge (learned the hard way)

- **Use JDK 11.** AGP 7.2.2 fails or misbehaves on JDK 17/21. Set `JAVA_HOME` explicitly if multiple JDKs are installed.
- **`chmod +x gradlew` after every unzip.** The execute bit gets stripped by zip extraction and Synology Drive sync. This bit us twice.
- Build: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`. Install: `adb install -r <apk>` (`-r` preserves settings).
- Toolchain: Gradle 7.3.3 / AGP 7.2.2 / Kotlin 1.7.20, `compileSdk` 33, `minSdk` 21.
- Autel SDK is a **bundled AAR in-repo** (`app/libs/autel-sdk-release.aar`), not a remote artifact.
- App ID `com.tak.uastoollite`; App Key already wired into `TestApplication.java`.
- Java package (`com.autel.sdksample`) and applicationId (`com.tak.uastoollite`) intentionally differ — valid Android, just confusing if you forget.

**Dependencies added:** `org.osmdroid:osmdroid-android:6.1.14` (map rendering, supports offline tile caches); `com.github.pedroSG94.rtmp-rtsp-stream-client-java:rtsp:2.2.6` (RTSP publishing — only its low-level `RtspClient` is used since we supply pre-encoded frames). No third-party TLS library; the CoT client uses JDK `SSLSocket` with `KeyStore`/`KeyManagerFactory`/`TrustManagerFactory`.

**How the port was verified without running it:** every new Kotlin file was type-checked against the real `autel-sdk-release.aar`, real osmdroid 6.1.14, real pedro rtsp 2.2.6, and Android API 30 `android.jar` — 0 errors (androidx and `R` stubbed). This caught three real bugs pre-build: a duplicated singleton, a Kotlin scoping shadow, and the contested product-listener slot. Not possible: running aapt / full Gradle assembly — which is exactly why the bugs that surfaced on device were all resource/layout issues, not code issues.

**Autel SDK API signatures were recovered by decompiling the AAR with `javap`**, since the public docs site documents methods but not field-level definitions for telemetry data classes. Reuse this for any future SDK gaps.

## 5. Bugs found and fixed on device (v1.0 → v1.1)` to just `## 5. Bugs found and fixed on device`, and replace that section's table with a version-tagged one:

| Version | Bug | Symptom | Cause | Fix |
|---|---|---|---|---|
| v1.1 | Missing buttons | TAK Setup and Data Sync absent from Home | Left column was `match_parent` with no ScrollView; overflow silently clipped. No error, no crash | Wrapped left column in `ScrollView` with `fillViewport` |
| v1.2 | Dead touch target | "Enter Flight" card only responded at the very bottom edge | Click listener only on the 56dp button, not the surrounding card that *looks* like one big tile | Made whole card clickable + focusable with `selectableItemBackground` ripple |

**Watch for more of the same in `FlightActivity`** — built from primitives, never rendered, and its right-side control column (Home / Video / Drop Pin / Pin @ Cam / Map ±) has the same risk profile. Screenshot-driven review works well.

## 6. Current test status

| Item | Status |
|---|---|
| Builds and installs | ✅ |
| Home screen renders, buttons reachable | ✅ (after v1.1 fixes) |
| "Enter Flight" full-card tap target | ✅ (v1.2 fix) |
| Flight screen opens | ✅ basic screen confirmed |
| **TAK connect + cert enrollment** | ✅ **Confirmed against test OpenTAKServer, connected both ends.** Hardest part of the stack, same code path as the working DJI app |
| CoT actually flowing / marker on server map | ❓ Not verified — "connected" proves the socket, not that telemetry is read and sent correctly |
| Aircraft telemetry | ❌ Untested — no aircraft |
| Video path | ❌ Untested |
| Real Smart Controller V3 | ❌ Not yet — all testing on a Pixel 8 Pro |

**Known non-issue — 16KB page size warning.** On the Pixel 8 Pro, Android shows "App Compatibility / not 16 KB compatible, ELF alignment check failed" listing `libAutelUtil.so`, `libAutelPlayer.so`, `libNetWorkProxy.so`, `libmappingplaning-lib.so`, `libwhitename.so`. These are **Autel's own precompiled native libraries** in the bundled AAR, built with an older toolchain. Nothing in our code touches this; unfixable without Autel's source. It's an OS notice on a debuggable build, not a crash. The Smart Controller V3 runs Android 11, which doesn't have 16KB pages, so this is likely a Pixel-as-test-device artifact. If aircraft connection or video misbehaves on real hardware, revisit — `libAutelPlayer.so` and `libAutelUtil.so` are almost certainly in the video and connection paths.

## 7. Calibration items — resolve on first real flight

All isolated as named constants in `AutelTakBridge`, instrumented so one flight resolves them.

| Item | Constant / location | Note |
|---|---|---|
| Gimbal pitch sign | `PITCH_SIGN` | Assumed DJI-like (down = negative). Flip to `-1.0` if SPI lands wrong side |
| Gimbal yaw reference frame | `BEARING_MODE_RELATIVE`, `BEARING_OFFSET_DEG` | Unknown whether yaw is absolute compass bearing or body-relative. **DJI needed +105°; Autel starts at 0.** Both candidate bearings logged every SPI push so one flight settles it |
| 640T FOV constants | `EO_HFOV/VFOV`, `IR_HFOV/VFOV` | Spec-sheet starting values; tune against rendered cone in ATAK |
| GPS accuracy units | `ACC_DIVISOR` | Believed millimeters (standard GNSS convention), sanity-clamped. Bench-verify |
| HAE altitude | `EvoGpsInfo.getAltitude()` | High-confidence structural inference; spot-check vs known surveyed HAE |
| Video listener vs `AutelCodecView` concurrency | `AutelVideoStreamer` / `FlightActivity` | **Undocumented and untested.** If starting the stream blanks local video, mitigation is decoding our own frame tap into a `SurfaceView` via MediaCodec — we already have the frames, so it's small and contained |
| `codec.cancel()` on stream stop | `AutelVideoStreamer.stop()` | If it also kills the display view's feed, remove the cancel and rely on the `stopped` guard |
| CoT type code | `CotBuilder.buildDronePLI` | Should emit `a-f-A-M-H-Q` (rotary-wing UAS). Verify against a live client during bench testing |

## 8. Known gaps / deferred work

1. **Certificate storage** — enrolled certs land as `.p12` in app-private `filesDir` with a conventional passphrase, not hardware-backed Android Keystore. Acceptable on a non-rooted controller; contained TODO in `TakCertEnroller`.
2. **No automatic certificate renewal** — reconnects with stored certs but doesn't re-enroll before expiry. Add before fleet rollout if the server issues short-lived certs. An expired cert fails as an opaque TLS handshake error, which is miserable to diagnose in the field.
3. **Terrain-aware look-point (DTED)** — currently flat-ground trigonometry. Intended upgrade is a ray-march: step outward along the camera vector, look up terrain elevation beneath each step, find where the ray drops below terrain, refine by bisection. `AutelTakBridge` already caches `heightMeanSeaLevel` (MSL) and the comment marks the splice point. DTED is MSL-referenced, so pairing with cached MSL needs no geoid conversion.
4. **Wi-Fi handoff** — controllers move between hotspots. Client reconnects on socket failure but hasn't been observed switching hotspots mid-flight. A connectivity-change callback for immediate clean reconnect (rather than waiting for socket timeout) is small and worthwhile.
5. **Thermal/EO switching** not wired to a camera listener, so FOV constants assume visible-light.
6. **RC physical button mapping** — needs keycode logging on hardware.
7. **AR overlay** — deferred; data sources ready.

## 9. NEXT UP: optional debug logging (designed, not yet built)

Active task when the session ended. Requirements settled:

- **Scope:** capture **only our TAK/Autel bridge code**, not all of logcat. Low noise.
- **Crash capture:** only while the toggle is ON (not always-on).
- **Retention:** delete logs older than **2 hours**, swept on app start and on entering the Debug screen. Explicitly to avoid filling the controller.
- **Entry point:** a **Debug button at the bottom of the left-side button stack on Home** (below Data Sync).
- **Debug screen:** options down the left side — on/off, export current log (share sheet, e.g. email), clear current log, delete current log. Log view fills the rest.

**Proposed implementation:**
1. **`AppLog` facade** replacing scattered `Log.i/w/e` calls across `AutelTakBridge`, `AutelVideoStreamer`, `AutelProductHolder`, `TakForegroundService`, `TakMapMarkers`, `TakDropMarkers`, `TakMissionManager`, and the taklite Java layer. Always forwards to normal `Log.*` so `adb logcat` keeps working identically; *additionally* writes to file when enabled.
2. **File sink** at `filesDir/logs/`, timestamped lines, size-capped with rotation.
3. **Crash handler** via `Thread.setDefaultUncaughtExceptionHandler`, installed only while logging is on; writes full stack trace before the process dies. Arguably the highest-value part for field debugging.
4. **Debug screen** with live-scrolling monospace view + the four actions.

**Open design question to confirm:** "Clear" vs "Delete" are nearly identical unless differentiated. Proposal — **Clear** wipes the current active log's contents but keeps logging running; **Delete** removes all log files on disk including rotated ones.

**Why before hardware testing:** `adb logcat` works today only because the build is debuggable and tethered to a laptop. In the field on a Smart Controller V3, that's exactly when it stops being practical — and when you most need to know what happened.

## 10. Testing that doesn't need an aircraft

The TAK half has no SDK dependency: certificate enrollment, TLS connection, reconnection ✅ *(already proven)*; inbound CoT rendering; channel enumeration/selection; Data Sync feed creation, joining, marker publication; marker placement and transmission.

**Genuinely needs hardware:** telemetry field validation, video path end-to-end, all sensor-point calibration.

**Useful supplementary technique:** a small script that opens a TLS socket to 8089 with an exported certificate and replays synthetic CoT along a fake flight path. Validates the server-side contract — cert chain, channel routing, symbol rendering, stale-fade on every client type — independently of the Android app. Then the app only has to match something already proven.

## 11. Server contract quick reference

| Port | Protocol | Purpose |
|---|---|---|
| 8089 | TLS (mutual) | CoT streaming. One long-lived socket, CoT XML written as it occurs, no HTTP/framing. Same socket delivers inbound. Fire-and-forget, no replay |
| 8446 | HTTPS | Certificate enrollment. Authenticate with user/pass once, generate keypair, submit CSR, receive signed cert + CA truststore |
| 8443 | HTTPS REST | Data Sync / Mission API. Named persistent shareable feeds; durable unlike the ephemeral stream |

- **Identity:** server derives the user from the client cert's Common Name; group membership decides channel routing, entirely server-side. The app can *narrow* routing by tagging events with explicit destination channels.
- **Track identity ≠ connection identity:** the map distinguishes entities by CoT `uid`, not certificate. Several devices sharing one cert still produce distinct markers as long as `uid`s differ.
- **`uid` must be stable across sessions** — derived from the operator-entered callsign, so the same airframe is the same track forever. Never randomize per session.
- **`stale` is short (10–15s)** — a fast-moving track whose link drops should fade rather than leave a ghost.
- **Load:** at 0.5 Hz per aircraft, a six-airframe fleet is ~3 events/sec. Negligible for the server, modest on shared Wi-Fi.

## 12. Deployment notes

- **Wi-Fi only** — Smart Controller V3 has no SIM slot or cellular modem. Joins whatever hotspot is available on site, so the app must survive network changes mid-flight.
- Fleet is **Autel EVO II Dual 640T V3** + **Smart Controller V3** (not SE), Android 11, controller firmware 1.3.9.23+. Matches MSDK v1.5 scope exactly.
- **No laser rangefinder** on the EVO II V3 — the sensor look-point must be computed geometrically; no measured distance available.
- Per-device configuration reduces to **callsign only**; everything else entered at runtime and persisted. Nothing compiled in.
- Provisioning: sideload APK → enter host/user/pass/callsign once → done.

## 13. Files in play

| File | What it is |
|---|---|
| `TAKPilot2-Autel-Overview.md` | Polished functional/architectural doc for **outside developers**. Self-contained, no internal references, no credentials. Safe to circulate |
| `PORT-STATUS.md` | In-repo port status: component map, first-build checklist, calibration table, divergences |
| `TAKPilot2-Autel-HANDOFF.md` | This document — internal working context |
| `TAKPilot2-source-V1.zip` | The **original DJI TAKPilot2 source**, the port's reference. Keep it — it's the answer key for anything ambiguous about intended behavior |

## 14. Suggested first moves in the new session

1. Upload the current `takpilot-autel_v1-2` source (or latest zip) plus this document.
2. Build the debug logging feature (§9) — fully specified, was the active task.
3. Verify CoT is actually reaching the server (marker visible for the callsign), closing the one open question from the successful connection test.
4. Then move to the real Smart Controller V3, where the §7 calibration items become resolvable.

**Recommendation:** carry over `TAKPilot2-source-V1.zip` too. Several times during the port, the fastest way to resolve "what was this supposed to do?" was reading the DJI original rather than reasoning about it.
