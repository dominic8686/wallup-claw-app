# Wallup Claw — Architecture & Operations Guide

## Overview

A self-hosted voice assistant and multi-tablet intercom system built on **LiveKit** (WebRTC) and **Home Assistant** (smart home hub). Speak to wall-mounted Android tablets, control your home by voice, and call between rooms. Managed via the **Wallup Claw Admin Portal** (Next.js web UI).

## Architecture

```
┌──────────────────────────┐     ┌──────────────────────────┐
│  Android Tablet (Kitchen) │     │  Android Tablet (Bedroom) │
│  Kotlin/Compose + LiveKit │     │  Kotlin/Compose + LiveKit │
│  Wake Word → Conversation │     │  Wake Word → Conversation │
│  Intercom Calling         │     │  Intercom Calling         │
└────────────┬─────────────┘     └────────────┬─────────────┘
             │ WebRTC audio                    │ WebRTC audio
             ▼                                 ▼
┌───────────────────────────────────────────────────────────┐
│                    LiveKit Server                            │
│         Proxmox LXC (192.168.211.153:7880)                  │
│         Rooms: voice-room-{device_id}, call-{id}            │
└──────────────────────┬────────────────────────────────────┘
                       │
    ┌──────────────┼──────────────┬──────────────┐
    ▼                              ▼                      ▼
┌─────────────────┐ ┌──────────────────────┐ ┌───────────────┐
│ Token & Registry│ │ Voice Agent (Python) │ │ Admin Portal  │
│ Server (:8090)  │ │                      │ │ (Next.js)     │
│                 │ │ LiveKit AgentSession │ │ :3000         │
│ /register       │ │ + HA MCP (92 tools) │ │               │
│ /devices        │ │ + ask_hermes        │ │ AI models,    │
│ /heartbeat      │ │   delegation tool   │ │ devices,      │
│ /signal         │ │                      │ │ Docker, logs  │
│ /token          │ │                      │ │               │
└─────────────────┘ └──────────┬───────────┘ └───────────────┘
                               │
                               ▼
                  ┌───────────────────────┐
                  │   Home Assistant       │
                  │   (192.168.211.3:8123) │
                  │   MCP: /api/mcp        │
                  │   hermes_intercom      │
                  │   custom integration   │
                  └───────────────────────┘
```

## Components

### 1. LiveKit Server
- **Image**: `livekit/livekit-server:latest` (Docker)
- **Config**: `livekit.yaml` (mounted at `/etc/livekit.yaml`)
- **Credentials**: API key `devkey`, secret `secret`
- **Ports**: 7880 (API/WS), 50100-50120 (RTC UDP) — exposed via `network_mode: host`

### 2. Token & Device Registry Server
- **File**: `token-server/server.py`
- **Docker**: built from `token-server/Dockerfile`
- **Port**: 8090

**Endpoints**:
| Endpoint | Method | Description |
|---|---|---|
| `/token` | GET | Issue LiveKit JWT (`?identity=<id>&room=<room>`) |
| `/register` | POST | Register a tablet (`{device_id, display_name, room_location}`) |
| `/devices` | GET | List all devices with status |
| `/heartbeat` | POST | Refresh device online status (`{device_id}`) |
| `/signal` | POST | Relay call signaling (`{type, from, to, ...}`) |
| `/signals` | GET | Long-poll for incoming signals (`?device_id=<id>`) |
| `/calls` | GET | List active calls |
| `/health` | GET | Health check |

**Call signaling types**: `call_request`, `call_accept`, `call_decline`, `call_hangup`, `announcement`

### 3. Voice Agent
- **Files**: `agent/main.py` (entry point), `agent/livekit_session.py` (main logic)
- **Docker**: built from `agent/Dockerfile`
- **Architecture**: Multi-tablet session manager using the **LiveKit Agents SDK**. Polls token server for registered devices, spawns one `AgentSession` per tablet, each with its own LiveKit room (`voice-room-{device_id}`).

**Key components**:
| Component | Purpose |
|---|---|
| `LiveKitAgent` | Agent class with HA MCP tools + ask_hermes delegation tool |
| `_build_llm()` | Selects LLM based on `LIVEKIT_LLM` env var (realtime or pipeline) |
| `_build_session()` | Creates AgentSession with appropriate VAD/STT/TTS for the model type |
| `ask_hermes` | Function tool that delegates complex requests to the Hermes AI brain |

