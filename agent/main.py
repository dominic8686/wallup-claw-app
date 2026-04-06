"""LiveKit Voice Agent — native AgentSession mode.

Uses the LiveKit Agents framework (AgentSession) with speech-to-speech
realtime models (Gemini Live, OpenAI Realtime) or pipeline mode
(separate STT + LLM + TTS).

See livekit_session.py for configuration details.
"""

import asyncio
import logging
import sys

logging.basicConfig(level=logging.INFO, stream=sys.stdout)

import livekit_session

if __name__ == "__main__":
    asyncio.run(livekit_session.run())
