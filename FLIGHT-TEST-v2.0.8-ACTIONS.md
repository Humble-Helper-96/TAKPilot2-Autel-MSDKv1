# TAKPilot2-Autel v2.0.8 — flight-test action list

**Written in Simplified Technical English (ASD-STE100).**

**Build: versionName 2.0.8, versionCode 77.** Read the version on the home screen first. If it
does not say 2.0.8, stop.

This clears EIGHT builds — 2.0.1 to 2.0.8 — none of which has flown. Write the clock time
against each action. A log that cannot be matched to an action tells you much less.

---

## Before you start

- [ ] **B1.** SD card in the aircraft.
- [ ] **B2.** Debug → logging **ON**. Leave the TAK filter on.
- [ ] **B3.** Touch **Clear**.
- [ ] **B4.** Write the clock time now.

---

## Part G — on the ground, two minutes

- [ ] **G1.** Press the **hardware shutter** one time. The camera symbol must change to a still
      camera and say PHOTO, and the screen must say "The camera is now in photo".
- [ ] **G2.** Press the **hardware record button** ONE time. It must start to record. Watch the
      REC pill go red. ⚠ Do NOT press it a second time — the second press stops it.
- [ ] **G3.** Press the **hardware record button** again to stop.
- [ ] **G4.** Push the **zoom rocker** in and out. It must answer at once.
- [ ] **G5.** Press **C2**. A quick marker must go to your team.

> **G4 and G5 are the cold-start test.** They fail if the buttons did not arm. If they do not
> answer, tell me BEFORE you fly — do not leave the screen and come back, because that hides
> the fault.

---

## Part A — airborne

**A1 to A3 are the new obstacle display. This is the one that needs air.**

- [ ] **A1.** At a safe height, fly SLOWLY toward a large obstacle — a wall, a building face, a
      line of trees. Stop at about 30 ft. Watch the wash come in from the edge.
- [ ] **A2.** Continue slowly to about 15 ft, then to about 8 ft. Stop.
      **Write down whether the wash tells you the rate you are closing.** That is the whole
      question. Before today the display went almost flat inside 13 ft.
- [ ] **A3.** Back away. Then approach a second obstacle from a DIFFERENT side, so a second
      edge lights. Write down whether you can tell the two sides apart at a glance.

**The home point. This is the last untested claim in 2.0.1.**

⚠ **This changes where Return to Home sends the aircraft. Read A4 to A7 before you start.**

- [ ] **A4.** Fly the aircraft **at least 50 m horizontally away from where you stand**. Hold it.
- [ ] **A5.** Touch and hold **RTH**. Confirm **Set Home Here**. This sets home to YOUR position.
- [ ] **A6.** Write down what the screen said and how long after you confirmed. "Home Point
      Updated" must come from the AIRCRAFT, not at once.
- [ ] **A7.** ⚠ Set the home point again where you want RTH to go, before you land.

**The rest**

- [ ] **A8.** Touch **IR**. Wait **5 seconds**. Touch **IR** again. Wait **5 seconds**.
- [ ] **A9.** Start **REC**. While recording, touch **IR** one time — it must refuse in amber.
      Stop REC. Wait **10 seconds**.
- [ ] **A10.** Point the camera so that a team contact or a marker is OFF the side of the frame.
      **Look for the small arrow at the very edge of the screen.** Before v2.0.5 it was drawn
      outside the screen and you could not see it.
- [ ] **A11.** Turn until that arrow is on the RIGHT, low down, where the map is. It must lift
      to just above the map, not jump inward.
- [ ] **A12.** Press the **hardware shutter** three times, three seconds apart. Write down what
      the screen said each time.
- [ ] **A13.** Zoom to 4X with the rocker. Point at a fixed object. Wait **5 seconds**.
- [ ] **A14.** Return to 1X. Land.

---

## Part L — after you land

- [ ] **L1.** Debug → **Export**. Keep the file.
- [ ] **L2.** ⚠ Copy the whole **DCIM** folder off the card. The photo checks cannot be done
      from the log alone.
- [ ] **L3.** **The cold-start test, if G4 and G5 passed only because the buttons were already
      armed:** turn the aircraft OFF. Start the application. Open the flight screen. THEN turn
      the aircraft on. Wait for it to connect. Use the zoom rocker WITHOUT leaving the screen.
- [ ] **L4.** Tell me the times you wrote down. I will pull the logs.

---

## What I will look for

You do not need to read this. It is here so you can stop early if something is clearly wrong.

| Action | What must be in the log |
|---|---|
| G1, A12 | `camera media status: PHOTO_TAKEN_DONE (…MAX_nnnn.JPG)` — count against the card |
| G2 | `START_VIDEO`, then `startRecordVideo`, then `RECORD_START` — from ONE press |
| G4, L3 | `hardware quick-marker button armed`, then `controller button event: ZOOM_IN` |
| A1–A3 | the radar samples, for the distances you flew |
| A5 | `setLocationAsHomePoint: accepted`, then `home point check: … (NN m …)` — **N must be large** |
| A8 | `lens check: the picture agrees` |
| A9 | `IR refused — the camera is recording` |
| A10, A11 | `last arrow X,Y in 2048x1536` — X must be near 2016 or near 32 |
| A13 | `zoom check: the camera is at 4X as asked` |
| — | any `E/` line. There were none last flight. |

⚠ If you see **"The camera did not change lens"**, **"The home point did not move"** or
**"The photo did not save"** at any point, write down the time. Those are the three notices that
have never fired in the field.
