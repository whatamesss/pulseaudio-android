# PulseServer for Android

An Android app that runs a PulseAudio native protocol server on your phone or tablet. Linux machines can connect over the local network and use the Android device as a remote speaker — no root, no native libraries, no PulseAudio installed on Android.

## How it works

PulseServer implements the PulseAudio native binary wire protocol (tagstruct framing, protocol version 16) entirely in Kotlin. It opens a TCP socket and accepts connections from a Linux PulseAudio daemon using the standard `module-tunnel-sink` module.

- **Playback** — received PCM audio is written to `AudioTrack` (Android speaker)
- **Volume/mute** — PA volume and mute commands update the Android stream in real time
- **Multiple clients** — each TCP connection runs in its own coroutine, so a new connection never interrupts an existing playback stream
- **Playback only** — record/source commands are rejected; no microphone permission is needed

## Setup

### Android

1. Install the app
2. Grant the **Notification** permission when prompted (Android 13+)
3. Set the port if needed (default: `4713`)
4. Tap **Start Server**
5. Note the IP address shown on screen

### Linux

Add to `/etc/pulse/default.pa`, replacing `<ip>` and `<port>` with the values shown in the app:

```
load-module module-tunnel-sink server=tcp:<ip>:<port>
```

Then restart PulseAudio:

```bash
pulseaudio --kill && pulseaudio --start
```

Or connect for a single command without modifying the config:

```bash
PULSE_SERVER=tcp:<ip>:<port> paplay /path/to/file.wav
```

### MPD

If MPD feeds this server through a named output, set **MPD server** (`host` or
`host:port`, default port 6600) and **MPD output** in the app. The output is
enabled when the server starts and disabled when it stops, so MPD does not keep
writing into a tunnel that is gone.

The output name must match MPD's config:

```
audio_output {
    type       "pulse"
    name       "Android"
    server     "android.home.arpa:4713"
    always_on  "no"
    mixer_type "none"
}
```

## Requirements

- Android 8.0 (API 26) or later
- Linux with PulseAudio (tested with PulseAudio 15/16)
- Both devices on the same local network

## Building

```bash
./gradlew assembleRelease
```

The APK lands at `app/build/outputs/apk/release/app-release.apk`.

Release signing uses a local keystore that is **not** committed. Create
`keystore.properties` in the repo root (it is gitignored):

```properties
storeFile=keystore/release.jks
storePassword=…
keyAlias=pulseserver
keyPassword=…
```

and put the keystore at `keystore/release.jks`. Keep both safe — losing them
means you can't update an installed copy without uninstalling it first. If
`keystore.properties` is missing, `assembleRelease` still works but produces an
unsigned APK.

Install it on a connected device:

```bash
./gradlew installRelease
```

Run tests:

```bash
./gradlew testReleaseUnitTest
```

Run all quality checks (lint, detekt, coverage):

```bash
./gradlew qualityCheck
```

## Project structure

```
app/src/main/java/us/foxtek/pulseserver/
├── proto/
│   ├── PATag.kt               # PA protocol constants and opcodes
│   ├── PulseFrame.kt          # Wire framing (20-byte header + payload)
│   ├── TagStructReader.kt     # PA tagstruct deserialisation
│   ├── TagStructWriter.kt     # PA tagstruct serialisation
│   ├── PulseClientHandler.kt  # Per-connection protocol state machine
│   ├── ServerState.kt         # Shared volume/mute state (atomic)
├── audio/
│   ├── AudioPlayer.kt         # AudioTrack wrapper (PCM playback)
│   └── PcmAudioQueue.kt       # Bounded buffer between network and AudioTrack
├── service/
│   └── PulseAudioService.kt   # Foreground service, ServerSocket lifecycle
├── ui/
│   ├── MainActivity.kt        # Single-screen UI
│   ├── PortPreferences.kt     # SharedPreferences helper for port setting
│   └── UiHelpers.kt           # Permissions and battery-exemption helpers
└── assistant/
    └── AssistantVoiceInteractionService.kt  # VoiceInteractionService skeleton
```

## License

MIT — see [LICENSE](LICENSE).
