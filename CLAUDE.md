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

v1.6.1 is RELEASED; it carried the Field Guide rewrite AND the removal of channel selection.

**v2.0.0 IS RELEASED** — tag `v2.0.0`, versionCode 69, 2026-09-13. The flight-screen refresh,
finished. Basic flight test by the operator before release.

⚠ **FULL DEBUGGING IS DEFERRED, BY THE OPERATOR'S CALL.** The flight test was basic. Anything
found from here takes a SMALL update (2.0.1, 2.0.2 …), not a held release. Do not treat an
open finding as a reason to withhold the next fix.

⚠ **THERE IS NO v1.7.8 IN THE FIELD AND NO `v1.7.8` TAG.** The fleet went from v1.7.7 to
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

2. **A `PHOTO_TAKEN_DONE` IS NOT PROOF A PHOTO WAS SAVED.** When the visible capture fails the
   camera still reports DONE — with NO detail. A real capture's DONE carries a thumbnail url
   naming the file, and is followed by a SECOND, url-less DONE. Cross-referenced with the card:
   stills 0022/0023/0025 had a url and both halves are on the card; stills 0021 and 0024 had no
   url and **MAX_0021.JPG and MAX_0024.JPG do not exist**.
   ⚠ `lastPhotoDoneMs` was moved by ANY done, so a failed shutter's own failure landed ~1 ms
   later, inside `SPURIOUS_PHOTO_FAIL_WINDOW_MS`, and a REAL loss was logged as the firmware's
   harmless duplicate. Only a done that names a file counts now — see `photoDoneNamesAFile`,
   pinned by four tests against these exact cases. The pilot was also told "Photo Saved" for
   both lost photos; a real failure now shows "The photo did not save".

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
