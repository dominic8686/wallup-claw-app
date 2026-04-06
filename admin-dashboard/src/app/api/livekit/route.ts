import { NextRequest, NextResponse } from "next/server";
import { readLivekitYaml, sshWriteFile, sshExec } from "@/lib/ssh";

const COMPOSE_DIR = process.env.COMPOSE_DIR ?? "/opt/livekit-voice-agent";

/** Simple YAML key extractor (livekit.yaml is flat enough for regex). */
function extractYamlValue(yaml: string, key: string): string {
  const regex = new RegExp(`^\\s*${key}:\\s*(.+)$`, "m");
  const match = yaml.match(regex);
  return match ? match[1].trim() : "";
}

function setYamlValue(yaml: string, key: string, value: string): string {
  const regex = new RegExp(`^(\\s*${key}:\\s*)(.+)$`, "m");
  if (regex.test(yaml)) {
    return yaml.replace(regex, `$1${value}`);
  }
  return yaml;
}

export async function GET(request: NextRequest) {
  const action = request.nextUrl.searchParams.get("action") ?? "config";

  try {
    if (action === "rooms") {
      // Query LiveKit Server API for rooms via CLI
      const output = await sshExec(
        `docker exec $(docker ps -q -f name=livekit-server) /livekit-server --help 2>/dev/null; ` +
        `curl -s http://localhost:7880/twirp/livekit.RoomService/ListRooms ` +
        `-H 'Content-Type: application/json' -d '{}' 2>/dev/null || echo '{"rooms":[]}'`
      ).catch(() => '{"rooms":[]}');
      return NextResponse.json(JSON.parse(output || '{"rooms":[]}'));
    }

    const yaml = await readLivekitYaml();
    return NextResponse.json({
      port: extractYamlValue(yaml, "port"),
      rtc_port_start: extractYamlValue(yaml, "port_range_start"),
      rtc_port_end: extractYamlValue(yaml, "port_range_end"),
      use_external_ip: extractYamlValue(yaml, "use_external_ip"),
      log_level: extractYamlValue(yaml, "level"),
      raw: yaml,
    });
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
      restart?: boolean;
    };

    let yaml = await readLivekitYaml();

    for (const [key, value] of Object.entries(updates)) {
      yaml = setYamlValue(yaml, key, value);
    }

    await sshWriteFile(`${COMPOSE_DIR}/livekit.yaml`, yaml);

    if (restart) {
      await sshExec(
        `docker compose -f ${COMPOSE_DIR}/docker-compose.yml restart livekit-server`
      );
    }

    return NextResponse.json({ ok: true });
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : String(err);
    return NextResponse.json({ error: message }, { status: 500 });
  }
}
