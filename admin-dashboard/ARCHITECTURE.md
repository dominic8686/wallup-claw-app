# Wallup Claw Admin Portal — Architecture & Feature Roadmap

## 1. Overview

The Wallup Claw Admin Portal is a Next.js 16 web application that provides a unified management interface for the LiveKit-based voice agent platform. It manages AI model selection, device fleet operations, Docker service lifecycle, Home Assistant integration, intercom call monitoring, TTS configuration, and real-time system observability — all from a single browser-based dashboard.

The portal runs alongside the existing backend services (LiveKit Server, Voice Agent, Token Server) either on the same Proxmox LXC host or on a developer workstation, and communicates with the LXC via SSH and HTTP.

## 2. System Architecture

```
┌────────────────────────────────────────────────────────────────────┐
│  Browser (Admin Portal)                                            │
│  Next.js 16 — React 19, Tailwind 4, shadcn/ui, TanStack Query     │
│                                                                     │
│  Pages:                                                             │
│    /           Dashboard (health, stats, quick actions)             │
│    /models     AI model config (LLM, voice, STT/TTS, prompt)       │
│    /devices    Device fleet (table, bulk actions, disconnect)       │
│    /livekit    LiveKit server config (ports, RTC, API keys)         │
│    /system     Docker management (containers, logs, .env editor)   │
│    /home-assistant  HA MCP URL, token, tools browser               │
│    /intercom   Active calls, call history, timeout settings         │
│    /tts        TTS backend/voice config, test playback              │
│    /monitor    Real-time devices, calls, rooms, SSE log stream     │
│    /login      Password authentication gate                         │
└──────────┬──────────────────────────────┬──────────────────────────┘
           │ Next.js API Routes            │ Direct HTTP (client-side)
           │ (server-side)                 │
           ▼                               ▼
┌─────────────────────────┐  ┌──────────────────────────────────────┐
│  SSH to Proxmox LXC     │  │  Token Server (Python/aiohttp :8090) │
│  (node-ssh, singleton   │  │                                      │
│   pooled connection)    │  │  GET  /devices                       │
│                         │  │  GET  /calls                         │
│  • docker compose ps    │  │  POST /configure                     │
│  • docker compose       │  │  POST /signal                        │
│    restart/rebuild      │  │  GET  /tts?text=...                  │
│  • cat / write files    │  │  GET  /health                        │
│    (docker-compose.yml, │  └──────────────────────────────────────┘
│     livekit.yaml, .env) │
│  • docker logs -f       │  ┌──────────────────────────────────────┐
│    (SSE streaming)      │  │  LiveKit Server (:7880)              │
│                         │  │  Twirp API /ListRooms (via curl on   │
└─────────────────────────┘  │  LXC, proxied through /api/livekit)  │
                             └──────────────────────────────────────┘
```

### 2.1 Communication Patterns

- **SSH (server-side only)**: All config file reads/writes and Docker operations go through `lib/ssh.ts`, which maintains a singleton `NodeSSH` connection with keepalive and auto-reconnect. Files are backed up before writes, and content is base64-encoded to avoid shell escaping issues.
- **Token Server (client-side)**: Device list, call state, TTS test, and device configuration are fetched directly from the browser via CORS-enabled HTTP to the token server.  The `NEXT_PUBLIC_TOKEN_SERVER_URL` env var makes this URL available client-side.
- **SSE log streaming**: The `/api/logs-stream` route opens a dedicated SSH connection and runs `docker logs -f` in streaming mode, piping output through an SSE response. Auto-closes after 5 minutes.

### 2.2 Authentication

Password-based with httpOnly session cookies (`wc_session`). Controlled by the `DASHBOARD_PASSWORD` env var — if unset, auth is disabled entirely. The Next.js middleware intercepts all routes except `/login`, `/api/auth`, and static assets.

### 2.3 Configuration Persistence

Two persistence layers:

1. **Remote (LXC)**: `docker-compose.yml`, `livekit.yaml`, `.env` — modified via SSH, affect running services
2. **Local (dashboard)**: `.data/settings.json` and `.data/audit.json` — stores prompt presets and config change audit log

## 3. File Structure

