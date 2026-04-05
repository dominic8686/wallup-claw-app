"""LiveKit Voice Agent — selectable AI backend.

Controlled by AGENT_MODE environment variable:
  hermes      (default) Full Hermes AIAgent with tools, memory, HA integration, MCP.
  openrouter  Lightweight direct OpenRouter call. Model set via AGENT_MODEL env var.

Audio pipeline (both modes):
  LiveKit WebRTC -> Silero VAD -> OpenRouter STT (Gemini) -> backend -> edge-tts -> LiveKit

Key env vars:
  AGENT_MODE        hermes | openrouter (default: hermes)
  AVATAR_ENABLED    true | false  — send text via data channel instead of TTS (default: false)
  STT_MODEL         OpenRouter model for transcription (default: google/gemini-3.1-flash-lite-preview)
  TTS_VOICE         edge-tts voice (default: en-GB-SoniaNeural)
"""

import asyncio
import base64
import io
import logging
import os
import sys
import tempfile
import wave
import numpy as np
from PIL import Image

logging.basicConfig(level=logging.INFO, stream=sys.stdout)
logger = logging.getLogger("hermes-livekit")

# Load Hermes config (.env with all API keys) from the mounted ~/.hermes volume.
# load_dotenv only sets vars that are not already in the environment.
try:
    from dotenv import load_dotenv
    load_dotenv(os.path.expanduser("~/.hermes/.env"))
    logger.info("Loaded ~/.hermes/.env")
except Exception:
    pass

# ---------------------------------------------------------------------------
# Agent mode — read once at startup, fail fast on bad values
# ---------------------------------------------------------------------------

AGENT_MODE = os.environ.get("AGENT_MODE", "hermes").strip().lower()
if AGENT_MODE not in ("hermes", "openrouter"):
    raise SystemExit(f"Invalid AGENT_MODE={AGENT_MODE!r}. Must be 'hermes' or 'openrouter'.")

# When AVATAR_ENABLED=true the agent sends text via data channel instead of
# producing TTS audio itself. The TalkingHead.js avatar page (served by the
# token server) receives the text and speaks it with its own TTS + lip sync.
AVATAR_ENABLED = os.environ.get("AVATAR_ENABLED", "false").strip().lower() == "true"

logger.info("Agent mode: %s | avatar: %s", AGENT_MODE, AVATAR_ENABLED)

from livekit import rtc, api
import json as _json
from aiohttp import web

# ---------------------------------------------------------------------------
# Vision AI — video frame buffer + multimodal handler
# ---------------------------------------------------------------------------

# Per-device frame buffers: device_id -> JPEG bytes
_device_frames: dict[str, bytes] = {}

VISION_MODEL = os.environ.get("VISION_MODEL", "gpt-4o")
# Always attach the camera frame to every utterance during conversation.
# When False, only explicit vision triggers attach the frame.
VISION_ALWAYS_ATTACH = os.environ.get("VISION_ALWAYS_ATTACH", "true").strip().lower() == "true"

# RTSP bridge: publish camera streams to mediamtx for HA Generic Camera
RTSP_ENABLED = os.environ.get("RTSP_ENABLED", "true").strip().lower() == "true"
RTSP_SERVER = os.environ.get("RTSP_SERVER", "rtsp://localhost:8554")
RTSP_FPS = int(os.environ.get("RTSP_FPS", "5"))

import subprocess
import time as _time_mod


def frame_to_jpeg(frame: rtc.VideoFrame, quality: int = 80) -> bytes:
    """Convert a LiveKit VideoFrame (ARGB) to JPEG bytes."""
    argb_frame = frame.convert(rtc.VideoBufferType.RGBA)
    img = Image.frombytes(
        "RGBA",
        (argb_frame.width, argb_frame.height),
        argb_frame.data,
    )
    img = img.convert("RGB")
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=quality)
    return buf.getvalue()


