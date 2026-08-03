# TAKPilot2-Autel — Flight-Test Checklist

Purpose: close the Phase 4/5 items that can only be verified with the **aircraft airborne** — the
calibration constants (gimbal, bearing, FOV, GPS units, HAE), the second-TAK-client picture (PLI/SPI
symbol, callsign), and the recovery paths (RTH, link-loss, backgrounding, RC buttons).

Each test says what to **do**, what to **observe**, the **pass** bar, and — if it fails — the exact
**knob** to change. Most knobs are calibratable in-flight: **long-press (touch and hold) the AR
button** on the flight screen to open a menu with **"Aim Offsets…"** (bearing + pitch bias) and
**"Calibrate FOV…"** — both persist, no rebuild. The few knobs that need a rebuild are called out. A
quick-reference table is at the end.

**Do the whole thing with logging on.** Debug Log → *Logging enabled* + *Detailed*. Leave *Include
obstacle radar logs* OFF (it floods the log) unless you are specifically re-checking avoidance. The
SPI bearing candidates, gimbal angles and GPS accuracy all land in this log for post-flight review.

**Two things you need running:** the aircraft on the Smart Controller, and a **second TAK client**
(ATAK on a phone, or a WinTAK) logged into the same server so you can see what the team sees.

> Safety first. Fly in open area, VLOS, within the limits set in Pre-Flight. The RTH/link-loss tests
> put the aircraft into automatic modes — have the sticks ready to retake control. Nothing here
> overrides your judgement as PIC.

---

## Results — Flight 1 (2026-08-03)

- **G1–G6: all pass.**
- **G6 (GPS units) validated:** raw `GPSInfo` showed 31 sats, 3D fix, HorizontalAccuracy ≈ 0.7 m
  → `ACC_DIVISOR=1000` (mm) is correct. Settled.
- **A4: PASS — absolute model confirmed.** A fixed aim offset stayed correct through a 180° then a
  further 90° yaw, so the bearing is heading-independent → `BEARING_MODE_RELATIVE=false` is right,
  **no rebuild**. Calibrated **aim offsets for this airframe: Pitch −1.0°, Bearing −3.75°** (held on
  the device in prefs; code defaults stay 0.0 because offsets are per-airframe — re-run per aircraft).
- **A3 (pitch sign) looks correct** in passing (down = negative, SPI in front/below).
- **Best-found calibration method** (now documented in-app, Field Guide §4): aim the reticle at a
  known landmark, then adjust Aim Offsets while watching the live **`-SPI`** point converge on the
  target on a **second TAK client** — faster than drop-marker-and-compare.
- **Noted, aircraft-side (not the app):** a slow hover yaw/wander of a few degrees. Raw GPS showed
  good position (sub-metre, 31 sats) but a **noisy velocity solution** (GPSSpeed spiking ~1 m/s while
  the aircraft barely moved) — classic **multipath** near structures, which unsettles the
  position-hold loop. The app faithfully reported the stable position throughout (SPI stayed put
  while the airframe drifted). Retest position hold in the open, clear of buildings.

Still open from this flight: A5 (FOV), A7 (IR), and the R-series recovery tests.

---

## Before you fly — ground checks (aircraft powered, on the ground)

- [ ] **G1 · Launch the right way.** Open TAKPilot from the controller **home screen / launcher**, not
      by any shortcut into the flight screen. (A direct flight-screen entry comes up with a dead
      link — this is a hard rule.) Confirm the home screen shows **TAK: Connected** and **AIRCRAFT:
      EVO_2**.

- [ ] **G2 · Your callsign, not your username.** On the second TAK client, open the connected-users /
      contacts list.
      **Pass:** your **callsign** (the one set in Pre-Flight) appears — not your login username.
      **If wrong:** callsign source is `TakForegroundService.callsignFor` / the `takpilot2_tak`
      pref `callsign`; check Pre-Flight saved it.

- [ ] **G3 · Drone symbol on the map.** Confirm a **drone/air track** marker for the aircraft appears
      on the second client and is symbolized as an air asset (not a ground dot).
      **Pass:** it renders as an aircraft symbol. Driven by CoT type `a-f-A-M-H-Q`
      (`CotBuilder.java:20`, `DRONE_TYPE`). **If it's a ground symbol:** confirm that type is what's
      being emitted (bench §11.1).

