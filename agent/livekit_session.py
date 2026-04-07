"""LiveKit AgentSession mode — official SDK integration.

Uses the LiveKit Agents framework (AgentSession) instead of the custom
VAD → STT → LLM → TTS pipeline. Gets built-in interruptions, turn detection,
endpointing, and support for speech-to-speech realtime models.

Controlled by env vars:
  LIVEKIT_LLM      gemini-live | openai-realtime | gemini-2.5-flash | openai/gpt-4o-mini | ...
  LIVEKIT_STT      deepgram/nova-3 | google/chirp   (pipeline mode only)
  LIVEKIT_TTS      cartesia/sonic-3 | openai/tts-1   (pipeline mode only)
  LIVEKIT_VOICE    Puck | alloy | ...               (realtime models only)
  GOOGLE_API_KEY   Required for Gemini models
"""

import asyncio
import logging
import os

import aiohttp
from dotenv import load_dotenv

from livekit import agents, rtc, api
from livekit.agents import (
    Agent,
    AgentSession,
    AgentServer,
    TurnHandlingOptions,
    llm,
)
from livekit.agents.voice import room_io
from livekit.plugins import silero

load_dotenv(os.path.expanduser("~/.hermes/.env"))

logger = logging.getLogger("hermes-livekit")

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

LIVEKIT_LLM = os.environ.get("LIVEKIT_LLM", "gemini-live")
LIVEKIT_STT = os.environ.get("LIVEKIT_STT", "deepgram/nova-3")
LIVEKIT_TTS = os.environ.get("LIVEKIT_TTS", "cartesia/sonic-3")
LIVEKIT_VOICE = os.environ.get("LIVEKIT_VOICE", "Puck")
LIVEKIT_INSTRUCTIONS = os.environ.get("LIVEKIT_INSTRUCTIONS", (
    "You are a helpful voice assistant on a wall-mounted tablet. "
    "Keep responses concise — 1 to 3 sentences — since they will be spoken aloud. "
    "Be friendly and conversational.\n\n"
    "YOU HAVE TWO TOOL SYSTEMS AVAILABLE. USE THEM.\n\n"
    "1. Home Assistant MCP tools (ha-mcp) — for smart home control:\n"
    "   - Use ha_search_entities to find entity IDs first (e.g. query='office light', domain_filter='light').\n"
    "   - Use ha_call_service to control devices. Examples:\n"
    "     ha_call_service(domain='light', service='turn_off', entity_id='light.office_light')\n"
    "     ha_call_service(domain='switch', service='turn_on', entity_id='switch.coffee_maker')\n"
    "     ha_call_service(domain='climate', service='set_temperature', entity_id='climate.living_room', data={'temperature': 22})\n"
    "   - If you don't know the entity_id, search first with ha_search_entities.\n"
    "   - Use ha_get_state to check current state of an entity.\n\n"
    "2. ask_hermes tool — ALWAYS use this for anything that is NOT simple smart home control:\n"
    "   - Calendar queries, schedules, appointments\n"
    "   - Web searches, information lookup\n"
    "   - Memory recall (past conversations)\n"
    "   - Creating HA automations or scripts\n"
    "   - Any question requiring external knowledge or tools\n"
    "   - When the user says 'ask Hermes' or 'delegate to Hermes'\n"
    "   Pass the user's full request as the 'request' parameter.\n\n"
    "HONESTY RULES — NEVER LIE ABOUT TOOL RESULTS:\n"
    "- If a tool call returns an error, tell the user it failed and what went wrong.\n"
    "- Do NOT say 'Done' or 'I turned off the light' if the tool call failed.\n"
    "- If you get a ToolError or error response, say something like: "
    "'Sorry, I wasn't able to do that — [brief reason].'\n"
    "- Only confirm success if the tool returned a successful result with no errors.\n\n"
    "If you are unsure whether to answer directly or use a tool, USE ask_hermes."
))

