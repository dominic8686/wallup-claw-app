"""Intercom tools for Hermes Agent.

Provides LLM-callable tools for tablet-to-tablet calling and
broadcasting TTS announcements via the LiveKit intercom system.

Requires:
- TOKEN_SERVER_URL env var (default: http://192.168.211.153:8090)
- aiohttp (already in messaging extras)
"""

import asyncio
import json
import logging
import os
from typing import Any, Dict, List, Optional

try:
    import aiohttp
    AIOHTTP_AVAILABLE = True
except ImportError:
    AIOHTTP_AVAILABLE = False

logger = logging.getLogger(__name__)

TOKEN_SERVER_URL = os.environ.get("TOKEN_SERVER_URL", "http://192.168.211.153:8090")


async def _post_json(url: str, data: dict) -> dict:
    """POST JSON to the token server and return the response."""
    async with aiohttp.ClientSession() as session:
        async with session.post(
            url,
            json=data,
            timeout=aiohttp.ClientTimeout(total=10),
        ) as resp:
            text = await resp.text()
            return json.loads(text)


async def _get_json(url: str) -> dict:
    """GET JSON from the token server."""
    async with aiohttp.ClientSession() as session:
        async with session.get(
            url,
            timeout=aiohttp.ClientTimeout(total=10),
        ) as resp:
            text = await resp.text()
            return json.loads(text)


# ---------------------------------------------------------------------------
# Tool: intercom_list_devices
# ---------------------------------------------------------------------------

INTERCOM_LIST_DEVICES_SCHEMA = {
    "name": "intercom_list_devices",
    "description": (
        "List all registered intercom tablets/devices with their current status. "
        "Shows which devices are online, offline, in a call, or in Do Not Disturb mode."
    ),
    "parameters": {
        "type": "object",
        "properties": {},
        "required": [],
    },
}


async def intercom_list_devices(**kwargs) -> str:
    """List all registered devices."""
    if not AIOHTTP_AVAILABLE:
        return "Error: aiohttp not installed"

    try:
        result = await _get_json(f"{TOKEN_SERVER_URL}/devices")
        devices = result.get("devices", [])

        if not devices:
            return "No intercom devices are registered."

        lines = ["**Registered Intercom Devices:**\n"]
        for d in devices:
            status_icon = {
                "online": "🟢",
                "offline": "⚫",
            }.get(d["status"], "❓")

            call_state = d.get("call_state", "idle")
            call_info = ""
            if call_state == "in_call":
                call_info = " (in a call)"
            elif call_state == "ringing":
                call_info = " (ringing)"
            elif call_state == "do_not_disturb":
                call_info = " (DND)"

            location = f" — {d['room_location']}" if d.get("room_location") else ""
            lines.append(
                f"- {status_icon} **{d['display_name']}** (`{d['device_id']}`){location} "
                f"— {d['status']}{call_info}"
            )

        return "\n".join(lines)

    except Exception as e:
        return f"Error listing devices: {e}"


# ---------------------------------------------------------------------------
# Tool: intercom_call
# ---------------------------------------------------------------------------

INTERCOM_CALL_SCHEMA = {
    "name": "intercom_call",
    "description": (
        "Initiate an intercom call from one tablet to another. "
        "The target tablet will ring and the user can accept or decline. "
        "Use intercom_list_devices first to see available devices."
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "target_device": {
                "type": "string",
                "description": "The device_id of the tablet to call (e.g., 'kitchen', 'bedroom').",
            },
            "source_device": {
                "type": "string",
                "description": (
                    "The device_id of the tablet initiating the call. "
                    "If omitted, defaults to 'hermes-agent' (the AI agent itself)."
                ),
            },
        },
        "required": ["target_device"],
    },
}


async def intercom_call(target_device: str, source_device: str = "hermes-agent", **kwargs) -> str:
    """Initiate an intercom call."""
    if not AIOHTTP_AVAILABLE:
        return "Error: aiohttp not installed"

    try:
        result = await _post_json(f"{TOKEN_SERVER_URL}/signal", {
            "type": "call_request",
            "from": source_device,
            "to": target_device,
        })

        if result.get("ok"):
            call_id = result.get("call_id", "unknown")
            return f"Calling {target_device}... (call ID: {call_id}). The tablet is now ringing."
        else:
            error = result.get("error", "Unknown error")
            return f"Could not call {target_device}: {error}"

    except Exception as e:
        return f"Error initiating call: {e}"


