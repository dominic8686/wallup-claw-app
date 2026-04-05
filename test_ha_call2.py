"""Fire a call and wait for the agent to pick it up (don't drain the queue ourselves)."""
import urllib.request, json, time

BASE = "http://localhost:8090"

def post(p, d):
    req = urllib.request.Request(f"{BASE}{p}", data=json.dumps(d).encode(), headers={"Content-Type":"application/json"})
    return json.loads(urllib.request.urlopen(req).read().decode())

def get(p):
    return json.loads(urllib.request.urlopen(f"{BASE}{p}").read().decode())

# Re-register device (in case heartbeat expired)
post("/register", {"device_id": "tablet-ee8ce84e", "display_name": "android-user", "room_location": "1-Office"})

print("=== Triggering call from HA ===")
result = post("/signal", {"type": "call_request", "from": "homeassistant", "to": "tablet-ee8ce84e"})
print("  Call ID:", result.get("call_id"))
print("  Room:", result.get("room_name"))

# DON'T read /agent-calls — let the agent poller consume it
print("\n=== Waiting 10s for agent to pick up... ===")
time.sleep(10)

print("\n=== Active calls ===")
calls = get("/calls")
for c in calls.get("calls", []):
    print(f"  {c['room_name']}: status={c['status']}")

print("\n=== Agent-calls queue (should be empty if agent consumed it) ===")
agent_calls = get("/agent-calls")
print(f"  Remaining: {len(agent_calls.get('calls', []))}")

print("\n=== DONE ===")
