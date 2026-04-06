"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import {
  CheckCircle2,
  XCircle,
  RefreshCw,
  Tablet,
  Phone,
  BrainCircuit,
} from "lucide-react";
import { toast } from "sonner";
import type { HealthStatus, Device, ActiveCall } from "@/lib/types";

const TS_URL =
  process.env.NEXT_PUBLIC_TOKEN_SERVER_URL ?? "http://192.168.211.153:8090";

function StatusDot({ ok }: { ok: boolean }) {
  return ok ? (
    <CheckCircle2 className="h-5 w-5 text-green-500" />
  ) : (
    <XCircle className="h-5 w-5 text-destructive" />
  );
}

export default function DashboardPage() {
  const qc = useQueryClient();

  const { data: health, isLoading: healthLoading } = useQuery<HealthStatus>({
    queryKey: ["health"],
    queryFn: () => fetch("/api/health").then((r) => r.json()),
  });

  const { data: devicesData } = useQuery<{ devices: Device[] }>({
    queryKey: ["devices"],
    queryFn: () => fetch(`${TS_URL}/devices`).then((r) => r.json()),
  });

  const { data: callsData } = useQuery<{ calls: ActiveCall[] }>({
    queryKey: ["calls"],
    queryFn: () => fetch(`${TS_URL}/calls`).then((r) => r.json()),
  });

  const restartMutation = useMutation({
    mutationFn: (service?: string) =>
      fetch("/api/docker", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ action: "restart", service }),
      }).then((r) => r.json()),
    onSuccess: () => {
      toast.success("Restart triggered");
      qc.invalidateQueries({ queryKey: ["health"] });
    },
    onError: (e) => toast.error(`Restart failed: ${e.message}`),
  });

  const devices = devicesData?.devices ?? [];
  const onlineDevices = devices.filter((d) => d.status === "online");
  const calls = callsData?.calls ?? [];

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-2xl font-bold tracking-tight">Dashboard</h2>
        <p className="text-muted-foreground">
          System overview and quick actions
        </p>
      </div>

      {/* Health cards */}
      <div className="grid gap-4 md:grid-cols-3">
        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium">LiveKit Server</CardTitle>
            {healthLoading ? (
              <RefreshCw className="h-4 w-4 animate-spin text-muted-foreground" />
            ) : (
              <StatusDot ok={health?.livekit_server ?? false} />
            )}
          </CardHeader>
          <CardContent>
            <p className="text-xs text-muted-foreground">
              {health?.livekit_server ? "Running" : "Offline"}
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium">Voice Agent</CardTitle>
            {healthLoading ? (
              <RefreshCw className="h-4 w-4 animate-spin text-muted-foreground" />
            ) : (
              <StatusDot ok={health?.voice_agent ?? false} />
            )}
          </CardHeader>
          <CardContent>
            <p className="text-xs text-muted-foreground">
              {health?.agent_config?.LIVEKIT_LLM
                ? `Model: ${health.agent_config.LIVEKIT_LLM}`
                : "—"}
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium">Token Server</CardTitle>
            {healthLoading ? (
              <RefreshCw className="h-4 w-4 animate-spin text-muted-foreground" />
            ) : (
              <StatusDot ok={health?.token_server ?? false} />
            )}
          </CardHeader>
          <CardContent>
            <p className="text-xs text-muted-foreground">
              {health?.token_server_config?.TTS_BACKEND
                ? `TTS: ${health.token_server_config.TTS_BACKEND} / ${health.token_server_config.TTS_VOICE}`
                : "—"}
            </p>
          </CardContent>
        </Card>
      </div>

      {/* Stats row */}
      <div className="grid gap-4 md:grid-cols-3">
        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium">Devices</CardTitle>
            <Tablet className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{onlineDevices.length}</div>
            <p className="text-xs text-muted-foreground">
              {devices.length} registered, {onlineDevices.length} online
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium">Active Calls</CardTitle>
            <Phone className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{calls.length}</div>
            <p className="text-xs text-muted-foreground">
              {calls.filter((c) => c.status === "ringing").length} ringing,{" "}
              {calls.filter((c) => c.status === "active").length} in progress
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium">Current AI Model</CardTitle>
            <BrainCircuit className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">
              {health?.agent_config?.LIVEKIT_LLM ?? "—"}
            </div>
            <p className="text-xs text-muted-foreground">
              Voice: {health?.agent_config?.LIVEKIT_VOICE ?? "—"}
            </p>
          </CardContent>
        </Card>
      </div>

      <Separator />

      {/* Quick actions */}
      <Card>
        <CardHeader>
          <CardTitle>Quick Actions</CardTitle>
          <CardDescription>Restart services on the LXC host</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-wrap gap-3">
          <Button
            variant="outline"
            size="sm"
            disabled={restartMutation.isPending}
            onClick={() => restartMutation.mutate("voice-agent")}
          >
            <RefreshCw className="mr-2 h-4 w-4" />
            Restart Voice Agent
          </Button>
          <Button
            variant="outline"
            size="sm"
            disabled={restartMutation.isPending}
            onClick={() => restartMutation.mutate("token-server")}
          >
            <RefreshCw className="mr-2 h-4 w-4" />
            Restart Token Server
          </Button>
          <Button
            variant="outline"
            size="sm"
            disabled={restartMutation.isPending}
            onClick={() => restartMutation.mutate("livekit-server")}
          >
            <RefreshCw className="mr-2 h-4 w-4" />
            Restart LiveKit
          </Button>
          <Button
            variant="destructive"
            size="sm"
            disabled={restartMutation.isPending}
            onClick={() => restartMutation.mutate(undefined)}
          >
            <RefreshCw className="mr-2 h-4 w-4" />
            Restart All Services
          </Button>
        </CardContent>
      </Card>

      {/* Container status */}
      {health?.containers && health.containers.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Containers</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-2">
              {health.containers.map((c) => (
                <div
                  key={c.name}
                  className="flex items-center justify-between text-sm"
                >
                  <span className="font-mono">{c.name}</span>
                  <div className="flex items-center gap-2">
                    <span className="text-muted-foreground">{c.status}</span>
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
          </CardContent>
        </Card>
      )}
    </div>
  );
}
