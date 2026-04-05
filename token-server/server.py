"""Token & device registry server for LiveKit multi-tablet intercom.

Endpoints:
  GET  /token?identity=<device_id>&room=<room_name>
       Returns: {"token": "<jwt>", "url": "<livekit_ws_url>"}

  POST /register   (JSON body: {device_id, display_name?, room_location?})
       Registers a tablet in the device registry.

  GET  /devices
       Returns list of all registered devices with online/offline status.

  POST /heartbeat  (JSON body: {device_id})
       Refreshes a device's last_seen timestamp. Devices not seen for
       STALE_TIMEOUT seconds are marked offline.

  POST /signal     (JSON body: {type, from, to, ...})
       Relays a call-signaling message to the target device's pending queue.

  GET  /signals?device_id=<id>
       Long-poll: returns pending signals for a device (blocks up to 25s).

  GET  /calls
       Returns list of active calls.

  GET  /health
       Returns 200 OK.

  GET  /tts?text=<text>&voice=<voice>
       Generate speech via edge-tts (free, no API key).
       Returns audio/mpeg stream. Default voice: en-GB-SoniaNeural (TTS_VOICE env).

  GET  /avatar
  GET  /avatar/<path>
       Serves static files from the avatar/ directory (TalkingHead.js page).
"""

import asyncio
import json
import mimetypes
import os
import re
import socket
import time
from pathlib import Path
from typing import Any

import aiofiles
from aiohttp import web
from livekit.api import AccessToken, VideoGrants
from zeroconf import IPVersion, ServiceInfo, Zeroconf

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

LIVEKIT_API_KEY = os.environ["LIVEKIT_API_KEY"]
LIVEKIT_API_SECRET = os.environ["LIVEKIT_API_SECRET"]
LIVEKIT_EXTERNAL_URL = os.environ.get("LIVEKIT_EXTERNAL_URL", "ws://localhost:7880")

# TTS_BACKEND  edge-tts (default, free) | openai (requires OPENAI_API_KEY)
# TTS_VOICE    edge-tts: en-GB-SoniaNeural   openai: nova | alloy | echo | fable | onyx | shimmer
TTS_BACKEND = os.environ.get("TTS_BACKEND", "edge-tts").strip().lower()
TTS_VOICE = os.environ.get(
    "TTS_VOICE",
    "nova" if TTS_BACKEND == "openai" else "en-GB-SoniaNeural"
)
OPENAI_API_KEY = os.environ.get("OPENAI_API_KEY", "")
print(f"  TTS backend: {TTS_BACKEND} | voice: {TTS_VOICE}")

# Path to avatar static files (relative to this script, or absolute)
AVATAR_DIR = Path(os.environ.get("AVATAR_DIR", Path(__file__).parent.parent / "avatar")).resolve()

STALE_TIMEOUT = 45  # seconds before a device is considered offline
LONG_POLL_TIMEOUT = 25  # seconds to hold a /signals request
CALL_RING_TIMEOUT = 60  # seconds before an unanswered call auto-expires

# API key authentication (set via INTERCOM_API_KEY env var)
# If not set, auth is disabled (LAN-only mode).
INTERCOM_API_KEY = os.environ.get("INTERCOM_API_KEY", "")
if INTERCOM_API_KEY:
    print(f"  Auth: API key enabled ({len(INTERCOM_API_KEY)} chars)")
else:
    print("  Auth: DISABLED (set INTERCOM_API_KEY to enable)")

# ---------------------------------------------------------------------------
# In-memory device registry
# ---------------------------------------------------------------------------

# device_id -> {display_name, room_location, last_seen, status, call_state}
device_registry: dict[str, dict[str, Any]] = {}

# device_id -> asyncio.Queue of signal dicts
signal_queues: dict[str, asyncio.Queue] = {}

# Active calls: call_id -> {from, to, room_name, started_at, status}
active_calls: dict[str, dict[str, Any]] = {}

