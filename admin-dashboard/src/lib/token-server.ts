// ---------------------------------------------------------------------------
// Token server API client — works server-side (API routes) or client-side
// ---------------------------------------------------------------------------

const TOKEN_SERVER_URL =
  process.env.NEXT_PUBLIC_TOKEN_SERVER_URL ?? "http://192.168.211.153:8090";

async function fetchTS<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${TOKEN_SERVER_URL}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...init?.headers,
    },
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`Token server ${path}: ${res.status} — ${text}`);
  }
  return res.json() as Promise<T>;
}

export async function getDevices() {
  return fetchTS<{ devices: import("./types").Device[] }>("/devices");
}

export async function getCalls() {
  return fetchTS<{ calls: import("./types").ActiveCall[] }>("/calls");
}

export async function getHealth() {
  const res = await fetch(`${TOKEN_SERVER_URL}/health`);
  return res.ok;
}

export async function configureDevice(
  deviceId: string,
  settings: Record<string, string>
) {
  return fetchTS<{ ok: boolean }>("/configure", {
    method: "POST",
    body: JSON.stringify({ device_id: deviceId, settings }),
  });
}

export async function testTts(text: string, voice?: string) {
  const params = new URLSearchParams({ text });
  if (voice) params.set("voice", voice);
  const res = await fetch(`${TOKEN_SERVER_URL}/tts?${params}`);
  if (!res.ok) throw new Error(`TTS error: ${res.status}`);
  return res.blob();
}

export { TOKEN_SERVER_URL };
