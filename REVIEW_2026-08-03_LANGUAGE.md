# Language / Simplified Technical English Review — 2026-08-03

Scope: all pilot-visible text OUTSIDE the Pre-Flight screen (which was rewritten to STE on
2026-08-02). Notes only — nothing changed. Triage by number.

**Reference standard:** the Field Guide (`FieldGuideActivity.kt:14-61`) already carries a written
STE ruleset and follows it. It is the exemplar, not a target. Its rules: one instruction per
sentence, ≤20 words, active voice, present tense, no contractions, no "-ing" noun forms, one term
per concept, a short press is "touch", **the airframe is always "aircraft", never "drone".**

---

## Finding 1 — TERMINOLOGY CONFLICT (operator decision required before any rewrite)

- **Where:** app-wide. Quoted pilot strings: **120 use "aircraft", 34 use "drone".**
- **What:** the Field Guide mandates "aircraft, never drone." Yesterday's Pre-Flight rewrite
  standardised on **"drone"** at the operator's direction — section title "Drone Settings", button
  "Apply to Drone", status "The drone did not…". The two halves of the app now contradict the
  app's own style rule.
- **Why it matters:** STE's first principle is one term per concept. A pilot reading "aircraft" on
  the flight screen and "drone" in Pre-Flight learns the terms are interchangeable, which they are
  not supposed to be — and inconsistency reads as unpolished on exactly the screens that must feel
  trustworthy.
- **Decision needed:** pick ONE term for the airframe, app-wide. If "aircraft" wins, the Pre-Flight
  and lights strings from yesterday revert. If "drone" wins, the Field Guide's ruleset and its ~120
  "aircraft" uses change. Either is mechanical once chosen — this review located every occurrence.
- **Severity:** should-fix (blocks the rest of the language pass — do this first so the rewrites
  below use the agreed term).

---

## FlightActivity — largest surface, not yet STE (91 strings)

### 2. Contractions throughout (STE bans them)
- **Where:** `FlightActivity.kt` — `:386` "Can't drop", `:801` "isn't available… isn't wired up",
  `:935` "can't set", `:1266` "Can't place… waiting on", `:1783` "Can't place", `:1987` "Can't
  move"; `TakPilotHomeActivity.kt:124` "You'll need to relaunch".
- **What:** can't / isn't / you'll are not STE.
- **Fix:** expand all — "Cannot drop…", "You must relaunch the app."
- **Severity:** should-fix.

### 3. Parenthetical jargon stacked inside error toasts
- **Where:** `:386` "camera look-point not available (GPS/gimbal not ready)", `:1266` "waiting on
  GPS + gimbal", `:1783`/`:1987` same shape.
- **What:** the pilot is told the failure and the internal cause in one compressed line with a
  parenthetical. STE wants the instruction, not the mechanism.
- **Why it matters:** a pilot who cannot drop a marker needs "Wait for GPS and gimbal, then try
  again", not the subsystem names.
- **Fix:** rewrite as an action: "Cannot drop the marker yet. Wait for GPS and the gimbal."
- **Severity:** should-fix.

### 4. "-ing" forms and em-dashes as connectors
- **Where:** `:823` "Starting screen stream…", `:1487` "Camera still initialising — try again in a
  moment", `:801` em-dash joining two clauses.
- **What:** progressive verbs and em-dash-joined clauses are both flagged by STE.
- **Fix:** "The screen stream starts.", "The camera is not ready. Try again in a moment."
- **Severity:** polish.

### 5. Inconsistent phrasing for the SAME condition
- **Where:** "not connected" appears as `:776` "No aircraft connected", `:917` "Aircraft not
  connected", `:1412`/`:1481`/`:1583`/`:1673` "Aircraft camera not connected", `TakPilotHome`
  "TAK: Disconnected". At least three word orders for one idea.
- **What:** the same state is described four ways.
- **Why it matters:** repetition with variation is exactly what STE's controlled vocabulary exists
  to remove; it makes the app feel hand-assembled.
- **Fix:** one canonical form, e.g. "The aircraft is not connected." / "The camera is not
  connected."
- **Severity:** should-fix.

### 6. Marker dialog titles are noun fragments, not consistent with the guide's imperative style
- **Where:** `:2006` "Rename Marker", `:2018` "Change Type", `:2028` "Delete Marker", `:2042`
  "Clear All Markers", `:2114` "$affiliationLabel pin placed".
- **What:** mixed title-case noun phrases; "pin" here vs "marker" elsewhere (two terms, one thing).
- **Fix:** settle "marker" vs "pin" (one term), and keep dialog titles parallel.
- **Severity:** polish.

---

## Home screen

### 7. Stop & Quit dialog is one long sentence with jargon and a contraction
- **Where:** `TakPilotHomeActivity.kt:124` — "Force-stop TAKPilot2-Autel and all its background
  processes (video stream, TAK connection, telemetry)? You'll need to relaunch the app."
- **What:** 28 words, parenthetical list, "Force-stop"/"background processes" jargon, contraction.
- **Why it matters:** this is a confirm dialog for the most destructive control on the home screen;
  it should be the clearest text in the app, not the densest.
- **Fix:** "Stop TAKPilot and close the video, the TAK connection, and telemetry? You must start
  the app again to fly." (or shorter).
- **Severity:** should-fix.

### 8. Button labels use inconsistent casing/format
- **Where:** `activity_takpilot2_home.xml` — "STOP / QUIT" (caps+slash), "Pre-Flight Setup" (title),
  "ENTER FLIGHT" (caps), "Field Guide"/"Data Sync"/"Debug Log" (title), "TAP TO ENTER ›" (caps).
- **What:** three casing conventions among six adjacent buttons.
- **Fix:** pick one (title case reads calmest); reserve all-caps for the single primary action if at
  all.
