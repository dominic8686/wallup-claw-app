"""LiveKit Voice Agent with Hermes as the AI orchestrator.

Architecture mirrors Hermes' Discord voice implementation:
- LiveKit handles WebRTC audio transport (like discord.py handles Discord audio)
- Silero VAD detects speech boundaries
- OpenAI Whisper transcribes speech to text
- Hermes AIAgent processes text (tools, memory, skills)
- OpenAI TTS converts response to speech
- Audio published back to LiveKit room
"""

import asyncio
import io
import logging
import os
import sys
import tempfile
import wave
import numpy as np

logging.basicConfig(level=logging.INFO, stream=sys.stdout)
logger = logging.getLogger("hermes-livekit")

# Load Hermes .env file so HASS_TOKEN, HASS_URL etc. are available
try:
    from dotenv import load_dotenv
    load_dotenv(os.path.expanduser("~/.hermes/.env"))
    logger.info("Loaded ~/.hermes/.env")
except Exception:
    pass

from livekit import rtc, api
import json as _json

# ---------------------------------------------------------------------------
# Live transcript relay (sends to LiveKit room data channel)
# ---------------------------------------------------------------------------

_room_ref = None  # Set after room.connect()

async def relay_transcript(role: str, text: str):
    """Send transcript as a chat message visible in LiveKit's built-in chat UI."""
    if _room_ref is None:
        return
    try:
        prefix = "🎤 You: " if role == "user" else "🤖 Hermes: "
        msg = _json.dumps({
            "type": "chat_message",
            "message": prefix + text,
            "timestamp": int(asyncio.get_event_loop().time() * 1000),
        })
        await _room_ref.local_participant.publish_data(
            msg.encode("utf-8"),
            reliable=True,
            topic="lk-chat-topic",
        )
        logger.debug("Relayed chat: %s: %s", role, text[:50])
    except Exception as e:
        logger.debug("Chat relay failed: %s", e)

# ---------------------------------------------------------------------------
# Hermes AIAgent (the brain)
# ---------------------------------------------------------------------------

HERMES_AVAILABLE = False
try:
    sys.path.insert(0, "/opt/hermes")
    from run_agent import AIAgent
    HERMES_AVAILABLE = True
    logger.info("Hermes AIAgent loaded")
except ImportError as e:
    logger.warning("Hermes not available, using OpenAI fallback: %s", e)

# ---------------------------------------------------------------------------
# STT helper
# ---------------------------------------------------------------------------

async def transcribe_audio(wav_path: str) -> str:
    """Transcribe a WAV file using OpenAI Whisper."""
    from openai import OpenAI
    client = OpenAI()
    try:
        with open(wav_path, "rb") as f:
            result = client.audio.transcriptions.create(
                model="whisper-1",
                file=f,
                response_format="text",
            )
        return str(result).strip()
    finally:
        client.close()

# ---------------------------------------------------------------------------
# TTS helper
# ---------------------------------------------------------------------------

async def text_to_speech(text: str, output_path: str) -> bool:
    """Convert text to speech using OpenAI TTS. Returns True on success."""
    from openai import OpenAI
    client = OpenAI()
    try:
        response = client.audio.speech.create(
            model="tts-1",
            voice="alloy",
            input=text,
            response_format="pcm",  # Raw 24kHz 16-bit mono PCM
        )
        with open(output_path, "wb") as f:
            for chunk in response.iter_bytes():
                f.write(chunk)
        return True
    except Exception as e:
        logger.error("TTS failed: %s", e)
        return False
    finally:
        client.close()

# ---------------------------------------------------------------------------
# Hermes chat wrapper
# ---------------------------------------------------------------------------

