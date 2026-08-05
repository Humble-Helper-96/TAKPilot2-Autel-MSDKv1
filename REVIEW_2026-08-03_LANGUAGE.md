# Language review: Simplified Technical English — 3 August 2026

**Written in Simplified Technical English (ASD-STE100).**

> **This is a record of one date. All 13 findings are closed.** Section 5 gives the result of each
> one. Read this document for the reasons and for the rules. Do not read it as a list of open work.

**Scope:** all text that the pilot sees, except the Pre-Flight screen. That screen was rewritten to
STE on 2 August 2026.

**The reference:** the Field Guide (`FieldGuideActivity.kt`) contains a written STE ruleset and
obeys it. Its rules are:

- One instruction in each sentence.
- A maximum of 20 words in each sentence.
- Active voice.
- Present tense.
- No contractions.
- No "-ing" noun forms.
- One term for each concept.
- A short press is a "touch".
- **The airframe is always the "aircraft". It is never the "drone".**

## 1. Finding 1: a conflict of terms (a decision was necessary first)

**Where:** the full application. The count was 120 strings with "aircraft" and 34 with "drone".

**What:** The Field Guide gives the rule "aircraft, never drone". The Pre-Flight rewrite of the
previous day used "drone", at the direction of the operator. Examples were the title "Drone
Settings", the button "Apply to Drone" and the status "The drone did not…". Therefore the two parts
of the application disagreed with the style rule of the application.

**Why it is important:** The first principle of STE is one term for each concept. A pilot who reads
"aircraft" on the flight screen and "drone" in Pre-Flight learns that the two terms are the same.
They are not. A difference of this type also makes the application look incomplete. This occurs on
the screens that must give confidence.

**Severity:** Should fix. This finding stopped the other work, because each other correction needed
the agreed term.

## 2. FlightActivity

This screen has the largest quantity of text (91 strings). It was not in STE.

### Finding 2: contractions (severity: should fix)

**Where:** `FlightActivity.kt` and `TakPilotHomeActivity.kt`. Examples were "Can't drop", "isn't
available", "can't set", "You'll need to relaunch".

**What:** STE does not permit contractions.

**Correction:** Write the full words. "Cannot drop…". "You must start the application again."

### Finding 3: jargon in parentheses inside error messages (severity: should fix)

**Where:** `FlightActivity.kt`. Examples were "camera look-point not available (GPS/gimbal not
ready)" and "waiting on GPS + gimbal".

**What:** The message gives the failure and the internal cause in one line. STE gives the
instruction. It does not give the mechanism.

**Why it is important:** A pilot who cannot put a marker needs to know what to do. The names of the
subsystems do not help.

**Correction:** "Cannot drop the marker yet. Wait for GPS and the gimbal."

### Finding 4: "-ing" forms and the em-dash as a connector (severity: polish)

**Where:** Examples were "Starting screen stream…" and "Camera still initialising — try again in a
moment".

**What:** STE does not permit progressive verbs or two clauses that an em-dash joins.

**Correction:** "The screen stream starts." "The camera is not ready. Try again in a moment."

### Finding 5: different words for the same condition (severity: should fix)

**Where:** The condition "not connected" occurred as "No aircraft connected", "Aircraft not
connected", "Aircraft camera not connected" and "TAK: Disconnected". This is a minimum of three word
orders for one idea.

**Why it is important:** STE has a controlled vocabulary to remove this. A difference of this type
makes the application look like separate parts.

**Correction:** Use one form. "The aircraft is not connected." "The camera is not connected."

### Finding 6: the titles of the marker dialogs (severity: polish)

**Where:** "Rename Marker", "Change Type", "Delete Marker", "Clear All Markers", "$affiliationLabel
pin placed".

**What:** The titles are noun phrases with different styles. The word "pin" is here and the word
"marker" is in other places. These are two terms for one thing.

**Correction:** Use one term. Make the dialog titles the same shape.

## 3. Home screen

### Finding 7: the Stop and Quit dialog (severity: should fix)

**Where:** `TakPilotHomeActivity.kt`. The text was "Force-stop TAKPilot2-Autel and all its
background processes (video stream, TAK connection, telemetry)? You'll need to relaunch the app."

**What:** This is 28 words. It has a list in parentheses, the jargon "Force-stop" and "background
processes", and a contraction.

