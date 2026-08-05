# Low-bandwidth video — development notes

**Written in Simplified Technical English (ASD-STE100).**

> ## THIS DOCUMENT IS SUPERSEDED
>
> The function that this document describes is **removed**. The class
> `LowBandwidthTranscoder.kt` does not exist. No source file refers to `lowBandwidth` or to the
> preference `video_low_bw`.
>
> The video path changed completely. Read section 4 for the design that replaced it.
>
> This document is kept because it records the reason for the change. Do not use it as a
> description of the application.

## 1. What the removed function did

The application had an optional "Low Bandwidth" mode for the RTSP push. When the pilot set it to
on, the application did these operations:

- It changed the stream to 480p, 15 frames per second, H.264 baseline, approximately 600 kbit/s.
- It added `-Low` to the stream path. The stream was then a different feed on the server.
- It stopped the full-resolution stream. Only one stream operated at a time.
- It did not change the video on the screen of the pilot. Only the network stream became smaller.

## 2. Why that design was necessary at the time

The video path took the encoded frames directly from the aircraft. `AutelCodecListener
.onFrameStream()` gave H.264 or H.265 Annex-B frames that the aircraft had already encoded. The
application sent those bytes to the RTSP client. There was no transcode, no loss of quality and
almost no CPU load.

This is why a small stream needed new code. The application had no decoded pixels. It could not
change the size. It could not control the resolution or the bitrate of the source. It only moved
opaque bytes.

To get a smaller stream, the application had to decode the frames, make them smaller, and encode
them again. This costs CPU that the standard path was built to avoid. Therefore the function was
optional.

## 3. Why it was removed

The video source changed. The application no longer taps the frames from the aircraft.

The stream is now a **MediaProjection screen capture** of the full flight screen. This includes the
camera picture, the HUD, the map and the AR overlay. The screen capture already goes through an
encoder. Therefore a separate low-bandwidth transcoder is not necessary: the same encoder can use
different settings.

The screen capture also has two advantages that the frame tap did not have. The stream continues
through a link loss or a battery change. There is no second tap on the codec, so there is no
competition with the view that shows the video to the pilot.

## 4. The design that replaced it

`TranscodeProfile.kt` gives the pilot three quality levels. `ScreenCaptureEncoder` uses them.

| Level | Maximum height | Frames per second | Bitrate |
|---|---|---|---|
| LOW | 480 | 10 | 375 kbit/s |
| STANDARD | 720 | 15 | 800 kbit/s |
| HIGH | 1080 | 15 | 1800 kbit/s |

`maxHeight` is a limit on the vertical dimension. It is not a format. The application keeps the
aspect ratio of the screen. Therefore a 4:3 controller at the 720 level gives 960 x 720. It does
not give 1280 x 720.

Each level has one more resolution step than the DJI application. H.265 makes this possible. H.265
gives approximately the same quality as H.264 at approximately half the bitrate. The saving went to
resolution.

The bitrates come from bits per pixel per frame. STANDARD and HIGH use the same value of
approximately 0.077. Therefore a change between these two levels changes the resolution and the
frame rate but does not change the quality of each pixel.

LOW is different. It uses approximately 0.12 bits per pixel per frame. Its purpose is the lowest
TOTAL bitrate, because it must operate on a weak cellular link. Small frames at 10 frames per
second are cheap. Therefore the application can give each pixel more bits.

Screen capture makes the top of this range important. HUD text, map labels and AR labels are sharp
edges. They need many more bits than camera video. They become unclear first. An altitude value
that a person cannot read removes the purpose of the stream.

## 5. Related history

The keyframe pulse is recorded in `VIDEO-STREAM-VBR-FIX.md`. The cause was not the bitrate. The
application asked the encoder for CBR. The HEVC encoder of this chip does not offer CBR. Therefore
the encoder made the I-frames smaller to keep to a limit for each frame. A change to VBR corrected
this.
