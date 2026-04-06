"use client";

import { useEffect, useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Badge } from "@/components/ui/badge";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Separator } from "@/components/ui/separator";
import { RefreshCw, Save, Eye, EyeOff } from "lucide-react";
import { toast } from "sonner";

interface LkConfig {
  port: string;
  rtc_port_start: string;
  rtc_port_end: string;
  use_external_ip: string;
  log_level: string;
}

export default function LiveKitPage() {
  const qc = useQueryClient();
  const [showSecret, setShowSecret] = useState(false);

  const { data: config, isLoading } = useQuery<LkConfig>({
    queryKey: ["livekit-config"],
    queryFn: () => fetch("/api/livekit").then((r) => r.json()),
  });

  const { data: envData } = useQuery<{ entries: { key: string; value: string; masked: boolean; comment: boolean }[] }>({
    queryKey: ["env"],
    queryFn: () => fetch("/api/env").then((r) => r.json()),
  });

  const [port, setPort] = useState("");
  const [rtcStart, setRtcStart] = useState("");
  const [rtcEnd, setRtcEnd] = useState("");
  const [logLevel, setLogLevel] = useState("info");

  useEffect(() => {
    if (!config) return;
    setPort(config.port || "7880");
    setRtcStart(config.rtc_port_start || "50100");
    setRtcEnd(config.rtc_port_end || "50120");
    setLogLevel(config.log_level || "info");
  }, [config]);

  const apiKey = envData?.entries?.find((e) => e.key === "LIVEKIT_API_KEY")?.value ?? "";
  const apiSecret = envData?.entries?.find((e) => e.key === "LIVEKIT_API_SECRET")?.value ?? "";

  const saveMutation = useMutation({
    mutationFn: async (restart: boolean) => {
      const res = await fetch("/api/livekit", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          updates: {
            port,
            port_range_start: rtcStart,
            port_range_end: rtcEnd,
            level: logLevel,
          },
          restart,
        }),
      });
      if (!res.ok) throw new Error(await res.text());
      return res.json();
    },
    onSuccess: (_, restart) => {
      toast.success(restart ? "Saved & restarting LiveKit" : "Saved");
      qc.invalidateQueries({ queryKey: ["livekit-config"] });
    },
    onError: (e) => toast.error(`Save failed: ${e.message}`),
  });

  return (
    <div className="space-y-6 max-w-3xl">
      <div>
        <h2 className="text-2xl font-bold tracking-tight">LiveKit Server</h2>
        <p className="text-muted-foreground">
          WebRTC server configuration (livekit.yaml)
        </p>
      </div>

      {isLoading ? (
        <div className="flex items-center gap-2 text-muted-foreground">
          <RefreshCw className="h-4 w-4 animate-spin" /> Loading...
        </div>
      ) : (
        <>
          <Card>
            <CardHeader>
              <CardTitle>Server Settings</CardTitle>
              <CardDescription>Port and WebRTC configuration</CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="grid grid-cols-3 gap-4">
                <div className="space-y-2">
                  <Label>Server Port</Label>
                  <Input value={port} onChange={(e) => setPort(e.target.value)} />
                </div>
                <div className="space-y-2">
                  <Label>RTC Port Start</Label>
                  <Input value={rtcStart} onChange={(e) => setRtcStart(e.target.value)} />
                </div>
                <div className="space-y-2">
                  <Label>RTC Port End</Label>
                  <Input value={rtcEnd} onChange={(e) => setRtcEnd(e.target.value)} />
                </div>
              </div>
              <div className="space-y-2">
                <Label>Logging Level</Label>
                <Select value={logLevel} onValueChange={(v) => v && setLogLevel(v)}>
                  <SelectTrigger className="w-48">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {["debug", "info", "warn", "error"].map((l) => (
                      <SelectItem key={l} value={l}>{l}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>API Credentials</CardTitle>
              <CardDescription>
                Stored in .env on the LXC. Used by all services.
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <Label>API Key</Label>
                <Input value={apiKey} readOnly className="font-mono" />
              </div>
              <div className="space-y-2">
                <Label>API Secret</Label>
                <div className="flex gap-2">
                  <Input
                    value={showSecret ? apiSecret : "••••••••••••••••"}
                    readOnly
                    type={showSecret ? "text" : "password"}
                    className="font-mono"
                  />
                  <Button
                    variant="ghost"
                    size="icon"
                    onClick={() => setShowSecret(!showSecret)}
                  >
                    {showSecret ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                  </Button>
                </div>
                <p className="text-xs text-muted-foreground">
                  Edit via System → Environment Variables
                </p>
              </div>
            </CardContent>
          </Card>

          <Separator />
          <div className="flex gap-3">
            <Button
              variant="outline"
              onClick={() => saveMutation.mutate(false)}
              disabled={saveMutation.isPending}
            >
              <Save className="mr-2 h-4 w-4" /> Save Only
            </Button>
            <Button
              onClick={() => saveMutation.mutate(true)}
              disabled={saveMutation.isPending}
            >
              <RefreshCw className="mr-2 h-4 w-4" /> Save &amp; Restart LiveKit
            </Button>
          </div>
        </>
      )}
    </div>
  );
}
