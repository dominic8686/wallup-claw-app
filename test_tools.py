import os, sys
sys.path.insert(0, "/opt/hermes")
os.chdir("/opt/hermes")
from run_agent import AIAgent
a = AIAgent(model="gpt-4o-mini", quiet_mode=True)
tools = a.tools or []
print(f"Total tools: {len(tools)}")
for t in tools[:20]:
    name = t.get("function", {}).get("name", "?")
    print(f"  - {name}")
