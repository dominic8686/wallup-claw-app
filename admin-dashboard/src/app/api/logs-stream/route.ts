import { NextRequest } from "next/server";
import { NodeSSH } from "node-ssh";

const LXC_HOST = process.env.LXC_HOST ?? "192.168.211.153";
const LXC_USER = process.env.LXC_USER ?? "root";
const LXC_SSH_KEY = process.env.LXC_SSH_KEY ?? "";
const LXC_PASSWORD = process.env.LXC_PASSWORD ?? "";
const COMPOSE_DIR = process.env.COMPOSE_DIR ?? "/opt/livekit-voice-agent";

export async function GET(request: NextRequest) {
  const service = request.nextUrl.searchParams.get("service") ?? "voice-agent";

  const encoder = new TextEncoder();
  let sshConn: NodeSSH | null = null;

  const stream = new ReadableStream({
    async start(controller) {
      try {
        // Open a dedicated SSH connection for streaming
        sshConn = new NodeSSH();
        const config: Record<string, unknown> = {
          host: LXC_HOST,
          username: LXC_USER,
          readyTimeout: 10_000,
        };
        if (LXC_SSH_KEY) config.privateKeyPath = LXC_SSH_KEY;
        else if (LXC_PASSWORD) config.password = LXC_PASSWORD;
        await sshConn.connect(config);

        // Use docker logs -f for true streaming
        const cmd = `docker compose -f ${COMPOSE_DIR}/docker-compose.yml logs --no-color --tail=100 -f ${service}`;

        // execCommand doesn't stream, so use requestShell or exec with callbacks
        const conn = sshConn.connection;
        if (!conn) throw new Error("SSH connection not available");

        conn.exec(cmd, (err: Error | undefined, channel: import("ssh2").ClientChannel | undefined) => {
          if (err || !channel) {
            controller.enqueue(
              encoder.encode(`data: ${JSON.stringify({ error: err?.message ?? "exec failed" })}\n\n`)
            );
            controller.close();
            return;
          }

          channel.on("data", (data: Buffer) => {
            const text = data.toString();
            if (text.trim()) {
              controller.enqueue(
                encoder.encode(`data: ${JSON.stringify(text)}\n\n`)
              );
            }
          });

          channel.stderr.on("data", (data: Buffer) => {
            const text = data.toString();
            if (text.trim()) {
              controller.enqueue(
                encoder.encode(`data: ${JSON.stringify(text)}\n\n`)
              );
            }
          });

          channel.on("close", () => {
            controller.enqueue(encoder.encode("data: [DONE]\n\n"));
            controller.close();
            sshConn?.dispose();
          });

          // Auto-close after 5 minutes
          setTimeout(() => {
            try {
              channel.close();
              sshConn?.dispose();
            } catch { /* ignore */ }
          }, 5 * 60 * 1000);
        });
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        controller.enqueue(
          encoder.encode(`data: ${JSON.stringify({ error: msg })}\n\n`)
        );
        controller.close();
        sshConn?.dispose();
      }
    },
    cancel() {
      sshConn?.dispose();
    },
  });

  return new Response(stream, {
    headers: {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache",
      Connection: "keep-alive",
    },
  });
}