**Why it is important:** This dialog confirms the most destructive control on the home screen.
Therefore it must be the clearest text in the application.

**Correction:** "Stop TAKPilot and close the video, the TAK connection, and telemetry? You must
start the app again to fly."

### Finding 8: the letter case of the buttons (severity: polish)

**Where:** `activity_takpilot2_home.xml`. Examples were "STOP / QUIT", "Pre-Flight Setup", "ENTER
FLIGHT" and "Field Guide". This is three conventions in six adjacent buttons.

**Correction:** Use one convention.

## 4. Notifications and other screens

### Finding 9: the notification text (severity: polish)

**Where:** `TakForegroundService.kt`. The text was "Keeps the TAK connection and drone feed alive"
and "Holding the link".

**What:** The word "drone" disagrees with finding 1. "Holding the link" is internal language that a
pilot does not understand.

**Correction:** "TAKPilot is running." is sufficient.

### Finding 10: the unit labels (severity: should fix)

**Where:** `Units.kt`. The speed was "MPH" in capital letters. The distance and the altitude were
"ft" in small letters. Metres also occurred in text that the pilot sees.

**Why it is important:** Different units on a flight readout can cause a wrong reading. This is not
only a question of style.

**Correction:** Use one convention for the letter case. Keep all values that the pilot sees in
imperial units. Metres belong in the log only.

### Finding 11: TAK jargon reaches the pilot (severity: should fix)

**Where:** The Pre-Flight status was "Drone PLI streaming". "CoT" occurred in other status text.

**What:** "PLI" and "CoT" are words for a TAK operator. They are not words for a pilot.

**Correction:** "Sending the aircraft position to TAK." Use no acronym.

## 5. The Field Guide

### Finding 12: the guide can describe old behaviour (severity: should fix)

**Where:** `FieldGuideActivity.kt`. The sections about RTH, the failsafe and the limits were written
before the changes of 2 August 2026.

**Why it is important:** A field guide that describes the old behaviour is worse than no guide.

**Correction:** Compare the guide with the current behaviour.

### Finding 13: the length of the guide (severity: polish)

**What:** The guide has more than 4000 words. It is complete, but a pilot reads it quickly.

## 6. Results, 3 August 2026

Each result was tested on the connected controller.

| # | Finding | Result |
|---|---|---|
| 1 | Terms | **Closed.** All text that the pilot sees changed from "drone" to "aircraft". This was the decision of the operator. The code identifiers (`droneUid`, `sendDronePLI`) did not change. |
| 2 | Contractions | **Closed.** All contractions in the pilot files are now full words. |
| 3 | Jargon in parentheses | **Closed.** The error messages now give an action. |
| 4 | "-ing" forms and em-dashes | **Closed** for the strings in this review. Some progress messages such as "is starting" are kept. They are satisfactory for a short status message. |
| 5 | "not connected" | **Closed.** The forms are now "The aircraft is not connected." and "The camera is not connected." |
| 6 | Marker dialogs | **Closed.** The word "pin" changed to "marker" in the text that the pilot sees. |
| 7 | Stop and Quit dialog | **Closed.** |
| 8 | Button letter case | **No action necessary.** The Material theme sets `textAllCaps` on the buttons. Therefore each button label is in capital letters and they agree. This was confirmed on the device. |
| 9 | Notification text | **Closed.** The text is now "Keeps TAKPilot running while the screen is off." and "TAKPilot is running." |
| 10 | Unit labels | **Closed.** The speed label changed from "MPH" to "mph" to agree with "ft" and "mi". |
| 11 | TAK jargon | **Closed.** The status is now "Sending the aircraft position to TAK." |
| 12 | Old text in the guide | **Closed.** The guide gave the failsafe as "Return Home, Hover or Land". Hover and Land were removed on 2 August 2026. The text is now "the aircraft returns to home". The word "drone" was also removed from the guide. |
| 13 | Length of the guide | **Closed.** The repeated content and the old content were removed. The remaining length is the safety information for each control. The entries are complete in themselves, so more cuts would remove real content. |

**Total: 12 closed, 1 with no action necessary (finding 8).**

## 7. Text that this review did not examine

This review made a list of the feed dialogs in `DataSyncActivity` (22 strings) and the text of the
AR calibration dialog. It did not rewrite them.

They have the same faults: contractions and jargon. Give them the same corrections.