# ha-mcp server URL — the HA MCP server endpoint (92 tools)
# Can be:
#   - HA add-on: http://<ha-ip>:9583/private_<secret>
#   - Docker: http://<host>:8090  (if running ha-mcp container)
#   - HA built-in: http://<ha-ip>:8123/api/mcp (with token)
HA_MCP_URL = os.environ.get("HA_MCP_URL", "")
HA_MCP_TOKEN = os.environ.get("HA_MCP_TOKEN", os.environ.get("HASS_TOKEN", ""))

TOKEN_SERVER_URL = os.environ.get("TOKEN_SERVER_URL", "http://localhost:8090")
DEVICE_POLL_INTERVAL = int(os.environ.get("DEVICE_POLL_INTERVAL", "15"))
INTERCOM_API_KEY = os.environ.get("INTERCOM_API_KEY", "")


# ---------------------------------------------------------------------------
# Hermes delegation tool — sends complex requests to the full Hermes brain
# ---------------------------------------------------------------------------

HERMES_API_URL = os.environ.get("HERMES_API_URL", "http://localhost:8642/v1")


@llm.function_tool
async def ask_hermes(request: str) -> str:
    """Delegate a complex request to Hermes, the AI assistant with full tool access.

    Use this for tasks that require tools or external services:
    - Calendar queries (MS365, Google Calendar)
    - Web searches and information lookup
    - Memory recall ("what did we discuss yesterday?")
    - Creating or editing HA automations and scripts
    - Any multi-step task requiring reasoning with tools

    For simple conversational responses, answer directly without using this tool.
    For smart home control (lights, switches, climate), prefer the Home Assistant
    MCP tools over this tool.

    Args:
        request: The user's request in natural language, exactly as they said it.
    """
    try:
        async with aiohttp.ClientSession() as session:
            async with session.post(
                f"{HERMES_API_URL}/chat/completions",
                json={
                    "model": "hermes",
                    "messages": [{"role": "user", "content": request}],
                },
                timeout=aiohttp.ClientTimeout(total=60),
            ) as resp:
                if resp.status != 200:
                    body = await resp.text()
                    return f"Hermes error {resp.status}: {body[:200]}"
                data = await resp.json()
                return data["choices"][0]["message"]["content"]
    except Exception as e:
        logger.error("ask_hermes failed: %s", e)
        return f"Failed to reach Hermes: {e}"


# ---------------------------------------------------------------------------
# Home Assistant via ha-mcp
# ---------------------------------------------------------------------------

def _build_mcp_servers() -> list:
    """Build MCP server list for the LiveKit Agent."""
    from livekit.agents import mcp as lk_mcp

    servers = []

    if HA_MCP_URL:
        logger.info("HA MCP: connecting to %s", HA_MCP_URL)
        # ha-mcp supports Streamable HTTP transport
        headers = {}
        if HA_MCP_TOKEN:
            headers["Authorization"] = f"Bearer {HA_MCP_TOKEN}"
        servers.append(
            lk_mcp.MCPServerHTTP(
                url=HA_MCP_URL,
                headers=headers,
            )
        )
    else:
        logger.warning("HA_MCP_URL not set — no Home Assistant tools available. "
                       "Set HA_MCP_URL to your ha-mcp server endpoint.")

    return servers or None


# ---------------------------------------------------------------------------
# Build LLM / session based on LIVEKIT_LLM
# ---------------------------------------------------------------------------

