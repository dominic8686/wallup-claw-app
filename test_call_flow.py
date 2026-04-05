#!/usr/bin/env python3
"""End-to-end test of the token server call signaling."""
import urllib.request, json, sys

BASE = "http://localhost:8090"

def post(path, data):
    req = urllib.request.Request(
        f"{BASE}{path}",
        data=json.dumps(data).encode(),
        headers={"Content-Type": "application/json"},
    )
    return json.loads(urllib.request.urlopen(req).read().decode())

def get(path):
    return json.loads(urllib.request.urlopen(f"{BASE}{path}").read().decode())

print("=== 1. Register two test devices ===")
r1 = post("/register", {"device_id": "test-kitchen", "display_name": "Kitchen Tablet", "room_location": "Kitchen"})
print("  Kitchen:", r1)
r2 = post("/register", {"device_id": "test-bedroom", "display_name": "Bedroom Tablet", "room_location": "Bedroom"})
print("  Bedroom:", r2)

print("\n=== 2. List devices ===")
devices = get("/devices")
for d in devices.get("devices", []):
    print(f"  {d['device_id']}: status={d['status']} call_state={d['call_state']}")

print("\n=== 3. Initiate call: kitchen -> bedroom ===")
call = post("/signal", {"type": "call_request", "from": "test-kitchen", "to": "test-bedroom"})
print("  Result:", call)

print("\n=== 4. Check pending signals for bedroom ===")
signals = get("/signals?device_id=test-bedroom")
print("  Signals:", json.dumps(signals, indent=4))

print("\n=== 5. Active calls ===")
calls = get("/calls")
print("  Calls:", json.dumps(calls, indent=4))

print("\n=== 6. Device states after call request ===")
devices2 = get("/devices")
for d in devices2.get("devices", []):
    print(f"  {d['device_id']}: status={d['status']} call_state={d['call_state']}")

print("\n=== 7. Accept the call (bedroom accepts) ===")
call_id = call.get("call_id", "")
if call_id:
    accept = post("/signal", {"type": "call_accept", "from": "test-bedroom", "to": "test-kitchen", "call_id": call_id})
    print("  Accept result:", accept)

    print("\n=== 8. Device states after accept ===")
    devices3 = get("/devices")
    for d in devices3.get("devices", []):
        print(f"  {d['device_id']}: status={d['status']} call_state={d['call_state']}")

    print("\n=== 9. Hangup ===")
    hangup = post("/signal", {"type": "call_hangup", "from": "test-kitchen", "to": "test-bedroom", "call_id": call_id})
    print("  Hangup result:", hangup)

    print("\n=== 10. Device states after hangup ===")
    devices4 = get("/devices")
    for d in devices4.get("devices", []):
        print(f"  {d['device_id']}: status={d['status']} call_state={d['call_state']}")
else:
    print("  ERROR: No call_id returned, call initiation failed!")

print("\n=== DONE ===")
