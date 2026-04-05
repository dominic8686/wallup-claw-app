# Features

Complete feature list for the Hermes Voice Agent / Tablet Super-Device platform.

## Voice Assistant

- **Wake Word Detection** — On-device "Hey Jarvis" (or Alexa, Hey Mycroft) via ONNX models running locally on the tablet. No cloud dependency for wake word.
- **Speech-to-Text** — OpenRouter audio-capable models (default: Gemini Flash Lite) transcribe utterances to text.
- **AI Conversation** — Full Hermes AIAgent with tool calling, persistent memory, web search, Home Assistant control, and 56+ MCP server tools. Alternatively, lightweight OpenRouter direct mode.
- **Text-to-Speech** — Edge-TTS (free, no API key) or OpenAI TTS (`nova`, `alloy`, etc.). Configurable via `TTS_BACKEND` env var.
- **Live Transcripts** — Real-time user/assistant transcripts displayed in the conversation card via LiveKit data channel.
- **Silence Timeout** — Conversations auto-end after 30s of no speech, returning to wake word mode.
- **Per-Tablet Rooms** — Each tablet gets its own isolated LiveKit room (`voice-room-{device_id}`). Conversations on one tablet never bleed to another.

## Vision AI (Multimodal)

- **Ambient Vision** — When `VISION_ALWAYS_ATTACH=true` (default), every utterance during a conversation includes the latest camera frame. The AI always "sees" what the user sees.
- **Explicit Vision Queries** — Phrases like "What do you see?", "Look at this", "Read this", "How many?" trigger detailed visual analysis with higher resolution.
- **Conversation-Aware Vision** — The `MultimodalHandler` maintains a multimodal chat history (text + images) across turns, enabling follow-up questions about previous frames.
- **Proactive Scene Analysis** — External triggers (HA automations, doorbell) can ask the AI to describe what the camera sees via `proactive_describe` data channel messages.
- **Vision Model Selection** — Configurable via `VISION_MODEL` env var (default: `gpt-4o`). Any OpenAI-compatible vision model works.

## Security Camera

- **Always-On Camera Stream** — Front camera publishes a continuous 720p @ 15fps video track via LiveKit when `security_camera_enabled` is true (default).
- **RTSP Live Stream** — LiveKit video frames are piped through ffmpeg to a `mediamtx` RTSP server. Each tablet streams at `rtsp://<host>:8554/tablet-<device_id>`. Compatible with HA Generic Camera, Frigate, VLC.
- **Snapshot HTTP Endpoint** — `GET /snapshot` (or `GET /snapshot?device=<id>`) returns the latest JPEG frame on port 8091. Works with HA Generic Camera `still_image_url`.
- **Per-Device Frame Buffers** — Each tablet's video frames are stored independently, enabling multi-tablet camera dashboards.

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

## TalkingHead.js Avatar

- **3D Animated Avatar** — Optional TalkingHead.js avatar with real-time lip sync displayed in the conversation card.
- **Local Model** — Avatar GLB model served locally from the token server (no CDN dependency).
- **Muted Web Audio** — Avatar runs lip sync but audio comes through LiveKit, preventing double audio.
- **Agent-Driven Speech** — Agent sends `agent_speak` data channel messages; the avatar page handles TTS + lip animation.

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
- **TTS Proxy** — `GET /tts?text=...&voice=...` proxies to OpenAI TTS for the avatar page.
- **mDNS Advertisement** — Registers `_hermes-intercom._tcp.local.` for network discovery.
- **Remote Configuration** — `POST /configure` pushes settings to tablets (display name, room location).

## Android App

- **Kotlin + Jetpack Compose** — Modern declarative UI with animated transitions.
- **720p Camera** — Capture defaults set to 1280×720 @ 15fps for high-quality security cam + vision AI.
- **Auto-Update** — Checks GitHub releases for new APK versions with configurable intervals.
- **Settings Persistence** — DataStore preferences for all configuration (device ID, server URLs, wake word model, sensitivity, DND, etc.).
- **Room Disconnect Auto-Reconnect** — Handles LiveKit disconnections gracefully with automatic retry.
- **Swipe Gestures** — Swipe right for contacts, swipe left for settings.

## Infrastructure

- **Docker Compose** — All services (LiveKit, voice agent, token server, mediamtx) deployed via single `docker-compose.yml`.
- **Proxmox LXC** — Runs on Debian 13 container with `network_mode: host` for WebRTC UDP compatibility.
- **mediamtx Sidecar** — Lightweight RTSP relay server for camera streams.
- **Multi-Tablet Session Manager** — Agent polls token server for registered devices and spawns independent `TabletSession` per tablet.
- **Legacy Room Fallback** — Shared `voice-room` for tablets not yet updated to per-tablet rooms.