```
admin-dashboard/
├── src/
│   ├── app/
│   │   ├── layout.tsx              Root layout (sidebar, mobile nav, breadcrumbs)
│   │   ├── page.tsx                Dashboard overview
│   │   ├── login/
│   │   │   ├── layout.tsx          Login-only layout (no sidebar)
│   │   │   └── page.tsx            Password form
│   │   ├── models/page.tsx         AI model configuration
│   │   ├── devices/page.tsx        Device fleet management
│   │   ├── livekit/page.tsx        LiveKit server settings
│   │   ├── system/page.tsx         Docker + .env management
│   │   ├── home-assistant/page.tsx HA MCP integration
│   │   ├── intercom/page.tsx       Call monitor + history
│   │   ├── tts/page.tsx            TTS config + test
│   │   ├── monitor/page.tsx        Live monitoring
│   │   └── api/
│   │       ├── auth/route.ts       Login/logout
│   │       ├── health/route.ts     Aggregated health check
│   │       ├── config/route.ts     docker-compose.yml env vars (GET/PUT)
│   │       ├── env/route.ts        .env file (GET/PUT, masked secrets)
│   │       ├── docker/route.ts     Container ps, logs, restart, rebuild
│   │       ├── livekit/route.ts    livekit.yaml config + rooms API
│   │       ├── logs-stream/route.ts SSE log streaming via SSH
│   │       └── settings/route.ts   Dashboard settings + audit log
│   ├── components/
│   │   ├── sidebar.tsx             Desktop sidebar + mobile drawer
│   │   ├── breadcrumbs.tsx         Auto-generated breadcrumb nav
│   │   ├── confirm-dialog.tsx      Reusable confirmation dialog
│   │   ├── error-boundary.tsx      React error boundary
│   │   ├── providers.tsx           QueryClient + ThemeProvider + ErrorBoundary
│   │   ├── theme-provider.tsx      Dark/light theme with localStorage
│   │   └── ui/                     18 shadcn/ui components
│   ├── lib/
│   │   ├── ssh.ts                  Singleton SSH connection + Docker helpers
│   │   ├── token-server.ts         Token server HTTP client
│   │   ├── auth.ts                 Session cookie management
│   │   ├── settings.ts             JSON file persistence (presets, audit)
│   │   ├── types.ts                Shared TypeScript interfaces
│   │   └── utils.ts                Tailwind cn() helper
│   └── middleware.ts               Route protection
├── .data/                          Local persistence (gitignored)
│   ├── settings.json               Prompt presets
│   └── audit.json                  Config change log
├── .env.local                      Dashboard env vars
└── package.json                    Next.js 16, React 19, node-ssh, TanStack Query
```

## 4. Tech Stack

- **Runtime**: Next.js 16.2 (App Router, Turbopack)
- **UI**: React 19, Tailwind CSS 4, shadcn/ui (18 components), Lucide icons
- **State**: TanStack React Query (auto-refetch at 10–15s intervals, 5s for monitoring)
- **SSH**: node-ssh 13 (wraps ssh2) — singleton pooled connection with keepalive
- **Streaming**: SSE via ReadableStream + ssh2 channel for `docker logs -f`
- **Persistence**: JSON files for dashboard settings; remote files via SSH for service config
- **Auth**: Cookie-based session with SHA-256 token, Next.js middleware

## 5. Implemented Features

### 5.1 Dashboard (/)
- Health indicators for LiveKit Server, Voice Agent, Token Server (green/red)
- Device count (online/registered), active calls count, current AI model + voice
- Quick-action buttons: restart individual services or all
- Container status table (name, image, state, uptime)

### 5.2 AI Models (/models)
- LLM selector: Gemini Live, OpenAI Realtime (speech-to-speech); Gemini 2.5 Flash, GPT-4o, GPT-4o Mini (pipeline); custom model string input
- Voice selector: provider-aware (Google voices for Gemini, OpenAI voices for GPT); voice preview via token server TTS
- Temperature slider: 0.0–2.0 range
- Pipeline STT/TTS: conditional display for pipeline-mode models (Deepgram, Google Chirp, Cartesia, OpenAI TTS)
- System prompt editor: monospace textarea with prompt preset library (load, save, delete presets)
- Confirmation dialog before Save & Restart showing changed values

### 5.3 Devices (/devices)
- Table: device ID, display name, room, status (online/offline), call state, last seen
- Edit dialog: push display name + room location to device via `/configure`
- Bulk actions: select-all toggle, multi-select with switches, bulk room assignment
- Force disconnect: sends `force_disconnect` signal to online devices

### 5.4 LiveKit Server (/livekit)
- Server port, RTC port range (start/end), logging level (debug/info/warn/error)
- API key display (readonly), API secret (masked with reveal toggle)
- Save & restart LiveKit server (writes livekit.yaml via SSH)

