"""Clean up stale calls and reset device states."""
import urllib.request, json

BASE = "http://localhost:8090"

def post(p, d):
    req = urllib.request.Request(f"{BASE}{p}", data=json.dumps(d).encode(), headers={"Content-Type":"application/json"})
    return json.loads(urllib.request.urlopen(req).read().decode())

def get(p):
    return json.loads(urllib.request.urlopen(f"{BASE}{p}").read().decode())

# Get active calls
calls = get("/calls")
print(f"Active calls: {len(calls.get('calls', []))}")

# Hangup each stale call
for c in calls.get("calls", []):
    room = c.get("room_name", "")
    call_id = room  # call_id == room_name in this system
    from_dev = c.get("from", "")
    to_dev = c.get("to", "")
    print(f"  Hanging up: {from_dev} -> {to_dev} ({call_id})")
    try:
        post("/signal", {"type": "call_hangup", "from": from_dev, "to": to_dev, "call_id": call_id})
    except Exception as e:
        print(f"    Error: {e}")

# Reset device via heartbeat
post("/heartbeat", {"device_id": "tablet-ee8ce84e", "call_state": "idle"})
print("\nReset tablet-ee8ce84e to idle")

# Verify
devices = get("/devices")
for d in devices.get("devices", []):
    print(f"  {d['device_id']}: status={d['status']} call_state={d['call_state']}")
calls2 = get("/calls")
print(f"Active calls now: {len(calls2.get('calls', []))}")
