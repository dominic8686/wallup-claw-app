"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import {
  LayoutDashboard,
  BrainCircuit,
  Tablet,
  Server,
  Home,
  Phone,
  Volume2,
  Activity,
  Radio,
  LogOut,
  Moon,
  Sun,
  Menu,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Sheet, SheetContent, SheetTrigger } from "@/components/ui/sheet";
import { useTheme } from "@/components/theme-provider";
import { useState } from "react";

const NAV_ITEMS = [
  { href: "/", label: "Dashboard", icon: LayoutDashboard },
  { href: "/models", label: "AI Models", icon: BrainCircuit },
  { href: "/devices", label: "Devices", icon: Tablet },
  { href: "/livekit", label: "LiveKit", icon: Radio },
  { href: "/system", label: "System", icon: Server },
  { href: "/home-assistant", label: "Home Assistant", icon: Home },
  { href: "/intercom", label: "Intercom", icon: Phone },
  { href: "/tts", label: "TTS", icon: Volume2 },
  { href: "/monitor", label: "Live Monitor", icon: Activity },
];

function NavLinks({ onClick }: { onClick?: () => void }) {
  const pathname = usePathname();
  return (
    <>
      {NAV_ITEMS.map(({ href, label, icon: Icon }) => {
        const active =
          href === "/" ? pathname === "/" : pathname.startsWith(href);
        return (
          <Link
            key={href}
            href={href}
            onClick={onClick}
            className={cn(
              "flex items-center gap-2 px-3 py-2 rounded-md text-sm transition-colors",
              active
                ? "bg-primary text-primary-foreground"
                : "hover:bg-accent text-muted-foreground hover:text-foreground"
            )}
          >
            <Icon className="h-4 w-4" />
            {label}
          </Link>
        );
      })}
    </>
  );
}

function SidebarFooter() {
  const router = useRouter();
  const { theme, setTheme } = useTheme();

  async function handleLogout() {
    await fetch("/api/auth", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ action: "logout" }),
    });
    router.push("/login");
    router.refresh();
  }

  return (
    <div className="p-2 border-t space-y-0.5">
      <button
        onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
        className="flex items-center gap-2 px-3 py-2 rounded-md text-sm w-full text-muted-foreground hover:bg-accent hover:text-foreground transition-colors"
      >
        {theme === "dark" ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
        {theme === "dark" ? "Light Mode" : "Dark Mode"}
      </button>
      <button
        onClick={handleLogout}
        className="flex items-center gap-2 px-3 py-2 rounded-md text-sm w-full text-muted-foreground hover:bg-accent hover:text-foreground transition-colors"
      >
        <LogOut className="h-4 w-4" />
        Sign Out
      </button>
    </div>
  );
}

export function Sidebar() {
  return (
    <aside className="hidden md:flex w-56 shrink-0 border-r bg-muted/30 flex-col">
      <div className="px-4 py-5 border-b">
        <h1 className="text-lg font-bold tracking-tight">Wallup Claw</h1>
        <p className="text-xs text-muted-foreground">Admin Portal</p>
      </div>
      <nav className="flex-1 p-2 space-y-0.5">
        <NavLinks />
      </nav>
      <SidebarFooter />
    </aside>
  );
}

export function MobileNav() {
  const [open, setOpen] = useState(false);
  return (
    <div className="md:hidden flex items-center border-b px-4 py-3">
      <Sheet open={open} onOpenChange={setOpen}>
        <SheetTrigger render={<Button variant="ghost" size="icon" />}>
          <Menu className="h-5 w-5" />
        </SheetTrigger>
        <SheetContent side="left" className="w-56 p-0">
          <div className="px-4 py-5 border-b">
            <h1 className="text-lg font-bold tracking-tight">Wallup Claw</h1>
            <p className="text-xs text-muted-foreground">Admin Portal</p>
          </div>
          <nav className="flex-1 p-2 space-y-0.5">
            <NavLinks onClick={() => setOpen(false)} />
          </nav>
          <SidebarFooter />
        </SheetContent>
      </Sheet>
      <span className="ml-3 text-sm font-semibold">Wallup Claw</span>
    </div>
  );
}
