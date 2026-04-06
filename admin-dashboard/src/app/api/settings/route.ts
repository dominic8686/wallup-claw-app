import { NextRequest, NextResponse } from "next/server";
import { readSettings, writeSettings, readAuditLog, appendAudit } from "@/lib/settings";

export async function GET(request: NextRequest) {
  const action = request.nextUrl.searchParams.get("action") ?? "settings";

  try {
    if (action === "audit") {
      return NextResponse.json({ entries: readAuditLog() });
    }
    return NextResponse.json(readSettings());
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : String(err);
    return NextResponse.json({ error: message }, { status: 500 });
  }
}

export async function PUT(request: NextRequest) {
  try {
    const body = await request.json();
    const settings = readSettings();

    if (body.promptPresets) {
      settings.promptPresets = body.promptPresets;
    }

    writeSettings(settings);
    appendAudit("settings_updated", { changed: Object.keys(body) });

    return NextResponse.json({ ok: true });
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : String(err);
    return NextResponse.json({ error: message }, { status: 500 });
  }
}
