# Code review — 3 August 2026

**Written in Simplified Technical English (ASD-STE100).**

> **This is a record of one date. All seven findings are closed.** Each finding gives its result.
> Read this document for the reasons and for the rules that came from it. Do not read it as a list
> of open work.

**Scope:** `com/autel/sdksample/tak/` and `com/taklite/`. The sample code from Autel is not in the
scope.

## 1. The organising risk

This risk comes from the history of the project.

**An SDK method with the form `get*(callback)` can be a subscription that sends data approximately
two times each second. There can be no method to stop it.**

A person used one of these methods and thought that it gave one answer. This filled the
fly-controller channel with data. The aircraft then hit a wall on 2 August 2026.

Findings 1 to 3 are all forms of this risk.

## 2. Depth of the review

Be accurate about what was examined.

- **Examined closely, function by function:** `AutelTakBridge`, `AutelAvoidance`,
  `AutelProductHolder` (the connect path), `AutelControlRates`, `AutelExposureController`,
  `AutelLights`, `AircraftSettingsDump` and `FlightLimitsController`.
- **Examined for risk classes only:** `FlightActivity` (2235 lines). The review looked at the
  handler, the lifecycle and the listener code. It did not look at each line.
- **Not examined on this date:** the TAK network code, the internal code of `TakConnectActivity`,
  the video pipeline, the map and marker files, and the `onDraw` code of the nine custom views.
  Section 5 gives the later reviews of these parts.

## 3. Findings

### Finding 1: `productConnected` has no guard against a second call (severity: BLOCKER)

**Where:** `AutelProductHolder.connectListener.productConnected`, which calls
`AutelAvoidance.onProductConnected()`, which calls `getVisualSettingInfo(...)`.

**What:** `productConnected` does its full arming sequence each time that it operates. It has no
control to test if it is already armed for this product.

`AutelAvoidance.onProductConnected` arms a continuous `getVisualSettingInfo` reader. The comments in
that file confirm that it "fires about twice a second forever". There is no API to stop it.

`productConnected` can operate more than one time for the same product. This occurs with camera
enumeration, and when the SDK sends the callback again after the global listener is registered again
at each `onResume`. The Home screen and the Flight screen both do this. Each operation then adds
another permanent stream at 2 Hz to the fly-controller channel.

**Why a pilot cares:** This is the same shape and the same channel as the flood of 2 August 2026.
That flood made a hover unstable and defeated the obstacle avoidance. The fault was corrected at one
door. This is a second door to the same room.

**Severity: BLOCKER.** It needs no unusual conditions. Movement between screens while connected can
be sufficient.

**Result: CLOSED, 3 August 2026.** The fault was confirmed as real during the correction:
`AutelControlRates.kt` already recorded that `productConnected` operated "three times in 17 seconds,
observed 2026-08-02". That file had its own guard. `AutelAvoidance` did not.

The correction has two parts:
1. `AutelProductHolder.armedForProduct` arms one time for each product. A second call only refreshes
   the observers.
2. `getVisualSettingInfo` was removed completely. Read finding 2.

The application now calls `getVisualSettingInfo` zero times. The only subscription is one
`setVisualSettingInfoListener` that cleans itself, armed one time for each product.

**A future improvement, not built.** The `armedForProduct` guard depends on this behaviour: a real
loss of the link always gives `productDisconnected` before the aircraft announces itself again. The
evidence supports this, but it is not confirmed on hardware. To close the theoretical gap, add a
watchdog for stale telemetry: if the application is connected but no new telemetry has arrived for
approximately 5 seconds, arm the listeners again. The symptom of the gap is visible today (the HUD
stops) and the recovery is a reconnection. Therefore this is protection, not a correction.

### Finding 2: two continuous streams of the same data (severity: should fix)

**Where:** `AutelAvoidance.onProductConnected` armed a `setVisualSettingInfoListener` (continuous,
one slot, safe to arm again) **and** a `getVisualSettingInfo` (continuous at 2 Hz, cannot be
stopped). Both gave the same three values.

**What:** The `get*` call existed only to get a first value when the product connects. It never
stops. Therefore one clean connection gave two readers of the same data for the full flight.

**Why a pilot cares:** This is unnecessary data on the safety channel. It is also the component that
finding 1 multiplies.

