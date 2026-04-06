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
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Separator } from "@/components/ui/separator";
import { Badge } from "@/components/ui/badge";
import { Slider } from "@/components/ui/slider";
import { RefreshCw, Save, Play, BookOpen, Plus, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { ConfirmDialog } from "@/components/confirm-dialog";
import {
  MODEL_OPTIONS,
  VOICE_OPTIONS,
  STT_OPTIONS,
  TTS_OPTIONS,
} from "@/lib/types";
import type { ModelOption } from "@/lib/types";

interface ConfigResponse {
  agent: Record<string, string>;
  tokenServer: Record<string, string>;
}

export default function ModelsPage() {
  const qc = useQueryClient();

  const { data: config, isLoading } = useQuery<ConfigResponse>({
    queryKey: ["config"],
    queryFn: () => fetch("/api/config").then((r) => r.json()),
  });

  // Local form state
  const [llm, setLlm] = useState("");
  const [voice, setVoice] = useState("");
  const [stt, setStt] = useState("");
  const [tts, setTts] = useState("");
  const [instructions, setInstructions] = useState("");
  const [customModel, setCustomModel] = useState("");
  const [temperature, setTemperature] = useState(0.8);
  const [previewPlaying, setPreviewPlaying] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [presetName, setPresetName] = useState("");

  // Prompt presets
  const { data: settingsData } = useQuery<{ promptPresets: { id: string; name: string; content: string }[] }>({
    queryKey: ["settings"],
    queryFn: () => fetch("/api/settings").then((r) => r.json()),
  });
  const presets = settingsData?.promptPresets ?? [];

  // Sync remote → local when data loads
  useEffect(() => {
    if (!config) return;
    const a = config.agent;
    const currentLlm = a.LIVEKIT_LLM || "gemini-live";
    const knownModel = MODEL_OPTIONS.find((m) => m.id === currentLlm);
    if (knownModel) {
      setLlm(currentLlm);
      setCustomModel("");
    } else {
      setLlm("custom");
      setCustomModel(currentLlm);
    }
    setVoice(a.LIVEKIT_VOICE || "Puck");
    setStt(a.LIVEKIT_STT || "deepgram/nova-3");
    setTts(a.LIVEKIT_TTS || "cartesia/sonic-3");
    setInstructions(a.LIVEKIT_INSTRUCTIONS || "");
  }, [config]);

  // Derived state
  const selectedModel: ModelOption | undefined = MODEL_OPTIONS.find(
    (m) => m.id === llm
  );
  const isRealtime = selectedModel?.mode === "realtime";
  const isPipeline = selectedModel?.mode === "pipeline" || llm === "custom";
  const provider = selectedModel?.provider ?? "openai";
  const voiceOptions = VOICE_OPTIONS[provider] ?? VOICE_OPTIONS.openai;

  const saveMutation = useMutation({
    mutationFn: async (restart: boolean) => {
      const effectiveLlm = llm === "custom" ? customModel : llm;
      const updates: Record<string, string> = {
        LIVEKIT_LLM: effectiveLlm,
        LIVEKIT_VOICE: voice,
      };
      if (instructions) {
        updates.LIVEKIT_INSTRUCTIONS = instructions;
      }
      const res = await fetch("/api/config", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          updates,
          restart: restart ? "voice-agent" : undefined,
        }),
      });
      if (!res.ok) throw new Error(await res.text());
      return res.json();
    },
    onSuccess: (_, restart) => {
      toast.success(restart ? "Saved & restarting agent" : "Saved");
      qc.invalidateQueries({ queryKey: ["config"] });
      qc.invalidateQueries({ queryKey: ["health"] });
    },
    onError: (e) => toast.error(`Save failed: ${e.message}`),
  });

  return (
    <div className="space-y-6 max-w-3xl">
      <div>
        <h2 className="text-2xl font-bold tracking-tight">AI Model Configuration</h2>
        <p className="text-muted-foreground">
          Configure the voice agent&apos;s LLM, voice, and pipeline settings
        </p>
      </div>

      {isLoading ? (
        <div className="flex items-center gap-2 text-muted-foreground">
          <RefreshCw className="h-4 w-4 animate-spin" /> Loading config from
          LXC...
        </div>
      ) : (
        <>
          {/* LLM Model */}
          <Card>
            <CardHeader>
              <CardTitle>LLM Model</CardTitle>
              <CardDescription>
                Choose between realtime (speech-to-speech) or pipeline
                (STT→LLM→TTS) mode
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <Label>Model</Label>
<Select value={llm} onValueChange={(v) => v && setLlm(v)}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {MODEL_OPTIONS.map((m) => (
                      <SelectItem key={m.id} value={m.id}>
                        <span className="flex items-center gap-2">
                          {m.label}
                          <Badge variant="secondary" className="text-[10px]">
                            {m.mode}
                          </Badge>
                        </span>
                      </SelectItem>
                    ))}
                    <SelectItem value="custom">Custom model string</SelectItem>
                  </SelectContent>
                </Select>
              </div>

              {llm === "custom" && (
                <div className="space-y-2">
                  <Label>Custom model identifier</Label>
                  <Input
                    value={customModel}
                    onChange={(e) => setCustomModel(e.target.value)}
                    placeholder="e.g. gemini-3.1-flash-live-preview"
                  />
                </div>
              )}

              {selectedModel && (
                <div className="text-sm text-muted-foreground">
                  Mode:{" "}
                  <Badge variant="outline">
                    {isRealtime ? "Realtime (speech-to-speech)" : "Pipeline (STT → LLM → TTS)"}
                  </Badge>
                  {" "}Provider:{" "}
                  <Badge variant="outline">{provider}</Badge>
                </div>
              )}
            </CardContent>
          </Card>

          {/* Voice */}
          <Card>
            <CardHeader>
              <CardTitle>Voice</CardTitle>
              <CardDescription>
                {isRealtime
                  ? "Server-side voice for the realtime model"
                  : "Voice used by the TTS engine"}
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="flex gap-3 items-end">
                <div className="flex-1">
<Select value={voice} onValueChange={(v) => v && setVoice(v)}>
                    <SelectTrigger>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {voiceOptions.map((v) => (
                        <SelectItem key={v} value={v}>
                          {v}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={previewPlaying}
                  onClick={async () => {
                    setPreviewPlaying(true);
                    try {
                      const tsUrl = process.env.NEXT_PUBLIC_TOKEN_SERVER_URL ?? "http://192.168.211.153:8090";
                      const res = await fetch(`${tsUrl}/tts?text=Hello!+I+am+${encodeURIComponent(voice)}.+How+can+I+help+you+today?`);
                      if (!res.ok) { setPreviewPlaying(false); return; }
                      const blob = await res.blob();
                      const audio = new Audio(URL.createObjectURL(blob));
                      audio.onended = () => setPreviewPlaying(false);
                      audio.onerror = () => setPreviewPlaying(false);
                      await audio.play();
                    } catch { setPreviewPlaying(false); }
                  }}
                >
                  <Play className="mr-1 h-4 w-4" />
                  {previewPlaying ? "Playing..." : "Preview"}
                </Button>
              </div>
              <p className="text-xs text-muted-foreground">
                Preview uses token server TTS. Realtime model voices may sound different.
              </p>
            </CardContent>
          </Card>

          {/* Temperature */}
          <Card>
            <CardHeader>
              <CardTitle>Temperature</CardTitle>
              <CardDescription>
                Controls randomness in LLM responses (0 = deterministic, 2 = creative)
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="flex items-center gap-4">
                <Slider
                  value={[temperature]}
onValueChange={(val) => setTemperature(Array.isArray(val) ? val[0] : val)}
                  min={0}
                  max={2}
                  step={0.1}
                  className="flex-1"
                />
                <span className="text-sm font-mono w-10 text-right">{temperature.toFixed(1)}</span>
              </div>
            </CardContent>
          </Card>

          {/* Pipeline-only STT/TTS */}
          {isPipeline && (
            <Card>
              <CardHeader>
                <CardTitle>Pipeline Settings</CardTitle>
                <CardDescription>
                  STT and TTS engines for pipeline mode
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-4">
                <div className="grid grid-cols-2 gap-4">
                  <div className="space-y-2">
                    <Label>STT Engine</Label>
<Select value={stt} onValueChange={(v) => v && setStt(v)}>
                      <SelectTrigger>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        {STT_OPTIONS.map((s) => (
                          <SelectItem key={s} value={s}>
                            {s}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="space-y-2">
                    <Label>TTS Engine</Label>
<Select value={tts} onValueChange={(v) => v && setTts(v)}>
                      <SelectTrigger>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        {TTS_OPTIONS.map((t) => (
                          <SelectItem key={t} value={t}>
                            {t}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                </div>
              </CardContent>
            </Card>
          )}

          {/* System Prompt */}
          <Card>
            <CardHeader>
              <CardTitle>System Prompt</CardTitle>
              <CardDescription>
                Instructions sent to the LLM at the start of each session
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              {/* Preset selector */}
              {presets.length > 0 && (
                <div className="flex items-center gap-2">
                  <BookOpen className="h-4 w-4 text-muted-foreground" />
                  <span className="text-sm text-muted-foreground">Load preset:</span>
                  {presets.map((p) => (
                    <Button
                      key={p.id}
                      variant="outline"
                      size="sm"
                      onClick={() => setInstructions(p.content)}
                    >
                      {p.name}
                    </Button>
                  ))}
                </div>
              )}
              <Textarea
                value={instructions}
                onChange={(e) => setInstructions(e.target.value)}
                rows={10}
                className="font-mono text-sm"
                placeholder="You are a helpful voice assistant..."
              />
              {/* Save as preset */}
              <div className="flex items-center gap-2">
                <Input
                  className="w-48"
                  placeholder="Preset name..."
                  value={presetName}
                  onChange={(e) => setPresetName(e.target.value)}
                />
                <Button
                  variant="outline"
                  size="sm"
                  disabled={!presetName.trim() || !instructions.trim()}
                  onClick={async () => {
                    const newPreset = {
                      id: presetName.toLowerCase().replace(/\s+/g, "-"),
                      name: presetName,
                      content: instructions,
                      createdAt: new Date().toISOString(),
                    };
                    const updated = [...presets.filter((p) => p.id !== newPreset.id), newPreset];
                    await fetch("/api/settings", {
                      method: "PUT",
                      headers: { "Content-Type": "application/json" },
                      body: JSON.stringify({ promptPresets: updated }),
                    });
                    qc.invalidateQueries({ queryKey: ["settings"] });
                    setPresetName("");
                    toast.success(`Preset "${presetName}" saved`);
                  }}
                >
                  <Plus className="mr-1 h-3 w-3" /> Save as Preset
                </Button>
                {presets.length > 0 && (
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={async () => {
                      // Delete last preset as a simple approach
                      const updated = presets.slice(0, -1);
                      await fetch("/api/settings", {
                        method: "PUT",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({ promptPresets: updated }),
                      });
                      qc.invalidateQueries({ queryKey: ["settings"] });
                      toast.success("Last preset deleted");
                    }}
                  >
                    <Trash2 className="mr-1 h-3 w-3" /> Delete Last
                  </Button>
                )}
              </div>
            </CardContent>
          </Card>

          <Separator />

          {/* Save buttons */}
          <div className="flex gap-3">
            <Button
              onClick={() => saveMutation.mutate(false)}
              disabled={saveMutation.isPending}
              variant="outline"
            >
              <Save className="mr-2 h-4 w-4" />
              Save Only
            </Button>
            <Button
              onClick={() => setConfirmOpen(true)}
              disabled={saveMutation.isPending}
            >
              <RefreshCw className="mr-2 h-4 w-4" />
              Save &amp; Restart Agent
            </Button>
          </div>

          <ConfirmDialog
            open={confirmOpen}
            onOpenChange={setConfirmOpen}
            title="Save & Restart Voice Agent?"
            description="This will update the config and restart the voice-agent container. Active conversations will be interrupted."
            details={[
              `Model: ${llm === "custom" ? customModel : llm}`,
              `Voice: ${voice}`,
              `Temperature: ${temperature}`,
            ]}
            confirmLabel="Save & Restart"
            loading={saveMutation.isPending}
            onConfirm={() => {
              saveMutation.mutate(true);
              setConfirmOpen(false);
            }}
          />
        </>
      )}
    </div>
  );
}
