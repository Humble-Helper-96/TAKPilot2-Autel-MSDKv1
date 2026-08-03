# Code Soundness Review — 2026-08-03

Scope: `com/autel/sdksample/tak/` + `com/taklite/`. Inherited sample code excluded (see end).
Notes only — nothing changed. Triage by number.

**Review depth (be honest about it):**
- **Close, function by function:** AutelTakBridge, AutelAvoidance, AutelProductHolder (connect
  path), AutelControlRates, AutelExposureController, AutelLights, AircraftSettingsDump,
  FlightLimitsController (also reviewed yesterday).
- **Targeted scan** (risk classes only — handler/lifecycle/listener hygiene, not every line):
  FlightActivity (2235 ln).
- **Not reached tonight** (flag for the operator — needs a second session): TAK-side networking
  (`taklite/` — TakManager 508, TakCertEnroller 443, CotBuilder 432, CotParser, TakClient),
  TakConnectActivity internals, the video pipeline (AutelVideoStreamer, LowBandwidthTranscoder,
  ScreenCaptureEncoder), the map/marker files, and the nine custom views' onDraw paths.

The organising risk, from this project's history: an SDK `get*(callback)` can be a ~2 Hz
subscription with **no way to deregister**, and treating one as one-shot flooded the fly-controller
channel and put an aircraft into a wall (2026-08-02). Findings 1–3 are variations on that theme.

---

## Safety-critical (aircraft-write path)

### 1. `productConnected` has no idempotency guard, and one callee arms an uncancellable 2 Hz subscription each time — latent recurrence of the wall-strike flood
- **Where:** `AutelProductHolder.kt:253-306` (`connectListener.productConnected`) →
  `AutelAvoidance.onProductConnected()` `AutelAvoidance.kt:60-115`, specifically the
  `getVisualSettingInfo(...)` call at `:84-102`.
- **What:** `productConnected` runs its whole arming sequence every time it fires, with no "already
  armed for this product" guard. `AutelAvoidance.onProductConnected` arms a **continuous**
  `getVisualSettingInfo` reader that this file's own comments confirm "fires about twice a second
  forever" and which has no deregister API. If `productConnected` fires more than once on the same
  product — camera-enumeration churn (observed yesterday), or the SDK replaying the callback when
  the global product listener is re-registered on `onResume` (Home/Flight both call
  `AutelProductHolder.install()` every resume — `TakPilotHomeActivity.kt:63,146`,
  `FlightActivity.kt:438`) — each firing stacks another permanent 2 Hz stream on the fly-controller
  channel.
- **Why a pilot cares:** this is the exact shape and the exact channel of the 2026-08-02 flood that
  destabilised a hover and defeated obstacle avoidance. It was fixed at the `readOnce`/`setSwitch`
  door; this is a second door to the same room.
- **Fix:** guard `productConnected` so re-entry on the *same* product is a no-op (compare the
  `BaseProduct` instance, or an `armedForThisProduct` flag cleared in `productDisconnected`).
  Separately, see #2.
- **Severity:** **blocker** (given the history and that it needs no exotic trigger — screen
  navigation while connected may be enough).
