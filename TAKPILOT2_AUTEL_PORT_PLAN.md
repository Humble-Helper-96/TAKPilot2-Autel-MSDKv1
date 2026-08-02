# TAKPilot2-Autel — Bring-Forward Port Plan

*Written 2026-07-29. This is a living plan doc, same convention as the DJI side's
`docs/TAKPILOT2_V4_PORT_PLAN.md` — snapshot, not live state. Cross-check against the
actual code and against `git log` (once this tree is under git — see Environment) if
anything here looks stale.*

## START HERE if resuming in a new chat

**This is not a from-scratch port.** A working v1.2 Autel app already exists in this
directory: builds, installs, runs on a Pixel 8 Pro, TAK cert enrollment + connect
confirmed against a test server. See `TAKPilot2-Autel-Overview.md` (polished external
explainer), `PORT-STATUS.md` (component map / calibration table), and
`TAKPilot2-Autel-HANDOFF.md` (internal working notes from that session) for the full
detail — this doc does not repeat them, it plans what comes next.

**The catch:** v1.2 was ported from an **older, different DJI source** — DJI Mobile
SDK **v5**, M30-series aircraft, `DefaultLayoutActivity` (DJI's proprietary uxsdk widget
framework) for the flight screen. That predates essentially everything the *current*
DJI app — TAKPilot2 Go V4, MSDK **v4**, Mini 2, at
`DJI/Mobile-SDK-Android-4.18/SampleCode-device-compat/` — has shipped since: custom
toolbar/HUD, locked mini-map with home-line, Field Guide, Debug Log screen, Phase 5 RTSP
push, Phase 6 markers/AR, FAA UASFM ceilings, DTED terrain-corrected SPoI, loss-of-signal
RTH failsafe, the recent TAK client-identity + callsign-reporting fixes.

**The goal, per the operator's explicit requirement:** a pilot should be able to move
from the Mini 2 to the EVO II 640T V3 and fly TAKPilot2 without relearning the UI. So the
current MSDKv4 app's screens are the template — copy its layouts/behavior and swap only
the drone-data-source calls — rather than treating this as an independent redesign.
Thermal/dual-lens UI is explicitly deferred; keep the Autel flight screen single-lens,
looking identical to the Mini 2's, for this pass.

> ### STANDING RULE (operator, 2026-07-30) — read before omitting anything
>
> **The TAKPilot2 DJI MSDKv4 app is the blueprint, and UI parity is the priority.** Build
> every feature the blueprint has. Where something can't be wired up yet on the Autel side,
> **ship it as a placeholder icon in the right place that shows a Toast saying it isn't
> implemented yet** — do NOT leave it out.
>
> This overrules the instinct (applied in this doc's earlier increments, and since corrected)
> that a non-functional control is worse than an absent one. A pilot moving between airframes
> must find the same layout; a placeholder that explains itself is better than a hole. The
> one carve-out is *indicators* that could be mistaken for live data — those must render in
> an unmistakable no-data state and say so when tapped, rather than showing a plausible
> number (see the signal-bars and REC handling on the Flight screen).

**Status (2026-07-30): Phases 0–2 complete, committed as v1.3.** Phase 0/1/2 work is
committed as `380ba0e` (the port) + `4ad40fc` (the version bump, tagged `autel-v1.3`), on
top of the v1.2 baseline (`c175d40`, tag `autel-baseline-v1.2`). Every screen now matches the DJI blueprint: Home, Flight (full
toolbar + locked mini-map + right-hand HUD column + marker suite), Pre-Flight Setup (all
six sections), Field Guide, Debug Log, Data Sync. `./gradlew assembleDebug` clean
throughout. The not-yet-functional controls ship as self-describing placeholders per the
standing rule above. **Nothing has been run against an aircraft — see the QC note under
Phase 4.**

**Versioning.** The app's own version is now `versionCode 2` / `versionName "1.3"`,
replacing the stock Autel sample's `V1.0.1.40` this project forked from — "v1.2" only ever
existed as a directory name and a git tag, never in the app itself. Note `versionName` is
not cosmetic: `TestApplication` reads it via `PackageManager` and reports it as
`<takv version>` on every PLI, so it is what a TAK server's Connected Users panel shows for
this client. Bump it when what goes on the wire changes. Debug/release APKs are now named
`TAKPilot2-Autel_<version>` instead of the stock `NewSDK_<version>`.

*(The project directory is still `takpilot-autel_v1-2/` — deliberately not renamed, since
that path is referenced outside this repo. The folder name is not the version; build.gradle
and the git tags are.)*

*(Earlier status line, for history: Phases 0–1 complete as of 2026-07-29.)*
`com.taklite.client.tak` diffed against current DJI and reconciled — see "Phase 0 status"
below. DTED terrain-corrected SPoI, FAA UASFM ceilings, and RTH/flight-limit defaults are
ported and wired; ADS-B needed no new code; the marker suite, gimbal-pitch HUD cue, and
units formatting turned out to be Phase 2 UI work, not headless — reclassified there with
their findings recorded. Next up: Phase 2 (UI parity rebuild), starting with Home or Flight
screen.

**Hardware constraint that applies to every phase from here on: the Smart Controller V3
runs Android 11 (API 30).** Design and code with that OS's behavior in mind, not against
it — don't assume the DJI app's Android version (check its own plan doc) or a dev Pixel's
version. Concretely this means: scoped storage applies (no raw `Environment.
getExternalStorageDirectory()` writes without `requestLegacyExternalStorage` — and that
flag's opt-out is not guaranteed to still work by the time this ships; prefer
app-scoped/`MediaStore` paths), one-time permission grants can be revoked by the OS if the
app goes unused, background-service/foreground-service start restrictions match API 30
rules (not more, not less), and BLE/Wi-Fi APIs should be checked against API 30's
behavior, not a newer OS's. `app/build.gradle` currently has `compileSdkVersion 33` /
`targetSdkVersion 29` / `minSdkVersion 21` — targeting 29 on an API 30 device is fine
today, but don't bump `targetSdkVersion` past 30 without re-auditing storage/permission
behavior against the actual hardware, since Google's scoped-storage enforcement tightens
per target-SDK tier and the Smart Controller V3 can't be assumed to match a newer device's
leniency.

---

## Reusable as-is (verified in the v1.2 port, don't re-litigate)

| Piece | Why it transfers |
|---|---|
| `com.taklite.client.tak` (TLS CoT client, CotBuilder/Parser, cert enrollment, channels, Data Sync/Mission API) | Vendor-neutral — zero drone imports — copied verbatim in v1.2, and it's the *same* package unchanged in the current DJI app. Should need at most a diff-and-reconcile, not a rewrite. |
| `CameraSlantPoint` | Pure trigonometry, ported verbatim already. |
| `AutelTakBridge`'s cache-and-emit pattern | Autel MSDK v1.5 is listener-only (no synchronous polling like DJI's `KeyManager`). v1.2 already solved this: register listeners once, cache into `@Volatile` fields, timer reads cache and emits CoT. Extend this pattern, don't rethink it. |
| Autel's raw-frame video passthrough | Genuinely better than DJI's current approach — Autel's SDK hands over already-encoded H.264/H.265 frames, so RTSP push needs zero transcode. DJI's MSDKv4 Phase 5 had to resort to on-device screen-capture transcoding because DJI only exposes a decoded surface. Keep this advantage; don't rebuild it as a screen-capture pipeline to "match" DJI — the current approach is objectively better and the wire output (RTSP stream) looks identical to any TAK client either way. |

---

## Phase 0 — Audit & reconcile

Before extending anything, confirm the parts assumed "verbatim" haven't drifted.

- [x] Diff `com.taklite.client.tak` in the Autel tree against the current DJI
      (`SampleCode-device-compat`) copy. Pull forward any fix that applies to shared,
      vendor-neutral code. **Done 2026-07-29 — see "Phase 0 status" below.**
