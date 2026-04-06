# Wallup Claw — Android App

Kotlin/Compose Android app for wall-mounted tablets. Provides voice assistant, DLNA speaker, and multi-tablet intercom functionality.

## Features

- **Voice Assistant** — "Hey Jarvis" wake word (ONNX) → LiveKit AgentSession conversation
- **DLNA Speaker** — UPnP MediaRenderer auto-discovered by Home Assistant
- **Intercom** — Room-to-room video/audio calls via LiveKit WebRTC
- **HA Dashboard** — Embedded WebView showing your Home Assistant dashboard
- **Security Camera** — Optional 720p camera stream via LiveKit video track

## Build

```powershell
# Debug build
android-app\gradlew.bat -p android-app assembleDebug

# Release build
android-app\gradlew.bat -p android-app assembleRelease

# Install on connected tablet
adb install -r android-app\app\build\outputs\apk\release\app-release.apk
```

## Configuration

Swipe left or tap ⚙ in the app to access settings:

- **Device Identity**: Unique ID, display name, room location
- **Wake Word**: Model selection (Hey Jarvis, Alexa, Hey Mycroft), sensitivity
- **Server URLs**: LiveKit server and token server addresses
- **Call Mode**: Manual (tap to start) or Wake Word (always listening)

Device settings can also be pushed remotely via the Admin Portal's device fleet page.