class RtspBridge:
    """Pipes raw video frames through ffmpeg to publish an RTSP stream to mediamtx."""

    def __init__(self, device_id: str, width: int, height: int):
        self.device_id = device_id
        self.width = width
        self.height = height
        self._proc: subprocess.Popen | None = None

    def start(self):
        rtsp_url = f"{RTSP_SERVER}/tablet-{self.device_id}"
        cmd = [
            "ffmpeg", "-y",
            "-f", "rawvideo",
            "-pix_fmt", "rgb24",
            "-s", f"{self.width}x{self.height}",
            "-r", str(RTSP_FPS),
            "-i", "pipe:0",
            "-c:v", "libx264",
            "-preset", "ultrafast",
            "-tune", "zerolatency",
            "-g", str(RTSP_FPS * 2),
            "-f", "rtsp",
            "-rtsp_transport", "tcp",
            rtsp_url,
        ]
        try:
            self._proc = subprocess.Popen(
                cmd, stdin=subprocess.PIPE,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
            logger.info("[%s] RTSP bridge -> %s (%dx%d@%dfps)",
                        self.device_id, rtsp_url, self.width, self.height, RTSP_FPS)
        except Exception as e:
            logger.error("[%s] RTSP bridge failed: %s", self.device_id, e)

    def write_frame(self, rgb_bytes: bytes):
        if self._proc and self._proc.stdin and self._proc.poll() is None:
            try:
                self._proc.stdin.write(rgb_bytes)
            except BrokenPipeError:
                logger.warning("[%s] RTSP pipe broken, restarting", self.device_id)
                self.stop()
                self.start()

    def stop(self):
        if self._proc:
            try:
                self._proc.stdin.close()
            except Exception:
                pass
            try:
                self._proc.terminate()
                self._proc.wait(timeout=3)
            except Exception:
                try:
                    self._proc.kill()
                except Exception:
                    pass
            self._proc = None


_rtsp_bridges: dict[str, RtspBridge] = {}


async def capture_video_frames(video_stream: rtc.VideoStream, device_id: str = "default"):
    """Capture frames: JPEG buffer for snapshots/vision + RTSP bridge for HA live view."""
    logger.info("Video frame capture started for device=%s (rtsp=%s)", device_id, RTSP_ENABLED)
    frame_count = 0
    last_rtsp_time = 0.0
    rtsp_interval = 1.0 / max(RTSP_FPS, 1)

    async for event in video_stream:
        try:
            frame = event.frame

            # JPEG snapshot buffer
            jpeg = frame_to_jpeg(frame)
            _device_frames[device_id] = jpeg
            frame_count += 1
            if frame_count == 1 or frame_count % 300 == 0:
                logger.info("[%s] Video frame #%d (%d bytes)",
                            device_id, frame_count, len(jpeg))

            # RTSP bridge (rate-limited)
            if RTSP_ENABLED:
                now = _time_mod.monotonic()
                if now - last_rtsp_time >= rtsp_interval:
                    last_rtsp_time = now
                    rgba = frame.convert(rtc.VideoBufferType.RGBA)
                    img = Image.frombytes(
                        "RGBA", (rgba.width, rgba.height), rgba.data,
                    ).convert("RGB")

                    if device_id not in _rtsp_bridges:
                        bridge = RtspBridge(device_id, img.width, img.height)
                        bridge.start()
                        _rtsp_bridges[device_id] = bridge

                    _rtsp_bridges[device_id].write_frame(img.tobytes())

        except Exception as e:
            if frame_count < 3:
                logger.error("[%s] Frame error: %s", device_id, e)


class MultimodalHandler:
    """Handles vision-aware conversations for a single tablet session.

    Features:
    - Maintains a multimodal conversation history (text + images)
    - Attaches the latest camera frame to every utterance (VISION_ALWAYS_ATTACH)
      so the LLM always "sees" what the user sees
    - Explicit vision triggers get enhanced prompting for detailed descriptions
    - Proactive scene analysis: can be triggered externally to describe what
      the camera sees without a user prompt (e.g., doorbell automation)
    - Interactive prompts: the LLM can ask follow-up questions about what it sees
    """

    SYSTEM_PROMPT = (
        "You are a helpful voice assistant with a camera. You can see what the user's "
        "camera shows. When an image is attached, incorporate what you see naturally "
        "into your response. Keep answers concise (1-3 sentences) since they will be "
        "spoken aloud.\n\n"
        "If you notice something interesting, dangerous, or noteworthy in the image "
        "that the user didn't ask about, briefly mention it.\n\n"
        "If the user asks you to look at, describe, read, count, or identify something, "
        "give a detailed but spoken-word-friendly answer.\n\n"
        "If no image is attached, respond normally without referencing visuals."
    )

    # Explicit triggers get an enhanced prompt prefix
    VISION_TRIGGERS = [
        "what do you see", "look at this", "describe what", "what is this",
        "show you", "explain what", "can you see", "tell me what",
        "what am i holding", "what's this", "identify this", "what color",
        "how many", "read this", "what does it say", "look at",
        "check this", "examine", "analyze this", "scan this",
    ]

    MAX_HISTORY = 20  # max conversation turns to keep

    def __init__(self, device_id: str):
        self.device_id = device_id
        self.history: list[dict] = []  # OpenAI-format messages
        self._client = None

    def _get_client(self):
        if self._client is None:
            from openai import OpenAI
            self._client = OpenAI()
        return self._client

    def _is_explicit_vision(self, transcript: str) -> bool:
        """Check if the user is explicitly asking about something visual."""
        lower = transcript.lower()
        return any(t in lower for t in self.VISION_TRIGGERS)

    def _build_user_message(self, transcript: str, jpeg_bytes: bytes | None, explicit_vision: bool) -> dict:
        """Build a multimodal user message with optional image."""
        content = []

        # Add enhanced prompt prefix for explicit vision requests
        if explicit_vision and jpeg_bytes:
            text = (
                f"[The user is pointing the camera at something and asking you to look. "
                f"Describe what you see in detail.] {transcript}"
            )
        else:
            text = transcript

        content.append({"type": "text", "text": text})

        if jpeg_bytes is not None:
            b64 = base64.b64encode(jpeg_bytes).decode("utf-8")
            content.append({
                "type": "image_url",
                "image_url": {
                    "url": f"data:image/jpeg;base64,{b64}",
                    "detail": "low" if not explicit_vision else "auto",
                },
            })

        return {"role": "user", "content": content}

    async def chat(self, transcript: str, text_backend=None) -> str:
        """Process a user utterance with optional vision.

        Decision logic:
        1. Explicit vision trigger + frame available → multimodal query (detailed)
        2. VISION_ALWAYS_ATTACH + frame available → multimodal query (ambient)
        3. No frame or vision disabled → delegate to text_backend
        """
        jpeg = _device_frames.get(self.device_id)
        explicit_vision = self._is_explicit_vision(transcript)
        use_vision = (explicit_vision and jpeg is not None) or \
                     (VISION_ALWAYS_ATTACH and jpeg is not None)

        if not use_vision:
            # No vision — use the regular text backend (Hermes/OpenRouter)
            if text_backend:
                return await text_backend.chat(transcript)
            # Fallback: send as text-only to vision model
            return await self._query(transcript, None, False)

        return await self._query(transcript, jpeg, explicit_vision)

    async def _query(self, transcript: str, jpeg: bytes | None, explicit_vision: bool) -> str:
        """Send multimodal query to the vision model with conversation history."""
        client = self._get_client()

        user_msg = self._build_user_message(transcript, jpeg, explicit_vision)
        self.history.append(user_msg)

        # Trim history (keep system + last N turns)
        trimmed = self.history[-self.MAX_HISTORY:]

        messages = [
            {"role": "system", "content": self.SYSTEM_PROMPT},
            *trimmed,
        ]

        try:
            response = await asyncio.to_thread(
                client.chat.completions.create,
                model=VISION_MODEL,
                messages=messages,
                max_tokens=400,
            )
            reply = response.choices[0].message.content
            self.history.append({"role": "assistant", "content": reply})
            return reply
        except Exception as e:
            logger.error("MultimodalHandler query failed: %s", e)
            return f"I had trouble processing that: {e}"

    async def proactive_describe(self, context: str = "") -> str | None:
        """Proactively describe what the camera sees.

        Called externally (e.g., by HA automation, doorbell trigger, motion sensor).
        Returns None if no frame is available.
        """
        jpeg = _device_frames.get(self.device_id)
        if jpeg is None:
            return None

        prompt = "Briefly describe what you see in the camera image."
        if context:
            prompt = f"{context} Briefly describe what you see in the camera image."

        return await self._query(prompt, jpeg, explicit_vision=True)

    def reset(self):
        """Clear conversation history (called when conversation ends)."""
        self.history.clear()


# ---------------------------------------------------------------------------
# Snapshot HTTP server (for HA Generic Camera integration)
# ---------------------------------------------------------------------------

SNAPSHOT_PORT = int(os.environ.get("SNAPSHOT_PORT", "8091"))


async def handle_snapshot(request: web.Request) -> web.Response:
    """Return the latest camera frame as a JPEG image.

    GET /snapshot            -> first available device
    GET /snapshot?device=id  -> specific device
    """
    device_id = request.query.get("device", "")
    if device_id:
        jpeg = _device_frames.get(device_id)
    elif _device_frames:
        jpeg = next(iter(_device_frames.values()))
    else:
        jpeg = None
    if jpeg is None:
        return web.Response(text="No frame available", status=503)
    return web.Response(
        body=jpeg,
        content_type="image/jpeg",
        headers={"Cache-Control": "no-cache"},
    )


async def handle_snapshot_health(request: web.Request) -> web.Response:
    devices = {k: len(v) for k, v in _device_frames.items()}
    return web.Response(text=_json.dumps({"ok": True, "devices": devices}),
                        content_type="application/json")


async def start_snapshot_server():
    """Start the snapshot HTTP server for HA camera integration."""
    app = web.Application()
    app.router.add_get("/snapshot", handle_snapshot)
    app.router.add_get("/health", handle_snapshot_health)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "0.0.0.0", SNAPSHOT_PORT)
    await site.start()
    logger.info("Snapshot server listening on :%d", SNAPSHOT_PORT)


