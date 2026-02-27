# Music Assistant Companion App for Android

The official Android companion app for [Music Assistant](https://music-assistant.io/) — a free, open-source, self-hosted music server.

## Features

- **Native Android experience** — WebView-based UI with native navigation drawer
- **Media controls** — Lock screen, notification, and Bluetooth/headset controls via Media3 MediaSession
- **Background playback** — Foreground service keeps audio playing when the app is in the background
- **Server discovery** — Automatic mDNS/DNS-SD discovery of Music Assistant servers on your local network
- **Material 3** — Modern Material You theming

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
| Navigation | Navigation Compose (3 routes: Launcher, Main, Settings) |
| Media | Media3 1.5.1 (MediaSession + custom SimpleBasePlayer proxy) |
| Background | Foreground Service with media playback type |
| WebView | Singleton with MutableContextWrapper for lifecycle management |
| Settings | DataStore Preferences |
| Networking | OkHttp, Coil |

## Project structure

```
app/src/main/java/io/musicassistant/companion/
├── data/
│   ├── discovery/       # mDNS server discovery
│   └── settings/        # DataStore preferences
├── media/
│   ├── MaProxyPlayer    # Custom SimpleBasePlayer (mirrors web player state)
│   ├── MediaSessionManager
│   └── MediaBridgeScript # JS bridge for player state sync
├── service/
│   └── PlayerService    # Foreground service + notification
└── ui/
    ├── launcher/        # Server URL setup screen
    ├── main/            # Main screen with navigation drawer
    ├── settings/        # Settings screen
    ├── webview/         # WebView holder, clients, composable
    ├── navigation/      # App navigation graph
    └── theme/           # Material 3 theming
```

## Related projects

- [Music Assistant Server](https://github.com/music-assistant/server)
- [Music Assistant Frontend](https://github.com/music-assistant/frontend)
- [Music Assistant Desktop App](https://github.com/music-assistant/desktop-app)

## License

This project is licensed under the Apache License 2.0 — see the [LICENSE](LICENSE) file for details.
