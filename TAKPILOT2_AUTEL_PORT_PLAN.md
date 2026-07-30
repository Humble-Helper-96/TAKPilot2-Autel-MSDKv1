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

**Status: planning. No bring-forward work has started yet.** Pick up at Phase 0 below.

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

- [ ] Diff `com.taklite.client.tak` in the Autel tree against the current DJI
      (`SampleCode-device-compat`) copy. The DJI side has moved since this port was cut
      (e.g. the callsign-vs-username fix just landed there — commit `ed07d61`). Pull
      forward any fix that applies to shared, vendor-neutral code.
- [ ] Confirm `CotBuilder.buildPLI`'s emitted type code (`a-f-A-M-H-Q`) and `<contact
      callsign>` wiring match current DJI behavior (this is exactly the class of bug we
      just fixed on the DJI side — verify the Autel call sites pass the real callsign,
      not a username, from day one).
- [ ] Put this project tree under git (it currently has no `.git` at the
      `takpilot-autel_v1-2` root — see Environment note below). Establishes a real diff
      history for everything that follows.

## Phase 1 — Telemetry & feature parity (headless, no UI yet)

Bring `AutelTakBridge` and supporting logic up to what the DJI app's telemetry/CoT side
now does, using Autel's data sources. All of this should be substantially vendor-neutral
logic already proven on the DJI side — the work is wiring, not invention.

- [ ] **DTED terrain-corrected SPoI.** DJI's `TerrainAgl` + the DTED ray-march upgrade to
      `CameraSlantPoint` (mentioned as a deferred item in the old Autel handoff, since
      built and field-verified on DJI). Port the DTED lookup and splice it in — the Autel
      bridge already caches `heightMeanSeaLevel`, which is exactly what that ray-march
      needs.
- [ ] **FAA UASFM advisory ceilings on the HUD** — vendor-neutral gridded lookup, should
      port with no changes beyond wiring the HUD readout.
- [ ] **ADS-B air-track ingestion** (read-only TAK channel + METAR) — vendor-neutral,
      should port directly.
- [ ] **Marker management suite** (Phase 6 A–D on the DJI side: inbound contacts on the
      map, pin drops at crosshair, move/rename/retype/re-send/delete/clear-all/reset
      numbering, AR overlay). `TakMapMarkers`/`TakDropMarkers` already exist in the Autel
      tree re-implemented on osmdroid — bring them up to the current feature set rather
      than re-deriving it.
- [ ] **Loss-of-signal → RTH failsafe.** Check whether Autel MSDK v1.5 exposes an
      equivalent signal-loss / connection-failsafe hook to DJI's
      `setConnectionFailSafeBehavior`; if so wire the same pilot-facing behavior.
- [ ] Gimbal-pitch HUD readout + crosshair accuracy cue (green/amber/white by pitch,
      since ground-point error scales as `1/sin²(pitch)`) — vendor-neutral once the
      gimbal pitch value is in hand.
- [ ] Units standardization (imperial, `Units.kt` equivalent) and flight-timer /
      time-remaining accuracy, matching DJI.

## Phase 2 — UI parity rebuild

The bigger lift. Old Autel `FlightActivity` was hand-built from primitives against the
*old* DJI UI and needs to be rebuilt against the *current* one — not incrementally
patched.

- [ ] **Home screen** — mirror `TAKPilot2GoHomeActivity`: connection-state cards
      (aircraft / TAK), Pre-Flight Setup entry, Data Sync entry, Field Guide entry, Debug
      Log entry, "Enter Flight" affordance. Reuse DJI's layout XML as the template,
      swapping only data bindings.
- [ ] **Flight screen** — mirror `TAKPilot2GoFlightActivity`: same toolbar layout
      (hamburger | RTH | TAK shield+dot | battery ring | GPS | RC signal ‖ Video Re-Sync
      | LIVE | REC), same status strip, same telemetry strip (altitude/speed/heading/
      battery/satellites), same **locked mini-map** treatment (no pan/zoom/rotate,
      north-up, fixed zoom, red home→aircraft line) rather than the old freely
      interactive osmdroid map, same expand/collapse map behavior, same drop-pin /
      pin-at-look-point buttons.
- [ ] **Pre-Flight Setup** — mirror DJI's version: max altitude/distance/RTH-altitude
      defaults, map style choice, callsign, DTED import (§5 on the DJI side — ATAK-style
      zip import + per-tile delete), channel selection, video destination with live URL
      preview.
- [ ] **Field Guide screen** — port `FieldGuideActivity` content and structure (three
      sections with live icon views) — this is drone-agnostic reference material, should
      transfer near verbatim.
- [ ] **Debug Log screen** — the DJI app's `AppLog` facade + file sink + crash handler +
      viewer/export/clear screen. (Note: the old Autel handoff had an equivalent feature
      *designed but not built* as its next task when that session ended — that design
      doc's requirements, §9 of `TAKPilot2-Autel-HANDOFF.md`, are still valid and can be
      used directly, but implement it as a port of DJI's now-built version instead of
      building fresh from that spec.)
- [ ] **Data Sync screen** — already ported (package/import swap only per `PORT-STATUS.md`);
      verify it still matches DJI's current version, since Data Sync/Mission API is
      vendor-neutral.
- [ ] Sweep for the same class of layout bugs the old port hit and fixed once already
      (missing `ScrollView` clipping overflow silently, dead touch targets on cards that
      look clickable but aren't) — screenshot-driven review, as noted in the handoff.

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
  **Not currently under git** — Phase 0 includes putting it under version control.
- Toolchain: Gradle 7.3.3 / AGP 7.2.2 / Kotlin 1.7.20, `compileSdk` 33, `minSdk` 21.
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

Open a new chat, point it at this file plus the project directory. Say which phase
you're picking up — Phase 0 (audit/git-init) is the recommended starting point since
everything downstream assumes the taklite layer and CotBuilder are confirmed current.
