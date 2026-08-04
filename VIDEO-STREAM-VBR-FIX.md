# Video Stream Fix — VBR + 4s I-Frame Interval

> ## ✅ IMPLEMENTED AND RESOLVED 2026-08-04 — this document is now HISTORY
>
> Kept for the diagnosis and the frame-extraction evidence, which were correct. **The outcome is
> recorded in `TAKPILOT2_AUTEL_PORT_PLAN.md` §"Video streaming — 2-second pixelated pulse"** —
> read that, not this plan, for what the code actually does.
>
> Two of this plan's predictions were WRONG, and the pattern is worth keeping:
>
> - **"VBR support on the SDM660 is unknown and may not exist" / "expected outcome: VBR doesn't
>   work"** (Constraints 1-2, Step 4). VBR is supported and works, decisively: I/P frame-size
>   ratio went 2.53× → 14×, pulse gone. The probe also found the hardware encoder declares
>   **VBR=true, CBR=false** — the old code was requesting a mode the encoder never advertised,
>   which is the actual root cause and something this plan did not anticipate.
> - **The 4s I-frame interval was not kept.** It was insurance against VBR failing; once VBR
>   worked it was paying doubled join latency and loss-recovery time for nothing. Reverted to 2s.
>
> Step 4's on-demand-IDR path was never needed. Step 0's probe was the step that mattered — three
> of the five "hardware constraints" here were written as expected blockers, and the first one
> actually tested was simply false. **Probe the hardware before designing around its limits.**

**Project:** TAKPilot2-Autel
**Component:** `AutelVideoStreamer.kt` (screen-capture → MediaCodec H.265 → RTSP push)
**Date:** 2026-08-04
**Hardware:** Autel Smart Controller V3, Qualcomm SDM660 (Snapdragon 660), Android 11 / API 30

---

## The problem

The outgoing RTSP video stream has a visible periodic "pulse" — a momentary drop in image quality that repeats on a fixed cadence. It is visible only to remote TAK stream viewers, never on the controller's own display. The controller renders via `AutelCodecView` which receives decoded frames directly from the Autel SDK; the pulse is created entirely by our re-encode path (MediaProjection screen capture → MediaCodec HW encoder → RTSP push).

### Root cause (confirmed via frame extraction)

The encoder is configured with **CBR (constant bitrate)** and a forced **IDR every 2 seconds** (`I_FRAME_INTERVAL_S = 2`). At ~14.9 fps that puts a keyframe every 30 frames.

H.265 I-frames encode the entire image from scratch with no motion prediction. They inherently need far more bits than P-frames to achieve comparable visual quality — typically 5–10× more. Under strict CBR, the rate controller cannot allocate those extra bits, so it raises QP (quantization) sharply on the I-frame to stay within the bit budget. The result is a visibly blockier, softer frame at every keyframe, followed by rapid quality recovery over the next few P-frames as the rate controller catches up.

### Evidence from the captured file

`EVO2-B2-Low_2026-08-04_08.10.56.mp4` — 27.6s, H.265 Main, 960×720, ~14.9 fps, 1516 kbps average.

```
GOP structure: strict I-frame every 30 frames (2.0s)
I-frame sizes:  26–29 KB  (~2× average P)
Avg P-frame:    12.4 KB
Min P-frame:    224 B
Max P-frame:    68 KB
```

I-frames are getting only ~2× the bits of an average P-frame — far too little for a full intra-coded frame at this resolution. Visual comparison of extracted frames confirms: mid-GOP P-frames are sharp, the I-frame itself is visibly blockier (tree canopy, fence texture, HUD text edges), and quality recovers within a few frames after each IDR.

This matches exactly with the diagnosis already in `TAKPILOT2_AUTEL_PORT_PLAN.md` §"Video streaming — 2-second pixelated pulse." The bitrate doubling (LOW 275→475k, STANDARD 800→1.6M, HIGH 1.8→3.6M) already applied in v1.3 did not fix the pulse because the fundamental problem is CBR rate-control behavior, not total bitrate — more bits under CBR still spike QP at keyframes.

