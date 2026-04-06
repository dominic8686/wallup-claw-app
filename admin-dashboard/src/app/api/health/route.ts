import { NextResponse } from "next/server";
import { dockerPs, readComposeFile } from "@/lib/ssh";
import { getHealth as tsHealth } from "@/lib/token-server";
import type { ContainerStatus, HealthStatus } from "@/lib/types";

/** Parse `docker compose ps --format json` output (one JSON object per line). */
function parseDockerPs(raw: string): ContainerStatus[] {
  return raw
    .split("\n")
    .filter(Boolean)
    .map((line) => {
      try {
        const obj = JSON.parse(line);
        return {
          name: obj.Name ?? obj.Service ?? "unknown",
          state: (obj.State ?? "unknown").toLowerCase(),
          status: obj.Status ?? "",
          image: obj.Image ?? "",
        } as ContainerStatus;
      } catch {
        return null;
      }
    })
    .filter(Boolean) as ContainerStatus[];
}

/** Extract env var values from docker-compose.yml (simple regex). */
function extractEnvVar(compose: string, key: string): string {
  const regex = new RegExp(`-\\s*${key}=(.+)`, "m");
  const match = compose.match(regex);
  return match ? match[1].trim() : "";
}

export async function GET() {
  try {
    const [psRaw, compose, tokenOk] = await Promise.all([
      dockerPs().catch(() => ""),
      readComposeFile().catch(() => ""),
      tsHealth().catch(() => false),
    ]);

    const containers = parseDockerPs(psRaw);

    const lkRunning = containers.some(
      (c) => c.name.includes("livekit-server") && c.state === "running"
    );
    const agentRunning = containers.some(
      (c) => c.name.includes("voice-agent") && c.state === "running"
    );

    const health: HealthStatus = {
      livekit_server: lkRunning,
      voice_agent: agentRunning,
      token_server: tokenOk,
      containers,
      agent_config: {
        LIVEKIT_LLM: extractEnvVar(compose, "LIVEKIT_LLM"),
        LIVEKIT_VOICE: extractEnvVar(compose, "LIVEKIT_VOICE"),
        HA_MCP_URL: extractEnvVar(compose, "HA_MCP_URL"),
      },
      token_server_config: {
        TTS_BACKEND: extractEnvVar(compose, "TTS_BACKEND"),
        TTS_VOICE: extractEnvVar(compose, "TTS_VOICE"),
      },
    };

    return NextResponse.json(health);
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : String(err);
    return NextResponse.json({ error: message }, { status: 500 });
  }
}
