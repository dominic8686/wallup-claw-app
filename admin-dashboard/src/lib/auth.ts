import { cookies } from "next/headers";
import { NextRequest, NextResponse } from "next/server";
import crypto from "crypto";

// ---------------------------------------------------------------------------
// Simple password-based auth with httpOnly session cookie
// ---------------------------------------------------------------------------

const DASHBOARD_PASSWORD = process.env.DASHBOARD_PASSWORD ?? "";
const SESSION_COOKIE = "wc_session";
const SESSION_MAX_AGE = 60 * 60 * 24 * 7; // 7 days

function hashToken(password: string): string {
  return crypto.createHash("sha256").update(password).digest("hex");
}

/** Validate a password and return a session token. */
export function createSession(password: string): string | null {
  if (!DASHBOARD_PASSWORD) return "no-auth"; // Auth disabled if no password set
  if (password !== DASHBOARD_PASSWORD) return null;
  return hashToken(password + Date.now().toString());
}

/** Check if auth is enabled. */
export function isAuthEnabled(): boolean {
  return !!DASHBOARD_PASSWORD;
}

/** Set the session cookie. */
export async function setSessionCookie(token: string) {
  const cookieStore = await cookies();
  cookieStore.set(SESSION_COOKIE, token, {
    httpOnly: true,
    secure: false, // LAN-only
    sameSite: "lax",
    maxAge: SESSION_MAX_AGE,
    path: "/",
  });
}

/** Check if the current request has a valid session. */
export async function isAuthenticated(): Promise<boolean> {
  if (!DASHBOARD_PASSWORD) return true; // Auth disabled
  const cookieStore = await cookies();
  const session = cookieStore.get(SESSION_COOKIE);
  return !!session?.value;
}

/** Clear the session cookie. */
export async function clearSession() {
  const cookieStore = await cookies();
  cookieStore.delete(SESSION_COOKIE);
}

/** Middleware helper: returns 401 if not authenticated. */
export async function requireAuth(): Promise<NextResponse | null> {
  if (!DASHBOARD_PASSWORD) return null; // Auth disabled
  const authed = await isAuthenticated();
  if (!authed) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }
  return null;
}

/** Middleware for route protection (used in middleware.ts). */
export function authMiddleware(request: NextRequest): NextResponse | null {
  if (!DASHBOARD_PASSWORD) return null;

  const session = request.cookies.get(SESSION_COOKIE);
  const isLoginPage = request.nextUrl.pathname === "/login";
  const isApiAuth = request.nextUrl.pathname === "/api/auth";

  if (isLoginPage || isApiAuth) return null; // Allow access

  if (!session?.value) {
    // API routes return 401, pages redirect to login
    if (request.nextUrl.pathname.startsWith("/api/")) {
      return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
    }
    return NextResponse.redirect(new URL("/login", request.url));
  }

  return null;
}