---

## Hardware constraints — read before implementing

The Smart Controller V3 runs a **Snapdragon 660 (SDM660)** — a 2017 mid-range SoC with a Venus v4.x video processing block, running **Android 11 (API 30)**. This imposes several constraints that directly affect what fixes are actually possible.

### Constraint 1: VBR support on the SDM660 is unknown and may not exist

Whether `OMX.qcom.video.encoder.hevc` on this device declares VBR support depends on what Autel shipped in their `media_codecs.xml` (baked into `/vendor/etc/` or `/system/etc/`). Newer Qualcomm platforms (sm7250+) explicitly declare `<Feature name="bitrate-modes" value="VBR,CBR" />` for the HEVC encoder. The SDM660 is two hardware generations older, and Autel's firmware is a custom locked build with no public record of its codec declarations.

If VBR isn't declared, `MediaCodec.configure()` will either throw an exception or silently fall back to CBR.

### Constraint 2: Even "supported" VBR may behave identically to CBR on older Qualcomm encoders

Documented cases exist of drone controllers using similar-era hardware where switching between VBR, CBR, and CQ produced identical output — the encoder accepted the mode flag without error but its rate controller behaved the same regardless. The Qualcomm OMX encoder on older Venus blocks often implements VBR with an extremely tight rate-controller window, making it functionally indistinguishable from CBR.

### Constraint 3: Android 11 has no VBR quality floor

Starting with Android 12 (API 31), the framework enforces a minimum quality floor (VMAF ≥ 70) when the codec is in VBR mode for resolutions between 320×240 and 1920×1080. This gives VBR real teeth even when the hardware encoder's own VBR is weak. Android 11 has no such enforcement. VBR behavior is 100% at the mercy of the OMX encoder implementation.

### Constraint 4: `KEY_MAX_I_FRAME_QP` does not exist on API 30

This MediaFormat key was introduced in API 31. It cannot be used to clamp keyframe quality on this device. Any code using it will be a no-op or throw at runtime.

### Constraint 5: CQ mode and the `.hevc.cq` encoder variant don't exist on this platform

Newer Qualcomm platforms ship a separate `OMX.qcom.video.encoder.hevc.cq` codec for constant-quality encoding. The SDM660 predates this. CQ encoding is not available.

### What IS guaranteed to work

`KEY_I_FRAME_INTERVAL` is a straightforward integer parameter that every HEVC encoder handles. Changing it from 2 to 4 will work regardless of VBR support. It halves pulse frequency but does not change pulse amplitude.

---

## Step 0 — Query before coding (REQUIRED FIRST STEP)

Before writing any encoder changes, run a capability probe on the actual Smart Controller V3. This determines which implementation path is viable.

Add a one-shot probe that runs at app start (or in the Debug Log screen) and logs:

```kotlin
fun probeEncoderCapabilities() {
    val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
    val hevcEncoder = codecList.codecInfos
        .filter { it.isEncoder }
        .firstOrNull { "video/hevc" in it.supportedTypes }

    if (hevcEncoder == null) {
        Log.e(TAG, "PROBE: No HEVC encoder found!")
        return
    }

    val caps = hevcEncoder.getCapabilitiesForType("video/hevc")
    val encCaps = caps.encoderCapabilities

    val supportsVbr = encCaps.isBitrateModeSupported(
        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
    )
    val supportsCbr = encCaps.isBitrateModeSupported(
        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
    )
    val supportsCq = encCaps.isBitrateModeSupported(
        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
    )

    Log.i(TAG, "PROBE: HEVC encoder=${hevcEncoder.name}" +
        " VBR=$supportsVbr CBR=$supportsCbr CQ=$supportsCq")

    // Also log the complexity range if available
    val complexityRange = encCaps.complexityRange
    Log.i(TAG, "PROBE: complexity range=$complexityRange")
}
```

**Decision gate:**
- `VBR=true` → proceed with VBR implementation (Step 1), but expect it may not actually change behavior (see verification in Step 3)
- `VBR=false` → skip VBR entirely, apply only the 4s interval change and proceed to the on-demand IDR path (Step 4)

