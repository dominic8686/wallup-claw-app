# Hermes Voice Agent — LiveKit + Android Tablet + Intercom

A self-hosted voice assistant and **multi-tablet intercom** system built on **LiveKit** (WebRTC), **Hermes Agent** (AI brain), and **Home Assistant**. Speak to wall-mounted Android tablets, control your smart home by voice, and call between rooms.

## Features

- **Voice Assistant** — "Hey Jarvis" wake word → AI conversation with full Hermes toolset (HA control, memory, web search, 56 MCP tools)
- **Multi-Tablet Intercom** — Call between tablets in different rooms, with contacts list, incoming call overlay, and active call UI
- **Home Assistant Integration** — Custom integration (`hermes_intercom`) exposes tablets as HA devices with call/broadcast/DND services
- **Automation Blueprints** — Doorbell → call all, missed call → phone notification, bedtime DND, motion → announce
- **Lovelace Dashboard Card** — Device grid with status, call buttons, and quick announce bar
- **Anam Avatar** — Optional animated avatar during voice conversations

See [AGENTS.md](AGENTS.md) for full architecture, deployment, and operations documentation.

## Architecture

```
┌─────────────────────────┐
│   Android Tablet App    │
│   (Kotlin/Compose)      │
│   LiveKit Android SDK   │
└───────────┬─────────────┘
            │ WebRTC (audio)
            ▼
┌─────────────────────────┐
│   LiveKit Server        │
│   (Proxmox LXC)        │
│   192.168.211.153:7880  │
└───────────┬─────────────┘
            │ WebRTC (audio)
            ▼
┌─────────────────────────────────────────┐
│   Voice Agent (Python)                  │
│                                         │
│   ┌──────────┐  ┌──────────────────┐    │
│   │ Silero   │  │ OpenAI Whisper   │    │
│   │ VAD v5   │→ │ STT              │    │
│   └──────────┘  └────────┬─────────┘    │
│                          │ text         │
│                          ▼              │
│                 ┌──────────────────┐    │
│                 │ Hermes AIAgent   │    │
│                 │ (tools, memory,  │    │
│                 │  skills, HA)     │    │
│                 └────────┬─────────┘    │
│                          │ response     │
│                          ▼              │
│                 ┌──────────────────┐    │
│                 │ OpenAI TTS       │    │
│                 │ (alloy voice)    │    │
│                 └──────────────────┘    │
│                                         │
│   + Live chat relay via data channel    │
└─────────────────────────────────────────┘
```

## Components

### 1. LiveKit Server
- **Docker**: `livekit/livekit-server:latest` via Docker Compose on Proxmox LXC
- **Config**: `livekit.yaml` (mounted into container)
- **Dev credentials**: API key `devkey`, secret `secret`
- **Networking**: `network_mode: host` — required for WebRTC UDP/ICE to work correctly

### 2. Voice Agent (`agent.py`)
- **Location**: `/opt/agent/agent.py` on the LXC container
- **Python venv**: `/opt/agent/` with all dependencies
- **Key dependencies**:
  - `livekit` — Room/track API for WebRTC audio
  - `livekit-api` — Token generation
  - `openai` — Whisper STT + TTS
  - `onnxruntime` — Silero VAD inference
  - `hermes-agent` — Hermes AIAgent library (installed from `/opt/hermes`)
  - `numpy` — Audio processing

#### Voice Pipeline
1. **VAD** (Silero v5 ONNX): Accumulates 16kHz audio in 512-sample chunks. Detects speech start (prob ≥ 0.5) and end (1.5s silence). Returns complete utterance as PCM bytes.
2. **STT** (OpenAI Whisper): Converts utterance WAV to text via `whisper-1` model.
3. **LLM** (Hermes AIAgent): Routes through OpenRouter with full Hermes capabilities — tools, memory, skills, Home Assistant integration.
4. **TTS** (OpenAI): Converts response to 24kHz PCM using `tts-1` model with `alloy` voice.
5. **Playback**: Publishes audio frames to a persistent LiveKit audio track with 20ms pacing.

#### Audio Processing Details
- LiveKit sends 48kHz mono, 480-sample frames (10ms)
- Agent resamples to 16kHz via 3:1 decimation for VAD/STT
- VAD accumulates small frames in a buffer before processing (512 samples needed per inference)
- TTS output is 24kHz 16-bit mono PCM, published in 480-sample frames with `asyncio.sleep` pacing

#### Live Chat Relay
The agent sends transcripts to the LiveKit room's data channel using topic `lk-chat-topic`. The Android app's built-in `ChatLog` component displays these messages when the chat panel is open.

### 3. Token Server (`token-server.py`)
- **Location**: `/opt/agent/token-server.py` on the LXC container
- **Port**: 8090
- **Endpoint**: `GET /token?identity=<name>&room=<room_name>`
- **Returns**: `{"token": "<jwt>", "url": "<livekit_ws_url>"}`
- The Android app fetches a token before connecting to the LiveKit room.