# Pending config: device_id -> {settings dict}
pending_config: dict[str, dict[str, Any]] = {}


def _get_or_create_queue(device_id: str) -> asyncio.Queue:
    if device_id not in signal_queues:
        signal_queues[device_id] = asyncio.Queue()
    return signal_queues[device_id]


PURGE_TIMEOUT = 300  # Remove devices offline for more than 5 minutes


def _mark_stale_devices() -> None:
    """Mark devices that haven't sent a heartbeat as offline, purge old ones."""
    now = time.time()
    to_remove = []
    for device_id, info in device_registry.items():
        if info["status"] == "online" and (now - info["last_seen"]) > STALE_TIMEOUT:
            info["status"] = "offline"
        elif info["status"] == "offline" and (now - info["last_seen"]) > PURGE_TIMEOUT:
            to_remove.append(device_id)
    for device_id in to_remove:
        device_registry.pop(device_id, None)
        signal_queues.pop(device_id, None)


def _expire_stale_calls() -> None:
    """Auto-cancel calls stuck in 'ringing' for too long."""
    now = time.time()
    expired = [
        cid for cid, c in active_calls.items()
        if c.get("status") == "ringing" and (now - c.get("started_at", now)) > CALL_RING_TIMEOUT
    ]
    for cid in expired:
        call = active_calls.pop(cid)
        # Reset device states
        if call["from"] in device_registry:
            device_registry[call["from"]]["call_state"] = "idle"
        if call["to"] in device_registry:
            device_registry[call["to"]]["call_state"] = "idle"
        print(f"[calls] Expired stale call: {cid}")


def _device_to_dict(device_id: str, info: dict) -> dict:
    return {
        "device_id": device_id,
        "display_name": info.get("display_name", device_id),
        "room_location": info.get("room_location", ""),
        "status": info.get("status", "offline"),
        "call_state": info.get("call_state", "idle"),
        "last_seen": info.get("last_seen", 0),
    }


# ---------------------------------------------------------------------------
# CORS helper
# ---------------------------------------------------------------------------

CORS_HEADERS = {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
}

# Paths that don't require authentication.
# Tablet-facing endpoints (register, heartbeat, signals, token) are exempt
# because the Android app connects directly on the LAN without an API key.
_PUBLIC_PATHS = {"/health", "/avatar", "/register", "/heartbeat", "/signals", "/token"}


@web.middleware
async def auth_middleware(request: web.Request, handler):
    """Check Bearer token on all endpoints except health and OPTIONS."""
    if not INTERCOM_API_KEY:
        return await handler(request)  # Auth disabled
    if request.method == "OPTIONS":
        return await handler(request)  # CORS preflight
    if request.path in _PUBLIC_PATHS or request.path.startswith("/avatar/"):
        return await handler(request)

    auth = request.headers.get("Authorization", "")
    if auth == f"Bearer {INTERCOM_API_KEY}":
        return await handler(request)

    return web.Response(
        text=json.dumps({"error": "Unauthorized"}),
        content_type="application/json",
        status=401,
        headers=CORS_HEADERS,
    )


def _json_response(data: Any, status: int = 200) -> web.Response:
    return web.Response(
        text=json.dumps(data),
        content_type="application/json",
        status=status,
        headers=CORS_HEADERS,
    )


# ---------------------------------------------------------------------------
# Handlers
# ---------------------------------------------------------------------------

async def handle_token(request: web.Request) -> web.Response:
    """Issue a LiveKit JWT for a device to join a room."""
    identity = request.query.get("identity", "android-user")
    room = request.query.get("room", "voice-room")

    token = (
        AccessToken(LIVEKIT_API_KEY, LIVEKIT_API_SECRET)
        .with_identity(identity)
        .with_grants(
            VideoGrants(
                room_join=True,
                room=room,
                can_publish=True,
                can_subscribe=True,
            )
        )
    )

    jwt_token = token.to_jwt()
    return _json_response({"token": jwt_token, "url": LIVEKIT_EXTERNAL_URL})


