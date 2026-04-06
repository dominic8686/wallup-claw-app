// ---------------------------------------------------------------------------
// Shared types for the Wallup Claw Admin Portal
// ---------------------------------------------------------------------------

/** AI model categories */
export type ModelMode = "realtime" | "pipeline";

export interface ModelOption {
  id: string;
  label: string;
  mode: ModelMode;
  provider: "google" | "openai" | "inference";
}

export const MODEL_OPTIONS: ModelOption[] = [
  { id: "gemini-live", label: "Gemini Live (Realtime)", mode: "realtime", provider: "google" },
  { id: "openai-realtime", label: "OpenAI Realtime", mode: "realtime", provider: "openai" },
  { id: "gemini-2.5-flash", label: "Gemini 2.5 Flash", mode: "pipeline", provider: "google" },
  { id: "gpt-4o-mini", label: "GPT-4o Mini", mode: "pipeline", provider: "openai" },
  { id: "gpt-4o", label: "GPT-4o", mode: "pipeline", provider: "openai" },
];

export const VOICE_OPTIONS: Record<string, string[]> = {
  google: ["Puck", "Charon", "Kore", "Fenrir", "Aoede"],
  openai: ["alloy", "echo", "nova", "fable", "onyx", "shimmer"],
};

export const STT_OPTIONS = ["deepgram/nova-3", "google/chirp"];
export const TTS_OPTIONS = ["cartesia/sonic-3", "openai/tts-1"];
export const TTS_BACKEND_OPTIONS = ["edge-tts", "openai"];

export const TTS_VOICE_OPTIONS: Record<string, string[]> = {
  "edge-tts": ["en-GB-SoniaNeural", "en-US-GuyNeural", "en-US-JennyNeural", "en-AU-NatashaNeural"],
  openai: ["nova", "alloy", "echo", "fable", "onyx", "shimmer"],
};

/** Agent config — maps to docker-compose env vars */
export interface AgentConfig {
  LIVEKIT_LLM: string;
  LIVEKIT_VOICE: string;
  LIVEKIT_STT: string;
  LIVEKIT_TTS: string;
  LIVEKIT_INSTRUCTIONS: string;
  HA_MCP_URL: string;
  TOKEN_SERVER_URL: string;
  DEVICE_POLL_INTERVAL: string;
}

/** Token server config — env vars */
export interface TokenServerConfig {
  TTS_BACKEND: string;
  TTS_VOICE: string;
  LIVEKIT_EXTERNAL_URL: string;
  STALE_TIMEOUT?: string;
  CALL_RING_TIMEOUT?: string;
}

/** Registered device from token server /devices */
export interface Device {
  device_id: string;
  display_name: string;
  room_location: string;
  status: "online" | "offline";
  call_state: "idle" | "calling" | "ringing" | "in_call" | "do_not_disturb";
  last_seen: number;
}

/** Active call from token server /calls */
export interface ActiveCall {
  from: string;
  to: string;
  room_name: string;
  started_at: number;
  status: "ringing" | "active";
}

/** Docker container status */
export interface ContainerStatus {
  name: string;
  state: "running" | "exited" | "restarting" | "created" | "dead";
  status: string; // e.g. "Up 2 hours"
  image: string;
}

/** System health summary */
export interface HealthStatus {
  livekit_server: boolean;
  voice_agent: boolean;
  token_server: boolean;
  containers: ContainerStatus[];
  agent_config: Partial<AgentConfig>;
  token_server_config: Partial<TokenServerConfig>;
}
