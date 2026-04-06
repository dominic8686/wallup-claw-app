"use client";

import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
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
import { Separator } from "@/components/ui/separator";
import { RefreshCw, Terminal, RotateCcw, Hammer, Eye, EyeOff, Save } from "lucide-react";
import { toast } from "sonner";
import type { ContainerStatus } from "@/lib/types";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

const SERVICES = ["voice-agent", "token-server", "livekit-server"];

function parseContainers(raw: string): ContainerStatus[] {
  return raw
    .split("\n")
    .filter(Boolean)
    .map((line) => {
      try {
        const obj = JSON.parse(line);
        return {
          name: obj.Name ?? obj.Service ?? "unknown",
          state: (obj.State ?? "unknown").toLowerCase(),
          status: obj.Status ?? "",
          image: obj.Image ?? "",
        } as ContainerStatus;
      } catch {
        return null;
      }
    })
    .filter(Boolean) as ContainerStatus[];
}

export default function SystemPage() {
  const qc = useQueryClient();
  const [logService, setLogService] = useState("voice-agent");
  const [logLines, setLogLines] = useState("");

  const { data: psData, isLoading: psLoading } = useQuery<{ output: string }>({
    queryKey: ["docker-ps"],
    queryFn: () => fetch("/api/docker?action=ps").then((r) => r.json()),
    refetchInterval: 15_000,
  });

  const containers = psData?.output ? parseContainers(psData.output) : [];

  const logsMutation = useMutation({
    mutationFn: async (service: string) => {
      const res = await fetch(
        `/api/docker?action=logs&service=${service}&lines=150`
      );
      return res.json();
    },
    onSuccess: (data) => setLogLines(data.output ?? ""),
    onError: (e) => toast.error(`Logs failed: ${e.message}`),
  });

  const restartMutation = useMutation({
    mutationFn: async (service?: string) => {
      const res = await fetch("/api/docker", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ action: "restart", service }),
      });
      return res.json();
    },
    onSuccess: () => {
      toast.success("Restart triggered");
      qc.invalidateQueries({ queryKey: ["docker-ps"] });
      qc.invalidateQueries({ queryKey: ["health"] });
    },
    onError: (e) => toast.error(`Restart failed: ${e.message}`),
  });

  const rebuildMutation = useMutation({
    mutationFn: async (service?: string) => {
      const res = await fetch("/api/docker", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ action: "rebuild", service }),
      });
      return res.json();
    },
    onSuccess: () => {
      toast.success("Rebuild & restart triggered");
      qc.invalidateQueries({ queryKey: ["docker-ps"] });
    },
    onError: (e) => toast.error(`Rebuild failed: ${e.message}`),
  });

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-2xl font-bold tracking-tight">System</h2>
        <p className="text-muted-foreground">
          Docker containers, logs, and service management
        </p>
      </div>

      {/* Container status */}
      <Card>
        <CardHeader>
          <CardTitle>Containers</CardTitle>
          <CardDescription>
            Docker Compose services on the LXC host
          </CardDescription>
        </CardHeader>
        <CardContent>
          {psLoading ? (
            <div className="flex items-center gap-2 text-muted-foreground">
              <RefreshCw className="h-4 w-4 animate-spin" /> Loading...
            </div>
          ) : containers.length === 0 ? (
            <p className="text-muted-foreground">
              No containers found. Is the LXC SSH connection configured?
            </p>
          ) : (
            <div className="space-y-3">
              {containers.map((c) => (
                <div
                  key={c.name}
                  className="flex items-center justify-between border rounded-md p-3"
                >
                  <div>
                    <p className="font-mono text-sm">{c.name}</p>
                    <p className="text-xs text-muted-foreground">{c.image}</p>
                  </div>
                  <div className="flex items-center gap-3">
                    <span className="text-xs text-muted-foreground">
                      {c.status}
                    </span>
                    <Badge
                      variant={
                        c.state === "running" ? "default" : "destructive"
                      }
                    >
                      {c.state}
                    </Badge>
                  </div>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      {/* Service actions */}
      <Card>
        <CardHeader>
          <CardTitle>Service Actions</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {SERVICES.map((svc) => (
              <div key={svc} className="flex items-center gap-2">
                <span className="font-mono text-sm flex-1">{svc}</span>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={restartMutation.isPending}
                  onClick={() => restartMutation.mutate(svc)}
                >
                  <RotateCcw className="mr-1 h-3 w-3" /> Restart
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={rebuildMutation.isPending}
                  onClick={() => rebuildMutation.mutate(svc)}
                >
                  <Hammer className="mr-1 h-3 w-3" /> Rebuild
                </Button>
              </div>
            ))}
          </div>
          <Separator className="my-4" />
          <div className="flex gap-3">
            <Button
              variant="destructive"
              size="sm"
              disabled={restartMutation.isPending}
              onClick={() => restartMutation.mutate(undefined)}
            >
              <RotateCcw className="mr-2 h-4 w-4" />
              Restart All
            </Button>
            <Button
              variant="destructive"
              size="sm"
              disabled={rebuildMutation.isPending}
              onClick={() => rebuildMutation.mutate(undefined)}
            >
              <Hammer className="mr-2 h-4 w-4" />
              Rebuild &amp; Restart All
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* Logs viewer */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Terminal className="h-5 w-5" /> Logs
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex items-center gap-3">
<Select value={logService} onValueChange={(v) => v && setLogService(v)}>
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
            <Button
              size="sm"
              onClick={() => logsMutation.mutate(logService)}
              disabled={logsMutation.isPending}
            >
              {logsMutation.isPending ? (
                <RefreshCw className="mr-2 h-4 w-4 animate-spin" />
              ) : (
                <Terminal className="mr-2 h-4 w-4" />
              )}
              Fetch Logs
            </Button>
          </div>
          {logLines && (
            <pre className="bg-black text-green-400 text-xs p-4 rounded-md overflow-auto max-h-96 font-mono whitespace-pre-wrap">
              {logLines}
            </pre>
          )}
        </CardContent>
      </Card>

      {/* .env Editor */}
      <EnvEditor />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Inline .env file editor
// ---------------------------------------------------------------------------

interface EnvEntry {
  key: string;
  value: string;
  masked: boolean;
  comment: boolean;
  raw: string;
}

function EnvEditor() {
  const qc = useQueryClient();
  const [revealed, setRevealed] = useState<Set<string>>(new Set());
  const [edits, setEdits] = useState<Record<string, string>>({});

  const { data, isLoading } = useQuery<{ entries: EnvEntry[] }>({
    queryKey: ["env"],
    queryFn: () => fetch("/api/env").then((r) => r.json()),
  });

  const saveMutation = useMutation({
    mutationFn: async () => {
      const res = await fetch("/api/env", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ updates: edits }),
      });
      if (!res.ok) throw new Error(await res.text());
      return res.json();
    },
    onSuccess: () => {
      toast.success(".env saved");
      setEdits({});
      qc.invalidateQueries({ queryKey: ["env"] });
    },
    onError: (e) => toast.error(`Save failed: ${e.message}`),
  });

  const entries = data?.entries ?? [];
  const envVars = entries.filter((e) => !e.comment && e.key);
  const hasEdits = Object.keys(edits).length > 0;

  function toggleReveal(key: string) {
    setRevealed((prev) => {
      const next = new Set(prev);
      next.has(key) ? next.delete(key) : next.add(key);
      return next;
    });
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Environment Variables</CardTitle>
        <CardDescription>
          .env file on the LXC host. Sensitive values are masked.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {isLoading ? (
          <div className="flex items-center gap-2 text-muted-foreground">
            <RefreshCw className="h-4 w-4 animate-spin" /> Loading...
          </div>
        ) : (
          <>
            {envVars.map((entry) => (
              <div key={entry.key} className="flex items-center gap-2">
                <Label className="w-48 shrink-0 font-mono text-xs truncate" title={entry.key}>
                  {entry.key}
                </Label>
                <Input
                  className="font-mono text-xs"
                  type={entry.masked && !revealed.has(entry.key) ? "password" : "text"}
                  value={edits[entry.key] ?? entry.value}
                  onChange={(e) =>
                    setEdits((prev) => ({ ...prev, [entry.key]: e.target.value }))
                  }
                />
                {entry.masked && (
                  <Button
                    variant="ghost"
                    size="icon"
                    onClick={() => toggleReveal(entry.key)}
                  >
                    {revealed.has(entry.key) ? (
                      <EyeOff className="h-4 w-4" />
                    ) : (
                      <Eye className="h-4 w-4" />
                    )}
                  </Button>
                )}
              </div>
            ))}
            {hasEdits && (
              <div className="pt-3 flex gap-3">
                <Button
                  size="sm"
                  onClick={() => saveMutation.mutate()}
                  disabled={saveMutation.isPending}
                >
                  <Save className="mr-2 h-4 w-4" /> Save .env
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => setEdits({})}
                >
                  Discard
                </Button>
              </div>
            )}
          </>
        )}
      </CardContent>
    </Card>
  );
}