async def handle_register(request: web.Request) -> web.Response:
    """Register a tablet device."""
    try:
        body = await request.json()
    except Exception:
        return _json_response({"error": "invalid JSON"}, 400)

    device_id = body.get("device_id", "").strip()
    if not device_id:
        return _json_response({"error": "device_id required"}, 400)

    now = time.time()
    device_registry[device_id] = {
        "display_name": body.get("display_name", device_id),
        "room_location": body.get("room_location", ""),
        "last_seen": now,
        "status": "online",
        "call_state": body.get("call_state", "idle"),
    }
    _get_or_create_queue(device_id)

    print(f"[registry] Device registered: {device_id} ({body.get('display_name', device_id)})")
    return _json_response({"ok": True, "device_id": device_id})


async def handle_heartbeat(request: web.Request) -> web.Response:
    """Refresh a device's online status."""
    try:
        body = await request.json()
    except Exception:
        return _json_response({"error": "invalid JSON"}, 400)

    device_id = body.get("device_id", "").strip()
    if not device_id or device_id not in device_registry:
        return _json_response({"error": "unknown device_id"}, 404)

    device_registry[device_id]["last_seen"] = time.time()
    device_registry[device_id]["status"] = "online"

    # Allow updating call_state via heartbeat
    if "call_state" in body:
        device_registry[device_id]["call_state"] = body["call_state"]
    else:
        # Auto-reset stale call states if no active call involves this device
        current_state = device_registry[device_id].get("call_state", "idle")
        if current_state not in ("idle", "do_not_disturb"):
            in_active_call = any(
                c for c in active_calls.values()
                if device_id in (c.get("from"), c.get("to"))
            )
            if not in_active_call:
                device_registry[device_id]["call_state"] = "idle"

    return _json_response({"ok": True})


async def handle_devices(request: web.Request) -> web.Response:
    """Return list of all registered devices with current status."""
    _mark_stale_devices()
    _expire_stale_calls()
    devices = [
        _device_to_dict(did, info)
        for did, info in device_registry.items()
    ]
    return _json_response({"devices": devices})


async def handle_signal(request: web.Request) -> web.Response:
    """Relay a call-signaling message to a target device.

    Body: {type: "call_request"|"call_accept"|"call_decline"|"call_hangup"|"call_ringing",
           from: "<device_id>", to: "<device_id>", ...extra fields}
    """
    try:
        body = await request.json()
    except Exception:
        return _json_response({"error": "invalid JSON"}, 400)

    signal_type = body.get("type", "")
    from_device = body.get("from", "")
    to_device = body.get("to", "")

    if not signal_type or not from_device or not to_device:
        return _json_response({"error": "type, from, to required"}, 400)

    # Validate target exists
    if to_device not in device_registry:
        return _json_response({"error": f"device '{to_device}' not registered"}, 404)

    _mark_stale_devices()
    _expire_stale_calls()
    target_info = device_registry[to_device]

    # --- Call state machine ---
    if signal_type == "call_request":
        if target_info["status"] != "online":
            return _json_response({"error": f"device '{to_device}' is offline"}, 409)
        if target_info["call_state"] == "do_not_disturb":
            return _json_response({"error": f"device '{to_device}' is in Do Not Disturb mode"}, 409)
        if target_info["call_state"] not in ("idle",):
            return _json_response({"error": f"device '{to_device}' is busy ({target_info['call_state']})"}, 409)

        # Create a call record
        call_id = f"call-{from_device}-{to_device}-{int(time.time())}"
        room_name = call_id
        active_calls[call_id] = {
            "from": from_device,
            "to": to_device,
            "room_name": room_name,
            "started_at": time.time(),
            "status": "ringing",
        }
        body["call_id"] = call_id
        body["room_name"] = room_name

        # Update device call states
        if from_device in device_registry:
            device_registry[from_device]["call_state"] = "calling"
        device_registry[to_device]["call_state"] = "ringing"

    elif signal_type == "call_accept":
        call_id = body.get("call_id", "")
        if call_id in active_calls:
            active_calls[call_id]["status"] = "active"
            if from_device in device_registry:
                device_registry[from_device]["call_state"] = "in_call"
            if to_device in device_registry:
                device_registry[to_device]["call_state"] = "in_call"

    elif signal_type == "call_decline":
        call_id = body.get("call_id", "")
        if call_id in active_calls:
            call = active_calls.pop(call_id)
            if call["from"] in device_registry:
                device_registry[call["from"]]["call_state"] = "idle"
            if call["to"] in device_registry:
                device_registry[call["to"]]["call_state"] = "idle"

    elif signal_type == "call_hangup":
        call_id = body.get("call_id", "")
        if call_id in active_calls:
            call = active_calls.pop(call_id)
            if call["from"] in device_registry:
                device_registry[call["from"]]["call_state"] = "idle"
            if call["to"] in device_registry:
                device_registry[call["to"]]["call_state"] = "idle"

    # Enqueue signal for the target device
    body["timestamp"] = time.time()
    queue = _get_or_create_queue(to_device)
    await queue.put(body)

    # Also notify the caller for accept/decline/hangup
    if signal_type in ("call_accept", "call_decline", "call_hangup"):
        caller_queue = _get_or_create_queue(from_device)
        await caller_queue.put(body)

    print(f"[signal] {signal_type}: {from_device} -> {to_device}")
    return _json_response({"ok": True, **{k: body[k] for k in ("call_id", "room_name") if k in body}})


