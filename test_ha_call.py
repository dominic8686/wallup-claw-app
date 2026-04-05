"""Simulate HA calling a tablet and verify the agent picks up."""
import urllib.request, json, time

BASE = "http://localhost:8090"

def post(p, d):
    req = urllib.request.Request(f"{BASE}{p}", data=json.dumps(d).encode(), headers={"Content-Type":"application/json"})
    return json.loads(urllib.request.urlopen(req).read().decode())

def get(p):
    return json.loads(urllib.request.urlopen(f"{BASE}{p}").read().decode())

print("=== 1. Trigger call from 'homeassistant' (non-device) ===")
result = post("/signal", {"type": "call_request", "from": "homeassistant", "to": "tablet-ee8ce84e"})
print("  Signal result:", result)

print("\n=== 2. Check agent-calls queue ===")
agent_calls = get("/agent-calls")
print("  Agent calls:", json.dumps(agent_calls, indent=2))

print("\n=== 3. Device state ===")
devices = get("/devices")
for d in devices.get("devices", []):
    print(f"  {d['device_id']}: status={d['status']} call_state={d['call_state']}")

print("\n=== 4. Active calls ===")
calls = get("/calls")
print("  Calls:", json.dumps(calls, indent=2))

print("\n=== 5. Wait 5s, then check if agent joined (call should still be active) ===")
time.sleep(5)
calls2 = get("/calls")
print("  Calls after 5s:", json.dumps(calls2, indent=2))

print("\n=== DONE ===")