### 5.5 System (/system)
- Container status cards with name, image, state badge, uptime
- Per-service actions: restart and rebuild buttons for each service
- Bulk actions: restart all, rebuild & restart all
- Log viewer: select service, fetch last 150 lines
- Environment variables editor: inline edit of all `.env` keys, masked secrets with reveal toggle, save to LXC

### 5.6 Home Assistant (/home-assistant)
- HA MCP URL input + bearer token (masked)
- Test Connection button: sends JSON-RPC `tools/list` request, shows tool count
- MCP tools browser: searchable list of all discovered HA tools with names and descriptions
- Save & restart voice agent

### 5.7 Intercom (/intercom)
- Active calls table (5s refresh): from, to, room, status, duration
- Device call states: list of non-idle devices with state badges
- Call history: client-side accumulator captures calls during the session, displayed in a table
- Editable timeouts: ring timeout, stale timeout, long-poll timeout with save & restart

### 5.8 TTS (/tts)
- Backend selector: edge-tts (free) vs OpenAI (paid)
- Voice selector: auto-updates options based on backend
- Test TTS: text input + play button, streams audio from token server `/tts` endpoint
- Save & restart token server

### 5.9 Live Monitor (/monitor)
- Device heartbeats: online/offline dots with display names (5s refresh)
- Active calls: from → to with status badges (5s refresh)
- Voice sessions: LiveKit rooms with participant counts from Twirp API (10s refresh)
- Log stream: SSE-based `docker logs -f` via SSH channel, 5-minute sessions, auto-scroll, start/stop controls with LIVE indicator

### 5.10 Cross-cutting
- Auth: password login page, httpOnly session cookie, middleware protection
- Theme: dark/light toggle in sidebar footer, persisted to localStorage
- Mobile: responsive sidebar becomes Sheet drawer below `md` breakpoint, hamburger menu
- Breadcrumbs: auto-generated from pathname on all sub-pages
- Error boundary: wraps all content with retry button
- Audit log: every config change recorded to `.data/audit.json` with timestamp, action, and details
- Config safety: auto-backup (`.bak`) before every remote file write

## 6. API Route Reference

| Route | Method | Purpose |
|-------|--------|---------|
| `/api/auth` | GET | Check if auth is enabled |
| `/api/auth` | POST | Login (password) or logout |
| `/api/health` | GET | Aggregated health: docker ps + token server + config extract |
| `/api/config` | GET | Read docker-compose.yml env vars (agent + token server keys) |
| `/api/config` | PUT | Update env vars, optionally restart a service; writes audit log |
| `/api/env` | GET | Read .env file from LXC (masked secrets) |
| `/api/env` | PUT | Update .env key-value pairs on LXC |
| `/api/docker?action=ps` | GET | Docker compose ps (JSON per line) |
| `/api/docker?action=logs&service=X` | GET | Docker compose logs (last N lines) |
| `/api/docker` | POST | Restart or rebuild a service (or all) |
| `/api/livekit` | GET | Read livekit.yaml config values |
| `/api/livekit?action=rooms` | GET | List active LiveKit rooms (Twirp API) |
| `/api/livekit` | PUT | Update livekit.yaml values, optionally restart |
| `/api/logs-stream?service=X` | GET (SSE) | Real-time log streaming via SSH channel |
| `/api/settings` | GET | Read dashboard settings (prompt presets) |
| `/api/settings?action=audit` | GET | Read audit log entries |
| `/api/settings` | PUT | Update dashboard settings |

## 7. Environment Variables

### Dashboard (`.env.local` or Docker env)
| Variable | Required | Description |
|----------|----------|-------------|
| `DASHBOARD_PASSWORD` | No | Password to protect the portal. Empty = auth disabled. |
| `LXC_HOST` | Yes | IP of the Proxmox LXC running Docker Compose |
| `LXC_USER` | Yes | SSH username (typically `root`) |
| `LXC_SSH_KEY` | Yes* | Path to SSH private key |
| `LXC_PASSWORD` | Yes* | SSH password (fallback if no key) |
| `COMPOSE_DIR` | Yes | Path to docker-compose project on the LXC |
| `NEXT_PUBLIC_TOKEN_SERVER_URL` | Yes | Token server URL accessible from the browser |

*One of `LXC_SSH_KEY` or `LXC_PASSWORD` is required.

## 8. Data Flow Diagrams

