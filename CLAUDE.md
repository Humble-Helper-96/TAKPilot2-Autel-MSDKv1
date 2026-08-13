# TAKPilot2-Autel — rules for every coding session

**Written in Simplified Technical English (ASD-STE100).** This file goes to the agent on
every invocation. It holds the decisions and the safety rules that the code cannot show by
itself. The full reference is `TAKPILOT2_AUTEL_PORT_PLAN.md`; the current state is
`PORT-STATUS.md`.

## What this application is

The TAK flight interface for the Autel EVO II 640T V3 on the Smart Controller V3
(1024x720dp). It replaces Autel Explorer during TAK operations on a six-controller
public-safety fleet. The DJI sibling application is the UI template: a pilot must be able to
change aircraft and find the same screens.

## Safety rules — these come from real incidents

1. **In this SDK, a getter is not always one call.** Some `get*(callback)` calls are 2 Hz
   subscriptions that never stop. Confirm each call in the aar bytecode before you use it.
   One wrong assumption flooded a safety channel and put an aircraft into a wall.
2. **Listener slots hold ONE client.** A second `set*Listener` replaces the first with no
   warning, and `setInfoDataListener(null)` kills the channel for the process. Only
   `AutelTakBridge` and `AutelAvoidance` own SDK listeners. New consumers are FED from the
   bridge callback — see `FlightPathLogger.onTelemetry` for the pattern.
3. **Never write to the fly-controller channel on a timer.** Limits go to the aircraft at
   connect and on an explicit button press only. Keystroke-burst writes crashed an aircraft
   on 2026-08-02.
4. **Do not trust `onSuccess` from the camera alone.** Verify with a read-back where the
   result matters.
5. **Sign conventions:** Autel local altitude is NED (down-positive). Correct a sign ONE
   time, at ingest, never in consumers. When one value has the wrong sign, examine the
   others immediately.
6. **`com.taklite.client.tak` must not import an SDK.** It is vendor-neutral by contract.
7. **Make no permanent change to the controller.** No device-owner, no system settings.
   AirData stays installed. The Explorer watchdog kills processes; it changes nothing.
8. **Test the hardware before you design around its limits.** Three wrong "the SDK cannot
   do this" calls came from auditing one subsystem. Sweep the full aar surface.
9. **`applicationId` is `com.tak.uastoollite` and must not change** — the DJI key and the
   aircraft registration are bound to it.

## Verification

- Unit tests: `./gradlew :app:testDebugUnitTest` — pure-logic core (warnings policy,
  flight-record formats, CoT XML, conversions). Run them before each commit that touches
  those files. Add a test when you change the policy they pin.
- A release needs the bench: `FLIGHT-TEST-CHECKLIST.md`, then the signed APK goes to
  `../../signedReleases/` with STE release notes, a git tag, and a GitHub release.
- The build: `./gradlew :app:assembleRelease` (signing is in the repo — AOSP platform key).

## Conventions

- Documents are STE with the marker line at the top. New code comments are STE. Old
  comments become STE when a file is next touched for real work.
- UI state must show what the AIRCRAFT holds, not what was requested. Unknown is its own
  state (amber), never collapsed into off.
- Release notes are short and simple, one line per function, next to the APK.
- Colours: prefer the tokens in `takpilot_colors.xml` over `Color.parseColor` in new code.

## Current work

v1.5.9 is on the fleet (tag `v1.5.9`). v1.6.0 is open on master, at versionName `1.6.0` /
versionCode 16; it waits for flight-test feedback from the test users. The v1.6.0 finding
list is in `REVIEW_2026-08-07_AUDIT.md` section 4.

In v1.6.0 so far: the CoT video advertisement now carries a nested `ConnectionEntry`, which
is what makes the feed playable from the aircraft marker and the pilot marker. This was
flight-verified on 2026-08-12 — the operator confirmed video on both markers. The bare
`<__video sensor url/>` shape it replaced put the url on the wire and gave no client a play
control. `com.taklite` is shared by contract, so this code is the same in the DJI tree.

⚠ The advertised url still carries the video credentials (`user:pass@`) to every client on
the channel. Open decision in both trees, and now in front of test users.
