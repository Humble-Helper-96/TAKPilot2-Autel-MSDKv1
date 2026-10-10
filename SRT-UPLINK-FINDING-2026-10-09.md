# The first H.265 flight: torn picture at the far end, and what caused it

**Written in Simplified Technical English (ASD-STE100).**

**Date:** 2026-10-09. **Build:** v2.4.0, versionCode 106, the `uasvideo-split` branch.
**Hardware:** Autel Smart Controller V3, EVO II 640T V3. **Transport:** SRT to MediaMTX.
**Component:** `ScreenCaptureEncoder` (encode), `AutelVideoStreamer` (push), MediaMTX (record).

> ## WHAT TO READ IF YOU READ ONE LINE
>
> **The uplink lost video. The encoder, the codec and the recorder were all correct.** The
> operator saw green blocks in a recording and asked whether the transcoding caused them. There
> is no transcoding: MediaMTX writes the stream it receives. The damage was already in what
> arrived.

---

## 1. What was reported

The operator selected H.265 for the first time, flew, and then looked at a recording in the
MediaMTX archive. Large areas of the picture were green blocks. **The operator did not see this
in flight.**

## 2. Why the pilot could not see it, and why that is permanent

The controller shows the video that comes DOWN from the aircraft, decoded on the controller. The
stream that goes UP to the media server is a separate leg. The pilot's screen is upstream of
every fault in this document.

⚠ **THE PILOT'S SCREEN CANNOT REPORT WHAT THE TEAM RECEIVES.** This is a property of the design
and not a defect. `VIDEO-STREAM-VBR-FIX.md` made the same point in August about the 2-second
pulse. Only the far end, or the `link [...]` line in `app.log`, can answer that question.

## 3. What was measured

### 3.1 The encoder was correct

From the controller's own log:

```
screen capture [STANDARD] H.265: 2048x1536 -> 1024x768 @ 15fps 800kbps, 10s IDR,
intra refresh 30f — variant: OMX.qcom.video.encoder.hevc /
full (profile+level, VBR, max-fps) + intra-refresh
```

The hardware encoder accepted the FULL variant: profile and level, VBR, max-fps AND intra
refresh. That is the top rung of the ladder in `ScreenCaptureEncoder`. **H.265 works on this
SoC and needed no fallback.**

### 3.2 The recorded file

Segment `ANC-EVO2-B2-23c1bdb4-Low`, 2026-10-09T22:51:50Z, 126.112 s, 12.07 MB. Pulled from the
MediaMTX playback service and examined with `ffprobe`/`ffmpeg`:

| Property | Value |
|---|---|
| Codec | HEVC, Main profile, level 3.1 |
| Size | 1024x768 |
| Rate | 14.0 fps, 763 kbps |
| Frames | 1767 |
| Keyframes | 12 — spacing 10.0 to 12.1 s |
| **Decode errors** | **24 × `Could not find ref with POC`** |

The keyframe spacing confirms `idrIntervalS(withIntraRefresh) = 10`. The decode errors are the
finding: `Could not find ref` means the decoder tried to build a frame from a reference frame
that is **not in the file**. That is the green-block signature.

### 3.3 The link, from the application's own instrument

`AutelVideoStreamer.countFrame` prints `link [...]` every 10 s at INFO. Its own documentation
names the two failure modes and they BOTH occurred in this flight.

`payload` is the video. `wire` is what the library hands the socket, which includes
retransmission.

```
14:53:39  payload=798   wire=1550     x1.9     drops=0
14:53:49  payload=774   wire=6169     x8.0     drops=0
14:53:59  payload=794   wire=11479    x14.5    drops=0
14:54:09  payload=789   wire=17679    x22.4    drops=0
14:54:19  payload=810   wire=23017    x28.4    drops=11
14:54:39  payload=779   wire=34795    x44.7    drops=79
14:54:50  payload=792   wire=47914    x60.5    drops=86
14:55:30  payload=784   wire=85092    x108.5   drops=86
14:55:50  payload=778   wire=859      x1.1     drops=86   <- recovered
```

A second, milder episode ran 14:59:07 to 14:59:47 and peaked at x30.8. It recovered before the
queue overflowed; `drops` stayed at 0.

**The payload never moved.** 700 to 1000 kbps at 13 to 15 fps for the whole flight, through both
episodes. The encoder did not falter and the codec is not implicated.

## 4. The cause, in sequence

1. The uplink degrades.
2. SRT retransmits what it can. `wire` climbs away from `payload`.
3. The retransmission is itself traffic on the same congested link, so the condition feeds
   itself. `wire` reaches two orders of magnitude above the video.
