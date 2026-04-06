import fs from "fs";
import path from "path";

// ---------------------------------------------------------------------------
// Dashboard settings and audit log — persisted to local JSON files
// ---------------------------------------------------------------------------

const DATA_DIR = path.join(process.cwd(), ".data");
const SETTINGS_FILE = path.join(DATA_DIR, "settings.json");
const AUDIT_FILE = path.join(DATA_DIR, "audit.json");
const MAX_AUDIT_ENTRIES = 200;

function ensureDataDir() {
  if (!fs.existsSync(DATA_DIR)) {
    fs.mkdirSync(DATA_DIR, { recursive: true });
  }
}

// ---------------------------------------------------------------------------
// Dashboard settings
// ---------------------------------------------------------------------------

export interface DashboardSettings {
  promptPresets: PromptPreset[];
}

export interface PromptPreset {
  id: string;
  name: string;
  content: string;
  createdAt: string;
}

const DEFAULT_SETTINGS: DashboardSettings = {
  promptPresets: [
    {
      id: "concise",
      name: "Concise Assistant",
      content: "You are a helpful voice assistant on a wall-mounted tablet. Keep responses concise — 1 to 3 sentences — since they will be spoken aloud. Be friendly and conversational.",
      createdAt: new Date().toISOString(),
    },
    {
      id: "smart-home",
      name: "Smart Home Focused",
      content: "You are a smart home voice assistant. Focus on controlling lights, climate, and devices via Home Assistant. Keep responses very brief. Always prefer using HA tools over conversational answers.",
      createdAt: new Date().toISOString(),
    },
  ],
};

export function readSettings(): DashboardSettings {
  ensureDataDir();
  try {
    if (fs.existsSync(SETTINGS_FILE)) {
      return JSON.parse(fs.readFileSync(SETTINGS_FILE, "utf-8"));
    }
  } catch { /* ignore */ }
  return DEFAULT_SETTINGS;
}

export function writeSettings(settings: DashboardSettings) {
  ensureDataDir();
  fs.writeFileSync(SETTINGS_FILE, JSON.stringify(settings, null, 2));
}

// ---------------------------------------------------------------------------
// Audit log
// ---------------------------------------------------------------------------

export interface AuditEntry {
  timestamp: string;
  action: string;
  details: Record<string, unknown>;
}

export function readAuditLog(): AuditEntry[] {
  ensureDataDir();
  try {
    if (fs.existsSync(AUDIT_FILE)) {
      return JSON.parse(fs.readFileSync(AUDIT_FILE, "utf-8"));
    }
  } catch { /* ignore */ }
  return [];
}

export function appendAudit(action: string, details: Record<string, unknown>) {
  const entries = readAuditLog();
  entries.unshift({
    timestamp: new Date().toISOString(),
    action,
    details,
  });
  // Trim old entries
  if (entries.length > MAX_AUDIT_ENTRIES) {
    entries.length = MAX_AUDIT_ENTRIES;
  }
  ensureDataDir();
  fs.writeFileSync(AUDIT_FILE, JSON.stringify(entries, null, 2));
}
