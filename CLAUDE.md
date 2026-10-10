# TAKPilot2-Autel — rules for every coding session

**Written in Simplified Technical English (ASD-STE100).** This file goes to the agent on
every invocation. It holds the decisions and the safety rules that the code cannot show by
itself. The full reference is `TAKPILOT2_AUTEL_PORT_PLAN.md`; the current state is
`PORT-STATUS.md`. **`README.md` is CURRENT as of v2.3.2 (2026-09-16)**: it was rewritten that
day against this file, the gradle version record and the tree. It is the public face of the
repository, thus a release that changes what the application does, or the list of documents,
owes it a line.

## What this application is

The TAK flight interface for the Autel EVO II 640T V3 on the Smart Controller V3
(1024x720dp). It replaces Autel Explorer during TAK operations on a six-controller
public-safety fleet. It is one of three TAKPilot2 applications, with the DJI MSDKv4 and DJI
MSDKv5 siblings:

> A pilot changes airframe and finds the same screens, the same controls in the same places,
> and the same words.

## Terrain data

`../../../TERRAIN-FILE-SPEC.md` — beside the UI specification, because it is shared by ALL
THREE trees and that was verified rather than assumed (every format-bearing constant in
`DtedTile`/`DtedStore` is identical in the three copies). It specifies what the DTED parser
accepts, written
from `DtedTile.open` and `DtedStore.import` rather than from MIL-PRF-89020B — the two differ,
and the document is what the code does. Read it before sourcing or building elevation data.
⚠ The two traps it exists for: posts are **sign-magnitude**, not two's complement, and the
vertical datum is **MSL (EGM96)**, so an ellipsoid-referenced source is about 12 m high in
Anchorage and nothing in the application will say so.

## The UI specification

`../../../TAKPILOT2-UI-SPEC.md` is the single source of truth for the user interface of all
three applications. It outranks any UI note in this file or in the documents in this tree.
Read it before you change a screen, a layout, a colour or a readout format. This tree's
gap list is in `../../../TAKPILOT2-UI-CONFORMANCE.md`.

A UI change lands in BOTH LIVE applications — Autel and MSDKv5 — or it lands in neither.
MSDKv4 is FROZEN (operator, 2026-10-09) and is owed nothing. One tree may LEAD, which is
how a change gets tested on hardware first; what is not allowed is a change that lands in
one tree and is not WRITTEN DOWN as owed by the other. See specification §8 rule 1.

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

## Releases — a hard rule

⚠ **NO BINARIES ON GITHUB. SOURCE ONLY.** No APK is ever attached to a GitHub release, a
commit or an issue (operator, 2026-09-14). A GitHub release is the TAG and the STE notes,
and nothing else. The signed APK goes to `../../signedReleases/Autel-MSDKv1/` beside its
notes file, which is where the fleet takes it from.

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
  ⚠ **THE v2.1.9 RELEASE IS THE MODEL — the operator rewrote mine to make the point
  (2026-09-14).** Read it before writing notes. What was CUT is the lesson: every ⚠ paragraph,
  every "before, it did X" comparison, the reason behind a change, the test status, the
  internal behaviour, and a whole section covering things the pilot cannot see. What SURVIVED
  is one line for each PILOT-VISIBLE change, stated once. The notes say what the application
  now does. They do not argue for it, justify it, or say what it used to do.
- Colours come from the tokens in `res/values/takpilot_colors.xml`. Do not add a
  `Color.parseColor` call site — specification §6.1. `res/values/colors.xml` belongs to the
  vendor sample; leave it alone, and see the recorded exception in §6.1 before you change
  the three chrome colours in it.

## Current work

⚠ **DO NOT READ THIS CONTROLLER'S LOAD AVERAGE AS CPU PRESSURE** (measured 2026-10-09). It sits
near 7-8 on an 8-core device and looks saturated. It is not: measured from `/proc/stat` while
streaming, the device was **28.8 % busy and 71.2 % idle**, with only 3 runnable processes. Linux
load counts UNINTERRUPTIBLE-SLEEP tasks too, and the Autel SDK keeps 61 `SDK 2.0 udp-receive`
threads queued on ONE `DatagramSocket` monitor — blocked threads inflate the load and use no CPU.
The whole application draws about 0.9 of ONE core while streaming: RenderThread 24.9 %, main
15.4 %, the SRT coroutines 12.9 %, and `ScreenCaptureEncoder`'s drain loop just 1.3 %. ⚠ There is
no CPU problem to fix here, and the 2026-10-09 freeze was a fence deadlock, not starvation — the
encoder was the CHEAPEST thread in the process when it stalled.

⚠ **A STALLED ENCODER CAN KILL THE CONTROLLER, AND ONLY A REBOOT RECOVERS IT** (2026-10-09).
`SURFACEFLINGER-FREEZE-2026-10-09.md` has the stacks. In one line: the encoder stopped releasing
buffers, the release fence on our MediaProjection VirtualDisplay never signalled, and
SurfaceFlinger's main thread sat in `Fence::waitForever` — a wait with NO TIMEOUT — so the whole
display died. ⚠ **Force-stop does not recover it. Killing and respawning SurfaceFlinger does not
recover it. Reboot the controller; do not spend time on the soft options.**

`ScreenCaptureStallPolicy` + the watchdog in `ScreenCaptureEncoder.drainLoop` are the defence:
the capture is torn down when the encoder produces NOTHING for 10 s. ⚠ **The test is "no output
at all" and never "below profile"** — the 49-minute reproduction run dipped to 10.9-11.8 fps six
times and every dip recovered, so a watchdog on a slow encoder would have killed a working
stream six times. ⚠ **It is not a cure**: the encoder stalls first and the compositor wedges
seconds later, and if the fence is already dead the teardown may block too. It converts the
survivable case only.

⚠ **THE FREEZE IS INTERMITTENT, WAS NOT REPRODUCED IN 49 MINUTES, AND ITS TRIGGER IS UNKNOWN.**
Do not attribute it to the SRT latency: it happened AT 1000 ms, and 500 ms is NOT a known-good
baseline — nothing has tested it against this failure.

⚠ **PICK UP HERE: THE AR OVERLAY IS NOT ACCURATE ENOUGH** (operator, 2026-10-09, closing the
session: "I am still not happy with AR accuracy but I dont know how to fix it"). Nothing else is
outstanding — v2.4.0 is released and on the controller. This is the next piece of work.

**Start by MEASURING, because nothing can be aimed at an opinion.** ⚠ And note that offsets are
ALREADY DIALLED IN — the CoT of 2026-10-09 carried `aim=[pitch-1.25 brg+4.00]`, so the error you
see is already being compensated by 1.25 deg of pitch and 4 deg of bearing. **Record the offsets
in force with any measurement, or zero both first**, or the number measures the compensation
rather than the error. The instrument already
exists and needs no new code: `AutelTakBridge.pushCameraPoint` logs the solved ground point to 7
decimals for exactly this purpose, and its own comment gives the method — aim the crosshair at a
feature whose true coordinates are known, and the offset between the logged point and those
coordinates IS the aim error, in metres on the ground. ⚠ **DECOMPOSE IT**: cross-track offset is
a BEARING error, along-track is a PITCH error. The two have different causes and different
fixes, and an undecomposed "it is off by 12 m" cannot be acted on.

The three candidates, from the 2026-09-14 AR audit, in the order to attack them:

1. ⚠ **THE GIMBAL ROLL IS ALMOST CERTAINLY A NON-ISSUE. DO NOT START HERE** (operator,
   2026-10-09: "I can't control its roll. I can only control pitch"). An earlier version of this
   list called the roll sign "the cheapest, one bench session" — **that advice was wrong and the
   session cannot be run**, because roll is not a pilot input on this airframe.
   Measured instead, from the 49-minute log of 2026-10-09: the AR geometry trace recorded
   `roll=0.0` on **8421 of 8421 samples**, one distinct value, while `camPitch` moved across 21
   — so the trace reads a real field and roll simply never moves. `liveGimbalRoll` stays RAW and
   out of `CameraPose`, which now costs nothing.
   ⚠ **NOT PROVABLE FROM THE BENCH, which is why the item is not deleted.** That run was
   stationary on the ground, and a three-axis gimbal holding level on a motionless aircraft
   reads zero whether or not it lags in a banking turn. **To close it, grep a FLIGHT log for a
   non-zero `roll=` in the `geom:` trace.** If there is none, delete this item; do not schedule
   a bench session for it.
2. **TELEMETRY LEADS VIDEO.** The overlay projects from telemetry that is current while the
   video under it is some milliseconds old. ⚠ Needs the lag MEASURED on this hardware before any
   compensation is written. Same mechanism as the ADS-B dead-reckoning measured at 3.3° on
   2026-09-15 and deliberately stored.
3. **MAGNETOMETER BIAS BY HEADING — NO APP CORRECTION.** The operator's standing decision. It is
   listed only so it is not mistaken for unexplored. Do not propose a per-airframe table again.