### 4. Android App
- **Base**: `livekit-examples/agent-starter-android` (Kotlin + Jetpack Compose)
- **Source**: `android-app/` directory
- **Key modifications**:
  - `TokenExt.kt` — Points to self-hosted token server at `192.168.211.153:8090`
  - `ConnectScreen.kt` — Fetches token from token server on "START CALL"
  - `AndroidManifest.xml` — `usesCleartextTraffic=true` for HTTP access
- **LiveKit SDK**: `io.livekit:livekit-android:2.23.1`

### 5. Hermes Integration
- **Installation**: Cloned to `/opt/hermes` on the LXC container, installed as editable package in agent venv
- **Config**: `~/.hermes/config.yaml` and `~/.hermes/.env` — copied from the working Docker setup
- **Provider**: OpenRouter (`openai/gpt-4o-mini` via `https://openrouter.ai/api/v1`)
- **Home Assistant**: `HASS_URL=http://192.168.211.3:8123` (resolved from `homeassistant.local`)
- **All tokens migrated**: OpenRouter, OpenAI (STT/TTS), Telegram, Discord, Home Assistant, Browserbase, Firecrawl

## Infrastructure

### Proxmox LXC Container (ID 200)
- **Hostname**: `livekit-voice`
- **OS**: Debian 13 (Trixie)
- **Resources**: 4GB RAM, 2 CPU cores, 8GB disk
- **IP**: `192.168.211.153` (DHCP on `vmbr0`)
- **Network**: Same LAN as the tablet (`192.168.211.0/24`)

### Why network_mode: host on Proxmox?
Docker bridge networking breaks WebRTC because:
- UDP port mapping for RTC media is unreliable
- ICE candidates advertise Docker-internal IPs that external clients can't reach

`network_mode: host` on the Proxmox LXC (Linux) gives containers direct access to the host network interface, so WebRTC just works.

## Starting the Services

From Windows, sync the repo to the LXC and bring up all services:

```powershell
# Sync repo files to LXC (PowerShell)
$KEY = "C:\Users\domin\.ssh\proxmox_hermes"
$LXC = "root@192.168.211.153"
scp -i $KEY -r agent token-server livekit.yaml docker-compose.yml .env "${LXC}:/opt/livekit-voice-agent/"
```

```bash
# On the LXC — start (or restart) all services
docker compose -f /opt/livekit-voice-agent/docker-compose.yml up -d --build

# View logs
docker compose -f /opt/livekit-voice-agent/docker-compose.yml logs -f
```

## Using the App

1. Open the **LiveKit Voice Assistant** app on the Android tablet
2. Tap **START CALL**
3. Grant microphone permission when prompted
4. The agent greets you — then speak naturally
5. Tap the **chat icon** in the control bar to see live transcripts
6. Tap the **red phone icon** to end the call

## Logs and Debugging

```bash
# All services
docker compose -f /opt/livekit-voice-agent/docker-compose.yml logs -f

# Individual service
docker compose -f /opt/livekit-voice-agent/docker-compose.yml logs -f voice-agent

# Container status
docker compose -f /opt/livekit-voice-agent/docker-compose.yml ps

# Android app logs (from Windows)
adb logcat -s "livekit","LiveKit"
```

## Key Files

```
livekit-voice-agent/
├── agent/
│   ├── agent.py              # Main voice agent (VAD + STT + Hermes + TTS + LiveKit)
│   ├── Dockerfile            # Docker build (unused — running natively on Proxmox)
│   └── requirements.txt      # Python dependencies
├── token-server/
│   ├── server.py             # JWT token server for Android app auth
│   └── requirements.txt
├── android-app/              # LiveKit Android starter app (Kotlin/Compose)
│   └── app/src/main/java/io/livekit/android/example/voiceassistant/
│       ├── TokenExt.kt       # Server URLs (192.168.211.153)
│       ├── screen/
│       │   ├── ConnectScreen.kt   # START CALL with token fetch
│       │   └── VoiceAssistantScreen.kt  # Voice UI with chat
│       └── viewmodel/
│           └── VoiceAssistantViewModel.kt
├── docker-compose.yml        # Docker Compose (runs on Proxmox LXC with network_mode: host)
├── livekit.yaml              # LiveKit server config
├── .env                      # API keys
└── README.md                 # This file
```

## Future Work (Phase 6)

- **QR code onboarding**: HA generates QR → tablet scans to auto-configure
- **AI receptionist**: Hermes answers on behalf of DND/unanswered tablets
- **Battery + WiFi reporting**: Tablet metrics as HA sensors
- **Remote volume control**: HA number entity controls tablet volume
- **Video calling**: LiveKit video tracks between rooms
- **Multi-room audio**: Tablets as `media_player` entities
- **Production LiveKit**: Replace `--dev` mode with proper keys
- **Systemd units**: Auto-start on LXC boot
