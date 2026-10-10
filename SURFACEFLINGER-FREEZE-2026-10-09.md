# The controller froze solid: a stalled encoder wedged SurfaceFlinger

**Written in Simplified Technical English (ASD-STE100).**

**Date:** 2026-10-09. **Build:** v2.4.0, versionCode 108. **Hardware:** Autel Smart Controller V3.
**Configuration:** H.264 High, 1024x768, SRT publish, 1000 ms latency (Debug override).
**Component:** `ScreenCaptureEncoder` (MediaProjection + MediaCodec), and the platform below it.

> ## WHAT TO READ IF YOU READ ONE LINE
>
> **A stalled video encoder stopped a fence from signalling, and SurfaceFlinger waited on that
> fence FOR EVER. The whole display died. Only a reboot recovered it.** The application cannot
> stop the platform doing this, but it CAN stop presenting it with a stalled encoder — see §7.

---

## 1. What was seen

The operator was running an SRT test on the bench. The application "locked up solid". The
aircraft was not flying.

## 2. The deadlock, with the evidence for each link

The chain was measured on the live, wedged controller before it was rebooted. The stacks are in
`SURFACEFLINGER-FREEZE-2026-10-09-evidence.txt`.

| # | Who | State |
|---|---|---|
| 1 | SurfaceFlinger MAIN thread | `Fence::waitForever` ← inside `queueBuffer` for a `RenderSurface`, from `Display::finishFrame` → `Output::present` |
| 2 | Every display | SurfaceFlinger composes nothing more, for any display |
| 3 | Our RenderThread | blocked in `Surface::queueBuffer`, a binder call that can never return |
| 4 | Our main thread | blocked in `DrawFrameTask::postAndWait`, waiting for its RenderThread |
| 5 | Android | files an ANR against us |

⚠ **THE NAME OF THE FUNCTION IS THE WHOLE FINDING.** `Fence::waitForever` has NO TIMEOUT. A GPU
release fence that never signals stops the system compositor permanently. Nothing in the
application, and nothing short of a reboot, recovers it.

**Which display?** Our own: the MediaProjection VirtualDisplay `TAKPilot2Stream`, 1024x768,
`state ON`, `owner com.tak.uastoollite`, confirmed present at the time of capture. Its consumer
is the MediaCodec input surface. When the encoder stops releasing buffers, that fence never
signals.

## 3. ⚠ The timestamps say the freeze began 11 minutes before the ANR

The recording on the media server stops at **02:05:20 UTC (18:05:20 local)**. The ANR was filed
at **18:16:27**. An ANR fires on an input timeout, so the gap is the operator not touching the
screen. **The event to look for in any log is the stream going quiet at the SERVER, not the
application visibly locking.**

## 4. ⚠ Recovery: only a reboot works, and that is measured

| Attempt | Result |
|---|---|
| `am force-stop` the application | app gone; display STILL dead |
| `killall surfaceflinger` | respawned as a new pid; display STILL dead after 60 s |
| `adb reboot` | **recovered** |

⚠ **KILLING THE PRODUCER DOES NOT RELEASE A WAIT ON A FENCE THAT IS ALREADY DEAD.** Do not spend
time on the soft options in the field. If a controller does this, reboot it.

## 5. It is INTERMITTENT, and that is established

The same build, the same codec and profile, the same 1000 ms budget and the same bench were run
again for **49 minutes** — longer than the ~34 minutes the frozen session managed — and did NOT
reproduce it:

```
49.0 min   mean wire/payload 1.14x   worst 1.58x   cumulative drops = 0
publisher: ONE unbroken SRT connection, 335 MB, RTT 2.6 ms
6 encoder dips to 10.9-11.8 fps, every one self-healed within 10-20 s
19 aircraft downlink restarts, none of which touched the uplink
```

So the freeze is not a reliable consequence of this configuration. One occurrence in two
sessions is not a rate, and the trigger is unknown.

## 6. What is NOT the cause

- **Not the SRT latency.** The freeze happened AT 1000 ms, and 49 clean minutes at 1000 ms
  followed. ⚠ 500 ms is NOT a known-good baseline — it is only the older default, and nothing
  has tested it against this failure. Two clean runs at different settings cannot be compared.
- **Not the uplink.** `drops=0` for the whole 49-minute run, and the publish connection never
  broke.
- **Not our Java code.** The main thread was in the platform's draw path, not in anything this
  application wrote.
- **Not the aircraft downlink, as far as can be shown.** 19 restarts in the clean run caused six
  recoverable encoder dips and no freeze. The correlation between a downlink restart and an
  encoder dip is real and is NOT established as the cause of the deadlock.
- ⚠ **Not the 61 Autel SDK UDP threads, as far as can be shown.** The dump has 185 threads, 61
  of them `SDK 2.0 udp-receive Thread`, and 60 of those waiting on ONE `DatagramSocket` monitor
  held by a thread in a blocking `recvfrom`. That is the SDK's own design and it looks alarming,
  but nothing ties it to the fence. Do not present it as the cause without new evidence.

## 7. The defence, and its honest limit

⚠ **THE APPLICATION MUST NOT BE ABLE TO WEDGE THE COMPOSITOR.** It cannot fix
`Fence::waitForever` — that is the platform's. What it can do is stop feeding a VirtualDisplay
whose consumer has stopped: `ScreenCaptureEncoder` now carries a WATCHDOG that tears the capture
down when the encoder produces no output for `STALL_WATCHDOG_MS` while the stream is live.

⚠ **IT IS NOT A CURE AND MUST NOT BE SOLD AS ONE.** The causality is encoder-stall first,
compositor-wedge second, and the two are seconds apart. If the fence is already dead when the
watchdog fires, the teardown call may itself block and nothing is saved — §4 showed that even
killing the whole process does not release it. What the watchdog reliably converts is the
SURVIVABLE case: an encoder that stops while the compositor is still alive becomes a dropped
stream the pilot can restart, instead of a silent dead feed.

⚠ **THE THRESHOLD IS "NO OUTPUT AT ALL", NEVER "BELOW PROFILE".** This run produced six dips to
10.9-11.8 fps that all recovered. A watchdog that fired on a slow encoder would have killed the
stream six times in 49 minutes on a healthy link. See `ScreenCaptureStallPolicy`.

## 8. Still open

- **The trigger.** Unknown. One occurrence, not reproduced in 49 minutes.
- **Whether the watchdog ever saves the device**, as opposed to only the stream. Unprovable
  without a reproduction.
- **Whether 500 ms behaves differently.** Untested, and untestable without a reproduction.

## 9. Evidence

`SURFACEFLINGER-FREEZE-2026-10-09-evidence.txt` — the four stacks, the recovery results and the
thread census, extracted and committed. ⚠ The full artefacts (a 1.1 MB ANR trace, the app's
thread dump 7 minutes later, and the SurfaceFlinger backtrace) are in
`flight-logs/2026-10-09/freeze-18-16/`, which is **gitignored**. They exist on one machine.