⚠ **THE DTED CACHE OF 2026-10-09 CHANGES NO ANSWER AND IS NOT A SUSPECT.** It caches the POSTS
and interpolates on the caller's exact lat/lon; quantising the QUERY instead is the thing that
WOULD move a marker, and `DtedTileCacheTest` fails on that. Do not look there first.

---

**v2.4.0 IS RELEASED** — tag `v2.4.0`, versionCode 110 (re-cut the same evening to carry the
1000 ms SRT latency default; vc108 was the first cut and 109 a withdrawn 2.4.1), 2026-10-09, signed APK and notes in
`../../../signedReleases/Autel-MSDKv1/`, GitHub release without the APK. Developed on the
`uasvideo-split` branch and merged to master on release (2026-10-05, refined 2026-10-08 and
2026-10-09; it was numbered 2.3.9 until master released that number for Random Path).
⚠ **BENCH TESTED AND ONE LOCAL FLIGHT on 2026-10-09.** The menus, the split across the two
accounts and the live streaming were confirmed by the operator. The reconnect-on-resume and both
retraction paths were proved from the log. ⚠ The AR accuracy is NOT to the operator's
satisfaction — see the open
items in the 2026-09-14 AR audit, of which the gimbal roll sign is the cheapest. The video split, plus Emergency Broadcast. **Two shared TAK accounts for the
fleet, Standard and Elevated**; every controller enrolls on both. The Elevated connection (cert
B in `TakManager`) carries the live-video link to the one channel that account is in; the
Standard connection carries the aircraft's position, FOV, SPI and markers to everyone else with
no video. `TakManager.videoFor` is the one place that decides it — `VideoSplitPolicyTest`.
"Elevated Account" section in `TakServerActivity` (its own enroll/connect, a WRITABLE channel
list since 2026-10-09, and a red line if both accounts share a channel — the one server mistake
the screen can see); `TakAutoConnect` reconnects it silently like the Standard one. ⚠ That section
was on `TakConnectActivity` until the configuration moved to its own screen — see the 2026-10-09
entry at the foot of this file.

**Emergency Broadcast** starts from the LIVE long-press menu; every channel gets the video link
for 15 minutes. The notice at the top of the flight screen carries the timer and IS the
control: tap renews, touch-and-hold stops. No pill in the actions column (six pills, two widths
is a rule). Start, renew, stop and expiry go in the flight's events file beside its CSV and GPX
(`FlightPathLogger.event`) — no "who", that is recorded outside the application. It resets to
OFF on an app-level reconnect or restart; a socket blip inside `TakClient` does not end it.
When it ends, the link simply goes back to the Elevated connection only — **the stream path is
left alone** (operator, 2026-10-08: a token rotation at the end was built and then removed as
more than the feature needs). A client that saved the link during a broadcast keeps it; with
Random Path on it dies at the next relaunch, with it off it lives on. Accepted. See
`CHANNELS-FINDINGS.md` §12.

⚠ **THE PILOT MARKER NO LONGER CARRIES THE VIDEO URL** (operator, bench 2026-10-08). It had
since 2026-08-05, so a downed or GPS-less aircraft still left a marker saying where the stream
was. TAK Aware draws a team member whose report carries a video entry as a MIL-STD-2525 unit
symbol — a cyan square — instead of the team-member dot, for as long as the stream is up.
Proved with nothing else changed: TAK Aware logs 11 (url present, square) and 12 (url absent,
dot). The dot won. `AutelTakBridge.sendPilotPli` passes null; the shared core keeps the
parameter and **both DJI trees still pass their url and owe this decision** when they port the
split. The aircraft marker alone advertises the stream.

Also learned on the same bench, for the record: a TAK client keeps the play control on a marker
after the stream stops (a later report with no video entry does not remove a saved video
entry) — a client behaviour, raised with the TAK Aware developer rather than worked around; the
Standard account's in-flight TAK Channels dialog shows the Standard account only, by design;
and the controller's clock must be on network time (`FLIGHT-TEST-CHECKLIST.md` §2).
**NOT FLOWN. Phase 0 on a TEST server first** (the channel layout is in `CHANNELS-FINDINGS.md`
§12; that the server keeps two connections from one controller with the same aircraft uid is
ASSUMED to work — operator, 2026-10-08 — with a `-V` uid fallback noted). The DJI v5 port follows
once Autel is confirmed working, from these specs, in its own session.

⚠ **FIXED as a side effect of this work**: `sendDronePLI` and `sendCameraPoint` had called
`client.sendMessage` directly since the v1.2 baseline, bypassing the logged/redacted send path
documented in the channel-selection removal note in `TakManager.java`. Both now route through
`sendCotToBoth` like every other outbound message in that class.

**v2.3.9 IS RELEASED** — tag `v2.3.9`, versionCode 104, 2026-10-07, signed APK and notes in
`../../../signedReleases/Autel-MSDKv1/`, GitHub release without the APK. Installed on the
operator's controller over adb. **BENCH TESTED by the operator on 2026-10-07, after the release
went out**: live stream to the server with the toggle on; a LIVE stop and restart kept the token;
a close and reopen of the application rotated it (`2eadb0f8` to `602b0736`) while the CoT video
uid stayed `0655ed70-…` — one alias on the client, as designed. Read off the wire in logcat, 44 and
34 PLIs with one token each. **NOT FLOWN.** The SRT reconnect and the rotate are not separately
checked; both are the same process and the same `lazy`, so neither has a path to a new token.
RANDOMIZE STREAM PATH: a toggle on each Video Servers card, OFF
by default. ON puts a random token in the stream path, between the broadcast id and the `-Low`
suffix: `ANC-EVO2-B2-7f3a9c2d-Low`. The server keys on the `-Low` ending and the agency prefix,
thus the token goes between them and not at either end.
⚠ **ONE TOKEN PER PROCESS, IN MEMORY ONLY.** `StreamPath.sessionToken` is eight lowercase hex
characters from `SecureRandom`, made on first use and never written to a preference. It does not
change on a stream stop and start, an SRT reconnect, a landing or an activity recreate. It
changes only when the application is relaunched. A token that changed mid-session would leave
the team holding a CoT that names a feed which no longer exists.
⚠ **THE PATH IS COMPOSED IN ONE PLACE,** `StreamPath.compose`, reached only through
`VideoConfig.streamPath()`. The SRT stream id, the RTSP push, the CoT url (and so the
`ConnectionEntry` path the shared core parses out of it), the masked preview and the two
pre-flight readouts all take it from there. Do not build a path a second time.
⚠ **THE CoT VIDEO UID DOES NOT FOLLOW THE TOKEN.** `CotBuilder.videoUidFor` strips the
`-<8 hex>-Low` segment before it hashes the url, so ATAK keeps one alias per aircraft and not
one per flight, and the uid is identical with the toggle off. This is a SHARED-CORE change: the
taklite-core master moved first. **Both DJI trees owe the `CotBuilder` sync** (they also still
owe the 14 and 16 September moves — `check-taklite.sh` lists them).
⚠ The `-Low` suffix and the `[0-9a-f]{8}` token shape are now pinned in two places, the composer
and the uid rule. A change to one is a change to both.

**v2.3.8 IS RELEASED** — tag `v2.3.8`, versionCode 103, 2026-09-16, signed APK and notes in
`../../../signedReleases/Autel-MSDKv1/`, GitHub release without the APK. PIP is `base=None` at
75 % thermal by default, and **THE PILOT SETS THE BLEND WITH A SLIDER**. v2.3.5 (100) measured
the base, v2.3.6 (101) set it and v2.3.7 (102) took the ratio to 75 %; none of the three
shipped. Bench tested on the controller by the operator — the outlines, the default and the
slider. **NOT FLOWN.**

**The slider** is vertical, parallel to the actions column's right edge, the column's own
height, and **SHOWN ONLY IN PIP** — in visible there is no thermal layer and in full thermal
nothing to mix it with, thus the control would move and change nothing. It is `EvSliderView`
turned on its side: same track, ticks, thumb and black double-pass outline, so the HUD keeps one
weight of edge (specification §4.3). UP IS MORE THERMAL, matching Explorer's percentage; 20
steps of 5 %.
⚠ **THE WRITE IS THROTTLED ON THE DRAG (250 ms) AND COMMITTED ON THE LIFT.** A stroke crosses
twenty steps, and twenty writes in half a second is the shape of the 2026-08-02 keystroke burst
— on the camera's channel rather than the fly-controller's, but the same shape. **Only the lift
is STORED**: a value the thumb passed over is not a decision. The stored value is what the next
connect writes, thus the pilot's choice survives a session and the camera still gets a known
value.
⚠ **THE SLIDER IS LEFT CHROME WHILE IT IS SHOWN.** The AR overlay and the obstacle view take
their left inset from ITS right edge, or an edge arrow and a proximity label are drawn on the
thumb. Its x and its height come from the MEASURED column in the same layout pass — never a dp
in the layout file, which would be a second copy of a width that changes with the pills.
⚠ **THE RATIO ARITHMETIC IS EXPLORER'S** and is pinned in `AutelBlendFormatTest`: IR = pct/100 ×
65536, Visible = 65535 − IR, the pair always summing to 65535. v2.3.7's hand-computed 75 % pair
was `49151/16384`, one count low; the formula gives `49152/16383`.

