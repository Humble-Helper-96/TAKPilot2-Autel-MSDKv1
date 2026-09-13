# TAKPilot2-Autel v2.0.1 — flight-test action list

**Written in Simplified Technical English (ASD-STE100).**

**Build: versionName 2.0.1, versionCode 70.** Read the version on the home screen before you
start. If it does not say 2.0.1, stop.

This list has one purpose: to make the debug log READABLE AFTERWARDS. Do the actions in order.
Write the clock time against each one. A log that cannot be matched to an action tells you
much less.

The general flight-test procedure is in `FLIGHT-TEST-CHECKLIST.md`. This list does NOT replace
it. This list tests the NEW function in v2.0.1 only.

---

## Before you start

- [ ] **B1.** Put the SD card in the aircraft. The card is evidence. On 12 September the logs
      alone settled none of the three faults and the card settled all three.
- [ ] **B2.** Go to Debug. Turn **logging ON**. Leave the TAK filter ON.
- [ ] **B3.** Touch **Clear**. The log starts empty.
- [ ] **B4.** Write down the clock time now. Write the time against each action below.
- [ ] **B5.** The aircraft is OFF at this point. Do not turn it on yet. Action A1 needs a cold
      start.

---

## Part A — the cold start (on the ground)

This part tests the camera-ready sequence of v1.7.2, which has never been flight tested.

- [ ] **A1.** Start the application with the aircraft OFF. Go to the flight screen. Wait there.
- [ ] **A2.** Now turn the aircraft ON. Wait for it to connect. **Do not touch the screen.**
- [ ] **A3.** Look at the IR button and the lights button. Write down what each one shows.
      The lights button must NOT stay amber after the aircraft connects.
- [ ] **A4.** Write down the zoom label.

## Part B — the checks, on the ground

The aircraft is connected. It is on the ground and the motors are off.

**The lens**

- [ ] **B6.** Touch **IR**. Wait **5 seconds**. Do not touch anything else.
- [ ] **B7.** Write down: did the picture change to thermal? What did the IR button show?
- [ ] **B8.** Touch **IR** again to go back to the normal camera. Wait **5 seconds**.

**The lens while the aircraft records**

- [ ] **B9.** Touch **REC** to start a recording. Wait 3 seconds.
- [ ] **B10.** Touch **IR**. The screen must show an amber notice: "Stop the recording to
      change the lens". Write down what you saw.
- [ ] **B11.** Touch **IR** three more times, about one second apart. The notice must come
      each time and the picture must NOT change.
- [ ] **B12.** Touch **REC** to stop. Wait **10 seconds**.
- [ ] **B13.** Touch **IR**. Now it must change to thermal. Wait 5 seconds, then touch **IR**
      again to return to the normal camera.

**The zoom**

- [ ] **B14.** Touch the zoom pill to go to 2X. Wait **3 seconds**.
- [ ] **B15.** Push the zoom rocker IN four times, one second apart. Wait **3 seconds**.
- [ ] **B16.** Hold the zoom rocker OUT until the zoom stops at 1X. Wait **3 seconds**.

**The home point — THIS MOVED TO PART C**

⚠ **THE AIRCRAFT REFUSES A HOME POINT ON THE GROUND.** Measured 2026-09-13: three attempts
with the aircraft disarmed all came back *"Cannot set the location since the home point in
disarm|landing mode"*. The fourth attempt, in the air, worked. The first version of this list
put the test here and it could not be done. See C10.

**The video**

- [ ] **B22.** Touch **LIVE** to start the stream. Wait 10 seconds. Confirm the picture on a
      TAK client.
- [ ] **B23.** Leave LIVE running for the rest of the test.

**The photo**

- [ ] **B24.** Press the **hardware shutter** five times, about three seconds apart. After each
      one, write down what the screen said: "Photo Saved", "The photo did not save", or
      nothing.

---

## Part C — airborne

Fly the aircraft normally. Add these actions.

- [ ] **C1.** At a safe height, touch **IR**. Wait **5 seconds**. Touch **IR** again. Wait
      **5 seconds**.
- [ ] **C2.** Press the **hardware shutter** three times, three seconds apart. Write down what
      the screen said each time.
- [ ] **C3.** Touch **REC**. While it records, press the hardware shutter two times. Write
      down what the screen said.
