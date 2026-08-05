# Debug logging — development notes

**Written in Simplified Technical English (ASD-STE100).**

**Who reads this:** the person who maintains the DJI build of TAKPilot2.

This document describes a function that was built for the Autel port. It also tells you how to move
the function to the DJI application. It is not a specification. The Autel code is the reference.
Change the package names and the file names to agree with the DJI tree.

## 1. Purpose

Both applications replace the flight application of the manufacturer during TAK operations. They
operate on hardware that has no connection to a computer in the field.

`adb logcat` operates only when a computer is connected. This is not true at the time when a fault
occurs on site.

The function has these goals:

- Record only the TAK code and the bridge code. Do not record the full logcat. The log is then
  short and easy to read.
- Stay off until a person sets it to on. It then has no cost when it is not necessary.
- Give two levels of detail.
- Record a crash, but only when logging is on.
- Keep the file small. The application reads a working copy that has a limit and a sweep function.
  The file cannot fill the device.
- Let a person get the log without adb. A person can connect USB and open Downloads. A person can
  also send the log by email from the application.

## 2. Architecture

### 2.1 The `AppLog` object

One object, `com.taklite.util.AppLog`, replaces the `android.util.Log` calls.

`AppLog` uses only the JDK and the Android framework. It has no import from a manufacturer SDK.
Therefore it can be next to `com.taklite.client.tak`, which has the same rule. **You can copy this
file into the DJI tree with no change.**

Each call goes to `android.util.Log` first. Therefore `adb logcat` operates as before. The call then
writes to the disk if the controls permit it.

```kotlin
AppLog.i(TAG, "message")             // information: a change of state
AppLog.w(TAG, "message")             // warning
AppLog.e(TAG, "message", throwable)  // error, with an optional stack trace
AppLog.d(TAG, "message")             // debug
AppLog.v(TAG, "message")             // detail level: read section 2.2
```

### 2.2 Two levels of detail

Two independent values are kept in `SharedPreferences`.

| Value | Label on the screen | Function |
|---|---|---|
| `AppLog.enabled` | "Logging enabled" | The main control. Nothing goes to the disk when this is false. |
| `AppLog.verbose` | "Detailed (all app functions)" | When true, the application also records the `AppLog.v(...)` calls. |

**Standard level** (`enabled=true`, `verbose=false`). This records only the calls that were in the
bridge code and the TAK code before this function was built. These are the telemetry listener
errors, the TAK connect and disconnect events, the certificate enrollment and the CoT send
confirmations. These are changes of state and failures.

**Detailed level** (`verbose=true`). This also records the instrumentation that was added for this
function. It includes the `onCreate`, `onResume` and `onPause` of each screen, each button touch,
each toggle change, the marker events, a telemetry record every 5 seconds and a video summary every
150 frames. This level lets you see what the pilot did. The standard level only shows what failed.

The application reads both values at each call. A change operates immediately. You do not start the
application again.

### 2.3 Two destinations with two different lifetimes

Keep this design when you do the port. The two copies have different purposes.

**Private working copy.** The file is `filesDir/logs/app.log`. Its limit is 1 MB. When it is full,
the application makes a new file with a time in its name. The Debug screen reads this file. The
Clear button and the Delete button operate on this file. The application also deletes each file in
that directory that is more than 2 hours old. It does this when the application starts and when a
person opens the Debug screen. Therefore the live view stays small and the controller does not fill
with old logs.

**Public archive.** The files are in `Download/TAKPilot2 Logs/`. The application uses `MediaStore
.Downloads` on API 29 and higher. Below API 29 it writes a file directly in
`Environment.DIRECTORY_DOWNLOADS`. The manifest has `android:requestLegacyExternalStorage="true"`
for API 29. Android 11 and higher ignore that flag, and MediaStore is necessary there.

The application makes a new file with a time in its name for each session. Each file has a limit of
1 MB. **The Clear button and the Delete button do not touch these files.**