⚠ **AUTEL LEADS AND THE DJI TREES FOLLOW ONLY IF THE OPERATOR WANTS IT** (2026-09-16). The
slider is a flight-screen control that deliberately does NOT land in all three applications. The
rule is SUSPENDED for it by the operator's decision, as it was for the flight-screen refresh on
2026-09-12 — read it as a decision and not as drift. ⚠ Specification §4 still owes the slider a
line.

⚠ **THE APPLICATION HAD BEEN TURNING THE EDGE OUTLINES ON AT EVERY CONNECT SINCE v2.3.0, AND
NOTHING COULD SEE IT.** `AutelBlendFormat.applyAtConnect` wrote first and read back afterwards,
thus its read could only ever confirm OUR OWN VALUE to us: every log said
`base=IR ratio=32768/32767 agrees=true` while the picture was wrong. The operator saw white
outlines on every panel gap and wheel arch in PIP, in TAKPilot2 AND in Autel Explorer, because
the setting belongs to the CAMERA and both applications share it.

⚠ **NEVER OVERWRITE A CAMERA SETTING WITHOUT RECORDING WHAT WAS THERE.** That is the defect;
the wrong constant is only what it concealed. `applyAtConnect` now READS the base and the ratio
before it writes, and the held values go in the log. That read stays in.

**The measurement, in one line.** The outlines were switched off by hand in Explorer (the flame
control above its blend slider), then TAKPilot2 was opened:
`the camera HELD, before this write: base=None ratio=32768/32767`.

⚠ **`None` IS THE PLAIN THERMAL WINDOW AND `IR` IS THE EDGE LOOK. THE v2.2.0 BENCH NOTE HAS THE
TWO BASES THE WRONG WAY ROUND** — it calls `Visible` the edge look and `IR` the plain window.
⚠ **`Visible` IS UNMEASURED ON THIS FIRMWARE.** Do not describe it from that note either.
⚠ **THE RATIO IS AN OPACITY MIX AND IT IS LIVE UNDER `None`** (measured 2026-09-16, and it
CORRECTS an earlier line here that said the ratio was not part of this). `None` is a BLEND, not
blending switched off — the window carries visible-lens detail a 640x512 thermal cannot draw.
The pair is Explorer's slider: IR = pct/100 × 65536, Visible = 65535 − IR, and Explorer reads
back whatever this application last wrote. At 50 % (`32768/32767`, v2.3.6 and earlier) the
window was half visible: gravel sharp enough to count the stones. At 75 % (`49151/16384`,
v2.3.7) it is white-hot thermal with the scene still readable — **the operator's default**.
⚠ The FLAME control in Explorer moves the BASE alone; the SLIDER is the ratio. Two different
controls, and the outlines were the base.
⚠ **A PILOT-FACING SLIDER IS WANTED** (operator, 2026-09-16) and is NOT built. It is a
flight-screen control, thus specification §4 and both DJI trees — not a bolt-on. Until it
exists the default goes to the camera at every connect. ⚠ 25 % and 100 % are unmeasured, so 75
is a chosen value and not the best of a sweep.

**PIP IS NOW THE APPLICATION'S DEFAULT, NOT EXPLORER'S LEFTOVERS** (operator, 2026-09-16). Base
and ratio are written at every connect, so the picture is the same on every aircraft in the
fleet whatever Explorer was last set to. ⚠ What is still INHERITED, because nothing here writes
it: the thermal palette (read at connect, cycled by the pilot), the IR position trim
(deliberately read-only — see `AutelBlendFormat`), and the other thermal image settings
(`IrEnhance`, denoise, gain, isotherm).

**v2.3.4 IS RELEASED** — tag `v2.3.4`, versionCode 99, 2026-09-16, signed APK and notes in
`../../../signedReleases/Autel-MSDKv1/`, GitHub release without the APK. It carries the REC fix
below AND the AR edge arrows of the same day: the arrow keeps clear of the actions column on the
left, and rides outboard of the readouts on the right. Bench tested on the controller by the
operator — the pill through the leave-and-return repro, the arrows by eye. Not flown.

⚠ **v2.3.3 SHIPPED NOWHERE.** It was built, bench tested and its APK put in `signedReleases/`,
then 2.3.4 took its place the same day; there is no `v2.3.3` tag and no GitHub release. The
2.3.3 APK and notes are still in that folder, superseded. Do not read the gap as a lost release.

⚠ **THE RIGHT EDGE OF THE AR VIEW TAKES NO INSET, AND MUST NOT** (operator, 2026-09-13, again
2026-09-16). The readouts lost their panels in v1.7.7, thus the HUD column is outlined text and
an arrow behind it is visible; the old full-width inset cost about 208dp, a fifth of this
screen, on the edge a pilot scans most. The 16 September fault was an arrow IN the glyphs of
"200 ft AGL", and the fix is the ARROW MOVING OUTBOARD into the strip the column already leaves
clear — its own paddingEnd, halved, because the margin places the arrow's CENTRE. The LEFT is a
real inset: the actions column is the same 70 % fill as the band, it runs the full height of the
picture, and an arrow was drawn on the AR pill. Specification §4.2 carries both; both DJI trees
owe the left inset. ⚠ Projected markers stay pinned under chrome — only `drawEdgeArrow` reads an
inset, and that must not change.

The REC pill stays lit while the aircraft records.

⚠ **THE FAULT WAS IN THE CODE WRITTEN TO PREVENT IT, AND THE v2.1.9 NOTES ALREADY CLAIMED IT
FIXED.** Start a recording, leave the flight screen, come back: the pill was dark with the
aircraft still rolling, a press gave "Needs an idle status to start recording videos", and only
the hardware button recovered it. Returning re-fires the camera listener; the 2026-09-14 re-fire
guard worked correctly and called the read-back that confirms the state. **At 15:36:09, six
seconds into a recording the camera had itself confirmed with RECORD_START,
`getCurrentRecordTime` answered `-1209991155`.** The SDK returns the seconds through a
`CallbackWithOneParam<Int>` while `XT706CameraInfo` types the same value a LONG; the truncation
is what comes out. So `> 0 seconds` read a live recording as idle and cleared the flag.

⚠ **DO NOT USE `getCurrentRecordTime` FOR A DECISION.** It is not a duration and it is not a
state. It is left in the aar, unused.

⚠ **A WRONG FLAG WAS PERMANENT, AND THAT IS THE HALF THAT MADE IT A FAULT.** `isRecording` is
learned from MediaStatus pushes, and a recording already running sends no second `RECORD_START`.
The correction is now `XT706CameraInfo.getWorkState()` — IDLE / CAPTURE / RECORD /
RECORD_PHOTO_TAKING — an enum on the ~2 Hz info push that already carries the FOV and the card
figures. No call of its own, and nothing to truncate. UNKNOWN and null are not answers and
change nothing. Also: the camera-change branch no longer CLEARS the flag before asking (the
clear ran first and the read is the part that can fail); a REC press checks the work state as
well as the flag and STOPS a recording the pill did not know about; and the `UnknownCamera`
placeholder the listener announces ~3 s before the real camera is ignored rather than armed on.

⚠ **THE LESSON IS ABOUT THE LOG, NOT THE CAMERA.** An audit of the recording path found three
plausible causes and fixed two of them; none was this one. The operator's repro with Detailed
logging on put the impossible number on the screen in one run. **A camera question is settled by
a log line, not by reading the path** — the same rule the SD card taught on 12 September.

⚠ **BOTH DJI TREES OWE A CHECK, NOT A PORT.** This is an Autel SDK shape. Confirm what their
SDKs return for a record time before assuming either is affected.

**v2.3.2 IS RELEASED** — tag `v2.3.2`, versionCode 97, 2026-09-16, on the operator's controller.
A persistent item never reports stale (`TakUser.isStale`), so a marker shared through TAK Aware
keeps its colour instead of going grey when the sender's ten-minute window passes. Shared core:
the taklite-core master moved first (commit `1cd9d3e` in the UAS_Apps repo, which also committed
the 14 September `isUasReport` move that had sat on disk). Both DJI trees owe both moves.

**v2.3.1 IS RELEASED** — tag `v2.3.1`, versionCode 96, 2026-09-15: the log switch sets its
three options (on checks all, off clears all) and the resource row follows the switch.