**Result: CLOSED, 3 August 2026.** The correction went further than the review proposed.
`getVisualSettingInfo` was removed from the avoidance code completely. `AutelAvoidance` now keeps
the full `VisualSettingInfo` from its one listener and gives it to the other code. `applyAtConnect`
reads that copy. `AircraftSettingsDump` also reads it, instead of opening a third copy of the same
subscription. The result is one listener and no query subscriptions.

### Finding 3: the telemetry copy is not cleared at a disconnection (severity: should fix)

**Where:** `AutelTakBridge`. `TakBridgeHolder.onProductDisconnected()` does nothing, and the bridge
never clears its copy of the position. `pushOnce` continues to send the last known position at 2 Hz
while TAK is connected.

**What:** When the link to the aircraft stops during a flight, the bridge does not stop and does not
mark the track as old. It sends the last position with no limit.

**Why a pilot cares:** The TAK team sees the aircraft at its last position and connected. Nothing
shows that the data is dead. In a public-safety operation this is a false picture that other people
will act on. This is worse than a track that disappears.

**Result: CLOSED as the intended behaviour.** This was the decision of the operator on 3 August 2026.

The behaviour is deliberate. It stops the aircraft disappearing from the maps of the team during a
period of poor network.

The design was examined and is correct. `CotBuilder.DRONE_STALE_DURATION_MS` is 120000 (2 minutes),
and `pushOnce` stops early when TAK is not connected. Therefore a network fault lets the last
position become old correctly.

One narrow case is recorded for the operator: if the link to the AIRCRAFT stops but TAK stays
connected, the application continues to send the frozen position. This refreshes the stale time with
no limit. Therefore an aircraft that is permanently lost never becomes old. **The operator decided to
leave this as it is.** The case is rare and the pilot sees it on their own screen.

### Finding 4: the rule for arming a listener again is not written down (severity: should fix)

**Where:** `AutelTakBridge.subscribe()` uses `set*Listener` methods. These have one slot, so arming
again replaces the listener and is safe. `AutelAvoidance` mixed a safe `setListener` with an unsafe
`get*(callback)`.

**What:** The safety of arming again depends on this: is the SDK method a setter with one slot, or a
subscription that collects? Nothing in the code said which one each call was.

**Why a pilot cares:** This is the exact difference that the next person will get wrong. A person got
it wrong before.

**Result: CLOSED, 3 August 2026.** Each SDK listener and getter call was compared with the bytecode
of the AAR and then annotated.

Two results are important to keep:

- **The name "set…Listener" is not a reliable signal in this SDK.** The XStar version of
  `setFlyControllerInfoListener` calls `addIStarLinkLongTimeCallback` and COLLECTS listeners. The
  EVO2 version that this application uses cleans itself: `Evo2FlyController` calls
  `removeXInfoListener…` and then `addXInfoListener…`. Therefore arming again REPLACES and is safe.
  **Confirm each implementation. Do not use the name.**
- **Each `get*(callback)` that this application calls gives one answer.** This was confirmed. The
  fly-controller getters use `ParamsQueryPacket` and `sendPacket`. The camera getters use
  `CameraHttpRequest`, which is an HTTP GET. The one getter with the shape of a subscription,
  `getVisualSettingInfo`, was removed in the correction for finding 1.

### Finding 5: the telemetry values can come from two different frames (severity: polish)

**Where:** `AutelTakBridge.pushOnce()` copied `lat` and `lon` into local values but read `hae`,
`relAlt`, `speedMs`, `headingDeg` and `batteryPct` directly. The listener writes them on the thread
of the SDK.

**What:** `@Volatile` makes each read atomic. It does not make a consistent group. Therefore one
message can contain values from two different telemetry frames.

**Why a pilot cares:** The effect is very small at 2 Hz, because the values change slowly. But it is
a real data race on the data that the application sends most frequently.

**Result: CLOSED, 3 August 2026.** `pushOnce` now copies each value that it uses into local values at
the start. It also gives the gimbal copy to `pushCameraPoint`. Therefore the SPI and the position
message describe the same telemetry frame.

### Finding 6: the physical units were not confirmed (severity: should fix)

**Where:** `AutelTakBridge` `ACC_DIVISOR = 1000.0`, with a comment that said "believed mm". Also
`AutelAvoidance` radar distances, with a comment that said "believed CENTIMETRES, still not
confirmed". The radar values go to `ObstacleEdgeView`.

