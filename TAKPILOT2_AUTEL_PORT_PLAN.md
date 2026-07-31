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

## Environment / Tooling

- Project root: `/home/echos6/SynologyDrive/TAKServer/UAS_Apps/Autel/AutelTAKPilot2/takpilot-autel_v1-2/`.
  Under git — commit `c175d40`, tag `autel-baseline-v1.2`.
- **Target hardware runs Android 11 (API 30).** The Smart Controller V3 is the real
  deployment OS — design/test assumptions should track API 30 behavior (scoped storage,
  permission auto-revoke, foreground-service start restrictions), not a newer dev device's
  OS. See the Android 11 callout near the top of this doc.
- Toolchain: Gradle 7.3.3 / AGP 7.2.2 / Kotlin 1.7.20, `compileSdk` 33, `minSdk` 21,
  `targetSdk` 29.
  **Use JDK 11** — AGP 7.2.2 misbehaves on JDK 17/21 (same lesson learned on the DJI
  MSDKv4 side with its own JDK/Gradle combination).
- `chmod +x gradlew` after any unzip/re-sync — the execute bit is stripped by zip
  extraction and by Synology Drive sync; this has bitten the project twice already.
- Autel SDK is a bundled AAR in-repo (`app/libs/autel-sdk-release.aar`), not a remote
  artifact. App ID `com.tak.uastoollite`; App Key already wired into
  `TestApplication.java`.
- Build: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
  Install: `adb install -r <apk>`.
- Reference material to keep on hand: `TAKPilot2-source-V1.zip` (the original DJI
  TAKPilot2 source this v1.2 port was built against — useful for archaeology, but prefer
  the *current* `SampleCode-device-compat` tree as the parity target for anything UI or
  feature related, since that's what pilots will actually compare against).
- Standing constraint carried over from the DJI project: **do not commit without asking
  first.**

## To resume

Open a new chat, point it at this file plus the project directory. Phases 0–1 (headless)
are complete — pick up at Phase 2 (UI parity rebuild), starting with the Home screen or
Flight screen per the operator's preference. Keep the Android 11 / Smart Controller V3
constraint (see above) in mind for anything touching storage, permissions, or foreground
services from here on. Nothing from this session is committed yet — the working tree has
Phase 0 + Phase 1 changes staged and building clean (`./gradlew assembleDebug`), awaiting
a go-ahead to commit per the standing constraint.
