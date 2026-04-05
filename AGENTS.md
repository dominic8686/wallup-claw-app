# Hermes Voice Agent — Architecture & Operations Guide

## Overview

A self-hosted voice assistant and multi-tablet intercom system built on **LiveKit** (WebRTC), **Hermes Agent** (AI brain), and **Home Assistant** (smart home hub). Speak to wall-mounted Android tablets, control your home by voice, and call between rooms.

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
┌─────────────────────────────────────────────────────────────┐
│                    LiveKit Server                            │
│         Proxmox LXC (192.168.211.153:7880)                  │
│         Rooms: voice-room, call-{id} rooms                  │
└──────────────────────┬──────────────────────────────────────┘
                       │
          ┌────────────┼────────────┐
          ▼                         ▼
┌──────────────────┐   ┌───────────────────────┐
│  Token & Registry │   │  Voice Agent (Python)  │
│  Server (:8090)   │   │                        │
│                    │   │  Silero VAD → Whisper  │
│  /register         │   │  STT → Hermes AI →    │
│  /devices          │   │  OpenAI TTS → LiveKit  │
│  /heartbeat        │   │                        │
│  /signal           │   │  + HA event listener   │
│  /signals          │   │  + Intercom tools       │
│  /token            │   │  + MCP servers (56 tools)│
└──────────────────┘   └───────────────────────┘
                                │
                                ▼
                    ┌───────────────────────┐
                    │   Home Assistant       │
                    │   (192.168.211.3:8123) │
                    │                        │
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
- **File**: `agent/agent.py`
- **Docker**: built from `agent/Dockerfile`
- **Hermes**: mounted from `/opt/hermes` on the LXC host (read-only)
- **Hermes config**: mounted from `/root/.hermes` on the LXC host (read-only)

**Voice pipeline**:
1. **VAD** — Silero v5 ONNX, 16kHz, 512-sample chunks, speech threshold 0.5
2. **STT** — OpenAI Whisper (`whisper-1`)
3. **LLM** — Hermes AIAgent via OpenRouter (tools, memory, skills, HA control)
4. **TTS** — OpenAI TTS (`tts-1`, `alloy` voice), 24kHz PCM
5. **Playback** — Published to LiveKit audio track, 20ms frame pacing

**Toolsets enabled**: `web`, `homeassistant`, `memory`, `terminal` + 56 MCP tools (second_brain, ha_mcp, ms365)

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
| `IntercomManager` | Call signaling state machine via long-poll `/signals` |
| `AudioPipelineManager` | Mic capture with dual-channel output (wake word + LiveKit) |
| `WakeWordManager` | ONNX wake word detection (Hey Jarvis, Alexa, Hey Mycroft) |
| `AppSettings` | DataStore persistence for all settings |
| `HomeAssistantDetector` | Auto-discovers HA instance on local network |

### 5. Hermes Intercom Tools
- **File**: `tools/intercom_tool.py`
- **Tools**: `intercom_list_devices`, `intercom_call`, `intercom_hangup`, `intercom_announce`
- **Used by**: Hermes AIAgent for voice-triggered intercom commands

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
│   ├── agent.py                    # Voice agent (VAD + STT + Hermes + TTS)
│   ├── Dockerfile
│   └── requirements.txt
├── token-server/
│   ├── server.py                   # Token + device registry + call signaling
│   └── requirements.txt
├── android-app/                    # Kotlin/Compose Android app
│   └── app/src/main/java/.../
│       ├── MainActivity.kt
│       ├── settings/AppSettings.kt
│       ├── intercom/IntercomManager.kt
│       ├── screen/
│       │   ├── MainDashboardScreen.kt
│       │   ├── ContactsScreen.kt
│       │   ├── IncomingCallOverlay.kt
│       │   ├── ActiveCallScreen.kt
│       │   ├── SettingsScreen.kt
│       │   └── ...
│       ├── audio/
│       │   ├── AudioPipelineManager.kt
│       │   └── WakeWordManager.kt
│       └── ui/
│           ├── ConversationCard.kt
│           ├── VoiceBubble.kt
│           └── ...
├── custom_components/hermes_intercom/   # HA integration
│   ├── __init__.py                 # Coordinator + services
│   ├── config_flow.py              # UI setup flow
│   ├── const.py                    # Constants
│   ├── manifest.json
│   ├── binary_sensor.py            # Online/offline sensor
│   ├── sensor.py                   # Call state + last activity + last call
│   ├── switch.py                   # DND toggle
│   ├── strings.json
│   ├── blueprints/automation/      # Automation blueprints
│   │   ├── doorbell_call_all.yaml
│   │   ├── missed_call_notify.yaml
│   │   ├── bedtime_dnd.yaml
│   │   └── motion_announce.yaml
│   └── www/
│       └── hermes-intercom-card.js # Lovelace custom card
├── tools/
│   └── intercom_tool.py            # Hermes intercom tools
├── livekit.yaml                    # LiveKit server config (local copy)
├── .env                            # All credentials and config
├── .env.example                    # Template without secrets
├── AGENTS.md                       # This file
└── README.md                       # Project overview
```

## Known Issues & Future Work

### Phase 6 (Planned)
- QR code device onboarding (HA generates QR → tablet scans)
- AI receptionist (Hermes answers on behalf of DND/unanswered tablets)
- Battery + WiFi signal reporting from tablets → HA sensors
- Remote volume control from HA
- Video calling (LiveKit video tracks)
- Multi-room audio / `media_player` entities

### Known Issues
- LiveKit server runs in `--dev` mode — move to production keys for deployment
- Token server uses in-memory registry — restarts lose device list (tablets re-register on heartbeat)
- `.env` contains the actual LiveKit `secret` key used in `--dev` mode; the `livekit.yaml` in repo has the old long secret
