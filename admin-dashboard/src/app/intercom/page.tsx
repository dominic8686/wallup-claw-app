"use client";

import { useState, useEffect, useRef } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Phone, RefreshCw, Save, History } from "lucide-react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { toast } from "sonner";
import type { ActiveCall, Device } from "@/lib/types";

interface CallHistoryEntry {
  from: string;
  to: string;
  room_name: string;
  started_at: number;
  seen_at: number;
  last_status: string;
}

const TS_URL =
  process.env.NEXT_PUBLIC_TOKEN_SERVER_URL ?? "http://192.168.211.153:8090";

function duration(startedAt: number): string {
  const secs = Math.floor(Date.now() / 1000 - startedAt);
  const m = Math.floor(secs / 60);
  const s = secs % 60;
  return m > 0 ? `${m}m ${s}s` : `${s}s`;
}

export default function IntercomPage() {
  const { data: callsData, isLoading: callsLoading } = useQuery<{
    calls: ActiveCall[];
  }>({
    queryKey: ["calls"],
    queryFn: () => fetch(`${TS_URL}/calls`).then((r) => r.json()),
    refetchInterval: 5_000,
  });

  const { data: devicesData } = useQuery<{ devices: Device[] }>({
    queryKey: ["devices"],
    queryFn: () => fetch(`${TS_URL}/devices`).then((r) => r.json()),
    refetchInterval: 10_000,
  });

  const calls = callsData?.calls ?? [];
  const devices = devicesData?.devices ?? [];
  const inCallDevices = devices.filter(
    (d) => d.call_state !== "idle" && d.call_state !== "do_not_disturb"
  );

  // Client-side call history accumulator
  const [callHistory, setCallHistory] = useState<CallHistoryEntry[]>([]);
  const seenCalls = useRef<Set<string>>(new Set());

  useEffect(() => {
    if (!calls.length) return;
    const now = Date.now() / 1000;
    const newEntries: CallHistoryEntry[] = [];
    for (const c of calls) {
      if (!seenCalls.current.has(c.room_name)) {
        seenCalls.current.add(c.room_name);
        newEntries.push({
          from: c.from,
          to: c.to,
          room_name: c.room_name,
          started_at: c.started_at,
          seen_at: now,
          last_status: c.status,
        });
      }
    }
    if (newEntries.length > 0) {
      setCallHistory((prev) => [...newEntries, ...prev].slice(0, 50));
    }
  }, [calls]);

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-2xl font-bold tracking-tight">Intercom</h2>
        <p className="text-muted-foreground">
          Active calls and device call states
        </p>
      </div>

      {/* Active calls */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Phone className="h-5 w-5" /> Active Calls
          </CardTitle>
          <CardDescription>
            Real-time view of intercom calls between tablets (refreshes every 5s)
          </CardDescription>
        </CardHeader>
        <CardContent>
          {callsLoading ? (
            <div className="flex items-center gap-2 text-muted-foreground py-4">
              <RefreshCw className="h-4 w-4 animate-spin" /> Loading...
            </div>
          ) : calls.length === 0 ? (
            <p className="text-muted-foreground py-4">
              No active calls right now.
            </p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>From</TableHead>
                  <TableHead>To</TableHead>
                  <TableHead>Room</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Duration</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {calls.map((c) => (
                  <TableRow key={c.room_name}>
                    <TableCell className="font-mono text-xs">
                      {c.from}
                    </TableCell>
                    <TableCell className="font-mono text-xs">{c.to}</TableCell>
                    <TableCell className="font-mono text-xs">
                      {c.room_name}
                    </TableCell>
                    <TableCell>
                      <Badge
                        variant={
                          c.status === "active" ? "default" : "secondary"
                        }
                      >
                        {c.status}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-muted-foreground text-xs">
                      {duration(c.started_at)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {/* Devices in-call */}
      <Card>
        <CardHeader>
          <CardTitle>Device Call States</CardTitle>
          <CardDescription>
            Devices currently involved in a call or ringing
          </CardDescription>
        </CardHeader>
        <CardContent>
          {inCallDevices.length === 0 ? (
            <p className="text-muted-foreground py-4">
              All devices are idle.
            </p>
          ) : (
            <div className="space-y-2">
              {inCallDevices.map((d) => (
                <div
                  key={d.device_id}
                  className="flex items-center justify-between text-sm border rounded-md p-3"
                >
                  <div>
                    <span className="font-mono">{d.device_id}</span>
                    {d.display_name !== d.device_id && (
                      <span className="text-muted-foreground ml-2">
                        ({d.display_name})
                      </span>
                    )}
                  </div>
                  <Badge>{d.call_state.replace("_", " ")}</Badge>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      {/* Call history */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <History className="h-5 w-5" /> Call History
          </CardTitle>
          <CardDescription>
            Calls observed during this browser session (not persisted)
          </CardDescription>
        </CardHeader>
        <CardContent>
          {callHistory.length === 0 ? (
            <p className="text-muted-foreground py-4">
              No calls observed yet. History accumulates while this page is open.
            </p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>From</TableHead>
                  <TableHead>To</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Started</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {callHistory.map((h) => (
                  <TableRow key={h.room_name}>
                    <TableCell className="font-mono text-xs">{h.from}</TableCell>
                    <TableCell className="font-mono text-xs">{h.to}</TableCell>
                    <TableCell>
                      <Badge variant="secondary">{h.last_status}</Badge>
                    </TableCell>
                    <TableCell className="text-muted-foreground text-xs">
                      {new Date(h.started_at * 1000).toLocaleTimeString()}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {/* Editable timeouts */}
      <IntercomSettings />
    </div>
  );
}

function IntercomSettings() {
  const qc = useQueryClient();
  const [ringTimeout, setRingTimeout] = useState("60");
  const [staleTimeout, setStaleTimeout] = useState("45");
  const [pollTimeout, setPollTimeout] = useState("25");

  const saveMutation = useMutation({
    mutationFn: async () => {
      const res = await fetch("/api/config", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          updates: {
            CALL_RING_TIMEOUT: ringTimeout,
            STALE_TIMEOUT: staleTimeout,
            LONG_POLL_TIMEOUT: pollTimeout,
          },
          restart: "token-server",
        }),
      });
      if (!res.ok) throw new Error(await res.text());
      return res.json();
    },
    onSuccess: () => {
      toast.success("Saved & restarting token server");
      qc.invalidateQueries({ queryKey: ["config"] });
    },
    onError: (e) => toast.error(`Save failed: ${e.message}`),
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle>Intercom Settings</CardTitle>
        <CardDescription>
          Timeouts for the token server. Requires restart to take effect.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid grid-cols-3 gap-4">
          <div className="space-y-2">
            <Label>Ring Timeout (s)</Label>
            <Input value={ringTimeout} onChange={(e) => setRingTimeout(e.target.value)} />
          </div>
          <div className="space-y-2">
            <Label>Stale Timeout (s)</Label>
            <Input value={staleTimeout} onChange={(e) => setStaleTimeout(e.target.value)} />
          </div>
          <div className="space-y-2">
            <Label>Long-poll Timeout (s)</Label>
            <Input value={pollTimeout} onChange={(e) => setPollTimeout(e.target.value)} />
          </div>
        </div>
        <Button
          size="sm"
          onClick={() => saveMutation.mutate()}
          disabled={saveMutation.isPending}
        >
          <Save className="mr-2 h-4 w-4" /> Save & Restart Token Server
        </Button>
      </CardContent>
    </Card>
  );
}