- [ ] **G4 · Limits applied and read back.** In Pre-Flight, set **RTH altitude** (min is **82 ft /
      25 m** — the aircraft floor; below that is refused), **max altitude**, **max distance**, then
      **Apply to Drone**.
      **Pass:** the read-back values match what you entered, and the flight HUD shows the RTH
      altitude (`RTH ___ ft`, not amber `RTH --`).

- [ ] **G5 · Go dark.** Toggle the exterior-lights button off, then on.
      **Pass:** the aircraft's navigation lights actually extinguish and relight. (Operator-verified
      already — this is a spot recheck. Note: only nav lights are controllable; an auxiliary LED is
      read-only.)

- [ ] **G6 · GPS accuracy sanity (units).** With a good fix, read the GPS accuracy on the HUD.
      **Pass:** a plausible **metres** value (roughly 0.3–5 m outdoors). **If it reads absurd**
      (thousands, or ~0.00x): the raw units aren't millimetres — change **`ACC_DIVISOR`**
      (`AutelTakBridge.kt:574`, currently `1000.0`). Rebuild required.

---

## Airborne — calibration

Take off to a stable **~10–20 ft hover** first and confirm the basics before climbing.

- [ ] **A1 · Live PLI.** Watch the drone marker on the second client while you reposition.
      **Pass:** its position tracks the aircraft smoothly at the ~2 s PLI cadence; HUD AGL and the
      map agree.

- [ ] **A2 · HAE altitude spot-check.** At a known/surveyed spot (or against a GPS app reading HAE),
      compare the drone marker's **`hae`** on the second client to the reference.
      **Pass:** within a few metres. Source is `EvoGpsInfo.getAltitude()` (`AutelTakBridge.kt:59`).
      Note HUD **AGL** and logged **relAlt** differ from HAE/MSL by the terrain offset — compare like
      with like.

- [ ] **A3 · Gimbal pitch direction.** Tilt the gimbal fully **down (−90°)**, then **level (0°)**.
      **Pass:** HUD gimbal pitch reads **negative looking down**, ~0 level; and the SPI/AR aim point
      on the second client sits **out in front of / below** the aircraft (not behind it) as you tilt.
      **If the SPI is vertically off but the HUD pitch is correct:** open **long-press AR → Aim
      Offsets…** and nudge the **pitch** value (best judged at ~**25° down**, where a bias shows most).
      **Do NOT flip `PITCH_SIGN`** (`AutelTakBridge.kt:591`) — it would invert SPI/AR while leaving the
      HUD right, so the two silently disagree.

- [ ] **A4 · Gimbal bearing (the big one).** Put the aircraft on a **known heading** (e.g. nose
      north) and aim the camera at a **landmark whose bearing you know**, gimbal ~**25° down**.
      **Pass:** the sensor cone / SPI on the second client points at that landmark. Every SPI push
      logs **both candidate bearings** in one line — `az=` (the value being published, **absolute**
      model) and `headingPlusYaw=` (the **body-relative** model). Read which one equals the real
      landmark bearing:
        - **`az=` matches** → keep **`BEARING_MODE_RELATIVE=false`** (`AutelTakBridge.kt:581`) and fine-
          tune bearing via **long-press AR → Aim Offsets…** (no rebuild).
        - **`headingPlusYaw=` matches** → set **`BEARING_MODE_RELATIVE=true`** (rebuild), then fine-tune
          the offset the same way.
      Record the offset that lands it.

- [ ] **A5 · FOV cone.** Frame two landmarks at the **left and right edges** of the video. Compare the
      published `<sensor>` cone drawn in ATAK against what's actually at the frame edges.
      **Pass:** the cone edges line up with the frame edges. **If not:** open **long-press AR →
      Calibrate FOV…** and adjust (seeds from `EO_HFOV=79 / EO_VFOV=62`, `AutelTakBridge.kt:599`; no
      rebuild). Then **zoom to 2×/4×** and confirm the cone narrows correctly (it should follow the
      true-perspective curve, not shrink linearly). Note: Aim Offsets fixes markers wrong in the
      **centre**; Calibrate FOV fixes markers wrong at the **edges** — don't reach for one to fix the
      other.

- [ ] **A6 · Video in flight.** With the LIVE stream up, confirm on a viewer/media server that the
      **screen mirror** (FPV + HUD + map) is live and stays up through a few maneuvers and a lens
      switch. (Bench-verified already; this confirms it survives real flight.)

