# PulseServer for Android

An Android app that runs a PulseAudio native protocol server on your phone or tablet. Linux machines can connect over the local network and use the Android device as a remote speaker and microphone — no root, no native libraries, no PulseAudio installed on Android.

## How it works

PulseServer implements the PulseAudio native binary wire protocol (tagstruct framing, protocol version 16) entirely in Kotlin. It opens a TCP socket and accepts connections from a Linux PulseAudio daemon using the standard `module-tunnel-sink` and `module-tunnel-source` modules.

- **Playback** — received PCM audio is written to `AudioTrack` (Android speaker)
- **Capture** — microphone audio from `AudioRecord` is streamed back to the client
- **Volume/mute** — PA volume and mute commands update the Android stream in real time
- **Multiple clients** — each TCP connection runs in its own coroutine; only one client can hold the microphone at a time

## Setup

### Android

1. Install the app
2. Grant the **Microphone** permission when prompted
3. Set the port if needed (default: `4713`)
4. Tap **Start Server**
5. Note the IP address shown on screen

### Linux

Add to `/etc/pulse/default.pa`, replacing `<ip>` and `<port>` with the values shown in the app:

```
load-module module-tunnel-sink   server=tcp:<ip>:<port>
load-module module-tunnel-source server=tcp:<ip>:<port>
```

Then restart PulseAudio:

```bash
pulseaudio --kill && pulseaudio --start
```

Or connect for a single command without modifying the config:

```bash
PULSE_SERVER=tcp:<ip>:<port> paplay /path/to/file.wav
```

## Requirements

- Android 8.0 (API 26) or later
- Linux with PulseAudio (tested with PulseAudio 15/16)
- Both devices on the same local network

## Building

```bash
./gradlew assembleDebug
```

Run tests:

```bash
./gradlew testDebugUnitTest
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
│   └── ServerState.kt         # Shared volume/mute state (atomic)
├── audio/
│   ├── AudioPlayer.kt         # AudioTrack wrapper (PCM playback)
│   ├── AudioRecorder.kt       # AudioRecord wrapper (mic capture)
│   └── PcmAudioQueue.kt       # Bounded buffer between network and AudioTrack
├── service/
│   └── PulseAudioService.kt   # Foreground service, ServerSocket lifecycle
├── ui/
│   ├── MainActivity.kt        # Single-screen UI
│   ├── PortPreferences.kt     # SharedPreferences helper for port setting
│   └── UiHelpers.kt           # Permissions, clipboard, connection hint
└── assistant/
    └── AssistantVoiceInteractionService.kt  # VoiceInteractionService skeleton
```

## License

MIT — see [LICENSE](LICENSE).