async def handle_signals(request: web.Request) -> web.Response:
    """Long-poll: returns pending signals for a device.

    Blocks up to LONG_POLL_TIMEOUT seconds waiting for signals.
    Returns immediately if signals are already queued.
    """
    device_id = request.query.get("device_id", "").strip()
    if not device_id:
        return _json_response({"error": "device_id required"}, 400)

    queue = _get_or_create_queue(device_id)
    signals = []

    # Drain any already-queued signals
    while not queue.empty():
        try:
            signals.append(queue.get_nowait())
        except asyncio.QueueEmpty:
            break

    if signals:
        return _json_response({"signals": signals})

    # Long-poll: wait for a signal
    try:
        signal = await asyncio.wait_for(queue.get(), timeout=LONG_POLL_TIMEOUT)
        signals.append(signal)
        # Drain any additional signals that arrived
        while not queue.empty():
            try:
                signals.append(queue.get_nowait())
            except asyncio.QueueEmpty:
                break
    except asyncio.TimeoutError:
        pass  # Return empty list on timeout

    return _json_response({"signals": signals})


async def handle_calls(request: web.Request) -> web.Response:
    """Return list of active calls."""
    return _json_response({"calls": list(active_calls.values())})


async def handle_options(request: web.Request) -> web.Response:
    """Handle CORS preflight."""
    return web.Response(status=204, headers=CORS_HEADERS)


async def handle_configure(request: web.Request) -> web.Response:
    """Store or retrieve pending configuration for a device.

    POST: {device_id, settings: {display_name?, room_location?, ...}}
    GET:  ?device_id=<id> -> returns and clears pending config
    """
    if request.method == "GET":
        device_id = request.query.get("device_id", "").strip()
        if not device_id:
            return _json_response({"error": "device_id required"}, 400)
        config = pending_config.pop(device_id, None)
        return _json_response({"config": config})

    # POST
    try:
        body = await request.json()
    except Exception:
        return _json_response({"error": "invalid JSON"}, 400)

    device_id = body.get("device_id", "").strip()
    settings = body.get("settings", {})
    if not device_id:
        return _json_response({"error": "device_id required"}, 400)
    if not settings:
        return _json_response({"error": "settings required"}, 400)

    # Merge with any existing pending config
    existing = pending_config.get(device_id, {})
    existing.update(settings)
    pending_config[device_id] = existing

    print(f"[configure] Pending config for {device_id}: {existing}")
    return _json_response({"ok": True, "device_id": device_id})


