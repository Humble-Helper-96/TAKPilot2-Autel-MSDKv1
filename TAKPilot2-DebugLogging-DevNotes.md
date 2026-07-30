# TAKPilot2 Debug Logging — Dev Notes

**Audience:** whoever maintains the DJI build of TAKPilot2. This describes a feature built for the Autel port and proposes how to bring it over to the DJI app. Not a spec — the Autel implementation is the reference; adapt package names/files to what actually exists on the DJI side.

## 1. Intent

Both apps replace the vendor flight app during TAK operations and run on hardware that isn't tethered to a laptop in the field (Smart Controller V3 for Autel; likely similar for whichever DJI RC this targets). `adb logcat` only works when a laptop is plugged in — exactly the condition that stops being true right when something goes wrong on site. The goals:

- Capture **only our TAK/bridge code**, not full logcat — low noise, easy to read.
- **Off by default**, so it costs nothing when not needed, and toggleable to two detail levels once it is needed.
- **Crash capture**, but only while logging is turned on — not an always-on handler nobody asked for.
- **Bounded** — the working copy the app itself reads is capped and swept, so it can't slowly fill a constrained device.
- **Gettable off the device without adb** — a field session should be pullable by plugging in USB and opening Downloads, or emailing it straight from the app.

## 2. Architecture

### 2.1 The `AppLog` facade

A single object, `com.taklite.util.AppLog`, replaces scattered `android.util.Log` calls. It is **vendor-neutral** — JDK + Android framework only, no DJI/Autel SDK imports — so it lives comfortably next to `com.taklite.client.tak`, the existing vendor-neutral TAK layer, without breaking that package's "no SDK imports" rule. That also means **this exact file can likely be copied into the DJI tree verbatim.**

Every call always forwards to `android.util.Log` first (so `adb logcat` behaves identically to today), then conditionally writes to disk:

```kotlin
AppLog.i(TAG, "message")   // info — state changes
AppLog.w(TAG, "message")   // warning
AppLog.e(TAG, "message", throwable)  // error, optional stack trace
AppLog.d(TAG, "message")   // debug
AppLog.v(TAG, "message")   // NEW: verbose/detail tier, see below
```

### 2.2 Two-tier detail level

Two independent, persisted (`SharedPreferences`) booleans:

| Flag | UI label | Meaning |
|---|---|---|
| `AppLog.enabled` | "Logging enabled" | Master on/off. Nothing is written to disk when false. |
| `AppLog.verbose` | "Detailed (all app functions)" | When true, also captures the `AppLog.v(...)` call sites. |

- **Standard** (`enabled=true`, `verbose=false`): only the log calls that already existed in the bridge/TAK code before this feature — telemetry listener errors, TAK connect/disconnect, cert enrollment, PLI/CoT send confirmations. Mostly state transitions and failures.
- **Detailed** (`verbose=true`): additionally captures whole-app instrumentation added specifically for this feature — every screen's `onCreate`/`onResume`/`onPause`, every button tap, every toggle flip, marker-placed/sent/deleted events, a telemetry snapshot every ~5s, and a video-throughput summary every ~150 frames. This is what makes it possible to reconstruct "what did the user actually do" during a field session, not just "what broke."

Both flags are checked live on every call — flipping either takes effect immediately, no restart needed.

### 2.3 Two destinations, two lifetimes

This is the part most worth preserving carefully when porting — it's not just "write to two places," the two copies serve different purposes:

**Private working copy** — `filesDir/logs/app.log`, size-capped (1MB) with rotation to timestamped files on overflow. This is what the in-app Debug screen reads and what "Clear"/"Delete" act on. It's also swept: any file in that directory older than 2 hours is deleted on app start and on opening the Debug screen. This keeps the live-view file small and keeps the controller from silently filling up with logs nobody's looking at.

**Public archive** — `Download/TAKPilot2 Logs/<name>.log`, written via `MediaStore.Downloads` on API 29+ (falls back to a direct `File` under `Environment.DIRECTORY_DOWNLOADS` below that, guarded by `android:requestLegacyExternalStorage="true"` in the manifest for API 29 specifically — Android 11+ ignores that flag and MediaStore is mandatory there anyway). A **new timestamped file per app process/session**, each individually capped at 1MB like the private copy. **Never touched by Clear/Delete** — those only affect the private working copy.

