"""Update the hermes-intercom-card config in HA lovelace storage."""
import json

path = "/config/.storage/lovelace.lovelace"
d = json.load(open(path))

updated = False
for view in d.get("data", {}).get("config", {}).get("views", []):
    for section in view.get("sections", []):
        for card in section.get("cards", []):
            if card.get("type") == "custom:hermes-intercom-card":
                print("BEFORE:", json.dumps(card, indent=2))
                card["token_server_url"] = "https://intercom.dezznuts.me"
                card["livekit_url"] = "wss://livekit.dezznuts.me"
                card["api_key"] = "f2OeBSnXNvJQWgd867V1hpc9wioMKlmF"
                print("AFTER:", json.dumps(card, indent=2))
                updated = True
    # Also check top-level cards (non-section views)
    for card in view.get("cards", []):
        if card.get("type") == "custom:hermes-intercom-card":
            print("BEFORE:", json.dumps(card, indent=2))
            card["token_server_url"] = "https://intercom.dezznuts.me"
            card["livekit_url"] = "wss://livekit.dezznuts.me"
            card["api_key"] = "f2OeBSnXNvJQWgd867V1hpc9wioMKlmF"
            print("AFTER:", json.dumps(card, indent=2))
            updated = True

if updated:
    json.dump(d, open(path, "w"), indent=2)
    print("\nConfig updated! Restart HA or hard-refresh dashboard.")
else:
    print("Card not found in lovelace config!")
    # Debug: show structure
    for i, view in enumerate(d.get("data", {}).get("config", {}).get("views", [])):
        cards = view.get("cards", [])
        sections = view.get("sections", [])
        print(f"  View {i}: {len(cards)} cards, {len(sections)} sections")
        for s in sections:
            sc = s.get("cards", [])
            print(f"    Section: {len(sc)} cards -> {[c.get('type','?') for c in sc]}")