The design of the archive changed two times. Record these steps:

1. The first design kept the archive files for ever. This was not correct. If a person left the
   detailed level on, the total size of the folder had no limit.
2. A sweep by age was examined next and refused. **Age is a bad measure of log volume.** A quiet
   period makes almost no data. Therefore "2 hours" is not a constant quantity of log content.
3. The final design is **a limit on the total size of the folder**. The constant is
   `PUBLIC_ARCHIVE_MAX_BYTES` and its value is 10 MB. The application applies the limit when it
   makes a new session file. It deletes the oldest files first until the total is below the limit.
   This controls the disk use directly. You do not have to think about quiet periods.

If you add a button that deletes the archive, make it a separate button with a clear label. Do not
put this function in the Delete button. A person expects Delete to operate only on the file that
they can see.

The same `writeToFile()` call writes to both destinations. Therefore the two are always in
agreement. The archive does not need an export step. It exists from the first line.

### 2.4 Crash handler

Install the handler in `Application.onCreate()`. Put it around the handler that is already there.

```kotlin
Thread.setDefaultUncaughtExceptionHandler(
    new AppLogCrashHandler(Thread.getDefaultUncaughtExceptionHandler()));
```

`AppLogCrashHandler` writes the stack trace with `AppLog.writeCrash(thread, throwable)`. It does
this **only if `AppLog.enabled` is true at the time of the crash.** It then always calls the handler
that was installed before it.

The Autel application already had a crash writer that was always on (`TestApplication.EHandle`).
This design does not change that path.

The DJI `DJIApplication.kt` does not appear to have a crash handler. Therefore the DJI side is
easier. Install `AppLogCrashHandler` with `Thread.getDefaultUncaughtExceptionHandler()` as the
handler behind it. There is no older handler to keep.

### 2.5 Debug screen

The screen is one activity, `DebugActivity`. A button on the home screen opens it.

The left column has two checkboxes (Logging enabled, Detailed), the Export, Clear and Delete
buttons, and a note that says "Also archived to Downloads/TAKPilot2 Logs". The right side shows the
private working log in a monospace view that a person can scroll. The screen reads the file each
second. It draws the text again only when the length of the file changes.

**Do not move the view to the bottom at each refresh.** The first version called
`scrollView.fullScroll(FOCUS_DOWN)` at each refresh. This fought against a person who tried to read
the earlier part of the log: the view moved back to the bottom in one second.

The correct solution uses a value that a touch controls. It does not use a calculation of the
position.

```kotlin
private var pinnedToBottom = true

logScroll.setOnTouchListener { _, event ->
    if (event.action == MotionEvent.ACTION_DOWN) pinnedToBottom = false
    else if (event.action == ACTION_UP || event.action == ACTION_CANCEL) {
        logScroll.postDelayed({ pinnedToBottom = isScrolledToBottom() }, 300)
    }
    false   // Do not consume the event. The ScrollView must still get the drag.
}
```

The automatic scroll operates only when `pinnedToBottom` is true. A touch on the log makes it false.
When a person moves the view to the bottom, it becomes true again.

A calculation of "is the view near the bottom now" at each refresh does not work correctly. It
passed a quick test. Later in the same session it moved the view to the bottom with no command from
the person. The cause is almost certainly a layout race. A value that a touch controls does not have
this class of fault, because an action of the person sets it.

Export uses a `FileProvider`. This needs a new `<provider>` entry and `res/xml/file_paths.xml`. It
gives the active private file to the share function of the system. A person can then send a log by
email.

## 3. Files in the Autel reference code

