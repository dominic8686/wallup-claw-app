"""Minimal token server for LiveKit.

Endpoints:
  GET /token?identity=<name>&room=<room_name>
    Returns a JSON object: {"token": "<jwt>", "url": "<livekit_ws_url>"}

  GET /health
    Returns 200 OK
"""

import os
import json

from aiohttp import web
from livekit.api import AccessToken, VideoGrants

LIVEKIT_API_KEY = os.environ["LIVEKIT_API_KEY"]
LIVEKIT_API_SECRET = os.environ["LIVEKIT_API_SECRET"]
# URL the *client* should connect to (external, not internal docker network)
LIVEKIT_EXTERNAL_URL = os.environ.get("LIVEKIT_EXTERNAL_URL", "ws://localhost:7880")


async def handle_token(request: web.Request) -> web.Response:
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

    return web.Response(
        text=json.dumps({"token": jwt_token, "url": LIVEKIT_EXTERNAL_URL}),
        content_type="application/json",
        headers={"Access-Control-Allow-Origin": "*"},
    )


async def handle_health(request: web.Request) -> web.Response:
    return web.Response(text="ok")


app = web.Application()
app.router.add_get("/token", handle_token)
app.router.add_get("/health", handle_health)

if __name__ == "__main__":
    port = int(os.environ.get("PORT", 8090))
    print(f"Token server listening on :{port}")
    web.run_app(app, host="0.0.0.0", port=port)