async def handle_health(request: web.Request) -> web.Response:
    return web.Response(text="ok", headers=CORS_HEADERS)


async def handle_tts(request: web.Request) -> web.Response:
    """Generate speech via edge-tts or OpenAI TTS.

    Supports two call formats:
      GET  /tts?text=<text>[&voice=<voice>]  → audio/mpeg binary
      POST /tts  (Google Cloud TTS JSON body)  → {audioContent: base64}

    TTS backend is selected by the TTS_BACKEND env var (edge-tts | openai).
    """
    import base64

    # --- Determine text and extract SSML marks (voice always from server config) ---
    mark_names: list = []
    if request.method == "POST":
        try:
            body = await request.json()
        except Exception:
            return web.Response(text="invalid JSON", status=400, headers=CORS_HEADERS)
        inp = body.get("input", {})
        ssml = (inp.get("ssml") or "").strip()
        if ssml:
            # Extract <mark name="..."/> tags (TalkingHead needs these back as timepoints)
            mark_names = re.findall(r'<mark\s+name=["\']([^"\']+)["\']', ssml)
            # Strip SSML tags to get speakable plain text
            text = re.sub(r'<[^>]+>', ' ', ssml)
            text = re.sub(r'\s+', ' ', text).strip()
        else:
            text = (inp.get("text") or "").strip()
    else:
        text = request.query.get("text", "").strip()

    if not text:
        return web.Response(text="text parameter required", status=400, headers=CORS_HEADERS)

    try:
        if TTS_BACKEND == "openai":
            if not OPENAI_API_KEY:
                return web.Response(text="OPENAI_API_KEY not set", status=503, headers=CORS_HEADERS)
            import asyncio
            from openai import OpenAI
            client = OpenAI(api_key=OPENAI_API_KEY)
            response = await asyncio.get_event_loop().run_in_executor(
                None,
                lambda: client.audio.speech.create(
                    model="tts-1",
                    voice=TTS_VOICE,
                    input=text,
                    response_format="mp3",
                )
            )
            audio_bytes = response.content
        else:
            import edge_tts
            communicate = edge_tts.Communicate(text, TTS_VOICE)
            mp3_chunks = []
            async for chunk in communicate.stream():
                if chunk["type"] == "audio":
                    mp3_chunks.append(chunk["data"])
            if not mp3_chunks:
                return web.Response(text="TTS returned no audio", status=502, headers=CORS_HEADERS)
            audio_bytes = b"".join(mp3_chunks)

        # POST callers (TalkingHead) expect Google TTS JSON response + timepoints
        if request.method == "POST":
            # Generate approximate timepoints for lip sync.
            # Estimate total duration from word count (~0.4 s/word at 150 wpm).
            words = text.split()
            total_dur = max(len(words), 1) * 0.4
            if mark_names:
                interval = total_dur / len(mark_names)
                timepoints = [
                    {"markName": name, "timeSeconds": round(i * interval, 3)}
                    for i, name in enumerate(mark_names)
                ]
            else:
                timepoints = []
            return web.Response(
                text=json.dumps({
                    "audioContent": base64.b64encode(audio_bytes).decode(),
                    "timepoints": timepoints,
                }),
                content_type="application/json",
                headers=CORS_HEADERS,
            )

        # GET callers get raw MP3
        return web.Response(
            body=audio_bytes,
            content_type="audio/mpeg",
            headers={**CORS_HEADERS, "Cache-Control": "no-cache"},
        )
    except Exception as e:
        print(f"[tts] error ({TTS_BACKEND}): {e}")
        return web.Response(text=f"TTS error: {e}", status=500, headers=CORS_HEADERS)