# ---------------------------------------------------------------------------
# Tool: intercom_hangup
# ---------------------------------------------------------------------------

INTERCOM_HANGUP_SCHEMA = {
    "name": "intercom_hangup",
    "description": "Hang up an active intercom call.",
    "parameters": {
        "type": "object",
        "properties": {
            "call_id": {
                "type": "string",
                "description": "The call ID to hang up. Use intercom_list_devices or check active calls.",
            },
            "source_device": {
                "type": "string",
                "description": "The device_id sending the hangup signal.",
            },
            "target_device": {
                "type": "string",
                "description": "The other device in the call.",
            },
        },
        "required": ["call_id", "source_device", "target_device"],
    },
}


async def intercom_hangup(call_id: str, source_device: str, target_device: str, **kwargs) -> str:
    """Hang up an active call."""
    if not AIOHTTP_AVAILABLE:
        return "Error: aiohttp not installed"

    try:
        result = await _post_json(f"{TOKEN_SERVER_URL}/signal", {
            "type": "call_hangup",
            "from": source_device,
            "to": target_device,
            "call_id": call_id,
        })

        if result.get("ok"):
            return f"Call {call_id} has been ended."
        else:
            return f"Could not hang up: {result.get('error', 'Unknown error')}"

    except Exception as e:
        return f"Error hanging up: {e}"


# ---------------------------------------------------------------------------
# Tool: intercom_announce
# ---------------------------------------------------------------------------

INTERCOM_ANNOUNCE_SCHEMA = {
    "name": "intercom_announce",
    "description": (
        "Send a text announcement to one or more tablets. The announcement will be "
        "converted to speech (TTS) and played on the target tablets' speakers. "
        "Use 'all' as target to announce to every registered device."
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "message": {
                "type": "string",
                "description": "The text message to announce via TTS.",
            },
            "targets": {
                "type": "string",
                "description": (
                    "Comma-separated device_ids to announce to (e.g., 'kitchen,bedroom'), "
                    "or 'all' for all devices."
                ),
            },
        },
        "required": ["message", "targets"],
    },
}


async def intercom_announce(message: str, targets: str = "all", **kwargs) -> str:
    """Send a TTS announcement to tablets."""
    if not AIOHTTP_AVAILABLE:
        return "Error: aiohttp not installed"

    try:
        # Get device list to resolve targets
        devices_result = await _get_json(f"{TOKEN_SERVER_URL}/devices")
        all_devices = devices_result.get("devices", [])

        if targets.lower() == "all":
            target_ids = [d["device_id"] for d in all_devices if d["status"] == "online"]
        else:
            target_ids = [t.strip() for t in targets.split(",") if t.strip()]

        if not target_ids:
            return "No online devices to announce to."

        # Send announcement signal to each target
        results = []
        for target in target_ids:
            try:
                await _post_json(f"{TOKEN_SERVER_URL}/signal", {
                    "type": "announcement",
                    "from": "hermes-agent",
                    "to": target,
                    "message": message,
                })
                results.append(f"✓ {target}")
            except Exception as e:
                results.append(f"✗ {target}: {e}")

        return f"Announcement sent to {len(target_ids)} device(s):\n" + "\n".join(results)

    except Exception as e:
        return f"Error sending announcement: {e}"


# ---------------------------------------------------------------------------
# Tool registry (for Hermes tool loading)
# ---------------------------------------------------------------------------

TOOLS = [
    (INTERCOM_LIST_DEVICES_SCHEMA, intercom_list_devices),
    (INTERCOM_CALL_SCHEMA, intercom_call),
    (INTERCOM_HANGUP_SCHEMA, intercom_hangup),
    (INTERCOM_ANNOUNCE_SCHEMA, intercom_announce),
]
