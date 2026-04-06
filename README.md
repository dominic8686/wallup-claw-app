# Hermes Voice Agent — Tablet Super-Device

A self-hosted **voice assistant**, **security camera**, **DLNA speaker**, and **multi-tablet intercom** built on **LiveKit** (WebRTC), **Hermes Agent** (AI brain), and **Home Assistant**. Wall-mounted Android tablets become multi-function smart home devices.

See [FEATURES.md](FEATURES.md) for a complete feature list.
See [AGENTS.md](AGENTS.md) for architecture, deployment, and operations.

## What It Does

- **Voice Assistant** — "Hey Jarvis" wake word → AI conversation with vision, tools, memory, Home Assistant control
- **Vision AI** — Show objects to the camera and ask "What do you see?" — multimodal conversations with GPT-4o
- **Security Camera** — 720p live RTSP stream + snapshot endpoint, viewable in Home Assistant dashboards or Frigate
- **DLNA Speaker** — HA discovers each tablet as a `media_player` entity for TTS announcements and media playback
- **Multi-Tablet Intercom** — Video/audio calls between rooms, with HA automation triggers
- **3D Avatar** — Optional TalkingHead.js animated avatar with real-time lip sync

## Architecture

```
┌──────────────────────────────────────────────────┐
│              Android Tablet (per room)             │
│                                                    │
│  Wake Word → Voice Conversation (mic + camera)     │
│  DLNA Speaker (UPnP MediaRenderer)                 │
│  Security Camera (LiveKit video track)              │
│  Intercom (call between tablets)                    │
│  HA Dashboard (WebView)                             │
└────────────────────┬───────────────────────────────┘
                     │ WebRTC (audio + video)
                     ▼
┌────────────────────────────────────────────────────┐
│              Proxmox LXC (Docker Compose)           │
│                                                     │
│  ┌─────────────┐ ┌──────────────────────────────┐  │
│  │ LiveKit      │ │ Voice Agent (Python)          │  │
│  │ Server       │ │                               │  │
│  │ :7880        │ │ TabletSession per device:     │  │
│  │              │ │   Silero VAD → Gemini STT →   │  │
│  │              │ │   MultimodalHandler (GPT-4o)  │  │
│  │              │ │   → edge-tts/OpenAI TTS       │  │
│  │              │ │                               │  │
│  │              │ │ + RTSP bridge (ffmpeg→mediamtx)│  │
│  │              │ │ + Snapshot server (:8091)      │  │
│  │              │ │ + HA event listener            │  │
│  └─────────────┘ └──────────────────────────────┘  │
│                                                     │
│  ┌─────────────┐ ┌──────────────────────────────┐  │
│  │ Token Server │ │ mediamtx (RTSP relay)         │  │
│  │ :8090        │ │ :8554                         │  │
│  │              │ │                               │  │
│  │ /token       │ │ rtsp://host:8554/tablet-{id}  │  │
│  │ /devices     │ └──────────────────────────────┘  │
│  │ /signal      │                                   │
│  └─────────────┘                                    │
└────────────────────────────────────────────────────┘
```

## Quick Start

### 1. Deploy server-side services

```powershell
# Sync to LXC
$KEY = "C:\Users\domin\.ssh\proxmox_hermes"
$LXC = "root@192.168.211.153"
scp -i $KEY -r agent token-server avatar livekit.yaml docker-compose.yml .env "${LXC}:/opt/livekit-voice-agent/"

# Start all services
ssh -i $KEY $LXC "docker compose -f /opt/livekit-voice-agent/docker-compose.yml up -d --build"
```

### 2. Build and install the Android app

```powershell
# Build
android-app\gradlew.bat -p android-app assembleRelease

# Install on connected tablet
adb install -r android-app\app\build\outputs\apk\release\app-release.apk
```

### 3. Configure Home Assistant

- **Camera**: Settings → Add Integration → Generic Camera → `rtsp://192.168.211.153:8554/tablet-<device_id>`
- **Speaker**: Auto-discovered via SSDP as DLNA Digital Media Renderer
- **Intercom**: Copy `homeassistant/custom_components/hermes_intercom/` to your HA config

## Docker Services

| Service | Image | Port | Purpose |
|---|---|---|---|
| `livekit-server` | `livekit/livekit-server` | 7880 | WebRTC signaling and media relay |
| `voice-agent` | Built from `agent/` | 8091 (snapshot) | AI voice agent, vision, RTSP bridge |
| `token-server` | Built from `token-server/` | 8090 | JWT tokens, device registry, call signaling |
| `mediamtx` | `bluenviron/mediamtx` | 8554 | RTSP stream relay for security cameras |

## Environment Variables

Key variables in `docker-compose.yml` (see `.env.example` for all):

| Variable | Default | Description |
|---|---|---|
| `AGENT_MODE` | `hermes` | `hermes` (full AI) or `openrouter` (lightweight) |
| `TTS_BACKEND` | `edge-tts` | `edge-tts` (free) or `openai` (paid, better quality) |
| `VISION_MODEL` | `gpt-4o` | Model for visual queries |
| `VISION_ALWAYS_ATTACH` | `true` | Attach camera frame to every utterance |
| `RTSP_ENABLED` | `true` | Enable LiveKit to RTSP camera bridge |
| `RTSP_FPS` | `5` | RTSP stream frame rate |
| `AVATAR_ENABLED` | `false` | Enable TalkingHead.js avatar lip sync (audio always via LiveKit) |

## Key Files

```
livekit-voice-agent/
├── agent/
│   ├── main.py                     # Voice agent: TabletSession, MultimodalHandler, RTSP bridge
│   ├── Dockerfile
│   └── requirements.txt
├── token-server/
│   ├── server.py                   # Token, registry, call signaling, TTS proxy
│   └── Dockerfile
├── android-app/
│   └── app/src/main/java/.../
│       ├── state/DeviceStateManager.kt    # Central resource coordinator
│       ├── dlna/DlnaRendererService.kt    # DLNA speaker (UPnP MediaRenderer)
│       ├── dlna/SsdpAdvertiser.kt         # SSDP multicast for HA discovery
│       ├── screen/MainDashboardScreen.kt  # Main UI: HA WebView + voice + intercom
│       ├── intercom/IntercomManager.kt    # Call signaling state machine
│       ├── audio/AudioPipelineManager.kt  # Mic capture for wake word + LiveKit
│       └── settings/AppSettings.kt        # All persistent settings
├── homeassistant/custom_components/hermes_intercom/  # HA integration
├── avatar/index.html               # TalkingHead.js avatar page
├── docker-compose.yml              # All services
├── FEATURES.md                     # Complete feature list
├── AGENTS.md                       # Architecture and operations guide
└── README.md                       # This file
```

## Logs

```bash
# All services
ssh root@192.168.211.153 "docker compose -f /opt/livekit-voice-agent/docker-compose.yml logs -f"

# Voice agent only
ssh root@192.168.211.153 "docker compose -f /opt/livekit-voice-agent/docker-compose.yml logs -f voice-agent"

# Android app
adb logcat -s "MainDashboard","DlnaRenderer","SsdpAdvertiser","IntercomManager"
```
