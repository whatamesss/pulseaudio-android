# PulseServer for Android 1.0.1

First published release of the playback-focused fork.

## Highlights

- **Buffered audio playback (main improvement)** — incoming PCM now goes
  through a bounded ~300 ms queue, with a dedicated writer coroutine feeding
  `AudioTrack`. Slow speaker writes no longer stall the network receive path;
  the oldest audio is dropped if the queue fills, and pause, flush, drain, and
  shutdown are handled explicitly.
- **Playback only** — microphone capture and the `RECORD_AUDIO` permission are
  removed. Record/source protocol commands are rejected.
- **One client at a time** — extra PulseAudio connections are refused so two
  devices cannot fight over playback.
- **MPD output control** — starting/stopping the server enables/disables the
  configured MPD output.
- **Release-only builds** — signed release APKs are built from the release
  variant; debug builds are disabled.

## Install

Download `pulseserver-1.0.1-release.apk` and sideload it. The app requires
Android 8.0 (API 26) or later.