4. At about x28 the SEND QUEUE overflows. **86 frames are discarded at the controller and never
   sent.**
5. Those frames are references for the frames that follow. The far end, and therefore the
   recording, cannot build the picture.

⚠ **THE CLIP THE OPERATOR LOOKED AT IS THE OTHER FAILURE MODE, NOT THIS ONE.** It ends at
14:53:56, when `drops` was still 0 and `wire` was x8 to x14. Its 24 missing references are
packets that SRT could not repair inside its latency budget, which is **500 ms**
(`VideoTransport.SRT_LATENCY_DEFAULT_MS`). SRT abandons what it cannot recover in the budget and
continues, by design. `countFrame`'s own comment states both cases:

> - `drops` climbing means the SEND QUEUE overflowed … That is BANDWIDTH, and no transport fixes
>   it — the answer is a lower video quality.
> - `drops` at zero with the rate at target, but a torn picture at the far end, means packets are
>   being LOST on the way. That is what SRT recovers and RTSP does not.

Both halves fired in one flight, ninety seconds apart.

## 5. What is NOT the cause

- **Not the transcoding.** MediaMTX records the received stream. There is no re-encode.
- **Not H.265.** The payload rate and the frame rate held through both episodes, and the hardware
  encoder took the full variant on the first attempt.
- **Not the recorder.** The file is a faithful copy of damaged input.
- **Not the 10 s IDR interval.** It does not cause a loss. It governs how long a damaged region
  survives before a full repair, and the intra-refresh band repairs in about 2 s between IDRs.

## 6. ⚠ Do not quote the 85 Mbps

85000 kbps is not physically possible on this uplink. `wire` is the library's own counter of what
it hands the socket, and under SRT it counts the MPEG-TS packing and every retransmission; a
requeued packet may be counted more than one time. **The SHAPE is reliable and it tracks `drops`
exactly. The absolute number is not.** Use it as a ratio against `payload`, never as a bandwidth.

## 7. Open, and for the operator to decide

Nothing in this document is corrected in code. Three levers exist and each has a cost:

| Lever | Helps | Costs |
|---|---|---|
| SRT latency (`VideoTransport.SRT_LATENCY_DEFAULT_MS`; Debug screen has a field override) — **was 500 ms here, and is 1000 ms from the v2.4.0 re-cut (versionCode 110)** | The clip in §3.2 — more time to repair a loss | Delay, on every frame |
| A lower video quality | The queue overflow in §3.3 — less video than the link must carry | Picture quality for the whole flight |
| A longer SRT latency AND a lower quality | Both | Both |

⚠ **THE FIRST TWO FIX DIFFERENT FAULTS.** A lower quality does nothing for a packet lost beyond
the budget, and more budget does nothing for a queue that is overflowing. Read the `link` line
before choosing: `drops` is the discriminator.

⚠ **A SECOND FLIGHT IS NEEDED BEFORE ANY OF THIS IS TUNED.** One flight on one day on one network
is one sample. The same route on H.264 would say whether the condition is the link or the
bitrate, because the two codecs carry different resolutions at the same target rate.

## 8. Evidence

- `SRT-UPLINK-FINDING-2026-10-09-evidence.txt` — the `link` lines and the encoder variant line
  from BOTH sessions, extracted and committed. ⚠ The controller's own `app.log` is NOT in this
  repository: `flight-logs/` is gitignored, so the extract is what survives.
- The recording: MediaMTX path `ANC-EVO2-B2-23c1bdb4-Low`, segment start
  `2026-10-09T22:51:50.497142Z`, duration 126.112 s. Reachable from the playback service while
  the archive holds it. ⚠ The archive has a retention limit; the file is not kept in this
  repository, and the measurements in §3.2 are what survives it.
- The `ffprobe`/`ffmpeg` results in §3.2 are reproducible against that segment.

---

## 9. The follow-up, the same afternoon — and why it does not close this

The operator raised the SRT latency to **1 s**, raised the stream to **1440x1080 at 1800 kbps**
(the HIGH profile), and started LIVE on the ground. No green blocks. The media server reported
`PKT LOSS 0.00 %` and `NACK 0.0/s`.

The encoder again took the top rung at the larger size:

```
screen capture [HIGH] H.265: 2048x1536 -> 1440x1080 @ 15fps 1800kbps, 10s IDR,
intra refresh 30f — variant: OMX.qcom.video.encoder.hevc /
full (profile+level, VBR, max-fps) + intra-refresh
```

⚠ **THIS IS ENCOURAGING AND IT IS NOT YET A RESULT.** Put the controller's own instrument from
the two sessions side by side:

