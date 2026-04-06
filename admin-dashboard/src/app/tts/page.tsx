"use client";

import { useEffect, useState, useRef } from "react";
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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Separator } from "@/components/ui/separator";
import { RefreshCw, Save, Play } from "lucide-react";
import { toast } from "sonner";
import { TTS_BACKEND_OPTIONS, TTS_VOICE_OPTIONS } from "@/lib/types";

const TS_URL =
  process.env.NEXT_PUBLIC_TOKEN_SERVER_URL ?? "http://192.168.211.153:8090";

interface ConfigResponse {
  tokenServer: Record<string, string>;
}

export default function TtsPage() {
  const qc = useQueryClient();
  const audioRef = useRef<HTMLAudioElement | null>(null);

  const { data: config, isLoading } = useQuery<ConfigResponse>({
    queryKey: ["config"],
    queryFn: () => fetch("/api/config").then((r) => r.json()),
  });

  const [backend, setBackend] = useState("edge-tts");
  const [voice, setVoice] = useState("");
  const [testText, setTestText] = useState("Hello! This is a test of the text to speech engine.");
  const [playing, setPlaying] = useState(false);

  useEffect(() => {
    if (!config) return;
    const ts = config.tokenServer;
    setBackend(ts.TTS_BACKEND || "edge-tts");
    setVoice(ts.TTS_VOICE || "");
  }, [config]);

  const voiceOptions = TTS_VOICE_OPTIONS[backend] ?? TTS_VOICE_OPTIONS["edge-tts"];

  // Auto-set voice when backend changes
  useEffect(() => {
    const opts = TTS_VOICE_OPTIONS[backend] ?? [];
    if (opts.length && !opts.includes(voice)) {
      setVoice(opts[0]);
    }
  }, [backend, voice]);

  const saveMutation = useMutation({
    mutationFn: async () => {
      const res = await fetch("/api/config", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          updates: { TTS_BACKEND: backend, TTS_VOICE: voice },
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

  async function testTts() {
    if (!testText.trim()) return;
    setPlaying(true);
    try {
      const params = new URLSearchParams({ text: testText });
      const res = await fetch(`${TS_URL}/tts?${params}`);
      if (!res.ok) {
        toast.error(`TTS error: ${res.status}`);
        setPlaying(false);
        return;
      }
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);

      if (audioRef.current) {
        audioRef.current.pause();
      }
      const audio = new Audio(url);
      audioRef.current = audio;
      audio.onended = () => {
        setPlaying(false);
        URL.revokeObjectURL(url);
      };
      audio.onerror = () => {
        setPlaying(false);
        toast.error("Audio playback failed");
      };
      await audio.play();
    } catch (e) {
      toast.error(`TTS test failed: ${e instanceof Error ? e.message : e}`);
      setPlaying(false);
    }
  }

  return (
    <div className="space-y-6 max-w-2xl">
      <div>
        <h2 className="text-2xl font-bold tracking-tight">Text-to-Speech</h2>
        <p className="text-muted-foreground">
          Token server TTS settings (used for DLNA announcements and TTS proxy)
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
              <CardTitle>TTS Backend</CardTitle>
              <CardDescription>
                edge-tts is free (Microsoft Azure), openai requires an API key
                but sounds more natural
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <Label>Backend</Label>
<Select value={backend} onValueChange={(v) => v && setBackend(v)}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {TTS_BACKEND_OPTIONS.map((b) => (
                      <SelectItem key={b} value={b}>
                        {b}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              <div className="space-y-2">
                <Label>Voice</Label>
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
            </CardContent>
          </Card>

          {/* Test TTS */}
          <Card>
            <CardHeader>
              <CardTitle>Test TTS</CardTitle>
              <CardDescription>
                Generate speech using the token server&apos;s /tts endpoint with the
                current settings
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <Input
                value={testText}
                onChange={(e) => setTestText(e.target.value)}
                placeholder="Type something to speak..."
              />
              <Button
                variant="outline"
                onClick={testTts}
                disabled={playing || !testText.trim()}
              >
                {playing ? (
                  <RefreshCw className="mr-2 h-4 w-4 animate-spin" />
                ) : (
                  <Play className="mr-2 h-4 w-4" />
                )}
                {playing ? "Playing..." : "Play"}
              </Button>
            </CardContent>
          </Card>

          <Separator />

          <div className="flex gap-3">
            <Button
              onClick={() => saveMutation.mutate()}
              disabled={saveMutation.isPending}
            >
              <Save className="mr-2 h-4 w-4" />
              Save &amp; Restart Token Server
            </Button>
          </div>
        </>
      )}
    </div>
  );
}
