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
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Pencil, RefreshCw, Unplug } from "lucide-react";
import { toast } from "sonner";
import { Switch } from "@/components/ui/switch";
import type { Device } from "@/lib/types";

const TS_URL =
  process.env.NEXT_PUBLIC_TOKEN_SERVER_URL ?? "http://192.168.211.153:8090";

function timeAgo(ts: number): string {
  if (!ts) return "never";
  const seconds = Math.floor(Date.now() / 1000 - ts);
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  return `${Math.floor(seconds / 3600)}h ago`;
}

function callStateBadge(state: Device["call_state"]) {
  const variants: Record<string, "default" | "secondary" | "destructive" | "outline"> = {
    idle: "secondary",
    calling: "default",
    ringing: "default",
    in_call: "default",
    do_not_disturb: "destructive",
  };
  return <Badge variant={variants[state] ?? "secondary"}>{state.replace("_", " ")}</Badge>;
}

export default function DevicesPage() {
  const qc = useQueryClient();
  const [editDevice, setEditDevice] = useState<Device | null>(null);
  const [editName, setEditName] = useState("");
  const [editRoom, setEditRoom] = useState("");
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [bulkRoom, setBulkRoom] = useState("");

  const { data, isLoading, isError } = useQuery<{ devices: Device[] }>({
    queryKey: ["devices"],
    queryFn: () => fetch(`${TS_URL}/devices`).then((r) => r.json()),
    refetchInterval: 10_000,
  });

  const configureMutation = useMutation({
    mutationFn: async ({
      deviceId,
      settings,
    }: {
      deviceId: string;
      settings: Record<string, string>;
    }) => {
      const res = await fetch(`${TS_URL}/configure`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ device_id: deviceId, settings }),
      });
      if (!res.ok) throw new Error(await res.text());
      return res.json();
    },
    onSuccess: () => {
      toast.success("Config pushed to device");
      setEditDevice(null);
      qc.invalidateQueries({ queryKey: ["devices"] });
    },
    onError: (e) => toast.error(`Failed: ${e.message}`),
  });

  const disconnectMutation = useMutation({
    mutationFn: async (deviceId: string) => {
      // Send a disconnect signal via the token server
      const res = await fetch(`${TS_URL}/signal`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ type: "force_disconnect", from: "admin", to: deviceId }),
      });
      if (!res.ok) throw new Error(await res.text());
      return res.json();
    },
    onSuccess: () => {
      toast.success("Disconnect signal sent");
      qc.invalidateQueries({ queryKey: ["devices"] });
    },
    onError: (e) => toast.error(`Failed: ${e.message}`),
  });

  const bulkConfigMutation = useMutation({
    mutationFn: async ({ deviceIds, settings }: { deviceIds: string[]; settings: Record<string, string> }) => {
      await Promise.all(
        deviceIds.map((id) =>
          fetch(`${TS_URL}/configure`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ device_id: id, settings }),
          })
        )
      );
    },
    onSuccess: () => {
      toast.success(`Config pushed to ${selected.size} devices`);
      setSelected(new Set());
      setBulkRoom("");
      qc.invalidateQueries({ queryKey: ["devices"] });
    },
    onError: (e) => toast.error(`Bulk config failed: ${e.message}`),
  });

  const devices = data?.devices ?? [];
  const online = devices.filter((d) => d.status === "online").length;
  const allSelected = devices.length > 0 && selected.size === devices.length;

  function toggleSelect(id: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  }

  function toggleAll() {
    if (allSelected) {
      setSelected(new Set());
    } else {
      setSelected(new Set(devices.map((d) => d.device_id)));
    }
  }

  function openEdit(d: Device) {
    setEditDevice(d);
    setEditName(d.display_name);
    setEditRoom(d.room_location);
  }

  function handleSave() {
    if (!editDevice) return;
    configureMutation.mutate({
      deviceId: editDevice.device_id,
      settings: {
        display_name: editName,
        room_location: editRoom,
      },
    });
  }

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-2xl font-bold tracking-tight">Device Fleet</h2>
        <p className="text-muted-foreground">
          {devices.length} registered tablets, {online} online
        </p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Registered Devices</CardTitle>
          <CardDescription>
            Click a device to edit its name and room assignment
          </CardDescription>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <div className="flex items-center gap-2 text-muted-foreground py-4">
              <RefreshCw className="h-4 w-4 animate-spin" /> Loading...
            </div>
          ) : isError ? (
            <p className="text-destructive">
              Failed to reach token server at {TS_URL}
            </p>
          ) : devices.length === 0 ? (
            <p className="text-muted-foreground py-4">
              No devices registered yet. Connect a tablet to see it here.
            </p>
          ) : (
            <>
            {/* Bulk actions bar */}
            {selected.size > 0 && (
              <div className="flex items-center gap-3 p-3 border rounded-md bg-muted/50 mb-4">
                <span className="text-sm font-medium">{selected.size} selected</span>
                <Input
                  className="w-48"
                  placeholder="Assign room..."
                  value={bulkRoom}
                  onChange={(e) => setBulkRoom(e.target.value)}
                />
                <Button
                  size="sm"
                  disabled={!bulkRoom.trim() || bulkConfigMutation.isPending}
                  onClick={() =>
                    bulkConfigMutation.mutate({
                      deviceIds: Array.from(selected),
                      settings: { room_location: bulkRoom },
                    })
                  }
                >
                  Apply Room
                </Button>
                <Button size="sm" variant="ghost" onClick={() => setSelected(new Set())}>
                  Clear
                </Button>
              </div>
            )}
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-10">
                    <Switch checked={allSelected} onCheckedChange={toggleAll} />
                  </TableHead>
                  <TableHead>Device ID</TableHead>
                  <TableHead>Display Name</TableHead>
                  <TableHead>Room</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Call State</TableHead>
                  <TableHead>Last Seen</TableHead>
                  <TableHead className="w-20"></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {devices.map((d) => (
                  <TableRow key={d.device_id}>
                    <TableCell>
                      <Switch
                        checked={selected.has(d.device_id)}
                        onCheckedChange={() => toggleSelect(d.device_id)}
                      />
                    </TableCell>
                    <TableCell className="font-mono text-xs">
                      {d.device_id}
                    </TableCell>
                    <TableCell>{d.display_name}</TableCell>
                    <TableCell>{d.room_location || "—"}</TableCell>
                    <TableCell>
                      <Badge
                        variant={
                          d.status === "online" ? "default" : "secondary"
                        }
                      >
                        {d.status}
                      </Badge>
                    </TableCell>
                    <TableCell>{callStateBadge(d.call_state)}</TableCell>
                    <TableCell className="text-muted-foreground text-xs">
                      {timeAgo(d.last_seen)}
                    </TableCell>
                    <TableCell className="flex gap-1">
                      <Button variant="ghost" size="icon" onClick={() => openEdit(d)}>
                        <Pencil className="h-4 w-4" />
                      </Button>
                      {d.status === "online" && (
                        <Button
                          variant="ghost"
                          size="icon"
                          onClick={() => disconnectMutation.mutate(d.device_id)}
                          title="Force disconnect"
                        >
                          <Unplug className="h-4 w-4 text-destructive" />
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            </>
          )}
        </CardContent>
      </Card>

      {/* Edit dialog */}
      <Dialog
        open={!!editDevice}
        onOpenChange={(open) => !open && setEditDevice(null)}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              Configure {editDevice?.device_id}
            </DialogTitle>
            <DialogDescription>
              Changes are pushed to the device on its next heartbeat
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-2">
            <div className="space-y-2">
              <Label>Display Name</Label>
              <Input
                value={editName}
                onChange={(e) => setEditName(e.target.value)}
              />
            </div>
            <div className="space-y-2">
              <Label>Room Location</Label>
              <Input
                value={editRoom}
                onChange={(e) => setEditRoom(e.target.value)}
                placeholder="e.g. Kitchen, Living Room"
              />
            </div>
            <div className="flex justify-end gap-2 pt-2">
              <Button variant="outline" onClick={() => setEditDevice(null)}>
                Cancel
              </Button>
              <Button
                onClick={handleSave}
                disabled={configureMutation.isPending}
              >
                Push Config
              </Button>
            </div>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}
