import { NextRequest, NextResponse } from "next/server";

const DASHBOARD_PASSWORD = process.env.DASHBOARD_PASSWORD ?? "";
const SESSION_COOKIE = "wc_session";

export function middleware(request: NextRequest) {
  if (!DASHBOARD_PASSWORD) return NextResponse.next();

  const session = request.cookies.get(SESSION_COOKIE);
  const { pathname } = request.nextUrl;

  // Allow login page, auth API, and static assets
  if (
    pathname === "/login" ||
    pathname === "/api/auth" ||
    pathname.startsWith("/_next/") ||
    pathname.startsWith("/favicon")
  ) {
    return NextResponse.next();
  }

  if (!session?.value) {
    if (pathname.startsWith("/api/")) {
      return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
    }
    return NextResponse.redirect(new URL("/login", request.url));
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