---

## Step 1 — Apply the two config changes

### 1a. I-frame interval: 2s → 4s

Find `I_FRAME_INTERVAL_S` (or wherever the interval constant lives) and change it:

```kotlin
// CHANGE: I-frame interval 2s → 4s
I_FRAME_INTERVAL_S = 4
```

At ~14.9fps, this puts I-frames every ~60 frames instead of 30. `KEY_I_FRAME_INTERVAL` is a float in seconds, not a frame count, so this is a one-constant change.

### 1b. CBR → VBR (only if Step 0 showed VBR=true)

In the `MediaFormat` setup before `MediaCodec.configure()`:

```kotlin
// CHANGE: CBR → VBR
format.setInteger(
    MediaFormat.KEY_BITRATE_MODE,
    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR  // was BITRATE_MODE_CBR
)
```

`KEY_BIT_RATE` stays the same — under VBR it becomes the target average, not a ceiling. Do NOT raise bitrates; VBR should redistribute within the same budget.

### What NOT to change

| Parameter | Current | Keep as-is | Why |
|---|---|---|---|
| Bitrate targets | LOW 475k / STD 1.6M / HIGH 3.6M | ✅ | VBR targets the same average — let it redistribute, don't raise it |
| bits/pixel parity across profiles | All three at same bits/pixel | ✅ | The last round deliberately equalised these |
| Codec (H.265) | HEVC | ✅ | Correct for this pipeline |
| Resolution (960×720) | Set by screen capture | ✅ | Not ours to change here |

---

## Step 2 — Add verification logging

### 2a. Encoder start log

Right after `codec.configure()` or `codec.start()`, log the configuration. Note: many OMX encoders do NOT echo back `KEY_BITRATE_MODE` in `getOutputFormat()` — it may always return -1. Log it anyway for the record, but do not rely on it as the sole verification.

```kotlin
val actualFormat = codec.outputFormat
val actualMode = try {
    actualFormat.getInteger(MediaFormat.KEY_BITRATE_MODE)
} catch (e: Exception) { -1 }
val modeLabel = when (actualMode) {
    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR -> "VBR"
    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR -> "CBR"
    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ  -> "CQ"
    else -> "UNKNOWN($actualMode)"
}
Log.i(TAG, "encoder started: ${actualFormat.getString(MediaFormat.KEY_MIME)}" +
    " ${actualFormat.getInteger(MediaFormat.KEY_WIDTH)}x${actualFormat.getInteger(MediaFormat.KEY_HEIGHT)}" +
    " bitrate=${actualFormat.getInteger(MediaFormat.KEY_BIT_RATE)}" +
    " mode=$modeLabel" +
    " iFrameInterval=${actualFormat.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL)}")
```

### 2b. Output frame size logging (this is the REAL verification)

The only reliable way to verify VBR is working on the SDM660 is to measure the actual I-frame vs P-frame size ratio in the encoder output. Add periodic logging of output buffer sizes tagged with whether the frame is a keyframe:

```kotlin
// In the output buffer loop:
val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
if (isKeyFrame) {
    Log.i(TAG, "KEYFRAME: size=${bufferInfo.size} bytes, pts=${bufferInfo.presentationTimeUs}")
} else if (frameCount % 30 == 0) {
    // Log every 30th P-frame to avoid spam
    Log.i(TAG, "P-frame sample: size=${bufferInfo.size} bytes")
}
```

**How to read the results:**
- **VBR working:** I-frame sizes should be 4–8× the P-frame average (vs the current ~2×). P-frames for mostly-static content should be very small.
- **VBR silently ignored:** I-frame sizes remain ~2× the P-frame average, same as CBR. Proceed to Step 4.

---

## Step 3 — Test and confirm

### Immediate (bench, no aircraft needed)

The stream is a screen capture — the aircraft doesn't need to be connected. Start the RTSP push, record the output.