def _build_llm():
    """Build the LLM instance based on LIVEKIT_LLM env var."""
    model = LIVEKIT_LLM.lower().strip()

    # --- Realtime / speech-to-speech models ---
    if model in ("gemini-live", "gemini-realtime"):
        from livekit.plugins import google
        return google.realtime.RealtimeModel(
            voice=LIVEKIT_VOICE,
            temperature=0.8,
            instructions=LIVEKIT_INSTRUCTIONS,
        )

    if model.startswith("gemini-") and "live" in model:
        # e.g. gemini-3.1-flash-live-preview
        from livekit.plugins import google
        return google.realtime.RealtimeModel(
            model=model,
            voice=LIVEKIT_VOICE,
            temperature=0.8,
            instructions=LIVEKIT_INSTRUCTIONS,
        )

    if model in ("openai-realtime", "gpt-4o-realtime"):
        from livekit.plugins import openai
        return openai.realtime.RealtimeModel(
            voice=LIVEKIT_VOICE,
            temperature=0.8,
        )

    # --- Pipeline LLM models (need separate STT + TTS) ---
    if model.startswith("gemini"):
        from livekit.plugins import google
        return google.LLM(model=model)

    if model.startswith("openai/") or model.startswith("gpt-"):
        from livekit.plugins import openai
        clean_model = model.replace("openai/", "")
        return openai.LLM(model=clean_model)

    # Fallback: try as a LiveKit Inference model string
    from livekit.agents import inference
    return inference.LLM(model=model)


def _is_realtime(llm_instance) -> bool:
    """Check if the LLM is a realtime (speech-to-speech) model."""
    type_name = type(llm_instance).__name__
    return "Realtime" in type_name or "realtime" in type_name


def _build_session(llm_instance) -> AgentSession:
    """Build an AgentSession with the right components for the LLM type."""
    if _is_realtime(llm_instance):
        # Realtime models have server-side VAD, STT, and TTS.
        # Local silero VAD is still needed for the AgentSession's
        # turn handling and interruption logic.
        vad = silero.VAD.load()
        return AgentSession(
            llm=llm_instance,
            vad=vad,
        )
    else:
        # Pipeline mode: need separate STT + TTS + VAD
        from livekit.plugins.turn_detector.multilingual import MultilingualModel
        vad = silero.VAD.load()
        return AgentSession(
            stt=LIVEKIT_STT,
            llm=llm_instance,
            tts=LIVEKIT_TTS,
            vad=vad,
            turn_handling=TurnHandlingOptions(
                turn_detection=MultilingualModel(),
            ),
        )


# ---------------------------------------------------------------------------
# Agent class
# ---------------------------------------------------------------------------

class LiveKitAgent(Agent):
    """Voice agent with HA MCP tools + Hermes brain for complex tasks."""

    def __init__(self):
        mcp_servers = _build_mcp_servers()
        super().__init__(
            instructions=LIVEKIT_INSTRUCTIONS,
            tools=[ask_hermes],
            mcp_servers=mcp_servers,
        )


# ---------------------------------------------------------------------------
# Per-tablet session management
# ---------------------------------------------------------------------------

async def discover_devices() -> list[str]:
    """Fetch registered device IDs from the token server."""
    headers = {}
    if INTERCOM_API_KEY:
        headers["Authorization"] = f"Bearer {INTERCOM_API_KEY}"
    try:
        async with aiohttp.ClientSession() as session:
            async with session.get(
                f"{TOKEN_SERVER_URL}/devices",
                headers=headers,
                timeout=aiohttp.ClientTimeout(total=5),
            ) as resp:
                if resp.status == 200:
                    data = await resp.json()
                    return [d["device_id"] for d in data.get("devices", []) if d.get("status") == "online"]
    except Exception as e:
        logger.warning("Device discovery failed: %s", e)
    return []


