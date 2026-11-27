# PulseServer for Android 1.0.2

Adds user control over playback buffering.

## Highlights

- **Adjustable audio buffer** — a new slider sets the playback queue from
  0 ms to 3000 ms (1000 ms by default). Raise it if playback hiccups; lower it
  to reduce latency. The choice is persisted across launches.
- **Buffer size advertised to the client** — the selected size is sent to the
  PulseAudio client as the stream `tlength`, so client bursts no longer
  overrun the app's queue. Oversized packets are still played when the buffer
  is set very small.
- The buffer setting applies to new playback streams; stop and restart the
  server after changing it.

## Install

Download `pulseserver-1.0.2-release.apk` and sideload it. The app requires
Android 8.0 (API 26) or later.