- [x] Confirm `CotBuilder.buildPLI`'s emitted type code (`a-f-A-M-H-Q`) and `<contact
      callsign>` wiring match current DJI behavior. **Done — always used real callsign,
      not username; no bug here. The actual drift found was client identity (`<takv>`)
      and marker/foreground-service lifecycle, not callsign wiring — see below.**
- [x] Put this project tree under git. **Done — commit `c175d40`, tag
      `autel-baseline-v1.2`.**

### Phase 0 status (2026-07-29)

Diffed `com.taklite.client.tak` against the DJI tree at its `4ecb840` ("TAK identity:
real client info, and disconnect when the app is actually closed") commit — the most
recent commit touching that package on the DJI side. (Note: there is no DJI commit
`ed07d61`; that reference in an earlier version of this doc was stale. `4ecb840` is the
real fix that needed pulling forward.) `TakMissionClient.java`, `TakClient.java`,
`CotParser.java`, `TakCertEnroller.java`, `TakUser.java`, `TakGroupAssigner.java` were
already byte-identical — no drift. Two files had drifted and have now been ported forward
into the Autel tree, plus two Autel-side call sites updated to use the new API:

- **`CotBuilder.java`** — `buildPLI()` now takes real `takv` identity params
  (`platform`/`device`/`os`/`version`) instead of the old hardcoded
  `device="TAKLite" platform="TAK Lite" version="1.0"` (which made every Autel PLI show
  up on a TAK server's Connected Users panel as an unbranded generic client). Drone-track
  stale duration bumped 15s→2min (a momentary GPS-lock hiccup no longer makes the
  aircraft vanish from TAK). New 14h `MARKER_STALE_DURATION_MS` replacing the old
  hardcoded 10-minute marker stale.
- **`TakManager.java`** — added `setClientIdentity()`, `deviceWithCallsign()` (folds the
  live callsign into the `<takv device>` string so two otherwise-identical devices are
  distinguishable in the connected-users list), `newMarkerUid()` / `sendMarkerWithUid()`
  (lets a caller move/update an existing marker in place instead of always spawning a new
  one — CoT identity is the uid, so re-sending the same uid moves it on every TAK client).
  File is now byte-identical to the DJI copy (comments aside, which correctly name each
  side as "this app" vs. "the sibling").
- **`TestApplication.java`** (Autel's `Application.onCreate`, the sibling of DJI's
  `DJISampleApplication.onCreate`) — now calls
  `TakManager.getInstance().setClientIdentity("TAKPilot2-Autel", Build.MODEL, "Android "
  + Build.VERSION.RELEASE, appVersionName(this))` at startup, so the Autel port reports
  its own real identity rather than either the shared core's generic placeholder or
  (worse) silently never setting it. `appVersionName()` reads the real versionName via
  `PackageManager` instead of a hand-maintained string.
- **`TakForegroundService.kt`** — added `onTaskRemoved()` override (mirrors the DJI fix):
  disconnects TAK and stops the service when the app task is swiped from recents. Before
  this, `START_STICKY` plus no disconnect path other than the Home screen's STOP/QUIT
  button meant closing the app didn't actually end the TAK session — the operator's
  presence stayed showing connected on the server indefinitely.

All four files verified building clean (`./gradlew compileDebugJavaWithJavac` and
`compileDebugKotlin`). Not yet committed — awaiting go-ahead per the standing "don't
commit without asking" constraint.

## Phase 1 — Telemetry & feature parity (headless, no UI yet)

Bring `AutelTakBridge` and supporting logic up to what the DJI app's telemetry/CoT side
now does, using Autel's data sources. All of this should be substantially vendor-neutral
logic already proven on the DJI side — the work is wiring, not invention.

### Phase 1 pre-audit (2026-07-29)

Compared the file lists of `com.dji.sdk.sample.tak` (28 files) against
`com.autel.sdksample.tak` (15 files) before starting any implementation, to confirm the
checklist below is complete and that nothing is silently half-done already. Result:
**every item below is a clean gap, not drift** — grepped the whole Autel source tree for
`Dted`/`Uasfm`/`AdsB`/`FailSafe` and got zero hits, so none of Phase 1 has been started,
including no partial/abandoned attempt. Specifically absent from the Autel tree and
present on the DJI side: `DtedIndex/DtedStore/DtedTile/TerrainAgl/TerrainDatabase`
(terrain-corrected SPoI), `UasfmDatabase/UasfmIndex/UasfmStore` (FAA ceilings),
`ArSettings` (AR overlay config), `FlightLimitsController`. The
`AutelTakBridge.activeLens` hook noted in Phase 3 as needing preservation does already
exist (`AutelTakBridge.kt:81`) and is live-used in the SPI/FOV calc — good, nothing to do
there yet, just confirmed it's real and not stale.

Files present on DJI but intentionally NOT ported (video transcode path —
`AnnexBNalAssembler/H264SliceParser/IdrRequesterHolder/ScreenCaptureEncoder/
ScreenCaptureService/StreamTranscoder/FpvDecoderHealth/DjiSdkBridge/DroneVideoStreamer`):
this is expected, not a gap — see the "Reusable as-is" table above. DJI needs an
on-device screen-capture transcode because DJI's SDK only exposes a decoded surface;
Autel's SDK hands over already-encoded frames directly (`AutelVideoStreamer.kt`), so that
whole subsystem doesn't apply here. Don't port it.

- [x] **DTED terrain-corrected SPoI.** **Done 2026-07-29.** DJI's `TerrainAgl` + the DTED
      ray-march upgrade to `CameraSlantPoint`, ported and wired into `AutelTakBridge`.
      See "DTED status" below.
### DTED status (2026-07-29)

Ported verbatim (package rename only): `DtedIndex.kt`, `DtedStore.kt`, `DtedTile.kt`,
`TerrainDatabase.kt` (Room: `ImportEntity`/`ImportTileEntity`/`TerrainDao`). `DtedStore`'s
import/delete API is headless-ready but has no caller yet — wiring an actual "Import
Terrain" button is Phase 2's Pre-Flight Setup screen, per the existing plan; nothing to do
there now.

Adapted (not verbatim, since Autel's telemetry shape differs from DJI's):
- **`CameraSlantPoint.kt`** — added `elevationAt`/`aircraftMslMeters` params to `compute()`,
  the terrain fixed-point iteration, `GroundPoint.elevationMeters`, `distanceMeters()`,
  `initialBearingDeg()`. Same math as the DJI version.
- **`TerrainAgl.kt`** — same takeoff-terrain-latch design as DJI's, retargeted to
  `AutelTakBridge.Hud` instead of `DroneTakBridge.Hud`.
- **`AutelTakBridge.kt`** — added `homeLat`/`homeLon`/`homeSet` (populated from
  `LocalCoordinateInfo.getHomeLatitude/Longitude/getHomeEnable()`, confirmed present in the
  bundled Autel SDK by inspecting `autel-sdk-release.aar`'s `classes.jar`). `start()` now
  calls `TerrainAgl.reset()` per new flight session. `pushCameraPoint()` and `lookPoint()`
  now pass `::elevationLookup` + `aircraftMsl(aglMeters)` into `CameraSlantPoint.compute()`
  so the SPoI ray-marches against real terrain wherever the pilot has imported DTED
  coverage, falling back to the original flat-ground estimate everywhere else. Added
  `aglReading()` for Phase 2 to wire into an actual HUD readout later.
- **`TestApplication.java`** — added a `getInstance()`/static `app` accessor (mirrors
  `DJISampleApplication.getInstance()`) since `TerrainAgl`/`DtedIndex` need a `Context` from
  a background telemetry tick with no `Activity` in hand.
- **`app/build.gradle`** — added `kotlin-kapt` plugin + `androidx.room:room-runtime`/
  `room-compiler:2.4.3`. DJI pins Room to 2.2.6 because its Kotlin 1.5.10 can't read newer
  Room's compiled metadata; this project is on Kotlin 1.7.20 so a current Room release is
  fine — confirmed by a full `assembleDebug` (kapt + javac + kotlinc all clean).

Not yet committed — awaiting go-ahead per the standing constraint.

- [x] **FAA UASFM advisory ceilings.** **Done 2026-07-29.** `UasfmDatabase.kt`/
      `UasfmIndex.kt`/`UasfmStore.kt` ported verbatim (package rename only — genuinely
      vendor-neutral, zero DJI-specific references). `UasfmIndex.preload()` wired into
      `TestApplication.onCreate` so the dataset warms off the main thread at startup,
      matching the DJI sibling. HUD readout wiring is Phase 2 (no HUD screen exists yet on
      this side to wire it into).
- [x] **ADS-B air-track ingestion — no action needed.** Audited: on the DJI side this isn't
      a distinct subsystem, it rides entirely on the already-ported `com.taklite.client.tak`
      layer (inbound CoT contacts over a read-only TAK channel), reconciled in Phase 0. The
      DJI side's own AR-overlay *display* of ADS-B tracks is itself deferred UI work (its own
      plan doc: "DEFERRED 2026-07-26, do after C"). Nothing to port at Phase 1; the display
      side lands with Phase 2/AR work if and when DJI builds it too.
- [~] **Marker management suite — reclassified to Phase 2, not Phase 1.** Audited 2026-07-29
      by diffing `TakMapMarkers.kt`/`TakDropMarkers.kt` between trees. Finding: DJI's map
      layer has since moved to **Mapbox** (`com.mapbox.mapboxsdk`, symbol/style-batch API —
      `onMapReady(Style)`, `stage()`/`rebuild()`), while Autel is on **osmdroid** (direct
      `Marker` mutation). This isn't a portable backend diff — it's two different map
      rendering frameworks, so "bring up to feature parity" is necessarily a UI-framework
      task against osmdroid's own API, i.e. Phase 2, not headless plumbing. Concrete feature
      gap for whoever picks up that Phase 2 item (DJI has, Autel's simpler version lacks):
      `inboundUser(uid)` lookup, public `hideInbound`, `nextAutoName()` (drone-callsign-
      prefixed pin numbering), `ownsUid()`, `quickPin()`/`placeQuick()`/`moveQuick()`,
      `rename()`/`changeType()`/`resend()` on a placed pin. Recorded here now so Phase 2
      doesn't have to re-derive this list from scratch.
- [x] **Loss-of-signal → RTH failsafe — partially done, rest confirmed unavailable.**
      **Done 2026-07-29** for the part that has an SDK hook. Ported `FlightLimitsController.kt`
      (max altitude / max radius / RTH altitude — `AutelFlyController.setMaxHeight/setMaxRange/
      setReturnHeight`, confirmed present via `javap` against the bundled
      `autel-sdk-release.aar`), wired into `AutelTakBridge`'s first-telemetry one-shot
      (mirrors the DJI sibling's `limitsApplied` pattern). **The signal-loss failsafe
      behavior itself has no Autel SDK equivalent** — audited the full public
      `AutelFlyController`/`Evo2FlyController` surface and the whole aar's `classes.jar`;
      no `setConnectionFailSafe`-shaped method or "RC lost" config type exists anywhere.
      There's an `EmergencyAction` enum (NONE/HOVER/LAND/GO_HOME, same shape as DJI's) and a
      `doEmergencyAction()` call, but that's a one-shot app-triggered command, not a
      persistent firmware policy that survives the app/phone dying — not the same safety
      guarantee, not wired as a substitute. Whatever the EVO II does on RC loss today is
      governed by the aircraft/RC's own firmware settings outside this app's control; call
      this out explicitly during Phase 5 field validation rather than assuming DJI-app
      parity. Revisit if hardware bring-up (Phase 4) or newer SDK docs turn up a real hook.
- [~] **Gimbal-pitch HUD readout + crosshair accuracy cue — reclassified to Phase 2.**
      Audited: on the DJI side this logic lives in `CrosshairView.kt`/`ArOverlayView.kt`/
      `TAKPilot2GoFlightActivity.kt` (the `takpilot2` UI-screen package), not the vendor-
      neutral `tak` package — it's HUD view code, not headless bridge logic. Nothing to
      port until the Flight screen itself is rebuilt (Phase 2).
- [~] **Units standardization (`Units.kt`) — reclassified to Phase 2.** Same finding: DJI's
      `Units.kt` lives in the `takpilot2` UI package alongside the HUD it formats numbers
      for, not in the vendor-neutral `tak` package. Bring it over together with the Flight
      screen rebuild rather than in isolation now.

**Phase 1 status: headless items complete as of 2026-07-29** (DTED, UASFM, RTH-limits, and
confirming ADS-B needs no new code). The three `[~]` items above were audited and found to
actually belong to Phase 2 (they're UI-framework or HUD-view code, not headless bridge
logic) — reclassify, don't force them into Phase 1. `./gradlew assembleDebug` clean after
every change in this phase. Not yet committed — awaiting go-ahead per the standing
constraint.

## Phase 2 — UI parity rebuild

The bigger lift. Old Autel `FlightActivity` was hand-built from primitives against the
*old* DJI UI and needs to be rebuilt against the *current* one — not incrementally
patched.

- [x] **Home screen — done 2026-07-30.** Audited `TakPilotHomeActivity`/
      `activity_takpilot2_home.xml` against current DJI's `TAKPilot2GoHomeActivity`/
      `activity_takpilot2go_home.xml`: structurally already near-identical (same
      `homeCard`/`homeCardTitle`/`homeCardValue` styles, same two-pane layout, same
      whole-card-clickable Enter Flight tile — this had already been ported well). Closed
      the real gaps:
      - **Added STOP/QUIT** (`homeQuit` button + `confirmQuit()`/`doQuit()`), ported
        verbatim from the DJI sibling — tears down `VideoStreamerHolder`, `TakBridgeHolder`,
        `TakManager`, `TakForegroundService`, then kills the process. This was a real
        missing safety/reliability feature, not cosmetic — the only way to clear stuck
        state without knowing which subsystem is wedged.
      - **Button wording aligned** with the current DJI screen: "TAK Setup" → "Pre-Flight
        Setup", "Debug" → "Debug Log" — same underlying screens, just matching labels so a
        pilot moving between airframes isn't relearning wording for identical actions.
      - **Field Guide entry deliberately NOT added yet** — its destination
        (`FieldGuideActivity`, ~700 lines on the DJI side) doesn't exist on this side yet
        and is its own Phase 2 checklist item below; a button pointing at nothing would be
        worse than no button.
      - Kept Autel-specific additions that aren't DJI parity gaps, just extra useful surface
        area on this airframe: the video-stream and camera-FOV/look-point toggles inline on
        Home, the battery readout on the Enter Flight card, and "SDK Test Tools" in the
        repurposed Button-Mapping slot (bench debugging — documented in the file's own
        header comment).
      - Verified with `./gradlew assembleDebug` (no device/emulator available in this
        environment to visually confirm on-screen — flagged, not silently assumed working).
- [ ] **Flight screen** — mirror `TAKPilot2GoFlightActivity`: same toolbar layout
      (hamburger | RTH | TAK shield+dot | battery ring | GPS | RC signal ‖ Video Re-Sync
      | LIVE | REC), same status strip, same telemetry strip (altitude/speed/heading/
      battery/satellites), same **locked mini-map** treatment (no pan/zoom/rotate,
      north-up, fixed zoom, red home→aircraft line) rather than the old freely
      interactive osmdroid map, and a single drop-pin-at-look-point button.
      *(Corrected 2026-07-30: this bullet previously also called for "same expand/collapse
      map behavior" and a separate pin-at-look-point button — both were wrong about what
      DJI actually does. See the corrections under the status notes below.)*
      Also folds in three items originally mis-scoped to Phase 1
      and reclassified here after audit (2026-07-29) — see Phase 1's `[~]` entries for the
      full findings:
      - Gimbal-pitch HUD readout + crosshair accuracy cue (green/amber/white by pitch,
        `1/sin²(pitch)` error scaling) — port from `CrosshairView.kt`.
      - Units standardization / imperial formatting — port from `Units.kt`.
      - Marker suite UI: DJI's map moved to **Mapbox**, Autel is on **osmdroid** — this is a
        framework rewrite against osmdroid's API, not a copy. Concrete feature gap to close:
        `inboundUser(uid)`, public `hideInbound`, `nextAutoName()` (drone-callsign-prefixed
        numbering), `ownsUid()`, `quickPin()`/`placeQuick()`/`moveQuick()`,
        `rename()`/`changeType()`/`resend()` on a placed pin. `AutelTakBridge.aglReading()`
        (added in Phase 1) is ready to wire into the AGL HUD readout here too.

      **Flight screen status (2026-07-30): instrument toolbar increment done, rest
      outstanding.** This is the largest item in Phase 2 (DJI's current version is ~2,400
      lines across the activity + a dozen custom chrome views; Autel's was a 283-line
      placeholder), so it's being worked in verified increments rather than one blind
      rewrite — no Android emulator/device is available in this environment, so every
      change here is confirmed by `./gradlew assembleDebug` and structural comparison
      against the DJI layout, NOT by seeing it on screen. Flag for on-device review before
      trusting the visuals.

      Done this increment: ported `BatteryGaugeView.kt`, `SignalBarsView.kt`,
      `LiveToggleView.kt`, `RecordToggleView.kt`, `Units.kt` (all vendor-neutral, verbatim
      package-rename ports) and the toolbar drawables (`bg_toolbar`, `ic_menu`,
      `ic_tak_logo`, `bg_status_dot`, `ic_rth`, `ic_drop_pin`, `bg_zoom_pill`,
      `ic_camera_shutter`, `ic_resync`, `ic_gps`). Added a real instrument toolbar bar to
      `activity_flight.xml`/`FlightActivity.kt`: back button, TAK connection icon+dot,
      battery gauge (live `AutelTakBridge` data), GPS satellite count, RTH button wired to
      `AutelFlyController.goHome()` with a confirmation dialog (confirmed `goHome()` exists
      via `javap` against the bundled aar). Removed the old plain-text "◄ Home" button,
      superseded by the toolbar's back button.

      Deliberately NOT wired: **signal-strength bars** — ported `SignalBarsView.kt` as a
      class but didn't add it to the toolbar. Audited `EvoDsp`/`SignalInfo` in the bundled
      SDK: it exposes raw RF metrics (RSRP, SNR, gain, MCS) with no 0-100% figure anywhere,
      unlike DJI's `AirLink.getUplinkSignalQuality()`. Deriving a percentage needs a
      calibrated RSRP/SNR→quality mapping that can only be tuned against real hardware —
      exactly a Phase 4 bring-up item, not something to fabricate now. Revisit there.

      > **WRONG — superseded, see "Step 8 revised, 2026-07-30" below.** The audit looked at
      > `EvoDsp`/`SignalInfo` only. `RemoteControllerInfo.getControllerSignalPercentage()`
      > is a ready-made 0-100% figure and the bars are now live off it.

      **Increment 2 — locked mini-map: done 2026-07-30.** New `LockedMapView.kt` (osmdroid
      subclass) reproduces DJI's `uiSettings.setAllGesturesEnabled(false)`: no pan, fling,
      double-tap zoom, pinch zoom or zoom buttons; north-up (orientation never touched);
      zoom pinned at `MAP_ZOOM = 15.0`, matching DJI so both airframes frame the same
      ground. Recentres on the aircraft each tick — the only camera movement. Added the
      home marker (`ic_home_marker`), the red home→aircraft `Polyline` ("which way back" on
      a map you can't pan), the `ic_rth_home_set` RTH icon swap, and a "Home Point Set"
      notice on first latch. Map moved to bottom-right to match DJI's HUD-column placement;
      the button column moved to the left to avoid overlapping it.

      Two implementation notes worth keeping (both cost a failed attempt):
      - osmdroid's `MapView` is **not** its own gesture listener (it holds a *private*
        `mGestureDetector`), so overriding `onScroll`/`onFling`/`onDoubleTap` does not
        compile. `LockedMapView` instead consumes every touch without calling
        `super.onTouchEvent` and runs its own detector to forward confirmed single taps to
        the overlay manager — which is what keeps tap-an-inbound-contact-to-hide working.
      - The obvious `setOnTouchListener { _, _ -> true }` shortcut locks the map but also
        kills marker taps, because `dispatchTouchEvent` consults the listener before
        `onTouchEvent` and osmdroid's overlays never see the gesture.

      **Two corrections to this plan doc's own Flight-screen description, found by
      checking the DJI source rather than trusting the text above:**
      1. "same expand/collapse map behavior" — **DJI has no such behavior.** Its map is a
         fixed `@dimen/flight_map_size` square in the HUD column with no expand control
         anywhere in `activity_takpilot2go_flight.xml`. Autel's expand/collapse button was
         a v1.2-only feature and has been removed; it is fundamentally incompatible with a
         locked map anyway (expanding existed to browse, which is what locking forbids).
      2. "same drop-pin / pin-at-look-point buttons" — **DJI has one drop-pin button, not
         two**, and it places at the look-point. Autel's separate "Drop Pin" (tap the map)
         and "Pin @ Cam" have been collapsed into a single look-point drop. This is a
         deliberate **feature removal** (tap-to-place is gone) and a direct consequence of
         locking the map: tap-to-place needs a pannable map to aim with. The camera
         crosshair is the better cursor regardless — it's DTED terrain-corrected. Flagged
         explicitly here because it's user-visible; revert the map lock if the operator
         wants tap-to-place back, since the two can't coexist.
      `TakDropMarkers.beginDrop()`/`onMapTap()` are now dormant rather than deleted (the
      machinery is correct and the outstanding AR/marker work may want a placement mode
      again) — documented as such in that file so nobody assumes it's live.

      **Increment 3 — HUD rebuilt to the blueprint's shape: done 2026-07-30.** This was the
      biggest visual divergence left: v1.2 had two full-width dark strips (top status,
      bottom telemetry) where DJI has a right-hand instrument column over the video. Now
      matched. `activity_flight.xml` gained DJI's `flightHudColumn` — weighted spacer,
      `fpvOverlayText`, `fpvGimbalPitch`, `fpvFaaCeiling`, then the mini-map, all in one
      container so they can't grow into each other at any screen size (the exact failure
      DJI hit when these were separately anchored). Both old strips deleted. Added the
      `hudText` style and DJI's transient `fpvNotice` (upper-left, auto-hiding — now carries
      "Home Point Set" instead of a toast).
      - **`CrosshairView.kt` ported** with the marker-accuracy ring, and wired to
        `updateGimbalPitch()`. Both the ring and the `GIMBAL n° DOWN` readout take their
        colour from the shared `CrosshairView.accuracyColorFor()`, so they cannot disagree.
      - **FAA ceiling readout wired** (`updateFaaCeiling()`), completing Phase 1's UASFM
        port — cell lookup cached per grid cell rather than per tick, judged against the
        terrain-corrected AGL, with the same `~`/Class-G/no-data states as DJI.
      - **`Units.kt` now actually used**: the readout is imperial throughout (ft / MPH /
        `AGL`-vs-`ALT` label / MSL), matching DJI line for line.
      - **Toolbar completed**: drop-pin moved into it as DJI's oversized `ic_drop_pin`
        button, and `LiveToggleView` added as the LIVE badge wired to `VideoStreamerHolder`
        (a real existing Autel subsystem, so this one is genuinely functional, not a shell).
        The left-hand button column is gone entirely — every action now lives in the
        toolbar, as on the blueprint.

      Two deliberate deviations, both forced by the Autel SDK rather than chosen:
      - **`CrosshairView` centres on the view, not a video content rect.** DJI's
        `FpvTextureView` reports the real pillarboxed video rectangle; `AutelCodecView` is an
        opaque SDK widget with no equivalent callback, so the reticle defaults to view
        bounds. Correct as long as that widget fills its container without internal
        letterboxing — **verify on real hardware in Phase 4**; if it letterboxes, the reticle
        will sit off the true image centre and `setVideoRect()` needs feeding. Documented in
        the class.
      - **The fifth readout line shows AC/TAK/SPI link state, not DJI's flight timer.**
        `EvoFlyControllerInfo` exposes no flight-time field (checked against the bundled
        aar), and neither does anything else on the Autel telemetry surface. Rather than
        fabricate a timer or leave the line blank, it carries the connection state the old
        top strip used to show. Revisit if Phase 4 turns up a real elapsed-time source.

      **Increment 4 — full toolbar parity: done 2026-07-30. This SUPERSEDES the judgment
      call in increments 1–3.** Those left signal bars, AR, photo, zoom, video re-sync, REC,
      the exposure slider and the crosshair quick-marker OUT, on the reasoning that a
      control which does nothing is worse than a missing one. **The operator overruled that,
      and the standing instruction is now explicit: the EVO II UI is to match the DJI
      MSDKv4 blueprint, and anything not yet wireable ships as a placeholder icon that
      Toasts "not implemented yet" when pressed.** That is the rule for the rest of this
      port — do not re-litigate it per control.

      The flight toolbar now carries **every** control the blueprint has, in the same order
      and at the same sizes; a diff of the two layouts' control ids matches exactly. Also
      added the exposure slider + EV scale + ISO/shutter readout at the top of the HUD
      column, and the "Waiting for aircraft…" cover.

      Genuinely wired this increment (not placeholders):
      - **RTH long-press → move home point to the CONTROLLER's position** (i.e. where the
        pilot is standing), confirmed first, with the coordinates shown in the dialog and
        destructive (red) styling. Uses
        `AutelFlyController.setLocationAsHomePoint(lat, lon, cb)` fed from the controller's
        own `LocationManager` fix, mirroring DJI's `onRthLongPressed`. Refuses outright with
        no fix rather than guessing.

        ⚠ **Wired wrong first time, caught by the operator in review — do not regress.** The
        initial version used `setAircraftLocationAsHomePoint()`, which the Autel SDK also
        offers and which sounds equivalent but sets home to wherever the AIRCRAFT is. That is
        a different feature and the dangerous one here: the entire point of the gesture is
        "I've walked/driven away from takeoff, come back to ME", and the aircraft-position
        variant would instead pin home to wherever it was hovering — a later RTH would land
        it out there rather than return it to the pilot. The correct call and the reasoning
        are now spelled out in `confirmResetHome()`'s doc comment so it can't be
        "simplified" back.

        This also surfaced the same Android 11 runtime-location gap found in Pre-Flight
        Setup: the gesture needs a location grant, so `FlightActivity` now requests it and
        retries rather than silently failing.

      Placeholder + Toast (each names itself and what it will do, rather than a generic
      "coming soon" that invites a second and third press mid-flight): AR (+ its long-press
      options), Photo, Zoom, Video re-sync, REC, Exposure slider, crosshair quick-marker
      (+ its long-press re-aim).

      Two inert *indicators* are handled differently from the inert *buttons*, because they
      can mislead rather than merely disappoint:
      - **Signal bars** are re-set to their null/no-data state every tick (so they can never
        look like a stale reading that used to be live) and tapping them explains that
        strength isn't measured on this airframe — pointing the pilot at the controller's
        own indicator. The Field Guide carries this as a WARNING: greyed bars mean "not
        measured", NOT "link is bad".
        *(Superseded — the bars are live as of the Step 8 revision; they now grey out only
        before the aircraft connects.)*
      - **REC** is held visibly stopped and its guide entry warns that this is not proof the
        aircraft isn't recording, since the app can't read the real camera state either.

      One implementation bug worth remembering: `EvSliderView` consumes `ACTION_DOWN` and
      returns true without calling `super.onTouchEvent`, so `performClick()` never runs and a
      `setOnClickListener` on it is **dead code** — the thumb would slide and nothing would
      happen, which is exactly the broken-looking state a placeholder exists to prevent. It
      is wired through `onIndexChanged` instead, throttled so a drag doesn't stack toasts.

      **Increment 5 — marker management suite: done 2026-07-30.** This closes the Phase 1
      `[~]` marker-suite item and is real operational parity, not a placeholder — none of it
      is hardware-dependent. Drop-pin long-press now opens the Dropped Markers panel
      (reachable with zero pins, since Clear All is still meaningful then), each row showing
      range + bearing from the aircraft via `Units.distance`. Row actions: **Move to
      crosshair / Rename / Change type / Re-send / Delete**, plus **Clear All**, with
      destructive (red) confirms on the two removals.

      `TakDropMarkers` gained `listPins()`/`PinInfo`, `moveToLookPoint()`, `rename()`,
      `changeType()`, `resend()`, `delete()`, `clearAll()` and a `redrawPin()` helper (the
      icon bitmap bakes in the label and affiliation shape, so any of those changing needs
      the marker rebuilt, not just repositioned).

      **The important fix underneath it: pins now carry their CoT uid.** `sendPin()` used
      `sendMarker`/`sendMarkerToMission`, which mint a fresh uid every call — so a re-send or
      a move would have scattered DUPLICATE markers across every other client's picture
      instead of updating the one already there (in CoT the uid *is* the marker's identity).
      It now reuses `Pin.cotUid` via `TakManager.sendMarkerWithUid` — the method ported in
      Phase 0 for exactly this — and **persists the uid**, so a restart doesn't orphan the
      identity and silently reintroduce the duplication bug on the next move.

      Still outstanding for this screen: the quick-marker (crosshair tap) the placeholder
      stands in for, and backing the other placeholders with real Autel camera-control,
      AR-overlay or RF-calibration work. All are implementation, not UI.
- [x] **Pre-Flight Setup — done 2026-07-30.** v1.2 had only
      DJI's sections 3 (TAK Server) and 4 (Video), unnumbered. Now carries all six section
      headings in blueprint order and numbering, so a pilot moving between the two apps
      finds the same thing in the same place.
      - **1. Drone Settings** — max altitude / max distance / RTH altitude in feet, wired to
        the already-ported `FlightLimitsController`. Saves to the same prefs the bridge's
        one-shot reads, and pushes immediately if the aircraft is already connected rather
        than making the pilot reconnect. **No signal-loss failsafe selector**, unlike DJI —
        the Autel SDK exposes no equivalent (Phase 1 audit); absent beats present-but-inert.
      - **5. Elevation Data (DTED)** — import a region .zip via `ACTION_OPEN_DOCUMENT`,
        per-region rows with size/date/file count, delete-region, clean-unused-tiles. Wired
        to the ported `DtedStore`. SAF deliberately (not a filesystem picker): it needs no
        storage permission and is the supported path under Android 11 scoped storage.
      - **6. FAA Airspace Ceilings (UASFM)** — centre lat/lon/radius, Use My Location, Check
        Size, Download with progress, Clear. Wired to the ported `UasfmStore`, and the
        downloaded data is what the flight HUD's ceiling readout reads.
      - **2. Map Display — Street + Custom, no Hybrid** (operator's decision, 2026-07-30).
        New `MapStyle.kt` is the osmdroid counterpart to DJI's `MaplibreStyle`, using the same
        pref file and key names (`map_style`, `map_custom_url`) so the ports stay parallel.
        DJI's third "Hybrid" satellite option is deliberately not offered: shipping imagery
        means picking a satellite tile provider, which is a licensing decision — a pilot who
        wants imagery points Custom at a provider they're entitled to use. `FlightActivity`
        now reads `MapStyle.tileSource()` instead of hardcoding MAPNIK.

        Note the custom source needed a small tile-source class rather than osmdroid's stock
        `XYTileSource`: that one builds URLs as `baseUrl + z + "/" + x + "/" + y + ending`,
        which only works when the provider's path happens to end in exactly that order.
        Pilots paste standard `{z}/{x}/{y}` templates, and providers commonly put an API key
        or style segment *after* the coordinates, so `XyzTemplateTileSource` substitutes the
        placeholders wherever they appear. Templates are validated on save (http(s) + all
        three placeholders) rather than failing at map-load time, and an unusable one falls
        back to street tiles with a log line instead of a blank map in flight.

      **Android 11 bug found and fixed while wiring this.** `lastKnownPhoneLocation()` reads
      `getLastKnownLocation`, which needs a RUNTIME location grant on API 23+. The manifest
      declared `ACCESS_FINE/COARSE_LOCATION` but **nothing in this app had ever requested
      them at runtime** — so on the Smart Controller V3 the read would throw, get swallowed
      by the surrounding `runCatching`, and "Use My Location" would report *"no GPS fix"*,
      blaming the hardware for a permission problem. Now requests on tap (prompt attached to
      the one action needing it), handles the result, and distinguishes "no fix yet" from
      "permission denied" in the message — different problems, different fixes.

- [ ] ~~**Pre-Flight Setup**~~ — original checklist text retained for reference: max altitude/distance/RTH-altitude
      defaults (backend already ported — `FlightLimitsController.kt`, Phase 1 — this screen
      just needs to read/write its saved prefs and call it, no signal-loss-failsafe control
      since Autel's SDK has none, see Phase 1 finding), map style choice, callsign, DTED
      import (§5 on the DJI side — ATAK-style zip import + per-tile delete; `DtedStore.kt`'s
      import/delete API is already ported and ready, Phase 1), channel selection, video
      destination with live URL preview.
- [x] **Field Guide screen — done 2026-07-30.** Ported `FieldGuideActivity` +
      `activity_field_guide.xml`, registered in the manifest, and wired the Field Guide
      button into Home (the entry deliberately left out when Home was rebuilt, because its
      destination didn't exist yet). All the content builders (`title`/`section`/`sub`/
      `body`/`bullet`/`note`/`warn`/`entry`/live-icon helpers) are verbatim, so the two
      guides look and read identically.

      **The content is NOT verbatim, deliberately — and this was the whole job.** The
      blueprint's guide documents 19 controls; **7 of them don't exist on this airframe**
      (controller-signal bars, AR overlay, photo, zoom, video re-sync, record-to-card,
      quick-marker-by-crosshair-tap, exposure slider). Copying it across would have produced
      a field reference sending pilots hunting for buttons that aren't there — the exact
      failure the blueprint's own header warns about ("a guide that only lists happy paths
      is the kind that gets someone in trouble"). So:
      - Only controls this build actually has are documented, and each claim was checked
        against `FlightActivity` rather than inherited from the DJI text. Four corrections
        came out of that check: the TAK badge says "tap to check state" (Autel toasts; DJI
        toggles the connection), RTH has no press-and-hold reset-home, drop-pin has no
        press-and-hold marker list, and the LIVE badge shows only Off/Streaming — its
        RECONNECTING state exists in the ported view but nothing on this side ever sets it,
        so showing it would advertise an unreachable state.
      - DJI-specific wording retargeted (aircraft name, "do firmware in Autel's app", the
        controller's own RTH button being independent of the app's).
      - The "if the signal is lost" bullet became a **warning** instead: it can't be set from
        this app on the EVO II, so the guide says to check it in Autel's own settings rather
        than leaving a pilot assuming the app covers it.
      - The crosshair accuracy-angle figures carry an added caveat that they were measured
        on the Mini 2 and have not been re-checked against the EVO II's camera/gimbal — a
        Phase 4 item, and not something to present as calibrated fact here.
      - **New section 4, "What this build doesn't have yet"**, lists the 7 missing controls
        plainly. A pilot moving over from the Mini 2 will reach for them; saying so beats
        letting them hunt, and it directly serves the cross-airframe goal.
- [x] **Debug Log screen — done 2026-07-30.** Was already built in v1.2 (the handoff's §9
      design got implemented) and audited as near-identical to DJI's — same functions, same
      layout ids bar one. The single gap was the **"Include TAK / CoT logs" filter**, which
      is shared `com.taklite.util` infrastructure that had drifted the same way the
      `com.taklite.client.tak` package had in Phase 0.

      Ported `AppLog.takLogging` + the `TAK_TAGS` set + the write-time filter, with this
      port's own bridge tag substituted (`AutelTakBridge`, not `DroneTakBridge`) — that
      substitution matters, since the bridge tag is the loudest of the group and missing it
      would leave the filter looking broken. Kept DJI's two safety properties verbatim:
      FATAL is never filtered (losing a crash trace to a log-noise setting would be the worst
      possible failure of this switch), and an unlisted tag fails OPEN (extra noise, never
      silent loss). Added the checkbox + explanatory line to `activity_debug.xml` and wired
      it in `DebugActivity`. Layout ids now diff clean against the blueprint.

      **Worth noting for the rest of the port:** this is the second place shared
      `com.taklite.*` code had drifted since v1.2 was cut (after the Phase 0 `client.tak`
      reconcile) — Phase 0 diffed `client.tak` but not `util`, so `AppLog` was missed.

      **Whole-package sweep done 2026-07-30 to close that class of gap for good.** Diffed
      every file under `com.taklite` (9 files) against the blueprint: identical file sets,
      and the only three files that differ now do so ONLY by intentional edits —
      `CotBuilder`/`TakManager`'s sibling-naming comments (each names the *other* port), and
      `AppLog`'s bridge tag + its doc note. **No unintentional drift remains anywhere in the
      shared layer.** Re-run that sweep before shipping, since the DJI side is still moving:
      `diff -r` the two `com/taklite` trees and expect exactly those three comment-level
      differences.
- [x] **Data Sync screen — verified 2026-07-30, no work needed.** Checked rather than
      assumed, since "already ported" was a v1.2-era claim and two other "already ported"
      things (the `client.tak` package, `AppLog`) had since drifted. Result: layout ids diff
      clean, `DataSyncActivity`'s function surface matches, and `TakMissionManager` is
      byte-identical to the blueprint modulo the package line. Genuinely current — the
      Mission API really is vendor-neutral and nothing has moved on the DJI side since.
- [x] **Layout overflow / silent-clipping sweep — done 2026-07-30.** The v1.1 bug class
      (a fixed-height container whose contents overflow, collapsing its weighted spacer and
      pushing the LAST child off-screen — no error, no crash, just a missing control) was
      swept for across every TAK screen. Done by arithmetic on the height budgets rather
      than screenshots, since there's no device in this environment; the numbers are written
      into the layouts so they can be re-checked rather than re-derived.

      **Found and fixed two real instances, one of which I introduced during this port:**
      1. **Flight HUD column overflowed — my bug.** I set `flight_map_size` to **200dp**
         where the blueprint uses **130dp** (160dp via `values-h440dp`), and never created
         the height-qualified override my own dimens comment claimed existed. Budget: ~249dp
         of slider + EV scale + exposure readout + 5-line readout + gimbal + FAA line sits
         above the map, so 200dp needed **449dp** on a Pixel 8 Pro that has **~448dp** — the
         map, being the last child, would have been silently clipped. Now 130dp base +
         `values-h440dp/dimens.xml` at 160dp, matching DJI exactly (409dp needed, fits).
      2. **Home "Enter Flight" card was ~362dp on a 360dp-capable screen** — v1.2 had an 84dp
         logo, a 30sp title, and a redundant 56dp "Enter Flight" **button below an already-
         clickable card**. Restored the blueprint's 72dp/26sp and replaced the duplicate
         button with DJI's "TAP TO ENTER ›" hint: ~60dp reclaimed, one less dead-ish control,
         and closer to the blueprint. The card was always the tap target, so nothing was lost.

      Verified sound (not changed): Pre-Flight Setup, Field Guide and Debug are all
      `ScrollView` + `fillViewport`; Data Sync's list uses `height=0dp weight=1`, which is the
      correct fill-remaining pattern and matches DJI.

      **One residual, inherited from the blueprint rather than introduced:** the flight
      toolbar now carries the full DJI control set and needs **~776dp** of width with its
      spacer collapsed. Fine on the Smart Controller V3 (~1000dp at density 2.0) and a Pixel
      8 Pro (~997dp), but it would clip the right-hand controls (REC/LIVE first) on a ~640dp
      landscape phone. DJI has the identical set and the same constraint, so this is left
      matching the blueprint rather than diverging — but **confirm the Smart Controller V3's
      actual density during the QC pass**, since at density 2.5 it would be ~800dp and
      uncomfortably close.

## Phase 2.5 — Flight-screen activation plan (written 2026-07-30, post-v1.3)

> **STATUS: steps 0–8 IMPLEMENTED 2026-07-30, same day, in order.** All compile-verified;
> none of it has run against an aircraft. Summary of what shipped vs the plan below:
> - **0 done** — `AutelProductHolder` caches the camera via `setCameraChangeListener`, owns
>   `setMediaStateListener` (drives `isRecording` + `photoTakenFlag`), and reads the zoom
>   baseline at connect (raw units logged; assumes camera connects at 1x — QC item).
> - **1–2 done** — REC pill driven by the camera's own `RECORD_*` events (the "stopped is
>   not proof" caveat is retired); Photo tries direct `startTakePhoto`, falls back to the
>   SINGLE→shoot→restore-VIDEO dance, "Photo Saved" notice on `PHOTO_TAKEN_DONE`.
> - **3 done** — zoom toggles baseline↔baseline*2 (units cancel out), feeds
>   `TakBridgeHolder.setLiveZoom` so the SPI cone narrows.
> - **4 SPLIT per operator** — EV slider stays a placeholder (postponed until the camera's
>   exposure behaviour is characterised); the read-only ISO/shutter readout IS live, polled
>   ~2s.
> - **5 done** — re-sync = codec-view teardown/rebuild (`stopCodec` + new `AutelCodecView`).
> - **6 done** — quick marker (tap/long-press reticle), `QUICK_NAME`, quick flag persisted.
> - **7 done** — `ArOverlayView` + `ArSettings` ported (gnomonic projection, edge arrows,
>   categories, air-range, chrome insets, in-flight FOV calibration dialog); bridge gained
>   `cameraPose()`/`isOwnPublishedUid()`; holder gained the calibratable FOV base; the
>   published `<sensor>` cone switched from linear `base/zoom` to the same tan-based
>   `zoomedFov` the overlay projects with, so the two can never disagree.
> - **8 done and live** — see the revision below; the bars show the controller's own
>   percentage, no calibration needed.
> Field Guide updated throughout to match (only the EV slider still carries "NOT WORKING
> YET"). Hardware shakedown items: photo-while-in-VIDEO acceptance, zoom units and
> connect-at-1x assumption, resync black-gap length, AR pose/FOV calibration.

### Step 4 revised, 2026-07-30 — exposure is fully settable; EV slider is live

Operator asked whether the SDK offers anything more for the EV slider, specifically whether
exposure mode can be set. It can, and the full exposure API was there all along on
`AutelXT706` (which the 640T's `AutelXT709` extends):

| Call | Notes |
|---|---|
| `setExposureMode(ExposureMode, cb)` | `Auto` / `Manual` / `ShutterPriority` / `AperturePriority` |
| `setExposure(ExposureCompensation, cb)` | ±3.0 EV in 1/3 stops |
| `setISO(CameraISO, cb)` | ISO 100 – 64000 |
| `setShutter(ShutterSpeed, cb)` | 15s – 1/8000 |
| `setAutoExposureLockState(AutoExposureLockState, cb)` | LOCK / UNLOCK / DISABLE |
| `setSpotMeteringArea(x, y, cb)` | + `RangePair` bounds from the range manager |
| `getExposureMode/getExposure/getISO/getShutter` | readback all works |

New `AutelExposureController.kt` mirrors the DJI blueprint's `ExposureController`: sends
`ExposureMode.Auto` (Autel's equivalent of DJI's PROGRAM — the mode the blueprint settled on
after SHUTTER_PRIORITY silently failed to auto-expose on the Mini 2), then applies the saved
EV. `FlightActivity`'s slider is wired to `setEvAt`; `AutelProductHolder`'s camera-change
listener calls `applyDefaults` so a mid-flight camera reconnect restores the pilot's EV.

Two things deliberately NOT ported:
- **The hidden +2/3 EV bias.** DJI's `HIDDEN_BIAS_STEPS = 2` was derived from three Mini 2
  field flights and is specific to that sensor plus the CENTER metering DJI forces. Set to 0
  here and marked a calibration constant. Copying it would be inventing a calibration.
- **Metering mode.** `MeteringMode` exists as an enum and `XT706StateInfo.getMeteringMode()`
  reads it, but **no public setter anywhere in the SDK takes one** — verified by grepping the
  constant pool of all 5,320 classes, not just the camera package. Only `setSpotMeteringArea`
  is exposed, which is a different behaviour, not a substitute. Genuine gap vs DJI.

**Enum-order trap worth remembering:** Autel declares `ExposureCompensation` DESCENDING
(`POSITIVE_3p0` first → `NEGATIVE_3p0` last), the inverse of DJI's ascending `N_5_0 … P_5_0`.
Porting the blueprint's ordinal arithmetic verbatim would have inverted the slider — up = darker
— and it would have looked like it worked. The controller sorts by a parsed numeric value
instead of trusting ordinal.

### Also found, not yet acted on — `XT706CameraInfo` push feed

> **UNBLOCKED 2026-08-01.** This was blocked on the camera enumerating; it now does (see the
> camera-enumeration section). It has also become more than a nicety: the flight screen's
> aspect-normalisation work needs real per-mode FOV, and the AR overlay / TAK sensor cone still
> use hand-calibrated FOV numbers that should be checked against measurements before the next
> marker-dropping flight.

`AutelXT706.setInfoListener(CallbackWithOneParam<XT706CameraInfo>)` is a **push** feed carrying
a great deal that this port currently polls for, guesses at, or holds a calibration constant for:

- `getISO()` / `getShutterSpeed()` / `getExposureCompensation()` — replaces the 2s
  `pollExposureReadout()`.
- `getZoomScale()` — real zoom, replacing the `zoomBaseRaw` "connect at 1x" assumption.
- **`getHorizontalFOV()` / `getVerticalFOV()`** — the camera's actual live FOV, which the AR
  overlay and the published CoT `<sensor>` cone currently derive from `DEFAULT_HFOV/VFOV`
  constants plus a tan-based zoom narrowing. This would replace a hand-calibration with a
  measurement.
- `getWorkState()` (IDLE/CAPTURE/RECORD/…) — real camera state, which retires the REC
  caveat that the app can't tell whether the aircraft is actually recording.
- `getCurrentRecordTime()`, `getMediaMode()`, SD/MMC state and free space.
- Thermal: center/high/low/touch temperature, `getFocalLength()`, `getPixelSize()` — directly
  relevant to Phase 3.

Deliberately left alone for now: it would rewrite already-reviewed AR/zoom/REC code and is a
scope call for the operator, not a drive-by.

### Step 8 revised, 2026-07-30 — the SDK *does* have a link percentage

Re-audit at the operator's request ("look over the SDK and ensure we aren't missing an
easier option"). It does, and the Step 8 conclusion below was wrong:

`BaseProduct.getRemoteController()` → `AutelRemoteController.setInfoDataListener(
CallbackWithOneParam<RemoteControllerInfo>)`, and `RemoteControllerInfo` exposes
`getControllerSignalPercentage()`, `getDSPPercentage()`, `getBatteryCapacityPercentage()`.
Decompiling the implementor (`RemoteController20$2$1`) shows `getControllerSignalPercentage()`
is a straight passthrough of the RC telemetry packet's `data[1]` — i.e. **the number the
controller's own signal indicator draws**. Same provenance as DJI's
`AirLink.getUplinkSignalQuality()`, so it can be shown honestly with no calibration and the
operator's "we have to calibrate and confirm" precondition no longer applies (it was premised
on raw RF being all that existed).

Wired: bridge caches all three into `Hud.uplinkSignalPct` / `rcBatteryPct` / `dspPct`;
`FlightActivity.updateHud()` now feeds `toolbarSignal`/`toolbarSignalText` exactly as the DJI
blueprint does, including the shared `bucketSignalPct()` (≤10% → 0, else nearest of
25/50/75/100). Tap reports the uncoarsened figure. The raw RSRP/SNR are kept and logged
alongside the percentages on one `LINK:` line as diagnostics/cross-check, no longer as a
calibration dataset. `rcBatteryPct`/`dspPct` are cached but unread — the toolbar's battery is
the AIRCRAFT's, and the blueprint has no controller-battery or downlink readout.

**Third wrong "the SDK can't do this" this phase** (after the signal-loss failsafe and the RTH
home point). Same root cause each time: searching one subsystem — here the DSP/radio path —
and concluding absence, instead of searching the whole 5,320-class surface for the *capability*.
The `javap`-verify rule already in force is necessary but not sufficient; it catches invented
APIs, not missed ones.

Turn every placeholder on the flight screen into a working control. Every SDK claim below
was verified by `javap` against the bundled `autel-sdk-release.aar` (not guessed from
method names — that mistake already happened twice, see the failsafe and RTH entries).
The 640T's camera is the **XT709**, whose interface chain is
`AutelXT709 → AutelXT706 → AutelBaseCamera`.

**Step 0 — camera plumbing (prerequisite for items 1–4).** There is no synchronous
"get camera" call; acquisition is listener-based like everything else on this SDK:
`BaseProduct.getCameraManager().setCameraChangeListener((CameraProduct, AutelBaseCamera))`.
Extend `AutelProductHolder` to install this listener alongside the product listener, cache
the `AutelBaseCamera` (and its `AutelXT706` downcast when applicable), and expose it the
way `evo2` is exposed today. Same single-global-slot caution as
`Autel.setProductConnectListener` — re-install on resume. Also register
`setMediaStateListener` here once, caching media status into the bridge.

**1 — REC (record to aircraft SD).** `startRecordVideo(cb)` / `stopRecordVideo(cb)` on
the base interface. Real state feedback exists: `setMediaStateListener` delivers
`MediaStatus.RECORD_START / RECORD_STOP / RECORD_FAILED_WRITE_ERROR /
RECORD_FAILED_SDCARD_REMOVED / RECORD_BUFFER_FULL` — so the pill can track the camera's
ACTUAL state, closing the "REC showing stopped is not proof" caveat in the Field Guide
(update that entry when this lands). May require `setMediaMode(MediaMode.VIDEO)` first if
the camera is in a photo mode — check `getMediaMode` and switch, mirroring DJI's
flat-mode dance in `onRecordToggleTapped`.

**2 — Photo.** `startTakePhoto(cb)`; confirmation via `MediaStatus.PHOTO_TAKEN_DONE`.
Open question only hardware can answer: whether XT709 accepts `startTakePhoto` while in
`MediaMode.VIDEO` (many cameras do). Attempt directly; on failure fall back to
`setMediaMode(SINGLE) → startTakePhoto → restore VIDEO`, which is exactly DJI's pattern.

**3 — Zoom (1X/2X pill).** `setDigitalZoomScale(int, cb)` + `getDigitalZoomScale` on
XT706 (there is also `setZoomSlide(int, cb)`). **Unknown units** — the int may be a plain
multiplier or x100; read and log the current value on connect BEFORE writing anything,
then derive the 2X value from the observed 1X reading. On success set
`bridge.liveZoom`, which already narrows the published SPI FOV cone — that plumbing has
been waiting for exactly this.

**4 — EV slider + ISO/shutter readout.** `setExposure(ExposureCompensation)` — enum in
1/3-EV steps spanning ±3.0, superset of the slider's −2..+2, so the existing 13-step
`EvSliderView` maps index→enum directly with no UI change. Readout: `getISO(cb)` /
`getShutter(cb)` polled on the 2s bridge tick into cached fields (no push listener for
these). Deviation from DJI to note in code: DJI's `ExposureController` forces
shutter-priority + auto-ISO on connect; on Autel leave `setExposureMode` alone initially
— EV compensation works within whatever auto mode the camera is in, and forcing modes on
an uncharacterised camera risks trading a known-good picture for a theory.

> **Partly superseded — see "Step 4 revised, 2026-07-30" above.** The slider now sets EV, and
> `setExposureMode(ExposureMode.Auto)` IS sent on camera connect. The reasoning above wasn't
> wrong about the risk, but "whatever auto mode the camera is in" turned out to mean "whatever
> Autel Explorer last left it in", which is not a known-good baseline — it's an unknown one.
> A push feed (`XT706CameraInfo`) also exists that would retire the 2s poll.

**5 — Video re-sync.** No direct "request IDR" hook, but `AutelCodecView` exposes static
`stopCodec()` / `startDecode(...)` / `pause()` / `resume()`. The contained approach,
since FlightActivity already constructs the view in code: tear down and rebuild —
remove view from container → `stopCodec()` → new `AutelCodecView` → re-add. Functionally
DJI's `requestResync()`, likely with a similar few-second black gap. Also reuse this for
the screen-lock/background recovery path Phase 5 wants to validate.

**6 — Quick marker (crosshair tap / long-press re-aim).** No SDK work at all — pure port
of the blueprint's `TakDropMarkers.quickPin()/placeQuick()/moveQuick()` + `QUICK_NAME`
(the remaining marker-suite gap already itemised under Phase 2) and wiring
`crosshairView.onReticleTap/onReticleLongPress` exactly as DJI's flight screen does.
`lookPoint()` and the send-with-uid machinery it needs are already live.

**7 — AR overlay (+ options long-press).** Biggest item, but pure app-side port:
`ArOverlayView.kt` (~695 lines) + `ArSettings.kt`. Its DJI imports map 1:1 to things that
now exist on this side (`CameraSlantPoint`, `TakDropMarkers.pinsForAr`, `TakMapMarkers`,
`TerrainAgl`, hud) — with one addition needed: port `cameraPose()` from `DroneTakBridge`
into `AutelTakBridge` (same bearing/pitch model as `lookPoint()`, one source of truth so
drops and overlay can never disagree). Known accuracy dependencies, all flagged
elsewhere: video-rect assumption, uncalibrated `PITCH_SIGN`/bearing offset, and the FOV
constants — AR will be *drawable* before it is *trustworthy*; the 6D-D FOV calibration
flow exists on the DJI side for exactly that reason.

**8 — Signal bars.** *(This whole paragraph is WRONG — see "Step 8 revised, 2026-07-30"
above. There IS a ready-made value; no calibration is needed and none was done.)*
The only item without a ready-made value. `BaseProduct.getDsp()` →
`EvoDsp.setDspInfoListener(EvoDspInfo)` → `EvoDspInfo.getSignalStrengthInfo()` returns
`SignalInfo` with raw RF metrics: `getRsrp()[]`, `getMasterSnr()`, `getAirSnr()`,
`getMeanPower()`. Wire the listener + cache NOW and log raw values every tick (that log
IS the Phase 4 calibration dataset), but map to bars only behind a provisional
RSRP→quality curve marked as a calibration constant — and keep the indicator in its
no-data state until the mapping has been sanity-checked against the controller's own
signal display on hardware. This is the one place "wire it now" and "show it now" split:
an invented link-quality number is the exact thing the standing rule's indicator
carve-out exists to prevent.

**Order of implementation:** 0 → 1 → 2 → 3 → 4 → 5 (camera cluster, each small once
plumbing exists) → 6 (small) → 7 (large) → 8 (plumbing now, display after calibration).
Rationale: the camera cluster shares Step 0 and each item is independently testable; the
quick marker unlocks the last Field Guide "NOT WORKING YET" that needs no hardware; AR
last because it's biggest and its accuracy is gated on Phase 4 calibration anyway.

**Phase 3 tie-in discovered during this survey:** `AutelXT706.setDisplayMode /
getDisplayMode` with `DisplayMode.{VISIBLE, PICTURE_IN_PICTURE, IR, OVERLAP}` is the
thermal/lens switch Phase 3 defers — and it's a two-call API, much simpler than feared.
When it lands, it must drive `bridge.activeLens` (the hook preserved since v1.2) so the
SPI FOV switches between the EO and IR constants.

## Phase 3 — Camera/gimbal seam (thermal deferred)

- [ ] Keep the Flight screen single-lens, visually identical to the Mini 2's — do **not**
      build PIP dual-lens control, on-screen EO/IR toggle, or an HSI strip in this pass.
- [ ] Preserve (don't remove) the existing `AutelTakBridge.activeLens` hook — it's the
      intended future splice point for a thermal toggle. Leave a comment marking it as
      such if one isn't already there.
- [ ] Confirm `EO_HFOV/VFOV`, `IR_HFOV/VFOV` constants exist and are spec-sheet values, so
      switching camera modes later is a data change, not a structural one.

## Phase 4 — Hardware bring-up

The Autel port has **never** run on a real Smart Controller V3 or 640T V3 — all testing
to date is a Pixel 8 Pro with no aircraft. Once Phases 1–2 land, expect the same category
of one-flight calibration items the DJI app already resolved once, using the same method
(named constants, both candidates logged, one flight settles it):

> ### QC PASS — do this before trusting any of Phases 1–2 (operator's plan: finish the port,
> ### then iterate fixes one at a time)
>
> **Everything in Phases 0–2 is compile-verified and structurally diffed against the
> blueprint. NONE of it has been run** — there was no emulator or device available in the
> porting environment. Treat "wired" in this doc as "wired and it builds", not "observed
> working."
>
> That distinction has already cost two real bugs that a compile could never have caught,
> both found by reading/review rather than by the toolchain:
> - **RTH long-press set home to the AIRCRAFT's position instead of the pilot's** — would
>   have looked like it worked (dialog confirms, SDK returns success) and only shown up as
>   an RTH flying to the wrong place. Caught by the operator.
> - **Dropped-marker re-send minted a new CoT uid each time** — would have scattered
>   duplicate markers across every other client instead of moving one.
>
> Both were "plausible API, wrong semantics." Expect more of that class. Highest-risk items
> to check first, because each currently rests on an assumption rather than an observation:
> 1. **`CrosshairView` centres on the view, not a real video rect.** If `AutelCodecView`
>    letterboxes internally, the reticle is off the true image centre and EVERY look-point
>    drop inherits that offset. Check against a known ground feature.
> 2. **Gimbal pitch sign / bearing reference** (`PITCH_SIGN`, `BEARING_MODE_RELATIVE`,
>    `BEARING_OFFSET_DEG`) — still uncalibrated; wrong values put the SPoI and every marker
>    drop in the wrong place, and the crosshair accuracy ring would be confidently wrong too.
> 3. **`LockedMapView`** — confirm pan/zoom really are dead AND that tapping an inbound
>    contact still hides it (the two pull in opposite directions; the whole class exists to
>    keep both).
> 4. **DTED import under Android 11 scoped storage** — SAF path is right in principle,
>    untested against the controller's file provider.
> 5. **Placeholder toasts** — confirm each fires (the EV slider's nearly didn't; see
>    increment 4).
> 6. **Screen density / layout fit on the Smart Controller V3.** The layout sweep was done by
>    arithmetic, not on glass. Two numbers to confirm first: the flight toolbar needs ~776dp
>    of WIDTH (right-hand controls clip first if short), and the HUD column ~409dp of HEIGHT
>    (the mini-map clips first). Both fit at density 2.0; density 2.5 would make the toolbar
>    marginal. Check the map's bottom edge and that REC/LIVE are both fully on screen.

- [ ] Gimbal pitch sign (`PITCH_SIGN`)
- [ ] Gimbal yaw reference frame + offset (`BEARING_MODE_RELATIVE`, `BEARING_OFFSET_DEG`
      — DJI needed +105°, Autel starts from an unknown baseline)
- [ ] 640T FOV constants (`EO_HFOV/VFOV`, `IR_HFOV/VFOV`) — spec-sheet starting values,
      tune against the rendered AR cone
- [ ] GPS accuracy units (`ACC_DIVISOR`) — believed mm, sanity-clamped, bench-verify
- [ ] HAE altitude spot-check against a known surveyed point
- [ ] Video: confirm the raw-frame codec listener and on-screen `AutelCodecView` can run
      **simultaneously** — undocumented in the Autel SDK and never tested on hardware.
      If they conflict, the contained mitigation is already identified: decode our own
      frame tap into a `SurfaceView` via MediaCodec, since the raw frames are already in
      hand.
- [ ] Physical button mapping on the Smart Controller V3 — key codes unknown; log
      `onKeyDown` on real hardware before wiring anything (DJI's RC button mapping was
      not portable and this needs its own investigation from scratch).
- [ ] RTH behavior end-to-end — sticks/RTH ride Skylink directly, not the app, but verify
      the app's RTH button/confirmation dialog and failsafe reporting behave the same way
      pilots already expect from the DJI app.
- [ ] Wi-Fi hotspot handoff mid-flight — Smart Controller V3 has no cellular modem; the
      client reconnects on socket failure but switching hotspots mid-flight has never been
      observed. A connectivity-change callback for immediate clean reconnect (rather than
      waiting on a socket timeout) is a small, worthwhile addition, mirrored from any
      equivalent hardening already done on the DJI side.

## Phase 5 — Field validation

Mirror the DJI app's now-proven validation checklist, adapted to Autel's aircraft/link:

- [ ] Connect + fly the EVO II 640T V3 via the Smart Controller V3.
- [ ] Live drone PLI + SPI visible and correctly symbolized on a second TAK client.
- [ ] Operator's own callsign (not username) correct in the server's Connected Users
      panel — same check we just did on the DJI side; verify the Autel call sites were
      built correctly from the start per Phase 0.
- [ ] Video path end-to-end: on-screen local video AND RTSP push to the media server,
      both live, ideally simultaneously (resolves the Phase 4 open question).
- [ ] Screen-lock / navigation recovery — video and TAK connection survive backgrounding,
      matching DJI's ~3.5s hard-resync behavior (though the Autel decode path differs, so
      this needs its own verification, not an assumption of parity).
- [ ] RTH command actually acknowledged by the aircraft (DJI hit a real "process timed
      out" RTH bug in the field on 2026-07-27 — see the DJI plan doc's open items; watch
      for anything analogous on Autel).
- [ ] Marker placement, movement, and Data Sync feed publication round-trip confirmed
      with a second client.

---

## Target hardware — Autel Smart Controller (measured 2026-07-31)

Read off the real controller over adb, not from a spec sheet. Everything here is
`adb shell` output from the device itself. **First time this project has touched the actual
deployment hardware** — every UI judgement before this date was made on an OUKITEL RT3
(533 × 853 dp), which is a different shape entirely.

### Identity

| | |
|---|---|
| `ro.product.model` | `RCPad` |
| `ro.product.device` / `name` | `sdm660_64_ms_01` |
| `ro.product.manufacturer` / `brand` | `QUALCOMM` / `qti` (unbranded — do NOT match on "Autel") |
| SoC | Qualcomm **SDM660** (Snapdragon 660), 8 cores, `arm64-v8a` |
| ABI list | `arm64-v8a, armeabi-v7a, armeabi` |
| Android | **11 / API 30** — confirms the API 30 assumption this port was built on |
| Build | `RKQ1.210304.002 1.3.9.23`, **userdebug / test-keys**, built 2025-07-01 |
| Security patch | 2021-02-05 (old — assume no modern platform fixes) |
| Serial | `5554d34f` |
| Autel Explorer | `com.autelrobotics.explorer` **V3.1.134** |
| MaxiTools | `com.Autel.maxitools` 2.45 |

`userdebug`/`test-keys` is useful: the build is more permissive than a production ROM for
`logcat`, `dumpsys` and sideloading.

### Screen — the big one

| | |
|---|---|
| Physical | 2048 × 1536 @ 320 dpi (xhdpi) |
| Full | **1024 dp × 768 dp** |
| App area (`app=2048x1440`) | **1024 dp × 720 dp** — system bars take 48 dp |
| Smallest width | **sw720dp** |
| Aspect | 4:3 |

**This invalidates every layout judgement made before 2026-07-31.** All UI review to date was
on the RT3 at 533 × 853 dp. The controller is ~2× wider and 4:3 rather than tall. Consequences:

- **`values-w820dp` now applies and has never been looked at.** It did not apply on the RT3.
- `values-h440dp` applies (720 dp ≫ 440 dp), so `flight_map_size` = 160 dp — on a 720 dp-tall
  screen that is proportionally about half the presence it had on the RT3, where it was judged
  correct.
- Pre-Flight Setup's 3-column rows, the flight toolbar, AR edge arrows and the Field Guide icon
  strips were all sized against 533 dp of width. At 1024 dp they will be sparse, not clipped —
  the opposite failure from the one the clipping audit hunted for.
- The earlier ScrollView-clipping work was correct for the RT3 and is not evidence about this
  device.

### Location — controller GPS is real

Relevant because RTH long-press sets the home point from **controller** GPS, and "Use My
Location" in Pre-Flight Setup depends on the same.

- Features declared: `android.hardware.location`, `.location.gps`, `.location.network`.
- Providers: `gps` (enabled, allowed, `requires=satellite`, supports bearing/speed/altitude),
  `fused` (enabled, allowed), `passive`.
- `location_mode = 3` (high accuracy), `location_providers_allowed = gps`.
- GNSS HAL present: `mTopHalCapabilities=0x41 (SCHEDULING MEASUREMENTS)`.

So `lastKnownPhoneLocation()`'s comment is correct — this controller has its own GNSS.

> **Indoors it reports `last location=null` with zero fixes**, so `getLastKnownLocation()`
> returns null and both features correctly show "No GPS fix yet". That is the expected bench
> behaviour, NOT a bug — do not "fix" it. Verify these outdoors only.

### Sensors

Accelerometer + gyro (InvenSense **MPU6500**, 200 Hz), magnetometer (ISENTEK **IST8310**,
100 Hz), proximity, ambient light. Each has a wakeup twin.

**No barometer** — controller-side pressure altitude is not available, which is fine: all
altitude comes from the aircraft.

**No cameras** (`Number of camera devices: 0`) — worth knowing before anything assumes a
device camera exists.

### Memory, storage, video

| | |
|---|---|
| RAM | 3.7 GB total, ~2.3 GB available; 2 GB swap; **not** `low_ram` |
| Dalvik heap | 512 MB max, **256 MB growth limit** — the per-app ceiling that matters |
| `/data` | 109 GB, 5.7 GB used, **102 GB free** |
| GPU | OpenGL ES 3.2 (`ro.opengles.version = 196610`) |
| HW video | `OMX.qcom.video.decoder.avc` / `.hevc`, `encoder.avc` / `.hevc` |

- The **2 GB tile cache is comfortable** against 102 GB free — no need to lower it, and room
  to raise it if a region download needs more.
- H.264 **and** H.265 hardware decode and encode are both present, so the FPV feed and the
  screen-capture transcode both have hardware paths on this SoC.
- 256 MB heap growth limit is the number to watch if tile cache, video buffers and the AR
  overlay ever contend.

### Connectivity / adb

- `wlan0` only (plus loopback). No cellular interface — **the controller is wifi-only**, so
  "download on wifi" guidance is the only option, not a preference.
- **USB-C is a data port and does work for adb.** Correct orientation: **C end into the
  controller, A end into the laptop.** A first attempt produced no USB enumeration at all
  (nothing in `dmesg`, nothing in `lsusb`) — cable/orientation, not a device limitation.
- The controller's **USB-A is a host port** (thumb drives work there). It cannot be used to
  reach a laptop — host-to-host enumerates nothing. Not a cable problem; wrong direction.
- **Wireless adb works and needed no pairing step**: `adb connect 192.168.3.127:43695`
  connected straight to `device` state. Android 11 wireless debugging, port is
  re-randomised per session.

### Open questions for the next hardware session

1. Screenshot every screen at 1024 × 720 dp and re-judge the layouts. Assume nothing carries
   over from the RT3.
2. Read what `values-w820dp` currently does to these screens — never inspected.
3. Confirm GPS outdoors: does "Use My Location" fill, and does RTH long-press accept?
4. Aircraft would not bind to the controller this session — unresolved, and separate from
   anything in this app. Pull Explorer's logcat during a pairing attempt.

## Camera enumeration — ROOT CAUSE AND FIX (2026-08-01)

*All of this was verified on the real EVO II 640T V3 + Smart Controller, with SDK logging
on. Where something is inference rather than measurement it says so.*

### The symptom

The SDK reported **success** with `CameraProduct.UNKNOWN` and handed back an `UnknownCamera`.
`AutelProductHolder.camera` was non-null so Photo/REC reached the SDK and failed with *"The SDK
init has failed since the communication to the aircraft has not been build up"*; `xt706` was
null (failed `as? AutelXT706`) so IR/zoom/exposure said "Aircraft camera not connected". Live
video worked throughout — a separate path.

### The cause, in one line

**The camera identifies itself as `XL726`, and no published Autel SDK contains that string.**

Chain, every step read off the bytecode and confirmed in the log:

1. The camera's `SystemStatus` push carries `CameraType: "XL726"`.
2. `CameraMessageDisPatcher.transferType()` remaps `XL720/XL705 → XT705` and
   `XL719/XL729/XK729/XL709/XL725 → XT709`. **`XL726` is in neither list**, so it passes
   through unchanged. It misses by one digit.
3. `CameraProduct.find("XL726")` → `UNKNOWN`; `BaseCamera20.getProduct()` is nothing but that
   lookup.
4. `CameraMessageDisPatcher.notifyConnected()` checks `product == UNKNOWN` and **suppresses the
   CONNECTED notification.** That guard is correct — logged as `camera notifyConnected true`.
5. So `CameraManager.connectStateChanged()` is never called with a real product, `currentCamera`
   stays null, and `CameraManager$2` substitutes `new UnknownCamera()` as a **null placeholder**.
   That placeholder is the "camera" the app was holding. It was never a camera.
6. **The latch:** the SDK's 3s retry (`Observable.interval` in `initHandler`) only runs while
   `isConnected == false`, and `setCameraCurrentData()` calls `notifyConnected()` *before*
   `setCameraCurrentDate()`, whose success callback sets `isConnected = true`. The SDK checks the
   product exactly once, gets UNKNOWN, then disables its own retry forever.

### Why no SDK update fixes it

All three of AutelSDK's public repos were checked (2026-08-01):

| repo | aar | camera IDs | XL726 |
|---|---|---|---|
| `AndroidAdvanceSample` | byte-identical to ours (`59023ae8`, 10,181,182 B, 2024-01-13) | `XK729 XL705 XL709 XL719 XL720 XL725 XL729` + `XT701..XT712` | no |
| `AndroidSample` | different build, 2023-08-01 | `XK729 XL709` + `XT701..XT712` | no — a strict SUBSET of ours |
| `MSDK2.0 V2.0.66` | 2024-09-23 | `XL705 XL709 XL715 XL716 XL720 XL730 XL732 XL736 XL8xx` | no |

Ours already IS `AndroidAdvanceSample` HEAD. MSDK2.0 has **no `XT7xx` classes at all** and jumps
`XL720 → XL730` — a different product generation (EVO Max, *inferred* from the numbering), and
adopting it would mean rewriting the whole SDK integration layer for no gain here.

That `XL726` belongs with `XL725` is **corroborated externally**, not guessed from the adjacent
number: the third-party `crgrove/automated-drone-image-analysis-tool` lists `"XL725, XL726"` as
the camera IDs of the same airframe — *Autel / Evo II Dual 640T*, RGB and Thermal — and its
thermal parser handles `XL726` explicitly. `XL725` already maps to `XT709`.

### The fix — a patched aar

`buildsystem/patch-autel-sdk-camera-id.py` repoints the string `XL719` → `XL726` across
`classes.jar`. Both are 5 bytes, so offsets, constant-pool structure and class lengths are
untouched. `XL719` is the donor because it is the only alias in BOTH lists that need fixing
(`transferType`'s XT709 group AND `RxAutelBaseCameraImpl.isEvoAdvance`), so one edit repairs
both — and they read *different* fields (`cameraModel` vs `cameraRealType`), so fixing one alone
would not have fixed the other.

**It MUST be a global replace, not a `CameraProduct.class`-only edit.** Class files deduplicate
UTF-8 entries, so the enum constant name, its `moduleName` value and the *field* name are one
shared entry; the other two classes reach that field by name via `getstatic`. Patching only
`CameraProduct` yields `NoSuchFieldError` at runtime.

**⚠ LANDMINE:** this build can no longer recognise genuine `XL719` hardware, and
`CameraProduct.XL719.name()` now returns `"XL726"`. Deliberate trade — an alias this airframe
will never use, bought for one it cannot fly without. Revisit if pointing this repo at a
different Autel camera. The right fix remains an Autel SDK that knows XL726; ask them.

### Verified working on hardware after the fix

Camera enumerates as `XT709` (`CameraXT709InitializeProxy`); photo (file confirmed on the
camera's own file server); recording (real `.MP4` files); IR display mode + BlackHot/WhiteHot
palettes; exposure set; zoom read.

**Zoom units measured: ×100** (`GetZoomFactor` → `ZoomValue: 100` at 1x). This resolves the
"could be a plain multiplier, could be x100" note on `AutelProductHolder.zoomBaseRaw`.

### THIS CAMERA REPORTS SUCCESS FOR THINGS IT DOES NOT DO

Three separate instances in one session. **Do not trust an Autel callback's `onSuccess` as
evidence that anything happened — verify the effect.**

1. **`StartRecording`** issued 2 ms after a Single→Record mode switch: answered `status: 0`,
   produced no `RECORD_START`, and left **no file on the card**. Fixed by
   `FlightActivity.startRecordVerified()` — settle delay, then confirm via the camera's own
   `RECORD_START`, retry once, and toast the pilot if it still never starts. A pilot who
   believes they have footage they do not have is the worst failure this control has.
2. **`setAspectRatio(Aspect_16_9)`**: answered success, changed nothing. Preview stayed
   1280×960 and stills before/after were both **4000×3000** (measured off the camera's file
   server). Do not re-add it; there is a note in `AutelProductHolder` saying so.
3. **`setMediaMode(VIDEO)` on the `UnknownCamera` placeholder**: fails by definition — every
   method on that object returns the "communication…not been built up" error. Not a timing
   problem. Guard camera-init work on `as? AutelXT706` so it runs only against a real camera.

### Display geometry — the camera sends three different shapes

| mode | stream | aspect |
|---|---|---|
| photo | 1280×960 | 4:3 |
| video | 1280×720 | 16:9 |
| IR | 640×512 | 5:4 |

Note photo vs video is the **same width, less height** — video genuinely has less vertical FOV.

**Neither can be equalised from the camera:** `setAspectRatio` is ignored (above), and
`VideoResolution` has no 4:3 option (7680×4320 / 5760×3240 / 4096×2160 / 3840×2160 / 2720×1528 /
1920×1080 / 1280×720 are 16:9; 1280×1024 and 640×512 are 5:4).

**This matters to the whole team, not just the pilot: the TAK feed is a MediaProjection SCREEN
CAPTURE** (`ScreenCaptureService` → `VideoStreamerHolder.startScreenCapture`). Anything on the
flight screen — bars, aspect jumps, overlays — is in their feed. `AutelVideoStreamer`'s
constructor defaults `mediaProjection = null`, which reads like the camera-feed path is the only
one; **check the call site, not the default.** A stale comment claiming screen capture was an
unported "known gap" cost real debugging time and has been corrected in place.

**What was done about it:**

- `FlightActivity.armVideoFill()` / `applyVideoFill()` — the video now FILLS the screen in every
  mode, aspect preserved, edges cropped, **true centre locked to the reticle**. `AutelCodecView`
  is a `TextureView`, so `setTransform()` scales what the SDK already rendered; the SDK fits
  content *centred* (its own `renderPos` trace proves it), so a centre-anchored uniform scale of
  `max(contentAspect/viewAspect, viewAspect/contentAspect)` crops the bars off exactly. The
  centre is the invariant on purpose — the crosshair is the aiming reference for marker drops,
  so losing edges is an accepted trade but a drifting centre would silently corrupt every marker.
- **VIDEO is the resting mode**, set at camera connect (`AutelProductHolder.setVideoModeWithRetry`)
  and restored after each photo. Operator's call: recordings are far more frequent than stills,
  so the unavoidable shape change belongs on the photo path, and recording — when the team is
  watching — stays visually still.
  **Setting it AT CONNECT is the part that matters.** The camera remembers its mode across power
  cycles, and this app previously only ever changed mode *reacting* to a button press, so it came
  up in whatever mode the camera was left in. "Video is the resting mode" was only true after the
  pilot had already pressed something.
- The shutter is disabled (greyed, not hidden) while recording, driven off the camera's reported
  state so it re-enables if a recording stops on its own.

**Still open:** the three modes still differ in apparent framing, because filling a fixed screen
with different source shapes crops each differently (4:3 → ×1.103, 5:4 → ×1.177, 16:9 → ×1.208).
Truly identical framing needs normalising to real FOV numbers from `XT706CameraInfo.setInfoListener`
— scale every mode so the same real-world angle fills the screen, cropping to the narrowest common
view. **Measure the FOVs and show them before committing**, since normalising IR to a much tighter
thermal lens could waste most of the EO image.

### SDK logging — it is OFF by default and that is why evidence kept vanishing

The SDK's most useful diagnostics go through `AutelLog.debug_i` → `com.autel.log.AutelLog.i`,
which is a **no-op while its `mLogger` field is null**, and nothing installs that logger by
default. `TestApplication.initAutelSdkLog()` now calls
`com.autel.log.AutelLog.init(true, …)` — `debug=true` selects `DebugLog`, which mirrors to
`android.util.Log`. (`debug=false` selects `LogImpl` → Tencent mars/xlog → **nothing in
logcat**.) Beware two same-named classes: `com.autel.util.log.AutelLog` is a thin
`android.util.Log` wrapper and is NOT the one that matters; the commented-out `AutelLog.init` in
`initXlog()` is that other class's XLog-era signature and would not have helped.

Useful tags: `ConnectDebug`, `MessageDisPatcher`, `AutelCameraConnectManager`, `camera_connect1`,
`camera_connect11`, `xxxx`.

### Also learned

- **Read the aar, don't infer from names** — but also **check the CALL SITE, not the default
  parameter**. Both bit this session.
- The camera's file server (`http://192.168.1.11/DCIM/100MEDIA/`) is reachable *from the
  controller* and is the ground truth for "did that actually record/shoot": `adb shell curl`.
  Ranged GETs plus a JPEG SOF parse give real image dimensions without pulling whole files.