- [ ] **C4.** While it still records, touch **IR** one time. Write down what the screen said.
- [ ] **C5.** Touch **REC** to stop. Wait **10 seconds**.
- [ ] **C6.** Press the hardware shutter one time. Write down what the screen said.
- [ ] **C7.** Use the zoom rocker to go to 4X. Point at a fixed object. Wait **5 seconds**.
- [ ] **C8.** Drop a marker with C2. Confirm your team receives it.

**The home point — IN THE AIR, and this is the one test still not done**

⚠ **This changes where Return to Home sends the aircraft. Read C10 fully before you start.**

- [ ] **C10.** Fly the aircraft **at least 50 m horizontally away from where you stand**, and
      hold it there. The distance is the whole point: the check cannot tell a home point that
      moved from one that did not when the two are within 3 m, and on 13 September the aircraft
      was 0.4 m from the requested position — which proved the check RUNS but not that it can
      CATCH a home point that stayed still.
- [ ] **C11.** Touch and hold the RTH button. Confirm **Set Home Here**. This sets the home to
      YOUR position, not the aircraft's.
- [ ] **C12.** Write down what the screen said and how long after you confirmed. "Home Point
      Updated" comes from the aircraft, not from the command.
- [ ] **C13.** Look at the mini-map. The home marker must be at YOUR position.
- [ ] **C14.** ⚠ **Decide where the home point must be before you land.** Set it again at the
      position you want Return to Home to use.

- [ ] **C15.** Return to 1X. Land.

---

## Part D — after the flight

- [ ] **D1.** Go to Debug. Touch **Export**. Keep the file. A copy also goes to
      `Downloads/TAKPilot2 Logs`.
- [ ] **D2.** ⚠ **Take the SD card out of the aircraft and copy the whole DCIM folder.**
      Do not only list the file names. The photo test in B24, C2, C3 and C6 CANNOT be
      checked without the card.
- [ ] **D3.** Write down the total quantity of shutter presses you made, and how many times
      the screen said "Photo Saved".
- [ ] **D4.** Give me the log, the card contents, and this list with your times on it.

---

## What I will look for in the log

You do not need to read this part. It is here so that you can see the test has a specific
answer, and so that you can stop the flight early if you see something bad.

| Action | Line in the log | What is wrong if it is missing |
|---|---|---|
| A2 | `camera ready — re-reading thermal mode and palette` | The cold-start read did not run. The buttons are guessing. |
| A2 | `camera display mode at connect:` | Same. |
| B6, B8, B13 | `display mode now IR` / `VISIBLE`, then `video frame size 640x512` | The camera accepted and did not act. |
| B6, B8, B13 | `lens check: the picture agrees` | **NEW.** The picture confirmed the lens. |
| — | `LENS DID NOT CHANGE.` | **NEW.** The fault of 12 September, caught. If you see the amber notice "The camera did not change lens", tell me. |
| B10, B11 | `IR refused — the camera is recording` | The refusal did not work. |
| B14–B16 | `zoom now 4X (raw=400)`, then `zoom check: the camera is at 4X as asked` | **NEW.** |
| — | `ZOOM WRITE IGNORED.` | **NEW.** A zoom the camera did not take. |
| C11 | `setLocationAsHomePoint: accepted — waiting for the aircraft` | The old code said "OK" here. |
| C12 | `home point check: the aircraft reports the new home (NN.N m ...)` | **NEW.** The aircraft confirmed. The distance must be LARGE — a small one means C10 was not done. |
| — | `HOME POINT DID NOT MOVE.` | **NEW.** The write was lost. Serious. |
| B24, C2 | `camera media status: PHOTO_TAKEN_DONE (http://...MAX_nnnn.JPG)` | A capture that named a file. Count these against the card. |
| B24, C2 | `camera media status: PHOTO_TAKEN_DONE` with nothing after it | A capture that named NO file. The screen must NOT have said "Photo Saved". |
| B22 | `screen capture [...] — variant: ... full (profile+level...)` | **CARRIED FORWARD.** If it says `max-fps, no profile/level`, H.264 High is not supported on this hardware and the encoder fell back without saying so. |

---

## Optional, and it needs Autel Explorer

This answers the open question in `CLAUDE.md` about what the aircraft SAVES. It is not a test
of this application.

- [ ] **E1.** In Autel Explorer, select **PIP** or **OVERLAP**. Record for 10 seconds. Stop.
- [ ] **E2.** Count the video files on the card at that number. If there are three
      (`MAX_`, `MIX_`, `IRX_`), the blend modes save all three streams and the lens lock during
      a recording stops mattering.
