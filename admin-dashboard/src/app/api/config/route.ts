import { NextRequest, NextResponse } from "next/server";
import { readComposeFile, writeComposeFile, dockerRestart } from "@/lib/ssh";
import { appendAudit } from "@/lib/settings";

/** Extract all env var values from docker-compose.yml for a given service block. */
function extractEnvVars(compose: string, keys: string[]): Record<string, string> {
  const result: Record<string, string> = {};
  for (const key of keys) {
    const regex = new RegExp(`-\\s*${key}=(.*)`, "m");
    const match = compose.match(regex);
    result[key] = match ? match[1].trim() : "";
  }
  return result;
}

/** Replace an env var value in docker-compose.yml. */
function setEnvVar(compose: string, key: string, value: string): string {
  const regex = new RegExp(`(-\\s*${key}=)(.*)`, "m");
  if (regex.test(compose)) {
    return compose.replace(regex, `$1${value}`);
  }
  return compose;
}

const AGENT_KEYS = [
  "LIVEKIT_LLM",
  "LIVEKIT_VOICE",
  "HA_MCP_URL",
  "TOKEN_SERVER_URL",
  "DEVICE_POLL_INTERVAL",
];

const TOKEN_SERVER_KEYS = [
  "TTS_BACKEND",
  "TTS_VOICE",
  "LIVEKIT_EXTERNAL_URL",
];

export async function GET() {
  try {
    const compose = await readComposeFile();
    const agent = extractEnvVars(compose, AGENT_KEYS);
    const tokenServer = extractEnvVars(compose, TOKEN_SERVER_KEYS);
    return NextResponse.json({ agent, tokenServer, raw: compose });
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : String(err);
    return NextResponse.json({ error: message }, { status: 500 });
  }
}

export async function PUT(request: NextRequest) {
  try {
    const body = await request.json();
    const { updates, restart } = body as {
      updates: Record<string, string>;
      restart?: string; // service name or "all"
    };

    let compose = await readComposeFile();

    for (const [key, value] of Object.entries(updates)) {
      compose = setEnvVar(compose, key, value);
    }

    await writeComposeFile(compose);

    if (restart) {
      await dockerRestart(restart === "all" ? undefined : restart);
    }

    appendAudit("config_updated", { keys: Object.keys(updates), restart: restart ?? null });

    return NextResponse.json({ ok: true });
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : String(err);
    return NextResponse.json({ error: message }, { status: 500 });
  }
}