**AI modes**:
- **Realtime (speech-to-speech)**: Gemini Live or OpenAI Realtime — single model handles VAD, STT, LLM, and TTS
- **Pipeline**: Separate Silero VAD → STT (Deepgram/Google Chirp) → LLM (Gemini/GPT) → TTS (Cartesia/OpenAI)

**Tools available**:
- 92 Home Assistant MCP tools (via Streamable HTTP transport to HA's `/api/mcp`)
- `ask_hermes` delegation to the full Hermes AI agent at `:8642` for calendar, web search, memory, complex tasks

**Configuration** (all via env vars, manageable from Admin Portal):
| Env Var | Example | Description |
|---|---|---|
| `LIVEKIT_LLM` | `openai-realtime` | Model: gemini-live, openai-realtime, gemini-2.5-flash, gpt-4o-mini |
| `LIVEKIT_VOICE` | `alloy` | Voice for realtime models |
| `HA_MCP_URL` | `http://192.168.211.3:8123/api/mcp` | Home Assistant MCP endpoint |

### 4. Android App
- **Source**: `android-app/`
- **Package**: `io.livekit.android.example.voiceassistant`
- **Min SDK**: 24, Target SDK: 36
- **Key dependencies**: LiveKit Android SDK 2.23.1, ONNX Runtime 1.17.0, emoji2

**Screens**:
| Screen | Purpose |
|---|---|
| `MainDashboardScreen` | HA WebView + voice overlay + conversation card |
| `SettingsScreen` | Device identity, wake word, sensitivity, servers |
| `ContactsScreen` | Online tablets list, tap to call |
| `IncomingCallOverlay` | Full-screen accept/decline for incoming calls |
| `ActiveCallScreen` | In-call UI with timer and hangup |
| `HermesScreen` | Standalone voice-only mode |
| `ConnectScreen` | Manual call start (legacy) |

**Key classes**:
| Class | Purpose |
|---|---|
| `DeviceStateManager` | Central resource coordinator (mic, camera, audio focus) across IDLE/CONVERSATION/INTERCOM_CALL states |
| `DlnaRendererService` | Foreground service: UPnP MediaRenderer with SSDP, NanoHTTPD, MediaPlayer |
| `SsdpAdvertiser` | SSDP multicast NOTIFY + M-SEARCH responder for HA auto-discovery |
| `IntercomManager` | Call signaling state machine via long-poll `/signals` |
| `AudioPipelineManager` | Mic capture with dual-channel output (wake word + LiveKit) |
| `WakeWordManager` | ONNX wake word detection (Hey Jarvis, Alexa, Hey Mycroft) |
| `AppSettings` | DataStore persistence for all settings (incl. `security_camera_enabled`) |
| `ConversationCard` | Chat panel with live transcripts |
| `HomeAssistantDetector` | Auto-discovers HA instance on local network |

### 5. Admin Portal (Wallup Claw Admin)
- **Directory**: `admin-dashboard/`
- **Tech**: Next.js 16, React 19, Tailwind 4, shadcn/ui, TanStack Query
- **Port**: 3000
- **Full documentation**: [admin-dashboard/ARCHITECTURE.md](admin-dashboard/ARCHITECTURE.md)

10 pages, 8 API routes. Manages AI model configuration, device fleet, LiveKit server, Docker containers, .env variables, Home Assistant MCP, intercom calls, TTS settings, and live monitoring with SSE log streaming.

### 6. Home Assistant Integration
- **Directory**: `custom_components/hermes_intercom/`
- **Domain**: `hermes_intercom`
- **Type**: HACS-installable custom integration

**Entities per tablet**:
| Entity | Type | Description |
|---|---|---|
| `binary_sensor.tablet_{name}_online` | Binary Sensor | Connected to LiveKit |
| `sensor.tablet_{name}_call_state` | Sensor | idle / ringing / in_call / dnd |
| `sensor.tablet_{name}_last_activity` | Sensor | Last heartbeat timestamp |
| `switch.tablet_{name}_do_not_disturb` | Switch | Toggle DND |
| `sensor.hermes_intercom_last_call` | Sensor | Most recent call details |

**Services**:
| Service | Parameters | Description |
|---|---|---|
| `hermes_intercom.call` | `target`, `source` | Call a tablet or group |
| `hermes_intercom.broadcast` | `message`, `targets` | TTS announcement |
| `hermes_intercom.hangup` | `call_id`, `source`, `target` | End a call |
| `hermes_intercom.set_dnd` | `target`, `enabled` | Toggle Do Not Disturb |

**Events**: `hermes_intercom_call_started`, `_call_ended`, `_call_missed`, `_device_online`, `_device_offline`

**Blueprints** (in `blueprints/automation/`):
- `doorbell_call_all.yaml` — Doorbell → call all tablets
- `missed_call_notify.yaml` — Missed call → phone notification
- `bedtime_dnd.yaml` — Scheduled DND for kids' tablets
- `motion_announce.yaml` — Motion → TTS announcement

**Lovelace Card**: `www/hermes-intercom-card.js`
```yaml
type: custom:hermes-intercom-card
token_server_url: http://192.168.211.153:8090
```

## Infrastructure

### Proxmox LXC Container (ID 200)
- **Hostname**: `livekit-voice`
- **OS**: Debian 13 (Trixie)
- **Resources**: 4GB RAM, 2 CPU cores, 8GB disk
- **IP**: `192.168.211.153` (DHCP on `vmbr0`)
- **SSH**: `ssh -i ~/.ssh/proxmox_hermes root@192.168.211.153`

### Network
- All devices on `192.168.211.0/24`
- LiveKit: `192.168.211.153:7880` (WebSocket + RTC)
- Token server: `192.168.211.153:8090` (HTTP)
- Home Assistant: `192.168.211.3:8123`
- Proxmox host: `192.168.211.9`

## Deployment

### First-Time Setup on LXC
```bash
# Install Docker (Debian)
apt-get update && apt-get install -y docker.io docker-compose-plugin
systemctl enable --now docker
```

### Deploy / Update Services
From Windows, rsync the repo to the LXC then run compose:
```powershell
# From Windows (PowerShell) — sync repo to LXC
$KEY = "C:\Users\domin\.ssh\proxmox_hermes"
$LXC = "root@192.168.211.153"
scp -i $KEY -r agent token-server livekit.yaml docker-compose.yml .env "${LXC}:/opt/livekit-voice-agent/"
```

```bash
# On the LXC — bring everything up (or rebuild after code changes)
docker compose -f /opt/livekit-voice-agent/docker-compose.yml up -d --build
```

### Build & Deploy Android App
```powershell
# Build
android-app\gradlew.bat -p android-app assembleDebug

# Install via ADB (tablet connected via USB or WiFi)
adb install -r android-app\app\build\outputs\apk\debug\app-debug.apk
```

### Install HA Integration
1. Copy `custom_components/hermes_intercom/` to your HA config directory
2. Restart Home Assistant
3. Go to Settings → Integrations → Add → "Hermes Intercom"
4. Enter token server URL: `http://192.168.211.153:8090`

## Configuration

### Voice Agent
Set in `docker-compose.yml` under `voice-agent.environment` (or via the Admin Portal at `:3000/models`):

| Variable | Default | Options |
|---|---|---|
| `LIVEKIT_LLM` | `openai-realtime` | `gemini-live`, `openai-realtime`, `gemini-2.5-flash`, `gpt-4o-mini`, custom string |
| `LIVEKIT_VOICE` | `alloy` | Puck/Charon/Kore/Fenrir/Aoede (Gemini), alloy/echo/nova/fable/onyx/shimmer (OpenAI) |
| `HA_MCP_URL` | — | Home Assistant MCP endpoint URL |

Changing the model via the Admin Portal writes to `docker-compose.yml` and restarts the voice-agent container automatically.

### Android App Settings
Open the app → swipe left or tap ⚙ → Settings:
- **Device Identity**: Set a unique ID (e.g., `kitchen`), display name, room
- **Wake Word**: Model selection (Hey Jarvis v2 default), sensitivity slider
- **Call Mode**: Manual (tap to start) or Wake Word (always listening)
- **Server URLs**: LiveKit and token server addresses

### Environment Variables (.env)
```
CT200_HOST=192.168.211.153
LIVEKIT_API_KEY=devkey
LIVEKIT_API_SECRET=secret
OPENAI_API_KEY=<key>
OPENROUTER_API_KEY=<key>
HASS_URL=http://192.168.211.3:8123
HASS_TOKEN=<long-lived-access-token>
```
See `.env.example` for a full template.

## Logs & Debugging

```bash
# SSH into LXC
ssh -i ~/.ssh/proxmox_hermes root@192.168.211.153

# Follow all service logs
docker compose -f /opt/livekit-voice-agent/docker-compose.yml logs -f

# Individual service logs
docker compose -f /opt/livekit-voice-agent/docker-compose.yml logs -f voice-agent
docker compose -f /opt/livekit-voice-agent/docker-compose.yml logs -f token-server
docker compose -f /opt/livekit-voice-agent/docker-compose.yml logs -f livekit-server

# Container status
docker compose -f /opt/livekit-voice-agent/docker-compose.yml ps

# Android app logs (from Windows)
adb logcat -s "MainDashboard","IntercomManager","livekit"
```

## Call Flow

### Voice Conversation
1. Tablet detects wake word ("Hey Jarvis") via ONNX model
2. Stops AudioRecord, enables LiveKit mic, sends `wake_word_detected` data message
3. Agent receives signal → activates → greets user
4. User speaks → VAD detects utterance → STT transcribes → Hermes processes → TTS responds
5. 30s silence timeout → agent deactivates → sends `conversation_ended` → tablet returns to wake word mode

### Intercom Call (Tablet → Tablet)
1. Caller opens Contacts screen → taps call button on target device
2. `IntercomManager` sends `POST /signal {type: "call_request", from, to}`
3. Token server creates call record, enqueues signal for target
4. Target's `IntercomManager` (long-polling `/signals`) receives call_request
5. Target shows `IncomingCallOverlay` with accept/decline
6. On accept: both tablets join dedicated call room `call-{caller}-{callee}-{timestamp}`
7. Direct WebRTC audio between tablets via LiveKit
8. Hangup: both leave call room, return to wake word mode

### HA-Triggered Call
```yaml
# From HA automation or service call
service: hermes_intercom.call
data:
  target: kitchen
  source: doorbell
```

## Project Structure

```
livekit-voice-agent/
├── agent/
│   ├── main.py                     # Entry point — runs livekit_session
│   ├── livekit_session.py          # LiveKit AgentSession + HA MCP + ask_hermes
│   ├── Dockerfile
│   └── requirements.txt
├── token-server/
│   ├── server.py                   # Token + device registry + call signaling + TTS proxy
│   └── Dockerfile
├── admin-dashboard/                # Wallup Claw Admin Portal (Next.js 16)
│   ├── src/app/                    # 10 pages + 8 API routes
│   ├── src/lib/                    # SSH, auth, settings, types
│   ├── ARCHITECTURE.md             # Full admin portal architecture doc
│   └── Dockerfile
├── android-app/                    # Kotlin/Compose Android app
│   └── app/src/main/java/.../
│       ├── state/DeviceStateManager.kt
│       ├── dlna/JupnpRendererService.kt
│       ├── intercom/IntercomManager.kt
│       ├── screen/MainDashboardScreen.kt
│       ├── audio/AudioPipelineManager.kt
│       └── settings/AppSettings.kt
├── homeassistant/custom_components/hermes_intercom/
├── livekit.yaml                    # LiveKit server config
├── docker-compose.yml              # All services
├── .env.example                    # Template without secrets
├── FEATURES.md                     # Complete feature list
├── AGENTS.md                       # This file
└── README.md                       # Project overview
```

## Known Issues & Future Work

### Completed
- ✅ LiveKit AgentSession — Gemini Live, OpenAI Realtime, and pipeline modes
- ✅ Home Assistant MCP — 92 tools via Streamable HTTP
- ✅ Admin Portal — Full web UI for managing all settings
- ✅ DLNA Speaker — UPnP MediaRenderer auto-discovered by HA as `media_player`
- ✅ Per-Tablet Rooms — Isolated LiveKit rooms per device, no cross-talk
- ✅ Video Calling — LiveKit video tracks during intercom calls
- ✅ DeviceStateManager — Central resource coordinator for mic/camera/audio focus

### Planned
- QR code device onboarding (HA generates QR → tablet scans)
- AI receptionist (answers on behalf of DND/unanswered tablets)
- OTA tablet updates via admin portal
- A/B testing for AI models

### Known Issues
- Token server uses in-memory registry — restarts lose device list (tablets re-register on heartbeat 404)
- LiveKit server startup race — voice agent may need restart if it starts before LiveKit is ready