| File | Function |
|---|---|
| `com/taklite/util/AppLog.kt` | The full object: logging, both file destinations, rotation, retention and the crash write. **It has no manufacturer code. You can move it with no change.** |
| `com/autel/sdksample/TestApplication.java` | `AppLog.init(this)` and the crash handler installation in `onCreate()`. |
| `com/autel/sdksample/tak/DebugActivity.kt` and `res/layout/activity_debug.xml` | The Debug screen. |
| `AndroidManifest.xml` | The `DebugActivity` entry, the `FileProvider` entry and `requestLegacyExternalStorage`. |
| `res/xml/file_paths.xml` | The FileProvider paths for the export function. |
| The other `com.autel.sdksample.tak.*` files and all of `com.taklite.client.tak` | `Log.i/w/e/d` changed to `AppLog.i/w/e/d`. New `AppLog.v(...)` calls were added at the user-interface actions. |

## 4. How to do the port to the DJI application

The DJI tree already has the `com.taklite.client.tak` package. It also has an equivalent layer in
`dji.sampleV5.aircraft.tak`. This is close to the Autel structure. Therefore this is a mechanical
port. It is not a new design.

1. **Copy `AppLog.kt` with no change.** Use the same package, `com.taklite.util`.
2. **Connect it in `DJIApplication.kt`.** Call `AppLog.init(this)` early in `onCreate()`. Then
   install the crash handler:
   ```kotlin
   Thread.setDefaultUncaughtExceptionHandler(
       AppLogCrashHandler(Thread.getDefaultUncaughtExceptionHandler()))
   ```
3. **Change the `Log.*` calls** in `dji.sampleV5.aircraft.tak/*` and `com.taklite.client.tak/*` to
   `AppLog.*`. This is a find-and-replace operation and an import change. This gives you the
   standard level.
4. **Add the Debug screen.** Move `DebugActivity.kt` and `activity_debug.xml`. Change the package
   paths. Add the `<provider>` entry, `file_paths.xml` and `android:requestLegacyExternalStorage=
   "true"` on `<application>`. Add a Debug button to the home screen.
5. **Add the detailed-level instrumentation.** Put `AppLog.v(...)` calls at the DJI equivalents of
   the Autel positions:
   - The home screen, the TAK connect screen, the data-sync screen and the flight screen: the
     lifecycle methods, each button touch and each toggle change.
   - `TakDropMarkers.kt` and `TakMapMarkers.kt`: logs for a marker that is placed, sent or deleted.
     The code there probably logs only failures.
   - `DroneTakBridge.kt`: a telemetry record every 5 seconds. Do not log at each tick. This is the
     same rate limit that `AutelTakBridge.logHudSnapshot()` uses.
   - `DroneVideoStreamer.kt`: a frame-count summary every 150 frames. Do not log each frame.
6. **Examine the `applicationId` and the manifest `package` value** before you set the FileProvider
   authority. The Autel application has the `applicationId` `com.tak.uastoollite` and the manifest
   `package` `com.autel.sdksample`. **The authority must use the `applicationId`.** Examine the DJI
   values. Do not assume that they are the same.
7. **Do a test.** Set the log to on and to off. Change between the standard level and the detailed
   level and confirm the difference in volume. Confirm that a file is in `Downloads/TAKPilot2 Logs`
   on the device. Confirm that Export opens the share function with a real file. Confirm that Clear
   and Delete operate only on the private copy and that the archive continues to exist. Write more
   than 10 MB and confirm that the application deletes the oldest files.

## 5. Items to examine on the DJI side

- **API levels.** This code was built and tested with `minSdk 21` and `targetSdk 29`. The test
  hardware was API 29 or higher. Therefore only the MediaStore path operated. The legacy `File`
  path was examined in a code review but was not tested on a device. If the DJI values are
  different, test both paths.
- **The size of the archive limit.** The value of `PUBLIC_ARCHIVE_MAX_BYTES` is 10 MB. This is more
  than some hours of logging at the detailed level. If the DJI controller has less free storage, or
  if its detailed level makes more data, change the value. It is one constant.
- **The name of the folder.** The name `TAKPilot2 Logs` is one shared constant. If the two
  applications can operate on the same device, use a different name for each application.