- [ ] **A7 · IR lens.** Switch to **IR**. Confirm the feed switches and the cone changes to the IR
      FOV. **If the IR cone is off:** `IR_HFOV=42 / IR_VFOV=34` (`AutelTakBridge.kt:600`) — a plain
      constant, rebuild to change (full IR calibration is Phase 3).

---

## Airborne — recovery & robustness

- [ ] **R1 · RTH end-to-end.** From a safe distance/altitude, command **Return to Home**.
      **Pass:** the aircraft returns and climbs to/holds the **commanded RTH altitude** (matches the
      HUD). Watch for any "process timed out" on the command (the DJI sibling hit a real RTH-ack bug
      in the field — flag if it recurs).

- [ ] **R2 · Link-loss failsafe.** In open area, briefly induce a controller-link drop.
      **Pass:** the aircraft runs **its own** failsafe — ~10 s hover, then climb, then return. (The
      app deliberately can't set/read the emergency action, so the picker is RTH-only; you're
      verifying the **aircraft's** behaviour, not app control. Sticks stay live — retake anytime.)

- [ ] **R3 · Background / screen-lock recovery.** Mid-hover, lock the screen (or background the app)
      for ~30 s, then return.
      **Pass:** TAK stays connected and **PLI keeps flowing** on the second client throughout (the
      foreground service holds it), video resumes, HUD picks back up.

- [ ] **R4 · Network blip (if TAK rides a hotspot).** Briefly drop/restore the network carrying TAK.
      **Pass:** TAK **auto-reconnects** without dropping the aircraft link or needing an app restart.

- [ ] **R5 · RC button keycodes (data-gathering, not a pass/fail).** Press each mappable **Smart
      Controller V3** button.
      **Do:** watch the Debug log for `onKeyDown` keycodes. Physical-button mapping is unported —
      this flight just **records the keycodes** so they can be wired up later. Note which physical
      button prints which code.

- [ ] **R6 · (Opportunistic) OOM restart.** Not deliberately inducible, but if the app is ever killed
      for memory in flight: it should **relaunch to the Home screen** (re-arming the link), not a
      frozen flight screen. If you see a flight screen with a dead/stale HUD after a restart, capture
      the log.

---

## After you land

- [ ] Debug Log → **Export** (also auto-archived to `Downloads/TAKPilot2 Logs`). Keep it — the SPI
      bearing candidates (A4), gimbal angles (A3), FOV and GPS accuracy (A5/G6) are all in it, which
      is what turns "looked about right" into a settled constant.
- [ ] Record the final values you landed on (bearing offset, pitch offset, EO FOV) so they can be
      baked in as the new defaults.

---

## Quick-reference: symptom → knob

| Symptom in flight | Knob | Where | Rebuild? |
|---|---|---|---|
| SPI/cone points at wrong compass bearing | Aim Offsets bearing (fine) / `BEARING_MODE_RELATIVE` (frame) | long-press AR → Aim Offsets… / `AutelTakBridge.kt:581` | offset no / mode yes |
| SPI vertically off, HUD pitch OK | Aim Offsets pitch | long-press AR → Aim Offsets… | no |
| HUD gimbal pitch itself inverted | (ingest normalisation — investigate, **not** `PITCH_SIGN`) | `AutelTakBridge.kt` setAngleListener | — |
| Cone wider/narrower than the view (EO) | Calibrate FOV | long-press AR → Calibrate FOV… | no |
| Cone wrong on IR | `IR_HFOV` / `IR_VFOV` | `AutelTakBridge.kt:600` | yes |
| GPS accuracy reads absurd | `ACC_DIVISOR` | `AutelTakBridge.kt:574` | yes |
| `hae` off by a constant | verify `EvoGpsInfo.getAltitude()` is HAE | `AutelTakBridge.kt:59` | — |
| Drone shows as ground symbol | `DRONE_TYPE` (`a-f-A-M-H-Q`) | `CotBuilder.java:20` | yes |
| RTH altitude won't go below ~82 ft | aircraft floor (25 m), not a bug | — | — |

## What this checklist does NOT need to cover (already settled)

Obstacle avoidance braking (flight-verified 2026-08-02), RTH altitude set/read/flown at 200 ft,
exterior "go dark", the video codec-listener/`AutelCodecView` concurrency question (now N/A — screen
capture, no second tap), and RF power (a regulatory refusal fixed in Explorer's region settings, not
the app).
