"use client";

import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Activity, Radio, Square, Mic } from "lucide-react";
import type { Device, ActiveCall } from "@/lib/types";

const TS_URL =
  process.env.NEXT_PUBLIC_TOKEN_SERVER_URL ?? "http://192.168.211.153:8090";

const SERVICES = ["voice-agent", "token-server", "livekit-server"];

export default function MonitorPage() {
  const [streamService, setStreamService] = useState("voice-agent");
  const [streaming, setStreaming] = useState(false);
  const [logBuffer, setLogBuffer] = useState("");
  const logRef = useRef<HTMLPreElement>(null);
  const abortRef = useRef<AbortController | null>(null);

  const { data: devicesData } = useQuery<{ devices: Device[] }>({
    queryKey: ["devices"],
    queryFn: () => fetch(`${TS_URL}/devices`).then((r) => r.json()),
    refetchInterval: 5_000,
  });

  const { data: callsData } = useQuery<{ calls: ActiveCall[] }>({
    queryKey: ["calls"],
    queryFn: () => fetch(`${TS_URL}/calls`).then((r) => r.json()),
    refetchInterval: 5_000,
  });

  const { data: roomsData } = useQuery<{ rooms?: { name: string; num_participants: number }[] }>({
    queryKey: ["livekit-rooms"],
    queryFn: () => fetch("/api/livekit?action=rooms").then((r) => r.json()).catch(() => ({ rooms: [] })),
    refetchInterval: 10_000,
  });

  const devices = devicesData?.devices ?? [];
  const calls = callsData?.calls ?? [];
  const rooms = roomsData?.rooms ?? [];

  function startStream() {
    if (streaming) return;
    setLogBuffer("");
    setStreaming(true);
    const controller = new AbortController();
    abortRef.current = controller;

    const eventSource = new EventSource(
      `/api/logs-stream?service=${streamService}`
    );
    eventSource.onmessage = (event) => {
      if (event.data === "[DONE]") {
        eventSource.close();
        setStreaming(false);
        return;
      }
      try {
        const text = JSON.parse(event.data);
        if (typeof text === "string") {
          setLogBuffer((prev) => prev + text);
        }
      } catch {
        // ignore parse errors
      }
    };
    eventSource.onerror = () => {
      eventSource.close();
      setStreaming(false);
    };

    // Store close handler
    controller.signal.addEventListener("abort", () => {
      eventSource.close();
      setStreaming(false);
    });
  }

  function stopStream() {
    abortRef.current?.abort();
    setStreaming(false);
  }

  // Auto-scroll
  useEffect(() => {
    if (logRef.current) {
      logRef.current.scrollTop = logRef.current.scrollHeight;
    }
  }, [logBuffer]);

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-2xl font-bold tracking-tight">Live Monitor</h2>
        <p className="text-muted-foreground">
          Real-time device status, calls, and log streaming
        </p>
      </div>

      {/* Live device status */}
      <div className="grid gap-4 md:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Activity className="h-5 w-5" /> Devices
              <Badge variant="secondary" className="ml-auto">
                {devices.filter((d) => d.status === "online").length} online
              </Badge>
            </CardTitle>
            <CardDescription>Refreshes every 5 seconds</CardDescription>
          </CardHeader>
          <CardContent>
            {devices.length === 0 ? (
              <p className="text-muted-foreground text-sm">No devices</p>
            ) : (
              <div className="space-y-2">
                {devices.map((d) => (
                  <div
                    key={d.device_id}
                    className="flex items-center justify-between text-sm"
                  >
                    <div className="flex items-center gap-2">
                      <span
                        className={`h-2 w-2 rounded-full ${
                          d.status === "online"
                            ? "bg-green-500"
                            : "bg-muted-foreground"
                        }`}
                      />
                      <span className="font-mono text-xs">{d.device_id}</span>
                      {d.display_name !== d.device_id && (
                        <span className="text-muted-foreground text-xs">
                          {d.display_name}
                        </span>
                      )}
                    </div>
                    {d.call_state !== "idle" && (
                      <Badge variant="outline" className="text-xs">
                        {d.call_state.replace("_", " ")}
                      </Badge>
                    )}
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Radio className="h-5 w-5" /> Active Calls
              <Badge variant="secondary" className="ml-auto">
                {calls.length}
              </Badge>
            </CardTitle>
            <CardDescription>Refreshes every 5 seconds</CardDescription>
          </CardHeader>
          <CardContent>
            {calls.length === 0 ? (
              <p className="text-muted-foreground text-sm">No active calls</p>
            ) : (
              <div className="space-y-2">
                {calls.map((c) => (
                  <div
                    key={c.room_name}
                    className="flex items-center justify-between text-sm border rounded-md p-2"
                  >
                    <span className="font-mono text-xs">
                      {c.from} → {c.to}
                    </span>
                    <Badge
                      variant={
                        c.status === "active" ? "default" : "secondary"
                      }
                    >
                      {c.status}
                    </Badge>
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      {/* Active voice sessions */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Mic className="h-5 w-5" /> Voice Sessions
            <Badge variant="secondary" className="ml-auto">
              {rooms.length} rooms
            </Badge>
          </CardTitle>
          <CardDescription>LiveKit rooms with connected participants (10s refresh)</CardDescription>
        </CardHeader>
        <CardContent>
          {rooms.length === 0 ? (
            <p className="text-muted-foreground text-sm">No active rooms</p>
          ) : (
            <div className="space-y-2">
              {rooms.map((r) => (
                <div
                  key={r.name}
                  className="flex items-center justify-between text-sm border rounded-md p-2"
                >
                  <span className="font-mono text-xs">{r.name}</span>
                  <Badge variant="outline">
                    {r.num_participants} participant{r.num_participants !== 1 ? "s" : ""}
                  </Badge>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      {/* Log stream */}
      <Card>
        <CardHeader>
          <CardTitle>Log Stream</CardTitle>
          <CardDescription>
            Live log tail via SSE (streams for ~2 minutes per session)
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex items-center gap-3">
<Select value={streamService} onValueChange={(v) => v && setStreamService(v)}>
              <SelectTrigger className="w-48">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {SERVICES.map((s) => (
                  <SelectItem key={s} value={s}>
                    {s}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {streaming ? (
              <Button variant="destructive" size="sm" onClick={stopStream}>
                <Square className="mr-2 h-4 w-4" /> Stop
              </Button>
            ) : (
              <Button size="sm" onClick={startStream}>
                <Activity className="mr-2 h-4 w-4" /> Start Stream
              </Button>
            )}
            {streaming && (
              <Badge variant="default" className="animate-pulse">
                LIVE
              </Badge>
            )}
          </div>
          <pre
            ref={logRef}
            className="bg-black text-green-400 text-xs p-4 rounded-md overflow-auto max-h-[500px] min-h-[200px] font-mono whitespace-pre-wrap"
          >
            {logBuffer || (streaming ? "Connecting..." : "Press Start Stream to begin")}
          </pre>
        </CardContent>
      </Card>
    </div>
  );
}
