# TAKPilot2 Low Bandwidth Video — Dev Notes

**Status:** built and code-reviewed, **not yet verified on hardware** — there was no aircraft attached during development, so the actual decode/encode pipeline has never processed a real H.264/H.265 stream. Everything that could be verified without one (compiles clean, UI wiring, URL naming, graceful no-aircraft behavior) has been. Treat the numeric tuning (bitrate especially) as a starting point, not a calibrated value — see §5.

## 1. What it is

An opt-in "Low Bandwidth" mode for the RTSP video push to the media server. When off (default), behavior is unchanged: the aircraft's already-encoded frames go straight to the RTSP client with zero transcode, as before. When on:

- The stream pushed to the media server is transcoded down to **480p, 15fps, H.264 baseline, ~600kbps** — small enough to survive a weak cellular uplink.
- The stream's path gets **`-Low` appended** (e.g. `Feed-B` → `Feed-B-Low`), so it shows up as a distinct, identifiable feed on the server rather than silently replacing the original.
- **The full-resolution stream to the media server stops** while Low Bandwidth is running — only one of the two is ever live.
- **The pilot's on-screen video is untouched** — still native resolution, unaffected by this toggle. Only what goes out over the network shrinks.

This exists because the normal path is a deliberate zero-transcode passthrough (see §2) — great for quality and CPU, bad for a link that can't sustain the aircraft's native bitrate. Low Bandwidth mode is the escape hatch for that second case, kept off the critical path so it costs nothing when not needed.

## 2. Why this wasn't already possible

The Autel port's video path (`AutelVideoStreamer.kt`) is architecturally different from — and better than — the DJI original specifically *because* it avoids transcoding. DJI's SDK only exposes a decoded surface, forcing the DJI app to re-encode (surface → MediaCodec encoder → RTSP) just to get anything onto the wire. Autel's `AutelCodecListener.onFrameStream()` hands the app **already-encoded** H.264/H.265 Annex-B frames straight from the aircraft, so the existing passthrough path just relays those bytes into the RTSP client — zero transcode, zero quality loss, near-zero CPU.

That's also exactly why it took new code to get 480p/15fps/high-compression out of it: the app never had decoded pixels to scale, and no control over the source's resolution or bitrate — it was just relaying opaque bytes. Getting a smaller stream out meant building, as an *optional* mode, the same kind of decode→re-encode pipeline the DJI app is forced to run all the time. The tradeoff is real and intentional: Low Bandwidth mode spends real CPU that the default path was specifically built to avoid, which is why it's toggle-gated rather than the default.

## 3. How it's built

### 3.1 Where the toggle lives

`AutelVideoStreamer.VideoConfig` gained one field:

```kotlin
data class VideoConfig(
    val host: String, val port: Int, val username: String, val password: String,
    val streamId: String, val tcp: Boolean,
    val lowBandwidth: Boolean = false,
)
```

`path()` appends `-Low` to the stream identifier when the flag is set — and because `pushUrl()`, `advertiseUrl()`, and `urlSafe()` all build on `path()`, that one change automatically makes the RTSP push target, the URL advertised in the drone's CoT (so other TAK clients pull the correct feed), and the on-screen preview all agree. No separate wiring needed.

The **"only one stream at a time" requirement needed no new code at all**: `VideoStreamerHolder` already only ever holds a single `AutelVideoStreamer` instance, and starting a new one always stops whatever was running first. Toggling Low Bandwidth just changes which `VideoConfig` gets built before `start()` is called — there was never a code path that could run both simultaneously.

The checkbox itself lives in `TakConnectActivity`'s video setup section (`activity_tak_connect.xml`, next to the existing TCP-transport checkbox), persisted to the same `SharedPreferences` the rest of the video config uses (`video_low_bw`), and read by `VideoStreamerHolder.startFromPrefs()` — so the Home screen's quick toggle and the flight screen's video button both respect it automatically, not just the TAK Setup screen's own Start Video button.

### 3.2 The transcoder itself (`LowBandwidthTranscoder.kt`)

A new, self-contained class instantiated only when `lowBandwidth` is true, and only once the existing SPS/PPS-sniffing logic in `AutelVideoStreamer` confirms it has valid parameter sets from the source stream (the same gate the passthrough path already used). Standard Android **decode → scale → re-encode** pipeline:

