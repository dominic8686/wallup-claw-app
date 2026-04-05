p = "/opt/livekit-voice-agent/docker-compose.yml"
t = open(p).read()
t = t.replace("- INTERCOM_API_KEY=\n", "- INTERCOM_API_KEY=${INTERCOM_API_KEY}\n")
open(p, "w").write(t)
print("Fixed. Current line:")
for line in open(p):
    if "INTERCOM" in line:
        print(" ", line.rstrip())