async def _run_tablet_session(
    device_id: str,
    url: str,
    api_key: str,
    api_secret: str,
):
    """Run a LiveKit AgentSession for a single tablet."""
    room_name = f"voice-room-{device_id}"

    # Create a room and connect
    room = rtc.Room()
    token = (
        api.AccessToken(api_key, api_secret)
        .with_identity(f"livekit-agent-{device_id}")
        .with_grants(api.VideoGrants(
            room_join=True,
            room=room_name,
            can_publish=True,
            can_subscribe=True,
            can_update_own_metadata=True,
            agent=True,
        ))
        .to_jwt()
    )

    logger.info("[%s] Connecting to room %s (livekit mode)", device_id, room_name)
    await room.connect(url, token)
    logger.info("[%s] Connected to room %s", device_id, room_name)

    # Log who's already in the room (helps debug participant linking)
    remote = room.remote_participants
    logger.info("[%s] Remote participants in room: %s",
                device_id, {p.identity: p.kind for p in remote.values()} if remote else "(none)")

    # Build a FRESH LLM instance per session (realtime models are stateful)
    llm_instance = _build_llm()
    session = _build_session(llm_instance)
    agent = LiveKitAgent()

    # Enable debug logging for livekit.agents
    import logging as _logging
    _logging.getLogger("livekit.agents").setLevel(_logging.DEBUG)
    _logging.getLogger("livekit.plugins").setLevel(_logging.DEBUG)

    # Wire up session event listeners for diagnostics
    @session.on("agent_state_changed")
    def _on_agent_state(ev):
        logger.info("[%s] Agent state: %s", device_id, ev)

    @session.on("user_state_changed")
    def _on_user_state(ev):
        logger.info("[%s] User state: %s", device_id, ev)

    @session.on("user_input_transcribed")
    def _on_user_input(ev):
        logger.info("[%s] User said: '%s' (final=%s)", device_id, ev.transcript, ev.is_final)

    @session.on("close")
    def _on_close(ev):
        logger.info("[%s] Session closed: %s", device_id, ev)

    # Start the session — link to the tablet participant and keep session alive
    # across tablet reconnects (close_on_disconnect=False prevents the session
    # from closing when the tablet briefly disconnects to re-join).
    logger.info("[%s] Starting AgentSession...", device_id)
    await session.start(
        room=room,
        agent=agent,
        room_options=room_io.RoomOptions(
            participant_identity=device_id,
            close_on_disconnect=False,
        ),
    )
    logger.info("[%s] AgentSession started (model=%s), waiting for user to speak...", device_id, LIVEKIT_LLM)

    # Listen for session close event
    close_event = asyncio.Event()
    session.on("close")(lambda *_: close_event.set())

    # Also listen for room disconnect as a fallback
    room.on("disconnected")(lambda *_: close_event.set())

    await close_event.wait()
    logger.info("[%s] Session/room closed", device_id)


# ---------------------------------------------------------------------------
# Main entry point
# ---------------------------------------------------------------------------

async def run():
    """Main loop for livekit mode — discovers tablets and runs AgentSessions."""
    url = os.environ["LIVEKIT_URL"]
    api_key = os.environ["LIVEKIT_API_KEY"]
    api_secret = os.environ["LIVEKIT_API_SECRET"]

    logger.info("LiveKit AgentSession mode starting (model=%s, voice=%s)", LIVEKIT_LLM, LIVEKIT_VOICE)

    logger.info("LLM model: %s", LIVEKIT_LLM)

    # Track active sessions: device_id -> (task, start_time)
    sessions: dict[str, tuple[asyncio.Task, float]] = {}
    import time as _time

    # Device discovery loop
    while True:
        try:
            devices = await discover_devices()
            for device_id in devices:
                existing = sessions.get(device_id)
                if existing is None or existing[0].done():
                    # Add a cooldown: don't restart a session that finished less
                    # than 10 seconds ago (prevents tight reconnect loops).
                    if existing and existing[0].done():
                        elapsed = _time.monotonic() - existing[1]
                        if elapsed < 10:
                            continue
                        # Log why the previous session ended
                        exc = existing[0].exception() if not existing[0].cancelled() else None
                        if exc:
                            logger.error("Session %s failed: %s", device_id, exc)
                        else:
                            logger.info("Session %s finished, restarting", device_id)

                    logger.info("New device discovered: %s, starting AgentSession", device_id)
                    task = asyncio.create_task(
                        _run_tablet_session(device_id, url, api_key, api_secret)
                    )
                    sessions[device_id] = (task, _time.monotonic())

            # Clean up sessions for devices that went offline
            offline = [did for did in sessions if did not in devices and sessions[did][0].done()]
            for did in offline:
                del sessions[did]

        except Exception as e:
            logger.error("Session manager error: %s", e)

        await asyncio.sleep(DEVICE_POLL_INTERVAL)