This went through two design iterations worth recording. The first version made the archive permanent (never swept at all), meant to be a durable "look back through history" record. That was wrong: it created an unbounded-growth risk if an inattentive user left Detailed logging on — nothing was ever bounding the *total* folder size. A time-based sweep (matching the private copy's 2h-by-age approach) was considered next and also rejected: **age is a bad proxy for log volume**, since idle stretches produce little or no data — "2 hours" doesn't mean a consistent amount of log content.

What it landed on: **a total-size cap on the whole folder — `PUBLIC_ARCHIVE_MAX_BYTES`, currently 10MB —enforced whenever a new session file is created** (`enforcePublicArchiveCapMediaStore()` / `...Legacy()`), deleting the oldest files first (by `DATE_ADDED` on MediaStore, by `lastModified()` legacy) until back under the cap. This bounds disk usage directly regardless of how long logging is left on, without needing to reason about idle time at all. If you want a "nuke everything including the archive" button later, make it a distinct, clearly-labeled action — don't fold it into the existing Delete, which people expect to only affect what they're looking at.

Both writes happen from the same `writeToFile()` call in `AppLog`, so they're always in sync — there's no separate "export" step required for the archive to exist; it's live from the first log line.

### 2.4 Crash handler

Installed in the `Application.onCreate()`, but layered so it doesn't disturb whatever crash handling already exists:

```kotlin
Thread.setDefaultUncaughtExceptionHandler(
    new AppLogCrashHandler(Thread.getDefaultUncaughtExceptionHandler()));
```

`AppLogCrashHandler` writes the stack trace via `AppLog.writeCrash(thread, throwable)` **only if `AppLog.enabled` is true at the moment of the crash**, then unconditionally delegates to whatever handler was previously installed. On the Autel side there was already a legacy always-on crash writer (`TestApplication.EHandle`); wrapping around it this way meant zero behavior change to that existing path. **The DJI `DJIApplication.kt` doesn't appear to have a pre-existing crash handler**, which makes this simpler there — just install `AppLogCrashHandler` directly with `Thread.getDefaultUncaughtExceptionHandler()` as the fallback (the system default), no legacy handler to preserve.

### 2.5 Debug screen

A single activity (`DebugActivity` on the Autel side), reached from a button on the home screen. Left column: two checkboxes (Logging enabled, Detailed), Export/Clear/Delete buttons, a small "Also archived to Downloads/TAKPilot2 Logs" note. Right side: a scrollable monospace view of the private working log, polling the file every second and re-rendering only when its length actually changed.

Worth calling out because it wasn't obvious in the first pass: **don't force-scroll to the bottom on every poll tick.** The first version did `scrollView.fullScroll(FOCUS_DOWN)` unconditionally on every refresh, which fights anyone trying to scroll back through history — the view yanks itself back down within a second of the user scrolling up. The fix that held up under testing was an explicit touch-driven flag, not a geometry check re-derived each tick:

```kotlin
private var pinnedToBottom = true

logScroll.setOnTouchListener { _, event ->
    if (event.action == MotionEvent.ACTION_DOWN) pinnedToBottom = false
    else if (event.action == ACTION_UP || event.action == ACTION_CANCEL) {
        logScroll.postDelayed({ pinnedToBottom = isScrolledToBottom() }, 300) // let fling settle
    }
    false // don't consume — let ScrollView still handle the drag
}
```

Auto-scroll only fires when `pinnedToBottom` is true. Touching the log at all clears it; scrolling back to the bottom yourself sets it again. A pure "am I near the bottom right now" geometry check, re-evaluated on every poll, turned out to be timing-sensitive — it worked in a quick test but produced a surprising unprompted snap-to-bottom later in the same session, almost certainly a layout-pass race after the view was fully laid out. The touch-driven flag sidesteps that class of bug entirely by tying the state to an explicit user action instead of inferred geometry.

Export uses a `FileProvider` (new `<provider>` entry + `res/xml/file_paths.xml`, since none existed) to hand the active private-copy file to the system share sheet — useful for emailing a log directly without hunting through Downloads.

## 3. Key files (Autel reference implementation)

| File | Role |
|---|---|
| `com/taklite/util/AppLog.kt` | The whole facade — logging, both file sinks, rotation, retention, crash write. **Vendor-neutral, portable as-is.** |
| `com/autel/sdksample/TestApplication.java` | `AppLog.init(this)` + crash handler installation in `onCreate()`. |
| `com/autel/sdksample/tak/DebugActivity.kt` + `res/layout/activity_debug.xml` | The Debug screen. |
| `AndroidManifest.xml` | `DebugActivity` registration, `FileProvider` entry, `requestLegacyExternalStorage`. |
| `res/xml/file_paths.xml` | FileProvider path config for the export share-sheet. |
| Every other `com.autel.sdksample.tak.*` file + all of `com.taklite.client.tak` | `Log.i/w/e/d` → `AppLog.i/w/e/d` (mechanical), plus new `AppLog.v(...)` calls added at UI action points for Detailed-tier coverage. |

## 4. Porting to the DJI app — suggested approach

The DJI tree (`android-sdk-v5-sample`) already has the same `com.taklite.client.tak` package and an analogous `dji.sampleV5.aircraft.tak` vendor-facing layer (`DroneTakBridge.kt`, `DroneVideoStreamer.kt`, `TakConnectActivity.kt`, `TakDropMarkers.kt`, `TakMapMarkers.kt`, `TakMissionManager.kt`, `TakForegroundService.kt`, `TakAutoConnect.kt`), plus `TAKPilot2HomeActivity.kt`, `DataSyncActivity.kt`, and the flight screen (`DJIAircraftMainActivity.kt`) at the package root. That maps closely enough to the Autel side that this should be a mechanical port, not a redesign:

1. **Copy `AppLog.kt` in as-is**, same package (`com.taklite.util`). No changes needed — it has no Autel-specific code anywhere.
2. **Wire it into `DJIApplication.kt`**: `AppLog.init(this)` early in `onCreate()`, then install the crash handler. Since there's no pre-existing handler to preserve here, this is simpler than the Autel side — just:
   ```kotlin
   Thread.setDefaultUncaughtExceptionHandler(
       AppLogCrashHandler(Thread.getDefaultUncaughtExceptionHandler()))
   ```
3. **Migrate existing `Log.*` calls** across `dji.sampleV5.aircraft.tak/*` and `com.taklite.client.tak/*` to `AppLog.*` — this is a pure find-replace (`Log.i(` → `AppLog.i(`, etc.) plus swapping the import, exactly like the Autel migration. This alone gives you the Standard tier.
4. **Add the Debug screen**: port `DebugActivity.kt` + `activity_debug.xml` with package paths adjusted. Add the manifest `<provider>` entry + `file_paths.xml` for export, and `android:requestLegacyExternalStorage="true"` on `<application>`. Add a Debug button to `TAKPilot2HomeActivity.kt`'s button stack and wire the click listener.
5. **Add Detailed-tier instrumentation** (`AppLog.v(...)` calls) at the DJI-side equivalents of what got instrumented on Autel:
   - `TAKPilot2HomeActivity.kt`, `TakConnectActivity.kt`, `DataSyncActivity.kt`, `DJIAircraftMainActivity.kt` (the flight screen equivalent of `FlightActivity`): lifecycle methods + every button tap/toggle change.
   - `TakDropMarkers.kt` / `TakMapMarkers.kt`: success-path logs for marker placed/sent/deleted (the pre-existing code there likely only logs failures, same as it was on Autel before this change).
   - `DroneTakBridge.kt`: a periodic (every ~5s, not every tick) telemetry snapshot log — same rate-limiting reasoning as `AutelTakBridge`'s `logHudSnapshot()`, to avoid flooding Detailed mode at the bridge's native tick rate.
   - `DroneVideoStreamer.kt`: a periodic frame-count/throughput summary (every ~150 frames), not per-frame — DJI's video path re-encodes via `MediaCodec` (per the handoff doc's §3.3), so the natural place to hook this is wherever that encoder emits buffers.