1. **Check the start log** — confirm the requested mode and interval.
2. **Check the frame size log** — I/P ratio is the ground truth, not the reported mode.
3. **Visual spot-check** — record 30+ seconds, extract I-frames and neighbors, compare quality gap. Should be negligible if VBR is working.

### Join latency

With the 4s interval, worst-case time-to-first-frame for a new RTSP subscriber doubles from 2s to 4s. Measure by connecting a viewer (VLC, ffplay, TAK client) at random times, N≥5 trials. Record the distribution.

### Loss recovery

A broken prediction chain now takes up to 4s to self-heal instead of 2s. Worth a deliberate test: kill and restart the stream mid-GOP, measure how long the corruption persists on the viewer side.

### Motion scene (flight)

VBR redistributes bits from low-complexity frames to high-complexity ones. During a gimbal pan or fast flight, complexity rises across all frames and VBR may produce different artifacts than the old periodic pulse. Confirm with a real flight that includes deliberate pans.

---

## Step 4 — If VBR doesn't work (expected outcome on SDM660)

If Step 0 shows VBR=false, or if Step 2b shows the I/P ratio unchanged, VBR is a dead end on this hardware. The 4s interval change from Step 1a still stands. The remaining options, in priority order:

### Option A: On-demand IDR at viewer join (recommended)

Replace the fixed `KEY_I_FRAME_INTERVAL` with a long safety-net interval (e.g. 10–15s) and request an IDR only when a new RTSP subscriber connects:

```kotlin
// Request sync frame on demand
val params = Bundle()
params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
codec.setParameters(params)
```

This eliminates unnecessary periodic IDRs entirely — the pulse only happens once per join, not every N seconds forever. For mostly-static screen capture content, loss recovery between forced keyframes is not a significant concern because P-frames are tiny deltas of a nearly-unchanged image.

**Requires:** the RTSP layer to signal "new subscriber attached" up to the encoder. Pedro's `RtspClient` may not surface this directly — investigate whether its `ConnectCheckerRtsp` callback or the underlying RTSP SETUP/PLAY sequence can be intercepted. If not, a simple "request IDR 500ms after stream start" plus a manual "force keyframe" button on the flight screen covers the common cases.

**Previously rejected options remain rejected:** intra-refresh (complexity plus unknown mid-stream-join behavior) and longer-GOP-alone (the 4s change already covers the "less frequent" axis). These were rejected by the operator and should not be re-proposed.

### Option B: Higher bitrate with 4s interval (diminishing returns)

Already partially tried (bitrate doubling in v1.3). Under CBR, more bits reduce pulse amplitude but don't eliminate it — the rate controller behavior is the same, just with more headroom. The 4s interval helps more than additional bitrate because it halves the number of I-frames competing for budget. If going further down this path, the constraint is the Wi-Fi link budget on site — 3.6M (HIGH) is already substantial for a shared tactical hotspot.

### Option C: Software encoder (last resort)

`c2.android.hevc.encoder` reliably honors VBR and CQ, but runs on the CPU. The Smart Controller V3 is already running the flight app, TAK client, telemetry bridge, and screen capture — adding a software HEVC encode at 960×720@15fps on a Snapdragon 660 (Kryo 260 cores, 2.2 GHz max) would be measurable. Only consider if the hardware encoder is truly intractable AND the pulse is operationally unacceptable at 4s intervals.

---

## Record the result

Same pattern as the existing calibration items in `TAKPILOT2_AUTEL_PORT_PLAN.md` §7 — one bench session resolves it:

- `isBitrateModeSupported(BITRATE_MODE_VBR)` result on the Smart Controller V3
- Actual I-frame size ratio (I/P average) before and after
- Whether the pulse is visually eliminated, reduced, or unchanged
- Join-latency distribution (N≥5 samples) at 4s interval
- Loss-recovery duration (measured, not assumed) at 4s interval
- Motion-scene behavior if tested in flight

Update the "Video streaming — 2-second pixelated pulse" section in the port plan doc with the outcome, and retire the "VBR at the same average bitrate remains untried" note — it's been tried (or confirmed unavailable).
