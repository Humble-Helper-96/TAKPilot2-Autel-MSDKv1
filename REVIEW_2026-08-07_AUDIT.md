# Quality audit — full application, after the v1.5.9 release

**Written in Simplified Technical English (ASD-STE100).**

**Date:** 7 August 2026. **Scope:** the full TAKPilot2-Autel application (the `tak` and
`taklite` packages, layouts, and all documents), not only the v1.5.9 changes. **Baseline:**
tag `v1.5.9`, commit `2e75285`.

**Rule for this audit:** v1.5.9 is on the fleet. This audit corrects documents, comments and
records now. It does NOT change shipped behaviour. Each behaviour finding goes to the v1.6.0
list (section 4).

## 1. Method

- Android lint (vital checks, release variant).
- Manual pass over the lifecycle-sensitive files: teardown, services, receivers, listeners,
  handlers, wakelocks, threads.
- Greps for the hazard patterns this project was burned by: single-client listener slots,
  unremoved callbacks, main-thread file I/O, per-tick writes to the fly-controller channel.
- Document accuracy: each claim checked against the current code.
- STE check of all documents and of the v1.5.9 code comments.

## 2. Corrected in this audit (documents and comments only)

1. **`V1_5_9_PLAN.md`** — marked as HISTORY with the three differences between the plan and
   the released build (no-fly-zone warning is log-only; Ironbow added; flight logging
   operates without enrollment).
2. **`PORT-STATUS.md`** — was five releases stale (v1.5.4). Updated to v1.5.9; the claim
   "no warnings screen" was false since the warnings banner shipped; a one-line-per-release
   section 1a was added for v1.5.5 to v1.5.9.
3. **`TAKPilot2-Autel-HANDOFF.md`** — was seven releases stale (v1.5.2). A header note now
   directs the reader to `PORT-STATUS.md` for current state. The lessons in it stay valid.
4. **STE in the v1.5.9 code comments** — the class documentation of `FlightPathLogger`,
   `FlightWarnings`, `NetworkStatus`, the RF probe in `DebugActivity`, and
   `TakAutoConnect.startTelemetryOnlyBridge` was rewritten into STE: short sentences, active
   voice, one instruction for each sentence. Content was kept.
5. **`rf-power-investigation/RF-POWER-FINDINGS.md`** — the log-bundle table now names the two
   probe log files that were pulled (the old row said "after the probe").

## 3. Checked and found good

- **Teardown:** `AppTeardown.releaseAll` covers video, bridge (and with it the flight
  logger and operator location), TAK, SDK and the foreground service, in dependency order.
  `FlightActivity.onDestroy` removes all handler callbacks and stops the overlays.
- **Listener discipline:** no code outside `AutelTakBridge` and `AutelAvoidance` registers a
  fly-controller or visual listener. The v1.5.9 consumers (logger, warnings) are fed from
  the bridge callback, as the standing rules demand.
- **Write discipline:** no timer writes to the fly-controller channel anywhere. Limits go
  to the aircraft at connect and on an explicit button press only.
- **Threads:** the bare `Thread { }` starts (enrollment, UASFM download, auto-connect) are
  one-shot and short-lived; each posts its result to the main thread. Acceptable.
- **Preferences:** all writes use `apply()`, none block the main thread.
- **taklite:** conventional guarded socket loop, synchronized listener lists, bounded
  reconnect delay. Flight-proven; no findings.
- **Lint:** three errors, all inside stock Autel sample layouts that TAKPilot2 does not
  open (`oribit_execute_content.xml`, `activity_base_gimbal.xml`); one note that Google
  Play requires targetSdk 30 — this fleet sideloads, so it does not apply. No lint findings
  in TAKPilot2 code.

## 4. Findings for v1.6.0 (behaviour — NOT changed in this audit)

1. **`ExplorerWatchdog` survives task removal.** It has no stop function and
   `AppTeardown.releaseAll` does not stop it. After the pilot swipes the app away, the
   cached process continues to poll and continues to kill Explorer's background process.
   This is against the teardown contract's own promise ("a pilot who swipes the app away
   expects it to be closed"). Add `ExplorerWatchdog.stop()` and call it from
   `AppTeardown.releaseAll`. Risk: low. Effort: small.
2. **`FlightWarnings.reset()` does not log.** After a flight-screen re-entry the log shows
   a second "warning ACTIVE" with no "cleared" line between (seen 2026-08-07 11:45). One
   log line in `reset()` makes the pattern readable. Effort: trivial.
3. **Hardcoded colours in code.** The status screens set colours with `Color.parseColor`
   (approximately 50 call sites) although `takpilot_colors.xml` holds the same values as
   named tokens. Not a defect — the values agree — but two sources of truth. Migrate the
   code to the tokens. Effort: medium, mechanical.
4. **No cross-check between RTH altitude and max altitude.** Each limit is validated only
   against its own aircraft range. A pilot can set RTH above max. Decide the rule, then
   enforce it in Pre-Flight. Effort: small.
5. **MediaStore name collisions.** If a flight CSV or GPX name already exists,
   MediaProvider renames the new file ("name (1).csv") and the orphan sweep then does not
   match the pair. Only possible when two sessions start in the same second — rare. Make
   the sweep match by prefix. Effort: small.

## 5. STE status of the documents

All 12 repository documents carry the STE marker and follow it to a workable degree. The
new v1.5.9 code comments now follow it too (section 2.4). The pre-v1.5.9 code comments do
NOT follow STE — they are the expressive working notes of the port sessions. Rewriting all
of them would be a large diff with churn risk and no behaviour value. Recommendation:
rewrite comments to STE only when a file is next touched for real work.
