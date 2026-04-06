import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import { Providers } from "@/components/providers";
import { Sidebar, MobileNav } from "@/components/sidebar";
import { Breadcrumbs } from "@/components/breadcrumbs";
import { Toaster } from "@/components/ui/sonner";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "Wallup Claw Admin Portal",
  description: "Wallup Claw Admin Portal",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="en"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased dark`}
      suppressHydrationWarning
    >
      <body className="min-h-full flex flex-col md:flex-row">
        <Providers>
          <Sidebar />
          <div className="flex-1 flex flex-col overflow-hidden">
            <MobileNav />
            <main className="flex-1 overflow-auto p-4 md:p-6">
              <Breadcrumbs />
              {children}
            </main>
          </div>
          <Toaster />
        </Providers>
      </body>
    </html>
  );
}
