# Video stream: the 2-second pulse and the VBR correction

**Written in Simplified Technical English (ASD-STE100).**

> ## THIS DOCUMENT IS HISTORY
>
> The fault was corrected on 4 August 2026.
>
> This document keeps the diagnosis and the measurements, which were correct. It also keeps the
> record of two predictions that were wrong.
>
> For the design of the current code, read the section "Video streaming" in
> `TAKPILOT2_AUTEL_PORT_PLAN.md`. Do not use this document as a description of the application.

**Component:** `ScreenCaptureEncoder` (screen capture, then MediaCodec H.265, then RTSP push)
**Hardware:** Autel Smart Controller V3, Qualcomm SDM660, Android 11 (API 30)

## 1. The lesson to keep

**Test the hardware before you make a design around its limits.**

This plan listed five hardware constraints. Three of them were written as expected blockers. The
first constraint that was tested was simply false.

The probe in section 5 was the step that had value. The other steps were not necessary.

## 2. What was wrong

The outgoing RTSP video had a pulse. The picture quality decreased for a moment. This happened at a
constant interval.

Only the remote viewers saw the pulse. The controller screen did not show it. The controller shows
the video from a different path.

## 3. The cause

The encoder was configured for **CBR** (constant bitrate). It was also configured to make an IDR
frame every 2 seconds. At approximately 14.9 frames per second this gives a keyframe every 30
frames.

An H.265 I-frame contains the full picture. It has no motion prediction. Therefore it needs many
more bits than a P-frame for the same quality. The usual ratio is 5 to 10 times more.

With strict CBR the rate controller cannot give those bits. Therefore it increases the quantization
of the I-frame to stay in the budget. The result is an I-frame that a person can see is not sharp.
The quality then becomes better through the next P-frames.

### 3.1 Measurements from a captured file

The file was 27.6 seconds, H.265 Main, 960 x 720, approximately 14.9 frames per second, 1516 kbit/s.

```
Structure:      one I-frame every 30 frames (2.0 s)
I-frame size:   26 to 29 KB   (approximately 2 times the average P-frame)
Average P-frame: 12.4 KB
Minimum P-frame: 224 B
Maximum P-frame: 68 KB
```

An I-frame received only approximately 2 times the bits of an average P-frame. This is much too
few for a full intra-coded frame at this resolution.

A comparison of the extracted frames confirmed this. The P-frames in the middle of the group are
sharp. The I-frame is not sharp. This is easy to see on the tree canopy, the fence and the edges of
the HUD text.

### 3.2 Why more bits did not correct it

An earlier change made the bitrates two times larger. This did not remove the pulse.

The cause is the behaviour of the CBR rate controller. It is not the total bitrate. With CBR, more
bits still give a high quantization at each keyframe.

## 4. The two predictions that were wrong

**Prediction 1: "VBR support on the SDM660 is unknown and may not exist."** This was wrong. VBR is
supported and it operates correctly. The I-frame to P-frame size ratio changed from 2.53 to 14. The
pulse stopped.

The probe also found that the hardware encoder declares **VBR=true and CBR=false**. Therefore the
old code asked for a mode that the encoder never offered. This was the true cause. This plan did not
predict it.

**Prediction 2: "Use a 4-second I-frame interval."** This change was not kept. It was protection
against a failure of VBR. When VBR operated, the longer interval only made the join time and the
loss-recovery time two times longer. The interval went back to 2 seconds.

## 5. The probe that gave the answer

This code found the true cause. Run a probe of this type before you make a design around a limit.

```kotlin
fun probeEncoderCapabilities() {
    val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
    val hevcEncoder = codecList.codecInfos
        .filter { it.isEncoder }
        .firstOrNull { "video/hevc" in it.supportedTypes }

    if (hevcEncoder == null) {
        Log.e(TAG, "PROBE: No HEVC encoder found")
        return
    }

    val encCaps = hevcEncoder.getCapabilitiesForType("video/hevc").encoderCapabilities
    val vbr = encCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
    val cbr = encCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
    val cq  = encCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)

    Log.i(TAG, "PROBE: encoder=${hevcEncoder.name} VBR=$vbr CBR=$cbr CQ=$cq")
}
```

## 6. The correction

The change was one line:

```kotlin
format.setInteger(
    MediaFormat.KEY_BITRATE_MODE,
    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR   // was BITRATE_MODE_CBR
)
```

`KEY_BIT_RATE` did not change. With VBR it is the target average. It is not a limit. The bitrates
were **not** increased. VBR moves the bits inside the same budget.

The bitrates that had been made two times larger to hide the pulse went back to their first values.
This is recorded in `TranscodeProfile.kt`.

## 7. How to prove that VBR operates

Do not trust the mode that the encoder reports. Many OMX encoders do not report `KEY_BITRATE_MODE`
in `getOutputFormat()`. They return -1.

**Measure the size of the frames.** This is the true test.

```kotlin
val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
if (isKeyFrame) {
    Log.i(TAG, "KEYFRAME: size=${bufferInfo.size} bytes")
} else if (frameCount % 30 == 0) {
    Log.i(TAG, "P-frame sample: size=${bufferInfo.size} bytes")
}
```

How to read the result:

- **VBR operates:** the I-frame size is 4 to 8 times the average P-frame size.
- **VBR does not operate:** the I-frame size stays at approximately 2 times the average P-frame
  size. This is the same as CBR.

The measured result after the change was a ratio of 14. The pilot confirmed on the live stream that
the pulse had stopped.

## 8. Constraints of this hardware that are still true

These items were not the cause, but they are correct and they limit future work.

- **Android 11 has no quality floor for VBR.** Android 12 (API 31) applies a minimum quality when
  the codec uses VBR. Android 11 does not. Therefore the behaviour of VBR depends fully on the OMX
  encoder.
- **`KEY_MAX_I_FRAME_QP` does not exist on API 30.** It came in API 31. Code that uses it does
  nothing or fails.
- **CQ mode and the `.hevc.cq` encoder do not exist on this platform.** Newer Qualcomm platforms
  have a separate codec for constant quality. The SDM660 is older.

## 9. Options that were refused

These options were examined and refused. Do not propose them again.

- **Intra-refresh.** It is complex, and its behaviour when a viewer joins in the middle of the
  stream is not known.
- **A longer group of pictures alone.** This makes the pulse less frequent. It does not make the
  pulse smaller.
- **A software encoder** (`c2.android.hevc.encoder`). It obeys VBR correctly, but it operates on the
  CPU. The controller already operates the flight application, the TAK client, the telemetry bridge
  and the screen capture. This is not necessary now that the hardware encoder operates correctly.
