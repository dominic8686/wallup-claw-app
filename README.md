# Hermes Voice Agent — LiveKit + Android Tablet

A self-hosted voice assistant that uses **LiveKit** for real-time audio transport and **Hermes Agent** as the AI brain. Speak to your Android tablet and get intelligent responses powered by Hermes' full tool suite — including Home Assistant control, memory, skills, and more.

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
- **Location**: Proxmox LXC container (ID 200) at `192.168.211.153`
- **Binary**: `/usr/local/bin/livekit-server` (v1.10.1)
- **Mode**: `--dev` with `--bind 0.0.0.0`
- **Dev credentials**: API key `devkey`, secret `secret`
- **Config**: Default dev config (no yaml needed for dev mode)

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

### Why not Docker on Windows?
Docker Desktop for Windows runs containers inside a Linux VM. This breaks WebRTC because:
- `network_mode: host` binds to the VM, not the Windows host
- UDP port mapping for RTC media doesn't work reliably
- ICE candidates advertise Docker-internal IPs that external clients can't reach

Running natively on a Linux LXC container eliminates all these issues.

## Starting the Services

SSH into Proxmox and run inside the LXC container:

```bash
# 1. Start LiveKit server
nohup /usr/local/bin/livekit-server --dev --bind 0.0.0.0 > /var/log/livekit.log 2>&1 &

# 2. Start the voice agent
export LIVEKIT_URL=ws://localhost:7880
export LIVEKIT_API_KEY=devkey
export LIVEKIT_API_SECRET=secret
export OPENAI_API_KEY=<your-key>
export OPENROUTER_API_KEY=<your-key>
nohup /opt/agent/bin/python /opt/agent/agent.py > /var/log/agent.log 2>&1 &

# 3. Start the token server
LIVEKIT_API_KEY=devkey \
LIVEKIT_API_SECRET=secret \
LIVEKIT_EXTERNAL_URL=ws://192.168.211.153:7880 \
nohup /opt/agent/bin/python /opt/agent/token-server.py > /var/log/token.log 2>&1 &
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
# Agent logs (VAD, STT, Hermes, TTS activity)
tail -f /var/log/agent.log

# LiveKit server logs (room/participant events)
tail -f /var/log/livekit.log

# Token server logs
tail -f /var/log/token.log

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
├── docker-compose.yml        # Docker Compose (unused — was for Windows, had networking issues)
├── livekit.yaml              # LiveKit server config
├── .env                      # API keys
└── README.md                 # This file
```

## Future Work

- **Wake word detection**: OpenWakeWord on Android (ONNX) with configurable models
- **Persistent service**: Systemd units for auto-start on the LXC container
- **ElevenLabs TTS**: Higher quality voice (needs valid voice ID)
- **Streaming TTS**: Send audio as it generates instead of waiting for full response
- **Production LiveKit**: Replace `--dev` mode with proper API key/secret
- **WiFi-only mode**: Remove USB/ADB dependency, use LAN IP directly