# ---------------------------------------------------------------------------
# Live transcript relay (sends to LiveKit room data channel)
# ---------------------------------------------------------------------------

_agent_label = "🤖 Hermes" if AGENT_MODE == "hermes" else "🤖 Assistant"


async def relay_transcript(room: rtc.Room, role: str, text: str):
    """Send transcript as a chat message visible in LiveKit's built-in chat UI."""
    try:
        prefix = "🎤 You: " if role == "user" else f"{_agent_label}: "
        msg = _json.dumps({
            "type": "chat_message",
            "message": prefix + text,
            "timestamp": int(asyncio.get_event_loop().time() * 1000),
        })
        await room.local_participant.publish_data(
            msg.encode("utf-8"),
            reliable=True,
            topic="lk-chat-topic",
        )
    except Exception as e:
        logger.debug("Chat relay failed: %s", e)

# ---------------------------------------------------------------------------
# STT helper — OpenRouter chat completions with audio input
# ---------------------------------------------------------------------------

STT_MODEL = os.environ.get("STT_MODEL", "google/gemini-3.1-flash-lite-preview")

async def transcribe_audio(wav_path: str) -> str:
    """Transcribe a WAV file via OpenRouter audio-capable model."""
    import base64
    import aiohttp

    api_key = os.environ.get("OPENROUTER_API_KEY", "")
    if not api_key:
        logger.error("STT: OPENROUTER_API_KEY not set")
        return ""

    with open(wav_path, "rb") as f:
        audio_b64 = base64.b64encode(f.read()).decode()

    payload = {
        "model": STT_MODEL,
        "messages": [{
            "role": "user",
            "content": [
                {"type": "text", "text": "Transcribe this audio exactly. Return only the spoken words, nothing else."},
                {"type": "input_audio", "input_audio": {"data": audio_b64, "format": "wav"}},
            ],
        }],
    }
    try:
        async with aiohttp.ClientSession() as session:
            async with session.post(
                "https://openrouter.ai/api/v1/chat/completions",
                headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
                json=payload,
                timeout=aiohttp.ClientTimeout(total=30),
            ) as resp:
                if resp.status != 200:
                    body = await resp.text()
                    logger.error("STT upstream error %d: %s", resp.status, body[:200])
                    return ""
                data = await resp.json()
                return data["choices"][0]["message"]["content"].strip()
    except Exception as e:
        logger.error("STT failed: %s", e)
        return ""