- `AutelXT709 extends AutelXT706`, so the existing `xt706` accessor works unchanged once a real
  camera is built.

## Environment / Tooling

- Project root: `/home/echos6/SynologyDrive/TAKServer/UAS_Apps/Autel/AutelTAKPilot2/takpilot-autel_v1-2/`.
  Under git — commit `c175d40`, tag `autel-baseline-v1.2`.
- **Target hardware runs Android 11 (API 30).** The Smart Controller V3 is the real
  deployment OS — design/test assumptions should track API 30 behavior (scoped storage,
  permission auto-revoke, foreground-service start restrictions), not a newer dev device's
  OS. See the Android 11 callout near the top of this doc.
- Toolchain: Gradle 7.3.3 / AGP 7.2.2 / Kotlin 1.7.20, `compileSdk` 33, `minSdk` 21,
  `targetSdk` 29.
  **Use JDK 17** — corrected 2026-08-01. This line used to say JDK 11, and JDK 11 no longer
  builds this tree at all: the vendored `rtsp-2.2.6-api.jar` ships **Java 17 class files
  (major version 61)**, so `kaptDebugKotlin` dies with *"class file has wrong version 61.0,
  should be 55.0"* on `com.pedro.rtsp.rtsp.RtspClient`. JDK 17 builds clean:
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug`.
- `chmod +x gradlew` after any unzip/re-sync — the execute bit is stripped by zip
  extraction and by Synology Drive sync; this has bitten the project twice already.
- Autel SDK is a bundled AAR in-repo, not a remote artifact. App ID `com.tak.uastoollite`;
  App Key already wired into `TestApplication.java`.
  **⚠ THE BUILD CONSUMES A PATCHED COPY, NOT THE VENDOR FILE.** `app/build.gradle` depends on
  `autel-sdk-release-xl726.aar`, generated from the pristine `autel-sdk-release.aar` by
  `buildsystem/patch-autel-sdk-camera-id.py`. Without it this aircraft's camera does not
  enumerate and NO camera command works. Regenerate after any SDK change; the script aborts
  loudly if the vendor aar's shape changes. Full reasoning in the camera-enumeration section
  below and at the top of the script.
- Build: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
  Install: `adb install -r <apk>`.
- **Two test devices, and they are not interchangeable.** OUKITEL RT3 (`OUKITELRT349474`,
  Android 13, 533 × 853 dp) is the convenience device; the Autel Smart Controller
  (`f270d328`, model `RCPad`, Android 11, 1024 × 720 dp — serial corrected 2026-08-01,
  this doc previously said `5554d34f`) is the real target. Always pass `-s <serial>` when
  both are attached. A layout verdict on the RT3 says nothing about the controller — see the
  target-hardware section above.
- Reference material to keep on hand: `TAKPilot2-source-V1.zip` (the original DJI
  TAKPilot2 source this v1.2 port was built against — useful for archaeology, but prefer
  the *current* `SampleCode-device-compat` tree as the parity target for anything UI or
  feature related, since that's what pilots will actually compare against).
- Standing constraint carried over from the DJI project: **do not commit without asking
  first.**

## Flight test 2026-08-01 (evening) — sign conventions, terrain, marker accuracy

*First real flights after the camera started working. Almost everything here was found by
flying, not by reading code.*

### AUTEL USES THE OPPOSITE SIGN TO DJI. TWICE.

Two independent inversions, both fixed by ONE negation at ingest — never at the display, never
in a downstream consumer, because each value feeds several consumers that must agree.

**1. Gimbal pitch — Autel reports DOWN POSITIVE.** Tilting down read "GIMBAL n° UP". Negated in
`setAngleListener`. It feeds three places: the HUD readout + crosshair accuracy ring, the CoT
`pitch=` attribute **published to TAK**, and the SPI/AR/sensor-cone math. Flipping `PITCH_SIGN`
(which the old comment invited — *"flip to -1.0 if inverted"*) would have fixed only the third
and left the HUD and the team's data inverted. `PITCH_SIGN` is now documented as a calibration
scale, explicitly NOT the inversion fix.

**2. Relative altitude — `LocalCoordinateInfo` is NED, i.e. DOWN POSITIVE.** At +198ft the HUD
read **-198ft**. Confirmed by the only test that settles a sign question: climbing drove it more
negative, descending toward zero, zero on the ground.

That one was not cosmetic. `aglMeters()` returns `relAlt` only when positive, else **0** — so the
slant-point solver placed every marker as if the aircraft were on the ground (visible as `agl=0`
in the SPI log while airborne). **That was the real cause of "markers are inaccurate", not a
calibration problem.** `isFlying` was also false for entire flights.

**Lesson: when one Autel telemetry value turns out inverted, sweep the others immediately.**
Pitch was fixed first and altitude was sitting right there with the same defect, found only when
the pilot noticed a negative altitude in flight.

### DTED: the coarse tile was silently winning

`DtedIndex.elevationAt()` returns the FIRST tile covering a point, and `DtedStore.listFiles()`
sorts by FILENAME. A cell imported at several levels flattens to `w149_n61.dt0` / `w149_n61.dt2`,
and **`.dt0` sorts first** — so the ~900m-post DTED0 won every lookup and the ~30m DTED2 the
pilot imported was parsed, indexed and never read. Fixed by sorting on `DtedTile.postSpacingDeg`.

Confirm from the log on first lookup:
`DtedIndex: loaded 16/16 DTED tile(s); finest post spacing 2.7777E-4°` (1 arc-sec = DTED2;
`8.33E-3°` would mean it is still on DTED0).

### Marker accuracy — measured, with the geometry that governs it

Measured against CloudTAK, ~200ft AGL:

| gimbal down | result |
|---|---|
| 54° | accurate to **<1m** (measured 45.01m ground distance vs 45.0m predicted) |
| 30° | "very accurate" |
| 21° | ~10ft out |
| 6° | ~200m out |

**Ground error scales as `1/sin²(pitch)`.** At 200ft AGL, per 1° of aim bias: 5ft at 54°, 27ft at
21°, **323ft at 6°**. Per 1m of terrain error: 0.4m at 54°, 2.6m at 21°, 9.5m at 6°. This is
geometry and applies to any aircraft — but it is NOT the whole story (see below).

**The AR/SPI math itself is sound.** At the 21° drop the solved pin sat 0.1° off the camera
bearing and 0.1° off camera pitch — the pipeline places the marker exactly where it believes the
camera points. Residual error is in the INPUTS, not the solve.

**Do not dismiss residual error as "just geometry".** The operator's DJI Mini did better at the
same look angles, which physics alone cannot explain. The real difference: **the DJI port's
offsets were flight-tuned (`BEARING_OFFSET_DEG = +105`); this port's had never been measured and
sat at 0.** Uncalibrated, not worse physics.

Also note: **relative altitude drifts between flights** — ground readings of -1.56m and -0.42m on
two flights, so it is per-flight drift (barometric/takeoff reference), not a fixed offset that
could be calibrated out. At 21° a 1.5m altitude error alone is ~13ft of marker miss.

### Aim calibration is now a pilot-facing control (Stage 1)

`PITCH_OFFSET_DEG`/`BEARING_OFFSET_DEG` moved from `const val` to runtime + SharedPreferences,
mirroring the existing FOV calibration exactly (`TakBridgeHolder.setAimOffsets` /
`ArSettings.loadAimOffsets`). **Long-press AR → "Aim Offsets…"**, ±0.25° steppers, applied and
persisted live.

Being compile-time is precisely why they were never calibrated — every candidate value cost a
rebuild. **Calibrate SHALLOW (15–25°)**: a bias is nearly invisible steeply down, so tuning at
50° proves almost nothing.

Stage 2 (not built): guided multi-angle calibration that least-squares solves for both offsets
and reports the residual. Deliberately deferred until hand-tuning shows a single offset actually
closes the DJI gap — otherwise Stage 2 would be built on an unproven premise.

### Camera / gimbal behaviour learned in flight

- **The camera lies about `StartRecording`.** Issued 2ms after a mode switch it returns
  `status: 0`, emits no `RECORD_START`, and writes no file. `startRecordVerified()` now settles,
  verifies against the camera's own event, retries once, and TOASTS the pilot on failure.
- **`setAspectRatio(Aspect_16_9)` is accepted and ignored.** Stills stayed 4000×3000 before and
  after (measured off the camera's file server). Do not re-add it.
- **Three stream shapes, unavoidable:** photo 1280×960 (4:3), video 1280×720 (16:9), IR 640×512
  (5:4). No camera setting equalises them — `VideoResolution` has no 4:3 option. The flight screen
  centre-crops to fill (`armVideoFill`), holding the true centre on the reticle because the
  crosshair is the aiming reference for marker drops.
- **VIDEO is the resting mode, set AT CONNECT.** The camera remembers its mode across power
  cycles and the app previously only changed mode reacting to a button, so it came up wherever
  the camera was left. Setting it at connect is what makes "recording never moves the picture"
  actually true.
- **Upward gimbal tilt unlocked** via `setGimbalLimitUpward(true)`. Argument sense VERIFIED, not
  assumed: the SDK's internal parameter is `isOpen` and `GimbalManager2` sends
  `CMD_SET_PITCH_LIMIT_UPWARD` with data 1 for true.
- **SPI is suppressed at/above the horizon.** There is no ground intersection up there;
  `CameraSlantPoint` would fall back to a fixed 300m guess, and a fabricated look-point on the TAK
  picture is worse than none because the team cannot tell and will act on it. Marker drops are
  also blocked whenever the reticle is RED — gated on `CrosshairView.accuracyColorFor()`, the
  same call that tints the reticle, so the cue and the gate cannot disagree.
- **ISO read "UNKNOWN" above 100.** `CameraISO` holds only whole stops (100/200/400/…); auto
  exposure routinely picks 1/3-stop values (125, 160, 250, 320…) which the SDK maps to UNKNOWN.
  Fixed by reading the RAW int the SDK already parses
  (`CameraAllSettingsWithParser…getImageISO().getISO()`), which cannot go stale as firmware adds
  values. `ShutterSpeed` has the same lossy shape (56 members) but degrades to "—" rather than a
  wrong value.

### Video streaming — 2-second pixelated pulse

Visible only to STREAM VIEWERS, never on the controller, because the artifact is created by our
re-encode and exists only in the outgoing stream.

Cause: a full IDR every `I_FRAME_INTERVAL_S` (2s) under FORCED CBR with no bit headroom, so the
rate controller spikes the quantiser on each keyframe. The old profiles were tuned for constant
quality-per-pixel and left **STANDARD and HIGH identical** in bits/pixel — which is why "just use
HIGH" would not have helped. Bitrates roughly doubled (LOW 275k→475k, STANDARD 800k→1.6M, HIGH
1.8M→3.6M), all three now at the same bits/pixel.

Rejected by the operator, recorded so they are not re-proposed: **intra-refresh** (complexity plus
unknown mid-stream-join behaviour) and a **longer GOP** (join latency, slower loss recovery).
**VBR at the same average bitrate** remains untried and is the option to reach for if the pulse
persists — screen capture is mostly static, so it would give keyframes headroom for no extra
average bandwidth, at the cost of burstiness.

⚠ LOW is no longer the minimum-bandwidth floor it was designed as. If a genuinely marginal link
needs one, add a new profile BELOW LOW rather than pushing LOW back down.

## 🚩 RELEASE BLOCKER — Autel Explorer steals the aircraft link BY ITSELF (found 2026-08-01)

**Must be tested and addressed in a dedicated session before any full release.** Not a
TAKPilot bug; a platform behaviour we have to defend against.

### What happens

Autel Explorer is a **system-privileged preinstalled app** (`com.autelrobotics.explorer`, uid
`system`). It can start **without the pilot ever opening it**, and when it starts it takes the
aircraft USB link. Straight from the log:

```
20:58:23.503  Start proc 14706:com.autelrobotics.explorer/1000
              for service {…/com.google.android.gms.measurement.AppMeasurementJobService}
