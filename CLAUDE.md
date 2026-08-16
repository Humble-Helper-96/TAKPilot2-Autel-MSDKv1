# TAKPilot2-Autel — rules for every coding session

**Written in Simplified Technical English (ASD-STE100).** This file goes to the agent on
every invocation. It holds the decisions and the safety rules that the code cannot show by
itself. The full reference is `TAKPILOT2_AUTEL_PORT_PLAN.md`; the current state is
`PORT-STATUS.md`.

## What this application is

The TAK flight interface for the Autel EVO II 640T V3 on the Smart Controller V3
(1024x720dp). It replaces Autel Explorer during TAK operations on a six-controller
public-safety fleet. It is one of three TAKPilot2 applications, with the DJI MSDKv4 and DJI
MSDKv5 siblings:

> A pilot changes airframe and finds the same screens, the same controls in the same places,
> and the same words.

## The UI specification

`../../../TAKPILOT2-UI-SPEC.md` is the single source of truth for the user interface of all
three applications. It outranks any UI note in this file or in the documents in this tree.
Read it before you change a screen, a layout, a colour or a readout format. This tree's
gap list is in `../../../TAKPILOT2-UI-CONFORMANCE.md`.

A UI change lands in all three applications, or it lands in none.

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

- Documents are STE with the marker line at the top — but NOT the release notes, which carry
  no marker line (operator, 2026-08-16). They are still written in STE; the pilot reading them
  does not need to be told which controlled language they are in. New code comments are STE. Old
  comments become STE when a file is next touched for real work.
- UI state must show what the AIRCRAFT holds, not what was requested. Unknown is its own
  state (amber), never collapsed into off.
- Release notes are short and simple, ONE LINE PER FUNCTION, next to the APK. Keep them that
  way: the v1.6.0 and v1.6.1 notes both had to be cut back after they grew into prose that
  stated a fact, then restated it as its own consequence (operator, 2026-08-16). A change gets
  a line or a bullet, not a paragraph and a follow-up paragraph.
- Colours come from the tokens in `res/values/takpilot_colors.xml`. Do not add a
  `Color.parseColor` call site — specification §6.1. `res/values/colors.xml` belongs to the
  vendor sample; leave it alone, and see the recorded exception in §6.1 before you change
  the three chrome colours in it.

## Current work

v1.5.9 is on the fleet (tag `v1.5.9`). v1.6.0 is open on master and waits for flight-test
feedback from the test users. The v1.6.0 finding list is in `REVIEW_2026-08-07_AUDIT.md`
section 4.

**The version numbers live in `app/build.gradle`. Read them there.** This paragraph carried a
`versionCode` that was 13 builds out of date.

In v1.6.0 so far: the CoT video advertisement now carries a nested `ConnectionEntry`, which
is what makes the feed playable from the aircraft marker and the pilot marker. This was
flight-verified on 2026-08-12 — the operator confirmed video on both markers. The bare
`<__video sensor url/>` shape it replaced put the url on the wire and gave no client a play
control. `com.taklite` is shared by contract, so this code is the same in the DJI tree.

The advertised url carries the video credentials (`user:pass@`). This is settled, not an open
item: the `ConnectionEntry` shape has no separate credential field, so a url without them does
not authenticate and the feed does not play. Do not raise it again and do not propose stripping
them.

Also in v1.6.0, from the 2026-08-14 documentation audit: the marker retention time that the
app tells the pilot is corrected from "about 14 hours" to 3 days, in the Delete Marker dialog,
the Clear All Markers dialog and the Field Guide. Only the text was wrong — the markers always
expired at the correct time, which is `CotBuilder.MARKER_STALE_DURATION_MS`. **This is
pilot-facing text that no test pilot has seen yet**, so it goes in the notes for the next
development build.

Also in v1.6.0: a refused marker now shows on the flight screen as an amber transient notice
instead of a Toast. A Toast is not in the screen capture, so the team saw nothing while the
pilot was told the marker was refused. `showNotice` takes a `refused` flag and owns the
colour; do not set the notice colour at a call site. Both DJI siblings took the same change on
the same day — specification §4.8.

Both of those changes shipped in v1.6.0. **v1.6.0 is now RELEASED** — tag `v1.6.0`,
versionCode 30, on the fleet from 2026-08-15. The earlier instruction here not to bump the
version applied only while v1.6.0 was open; it is spent. New work takes a new version.