**v2.3.0 IS RELEASED** — tag `v2.3.0`, versionCode 95, 2026-09-15, signed APK and notes in
`../../../signedReleases/Autel-MSDKv1/`, GitHub release without the APK (operator). It carries
everything below that was written under v2.2.0 (nothing shipped under 2.2.0) plus the 15th: the
left actions column, the video/photo switch, REC from any mode, the DEBUG LOG ON warning, the
Debug screen, and the AR fixes flown that day. Bench-tested and one test flight; not flown by
the test pilots.

**v2.2.0 was the development line (versionCode 94, 2026-09-14).** PIP: the thermal image in
the centre of the visible image, drawn by the CAMERA.
⚠ **THE TAP IS A TWO-WAY TOGGLE, visible ↔ PIP, and from full thermal it goes back to PIP.**
The C1 key does the same. **Full thermal is reached from a SECOND PILL in the actions column,
directly under the PIP pill, shown only while thermal is on screen** (`⤢` / `⤡`). **The pill
reads `PIP` in all three views** — unlit in visible, lit in PIP and in full thermal alike; the
"IR" label is gone. Two other shapes were tried and REJECTED that week: a long-press menu (the
14th) and a three-step cycle visible → PIP → thermal (the 15th, "a chore"). This paragraph
carried that cycle as fact until 2026-09-16; `CameraView.next` and specification §4.2 are what
shipped.
The measurements are in `app/build.gradle` under v2.2.0 and every one came off the camera's own
JSON-RPC API with a read-back. The five that must not be re-derived:

- **The controller receives ONE composited stream.** The camera blends; the app only asks.
- **OVERLAP is refused by this firmware** (status -1). Only PictureInPicture exists here.
- **PIP is centred and scaled by the camera** and meshes; `SetIrPosition` is a manual ±20 px
  trim that this app READS and never WRITES.
- **A blend publishes the VISIBLE cone** (operator). In PIP the camera reports the thermal FOV
  over the visible frame; `publishedHFov` overrides it. The CoT has no lens tag; the shared core
  is untouched.
- **`irOn` is gone.** `CameraView` (VISIBLE / IR / PIP) is the one lens state. Zoom is refused
  in PIP; the recording lock covers the view; PIP records THREE files at 720p25, about 7 GB/h.

⚠ Not measured: stills in PIP. ⚠ The 1080p H.264 record stream reads 60000 kbps, not the 45000
the v2.1.7/v2.1.9 notes carry — that ladder was read under H.265. Confirm from a file before
correcting those notes.

