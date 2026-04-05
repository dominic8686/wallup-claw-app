"""Add cache buster to the hermes-intercom-card resource URL."""
import json, time

path = "/config/.storage/lovelace_resources"
d = json.load(open(path))

for item in d["data"]["items"]:
    if "hermes-intercom-card" in item.get("url", ""):
        old = item["url"]
        item["url"] = f"/local/hermes-intercom-card.js?v={int(time.time())}"
        print(f"OLD: {old}")
        print(f"NEW: {item['url']}")

json.dump(d, open(path, "w"), indent=2)
print("Done. Restart HA to apply.")
