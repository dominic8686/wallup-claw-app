import urllib.request, json
BASE = "http://localhost:8090"
def post(p, d):
    req = urllib.request.Request(f"{BASE}{p}", data=json.dumps(d).encode(), headers={"Content-Type":"application/json"})
    return json.loads(urllib.request.urlopen(req).read().decode())
def get(p):
    return json.loads(urllib.request.urlopen(f"{BASE}{p}").read().decode())
r = post("/register", {"device_id":"tablet-ee8ce84e","display_name":"android-user","room_location":"1-Office"})
print("Register:", r)
print("Devices:", json.dumps(get("/devices"), indent=2))
