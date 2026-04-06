# Features

Complete feature list for the Wallup Claw voice agent and tablet platform.

## Voice Assistant

- **Wake Word Detection** — On-device "Hey Jarvis" (or Alexa, Hey Mycroft) via ONNX models running locally on the tablet. No cloud dependency for wake word.
- **LiveKit AgentSession** — Uses the official LiveKit Agents framework with two modes:
  - **Realtime (speech-to-speech)**: Gemini Live or OpenAI Realtime — server-side VAD, STT, and TTS in a single model
  - **Pipeline**: Separate STT (Deepgram, Google Chirp) → LLM (Gemini Flash, GPT-4o) → TTS (Cartesia, OpenAI)
- **Home Assistant MCP** — 92 tools for smart home control via Streamable HTTP MCP transport
- **Hermes Delegation** — `ask_hermes` tool delegates complex requests (calendar, web search, memory, automations) to the full Hermes AI agent
- **Live Transcripts** — Real-time user/assistant transcripts displayed in the conversation card via LiveKit data channel.
- **Per-Tablet Rooms** — Each tablet gets its own isolated LiveKit room (`voice-room-{device_id}`). Conversations on one tablet never bleed to another.
- **Configurable via Admin Portal** — Model, voice, temperature, and system prompt can be changed from the web UI without SSH.

## DLNA Speaker (Home Assistant `media_player`)

- **UPnP MediaRenderer** — Embedded DLNA renderer in the Android app advertises via SSDP. Home Assistant auto-discovers it as a `media_player` entity.
- **AVTransport Control** — Play, Pause, Stop, GetTransportInfo, GetPositionInfo. HA can send `media_player.play_media` to play any URL.
- **RenderingControl** — Volume set/get, Mute set/get. HA can control tablet volume remotely.
- **TTS Target** — Use `tts.speak` with the tablet as the media player target for voice announcements from HA automations.
- **Audio Focus Management** — DLNA music automatically ducks during voice conversations and pauses during intercom calls.
- **Foreground Service** — Runs independently of the app's UI lifecycle. Survives screen off, app backgrounding.

## Multi-Tablet Intercom

- **Room-to-Room Calling** — Tap a contact to call another tablet. Full-duplex WebRTC audio (and video if available) via dedicated call rooms.
- **Incoming Call Overlay** — Full-screen accept/decline UI when someone calls.
- **Call Signaling** — Token server manages call state machine: `call_request` → `call_accept`/`call_decline` → `call_hangup`.
- **Contacts Panel** — Swipe right to see all registered tablets with online/offline/in-call status.
- **HA-Triggered Calls** — `hermes_intercom.call` service lets HA automations initiate calls (e.g., doorbell → call all tablets).
- **Video Calling** — Camera enabled during intercom calls for face-to-face video between rooms.

## Home Assistant Integration

- **Custom Integration** — `hermes_intercom` HACS-compatible integration exposes tablets as HA devices.
- **Device Entities** — Each tablet gets: online binary sensor, call state sensor, last activity sensor, DND switch.
- **Services** — `call`, `broadcast`, `hangup`, `set_dnd` for automation control.
- **Events** — `call_started`, `call_ended`, `call_missed`, `device_online`, `device_offline` for triggering automations.
- **Automation Blueprints** — Doorbell → call all, missed call → phone notification, bedtime DND, motion → announce.
- **Lovelace Card** — Custom card showing device grid with status, call buttons, and quick announce bar.
- **HA WebView** — Tablet displays your HA dashboard as the main screen, with voice/intercom overlays on top.
- **Auto-Detection** — App auto-discovers HA on the local network (mDNS/IP scan).
- **Real-Time HA Events** — Agent monitors HA WebSocket for state changes (lights, sensors, climate) and logs them.

## Device State Management

- **Central Resource Coordinator** — `DeviceStateManager` manages mic, camera, and audio focus across three states: IDLE, CONVERSATION, INTERCOM_CALL.
- **Mic Arbitration** — Wake word (AudioRecord) ↔ voice conversation (LiveKit) ↔ intercom call (separate LiveKit room). Clean handoffs with 200ms delay.
- **Camera Arbitration** — Security stream pauses during intercom calls (single camera hardware), resumes after.
- **Audio Focus** — Voice conversations duck DLNA music (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`), intercom calls pause it (`AUDIOFOCUS_GAIN`).

## Token Server & Device Registry

- **JWT Token Issuance** — Issues LiveKit access tokens for tablets and agents.
- **Device Registry** — In-memory registry of tablets with display name, room location, online status, call state.
- **Heartbeat + Auto-Cleanup** — Tablets send heartbeats every 15s. Devices go offline after 45s, purged after 5min.
- **Heartbeat 404 Re-Registration** — If a heartbeat gets 404 (server restart), the tablet auto-re-registers.
- **Call Signaling Relay** — Long-poll endpoint for real-time call signal delivery.
- **TTS Proxy** — `GET /tts?text=...&voice=...` proxies to edge-tts or OpenAI TTS.
- **mDNS Advertisement** — Registers `_hermes-intercom._tcp.local.` for network discovery.
- **Remote Configuration** — `POST /configure` pushes settings to tablets (display name, room location).

## Android App

- **Kotlin + Jetpack Compose** — Modern declarative UI with animated transitions.
- **720p Camera** — Capture defaults set to 1280×720 @ 15fps for high-quality security cam + vision AI.
- **Auto-Update** — Checks GitHub releases for new APK versions with configurable intervals.
- **Settings Persistence** — DataStore preferences for all configuration (device ID, server URLs, wake word model, sensitivity, DND, etc.).
- **Room Disconnect Auto-Reconnect** — Handles LiveKit disconnections gracefully with automatic retry.
- **Swipe Gestures** — Swipe right for contacts, swipe left for settings.

## Admin Portal (Wallup Claw Admin)

- **AI Model Configuration** — Switch between Gemini Live, OpenAI Realtime, pipeline models, and custom model strings. Voice selector, temperature slider, system prompt editor with presets.
- **Device Fleet Management** — View all tablets, edit names/rooms, bulk assign, force disconnect.
- **LiveKit Server Settings** — Port, RTC range, logging level, API credentials.
- **System Management** — Docker container status, per-service restart/rebuild, log viewer, .env editor.
- **Home Assistant Integration** — MCP URL config, connection test, browse 92+ tools.
- **Intercom Management** — Active calls monitor, call history, editable timeouts.
- **TTS Configuration** — Backend/voice selector, test playback.
- **Live Monitor** — Real-time device heartbeats, active calls, LiveKit rooms, SSE log streaming.
- **Authentication** — Password-protected with httpOnly session cookies.
- **Dark/Light Theme** — Toggle in sidebar, persisted to localStorage.
- **Mobile Responsive** — Sidebar becomes drawer on mobile.

## Infrastructure

- **Docker Compose** — All services (LiveKit, voice agent, token server, admin dashboard) deployed via single `docker-compose.yml`.
- **Proxmox LXC** — Runs on Debian 13 container with `network_mode: host` for WebRTC UDP compatibility.
- **Multi-Tablet Session Manager** — Agent polls token server for registered devices and spawns independent AgentSession per tablet.
