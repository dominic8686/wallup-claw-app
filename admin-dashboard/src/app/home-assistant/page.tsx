"use client";

import { useEffect, useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Badge } from "@/components/ui/badge";
import { RefreshCw, Save, Plug, Search } from "lucide-react";
import { toast } from "sonner";

interface ConfigResponse {
  agent: Record<string, string>;
}

export default function HomeAssistantPage() {
  const qc = useQueryClient();

  const { data: config, isLoading } = useQuery<ConfigResponse>({
    queryKey: ["config"],
    queryFn: () => fetch("/api/config").then((r) => r.json()),
  });

  const [mcpUrl, setMcpUrl] = useState("");
  const [mcpToken, setMcpToken] = useState("");
  const [testResult, setTestResult] = useState<"ok" | "fail" | null>(null);
  const [mcpTools, setMcpTools] = useState<{ name: string; description: string }[]>([]);
  const [toolFilter, setToolFilter] = useState("");

  useEffect(() => {
    if (!config) return;
    setMcpUrl(config.agent.HA_MCP_URL || "");
  }, [config]);

  const saveMutation = useMutation({
    mutationFn: async () => {
      const updates: Record<string, string> = { HA_MCP_URL: mcpUrl };
      const res = await fetch("/api/config", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ updates, restart: "voice-agent" }),
      });
      if (!res.ok) throw new Error(await res.text());
      return res.json();
    },
    onSuccess: () => {
      toast.success("Saved & restarting agent");
      qc.invalidateQueries({ queryKey: ["config"] });
    },
    onError: (e) => toast.error(`Save failed: ${e.message}`),
  });

  async function testConnection() {
    setTestResult(null);
    try {
      const url = mcpUrl.replace(/\/$/, "");
      const headers: Record<string, string> = { "Content-Type": "application/json" };
      if (mcpToken) headers["Authorization"] = `Bearer ${mcpToken}`;

      const res = await fetch(url, {
        method: "POST",
        headers,
        body: JSON.stringify({
          jsonrpc: "2.0",
          id: 1,
          method: "tools/list",
          params: {},
        }),
      });
      if (res.ok) {
        const data = await res.json();
        const tools = data?.result?.tools ?? [];
        toast.success(`Connected! ${tools.length} MCP tools available`);
        setMcpTools(tools.map((t: { name: string; description?: string }) => ({
          name: t.name,
          description: t.description ?? "",
        })));
        setTestResult("ok");
      } else {
        toast.error(`HTTP ${res.status}`);
        setTestResult("fail");
      }
    } catch (e) {
      toast.error(`Connection failed: ${e instanceof Error ? e.message : e}`);
      setTestResult("fail");
    }
  }

  return (
    <div className="space-y-6 max-w-2xl">
      <div>
        <h2 className="text-2xl font-bold tracking-tight">Home Assistant</h2>
        <p className="text-muted-foreground">
          MCP integration for smart home control
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
              <CardTitle>HA MCP Server</CardTitle>
              <CardDescription>
                The voice agent connects to Home Assistant via MCP (Streamable
                HTTP). This gives it access to ~92 tools for controlling lights,
                switches, climate, etc.
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <Label>MCP URL</Label>
                <Input
                  value={mcpUrl}
                  onChange={(e) => setMcpUrl(e.target.value)}
                  placeholder="http://192.168.211.3:8123/api/mcp"
                />
                <p className="text-xs text-muted-foreground">
                  Can be the HA built-in MCP endpoint or the ha-mcp add-on
                </p>
              </div>

              <div className="space-y-2">
                <Label>
                  Bearer Token{" "}
                  <span className="text-muted-foreground">(optional)</span>
                </Label>
                <Input
                  type="password"
                  value={mcpToken}
                  onChange={(e) => setMcpToken(e.target.value)}
                  placeholder="Long-lived access token"
                />
                <p className="text-xs text-muted-foreground">
                  Required for HA built-in MCP. Stored in ~/.hermes/.env on the
                  LXC as HASS_TOKEN.
                </p>
              </div>

              <div className="flex gap-3 pt-2">
                <Button variant="outline" size="sm" onClick={testConnection}>
                  <Plug className="mr-2 h-4 w-4" />
                  Test Connection
                </Button>
                {testResult && (
                  <Badge
                    variant={testResult === "ok" ? "default" : "destructive"}
                  >
                    {testResult === "ok" ? "Connected" : "Failed"}
                  </Badge>
                )}
              </div>
            </CardContent>
          </Card>

          {/* MCP Tools List */}
          {mcpTools.length > 0 && (
            <Card>
              <CardHeader>
                <CardTitle>Available MCP Tools ({mcpTools.length})</CardTitle>
                <CardDescription>
                  Tools discovered from the HA MCP server
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-3">
                <div className="relative">
                  <Search className="absolute left-3 top-2.5 h-4 w-4 text-muted-foreground" />
                  <Input
                    className="pl-9"
                    placeholder="Filter tools..."
                    value={toolFilter}
                    onChange={(e) => setToolFilter(e.target.value)}
                  />
                </div>
                <div className="max-h-80 overflow-auto space-y-1">
                  {mcpTools
                    .filter((t) =>
                      !toolFilter ||
                      t.name.toLowerCase().includes(toolFilter.toLowerCase()) ||
                      t.description.toLowerCase().includes(toolFilter.toLowerCase())
                    )
                    .map((t) => (
                      <div key={t.name} className="flex items-start gap-2 text-sm p-2 rounded hover:bg-muted/50">
                        <Badge variant="outline" className="shrink-0 font-mono text-xs mt-0.5">
                          {t.name}
                        </Badge>
                        <span className="text-muted-foreground text-xs">{t.description}</span>
                      </div>
                    ))}
                </div>
              </CardContent>
            </Card>
          )}

          <div className="flex gap-3">
            <Button
              onClick={() => saveMutation.mutate()}
              disabled={saveMutation.isPending}
            >
              <Save className="mr-2 h-4 w-4" />
              Save &amp; Restart Agent
            </Button>
          </div>
        </>
      )}
    </div>
  );
}
