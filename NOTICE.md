# NOTICE

TAKPilot2-Autel contains software from third parties.

## The vendored SRT client

`app/src/main/java/com/pedro/srt/` and `app/src/main/java/com/pedro/common/` are taken from
RootEncoder (Copyright pedroSG94), licensed under the Apache License, Version 2.0. The source
is at https://github.com/pedroSG94/RootEncoder, tag `2.4.7`.

`com/pedro/srt/` is the whole `srt` module. `com/pedro/common/` holds the six files that the
`srt` module uses: `ConnectChecker`, `Extensions`, `BitrateManager`, `TimeUtils`, `AudioCodec`
and `VideoCodec`. The rest of the `common` module and the AV1 parser are not included.

Changes made to the vendored source, each marked `TAKPILOT2 CHANGE` in the file:

- `SrtClient` sends a 250 ms receiver delay in the handshake, not the library's 120 ms.
- `SrtClient.setAuthorization` threw `TODO()` with no message. It now throws an exception
  that says what to do instead. SRT has no user/password field.
- Seven enum files called `Enum.entries`, which is a Kotlin 1.9 idiom. They call `values()`,
  which is the same list. This is what lets Kotlin 1.7.20 compile the module.

The RTSP client (`com.github.pedroSG94.rtmp-rtsp-stream-client-java:rtsp:2.2.6`) is by the
same author, under the same licence. It is a dependency, not vendored.

A copy of the Apache License, Version 2.0 is at http://www.apache.org/licenses/LICENSE-2.0
