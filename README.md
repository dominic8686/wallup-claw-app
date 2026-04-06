# Wallup Claw — Voice Agent & Tablet Platform

A self-hosted **voice assistant**, **DLNA speaker**, and **multi-tablet intercom** built on **LiveKit** (WebRTC), **Home Assistant**, and wall-mounted Android tablets.

See [FEATURES.md](FEATURES.md) for a complete feature list.
See [AGENTS.md](AGENTS.md) for architecture, deployment, and operations.
See [admin-dashboard/ARCHITECTURE.md](admin-dashboard/ARCHITECTURE.md) for the admin portal architecture.

## What It Does

- **Voice Assistant** — "Hey Jarvis" wake word → AI conversation via LiveKit AgentSession (Gemini Live, OpenAI Realtime, or pipeline STT→LLM→TTS)
- **Home Assistant Control** — 92 MCP tools for lights, switches, climate + `ask_hermes` delegation for complex requests
- **DLNA Speaker** — HA discovers each tablet as a `media_player` entity for TTS announcements and media playback
- **Multi-Tablet Intercom** — Video/audio calls between rooms, with HA automation triggers
- **Admin Portal** — Web-based management UI for all settings, devices, and services

## Architecture

```
┌──────────────────────────────────────────────────────┐
│              Android Tablet (per room)                 │
│                                                        │
│  Wake Word → Voice Conversation (LiveKit AgentSession) │
│  DLNA Speaker (UPnP MediaRenderer)                     │
│  Intercom (call between tablets)                       │
│  HA Dashboard (WebView)                                │
└────────────────────┬───────────────────────────────────┘
                     │ WebRTC (audio + video)
                     ▼
┌────────────────────────────────────────────────────────┐
│              Proxmox LXC (Docker Compose)               │
│                                                         │
│  ┌─────────────┐  ┌──────────────────────────────────┐ │
│  │ LiveKit      │  │ Voice Agent (Python)              │ │
│  │ Server       │  │                                   │ │
│  │ :7880        │  │ LiveKit AgentSession per device:  │ │
│  │              │  │   Gemini Live / OpenAI Realtime   │ │
│  │              │  │   or pipeline (STT → LLM → TTS)  │ │
│  │              │  │   + HA MCP tools (92 tools)       │ │
│  │              │  │   + ask_hermes delegation         │ │
│  └─────────────┘  └──────────────────────────────────┘ │
│                                                         │
│  ┌─────────────┐  ┌──────────────────────────────────┐ │
│  │ Token Server │  │ Admin Dashboard (Next.js)         │ │
│  │ :8090        │  │ :3000                             │ │
│  │              │  │                                   │ │
│  │ /token       │  │ AI model config, device fleet,    │ │
│  │ /devices     │  │ Docker management, live monitor   │ │
│  │ /signal      │  │                                   │ │
│  └─────────────┘  └──────────────────────────────────┘ │
└────────────────────────────────────────────────────────┘
```

## Quick Start

### 1. Deploy server-side services

```powershell
# Sync to LXC
$KEY = "C:\Users\domin\.ssh\proxmox_hermes"
$LXC = "root@192.168.211.153"
scp -i $KEY -r agent token-server admin-dashboard livekit.yaml docker-compose.yml .env "${LXC}:/opt/livekit-voice-agent/"

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

- **Speaker**: Auto-discovered via SSDP as DLNA Digital Media Renderer
- **Intercom**: Copy `homeassistant/custom_components/hermes_intercom/` to your HA config

### 4. Open Admin Portal

Browse to `http://<LXC_IP>:3000`. Configure AI model, voice, devices, and monitor services.

## Docker Services

| Service | Image | Port | Purpose |
|---|---|---|---|
| `livekit-server` | `livekit/livekit-server` | 7880 | WebRTC signaling and media relay |
| `voice-agent` | Built from `agent/` | — | AI voice agent (LiveKit AgentSession) |
| `token-server` | Built from `token-server/` | 8090 | JWT tokens, device registry, call signaling, TTS proxy |
| `admin-dashboard` | Built from `admin-dashboard/` | 3000 | Wallup Claw Admin Portal (Next.js) |

## Environment Variables

Key variables in `docker-compose.yml` (see `.env.example` for all):

| Variable | Default | Description |
|---|---|---|
| `LIVEKIT_LLM` | `openai-realtime` | AI model: `gemini-live`, `openai-realtime`, `gemini-2.5-flash`, `gpt-4o-mini` |
| `LIVEKIT_VOICE` | `alloy` | Voice: Puck/Charon/Kore/Fenrir/Aoede (Gemini) or alloy/echo/nova (OpenAI) |
| `HA_MCP_URL` | — | Home Assistant MCP endpoint for smart home tools |
| `TTS_BACKEND` | `openai` | Token server TTS: `edge-tts` (free) or `openai` |
| `TTS_VOICE` | `nova` | Token server TTS voice |

## Key Files

```
livekit-voice-agent/
├── agent/
│   ├── main.py                     # Entry point — runs livekit_session
│   ├── livekit_session.py          # LiveKit AgentSession + HA MCP + ask_hermes
│   ├── Dockerfile
│   └── requirements.txt
├── token-server/
│   ├── server.py                   # Token, registry, call signaling, TTS proxy
│   └── Dockerfile
├── admin-dashboard/                # Wallup Claw Admin Portal (Next.js)
│   ├── src/app/                    # 10 pages + 8 API routes
│   ├── ARCHITECTURE.md             # Full architecture doc
│   └── package.json
├── android-app/                    # Kotlin/Compose Android app
│   └── app/src/main/java/.../
│       ├── state/DeviceStateManager.kt    # Central resource coordinator
│       ├── dlna/JupnpRendererService.kt   # DLNA speaker (UPnP MediaRenderer)
│       ├── screen/MainDashboardScreen.kt  # Main UI: HA WebView + voice + intercom
│       ├── intercom/IntercomManager.kt    # Call signaling state machine
│       ├── audio/AudioPipelineManager.kt  # Mic capture for wake word + LiveKit
│       └── settings/AppSettings.kt        # All persistent settings
├── homeassistant/custom_components/hermes_intercom/  # HA integration
├── livekit.yaml                    # LiveKit server config
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

# Admin dashboard
http://192.168.211.153:3000/monitor  # Live log streaming in the browser

# Android app
adb logcat -s "MainDashboard","IntercomManager"