# ---------------------------------------------------------------------------
# TTS helper — edge-tts or OpenAI → PCM via pydub
#
# TTS_BACKEND  edge-tts (default, free) | openai (requires OPENAI_API_KEY)
# TTS_VOICE    edge-tts voice name  e.g. en-GB-SoniaNeural
#              openai voice name    e.g. nova | alloy | echo | fable | onyx | shimmer
# ---------------------------------------------------------------------------

TTS_BACKEND = os.environ.get("TTS_BACKEND", "edge-tts").strip().lower()
TTS_VOICE = os.environ.get(
    "TTS_VOICE",
    "nova" if TTS_BACKEND == "openai" else "en-GB-SoniaNeural"
)
logger.info("TTS backend: %s | voice: %s", TTS_BACKEND, TTS_VOICE)


async def text_to_speech(text: str, output_path: str) -> bool:
    """Convert text to 24kHz 16-bit mono PCM. Supports edge-tts and OpenAI TTS."""
    from pydub import AudioSegment
    from io import BytesIO
    try:
        if TTS_BACKEND == "openai":
            from openai import OpenAI
            api_key = os.environ.get("OPENAI_API_KEY", "")
            if not api_key:
                logger.error("TTS: OPENAI_API_KEY not set")
                return False
            client = OpenAI(api_key=api_key)
            response = await asyncio.to_thread(
                client.audio.speech.create,
                model="tts-1",
                voice=TTS_VOICE,
                input=text,
                response_format="mp3",
            )
            mp3_bytes = response.content
        else:
            import edge_tts
            communicate = edge_tts.Communicate(text, TTS_VOICE)
            mp3_chunks = []
            async for chunk in communicate.stream():
                if chunk["type"] == "audio":
                    mp3_chunks.append(chunk["data"])
            if not mp3_chunks:
                logger.error("TTS: edge-tts returned no audio")
                return False
            mp3_bytes = b"".join(mp3_chunks)

        audio = AudioSegment.from_mp3(BytesIO(mp3_bytes))
        audio = audio.set_channels(1).set_frame_rate(24000).set_sample_width(2)
        with open(output_path, "wb") as f:
            f.write(audio.raw_data)
        return True
    except Exception as e:
        logger.error("TTS failed: %s", e)
        return False

