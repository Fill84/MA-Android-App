# Music Assistant Companion App for Android

The Android companion app for [Music Assistant](https://music-assistant.io/) — a free, open-source, self-hosted music server.

## Features

- **Fully native UI** — Jetpack Compose with Material 3 theming, no WebView
- **Direct audio streaming** — PCM audio via Sendspin protocol, played directly through Android AudioTrack for low-latency playback
- **Media controls** — Lock screen, notification, and Bluetooth/headset controls via Media3 MediaSession
- **Background playback** — Foreground service with WakeLock keeps audio playing reliably in the background
- **Multi-player support** — View and control any player on your MA server from the app
- **Library browsing** — Browse artists, albums, tracks, playlists, and radio stations with pagination
- **Search** — Search across your entire music library
- **Queue management** — View, reorder, and manage the playback queue
- **Real-time sync** — Event-driven WebSocket updates keep the app in sync with the server

## Requirements

- Android 8.0 (API 26) or higher
- A running [Music Assistant](https://github.com/music-assistant/server) server on your network

## Installation

Download the latest APK from the [Releases](../../releases) page.

## Building from source

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 35

### Build

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk` or `app/build/outputs/apk/release/app-release.apk`.

## Architecture

| Layer | Technology |
|-------|-----------|
| UI | Jetpack Compose, Material 3 |
| Navigation | Navigation Compose with slide animations |
| Media | Media3 (ExoPlayer + ForwardingPlayer + MediaSession) |
| Audio Streaming | Direct AudioTrack via Sendspin protocol (PCM) |
| Background | LifecycleService with foreground notification |
| Networking | OkHttp WebSockets, kotlinx.serialization |
| Settings | DataStore Preferences |
| Image Loading | Coil |

## Project structure

```
app/src/main/java/io/musicassistant/companion/
├── data/
│   ├── api/             # WebSocket API client + typed API wrapper
│   ├── model/           # Data models (Player, Queue, Media items)
│   ├── sendspin/        # Sendspin protocol client (audio streaming)
│   └── settings/        # DataStore preferences
├── media/
│   ├── NativeMediaManager   # ExoPlayer + ForwardingPlayer + MediaSession
│   ├── StreamAudioPlayer    # Direct AudioTrack for PCM streaming
│   └── AudioStreamPipe      # (legacy) Pipe-based streaming
├── service/
│   ├── MusicService     # Foreground service, notifications, metadata
│   └── ServiceLocator   # Shared singletons (DI)
└── ui/
    ├── common/          # Shared composables (MediaComponents, VisualEffects)
    ├── home/            # Home screen (recently played, favorites)
    ├── library/         # Library browsing + detail screens
    ├── navigation/      # App navigation graph
    ├── player/          # Now playing, mini player, queue
    ├── search/          # Search screen
    ├── settings/        # Settings screen
    └── theme/           # Material 3 theming (colors, typography)
```

## Related projects

- [Music Assistant Server](https://github.com/music-assistant/server)
- [Music Assistant Frontend](https://github.com/music-assistant/frontend)
- [Music Assistant Desktop App](https://github.com/music-assistant/desktop-app)

## License

This project is licensed under the Apache License 2.0 — see the [LICENSE](LICENSE) file for details.