class HermesChat:
    """Wraps Hermes AIAgent for multi-turn voice conversation."""

    def __init__(self):
        if HERMES_AVAILABLE:
            self._agent = AIAgent(
                model=os.environ.get("HERMES_MODEL", "gpt-4o-mini"),
                quiet_mode=True,
                enabled_toolsets=["web", "homeassistant", "memory", "terminal"],
            )
            self._history = []
            logger.info("HermesChat initialized with AIAgent")
        else:
            self._agent = None
            self._history = []
            logger.info("HermesChat initialized with OpenAI fallback")

    async def chat(self, user_message: str) -> str:
        """Send a message and get a response."""
        if self._agent:
            # Use Hermes AIAgent (has tools, memory, skills)
            result = await asyncio.to_thread(
                self._agent.run_conversation,
                user_message=user_message,
                conversation_history=self._history,
            )
            self._history = result.get("messages", [])
            return result.get("final_response", "I'm not sure how to respond.")
        else:
            # Fallback: direct OpenAI call
            from openai import OpenAI
            client = OpenAI()
            try:
                self._history.append({"role": "user", "content": user_message})
                resp = client.chat.completions.create(
                    model="gpt-4o-mini",
                    messages=[
                        {"role": "system", "content": "You are Hermes, a helpful voice assistant. Keep responses to 1-2 sentences."},
                        *self._history[-20:],  # Last 20 messages
                    ],
                )
                reply = resp.choices[0].message.content
                self._history.append({"role": "assistant", "content": reply})
                return reply
            finally:
                client.close()

# ---------------------------------------------------------------------------
# VAD (Voice Activity Detection) using Silero
# ---------------------------------------------------------------------------

class SimpleVAD:
    """Simple VAD using Silero ONNX model."""

    SAMPLE_RATE = 16000
    CHUNK_SAMPLES = 512  # 32ms at 16kHz
    SPEECH_THRESHOLD = 0.5
    SILENCE_DURATION = 1.5  # seconds of silence to end utterance
    MIN_SPEECH_DURATION = 0.3  # minimum speech to process

    def __init__(self):
        import onnxruntime
        # Download silero VAD model
        model_path = os.path.join(tempfile.gettempdir(), "silero_vad.onnx")
        if not os.path.exists(model_path):
            import urllib.request
            url = "https://huggingface.co/onnx-community/silero-vad/resolve/main/onnx/model.onnx"
            urllib.request.urlretrieve(url, model_path)
            logger.info("Downloaded Silero VAD model")

        self._session = onnxruntime.InferenceSession(model_path)
        # Silero VAD v5 uses 'state' (combined h+c), v4 uses separate h/c
        input_names = [i.name for i in self._session.get_inputs()]
        self._use_state = 'state' in input_names
        if self._use_state:
            self._state = np.zeros((2, 1, 128), dtype=np.float32)
        else:
            self._h = np.zeros((2, 1, 64), dtype=np.float32)
            self._c = np.zeros((2, 1, 64), dtype=np.float32)
        logger.info("VAD model inputs: %s (v5=%s)", input_names, self._use_state)
        self._is_speaking = False
        self._speech_buffer = bytearray()
        self._silence_start = None
        self._speech_start = None
        logger.info("SimpleVAD initialized")

    def process(self, pcm_16k: bytes) -> bytes | None:
        """Feed 16kHz 16-bit mono PCM. Returns complete utterance PCM or None."""
        import time
        new_samples = np.frombuffer(pcm_16k, dtype=np.int16).astype(np.float32) / 32768.0
        if not hasattr(self, '_input_buffer'):
            self._input_buffer = np.array([], dtype=np.float32)
        self._input_buffer = np.concatenate([self._input_buffer, new_samples])

        while len(self._input_buffer) >= self.CHUNK_SAMPLES:
            chunk = self._input_buffer[:self.CHUNK_SAMPLES]
            self._input_buffer = self._input_buffer[self.CHUNK_SAMPLES:]

            input_data = chunk.reshape(1, -1)
            if self._use_state:
                ort_inputs = {
                    "input": input_data,
                    "state": self._state,
                    "sr": np.array([self.SAMPLE_RATE], dtype=np.int64),
                }
                output, self._state = self._session.run(None, ort_inputs)
            else:
                ort_inputs = {
                    "input": input_data,
                    "h": self._h,
                    "c": self._c,
                    "sr": np.array([self.SAMPLE_RATE], dtype=np.int64),
                }
                output, self._h, self._c = self._session.run(None, ort_inputs)
            speech_prob = output[0][0]
            now = time.monotonic()

            if speech_prob >= self.SPEECH_THRESHOLD:
                if not self._is_speaking:
                    self._is_speaking = True
                    self._speech_start = now
                    self._speech_buffer = bytearray()
                    logger.info("Speech detected (prob=%.2f)", speech_prob)
                self._silence_start = None
                chunk_pcm = (chunk * 32768).astype(np.int16).tobytes()
                self._speech_buffer.extend(chunk_pcm)

            elif self._is_speaking:
                chunk_pcm = (chunk * 32768).astype(np.int16).tobytes()
                self._speech_buffer.extend(chunk_pcm)
                if self._silence_start is None:
                    self._silence_start = now
                elif now - self._silence_start >= self.SILENCE_DURATION:
                    duration = now - (self._speech_start or now)
                    self._is_speaking = False
                    self._silence_start = None
                    if duration >= self.MIN_SPEECH_DURATION:
                        logger.info("Utterance complete: %.1fs", duration)
                        result = bytes(self._speech_buffer)
                        self._speech_buffer = bytearray()
                        return result
                    else:
                        self._speech_buffer = bytearray()

        return None