6. **Verify the manifest `applicationId` vs `package` split** before hardcoding the FileProvider authority — the Autel app has `applicationId "com.tak.uastoollite"` while the manifest `package` is `com.autel.sdksample`, and the authority must use the *applicationId*. Check what the DJI build actually uses; don't assume they match.
7. **Smoke test**: toggle on/off, toggle Standard vs Detailed and confirm the volume difference, confirm a real file appears in `Downloads/TAKPilot2 Logs` on-device, confirm Export opens the share sheet with a real attachment, confirm Clear/Delete only affect the private copy and the Downloads archive survives them, and confirm the archive folder actually stays capped (write past 10MB total and check oldest files get pruned).

## 5. Open items / things to double check on the DJI side

- **API level assumptions**: this was built and tested against `minSdk 21` / `targetSdk 29`, with the legacy (`File`-based) Downloads write path present but only exercised in code review, not on a real API <29 device — the actual test hardware was API 29+, where MediaStore is the only path that ran. If the DJI build's `minSdk`/`targetSdk` differ meaningfully, re-check both storage branches.
- **Archive cap size**: `PUBLIC_ARCHIVE_MAX_BYTES` (10MB) was picked as "comfortably more than a few hours of active Detailed-mode logging." If the DJI RC has meaningfully less free storage than the Autel controller, or Detailed mode there produces more volume per hour (e.g. denser telemetry, more UI instrumentation), reconsider the number — it's a single constant, not a big change.
- **Naming collisions**: the Downloads subfolder name (`TAKPilot2 Logs`) is hardcoded as a shared constant — if both apps might ever run on the same physical device (unlikely, but worth a thought), consider a DJI/Autel-specific subfolder name instead of colliding into the same one.