1. **Decode** — a `MediaCodec` decoder configured for the source's actual codec (H.264 or H.265, detected the same way the existing passthrough sniffing already detects it). No explicit `csd-0`/`csd-1` are set at configure time; the raw Annex-B bytes (which already carry inline SPS/PPS/VPS before every keyframe, per the existing sniffing code's own doc comment) are fed directly as decoder input, and the decoder parses them itself — simpler and more portable than trying to hand-construct codec-specific-data across two possible codecs.
2. **Scale** — once the decoder reports its real output dimensions (via `INFO_OUTPUT_FORMAT_CHANGED`), an encoder is lazily created sized to **480p height, width computed to preserve the source's aspect ratio** (not a hardcoded 16:9 — the actual sensor aspect isn't knowable without hardware). Each decoded frame's YUV planes are downsampled via **nearest-neighbor decimation** into the encoder's input, using the `Image`/`Image.Plane` API on both ends — this abstracts away the actual pixel/row stride layout (NV12 vs I420 etc.), so there's no hand-rolled color-format-specific byte-packing code to get subtly wrong per device.
3. **Throttle to 15fps** — the source is decoded at its native frame rate (frames can't be selectively skipped *before* decode without breaking inter-frame prediction), but only one decoded frame every ~66ms is actually forwarded into the scale+encode step; the rest are decoded and discarded. This is a normal, standard way to do frame-rate downconversion.
4. **Re-encode** — H.264 baseline profile, `TARGET_BITRATE` (600kbps), 2s I-frame interval. The encoder's own codec-config output (its SPS/PPS — *not* the source's) is split out and handed back via a callback so `AutelVideoStreamer` can call `client.setVideoInfo(...)` with the *right* parameter sets for the stream that's actually being sent.

All of this runs on its own `HandlerThread`, fed by a small bounded queue (`ArrayBlockingQueue`, capacity 6) that **drops the oldest pending frame** if the transcoder falls behind, rather than growing unbounded or blocking the SDK's own frame-delivery callback. Every failure path is caught and logged rather than propagated — this is explicitly a best-effort mode; a dropped or glitched frame on the low-bandwidth stream is an acceptable cost, a crashed video pipeline is not.

### 3.3 Wiring into the existing frame path

In `AutelVideoStreamer`'s `codecListener.onFrameStream()`, the branch on `config.lowBandwidth` is minimal:

```kotlin
if (config.lowBandwidth) {
    transcoder?.submit(videoBuffer, size, isIFrame)
} else {
    // unchanged passthrough: client.sendVideo(...) directly
}
```

`stop()` releases the transcoder alongside the existing RTSP client teardown. Nothing about the passthrough path's code changed — the branch is additive.

## 4. What's deliberately *not* changed

- **`AutelCodecView`** (the pilot's on-screen video in `FlightActivity`) reads directly from the SDK, entirely separate from `AutelVideoStreamer`. Low Bandwidth mode never touches it — confirmed by inspection, since the transcoder only consumes frames already being delivered to `AutelVideoStreamer`'s own listener, not a new/competing one.
- **The passthrough path's behavior when `lowBandwidth=false`** — every part of the original zero-transcode flow (SPS/PPS sniffing, `client.setVideoInfo`, `client.sendVideo`) is byte-for-byte the same code path as before this feature existed.

## 5. What needs a real first test

Same spirit as the calibration table in the project handoff doc — these are informed choices, not verified ones:

| Item | Constant / location | Note |
|---|---|---|
| Target bitrate | `LowBandwidthTranscoder.TARGET_BITRATE` (600kbps) | Picked as a reasonable "survives weak cellular" starting point for 480p/15fps H.264; tune after a real network test. Single constant, trivial to change. |
| Software scaling performance | `downsamplePlane()` | Nearest-neighbor decimation via plain Kotlin loops (no GL/EGL), chosen over a GPU-based Surface-to-Surface pipeline for implementation robustness without hardware to test against. Should comfortably fit a 15fps budget on modern hardware, but has never been profiled on the actual Smart Controller V3. |
| Decoder without explicit `csd-0`/`csd-1` | `LowBandwidthTranscoder.ensureDecoder()` | Relies on Android decoders parsing inline Annex-B parameter sets, which is standard behavior but device/decoder-implementation dependent — worth confirming on real hardware, especially for whichever codec (H.264 vs H.265) the EVO II actually emits. |
| Frame queue capacity | `LowBandwidthTranscoder.QUEUE_CAPACITY` (6) | Arbitrary buffer against transient stalls before dropping frames; no real-world backpressure data yet. |
| Encoder codec-config split | `handleCodecConfig()` | Assumes the encoder emits SPS+PPS concatenated in one buffer with standard Annex-B start codes, which is typical Android encoder behavior but hasn't been observed on the actual target hardware. |

## 6. Files touched

| File | Change |
|---|---|
| `com/autel/sdksample/tak/LowBandwidthTranscoder.kt` | New — the entire decode/scale/encode pipeline. |
| `com/autel/sdksample/tak/AutelVideoStreamer.kt` | `VideoConfig.lowBandwidth` field + `-Low` suffix in `path()`; transcoder instantiation/wiring in `codecListener`; `transcoder?.release()` in `stop()`; `startFromPrefs()` reads the persisted flag. |
| `com/autel/sdksample/tak/TakConnectActivity.kt` | New checkbox wired into `buildConfig()`, the live URL preview, and pref persistence (`video_low_bw`). |
| `res/layout/activity_tak_connect.xml` | New "Low bandwidth (480p/15fps, transcoded, appends -Low)" checkbox, next to the existing TCP-transport checkbox. |
