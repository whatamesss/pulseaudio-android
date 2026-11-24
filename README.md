# PulseServer for Android

> **This is a fork** of
> [ModdingFox/pulseaudio-android](https://github.com/ModdingFox/pulseaudio-android),
> maintained at
> [whatamesss/pulseaudio-android](https://github.com/whatamesss/pulseaudio-android).
>
> What this fork changes:
>
> - **Buffered audio playback (main improvement)** — a bounded ~300 ms PCM
>   queue decouples socket reads from `AudioTrack` writes, so slow audio writes
>   no longer stall the network receive path.
> - **Playback only** — microphone capture and the `RECORD_AUDIO` permission
>   are gone; record/source protocol commands are rejected.
> - **One client at a time** — extra PulseAudio connections are refused so two
>   devices cannot fight over playback.
> - **MPD output control** — starting/stopping the server enables/disables a
>   configured MPD output.
> - **Release-only builds** — the debug variant is disabled, releases are
>   signed with a local keystore, and versioned APKs are published under
>   [Releases](https://github.com/whatamesss/pulseaudio-android/releases).
>
> The [original upstream README](#original-upstream-readme) is preserved
> verbatim at the bottom. Some of it (microphone capture, debug builds,
> multiple clients) no longer describes this fork.

An Android app that runs a PulseAudio native protocol server on your phone or tablet. Linux machines can connect over the local network and use the Android device as a remote speaker — no root, no native libraries, no PulseAudio installed on Android.

## How it works

PulseServer implements the PulseAudio native binary wire protocol (tagstruct framing, protocol version 16) entirely in Kotlin. It opens a TCP socket and accepts connections from a Linux PulseAudio daemon using the standard `module-tunnel-sink` module.

- **Playback** — received PCM audio is written to `AudioTrack` (Android speaker)
- **Buffered receive path** — incoming PCM goes through a bounded queue instead of being written to `AudioTrack` from the socket read loop
- **Volume/mute** — PA volume and mute commands update the Android stream in real time
- **Single client** — only one PulseAudio connection is accepted at a time; extra connections are refused so two devices cannot fight over playback
- **Playback only** — record/source commands are rejected; no microphone permission is needed

### Audio buffering

The main fork improvement is the playback path. Incoming PCM used to be written
to `AudioTrack` directly from the socket read loop, so a slow or blocking write
could stall network reads and cause the PulseAudio client to back up.

Now the socket loop only enqueues PCM into a bounded queue (about 300 ms at the
stream's sample rate). A dedicated writer coroutine drains it into
`AudioTrack`, so network reception stays responsive even when the speaker path
is busy or paused. If the queue fills, the oldest audio is dropped rather than
delaying playback. Pause/cork, flush, and drain are handled explicitly, and
shutdown waits for the writer without releasing `AudioTrack` underneath a
native write.

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

The versioned APK lands at
`app/build/outputs/apk/release/pulseserver-<version>-release.apk`.

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

## Branches

All development happens on `main`.

## Releases

Pushing a `v*` tag runs `.github/workflows/release.yml`, which builds, tests,
and publishes a GitHub Release with the signed APK attached.

To cut a release:

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Update `RELEASE_NOTES.md` for the new version.
3. Commit the changes.
4. Tag and push:

   ```bash
   git tag -a v1.0.1 -m "PulseServer 1.0.1"
   git push origin main v1.0.1
   ```

The workflow needs these repository secrets:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 keystore/release.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias (`pulseserver`) |
| `KEY_PASSWORD` | key password |

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

---

## Original upstream README

The following is the upstream project README at the point this fork started,
preserved verbatim for reference.

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
│   └── AudioRecorder.kt       # AudioRecord wrapper (mic capture)
├── service/
│   └── PulseAudioService.kt   # Foreground service, ServerSocket lifecycle
├── ui/
│   ├── MainActivity.kt        # Single-screen UI
│   └── PortPreferences.kt     # SharedPreferences helper for port setting
└── assistant/
    └── AssistantVoiceInteractionService.kt  # VoiceInteractionService skeleton
```

## License

MIT — see [LICENSE](LICENSE).