**THE AR OVERLAY WAS AUDITED ON 2026-09-14 AND SEVEN OF TEN FAULTS ARE FIXED AND FLOWN
(2026-09-15).** The ranked list and the measurements are in `app/build.gradle` under v2.2.0.
Fixed: the FOV pairing in thermal and PIP, the pitched-camera projection (`CameraProjection.kt`),
the sea-level pin, the flat-plane fallback, and the geoid (`ElevationPolicy.kt`). **Fixed
2026-10-09, carried in v2.4.0 with the video split (operator's call, rather than a second
branch): the DTED seeks on the UI thread** — `DtedTile` caches the posts and keeps its handle
open, which changes no answer because a post is a fixed grid sample and the interpolation still
runs on the caller's exact lat/lon (`DtedTileCacheTest`; ⚠ do NOT "simplify" it by quantising the
QUERY — that reintroduces the terrain error, 1 m becoming 2.6 m of horizontal miss at 21°).
**Gimbal roll is READ and LOGGED but NOT APPLIED** — `getRoll()` was on the interface the bridge
already used, so that gap was the app's; `liveGimbalRoll` holds it raw and is deliberately not in
`CameraPose`, because this firmware reports pitch down-positive against DJI and the roll sign is
therefore unknown until it is bench-measured. Open, in order: magnetometer bias by heading (**NO
APP CORRECTION — the operator's standing decision; do not propose a per-airframe table again**),
telemetry-to-video latency (needs the lag measured on this hardware first), and the roll sign.
ADS-B heights are PRESSURE altitude from the gateway and draw high or low by the
day's altimeter setting; not attempted. The switch time between views is the camera's 1.8 s and
matching the record format does not change it — measured, do not retry.

Also on 2026-09-15: `DEBUG LOG ON` is a banner warning the ✕ does not close; the Debug screen's
log control is a switch with its three options under it; the Explorer watchdog is always on and
has no control; the RF power write and probe are gone (Autel limit, operator); Cancel Landing is
removed (flown, the aircraft did not obey). **The PIP tap-cycle BECAME the two-way toggle and
shipped that way** — see v2.2.0 above. The maximise control is a PILL IN THE COLUMN, not a
control on the window: five placements on the picture were tried and rejected.
`PipWindowGeometry` stays as the record of where the camera draws the window.

v1.5.9 is on the fleet (tag `v1.5.9`). v1.6.0 is open on master and waits for flight-test
feedback from the test users. The v1.6.0 finding list is in `REVIEW_2026-08-07_AUDIT.md`
section 4.

**The version numbers live in `app/build.gradle`. Read them there.** This paragraph carried a
`versionCode` that was 13 builds out of date.

In v1.6.0 so far: the CoT video advertisement now carries a nested `ConnectionEntry`, which
is what makes the feed playable from the aircraft marker and the pilot marker. This was
flight-verified on 2026-08-12 — the operator confirmed video on both markers. ⚠ **The pilot
marker's share of this is REVERSED on the `uasvideo-split` branch (operator, 2026-10-08)** — see
the v2.4.0 entry under Current work; the aircraft marker alone carries the stream there. The bare
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

v1.6.1 is RELEASED; it carried the Field Guide rewrite AND the removal of channel selection.

**v2.0.0 IS RELEASED** — tag `v2.0.0`, versionCode 69, 2026-09-13. The flight-screen refresh,
finished. Basic flight test by the operator before release.

⚠ **FULL DEBUGGING IS DEFERRED, BY THE OPERATOR'S CALL.** The flight test was basic. Anything
found from here takes a SMALL update (2.0.1, 2.0.2 …), not a held release. Do not treat an
open finding as a reason to withhold the next fix.

⚠ **THERE IS NO v1.7.8 IN THE FIELD AND NO `v1.7.8` TAG.** v1.7.7 was the last release before
v2.0.0. A commit of 2026-09-12 (`5e01e12`) said 1.7.8 "goes to the fleet" and corrected the
notes to match, but no tag, no GitHub release and no signed APK were ever produced, so nothing
shipped under that number. 1.7.8 was the development line the work rode on, exactly as
originally planned. Do not read the gap as a lost release.

The versionName had to move off 1.7.7 during development even though nothing changed on the
wire, for the reason recorded under v1.6.1: versionName is what a TAK server's Connected Users
panel shows for this client, so a development build still calling itself 1.7.7 is
indistinguishable from the released one in the one place the fleet looks.

In v2.0.0:

- **The toolbar is TWO FLOATING CAPSULES over the video** — status on the left, actions on the
  right — and the solid blue bar is gone (`bg_toolbar` deleted). `bg_hud_capsule` is the fill,
  70 % black, borrowed from TAK Aware's `overlay-fill`.
  ⚠ **The band keeps the `flightToolbar` id and MUST.** `ArOverlayView` and `ObstacleEdgeView`
  read that view's HEIGHT as their top chrome inset, so an edge arrow or a proximity label
  cannot hide behind the chrome — the case that matters is the aircraft directly overhead.
  ⚠ **Both capsules carry `minHeight="@dimen/hud_capsule_height"`, and it is a MINIMUM.** They
  hold different things (icons and small text against 48dp touch targets) and measured 80 px
  against 104 px, so two capsules that start on one line ended on another. A fixed height would
  clip on a device not yet seen. `layout_height="match_parent"` in a wrap_content row was tried
  and collapsed BOTH to 18 px.
- **`ic_led_off`'s slash under-stroke follows its background.** It was the bar's `#0D47A1`; the
  bar is gone, so it is now the opaque `tp_hud_outline`. That icon's own comment predicted this.
- **The exterior-lights button says its state with COLOUR**, like AR and IR beside it: green
  lit, plain dark, **AMBER when the aircraft has not answered**. Three toggles in one capsule
  had carried two conventions, and the odd one out was the only one reporting an AIRCRAFT state.
  ⚠ The amber replaced a **45 % alpha**. Dimming reads as DISABLED, and this button genuinely
  disables itself with no colour change while a write is in flight — one appearance, two
  meanings. Do not bring the alpha back.
  ⚠ **Only the LIT bulb may be tinted.** `ic_led_off` draws its slash twice, dark under white,
  so it reads as a gap cut through the glass; a tint hits every path of a vector and would
  flatten both passes. This is safe only because the slashed bulb appears solely in the dark
  state, which takes the colourless pill. If the dark state ever gains a colour, the icon needs
  a second drawable FIRST.
- **`bg_ar_pill_active` is now `bg_pill_active`** — it had three consumers (AR, IR, the Field
  Guide) before the lights made a fourth, so the AR name was already wrong. The three pill
  drawables moved off raw hex onto tokens (`tp_pill_*`); the values are byte-identical and only
  the amber fill is new.

⚠ **THREE FAULTS CAME OUT OF THE 12 SEPTEMBER FLIGHT, AND THE SD CARD SETTLED ALL OF THEM.**
The logs alone were not enough for any of the three; the card is what turned an inference into
a fact. Pull both when a camera question comes up.

1. **THE CAMERA WILL NOT CHANGE LENS WHILE IT IS RECORDING, AND IT RETURNS `OK` ANYWAY.**
   Safety rule 4 in the flesh. Eight VISIBLE/IR toggles over 22 s, every one logging
   `setDisplayMode: OK`, and the incoming video never changed shape — it stayed 1920x1080.
   Five seconds after RECORD_STOP the same button gave `video frame size 640x512` **13 ms**
   later, which is the thermal sensor's native size. The pilot saw the application letterbox
   its own VISIBLE picture, because it believed itself and applied the thermal FIT rule.
   ⚠ The real damage was on the wire: it told the bridge the lens was IR, so **the camera point
   went to the whole TAK team tagged thermal, with the thermal FOV**, over visible video.
   `onIrTapped` and `onIrPaletteTapped` now REFUSE while `isRecording`, with an amber §4.8
   notice. A read-back is NOT the fix — `getDisplayMode` and `getIrColor` were both measured
   timing out 350 ms after RECORD_START, so the channel that would answer is itself unhealthy.
   **Autel Explorer behaves the same way** (operator checked), thus this matches the vendor.

2. **A `PHOTO_TAKEN_DONE` IS NOT PROOF A PHOTO WAS SAVED.** A real capture's DONE carries a
   thumbnail url naming the file, and is followed by a SECOND, url-less DONE. A done with no
   detail proves nothing on its own, thus only a done that names a file counts as a confirmed
   capture — see `photoDoneNamesAFile`.

   ⚠ **THE "TWO LOST PHOTOS" PART OF THIS FINDING IS WITHDRAWN (2026-09-13).** It said stills
   0021 and 0024 lost their visible frame and that `MAX_0021.JPG` and `MAX_0024.JPG` do not
   exist. Re-checked against the card:
   - **`MAX_0024.JPG` IS on the card**, 2.5 MB, with its `MIX_0024` and `IRX_0024` partners.
     Numbers 0022 to 0031 are all complete sets.
   - **`MAX_0021` WAS NEVER A PHOTO NUMBER.** That block runs in TRIPLETS, one press consuming
     three consecutive numbers — `MAX_0019` / `MIX_0020` / `IRX_0021`, and the same before it.
     0021 is the thermal third of a press, not a gap. Finding 3 below describes that very
     numbering, which was available when this was written.
   - Every 12 September file on the card has a `MIX_` partner, and **this application cannot
     produce a `MIX_` file**. They are Explorer's. Our own 12 September flight left nothing on
     this card, so the log's still numbers were matched against Explorer's files by coincidence
     of numbering.

   The 12 September logs have since ROTATED OFF the controller and no export was kept, thus
   this cannot be settled further. Treat it as unproven in both directions.

   ⚠ **WHAT SURVIVES, AND IT IS ENOUGH.** The BEHAVIOUR is real and was re-measured on
   2026-09-13: url-less dones exist (12 of them in one flight, each trailing a named one), and
   the camera does fire "The take photo is failed" after a capture that succeeded. The fix
   stands on that: 15 shutter presses gave 15 named dones and 15 files on the card, no loss.
   ⚠ **NOTHING HAS EVER BEEN SEEN TO FAIL.** "The photo did not save" has never fired in the
   field and its trigger is unproven. Do not cite a lost photo as the reason for this code.

3. **WHAT THE AIRCRAFT SAVES FOLLOWS `DisplayMode`, AND EXPLORER PROVES IT SAVES MORE THAN WE
   ASK FOR.** `DisplayMode` has four values — VISIBLE, IR, PICTURE_IN_PICTURE, OVERLAP — and
   this application only ever uses two. On a card written by Autel Explorer, ONE recording
   produced three video files at the same number (`MAX_0002` / `MIX_0002` / `IRX_0002`) and ONE
   shutter press consumed three consecutive numbers: visible, blended, thermal.
   **OPEN QUESTION FOR v2.0.0, NOT MEASURED BY US:** selecting a blend mode appears to make the
   camera save all three streams, for video and for stills. `IRX_0001.MP4` sitting alone on that
   card fits it — thermal display, thermal only. If it holds, a search gets visible, thermal and
   the blend from one recording, and the lens lock above stops mattering. It needs one test:
   select PIP or OVERLAP, record briefly, count the files.
   ⚠ On OUR card, with the app in VISIBLE throughout, a still outside a recording wrote BOTH
   halves and a still DURING a recording wrote the visible only. That is what the Field Guide
   now tells the pilot, and it is true for this application as configured — do not generalise
   it to Explorer's configuration.

- **The Field Guide mirrors all three lights states** via `lightsPill(dark)`, the way it already
  mirrored the TAK badge and the AR pill. It drew two bare white bulbs before, which disagreed
  with the screen the pilot holds.
- **The actions capsule is ONE FAMILY OF PILLS.** LIVE and REC were fully-round switches with a
  large white knob; the knob is what made them read as something to DRAG, and squaring the
  corner alone did not fix it. They take the same treatment as AR — a 30 % wash, a
  full-strength stroke, and the content in that colour — with only the hue differing, because
  red means "going out to the team" and amber "retrying", not "this feature is on".
  ⚠ **SIX PILLS, TWO WIDTHS, and that is a rule now** (operator): AR / zoom / IR / lights at
  54dp, LIVE / REC at 66dp. It was 46/54/46/46/66/62. Both numbers are the measured minimum for
  their content — 54 from the zoom pill's fractional labels, 66 from the word "SYNC" — so
  neither collapses into the other. `hud_pill_radius` and `hud_pill_stroke` drive all six, the
  three drawables and the two canvas views.
- **The on-screen shutter pill is GONE.** The controller has a hardware shutter and a second way
  to do it was clutter. ⚠ The "Photo Saved" notice STAYS and is now the hardware button's only
  confirmation — its flag comes from the camera's own MediaStatus, so it fires either way. REC
  still reads the media mode on EVERY press, and that matters more now, not less: the hardware
  shutter can leave the camera in SINGLE and this application neither drives nor observes it.
- **The mini-map has rounded corners** — `flightMapContainer` clips to a rounded outline and
  `bg_map_outline` curves with it. ⚠ This works only because osmdroid draws tiles to the
  ordinary Canvas; a SurfaceView-backed map is composited separately and a parent outline clip
  does NOT touch it, so a swap would silently leave square tiles behind a rounded border. The
  clip is `hud_pill_radius + stroke`, putting the cut on the MIDDLE of the frame's stroke — the
  frame's outer edge is `radius + stroke/2`, not `radius`, and matching that exactly still left
  a seam where two anti-aliased edges met. A hair is still findable under magnification;
  widening the frame to close it was tried and REJECTED ("close enough ... dont thicken it up").
- **The WIDE / NEAR button is a pill**, with one deliberate departure: it keeps the dark capsule
  fill because it sits ON the map, where the pills' 20 % white wash is invisible over pale
  street tiles. A pill's shape and hairline, a capsule's fill.

**v1.7.7 IS RELEASED** — tag `v1.7.7`, versionCode 67, 2026-09-12. Video encoding AND the first
step of the flight-screen refresh. ⚠ **NOT FLIGHT TESTED** — checked by screenshot and from the
receiving end only.

**What v1.7.7 carried, in detail.** Video encoding AND the first step of the flight-screen
refresh. Checked on the controller by screenshot; NOT flight tested.

⚠ **AUTEL LEADS THE FLIGHT-SCREEN WORK, AND THE DJI TREES FOLLOW LATER (operator,
2026-09-12).** This is a DELIBERATE, TEMPORARY departure from "a UI change lands in all three
applications, or it lands in none". The operator is taking the Autel screen to where they want
it FIRST, then porting. Read it that way and not as drift:

- The rule is NOT repealed. The DJI trees owe everything below.
- ✅ **THE SPECIFICATION IS AMENDED (2026-09-12) AND THE DEBT IS PAID ON THE DOCUMENT SIDE.**
  §4.2 is two floating capsules, §4.3 is outlined text with no panels, §4.4 has the size
  hierarchy, §4.9 has the rounded and clipped map, and §6.7 is a new section defining the pill.
  §6.1 carries the new tokens and §7 the new dimensions. **This tree is now RIGHT against the
  specification rather than ahead of it** — a reading of §4.3 that still expects panels is
  reading a stale copy.
- ✅ **THE DJI CODE IS PAID TOO, 2026-09-13.** D14 to D18 landed in MSDKv5 (`7d3ec20`) and
  MSDKv4 (`d938531`). All three applications carry the amended sections.
  ⚠ **Both DJI ports are closed against the BUILD and not against a device** — neither a phone
  nor an RC Plus 2 was attached. Each of those screens has a budget that fails SILENTLY, and
  neither tree should be released until it is measured with `dumpsys activity top`.
  ⚠ **The pill widths did NOT transfer.** MSDKv5 uses 48dp where this tree uses 54dp: its
  toolbar had about 34dp of slack and 54dp would have cost 24dp of it. §4.2 was amended to make
  the RULE the MUST and the numbers per-device — see finding X3.
- ⚠ **D4 IS SUPERSEDED.** The ledger closed a finding on 2026-08-14 that GAVE both DJI trees the
  HUD panels. §4.3 has since removed panels entirely. A closed ledger row does not prove the
  rule behind it still stands.
- The DJI phone port has a MEASURED constraint waiting — see the height budget below.

⚠ **THE HUD PANELS ARE GONE AND THE READOUTS CARRY A BLACK OUTLINE** — see
[OutlinedTextView]. This replaced two MUST clauses in specification §4.3 (the panels, and the
panel-width rule) and added the §4.4 size hierarchy. **THE SPECIFICATION SAYS SO AND BOTH DJI
TREES ARE NOW PORTED** — unmeasured on hardware, see above.

- **The mini-map carries the same outline**, as `flightMapContainer`'s foreground
  (`bg_map_outline`), thus it follows the map to its expanded size with no second value.
- **`tp_hud_outline` is the ONE token for every HUD edge** — readouts, EV slider, map frame.
  It is FULLY OPAQUE on purpose: the outline is half covered by the fill drawn over it, so any
  transparency reads as a grey smear. The width is `hud_text_outline_width`, per-device.

- **The translucent panel behind each HUD block is removed.** It stopped white text washing
  out over snow, wet asphalt or a white roof, and it worked — but it covered live video, and
  its opacity had already been walked 55 % to 40 % to 20 % chasing that trade. A black
  outline is legible on ANY background and covers only the pixels around the glyphs.
  `OutlinedTextView` draws the text layout twice: a black stroked pass, then the fill.
- **The height readout has a size hierarchy.** Its FIGURE is large and its unit small; the
  coordinates shrink. `HEIGHT_FIGURE_SCALE` / `HEIGHT_UNIT_SCALE` / `REFERENCE_SCALE` in
  `FlightActivity`. The unit and the coordinates give back most of what the figure takes, so
  the block grows only about 0.4 of a line.
- **The EV slider, its ticks and its thumb take the same outline**, at the same per-device
  dimen, so the whole HUD carries one weight of edge.

⚠ **THREE FAULTS WERE FOUND BY LOOKING AT THE SCREEN, AND EVERY ONE LOOKED LIKE SOMETHING
ELSE.** Read them before touching this code:

1. **The stroke was twice too wide** (taken from a CSS mockup, where the same word means the
   visible half, then doubled again in the view). The readouts rendered as black blobs.
   Keep the stroke near an EIGHTH of the text size.
2. **The fill pass was not white.** `OutlinedTextView` bypasses `super.onDraw`, and that is
   what normally sets the paint's colour from the resolved text colour — so a saved-and-
   restored colour just carries the wrong value forward. Read `currentTextColor` at fill time.
   This looked exactly like fault 1 and survived the first correction.
3. **The outline was CLIPPED FLAT on the right of every line.** A TextView is measured for the
   glyphs' advance width, which is the fill; the stroke's outer half falls outside it and the
   canvas is clipped to the view. Half the stroke of padding on each side is the fix. The EV
   thumb had the same fault at the ends of travel, where a mid-track screenshot hides it.

⚠ **THE HEIGHT BUDGET IS NOW THE RISK FOR THE DJI PHONE PORT.** The outline padding costs
about 1dp top and bottom on each of six views (~12dp) and the hierarchy about 5.8dp. The
recorded headroom on a Pixel-class screen is ~19dp, and the column's failure mode is the
mini-map being clipped SILENTLY — see `flight_map_size` in `dimens.xml`. Measure with
`dumpsys activity top` on the phone before landing this there. This controller is 720dp and
has hundreds of dp spare.

It also carries the video work:

- **H.264 asks for HIGH profile, not Baseline** (`VideoCodec.profile`). Baseline has no CABAC
  and no 8x8 transform. Both are picture quality at NO extra bandwidth, and the gain is
  largest at a low bit-per-pixel — this stream runs at 0.077 (960x720, 15 fps, 800 kbps).
  ⚠ **Safe ONLY because `ScreenCaptureEncoder.noBFrames` pins `KEY_MAX_B_FRAMES` to 0.**
  High PERMITS a B-frame; a B-frame is coded from a LATER frame, thus it delays the picture a
  pilot flies from. The old Baseline choice forbade them by construction and gave up CABAC to
  do it. Do not delete `noBFrames` and keep the profile. The key goes on the PROFILE RUNGS
  only: on the base format an encoder that refused this API-29 key would fail every rung and
  send no video at all.
  ⚠ **NOT yet flight tested, and High is not confirmed supported on this SoC.** Check the
  `screen capture` log line: `variant: … / full (profile+level…)` means High was ACCEPTED. A
  silent fall to `max-fps, no profile/level` looks exactly like a change that did nothing.

Decided AGAINST for this version, with reasons, so they are not re-opened:

- **The IDR interval stays at 10 s.** I-frames are ~9 % of the bandwidth and 20 s would free
  about half of that, but the operator needs QUICK JOINS for clients (2026-09-12). A late
  viewer waiting longer for its first clean picture is not worth 4 % of bitrate.
- **H.265 stays unselected.** It is already a pilot choice on the Video Servers screen and it
  is the biggest gain available, but the operator will not use it until every client on the
  net can decode HEVC (2026-09-12). This is a fleet-readiness decision, not a code one.

**v1.7.6 IS COMMITTED AND BENCH TESTED, NOT RELEASED** — versionCode 66, 2026-09-12, tested
in flight on the controller. Every item verified on hardware:

- **The warning banner opens on a tap and closes on a ✕** — both §4.8 slots, ported from
  MSDKv5. `FlightWarnings.Display.all` is the open list. `FlightActivity.renderWarning` owns
  the repaint; `warningDismissedSignature` holds the closed set. ⚠ A close hides ONE SET of
  warnings and never the banner: the signature is compared on every repaint. The banner row
  is a later `FrameLayout` child than `flightCrosshair`, thus it takes the touch. **Verified
  in flight: two taps on a live warning, no marker dropped.** Also verified with two warnings
  at once — BATTERY LOW took the banner from AT ALTITUDE LIMIT (worst first), the ▾ appeared,
  and the open list showed both, battery first. Per-device sizing is in `dimens.xml`
  (`flight_warning_*`). The Field Guide has the "Warnings (top left)" entry.
- **`AppLog` buffers lines and keeps the public stream open.** One MediaStore open per
  archive file, not per line. Flush at 8 KB or 1 s, at once on E and FATAL, and on Clear,
  Delete and logging-off. A process the system KILLS can lose the last second of lines.
  **Measured in flight with logging ON:** MediaProvider fell from 12,622 log lines to 63 and
  left the CPU top four; janky frames 3.81 % -> 1.75 %, slow UI thread 555 -> 63, 99th
  percentile 105 ms -> 19 ms. Logging ON is now smoother than logging OFF was before. Flush
  on disable measured at 37 ms, nothing lost.
  This is also the `taklite-core` master; Autel conforms fully. DJIv5 and DJIv4 are synced.
- **A stopped stream no longer logs "connection failed"** or puts "Stream failed" on the
  status line — `AutelVideoStreamer.handleFailed` returns early on the `stopped` flag.

**v1.7.5 IS RELEASED** — tag `v1.7.5`, versionCode 65, 2026-09-10. Flown twice that day. Two
faults from a real mission, both corrected in the shared core (`taklite-core` master first,
then synced here — DJI v4/v5 NOT yet synced, `check-taklite.sh` shows the drift):

- **The pilot marker goes out with no controller fix**, in the "position not known" form:
  `how="h-g-i-g-o"`, 0,0, `hae`/`ce`/`le` at 9999999, no track, no GPS source
  (`CotBuilder.buildPLINoFix`, `TakManager.sendPilotPLI` with a null location). Before, nothing
  went out, thus the controller was not in any client's contact list and nobody could send it
  a marker. A real fix now carries its true `ce`. The parser keeps a 0,0 event only for a live
  client. The map takes a 0,0 contact's marker OFF (a teammate that lost its fix).
- **Markers forwarded by TAK Aware drew as cyan dots.** TAK Aware puts a contact endpoint on a
  forwarded marker, and the renderers read that as a live client. The rule is now in ONE place,
  `CotParser.isLiveClient`: a persistent item is never a live client. `isPersistentType` takes
  `hasTakv`: a client's own report is never a placed item. Renderers read the flag only.
- A stale 2525 frame draws grey on the map and in AR. `ArSettings.categoryFor` takes the
  live-client flag, thus the AR layer and the AR draw style agree.

⚠ **With file logging ON, `AppLog` opens a MediaStore stream for EVERY log line**
(`writePublicMediaStore`). Measured in flight 2026-09-10: MediaProvider at 120% CPU, more
than the application, and the screen stuttered. Logging OFF, the same flight was smooth.
Open item for v1.7.6: keep the stream open, flush on a timer or a size, and FLUSH IN THE
CRASH HANDLER, or a buffered log ends before the crash it must explain. Also for v1.7.6:
`AutelVideoStreamer.handleFailed` logs the server's normal close after a user stop as
"connection failed" — check the `stopped` flag and log it as info.

⚠ **Not yet flight tested:** a teammate that loses its fix, as seen from a SECOND controller.

**v1.7.3 IS RELEASED** — tag `v1.7.3`, versionCode 59, 2026-08-31. Pre-Flight gains section 0,
Memory Card: card state, free space, and a Format Card button
(`AutelBaseCamera.formatSDCard`). The internal flash has its own call,
`formatFlashMemoryCard`, and is deliberately NOT offered.

⚠ **SECTIONS 0 AND 1 MUST FIT THE SCREEN WITHOUT SCROLLING, and the layout editor cannot show
you.** Measured on the controller: the scroll viewport is 1264 px and section 1 ends at 1376 of
1440 — 64 px clear, with the section-0 status line SHOWING, which is the tall case. Re-measure
with `dumpsys activity top` after any change to those sections or to `takLabelStyle` /
`takFieldStyle`. The room for section 0 came from VERTICAL PADDING in those two styles
(12/4 -> 6/2 and 12 -> 8 top/bottom), thus every section tightened evenly and no text shrank.

⚠ **The SDK does not report SD CAPACITY.** `XT706CameraInfo` has `getSDcardFreeSpace()` and no
total; the whole aar was searched and only free-space getters exist for the card, while the
internal flash has both. Do not add an inferred capacity to a pre-flight screen.

⚠ **A real format has NOT been run yet**, and section 0 is pilot-facing UI that the DJI trees
do not have — see the UI conformance ledger.

**v1.7.2 was released** — tag `v1.7.2`, versionCode 52, 2026-08-31. It carries one fix: the
flight screen reads the camera and the exterior lamps when the AIRCRAFT ARRIVES, not when the
screen opens.

⚠ **A read at `onResume` is not a read at connect, and a warm restart hides the difference.**
`syncIrStateFromCamera()` begins `xt706 ?: return` and `AutelLights.refresh()` ran in
`onCreate`; on a cold start both ran 11 minutes before `productConnected` (measured
2026-08-31) and nothing asked again. Every earlier log looks correct because the application
was restarted with the aircraft already connected. `AutelProductHolder` now has a
**camera-ready observer**, fired where a real `AutelXT706` is confirmed — NOT at
`productConnected`, which fires first with `UnknownCamera` and fails every call.

⚠ **The cold-start sequence is not yet flight tested.**

**v1.7.1 was released** — tag `v1.7.1`, versionCode 50, 2026-08-30. It carries the whole of the
never-released v1.7.0 (SRT as a second transport, the video-server screen, two servers, a port
and a passphrase per protocol) AND the video work of 30 August. Flown by the operator before
release. New work takes a new version.

⚠ **There is no `v1.7.0` tag and there never was a v1.7.0 in the field.** The fleet went from
v1.6.2 to v1.7.1. Do not read the gap as a lost release.

Landing in v1.7.1 on 30 August, all measured on the controller and recorded in the code:

- **Intra refresh is always on.** A full intra frame every 2 s cost 169 KB against a 9 KB P
  frame — 39 % of the whole data rate for 3 % of the frames, about 770 ms of link time at
  1800 kbps. Spread across 30 frames the keyframe share fell to 6.5 % (H.264 High) and 4.5 %
  (H.265 Standard). Both hardware encoders took the key on the FIRST attempt.
- ⚠ **The controller was never the problem.** `CAPABILITY` reports 159 fps at 1440x1080 for
  AVC against a need of 15, the GPU idles at 160-266 MHz while streaming, and the media server
  counted zero loss, zero retransmission and 2.18 ms RTT. Do not re-open "the hardware cannot
  keep up" without new evidence.
- ⚠ **A LAN cannot judge the video transport.** The bench path is clean, thus SRT latency and
  intra refresh both show nothing there. Judge them by `packetsReceivedDrop` from
  `GET /v3/srtconns/list` over LTE. **The media server API is `127.0.0.1:9898`, NOT 9997** —
  9997 belongs to the CloudTAK container on the same host.
- **The thermal picture is shown whole**, visible-light still fills and still cuts 25 % off the
  sides. That second decision has NOT been made.
- **The Debug SRT latency box could not be typed into** — the log pane's `fullScroll(FOCUS_DOWN)`
  took the window focus once a second. Never use a ScrollView method with `Focus` in the name.

**v1.6.2 was released** — tag `v1.6.2`, versionCode 33, 2026-08-16. Bench and flight tested the
same day; the results are `FLIGHT-TEST-CHECKLIST.md` section 4A.

⚠ Section 4A also records TWO BEHAVIOURS CONFIRMED CORRECT. Do not re-diagnose either as a
fault: the aircraft marker and the SPI stay after a channel change until they go stale (they are
marker-type CoT, unlike the pilot position), and the video address goes on the wire when a push
STARTS rather than when it connects (the CoT carries the address the pilot entered — operator,
2026-08-16).

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

**THE FIELD GUIDE HANDOUTS ARE GONE (operator, 2026-09-12)** — the `TAKPilot2-FieldGuide.md`
handout beside this file and the `tools/generate_field_guide_md.py` that made it from
`FieldGuideActivity.kt`. They were a paper copy of a screen the pilot already carries, and they
were a second thing to keep in step with it.

**`FieldGuideActivity.kt` IS THE FIELD GUIDE.** It is the screen in the application and it stays.
Only the exported copies went. An edit to the guide is now finished when the Kotlin is correct;
there is nothing left to regenerate.

**2026-10-09, LATER THE SAME DAY: THE TWO CHANGES BELOW ARE NOW TAKEN, plus two more.** The
debt list that follows is PAID — read it for the reasoning, not as work owed. versionCode 106.

1. **The TAK server configuration is on `TakServerActivity`** and Pre-Flight keeps a GENERATED
   three-line summary and a `Configure TAK Server…` button. `setupOneLock`/`applyLock` are gone
   and are now `ConfigLock`, one implementation for every screen; Pre-Flight keeps the video and
   battery locks because the controls they guard stayed. Nothing migrated — every preference key
   and every view id is the one that was already there.
   ⚠ **THE MOVE TOOK A RECONNECT WITH IT, AND THAT IS THE FAULT TO REMEMBER.** Pre-Flight's
   `onCreate` reconnected from saved certs on the way past, and that branch left with the fields.
   `TakAutoConnect.retryIfDown` now runs on the RESUME of the home screen AND of Pre-Flight, with
   no once-per-process latch: **tie a retry to a SCREEN BEING SHOWN, not to a process starting** —
   the foreground service keeps this process alive across a swipe-away, so "relaunching the app"
   is often not a new process at all. `reconnect()` gained an in-flight guard and a `try/catch`
   because a resume can now fire it.
2. **The Elevated account's channels are WRITABLE on both screens**, behind the same lock as the
   Standard rows, and `connectVideoChannel` passes `acceptInbound = true` at both call sites.
   ⚠ A tick is also the ONLY "ignore incoming" control that can exist — inbound CoT carries no
   channel label — and it changes the WHOLE FLEET, because activebits belongs to the shared
   account. ⚠ **NOT unticked on a live server.** It would take video from the fleet.
3. **The flight screen's TAK Channels dialog matches the sibling's wording**: the "The TAK server
   holds these channels" line is gone from it (the fact is on the TAK Server screen and in the
   Field Guide), the Elevated heading is just "Elevated account (video)", and its rows are ticks.
   ⚠ The sibling's TYPE SCALING did not transfer — it shrank its text for a 768dp panel; this
   controller is 1024x720dp and the dialog fits at 13sp. Specification §4.2 makes the rule the
   MUST and the numbers per-device.