| | SRT latency | Profile | Samples | mean wire/payload | worst | drops |
|---|---|---|---|---|---|---|
| After the change | 1 s | 1440x1080 @ 1800k | 20 (3.3 min) | 1.10x | 1.30x | 0 |
| The flight, FIRST 10 MINUTES | 500 ms | 1024x768 @ 800k | 22 (3.7 min) | 1.13x | 1.35x | 0 |

**The clean stretch that preceded the tearing is statistically identical to the follow-up.** The
first storm in the flight began at 14:53:49, about ten minutes in. The follow-up ran 3.3 minutes.
It has not yet met the condition that broke the flight, so it cannot separate "1 s corrected it"
from "the bad patch did not happen".

⚠ **THREE VARIABLES MOVED TOGETHER** and none is controlled: the latency budget, the resolution
and bitrate, and the radio conditions — the flight was airborne at distance, the follow-up was
static on the ground. Even a long clean run in this configuration would not attribute the
improvement to the latency.

**What would settle it:** fly the same route and profile again and watch one number. The
discriminator is already in the log and needs no new code — `wire` above about 3x `payload` is
the condition, and `drops` leaving 0 is the overflow behind it.

⚠ **THE AIRCRAFT LINK FAILING IS A DIFFERENT LINK AND IS NOT THIS FAULT.** The follow-up shows
"Waiting for aircraft…" repeatedly while the uplink stayed clean at `drops=0`. The screen capture
continued to encode and push correctly with no aircraft video in it. A downlink dropout and an
uplink loss look nothing alike in the log, and nothing alike on the far end: one gives a held or
empty picture, the other gives green blocks. See the 2026-10-09 RF investigation for the
downlink.

---

## 10. The SRT advertisement, bench-proved the same evening

The operator tried the advertised SRT url in a real ATAK and it did not connect. MediaMTX
answered `closed: invalid passphrase` on every attempt. ATAK's own log named the cause:

```
[VideoDropDownReceiver]: ConnectionEntry [address=anchortak.link, alias=ANC-EVO2-B2, port=8890,
 path=?streamid=read:ANC-EVO2-B2-3cab5c2f-Low:anc:red-house-three&passphrase=TentCoty-1914,
 protocol=srt, networkTimeout=12000, bufferTime=-1]
```

The server holds `srtReadPassphrase = TentCity-1914`. The CoT carried `TentCoty-1914`. The
operator had mistyped the read passphrase on the Video Servers card.

✅ **THE CoT IS CORRECT AND THIS IS THE PROOF THE RELEASE DID NOT HAVE.** ATAK read the address,
the port, the protocol and the WHOLE `?streamid=…&passphrase=…` string out of
`ConnectionEntry.path`, exactly as `srt-cot-video-advertising.md` specifies, and then appended
its own `&timeout=12000000` from `networkTimeout`. It reached the SRT handshake. Everything this
application builds is downstream-clean; only the key was wrong.

⚠ **A MASKED FIELD HIDES A TYPO, AND ONLY A FAILED CONNECTION REVEALS IT.** The read passphrase
is `textPassword` and the preview masks it to `***` — correct for a secret that is read over a
pilot's shoulder and screenshotted into training material, and it cost an evening here. If it
recurs, the fix is a show/hide control on that ONE field. Do not unmask the preview: that is the
line `urlSafe` exists to hold.

---

## 11. What the operator decided, 2026-10-09

**The SRT publish latency default moved from 500 ms to 1000 ms.** It was folded into a RE-CUT of v2.4.0 (versionCode 110). The one controller already on v2.4.0 had been set to 1 s by hand, so the change only moves what a device gets by DEFAULT. The reasoning is on
`VideoTransport.SRT_LATENCY_DEFAULT_MS`, which now carries both the 2026-08-29 ground test that
chose 500 and this flight that found it short.

⚠ **IT IS A JUDGEMENT AND THE DOCUMENT SAYS SO.** §9 above stands: the 1 s run was 3.3 minutes,
static, and had not met the condition that broke the flight. What makes the change defensible is
not that run but the ORIGINAL reasoning — 3 to 4 times the RTT, and an aircraft at range sees a
worse RTT than any ground test ever will — plus a flight that showed 500 ms was not enough on a
real link. Record it as a decision, not as a measurement.

⚠ **A CONTROLLER WITH A DEBUG-SCREEN OVERRIDE DOES NOT MOVE.** `srtLatencyMs` reads
`KEY_SRT_LATENCY_MS` and falls back to the default only when the key is absent or out of range.
A controller that was set to 1 s by hand already holds 1 s; one that was never touched moves
from 500 to 1000 on upgrade. To put a controller back on the default, clear the box.

**The cost is stated plainly: the team now watches one second behind the aircraft, not half.**