- **RESOLVED 2026-08-03.** Confirmed real in the wild while fixing: `AutelControlRates.kt:59-64`
  already documented `productConnected` firing "three times in 17 seconds, observed 2026-08-02" —
  it had guarded its own apply, but AutelAvoidance's uncancellable `getVisualSettingInfo` had no
  such guard, which is the flood path. Fixed by (a) `AutelProductHolder.armedForProduct` — arm
  once per product, re-fire only refreshes observers; (b) eliminating `getVisualSettingInfo`
  entirely (see #2). `getVisualSettingInfo` is now called ZERO times in the app; the only
  visual-setting subscription is one self-cleaning `setVisualSettingInfoListener`, armed once per
  product. Build green.
- **FUTURE-RELEASE OPTION (noted 2026-08-03, not built).** The `armedForProduct` guard relies on a
  real link loss always firing `productDisconnected` before the aircraft re-announces. Evidence
  supports that pairing, but it is not hardware-verified. To close the theoretical gap where the
  SDK re-delivers `productConnected` for the same object after silently killing listeners with no
  disconnect in between, add a **telemetry-staleness watchdog**: if the app is "connected" but no
  fresh telemetry has arrived for ~5s, force a re-arm. Symptom of the uncovered case today is
  visible (frozen HUD) and recovery is a reconnect/power-cycle, so this is hardening, not a fix.
  Consider for a future release.

### 2. `AutelAvoidance.onProductConnected` arms TWO continuous streams of the same data; the second never stops
- **Where:** `AutelAvoidance.kt:63-102` — a `setVisualSettingInfoListener` (continuous,
  single-slot, correctly re-armable) **and** a `getVisualSettingInfo` (continuous 2 Hz,
  uncancellable) for the same three booleans.
- **What:** the `get*` exists only to get an initial value "as soon as the product syncs" (its own
  comment), but it never stops — so even on a single clean connect there are two 2 Hz readers of
  identical data running for the whole flight.
- **Why a pilot cares:** needless sustained traffic on the safety channel, and it is the component
  that #1 multiplies.
- **Fix:** use the file's own `readOnce` latch (`:137-154`) for the initial value; the standing
  `setVisualSettingInfoListener` already covers updates. One continuous stream, not two.
- **Severity:** should-fix (becomes part of #1's fix).
- **RESOLVED 2026-08-03.** Went further than the proposed fix: removed `getVisualSettingInfo`
  from the avoidance path entirely (and `readOnce` with it). `AutelAvoidance` now caches the full
  `VisualSettingInfo` from its single standing listener and exposes it (`latestVisualSetting`);
  `applyAtConnect` reads that cache; `AircraftSettingsDump` reads it too instead of opening a
  third copy of the same subscription. One listener, zero query-subscriptions.

### 3. Telemetry cache is never invalidated on disconnect — TAK keeps receiving a frozen "live" track after link loss
- **Where:** `AutelTakBridge.kt` — `TakBridgeHolder.onProductDisconnected()` is a documented no-op
  (`:669`), and the bridge's own cache (`lat/lon/relAlt/...`, `:57-77`) is never cleared. `pushOnce`
  (`:295`) keeps sending the last-known position at 2 Hz as long as TAK is connected.
- **What:** when the aircraft link drops mid-flight (which yesterday's Explorer contention caused
  repeatedly), the bridge does not stop or mark the track stale — it republishes the last fix
  indefinitely.
- **Why a pilot cares:** the TAK team sees the aircraft parked at its last position, still
  "connected," with no indication the feed is dead. In a public-safety scenario that is a false
  picture others will act on — worse than the track disappearing.
- **Fix:** on `onProductDisconnected`, either stop the tick or stamp the PLI stale (stop sending
  after N seconds without fresh telemetry, or set a CoT staleTime). Decide with the operator which
  TAK behaviour is wanted.
- **Severity:** should-fix (borderline blocker for a public-safety deployment).
- **RESOLVED — INTENTIONAL (operator, 2026-08-03).** The persistence is by design: it prevents the
  aircraft dropping off teammates' maps during network instability. Verified the design is sound —
  `CotBuilder.DRONE_STALE_DURATION_MS = 120000` (2 min) with a matching rationale comment, and
  `pushOnce` returns early when TAK is disconnected, so a network outage ages the last PLI toward
  its 2-min stale correctly. One narrow sub-case noted for the operator: an aircraft link loss
  while TAK stays connected keeps re-pushing the frozen position, refreshing its stale
  indefinitely, so a *permanently* lost aircraft (not a transient drop) never ages out. **Operator
  decision 2026-08-03: leave as-is** — the rare case, and visible on the pilot's own screen. No
  change.

### 4. Re-arming asymmetry is undocumented and load-bearing
- **Where:** `AutelTakBridge.subscribe()` (`:151`, called from `start()` and `onProductConnected`)
  uses `set*Listener` — single-slot setters, so re-arming *replaces* and is safe. `AutelAvoidance`
  mixes a safe `setListener` with an unsafe `get*(callback)`.
- **What:** the safety of re-arming depends entirely on whether the SDK method is a single-slot
  setter or an accumulating subscription — and nothing in the code states which is which per call.
- **Why a pilot cares:** it is exactly this distinction that the next person will get wrong, as it
  was gotten wrong before.
- **Fix:** annotate each SDK listener/getter call site with `// single-slot setter, safe to
  re-arm` or `// SUBSCRIPTION, must latch — never re-arm`, verified against the aar
  (`javap -p -c`). Cheap insurance.
- **Severity:** should-fix.
- **RESOLVED 2026-08-03.** Verified every SDK listener/getter call site against the aar bytecode
  and annotated the clusters (`AutelTakBridge.subscribe`, `AircraftSettingsDump`,
  `AutelControlRates.refresh`, `FlightActivity.syncIrStateFromCamera`; `AutelLights`,
  `FlightLimitsController`, `AutelAvoidance` already documented, corrected where needed). Findings
  worth keeping:
  - **The "set…Listener" NAME is not a reliable signal in this SDK.** The XStar impl of
    `setFlyControllerInfoListener` calls `addIStarLinkLongTimeCallback` and ACCUMULATES; the EVO2
    impl this app uses is self-cleaning (`Evo2FlyController` does `removeXInfoListener…` then
    `addXInfoListener…`, and `VisualModelManager` does `remove…; set…`), so re-arming REPLACES and
    is safe. Verified per-impl, not by name.
  - **Every `get*(callback)` this app calls is one-shot**, verified: fly-controller getters →
    `ParamsQueryPacket`/`MAV_CMD_GET_LED` via `sendPacket`; camera getters → `CameraHttpRequest`
    (HTTP GET); RC getters measured one-shot. The sole subscription-shaped getter,
    `getVisualSettingInfo`, was eliminated in the #1 fix.

### 5. Torn reads in the telemetry snapshot
- **Where:** `AutelTakBridge.pushOnce()` `:297-326` snapshots `lat`/`lon` into locals but reads
  `hae`, `relAlt`, `speedMs`, `headingDeg`, `batteryPct` live; the listener writes them on the
  SDK's thread.
- **What:** `@Volatile` makes each field read atomic but gives no snapshot consistency, so one PLI
  can mix fields from two telemetry frames.
- **Why a pilot cares:** negligible in practice at 2 Hz (fields drift slowly), but it is a real
  data-race surface on the most-published data.
- **Fix:** snapshot all consumed fields at the top of `pushOnce`, or hold a single immutable
  telemetry record updated atomically.
- **Severity:** polish.
- **RESOLVED 2026-08-03.** `pushOnce` now snapshots every consumed field (lat/lon/hae/relAlt/
  speed/heading/battery/cap/voltage + gimbal) into locals at the top, and passes the gimbal
  snapshot into `pushCameraPoint` so the published SPI and the PLI describe the same telemetry
  frame. Build green.

### 6. Unverified physical units shown to a flying pilot
- **Where:** `AutelTakBridge.kt:534` `ACC_DIVISOR = 1000.0` ("believed mm — bench-verify"), GNSS
  accuracy clamped `0.01..500` (`:174-175`); `AutelAvoidance.kt:180-181` radar distances "believed
  CENTIMETRES, still not confirmed" feeding `ObstacleEdgeView`.
- **What:** two safety-relevant readouts (GPS accuracy, obstacle distance) rest on unconfirmed unit
  assumptions. If `ACC_DIVISOR` is wrong the accuracy is off by 1000×.
- **Why a pilot cares:** obstacle distance especially — the pilot judges clearance from it.
- **Fix:** the operator field-validated the obstacle display on 2026-08-02 (see project memory), so
  the radar-cm assumption may already be effectively confirmed — cross-reference and, if so, delete
  the "unconfirmed" caveat. GNSS accuracy still needs a bench check.
- **Severity:** should-fix (verification, not code).
- **RESOLVED 2026-08-03 — no functional change needed, as predicted.**
  - *Radar cm:* already FIELD-VALIDATED 2026-08-02 (`ObstacleEdgeView.kt:32-36` documents the
    operator flying the display against real obstacles). The stale "believed / not confirmed"
    caveat in `AutelAvoidance.logRadar` was corrected to match. Confirmed, not unverified.
  - *GNSS accuracy:* found `horizAccM`/`vertAccM` are WRITTEN but NEVER READ — no readout, no PLI,
    no consumer anywhere. So no unverified unit reaches a pilot; it is computed-but-unused
    scaffolding. Annotated the fields with a ⚠ that `ACC_DIVISOR` must be bench-verified BEFORE
    anyone wires them, since a wrong divisor would be off by 1000x. Severity was overstated in the
    original finding — nothing is shown today.

### 7. FlightActivity one-shot delayed lambdas are not cleared on destroy
- **Where:** `FlightActivity.kt` — `onPause` removes the `refresh` runnable (`:455`) but several
  `handler.postDelayed { … }` one-shots (record-verify `:1606,1639`, `:1685`) capture `cam`/context
  and are not cleared in `onDestroy` (`:545`).
- **What:** if the screen is destroyed inside a settle window, these fire against a dead activity.
- **Why a pilot cares:** low — mostly a stray log or a no-op guarded by `isRecording`, but it is a
  latent leak/late-callback class.
- **Fix:** `handler.removeCallbacksAndMessages(null)` in `onDestroy`.
- **Severity:** polish.
- **RESOLVED 2026-08-03.** Added `handler.removeCallbacksAndMessages(null)` to
  `FlightActivity.onDestroy`. Build green.

---

## Things confirmed SOUND (worth recording so they aren't re-flagged)

- `AutelAvoidance.readOnce` / `setSwitch` — the wall-strike fix itself is correct: latched with an
  `AtomicBoolean`, and `setSwitch` deliberately does not spawn a reader on completion.
- `AutelAvoidance.applyAtConnect` — guarded by `appliedForThisConnect`, refuses to write while
  `AutelTakBridge.airborne`, and only writes switches that are actually wrong. Good.
- `FlightLimitsController` — reads the aircraft's accepted range before writing, refuses
  out-of-range, reads back after Apply. This is the model the other writers should follow.
- `AutelExposureController.logReadback` — reads back what the camera applied, catching silent
  reverts. Good pattern.
- Sign-convention normalisation (relAlt, gimbal pitch) is done once at ingest with thorough
  reasoning — do not "simplify" it.

---

## Not reviewed tonight — recommend a second code session

`taklite/` networking (TLS, CoT build/parse, cert enrollment — the security surface),
`TakConnectActivity` internals, the video pipeline, and the custom-view `onDraw` allocation paths
(per-frame allocation is the one efficiency question that actually matters on this screen). None
were opened; do not assume they are clean.

**Update 2026-08-03 — `onDraw` allocation paths reviewed and fixed (commit pending).** Audited all
eight flight custom views. Most correctly hoist their `Paint`/`RectF` to fields. Three had genuine
per-frame allocation, now removed:
- **`ObstacleEdgeView`** (safety display, redraws at the radar push rate): `Color.parseColor` per
  edge per frame → precomputed `COLOR_DANGER`/`COLOR_WARN` ints; a `Path()` per rear chevron →
  reused `chevronPath`; `textPaint.fontMetrics` (allocates each read) → a cached `FontMetrics`
  filled via `getFontMetrics(fm)`.
- **`LiveToggleView`** (blinks while RECONNECTING): a `RectF` + `Paint` allocated per blink frame
  → hoisted `sweepRect` + `ringPaint` fields.
- **`CrosshairView`** (redraws each HUD tick): `arrayOf(outline, line)` per frame → an `armPaints`
  field.
Verified on-device with live obstacle radar: the red edge arc (2.2 ft), the REAR chevron
(1.6 ft) and the crosshair all render pixel-identically. `ArOverlayView`'s `.format()` diag calls
are throttled to 1 Hz and gated (`logThisPass`), not per-frame — left as-is.

**Update 2026-08-03 — video pipeline reviewed (commit 7ba980c).** `AutelVideoStreamer`,
`ScreenCaptureEncoder`, `ScreenCaptureService` and `VideoStreamerHolder` audited. The shipping
screen-capture path is sound (FGS-before-projection ordering, drain-thread shutdown gated then
joined before codec release, projection callback + `onTaskRemoved` teardown, correct H.265 NAL
classification). Found one real bug in the aircraft-camera path — which nothing calls in production
— an H.264/H.265 mis-detection in `sniffParameterSets`; that whole dead path was deleted rather
than patched, which also retired the Phase-4 codec/`AutelCodecView` concurrency question. Verified
the live path end-to-end on-device after deletion.

**Update 2026-08-03 — `taklite/` TLS/CoT/cert enrollment reviewed.** See
`REVIEW_2026-08-03_SECURITY.md`. CoT parsing is not XXE-vulnerable (XmlPullParser, DOCTYPE off);
runtime refuses plaintext. Two hardening fixes applied (buffer cap, enrolled-cert key match); the
enrollment-TOFU / hostname / key-at-rest findings were accepted as standard TAK behaviour. The full
review surface is now opened.

---

## Summary

**Blockers:** 1 (#1)
**Should-fix:** 5 (#2, #3, #4, #6, and #1's guard)
**Polish:** 3 (#5, #7, plus #4 doc)

Highest priority: **#1** — it is the same failure that crashed an aircraft, reachable through a
second entry point, with no guard. Fixing #1 and #2 together (guard `productConnected`; latch the
avoidance initial read) closes the flood class. **#3** (stale TAK track) is the one most likely to
mislead the team in the field and deserves an explicit operator decision on desired behaviour.