# ---------------------------------------------------------------------------
# Chat backends
# ---------------------------------------------------------------------------

class HermesBackend:
    """Full Hermes AIAgent — tools, memory, HA, MCP servers."""

    def __init__(self):
        try:
            from run_agent import AIAgent
        except ImportError as e:
            raise SystemExit(
                f"AGENT_MODE=hermes but Hermes failed to import: {e}\n"
                "Ensure the image was built with AGENT_MODE=hermes (pip-installs hermes-agent from GitHub)."
            ) from e
        self._agent = AIAgent(
            model=os.environ.get("HERMES_MODEL", "gpt-4o-mini"),
            quiet_mode=True,
            enabled_toolsets=["web", "homeassistant", "memory", "terminal"],
        )
        self._history = []
        logger.info("HermesBackend ready (model=%s)", os.environ.get("HERMES_MODEL", "gpt-4o-mini"))

    async def chat(self, user_message: str) -> str:
        result = await asyncio.to_thread(
            self._agent.run_conversation,
            user_message=user_message,
            conversation_history=self._history,
        )
        self._history = result.get("messages", [])
        return result.get("final_response", "I'm not sure how to respond.")


class OpenRouterBackend:
    """Lightweight direct OpenRouter call — no tools, no memory.

    Model is selected via AGENT_MODEL env var (default: openai/gpt-4o-mini).
    Uses OPENROUTER_API_KEY from the mounted ~/.hermes/.env.
    """

    SYSTEM_PROMPT = (
        "You are a helpful voice assistant. "
        "Keep responses concise — 1 to 3 sentences. "
        "You are running in lightweight mode without tools or memory."
    )

    def __init__(self):
        from openai import OpenAI
        api_key = os.environ.get("OPENROUTER_API_KEY")
        if not api_key:
            raise SystemExit(
                "AGENT_MODE=openrouter but OPENROUTER_API_KEY is not set.\n"
                "Ensure ~/.hermes/.env is mounted and contains OPENROUTER_API_KEY."
            )
        self._model = os.environ.get("AGENT_MODEL", "openai/gpt-4o-mini")
        self._client = OpenAI(
            api_key=api_key,
            base_url="https://openrouter.ai/api/v1",
        )
        self._history = []
        logger.info("OpenRouterBackend ready (model=%s)", self._model)

    async def chat(self, user_message: str) -> str:
        self._history.append({"role": "user", "content": user_message})
        resp = await asyncio.to_thread(
            self._client.chat.completions.create,
            model=self._model,
            messages=[
                {"role": "system", "content": self.SYSTEM_PROMPT},
                *self._history[-20:],
            ],
        )
        reply = resp.choices[0].message.content
        self._history.append({"role": "assistant", "content": reply})
        return reply


def create_backend():
    """Instantiate the backend selected by AGENT_MODE. Fails hard on misconfiguration."""
    if AGENT_MODE == "hermes":
        return HermesBackend()
    if AGENT_MODE == "openrouter":
        return OpenRouterBackend()
    # unreachable — guarded at module load above

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

                        elif msg.type in (aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.ERROR):
                            logger.warning("HA events: WebSocket closed/error")
                            break

        except Exception as e:
            logger.warning("HA events: connection error: %s, reconnecting in 10s", e)

        await asyncio.sleep(10)  # Reconnect backoff