**What:** Two safety values, the GPS accuracy and the obstacle distance, used units that nobody had
confirmed. If `ACC_DIVISOR` is wrong, the accuracy is wrong by 1000 times.

**Why a pilot cares:** The obstacle distance especially. A pilot judges the clearance from it.

**Result: CLOSED, 3 August 2026. No change to the code was necessary.**

- *The radar units:* These were already confirmed in flight on 2 August 2026. `ObstacleEdgeView`
  records that the operator flew the display against real obstacles. The comment in
  `AutelAvoidance.logRadar` that said "not confirmed" was corrected.
- *The GPS accuracy:* The values `horizAccM` and `vertAccM` are WRITTEN but never READ. There is no
  display, no message and no other user. Therefore no unconfirmed unit reaches a pilot. The fields
  now have a warning that a person must confirm `ACC_DIVISOR` on a bench BEFORE anyone uses them.
  **The severity in the original finding was too high.** Nothing is shown to a pilot today.

### Finding 7: delayed operations are not cancelled when the screen closes (severity: polish)

**Where:** `FlightActivity`. `onPause` removes the refresh operation, but several
`handler.postDelayed { … }` operations hold the camera and the context and are not removed in
`onDestroy`.

**What:** If the system destroys the screen inside one of these delay periods, the operations run
against a dead activity.

**Result: CLOSED, 3 August 2026.** `handler.removeCallbacksAndMessages(null)` was added to
`FlightActivity.onDestroy`.

## 4. Parts that are correct

These parts were examined and are correct. This record stops a second review of them.

- **`AutelAvoidance.readOnce` and `setSwitch`.** This is the correction for the wall strike. It is
  correct. It uses an `AtomicBoolean` latch, and `setSwitch` deliberately does not start a reader
  when it completes.
- **`AutelAvoidance.applyAtConnect`.** It has a guard, it refuses to write while the aircraft is in
  the air, and it writes only the switches that are wrong.
- **`FlightLimitsController`.** It reads the range that the aircraft accepts before it writes. It
  refuses a value outside that range. It reads the value again after it writes. **The other code
  that writes to the aircraft must follow this model.**
- **`AutelExposureController.logReadback`.** It reads what the camera applied. This finds a silent
  change back to an old value.
- **The correction of the sign conventions** for `relAlt` and the gimbal pitch. This is done one
  time, when the data arrives, with full reasoning. **Do not make this simpler.**

## 5. Later reviews of the parts not examined on 3 August

**The `onDraw` code of the custom views: examined and corrected.** All eight flight views were
examined. Most correctly keep their `Paint` and `RectF` objects as fields. Three made a new object
for each frame. These were corrected:

- **`ObstacleEdgeView`** (a safety display that draws at the radar rate): `Color.parseColor` for
  each edge for each frame became two constants. A new `Path()` for each rear chevron became one
  reused path. A `fontMetrics` read, which makes a new object, became one cached object.
- **`LiveToggleView`**: a `RectF` and a `Paint` for each frame became fields.
- **`CrosshairView`**: an array made for each frame became a field.

This was checked on the device with the live obstacle radar. The red arc, the rear chevron and the
crosshair all draw exactly as before.

**The video pipeline: examined.** `AutelVideoStreamer`, `ScreenCaptureEncoder`,
`ScreenCaptureService` and `VideoStreamerHolder` were examined. The screen-capture path is correct.

One real fault was found in the aircraft-camera path, which no production code calls: a wrong
detection of H.264 against H.265 in `sniffParameterSets`. That path was deleted, not corrected. This
also removed the question about the codec and `AutelCodecView` operating at the same time.

**The TAK network code, TLS and certificate enrollment: examined.** Read
`REVIEW_2026-08-03_SECURITY.md`. The CoT parsing is safe from XXE. The application refuses an
unencrypted connection. Two protection corrections were applied. Three findings were accepted as
standard TAK behaviour.

## 6. Summary

| Severity | Count | Findings |
|---|---|---|
| Blocker | 1 | Finding 1 |
| Should fix | 4 | Findings 2, 3, 4, 6 |
| Polish | 2 | Findings 5, 7 |

All are closed.

The most important was finding 1. It was the same failure that caused an aircraft to hit a wall,
through a second entry point, with no guard.