20:58:23.936  broadcast com.autel.maxifly.usb.attach   from explorer   (433ms after start)
20:59:50.555  broadcast com.autel.maxifly.usb.reset    from explorer
21:01:45.738  broadcast com.autel.maxifly.usb.reset    from explorer
21:03:45.479  broadcast com.autel.maxifly.usb.reset    from explorer
```

`AppMeasurementJobService` is **Google Firebase Analytics** — a batched telemetry upload. It has
nothing to do with flight. But Android's JobScheduler starts the app PROCESS to run the job,
which runs Explorer's `Application.onCreate`, which brings up its whole aircraft stack as a side
effect. An analytics upload drags the flight stack with it and it seizes USB.

There is at least a second waker: `com.mapbox.scheduler_flusher` fires from Explorer's package
**every 3 minutes** (20:46:21, 20:49:21, 20:52:21, 20:55:21, 20:58:23, 21:01:21, 21:04:21).

### What it looks like from TAKPilot

Repeated `productConnected` → `productDisconnected` churn, camera enumerating then dropping, and
every camera call failing with **"The execution of this process has timed out"**. Frozen HUD
values. The distinction that matters: **timeouts mean nothing answered** (contention); *errors*
mean the camera answered and refused (a real fault). On 2026-08-01 this was initially mistaken
for a regression in the new build — the camera had in fact enumerated as XT709 five times while
being fought over.

### Why it is a release blocker

This presents as **random, unreproducible mid-flight link loss with no pilot action to blame**,
on a schedule Explorer chooses. A pilot cannot prevent it by "not opening Explorer" — they never
opened it. `am force-stop` clears it only until the next scheduled wake.

### CONFIRMED KILLING A LIVE FLIGHT (2026-08-02)

No longer a log curiosity. Watched end to end while the operator was flying, with the aircraft
video dropping in front of them:

```
13:42:21.971  Start proc 19230:com.autelrobotics.explorer/1000
              for service {…/com.google.android.gms.measurement.AppMeasurementJobService}