# ---------------------------------------------------------------------------
# TabletSession — per-tablet room with independent conversation state
# ---------------------------------------------------------------------------

class TabletSession:
    """Manages a single tablet's LiveKit room and conversation lifecycle.

    Each tablet gets its own room (voice-room-{device_id}), its own VAD,
    conversation state, TTS track, and video frame buffer.
    """

    SILENCE_TIMEOUT = 30  # seconds of no speech before going back to IDLE

    def __init__(self, device_id: str, url: str, api_key: str, api_secret: str):
        self.device_id = device_id
        self.room_name = f"voice-room-{device_id}"
        self.url = url
        self.api_key = api_key
        self.api_secret = api_secret

        self.room = rtc.Room()
        self.backend = create_backend()
        self.vad = SimpleVAD()
        self.multimodal = MultimodalHandler(device_id)

        self.processing = False
        self.agent_active = False
        self.last_speech_time = 0.0
        self._running = True

        self.tts_source: rtc.AudioSource | None = None

    def log(self, level: str, msg: str, *args):
        getattr(logger, level)(f"[{self.device_id}] {msg}", *args)

    async def start(self):
        """Connect to the tablet's room and set up event handlers."""
        token = (
            api.AccessToken(self.api_key, self.api_secret)
            .with_identity(f"hermes-agent-{self.device_id}")
            .with_grants(api.VideoGrants(
                room_join=True,
                room=self.room_name,
                can_publish=True,
                can_subscribe=True,
            ))
            .to_jwt()
        )

        self.log("info", "Connecting to room %s", self.room_name)
        await self.room.connect(self.url, token)
        self.log("info", "Connected to room %s", self.room_name)

        # Publish TTS audio track
        self.tts_source = rtc.AudioSource(24000, 1)
        tts_track = rtc.LocalAudioTrack.create_audio_track(
            f"hermes-tts-{self.device_id}", self.tts_source
        )
        await self.room.local_participant.publish_track(tts_track)
        self.log("info", "Published TTS audio track")

        # Wire up event handlers
        self._setup_event_handlers()

        # Start silence timeout checker
        asyncio.ensure_future(self._silence_timeout_checker())

        self.log("info", "Session ready, waiting for wake word signal")

    def _setup_event_handlers(self):
        """Register LiveKit room event handlers for this tablet session."""

        @self.room.on("track_subscribed")
        def on_track_subscribed(track: rtc.Track, publication: rtc.RemoteTrackPublication, participant: rtc.RemoteParticipant):
            if track.kind == rtc.TrackKind.KIND_VIDEO:
                self.log("info", "Subscribed to VIDEO from %s", participant.identity)
                video_stream = rtc.VideoStream(track)
                asyncio.ensure_future(capture_video_frames(video_stream, self.device_id))
            elif track.kind == rtc.TrackKind.KIND_AUDIO:
                self.log("info", "Subscribed to audio from %s", participant.identity)
                asyncio.ensure_future(self._process_audio_stream(rtc.AudioStream(track)))

        @self.room.on("data_received")
        def on_data(data: rtc.DataPacket):
            try:
                msg = _json.loads(data.data.decode())
                msg_type = msg.get("type", "")
                if msg_type == "wake_word_detected":
                    score = msg.get("score", 0)
                    self.log("info", "Wake word detected (score=%s)", score)
                    if not self.agent_active:
                        asyncio.ensure_future(self._activate())
                elif msg_type == "end_conversation":
                    self.log("info", "End conversation requested")
                    if self.agent_active:
                        asyncio.ensure_future(self._deactivate())
                elif msg_type == "proactive_describe":
                    # Triggered externally (e.g., HA automation, doorbell)
                    context = msg.get("context", "")
                    self.log("info", "Proactive describe requested (context=%s)", context[:50])
                    asyncio.ensure_future(self._proactive_vision(context))
            except Exception as e:
                self.log("debug", "Data parse error: %s", e)

        @self.room.on("participant_connected")
        def on_participant(participant: rtc.RemoteParticipant):
            self.log("info", "Participant joined: %s", participant.identity)

    async def _activate(self):
        """Start a conversation with this tablet."""
        import time as _time
        self.agent_active = True
        self.last_speech_time = _time.monotonic()
        self.processing = True
        try:
            self.log("info", "Conversation ACTIVE")
            await relay_transcript(self.room, "system", "🌟 Conversation started")
            greeting = await self.backend.chat(
                "The user just said the wake word Hey Jarvis. Greet them warmly and briefly."
            )
            self.log("info", "Greeting: %s", greeting)
            await self._speak(greeting)
        finally:
            self.processing = False

    async def _deactivate(self):
        """End conversation, signal tablet to return to wake word mode."""
        self.agent_active = False
        self.multimodal.reset()
        self.log("info", "Conversation IDLE")
        await relay_transcript(self.room, "system", "💤 Conversation ended")
        try:
            msg = _json.dumps({"type": "conversation_ended"})
            await self.room.local_participant.publish_data(
                msg.encode(), reliable=True, topic="hermes-control"
            )
        except Exception:
            pass

    async def _silence_timeout_checker(self):
        import time as _time
        while self._running:
            await asyncio.sleep(5)
            if self.agent_active and not self.processing:
                elapsed = _time.monotonic() - self.last_speech_time
                if elapsed > self.SILENCE_TIMEOUT:
                    self.log("info", "Silence timeout (%.0fs)", elapsed)
                    await self._deactivate()

    async def _speak(self, text: str):
        """Speak text via LiveKit TTS audio track.

        When avatar is enabled, also sends text via data channel so
        TalkingHead.js can animate lip sync (its audio output is muted;
        all audible audio goes through the LiveKit WebRTC track).
        """
        if AVATAR_ENABLED:
            # Send text for avatar lip sync (audio muted on avatar side)
            try:
                msg = _json.dumps({"type": "agent_speak", "text": text})
                await self.room.local_participant.publish_data(
                    msg.encode("utf-8"), reliable=True, topic="hermes-control"
                )
            except Exception as e:
                self.log("error", "Avatar speak failed: %s", e)
            # Fall through — audio always published via LiveKit track

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

            samples_per_frame = 480  # 20ms at 24kHz
            frame_duration = samples_per_frame / 24000.0
            samples = np.frombuffer(pcm_data, dtype=np.int16)

            for i in range(0, len(samples), samples_per_frame):
                if not self.agent_active:
                    self.log("info", "TTS cancelled (deactivated)")
                    break
                frame_data = samples[i:i + samples_per_frame]
                if len(frame_data) < samples_per_frame:
                    frame_data = np.pad(frame_data, (0, samples_per_frame - len(frame_data)))

                frame = rtc.AudioFrame(
                    data=frame_data.tobytes(),
                    sample_rate=24000,
                    num_channels=1,
                    samples_per_channel=samples_per_frame,
                )
                await self.tts_source.capture_frame(frame)
                await asyncio.sleep(frame_duration * 0.8)
        finally:
            try:
                os.unlink(tmp.name)
            except OSError:
                pass

    async def _process_audio_stream(self, audio_stream: rtc.AudioStream):
        """Process audio frames from the tablet: VAD -> STT -> LLM -> TTS."""
        frame_count = 0
        async for event in audio_stream:
            if self.processing or not self.agent_active:
                continue

            frame = event.frame
            frame_count += 1
            if frame_count <= 3 or frame_count % 500 == 0:
                audio_bytes = frame.data.tobytes()
                rms = np.sqrt(np.mean(np.frombuffer(
                    audio_bytes[:min(len(audio_bytes), 1000)],
                    dtype=np.int16).astype(np.float32) ** 2)) if len(audio_bytes) > 0 else 0
                self.log("info", "Audio frame #%d: sr=%d ch=%d rms=%.0f",
                         frame_count, frame.sample_rate, frame.num_channels, rms)

            pcm_16k = resample_48k_to_16k_mono(
                frame.data.tobytes(), channels=frame.num_channels,
            )
            utterance = self.vad.process(pcm_16k)
            if utterance:
                self.processing = True
                asyncio.ensure_future(self._handle_utterance(utterance))

    async def _handle_utterance(self, pcm_data: bytes):
        """Process a complete utterance: STT -> multimodal handler -> TTS."""
        try:
            wav_path = pcm_to_wav(pcm_data, sample_rate=16000)
            try:
                transcript = await transcribe_audio(wav_path)
            finally:
                os.unlink(wav_path)

            if not transcript:
                return

            import time as _time
            self.last_speech_time = _time.monotonic()
            self.log("info", "User said: %s", transcript)
            await relay_transcript(self.room, "user", transcript)

            # MultimodalHandler decides: vision model (with frame) or text backend
            response = await self.multimodal.chat(transcript, text_backend=self.backend)

            self.log("info", "Response: %s", response[:100])
            await relay_transcript(self.room, "assistant", response)
            await self._speak(response)

        except Exception as e:
            self.log("error", "Utterance error: %s", e, exc_info=True)
        finally:
            self.processing = False

    async def _proactive_vision(self, context: str = ""):
        """Proactively describe what the camera sees and speak it.

        Triggered externally via data channel message:
            {"type": "proactive_describe", "context": "Someone rang the doorbell."}
        """
        self.processing = True
        try:
            description = await self.multimodal.proactive_describe(context)
            if description:
                self.log("info", "Proactive vision: %s", description[:100])
                await relay_transcript(self.room, "assistant", description)
                await self._speak(description)
            else:
                self.log("warning", "Proactive vision: no frame available")
        except Exception as e:
            self.log("error", "Proactive vision error: %s", e)
        finally:
            self.processing = False

    async def stop(self):
        self._running = False
        await self.room.disconnect()
        self.log("info", "Session stopped")


