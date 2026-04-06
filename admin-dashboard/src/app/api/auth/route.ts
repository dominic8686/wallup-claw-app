import { NextRequest, NextResponse } from "next/server";
import { createSession, setSessionCookie, clearSession, isAuthEnabled } from "@/lib/auth";

export async function POST(request: NextRequest) {
  try {
    const body = await request.json();
    const { password, action } = body as { password?: string; action?: string };

    if (action === "logout") {
      await clearSession();
      return NextResponse.json({ ok: true });
    }

    if (!isAuthEnabled()) {
      return NextResponse.json({ ok: true, authEnabled: false });
    }

    const token = createSession(password ?? "");
    if (!token) {
      return NextResponse.json({ error: "Invalid password" }, { status: 401 });
    }

    await setSessionCookie(token);
    return NextResponse.json({ ok: true });
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : String(err);
    return NextResponse.json({ error: message }, { status: 500 });
  }
}

export async function GET() {
  return NextResponse.json({ authEnabled: isAuthEnabled() });
}