### 8.1 AI Model Change
```
User picks new model + voice → clicks "Save & Restart"
  → ConfirmDialog shows: model, voice, temperature
  → User confirms
  → PUT /api/config {updates: {LIVEKIT_LLM, LIVEKIT_VOICE}, restart: "voice-agent"}
    → SSH: read docker-compose.yml
    → regex replace env vars
    → SSH: backup + write docker-compose.yml
    → SSH: docker compose restart voice-agent
    → appendAudit("config_updated", {keys, restart})
  → Toast: "Saved & restarting agent"
  → invalidateQueries(["config", "health"])
```

### 8.2 Device Configuration Push
```
User selects devices → enters room name → clicks "Apply Room"
  → For each device_id: POST token-server/configure {device_id, settings}
  → Token server stores in pending_config dict
  → Next heartbeat from tablet: GET /configure?device_id=X
  → Tablet receives and applies settings
```

### 8.3 Log Streaming
```
User clicks "Start Stream" on Monitor page
  → EventSource connects to /api/logs-stream?service=voice-agent
  → API route opens dedicated SSH connection
  → SSH exec: docker compose logs --tail=100 -f voice-agent
  → ssh2 channel.on("data") → SSE data frames
  → Client appends to logBuffer, auto-scrolls
  → Auto-closes after 5 minutes or user clicks Stop
  → SSH connection disposed on close/cancel
```

## 9. Future Roadmap

### 9.1 Short-term Improvements
- **Proper YAML parser**: Replace regex-based livekit.yaml parsing with `yaml` npm package for reliability with nested keys
- **WebSocket for real-time**: Replace polling-based device/call monitoring with WebSocket push from the token server
- **Audit log page**: Dedicated page to browse the `.data/audit.json` change history with filtering
- **Config diff preview**: Show a side-by-side diff of old vs new docker-compose.yml before writing
- **API key rotation**: Generate new LIVEKIT_API_KEY/SECRET from the LiveKit page and update all references

### 9.2 Medium-term Features
- **Multi-environment support**: Switch between dev/staging/prod LXC hosts from a dropdown; store per-env SSH configs
- **OTA tablet updates**: Upload APK to token server, trigger push to selected devices, track version in device table
- **Session recording browser**: List and playback recorded voice sessions from LiveKit's egress API
- **Resource monitoring**: CPU, memory, disk usage charts for the LXC host (via `docker stats` or Prometheus)
- **Persistent call history**: Add a SQLite database to the token server to store call events; expose via REST API

### 9.3 Long-term Vision
- **A/B testing for models**: Run two model configurations simultaneously, route devices to different groups, compare response quality metrics
- **Prompt versioning**: Git-like version history for system prompts with rollback capability
- **Custom model registration**: Add arbitrary LLM model strings with associated STT/TTS/voice presets
- **HA automation builder**: Visual editor for creating Home Assistant automations that trigger from voice commands
- **Tablet screen management**: Push dashboard URLs, screensaver settings, and display brightness to tablets via `/configure`

## 10. Deployment

### Development (Windows workstation)
```powershell
cd admin-dashboard
npm run dev
# Connects to LXC via SSH key at C:\Users\domin\.ssh\proxmox_hermes
```

### Production (Docker Compose on LXC)
The `admin-dashboard` service is defined in `docker-compose.yml`:
```yaml
admin-dashboard:
  build: ./admin-dashboard
  network_mode: host
  environment:
    - LXC_HOST=127.0.0.1          # localhost (running on the same LXC)
    - LXC_USER=root
    - LXC_SSH_KEY=/ssh/admin_dashboard
    - COMPOSE_DIR=/opt/livekit-voice-agent
    - NEXT_PUBLIC_TOKEN_SERVER_URL=http://localhost:8090
    - PORT=3000
  volumes:
    - /root/.ssh/admin_dashboard:/ssh/admin_dashboard:ro
```

Accessible at `http://<LXC_IP>:3000` from any device on the LAN.

## 11. Security Considerations

- **LAN-only**: Designed for private network access. No HTTPS, no CSRF tokens. Do not expose to the internet.
- **SSH key management**: The dashboard has root SSH access to the LXC. The private key must be readable only by the dashboard process.
- **Secret masking**: The `.env` editor masks values for keys containing KEY, SECRET, TOKEN, or PASS. The original values are never sent to the browser for masked fields (only a masked representation).
- **Session cookies**: `httpOnly`, `sameSite: lax`, no `secure` flag (HTTP-only LAN). 7-day expiry.
- **No input sanitization on SSH commands**: Config values are written via base64 encoding to prevent shell injection, but the service name parameter in Docker commands is not escaped. This is acceptable for a single-admin LAN tool but would need hardening for multi-user or internet-facing deployments.