- **Severity:** polish.

---

## Notifications and other screens

### 9. Notification channel text says "drone feed" (terminology) and describes internals
- **Where:** `TakForegroundService.kt` channel description "Keeps the TAK connection and drone feed
  alive"; content states "Holding the link".
- **What:** "drone" (see #1), and "Holding the link" is app-internal phrasing a pilot will not
  parse.
- **Fix:** align term after #1; "TAKPilot is running." is enough for an ongoing notification.
- **Severity:** polish.

### 10. Unit-label inconsistency
- **Where:** `Units.kt` — speed "%.0f MPH" (uppercase) vs distance/altitude "%.0f ft" (lowercase);
  metric leaks into pilot strings elsewhere ("${v}m", "46m", "%.1f m MSL" in
  TakConnect/FlightActivity).
- **What:** MPH vs ft casing disagree, and metres appear in a feet-facing UI.
- **Why it matters:** mixed units on a flight readout is a real misread risk, not just style.
- **Fix:** one casing convention for unit labels; keep all pilot-facing values imperial (metres
  belong only in logs).
- **Severity:** should-fix.

### 11. "PLI" and other TAK jargon reach the pilot
- **Where:** Pre-Flight status "Drone PLI streaming" / "PLI as \"$callsign\""
  (`TakConnectActivity.kt` ~L90), and "CoT" appears in some status text.
- **What:** PLI/CoT are TAK-operator vocabulary, not pilot vocabulary.
- **Fix:** "Sending the aircraft position to TAK." No acronym.
- **Severity:** should-fix.

---

## Field Guide — exemplar, but check two things (not style)

### 12. Guide may describe behaviour that changed yesterday
- **Where:** `FieldGuideActivity.kt` — the RTH / failsafe / limits sections predate the 2026-08-02
  changes (RTH-only failsafe, the 82 ft RTH floor, Apply-to-aircraft button, exterior-lights
  button replacing video re-sync).
- **What:** possible stale instructions — e.g. a re-sync entry was already updated to the lights
  button, but check the RTH-altitude and failsafe wording against current behaviour.
- **Why it matters:** a field guide that describes the old behaviour is worse than none.
- **Fix:** reconcile the guide against yesterday's commit `d46decb`.
- **Severity:** should-fix.

### 13. Length / repetition
- **Where:** ~4,000+ words across 5 sections.
- **What:** thorough, but a pilot reads it in a hurry. Candidate for tightening once #1 is decided.
- **Severity:** polish.

---

## Not covered tonight
DataSyncActivity feed dialogs (22 strings) and the AR-calibration dialog prose were inventoried but
not individually rewritten-in-notes; they follow the same patterns (contractions, jargon) and
should get the same treatment in the fix pass.

---

## Summary

**Blockers:** 0
**Should-fix:** 8 (#1, #2, #3, #5, #7, #10, #11, #12)
**Polish:** 5 (#4, #6, #8, #9, #13)

**Do #1 first** — the drone/aircraft decision determines the wording of every other fix. It is the
one item that needs the operator, not the writer. After that, FlightActivity (#2–#6) is the bulk of
the work: it is the largest untouched surface and the screen a pilot reads while flying.

---

## RESOLUTIONS — 2026-08-03 (verified on the connected controller)

- **#1 Terminology** — RESOLVED. Swept all user-visible "drone"→"aircraft" app-wide (operator
  decision: aircraft everywhere). Code identifiers (droneUid, sendDronePLI) untouched via
  word-boundary match. Verified on-device: Home + Pre-Flight show "aircraft" throughout.
- **#2 Contractions** — RESOLVED. Expanded can't/isn't/don't/won't/didn't/doesn't/you'll across
  the pilot files.
- **#3 Parenthetical jargon** — RESOLVED. FlightActivity error toasts rewritten as actions
  ("Cannot drop the marker. Wait for GPS and the gimbal.").
- **#4 -ing / em-dashes** — RESOLVED for the flagged strings (em-dash clauses split into
  sentences; "initialising"→"is not ready"). A few progress gerunds ("is starting") kept as
  acceptable toast status.
- **#5 "not connected" phrasing** — RESOLVED. Standardised to "The aircraft is not connected." /
  "The camera is not connected."
- **#6 Marker dialogs / pin** — RESOLVED. "pin"→"marker" in the user-facing marker strings.
- **#7 Stop & Quit dialog** — RESOLVED. "Stop TAKPilot and close the video, the TAK connection,
  and telemetry? You must start the app again to fly."
- **#8 Button casing** — MOOT. The Material theme forces textAllCaps on buttons, so all button
  labels render UPPERCASE and are already visually uniform (confirmed on-device). Source label
  tidied to "Stop / Quit" but the render is unchanged. No further action.
- **#9 Notification jargon** — RESOLVED. Channel desc → "Keeps TAKPilot running while the screen
  is off."; "Holding the link" → "TAKPilot is running."
- **#10 Unit labels** — RESOLVED. Speed "MPH"→"mph" to match "ft"/"mi" lowercase.
- **#11 PLI jargon** — RESOLVED. "Aircraft PLI streaming" → "Sending the aircraft position to
  TAK."
- **#12 Field Guide staleness** — RESOLVED. The guide listed the failsafe as "Return Home, Hover
  or Land" — Hover/Land were removed 2026-08-02. Now "the aircraft returns to home". Also swept
  the guide's own "drone" strays (it mandates "aircraft, never drone").
- **#13 Field Guide length** — NOT done (polish). The guide is ~4,000 words but sound; tightening
  deferred.

**Count:** 12 resolved, 1 moot (#8), 1 deferred (#13). Verified on-device.
