import { NextRequest, NextResponse } from "next/server";
import { dockerRestart, dockerLogs, dockerRebuild, dockerPs } from "@/lib/ssh";

export async function GET(request: NextRequest) {
  const action = request.nextUrl.searchParams.get("action") ?? "ps";
  const service = request.nextUrl.searchParams.get("service") ?? "";
  const lines = parseInt(request.nextUrl.searchParams.get("lines") ?? "100", 10);

  try {
    let result: string;
    switch (action) {
      case "ps":
        result = await dockerPs();
        break;
      case "logs":
        result = await dockerLogs(service, lines);
        break;
      default:
        return NextResponse.json({ error: `Unknown action: ${action}` }, { status: 400 });
    }
    return NextResponse.json({ output: result });
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : String(err);
    return NextResponse.json({ error: message }, { status: 500 });
  }
}

export async function POST(request: NextRequest) {
  try {
    const body = await request.json();
    const { action, service } = body as { action: string; service?: string };

    let result: string;
    switch (action) {
      case "restart":
        result = await dockerRestart(service);
        break;
      case "rebuild":
        result = await dockerRebuild(service);
        break;
      default:
        return NextResponse.json({ error: `Unknown action: ${action}` }, { status: 400 });
    }
    return NextResponse.json({ ok: true, output: result });
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : String(err);
    return NextResponse.json({ error: message }, { status: 500 });
  }
}