# ---------------------------------------------------------------------------
# Audio helpers
# ---------------------------------------------------------------------------

def pcm_to_wav(pcm_data: bytes, sample_rate: int = 16000) -> str:
    """Write PCM to a temporary WAV file, return path."""
    tmp = tempfile.NamedTemporaryFile(suffix=".wav", delete=False)
    with wave.open(tmp, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(pcm_data)
    return tmp.name

def resample_48k_to_16k_mono(data: bytes, channels: int = 1) -> bytes:
    """Resample 48kHz audio to 16kHz mono."""
    samples = np.frombuffer(data, dtype=np.int16)
    if channels > 1:
        samples = samples[::channels]  # Take first channel
    # Simple 3:1 decimation (48000 / 16000 = 3)
    samples = samples[::3]
    return samples.tobytes()

# ---------------------------------------------------------------------------
# Home Assistant Real-Time Events
# ---------------------------------------------------------------------------

HA_WATCH_DOMAINS = os.environ.get("HA_WATCH_DOMAINS", "light,switch,climate,binary_sensor,alarm_control_panel,cover,fan").split(",")
HA_COOLDOWN = int(os.environ.get("HA_COOLDOWN_SECONDS", "10"))

def format_ha_event(entity_id: str, old_state: str, new_state: str, attrs: dict) -> str:
    """Format a state change into a human-readable message."""
    domain = entity_id.split(".")[0]
    friendly = attrs.get("friendly_name", entity_id)

    if domain == "climate":
        current = attrs.get("current_temperature", "?")
        target = attrs.get("temperature", "?")
        return f"🌡️ {friendly}: HVAC changed from '{old_state}' to '{new_state}' (current: {current}°, target: {target}°)"
    elif domain == "binary_sensor":
        action = "triggered" if new_state == "on" else "cleared"
        return f"🔔 {friendly}: {action}"
    elif domain in ("light", "switch", "fan"):
        action = "turned on" if new_state == "on" else "turned off"
        icon = {"light": "💡", "switch": "🔌", "fan": "🌀"}.get(domain, "⚡")
        return f"{icon} {friendly}: {action}"
    elif domain == "alarm_control_panel":
        return f"🚨 {friendly}: alarm changed from '{old_state}' to '{new_state}'"
    elif domain == "cover":
        action = "opened" if new_state == "open" else "closed" if new_state == "closed" else new_state
        return f"🪟 {friendly}: {action}"
    elif domain == "sensor":
        unit = attrs.get("unit_of_measurement", "")
        return f"📊 {friendly}: changed from {old_state}{unit} to {new_state}{unit}"
    else:
        return f"⚡ {friendly}: changed from '{old_state}' to '{new_state}'"


async def ha_event_listener():
    """Connect to Home Assistant WebSocket and relay state changes to LiveKit chat."""
    import aiohttp

    hass_url = os.environ.get("HASS_URL", "")
    hass_token = os.environ.get("HASS_TOKEN", "")
    if not hass_url or not hass_token:
        logger.info("HA events: HASS_URL or HASS_TOKEN not set, skipping")
        return

    ws_url = hass_url.replace("http://", "ws://").replace("https://", "wss://") + "/api/websocket"
    cooldowns = {}  # entity_id -> last_event_time
    msg_id = 1

    while True:
        try:
            logger.info("HA events: connecting to %s", ws_url)
            async with aiohttp.ClientSession() as session:
                async with session.ws_connect(ws_url, heartbeat=30) as ws:
                    # Wait for auth_required
                    auth_req = await ws.receive_json()
                    if auth_req.get("type") != "auth_required":
                        logger.warning("HA events: unexpected message: %s", auth_req)
                        continue

                    # Authenticate
                    await ws.send_json({"type": "auth", "access_token": hass_token})
                    auth_resp = await ws.receive_json()
                    if auth_resp.get("type") != "auth_ok":
                        logger.error("HA events: auth failed: %s", auth_resp)
                        await asyncio.sleep(30)
                        continue

                    logger.info("HA events: authenticated, subscribing to state_changed")

                    # Subscribe to state_changed events
                    await ws.send_json({
                        "id": msg_id,
                        "type": "subscribe_events",
                        "event_type": "state_changed",
                    })
                    msg_id += 1
                    sub_resp = await ws.receive_json()
                    if not sub_resp.get("success"):
                        logger.error("HA events: subscribe failed: %s", sub_resp)
                        continue

                    logger.info("HA events: listening for state changes (domains: %s)", HA_WATCH_DOMAINS)
                    await relay_transcript("system", "🏠 Connected to Home Assistant — monitoring device changes")

                    # Listen for events
                    async for msg in ws:
                        if msg.type == aiohttp.WSMsgType.TEXT:
                            data = _json.loads(msg.data)
                            if data.get("type") != "event":
                                continue

                            event = data.get("event", {})
                            event_data = event.get("data", {})
                            entity_id = event_data.get("entity_id", "")
                            domain = entity_id.split(".")[0] if entity_id else ""

                            # Filter by watched domains
                            if domain not in HA_WATCH_DOMAINS:
                                continue

                            old = event_data.get("old_state", {}) or {}
                            new = event_data.get("new_state", {}) or {}
                            old_state = old.get("state", "unknown")
                            new_state = new.get("state", "unknown")

                            # Skip if state didn't actually change
                            if old_state == new_state:
                                continue

                            # Cooldown per entity
                            import time
                            now = time.monotonic()
                            last = cooldowns.get(entity_id, 0)
                            if now - last < HA_COOLDOWN:
                                continue
                            cooldowns[entity_id] = now

                            attrs = new.get("attributes", {})
                            formatted = format_ha_event(entity_id, old_state, new_state, attrs)
                            logger.info("HA event: %s", formatted)
                            await relay_transcript("system", formatted)

                        elif msg.type in (aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.ERROR):
                            logger.warning("HA events: WebSocket closed/error")
                            break

        except Exception as e:
            logger.warning("HA events: connection error: %s, reconnecting in 10s", e)

        await asyncio.sleep(10)  # Reconnect backoff


# ---------------------------------------------------------------------------
# Main: LiveKit room participant
# ---------------------------------------------------------------------------

async def main():
    url = os.environ["LIVEKIT_URL"]
    api_key = os.environ["LIVEKIT_API_KEY"]
    api_secret = os.environ["LIVEKIT_API_SECRET"]
    room_name = os.environ.get("LIVEKIT_ROOM", "voice-room")

    # Generate a token for the agent
    token = (
        api.AccessToken(api_key, api_secret)
        .with_identity("hermes-agent")
        .with_grants(api.VideoGrants(
            room_join=True,
            room=room_name,
            can_publish=True,
            can_subscribe=True,
        ))
        .to_jwt()
    )

    # Connect to room
    room = rtc.Room()
    hermes = HermesChat()
    vad = SimpleVAD()
    processing = False  # Prevent overlapping processing
    agent_active = False  # IDLE by default, ACTIVE when wake word detected
    last_speech_time = [0.0]  # Mutable ref for silence timeout tracking
    SILENCE_TIMEOUT = 30  # seconds of no speech before going back to IDLE

    logger.info("Connecting to LiveKit at %s, room=%s", url, room_name)
    await room.connect(url, token)
    global _room_ref
    _room_ref = room
    logger.info("Connected to room: %s", room_name)

    # Activate conversation (called when wake_word_detected received)
    async def activate():
        nonlocal processing, agent_active
        import time as _time
        agent_active = True
        last_speech_time[0] = _time.monotonic()
        processing = True
        try:
            logger.info("Agent ACTIVE - starting conversation")
            await relay_transcript("system", "🌟 Conversation started")
            greeting = await hermes.chat("The user just said the wake word Hey Jarvis. Greet them warmly and briefly.")
            logger.info("Greeting: %s", greeting)
            await speak(greeting)
        finally:
            processing = False

    async def deactivate():
        nonlocal agent_active
        agent_active = False
        logger.info("Agent IDLE - conversation ended")
        await relay_transcript("system", "💤 Conversation ended, listening for wake word...")
        # Signal tablet to return to wake word mode
        try:
            msg = _json.dumps({"type": "conversation_ended"})
            await room.local_participant.publish_data(msg.encode(), reliable=True, topic="hermes-control")
        except Exception:
            pass

    # Silence timeout checker
    async def silence_timeout_checker():
        import time as _time
        while True:
            await asyncio.sleep(5)
            if agent_active and not processing:
                elapsed = _time.monotonic() - last_speech_time[0]
                if elapsed > SILENCE_TIMEOUT:
                    logger.info("Silence timeout (%.0fs), deactivating", elapsed)
                    await deactivate()

    # Persistent audio source and track for TTS playback
    tts_source = rtc.AudioSource(24000, 1)
    tts_track = rtc.LocalAudioTrack.create_audio_track("hermes-tts", tts_source)
    await room.local_participant.publish_track(tts_track)
    logger.info("Published TTS audio track")

    async def speak(text: str):
        """Convert text to speech and send through persistent audio track."""
        tmp = tempfile.NamedTemporaryFile(suffix=".pcm", delete=False)
        tmp.close()
        try:
            success = await text_to_speech(text, tmp.name)
            if not success:
                return

            with open(tmp.name, "rb") as f:
                pcm_data = f.read()

            if not pcm_data:
                return

            # Send audio in frames with proper pacing
            samples_per_frame = 480  # 20ms at 24kHz
            frame_duration = samples_per_frame / 24000.0  # seconds per frame
            samples = np.frombuffer(pcm_data, dtype=np.int16)

            for i in range(0, len(samples), samples_per_frame):
                frame_data = samples[i:i + samples_per_frame]
                if len(frame_data) < samples_per_frame:
                    frame_data = np.pad(frame_data, (0, samples_per_frame - len(frame_data)))

                frame = rtc.AudioFrame(
                    data=frame_data.tobytes(),
                    sample_rate=24000,
                    num_channels=1,
                    samples_per_channel=samples_per_frame,
                )
                await tts_source.capture_frame(frame)
                await asyncio.sleep(frame_duration * 0.8)  # Slight underrun to keep buffer fed

        finally:
            try:
                os.unlink(tmp.name)
            except OSError:
                pass

    # Handle incoming audio from the user
    audio_buffer = bytearray()

    @room.on("track_subscribed")
    def on_track_subscribed(track: rtc.Track, publication: rtc.RemoteTrackPublication, participant: rtc.RemoteParticipant):
        if track.kind == rtc.TrackKind.KIND_AUDIO:
            logger.info("Subscribed to audio from %s", participant.identity)
            audio_stream = rtc.AudioStream(track)

            frame_count = 0

            async def process_audio():
                nonlocal processing, frame_count
                async for event in audio_stream:
                    if processing or not agent_active:
                        continue

                    frame = event.frame
                    frame_count += 1
                    if frame_count <= 3 or frame_count % 500 == 0:
                        audio_bytes = frame.data.tobytes()
                        rms = np.sqrt(np.mean(np.frombuffer(audio_bytes[:min(len(audio_bytes), 1000)], dtype=np.int16).astype(np.float32) ** 2)) if len(audio_bytes) > 0 else 0
                        logger.info("Audio frame #%d: sr=%d ch=%d samples=%d bytes=%d rms=%.0f",
                                    frame_count, frame.sample_rate, frame.num_channels,
                                    frame.samples_per_channel, len(audio_bytes), rms)

                    # Resample from 48kHz to 16kHz for VAD/STT
                    pcm_16k = resample_48k_to_16k_mono(
                        frame.data.tobytes(),
                        channels=frame.num_channels,
                    )

                    # Feed to VAD
                    utterance = vad.process(pcm_16k)
                    if utterance:
                        processing = True
                        asyncio.ensure_future(handle_utterance(utterance, hermes))

            asyncio.ensure_future(process_audio())

    async def handle_utterance(pcm_data: bytes, hermes: HermesChat):
        nonlocal processing
        try:
            # PCM -> WAV -> STT
            wav_path = pcm_to_wav(pcm_data, sample_rate=16000)
            try:
                transcript = await asyncio.to_thread(
                    lambda: asyncio.run(transcribe_audio(wav_path))
                )
            finally:
                os.unlink(wav_path)

            if not transcript:
                return

            import time as _time
            last_speech_time[0] = _time.monotonic()
            logger.info("User said: %s", transcript)
            await relay_transcript("user", transcript)

            # Hermes processes the transcript
            response = await hermes.chat(transcript)
            logger.info("Hermes response: %s", response[:100])
            await relay_transcript("assistant", response)

            # TTS and play back
            await speak(response)

        except Exception as e:
            logger.error("Error processing utterance: %s", e, exc_info=True)
        finally:
            processing = False

    # Listen for data messages from tablet (wake word signal)
    @room.on("data_received")
    def on_data(data: rtc.DataPacket):
        try:
            msg = _json.loads(data.data.decode())
            if msg.get("type") == "wake_word_detected":
                score = msg.get("score", 0)
                logger.info("Received wake_word_detected (score=%s)", score)
                if not agent_active:
                    asyncio.ensure_future(activate())
        except Exception as e:
            logger.debug("Data parse error: %s", e)

    @room.on("participant_connected")
    def on_participant(participant: rtc.RemoteParticipant):
        logger.info("Participant joined: %s", participant.identity)

    logger.info("Agent ready (IDLE mode), waiting for wake word signal...")

    # Start background tasks
    asyncio.ensure_future(ha_event_listener())
    asyncio.ensure_future(silence_timeout_checker())

    # Keep running
    try:
        while True:
            await asyncio.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        await room.disconnect()


if __name__ == "__main__":
    asyncio.run(main())