4. ⚠ **THE SPI STALES OUT, AND THIS APPLICATION DOES NOT DELETE IT** (operator, 2026-10-09:
   "I dont need the controller telling the TAK Server to delete the SPI, I just want it to stale
   out and disappear per EuD retention"). The report was that the camera point did not go away.
   A `t-x-d-d` delete was BUILT, bench-proved on the controller — edge-triggered correctly, one
   delete per transition, both reasons firing — and then REMOVED. **It worked and it was still
   the wrong shape**: how long a point survives after it stops being refreshed is the RECEIVING
   CLIENT'S retention policy, and an aircraft that reaches into every EUD on the net and removes
   a map item is a far larger hammer than the problem. Do not rebuild it.
   ⚠ **WHAT THIS APPLICATION OWES A RECEIVER IS TWO THINGS AND IT ALREADY DID BOTH** — confirmed
   on the wire from the bench capture, not inferred: `start` and `stale` exactly 15 s apart,
   `time` == `start`, and no `<archived/>` anywhere in the event. Nothing sent asks for the point
   to be kept. `SensorPointRetentionTest` pins both, and fails if a delete builder reappears; the
   reasoning is on `CotBuilder.SENSOR_POINT_STALE_MS`.
   ⚠ **PUBLISHING ALREADY STOPPED ON EVERY PATH THAT MATTERS** and none of that changed: above
   the horizon, no GPS fix, telemetry quiet (5 s), look-point off. A client still showing the
   point after its stale has passed is applying its own retention, and that is settled there.
   ⚠ **THE BENCH RUN THAT KILLED IT IS WORTH KEEPING.** Hung off `TELEMETRY_FRESH_MS` (5 s), the
   delete fired on every RF dropout — this airframe drops its link for seconds at a time — so the
   camera point blinked off every screen and came back. Raising the threshold to the stale window
   fixed the flicker, and then the feature went anyway. A correct implementation of the wrong
   idea.

5. **The CoT can advertise an SRT READ, and ATAK plays it.** The recipe is
   `../../../srt-cot-video-advertising.md`, recorded that day against a live server and real
   clients — read it before touching this.
   ⚠ **THE ONE FACT: `ConnectionEntry.path` carries the WHOLE query string**, leading `?`
   included. ATAK does not read `url` for SRT; it rebuilds the connection from `ConnectionEntry`
   and matches on the literal `?streamid=` text. `getRawQuery`, not `getQuery` — the stream id
   must reach ATAK byte for byte. ⚠ **RTSP is deliberately untouched**: its `?tcp` has never been
   part of its path and both clients have played that form for years.
   ⚠ **THE READ LEG IS A SEPARATE CHOICE FROM THE PUBLISH LEG AND RTSP STAYS THE DEFAULT.** TAK
   Aware cannot play SRT at all (no SRT module in its bundled MobileVLCKit — a vendor build
   issue), so moving a mixed fleet to SRT advertises an address its viewers fail to open, which
   reads as a dead feed. ⚠ `srtReadPassphrase` is NOT `srtPublishPassphrase`; the read one is the
   one secret that must appear in a url, and the preview masks it. ⚠ No dangling
   `&passphrase=`, and no trailing colons on a path with no authentication.
   The Video Servers screen took the sibling's terminology and ordering: TAK Advertisement Server,
   Video Publish Protocol, TAK Advertisement Protocol, CoT Advertisement Address, Publish Address.
   ✅ **BENCH-PROVED FROM THIS APPLICATION, 2026-10-09 evening.** A real ATAK on a Pixel 10a
   parsed the CoT and reached the SRT handshake. Its own log shows every field arriving intact:
   `ConnectionEntry [address=anchortak.link, port=8890, protocol=srt,
   path=?streamid=read:<path>:<user>:<pass>&passphrase=<secret>, networkTimeout=12000]`, and
   ATAK then appends its own `&timeout=12000000` from `networkTimeout`. The one failure was the
   operator mistyping the read passphrase into the field (`TentCoty` for `TentCity`), which
   MediaMTX refused with `closed: invalid passphrase` — downstream of everything this
   application builds.
   ⚠ **A MASKED FIELD HIDES A TYPO AND ONLY A FAILED CONNECTION REVEALS IT.** The read
   passphrase is `inputType="textPassword"` and the preview masks it to `***` by the rule in
   `urlSafe` — correct for a secret read over a pilot's shoulder, and it cost an evening here.
   If this recurs, the fix is a show/hide control on that one field, not unmasking the preview.

**2026-10-09, FLOWN on vc106 — THE FIRST H.265 FLIGHT, and a torn picture at the far end.**
The finding is `SRT-UPLINK-FINDING-2026-10-09.md`; read it before tuning anything about video.
In one paragraph: the operator saw green blocks in a MediaMTX recording and asked whether the
transcoding caused them. There is no transcoding. The uplink lost video — SRT retransmitted,
`wire` climbed to two orders of magnitude above `payload`, the send queue overflowed and 86
frames were never sent. The earlier clip is the OTHER failure mode, packets lost beyond SRT's
500 ms budget. `countFrame`'s own comment names both and `drops` is the discriminator.

⚠ **H.265 IS NOT IMPLICATED AND THE ENCODER TOOK THE TOP RUNG.** The log line reads
`full (profile+level, VBR, max-fps) + intra-refresh` on `OMX.qcom.video.encoder.hevc`, 1024x768
@ 15 fps, and the payload held at 700-1000 kbps through both bad patches. The H.265 question
from 2026-09-12 — a fleet-readiness decision, not a code one — is unchanged by this.

⚠ **THE PILOT'S SCREEN CANNOT REPORT WHAT THE TEAM RECEIVES**, and that is permanent: the
controller decodes the aircraft's DOWNLINK, which is upstream of every uplink fault. The same
point is in `VIDEO-STREAM-VBR-FIX.md` from August. Judge the outgoing stream by the `link [...]`
line in `app.log` or by the far end, never by the picture in front of the pilot.

⚠ **THE 1 s FOLLOW-UP IS NOT A RESULT YET.** The operator raised the SRT latency to 1 s, went to
1440x1080 at 1800 kbps and saw no tearing — but 3.3 minutes on the ground, at `wire/payload`
1.10x mean, is statistically identical to the FIRST TEN MINUTES of the flight that tore, which
ran at 1.13x before the storm. Three variables moved together (latency, bitrate, radio
conditions) and none is controlled. Do not record this as fixed; fly the same route and watch
`wire` against `payload`. ⚠ **THE FLEET DEFAULT MOVED TO 1000 ms** (v2.4.0, re-cut as versionCode 110) on the operator's
decision — see `VideoTransport.SRT_LATENCY_DEFAULT_MS`, which carries both the 2026-08-29 ground
test that chose 500 and this flight that found it short, and says which part is judgement. A
controller with a Debug-screen override does NOT move; the default applies only when that box is
empty or out of range.

⚠ **DO NOT QUOTE THE 85 Mbps `wire` FIGURE.** It is not physically possible on this uplink. The
counter is the library's own and double-counts a requeued packet; the SHAPE tracks `drops`
exactly, the absolute number means nothing. Use it as a ratio against `payload`.

Also flown that day: `TakAutoConnect`'s new in-flight guard earned its place — two resumes fired
in the same millisecond and the log reads `retrying the connection` / `reconnect already in
flight — ignoring this request` / one `Auto-connected`.

⚠ **THE SHARED CORE MOVED FROM THIS TREE, which is the reference tree.** `check-taklite.sh` says
Autel CONFORMS. Nothing is owed to DJIv5 from the SPI item — the delete was removed before it
reached that tree. Its `CotBuilder` is reported DRIFTED on comments alone (its own stale-time
note, and the retention note added here); its `TakManager` matches its pinned waiver again.

**2026-10-09: THIS TREE OWED THE DJIv5 TREE TWO CHANGES, and MSDKv4 is frozen.**

The DJI MSDKv5 tree took the video split from here on 2026-10-09 and then led on two changes of
its own, with the operator's agreement. Specification §8 rule 1 allows one live tree to lead;
what it requires is that the debt is written down when it is incurred. This is that record. ⚠ It
is NOT a list of things that happened here — nothing below is in this tree yet.

1. **The Elevated account's channels are WRITABLE there, and its connection ACCEPTS inbound.**
   Writable on Pre-Flight and in the flight screen's channel dialog, behind the same lock as the
   Standard rows. ⚠ A tick is also the only "ignore incoming" control that can exist: inbound
   CoT carries no channel label (§1-3 of `CHANNELS-FINDINGS.md`), so unticking is what stops
   traffic arriving, at the server. ⚠ It changes the WHOLE FLEET — activebits belongs to the
   shared account, not to one controller.
2. **The TAK server configuration moved off Pre-Flight into its own screen** (`TakServerActivity`
   there), the same move `VideoServersActivity` made for video on 2026-08-30. Pre-Flight keeps a
   generated summary — which user, which channels are active, where the video link goes — and a
   button. Their `setupOneLock`/`applyLock` became a shared `ConfigLock` in the same change.

⚠ **PART 1 IS NOW TAKEN HERE.** `TakManager.connectVideoChannel` takes an `acceptInbound` flag
and the six-argument form still DISCARDS — that default exists so a decision taken on the DJI
bench could not change the wire behaviour of this tree by accident. **Both of this tree's call
sites now pass `true`, deliberately** (`TakServerActivity.connectVideoChannelWithCerts` and
`TakAutoConnect.reconnect`), because the writable tick means nothing without it.

⚠ **MSDKv4 IS FROZEN** (operator). The specification now covers the two live applications, this
one and MSDKv5. `check-taklite.sh` reports that tree's drift and never fails on it.