13:42:25.764  camera changed: UNKNOWN (UnknownCamera)
13:42:25.770  AutelProductHolder: productDisconnected      <- 3.8s after Explorer started
13:43:33.489  AUTEL_USB: com.autel.maxifly.usb.reset       <- delivered to BOTH pids
```

**3.8 seconds from analytics job to lost aircraft.** The `usb.reset` broadcast reaching both our
pid (28190) and Explorer's (19230) is the two apps fighting over the same USB link. The pilot
never opened Explorer and had no way to know what happened — the symptom is simply that video
stops.

`am force-stop com.autelrobotics.explorer` recovers it immediately, and it stays fixed only
until the next scheduled wake.

### THE INTENDED FIX (operator, 2026-08-02 — deferred, not abandoned)

**Have TAKPilot suppress Explorer for as long as TAKPilot is running**: disable or block it at
launch, prevent it starting at all while we hold the aircraft, and restore it on exit. That is
the right shape — it makes the two apps mutually exclusive by design rather than relying on a
pilot to remember, and it survives whatever schedule Google's analytics job runs on.

Not yet designed. Things that will need answering when it is picked up:
- Explorer is a SYSTEM app (uid `system`), so `run-as` cannot touch it and an ordinary app
  cannot stop another app. `pm disable-user` needs shell/root, which an APK does not have.
  So this likely needs either a privileged helper, an ADB-time provisioning step performed once
  per controller, or device-owner/DPM APIs.
- **Restoring it matters as much as disabling it.** A pilot who can never open Autel's own app
  cannot do firmware updates, compass calibration or aircraft registration — all of which the
  field guide already tells them to do there. Whatever suppresses Explorer must reliably undo
  itself, including after a crash.
- The narrower `pm disable-user` of just
  `com.google.android.gms.measurement.AppMeasurementJobService` remains the cheap interim, and is
  reversible with `pm enable`. It does not cover other wake paths (a Mapbox flusher alarm fires
  from Explorer's package every 3 minutes).

### Candidate mitigations — TEST, none of these are validated

1. Disable just the waking component, narrower than disabling Explorer (a system app the
   controller may need for firmware updates):
   `pm disable-user --user 0 com.autelrobotics.explorer/com.google.android.gms.measurement.AppMeasurementJobService`
   Then **verify Explorer still works normally** — unknown whether anything else depends on it.
   Also find and handle the Mapbox flusher; disabling one waker is not enough.
2. Detect and surface it: TAKPilot could watch for `com.autel.maxifly.usb.attach`/`.reset` or
   poll for the Explorer process and TELL THE PILOT the link is being contended, rather than
   showing a mystery disconnect. Honest failure beats silent failure.
3. Establish whether the aircraft can be reacquired after an Explorer-induced reset without a
   full app restart.

### Also present, unexplained

`com.airdata.uav.app` had been running **4+ hours** on the same device. Not observed touching the
aircraft link, so not accused — but a second uninvited client on a device that tolerates one.
Worth checking in the same session.

**Open question:** whether Explorer's wake is purely scheduler-driven, or whether aircraft
connection also triggers it (a USB-attach intent filter would do it). Not determined.

## Session 2026-08-01 — what changed, and what is untested

**Uncommitted working tree** (standing constraint: do not commit without asking):

| file | change |
|---|---|
| `buildsystem/patch-autel-sdk-camera-id.py` | NEW — the XL726 aar patch + verifier |
| `app/libs/autel-sdk-release-xl726.aar` | NEW — generated; vendor aar left pristine |
| `app/build.gradle` | depends on the patched aar |
| `TestApplication.java` | `initAutelSdkLog()` — turns the SDK's own trace on |
| `AutelProductHolder.kt` | VIDEO mode at connect (guarded + retried); note against re-adding `setAspectRatio` |
| `FlightActivity.kt` | `startRecordVerified`, video fill/centre-crop, VIDEO resting mode, shutter locked during record |
| `AutelVideoStreamer.kt` | corrected the stale "screen capture is a known gap" comment |
| `TAKPILOT2_AUTEL_PORT_PLAN.md` | this write-up; JDK 17, controller serial, patched-aar warning |

**Verified on hardware:** camera enumerates as XT709; photo (file on card); recording (file on
card); IR + both palettes; exposure; zoom read (×100 units); video fills screen in all modes with
centre held; VIDEO resting mode means REC no longer moves the picture; shutter greys out while
recording.

**BLOCKING before full release:** see the Explorer-steals-the-link section immediately above.

**NOT verified — do these first next session:**

- **The aim calibration has never been used.** Fly it shallow (15–25°) and find out whether a
  single pitch/bearing offset closes the gap to the DJI Mini. Stage 2 depends on that answer.
- **The stream bitrate increase is untested.** Confirm `1600kbps` in the encoder's start log, then
  watch whether the 2s pulse goes. Unchanged ⇒ not the encoder, look at the RTSP path. Worse ⇒ it
  was keyframe burst/loss after all; revert.
- **Marker drops are now BLOCKED on a red reticle.** If that proves too aggressive in real use
  (especially the stricter no-DTED thresholds) the numbers are in `CrosshairView`.
- **IR toggled DURING a recording** — still untested; unknown whether the file splits or corrupts.
- **The ISO fix** — point at bright then dark and confirm the number tracks instead of UNKNOWN.
- **The failsafe-visibility gap.** Two low-battery events (one at ~14%, one at ~24% — so Autel's
  threshold is computed, not fixed) and the app had NO idea either was happening. It cannot tell
  the pilot or the team that the aircraft has taken control. On a situational-awareness screen
  that is a real omission.

- **The 800 ms `MODE_SWITCH_SETTLE_MS` on the record path.** Now that VIDEO is the resting mode,
  REC never switches modes, so that branch is effectively unreachable in normal use. The photo
  path's VIDEO restore does exercise the same constant.
- **The `startRecordVerified` retry/toast path.** Never triggered. Its whole point is that a
  silent failed record becomes visible; that has not been seen happen.
- **IR toggled DURING a recording.** The shutter is locked out but IR and zoom are not. IR
  changes the stream to 640×512 mid-record and it is unknown whether the camera splits the file,
  corrupts it, or handles it cleanly. Test deliberately.
- **Own-ship chevron rotation** — still unverified from earlier sessions.
- **Controller GPS** — `getLastKnownLocation()` reads a cache nothing fills; still needs a real
  `requestLocationUpdates()`.

## To resume

Open a new chat, point it at this file plus the project directory. Phases 0–1 (headless)
are complete — pick up at Phase 2 (UI parity rebuild), starting with the Home screen or
Flight screen per the operator's preference. Keep the Android 11 / Smart Controller V3
constraint (see above) in mind for anything touching storage, permissions, or foreground
services from here on. Nothing from this session is committed yet — the working tree has
Phase 0 + Phase 1 changes staged and building clean (`./gradlew assembleDebug`), awaiting
a go-ahead to commit per the standing constraint.