async def handle_avatar(request: web.Request) -> web.Response:
    """Serve static files from the avatar/ directory.

    GET /avatar        -> avatar/index.html
    GET /avatar/foo    -> avatar/foo
    """
    # Extract sub-path (everything after /avatar)
    sub_path = request.match_info.get("tail", "").lstrip("/")
    if not sub_path:
        sub_path = "index.html"

    file_path = (AVATAR_DIR / sub_path).resolve()

    # Security: reject path traversal outside AVATAR_DIR
    if not str(file_path).startswith(str(AVATAR_DIR)):
        return web.Response(text="Forbidden", status=403)

    if not file_path.exists() or not file_path.is_file():
        return web.Response(text="Not found", status=404)

    mime, _ = mimetypes.guess_type(str(file_path))
    mime = mime or "application/octet-stream"

    try:
        async with aiofiles.open(file_path, "rb") as f:
            content = await f.read()
        return web.Response(
            body=content,
            content_type=mime,
            headers=CORS_HEADERS,
        )
    except Exception as e:
        print(f"[avatar] File read error: {e}")
        return web.Response(text="Internal error", status=500)


# ---------------------------------------------------------------------------
# Zeroconf mDNS advertisement
# ---------------------------------------------------------------------------

_zeroconf: Zeroconf | None = None
_service_info: ServiceInfo | None = None


def _register_mdns(port: int) -> None:
    """Register mDNS service so Home Assistant can auto-discover this server."""
    global _zeroconf, _service_info

    hostname = socket.gethostname()
    local_ip = socket.gethostbyname(hostname)

    _service_info = ServiceInfo(
        type_="_hermes-intercom._tcp.local.",
        name=f"Hermes Intercom ({hostname})._hermes-intercom._tcp.local.",
        addresses=[socket.inet_aton(local_ip)],
        port=port,
        properties={
            "path": "/devices",
            "version": "0.1.0",
        },
        server=f"{hostname}.local.",
    )
    _zeroconf = Zeroconf(ip_version=IPVersion.V4Only)
    _zeroconf.register_service(_service_info)
    print(f"  mDNS: registered _hermes-intercom._tcp.local. on {local_ip}:{port}")


def _unregister_mdns() -> None:
    """Unregister mDNS service on shutdown."""
    global _zeroconf, _service_info
    if _zeroconf and _service_info:
        _zeroconf.unregister_service(_service_info)
        _zeroconf.close()
        _zeroconf = None
        _service_info = None


# ---------------------------------------------------------------------------
# App setup
# ---------------------------------------------------------------------------

app = web.Application(middlewares=[auth_middleware])

# Token & health
app.router.add_get("/token", handle_token)
app.router.add_get("/health", handle_health)

# TTS proxy — GET (simple) + POST (TalkingHead / Google TTS format)
app.router.add_get("/tts", handle_tts)
app.router.add_post("/tts", handle_tts)
app.router.add_route("OPTIONS", "/tts", handle_options)

# Avatar static files
app.router.add_get("/avatar", handle_avatar)
app.router.add_get("/avatar/{tail:.*}", handle_avatar)

# Device registry
app.router.add_post("/register", handle_register)
app.router.add_post("/heartbeat", handle_heartbeat)
app.router.add_get("/devices", handle_devices)

# Call signaling
app.router.add_post("/signal", handle_signal)
app.router.add_get("/signals", handle_signals)
app.router.add_get("/calls", handle_calls)

# Device configuration
app.router.add_post("/configure", handle_configure)
app.router.add_get("/configure", handle_configure)

# CORS preflight
app.router.add_route("OPTIONS", "/register", handle_options)
app.router.add_route("OPTIONS", "/heartbeat", handle_options)
app.router.add_route("OPTIONS", "/signal", handle_options)
app.router.add_route("OPTIONS", "/configure", handle_options)

if __name__ == "__main__":
    port = int(os.environ.get("PORT", 8090))
    print(f"Token & registry server listening on :{port}")
    print(f"  LiveKit external URL: {LIVEKIT_EXTERNAL_URL}")
    print(f"  Stale timeout: {STALE_TIMEOUT}s")

    _register_mdns(port)
    try:
        web.run_app(app, host="0.0.0.0", port=port)
    finally:
        _unregister_mdns()
