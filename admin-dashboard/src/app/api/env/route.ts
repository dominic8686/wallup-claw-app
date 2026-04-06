import { NextRequest, NextResponse } from "next/server";
import { readEnvFile, sshWriteFile } from "@/lib/ssh";

const COMPOSE_DIR = process.env.COMPOSE_DIR ?? "/opt/livekit-voice-agent";
const SENSITIVE_PATTERNS = ["KEY", "SECRET", "TOKEN", "PASS", "PASSWORD"];

function isSensitive(key: string): boolean {
  const upper = key.toUpperCase();
  return SENSITIVE_PATTERNS.some((p) => upper.includes(p));
}

function maskValue(value: string): string {
  if (value.length <= 8) return "••••••••";
  return value.slice(0, 4) + "•".repeat(Math.min(value.length - 4, 20));
}

interface EnvEntry {
  key: string;
  value: string;
  masked: boolean;
  comment: boolean;
  raw: string;
}

function parseEnv(content: string): EnvEntry[] {
  return content.split("\n").map((line) => {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) {
      return { key: "", value: "", masked: false, comment: true, raw: line };
    }
    const eqIdx = trimmed.indexOf("=");
    if (eqIdx === -1) {
      return { key: "", value: "", masked: false, comment: true, raw: line };
    }
    const key = trimmed.slice(0, eqIdx);
    const value = trimmed.slice(eqIdx + 1);
    const sensitive = isSensitive(key);
    return {
      key,
      value: sensitive ? maskValue(value) : value,
      masked: sensitive,
      comment: false,
      raw: line,
    };
  });
}

export async function GET() {
  try {
    const content = await readEnvFile();
    const entries = parseEnv(content);
    return NextResponse.json({ entries, raw: content });
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : String(err);
    return NextResponse.json({ error: message }, { status: 500 });
  }
}

export async function PUT(request: NextRequest) {
  try {
    const body = await request.json();
    const { updates } = body as { updates: Record<string, string> };

    let content = await readEnvFile();

    for (const [key, value] of Object.entries(updates)) {
      const regex = new RegExp(`^(${key})=.*$`, "m");
      if (regex.test(content)) {
        content = content.replace(regex, `${key}=${value}`);
      } else {
        // Add new key at end
        content = content.trimEnd() + `\n${key}=${value}\n`;
      }
    }

    await sshWriteFile(`${COMPOSE_DIR}/.env`, content);
    return NextResponse.json({ ok: true });
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : String(err);
    return NextResponse.json({ error: message }, { status: 500 });
  }
}