v1.6.1 is RELEASED. v1.6.2 is open: versionCode 33, versionName 1.6.2, on branch
`channels-feature`. v1.6.1 carried the Field Guide rewrite AND the removal of channel
selection.

**CHANNEL SELECTION IS GONE, and this is the important part of v1.6.1.** TAK Setup let a pilot
pick channels, and `TakManager` then put `<marti><dest group="…"/></marti>` on every CoT that
went through `sendCot`. THE SERVER DROPPED THOSE EVENTS. Markers never left the server;
deselect every channel and they arrive at once. Proved on the fleet controller 2026-08-15 with
one marker sent each way. It would have done the same to an alert, but **nothing in this app
calls `sendAlert`** — that method and its listener are unreachable code in the shared core, so
no alert was ever lost. An earlier version of this note and of the v1.6.1 release notes said
alerts were being dropped; that was inferred from the code path without checking it had a
caller, and it was wrong. The feature also never applied to the drone PLI or the
camera point, which call `sendMessage` directly — so a pilot who picked channels to LIMIT who
saw the aircraft still broadcast its position to everyone. It failed in both directions, and it
had done so since the v1.2 baseline. **That open question is now answered — see v1.6.2 below.**
`<dest group>` is not the mechanism a TAK Server accepts, and it must never come back.

**v1.6.2 BRINGS CHANNELS BACK, by the method a real TAK client uses.** The pilot picks channels
on TAK Setup, or from the flight screen with a touch-and-hold on the TAK badge. The application
holds NO channel state: it reads `GET /Marti/api/groups/all?useCache=true&sendLatestSA=true` and
writes `PUT /Marti/api/groups/activebits`. The server then applies the scope to EVERYTHING that
certificate sends — the markers, the pilot position, the aircraft position and the camera point.
The old feature never touched the aircraft position, thus this is the first version where a
pilot who limits the channels really limits who sees the aircraft. That was the operator's
requirement of 2026-08-16.

Rules that came from the tests, and that must not be lost:

1. **No `<dest group>` on any message, ever.** That attribute is the v1.6.0 fault. One
   receive-only channel in the list made the server refuse the WHOLE message, silently.
2. **`activebits` is ABSOLUTE.** Send the complete active set every time, never a change. An
   empty list switches every channel off.
3. **Never write to a server that returned no channels.** Cory Foy (TAK Aware) reported that a
   channel change sent to a server without channels can do real damage server side.
   `pushActiveChannels` refuses an empty list for this reason. Do not remove that guard.
4. **The server pushes `t-x-g-c` when the channels change.** Both screens listen and re-read.
   The event is a NOTICE and carries no list. Do not replace this with a timer.
5. **Locked is not disabled.** The lock stops a CHANGE, never the reading. The rows keep full
   contrast and stop taking touches. A pilot must always be able to see the scope of the
   aircraft (operator, 2026-08-16).
6. ⚠ **The active channels belong to the CERTIFICATE.** Two controllers enrolled as one user
   share one set. An aircraft that needs its own scope needs its own certificate.

The evidence is in `CHANNELS-FINDINGS.md`; `CHANNELS-FOR-OTHER-DEVS.md` is what went to Rick
(TAKPilot) and Cory (TAK Aware).

⚠ **BOTH DJI TREES STILL HAVE IT** — `withChannelDest` and `setChannels` are in each. Any pilot
on those airframes who selected a channel is silently losing markers. Check whether those trees
wire `sendAlert` before repeating the alert claim; this one does not.

v1.6.1 also makes the outbound CoT path visible: `sendCot` logs the exact bytes (verbose, so
Detailed must be on), `TakClient` reports the write failures a `PrintWriter` silently swallows,
and "Marker sent" is now "Marker queued for send" because it was written whether or not anything
went out. That log is what found the channel bug. The outbound log is redacted — the pilot PLI
carries the video password — and `OutboundLogRedactionTest` is a security control, not a
formatting test.

The Field Guide part: the guide lost 63% of its words, gained a controller-
button section (C1 / C2 / zoom rocker), and renamed "Unknown marker" to "Static marker",
which is a pilot-visible rename of a control the test pilots have already learned. It also
corrected two places where the guide disagreed with the app: a Pre-Flight "If the signal is
lost" control that was removed on 2026-08-13, and four settings the screen has but the guide
never listed.

The markdown and ODT handouts beside this file are GENERATED from `FieldGuideActivity.kt` by
`tools/generate_field_guide_md.py`. The Kotlin is the source of truth; regenerate after every
guide edit rather than editing the handouts and hoping they agree.