# ---------------------------------------------------------------------------
# Main: multi-tablet session manager
# ---------------------------------------------------------------------------

TOKEN_SERVER_URL = os.environ.get("TOKEN_SERVER_URL", "http://localhost:8090")
DEVICE_POLL_INTERVAL = int(os.environ.get("DEVICE_POLL_INTERVAL", "15"))
INTERCOM_API_KEY = os.environ.get("INTERCOM_API_KEY", "")


async def discover_devices() -> list[str]:
    """Fetch registered device IDs from the token server."""
    import aiohttp
    headers = {}
    if INTERCOM_API_KEY:
        headers["Authorization"] = f"Bearer {INTERCOM_API_KEY}"
    try:
        async with aiohttp.ClientSession() as session:
            async with session.get(f"{TOKEN_SERVER_URL}/devices", headers=headers, timeout=aiohttp.ClientTimeout(total=5)) as resp:
                if resp.status == 200:
                    data = await resp.json()
                    return [d["device_id"] for d in data.get("devices", []) if d.get("status") == "online"]
                elif resp.status == 401:
                    logger.warning("Device discovery: 401 Unauthorized (check INTERCOM_API_KEY)")
    except Exception as e:
        logger.warning("Device discovery failed: %s", e)
    return []


async def main():
    url = os.environ["LIVEKIT_URL"]
    api_key = os.environ["LIVEKIT_API_KEY"]
    api_secret = os.environ["LIVEKIT_API_SECRET"]

    sessions: dict[str, TabletSession] = {}  # device_id -> TabletSession

    logger.info("Multi-tablet agent starting (polling %s every %ds)", TOKEN_SERVER_URL, DEVICE_POLL_INTERVAL)

    # Start shared services
    asyncio.ensure_future(ha_event_listener())
    asyncio.ensure_future(start_snapshot_server())

    # Legacy fallback: join the shared "voice-room" for tablets still running
    # the old APK that hasn't been updated to per-tablet rooms yet.
    # This session handles ALL old tablets (same cross-talk behavior as before).
    # Remove this once all tablets are updated.
    legacy_session = TabletSession("legacy", url, api_key, api_secret)
    legacy_session.room_name = "voice-room"  # Override the per-tablet name
    try:
        await legacy_session.start()
        sessions["legacy"] = legacy_session
        logger.info("Legacy shared 'voice-room' session active (for old APK tablets)")
    except Exception as e:
        logger.warning("Legacy session failed: %s", e)

    # Device discovery + session management loop
    while True:
        try:
            devices = await discover_devices()
            # Start sessions for new devices
            for device_id in devices:
                if device_id not in sessions:
                    logger.info("New device discovered: %s, starting session", device_id)
                    session = TabletSession(device_id, url, api_key, api_secret)
                    sessions[device_id] = session
                    try:
                        await session.start()
                    except Exception as e:
                        logger.error("Failed to start session for %s: %s", device_id, e)
                        del sessions[device_id]

            # Log active sessions periodically
            if sessions:
                active = [sid for sid, s in sessions.items() if s.agent_active]
                logger.debug("Sessions: %d total, %d active conversations", len(sessions), len(active))

        except Exception as e:
            logger.error("Session manager error: %s", e)

        await asyncio.sleep(DEVICE_POLL_INTERVAL)


if __name__ == "__main__":
    asyncio.run(main())
