import { NodeSSH } from "node-ssh";

// ---------------------------------------------------------------------------
// SSH connection to the Proxmox LXC running docker compose
// Singleton pooled connection with auto-reconnect
// ---------------------------------------------------------------------------

const LXC_HOST = process.env.LXC_HOST ?? "192.168.211.153";
const LXC_USER = process.env.LXC_USER ?? "root";
const LXC_SSH_KEY = process.env.LXC_SSH_KEY ?? "";
const LXC_PASSWORD = process.env.LXC_PASSWORD ?? "";
const COMPOSE_DIR = process.env.COMPOSE_DIR ?? "/opt/livekit-voice-agent";

let _ssh: NodeSSH | null = null;
let _connecting: Promise<NodeSSH> | null = null;

function buildConfig(): Record<string, unknown> {
  const config: Record<string, unknown> = {
    host: LXC_HOST,
    username: LXC_USER,
    keepaliveInterval: 10_000,
    readyTimeout: 10_000,
  };
  if (LXC_SSH_KEY) {
    config.privateKeyPath = LXC_SSH_KEY;
  } else if (LXC_PASSWORD) {
    config.password = LXC_PASSWORD;
  }
  return config;
}

async function getSSH(): Promise<NodeSSH> {
  if (_ssh?.isConnected()) return _ssh;

  // Prevent concurrent connection attempts
  if (_connecting) return _connecting;

  _connecting = (async () => {
    try {
      if (_ssh) { try { _ssh.dispose(); } catch { /* ignore */ } }
      _ssh = new NodeSSH();
      await _ssh.connect(buildConfig());
      return _ssh;
    } finally {
      _connecting = null;
    }
  })();

  return _connecting;
}

/** Run a command on the LXC and return stdout. */
export async function sshExec(command: string): Promise<string> {
  const ssh = await getSSH();
  const result = await ssh.execCommand(command, { cwd: COMPOSE_DIR });
  if (result.stderr && result.code !== 0) {
    throw new Error(result.stderr);
  }
  return result.stdout;
}

/** Read a file from the LXC. */
export async function sshReadFile(path: string): Promise<string> {
  return sshExec(`cat ${path}`);
}

/** Write content to a file on the LXC (with backup). */
export async function sshWriteFile(path: string, content: string): Promise<void> {
  const ssh = await getSSH();
  // Backup existing file
  await ssh.execCommand(`cp -f ${path} ${path}.bak 2>/dev/null || true`, { cwd: COMPOSE_DIR });
  // Write via base64 to avoid shell escaping issues
  const b64 = Buffer.from(content).toString("base64");
  await ssh.execCommand(`echo '${b64}' | base64 -d > ${path}`, { cwd: COMPOSE_DIR });
}

// ---------------------------------------------------------------------------
// Docker Compose helpers
// ---------------------------------------------------------------------------

export async function dockerPs(): Promise<string> {
  return sshExec(`docker compose -f ${COMPOSE_DIR}/docker-compose.yml ps --format json`);
}

export async function dockerRestart(service?: string): Promise<string> {
  const svc = service ?? "";
  return sshExec(`docker compose -f ${COMPOSE_DIR}/docker-compose.yml restart ${svc}`.trim());
}

export async function dockerLogs(service: string, lines = 100): Promise<string> {
  return sshExec(
    `docker compose -f ${COMPOSE_DIR}/docker-compose.yml logs --no-color --tail=${lines} ${service}`
  );
}

export async function dockerRebuild(service?: string): Promise<string> {
  const svc = service ?? "";
  return sshExec(
    `docker compose -f ${COMPOSE_DIR}/docker-compose.yml up -d --build ${svc}`.trim()
  );
}

// ---------------------------------------------------------------------------
// Config file helpers (docker-compose.yml env vars)
// ---------------------------------------------------------------------------

const COMPOSE_FILE = `${COMPOSE_DIR}/docker-compose.yml`;

/** Read the docker-compose.yml from the LXC. */
export async function readComposeFile(): Promise<string> {
  return sshReadFile(COMPOSE_FILE);
}

/** Write the docker-compose.yml to the LXC. */
export async function writeComposeFile(content: string): Promise<void> {
  return sshWriteFile(COMPOSE_FILE, content);
}

/** Read the .env file from the LXC. */
export async function readEnvFile(): Promise<string> {
  return sshReadFile(`${COMPOSE_DIR}/.env`);
}

/** Read livekit.yaml from the LXC. */
export async function readLivekitYaml(): Promise<string> {
  return sshReadFile(`${COMPOSE_DIR}/livekit.yaml`);
}
