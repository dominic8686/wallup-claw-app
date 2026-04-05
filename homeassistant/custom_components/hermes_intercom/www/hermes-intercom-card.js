/**
 * Hermes Intercom Card for Home Assistant Lovelace
 *
 * Shows intercom tablets with status, call buttons, announce bar, and
 * **real WebRTC voice calls** between the HA dashboard and tablets via LiveKit.
 *
 * Usage in Lovelace YAML:
 *   type: custom:hermes-intercom-card
 *   token_server_url: http://192.168.211.153:8090
 *   livekit_url: ws://192.168.211.153:7880
 */

// Load LiveKit JS SDK from CDN (once)
const LIVEKIT_SDK_URL = "https://cdn.jsdelivr.net/npm/livekit-client@2.9.2/dist/livekit-client.umd.js";
let _livekitLoaded = false;
function loadLiveKitSDK() {
  if (_livekitLoaded || window.LivekitClient) { _livekitLoaded = true; return Promise.resolve(); }
  return new Promise((resolve, reject) => {
    const s = document.createElement("script");
    s.src = LIVEKIT_SDK_URL;
    s.onload = () => { _livekitLoaded = true; resolve(); };
    s.onerror = reject;
    document.head.appendChild(s);
  });
}

class HermesIntercomCard extends HTMLElement {
  set hass(hass) {
    this._hass = hass;
    if (!this._initialized) {
      this._initialize();
      this._initialized = true;
    }
    this._update();
  }

  setConfig(config) {
    this._config = config;
    this._tokenServerUrl = config.token_server_url || "http://192.168.211.153:8090";
    this._livekitUrl = config.livekit_url || "ws://192.168.211.153:7880";
    this._apiKey = config.api_key || "";
    this._pollInterval = config.poll_interval || 5000;
  }

  /** Build headers with auth if api_key is configured. */
  _headers(extra = {}) {
    const h = { "Content-Type": "application/json", ...extra };
    if (this._apiKey) h["Authorization"] = `Bearer ${this._apiKey}`;
    return h;
  }

  /** Fetch wrapper with auth + error handling. */
  async _apiFetch(path, opts = {}) {
    const url = `${this._tokenServerUrl}${path}`;
    opts.headers = this._headers(opts.headers || {});
    try {
      const resp = await fetch(url, opts);
      if (resp.status === 401) {
        this._showError("Unauthorized — check api_key in card config");
        return null;
      }
      return resp;
    } catch (e) {
      if (e instanceof TypeError && e.message.includes("Failed to fetch")) {
        this._showError("Cannot reach token server. If using HTTPS, ensure token_server_url is also HTTPS.");
      } else {
        this._showError(`Network error: ${e.message}`);
      }
      return null;
    }
  }

  _showError(msg) {
    const el = this.querySelector("#call-status");
    if (el) { el.textContent = msg; el.style.color = "#F44336"; }
    else console.error("Hermes Intercom:", msg);
  }

  _initialize() {
    this.innerHTML = `
      <ha-card header="Intercom">
        <div class="card-content">
          <div id="devices" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:12px;"></div>
          <div id="in-call-ui" style="display:none;margin-top:16px;background:#e8f5e9;border-radius:12px;overflow:hidden;">
            <div id="video-container" style="position:relative;width:100%;background:#000;min-height:60px;">
              <video id="remote-video" autoplay playsinline style="width:100%;max-height:400px;display:none;"></video>
              <video id="local-video" autoplay playsinline muted style="position:absolute;bottom:8px;right:8px;width:120px;border-radius:8px;border:2px solid #fff;display:none;"></video>
            </div>
            <div style="padding:16px;text-align:center;">
              <div id="call-status" style="font-size:16px;font-weight:600;margin-bottom:4px;">Calling...</div>
              <div id="call-timer" style="font-size:24px;font-weight:700;margin-bottom:12px;font-variant-numeric:tabular-nums;">00:00</div>
              <div style="display:flex;gap:12px;justify-content:center;">
                <button id="mute-btn" style="padding:10px 20px;background:#FF9800;color:#fff;border:none;border-radius:8px;cursor:pointer;font-size:16px;">🎤 Mute</button>
                <button id="cam-btn" style="padding:10px 20px;background:#2196F3;color:#fff;border:none;border-radius:8px;cursor:pointer;font-size:16px;">📷 Cam Off</button>
                <button id="hangup-btn" style="padding:10px 20px;background:#F44336;color:#fff;border:none;border-radius:8px;cursor:pointer;font-size:16px;">📵 Hang Up</button>
              </div>
            </div>
          </div>
          <div id="announce-bar" style="margin-top:16px;display:flex;gap:8px;">
            <input id="announce-input" type="text" placeholder="Type an announcement..."
              style="flex:1;padding:8px 12px;border:1px solid #ccc;border-radius:8px;font-size:14px;">
            <button id="announce-btn"
              style="padding:8px 16px;background:#2196F3;color:#fff;border:none;border-radius:8px;cursor:pointer;font-size:14px;">
              📢 Announce
            </button>
          </div>
          <div id="call-log" style="margin-top:16px;"></div>
          <div id="quick-call-bar" style="margin-top:16px;padding-top:14px;border-top:1px solid #e0e0e0;">
            <div style="font-size:12px;color:#888;margin-bottom:10px;text-transform:uppercase;letter-spacing:0.5px;">Quick Call</div>
            <div id="quick-call-icons" style="display:flex;flex-wrap:wrap;gap:14px;justify-content:center;"></div>
          </div>
        </div>
      </ha-card>
    `;

    this.querySelector("#announce-btn").addEventListener("click", () => this._sendAnnounce());
    this.querySelector("#announce-input").addEventListener("keydown", (e) => {
      if (e.key === "Enter") this._sendAnnounce();
    });
    this.querySelector("#hangup-btn").addEventListener("click", () => this._hangup());
    this.querySelector("#mute-btn").addEventListener("click", () => this._toggleMute());
    this.querySelector("#cam-btn").addEventListener("click", () => this._toggleCamera());

    this.addEventListener("click", (e) => {
      const callBtn = e.target.closest("[data-call-device]");
      if (callBtn) this._callDevice(callBtn.dataset.callDevice);
    });

    this._devices = [];
    this._room = null;
    this._callId = null;
    this._callTarget = null;
    this._callTimerInterval = null;
    this._callStartTime = null;
    this._muted = false;
    this._cameraOff = false;
    this._lastCallInfo = null;
    this._startPolling();
    loadLiveKitSDK().catch(e => console.warn("LiveKit SDK preload failed:", e));
  }

  _startPolling() {
    this._fetchDevices();
    this._pollTimer = setInterval(() => this._fetchDevices(), this._pollInterval);
  }

  async _fetchDevices() {
    const resp = await this._apiFetch("/devices");
    if (!resp) return;
    try {
      const data = await resp.json();
      this._devices = data.devices || [];
      this._renderDevices();
    } catch (e) {
      console.warn("Hermes Intercom: parse failed", e);
    }
  }

  _renderDevices() {
    const container = this.querySelector("#devices");
    if (!container) return;

    container.innerHTML = this._devices.map(d => {
      const isOnline = d.status === "online";
      const statusColor = !isOnline ? "#999"
        : d.call_state === "in_call" ? "#FF9800"
        : d.call_state === "ringing" ? "#FFD600"
        : d.call_state === "do_not_disturb" ? "#F44336"
        : "#4CAF50";

      const statusText = !isOnline ? "Offline"
        : d.call_state === "idle" ? "Available"
        : d.call_state === "in_call" ? "In Call"
        : d.call_state === "ringing" ? "Ringing"
        : d.call_state === "do_not_disturb" ? "DND"
        : d.call_state;

      const canCall = isOnline && d.call_state === "idle" && !this._room;

      return `
        <div style="background:#f5f5f5;border-radius:12px;padding:14px;display:flex;align-items:center;gap:12px;">
          <div style="width:12px;height:12px;border-radius:50%;background:${statusColor};flex-shrink:0;"></div>
          <div style="flex:1;min-width:0;">
            <div style="font-weight:600;font-size:15px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">
              ${d.display_name}
            </div>
            <div style="font-size:12px;color:#888;">
              ${d.room_location ? d.room_location + " · " : ""}${statusText}
            </div>
          </div>
          ${canCall ? `
            <button data-call-device="${d.device_id}"
              style="padding:6px 12px;background:#4CAF50;color:#fff;border:none;border-radius:8px;cursor:pointer;font-size:18px;">
              📞
            </button>
          ` : ""}
        </div>
      `;
    }).join("");

    this._renderQuickCallBar();
  }

  _renderQuickCallBar() {
    const bar = this.querySelector("#quick-call-icons");
    if (!bar) return;

    const roomIcons = {
      kitchen: "🍳", bedroom: "🛌", living: "🛋️", office: "💻",
      bathroom: "🚿", garage: "🚗", kids: "🧸", nursery: "👶",
      basement: "🏠", attic: "🏚️", patio: "☀️", garden: "🌿",
    };

    bar.innerHTML = this._devices.map(d => {
      const isOnline = d.status === "online";
      const canCall = isOnline && d.call_state === "idle" && !this._room;
      const opacity = canCall ? "1" : "0.35";
      const cursor = canCall ? "pointer" : "default";
      const location = (d.room_location || "").toLowerCase();
      const icon = Object.entries(roomIcons).find(([k]) => location.includes(k))?.[1] || "📱";
      const label = d.display_name || d.device_id;
      const borderColor = canCall ? "#4CAF50" : "#ccc";

      return `
        <div class="quick-call-icon" ${canCall ? `data-call-device="${d.device_id}"` : ""}
          style="display:flex;flex-direction:column;align-items:center;gap:4px;opacity:${opacity};cursor:${cursor};">
          <div style="width:52px;height:52px;border-radius:50%;background:#f5f5f5;border:2px solid ${borderColor};
            display:flex;align-items:center;justify-content:center;font-size:24px;transition:all 0.2s;">
            ${icon}
          </div>
          <span style="font-size:11px;color:#666;max-width:64px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;text-align:center;">
            ${label}
          </span>
        </div>
      `;
    }).join("");
  }

  // -----------------------------------------------------------------------
  // WebRTC call: signal → token → LiveKit room → mic/speaker → hangup
  // -----------------------------------------------------------------------

  async _callDevice(deviceId) {
    if (this._room) return;
    try {
      await loadLiveKitSDK();
      const LK = window.LivekitClient;
      if (!LK) throw new Error("LiveKit SDK not loaded");

      // Send call signal
      const sigResp = await this._apiFetch("/signal", {
        method: "POST",
        body: JSON.stringify({ type: "call_request", from: "ha-dashboard", to: deviceId }),
      });
      if (!sigResp) return;
      const sigData = await sigResp.json();
      if (!sigData.ok) { alert(`Call failed: ${sigData.error || "Unknown error"}`); return; }

      this._callId = sigData.call_id;
      this._callTarget = deviceId;
      const roomName = sigData.room_name;

      // Get LiveKit token
      const tokenResp = await this._apiFetch(`/token?identity=ha-dashboard&room=${encodeURIComponent(roomName)}`);
      if (!tokenResp) return;
      const tokenData = await tokenResp.json();

      // Connect to LiveKit room
      const room = new LK.Room({ adaptiveStream: true, dynacast: true });

      room.on(LK.RoomEvent.TrackSubscribed, (track, pub, participant) => {
        if (track.kind === "audio") {
          const el = track.attach();
          el.id = "hermes-call-audio";
          el.style.display = "none";
          this.appendChild(el);
        } else if (track.kind === "video") {
          const remoteVid = this.querySelector("#remote-video");
          if (remoteVid) { track.attach(remoteVid); remoteVid.style.display = "block"; }
        }
      });
      room.on(LK.RoomEvent.TrackUnsubscribed, (track) => {
        if (track.kind === "video") {
          const remoteVid = this.querySelector("#remote-video");
          if (remoteVid) { remoteVid.srcObject = null; remoteVid.style.display = "none"; }
        }
        track.detach().forEach(el => { if (el.id !== "remote-video" && el.id !== "local-video") el.remove(); });
      });
      room.on(LK.RoomEvent.ParticipantConnected, (p) => { this._updateCallStatus("Connected"); });
      room.on(LK.RoomEvent.ParticipantDisconnected, (p) => {
        if (room.remoteParticipants.size === 0) this._hangup();
      });
      room.on(LK.RoomEvent.Disconnected, () => { this._cleanupCall(); });

      await room.connect(this._livekitUrl, tokenData.token);
      await room.localParticipant.setMicrophoneEnabled(true);
      try {
        await room.localParticipant.setCameraEnabled(true);
        const localVid = this.querySelector("#local-video");
        const camPub = room.localParticipant.getTrackPublication("camera");
        if (camPub?.track && localVid) { camPub.track.attach(localVid); localVid.style.display = "block"; }
      } catch (camErr) { console.warn("Camera not available:", camErr); }
      this._room = room;
      this._showInCallUI(deviceId);
    } catch (err) {
      console.error("Hermes call error:", err);
      alert(`Call error: ${err.message}`);
      this._cleanupCall();
    }
  }

  async _hangup() {
    if (this._callId && this._callTarget) {
      try {
        await this._apiFetch("/signal", {
          method: "POST",
          body: JSON.stringify({ type: "call_hangup", from: "ha-dashboard", to: this._callTarget, call_id: this._callId }),
        });
      } catch (e) {}
    }
    this._cleanupCall();
  }

  _cleanupCall() {
    // Save last call info before clearing state
    if (this._callId && this._callStartTime) {
      const duration = Math.floor((Date.now() - this._callStartTime) / 1000);
      const target = this._devices.find(d => d.device_id === this._callTarget);
      this._lastCallInfo = {
        from: "Dashboard",
        to: target?.display_name || this._callTarget || "?",
        startedAt: new Date(this._callStartTime),
        duration: `${Math.floor(duration / 60)}:${String(duration % 60).padStart(2, "0")}`,
      };
    }
    if (this._room) { try { this._room.disconnect(); } catch (e) {} this._room = null; }
    const audioEl = this.querySelector("#hermes-call-audio");
    if (audioEl) audioEl.remove();
    // Reset video elements
    const rv = this.querySelector("#remote-video"); if (rv) { rv.srcObject = null; rv.style.display = "none"; }
    const lv = this.querySelector("#local-video"); if (lv) { lv.srcObject = null; lv.style.display = "none"; }
    this._callId = null;
    this._callTarget = null;
    this._muted = false;
    this._cameraOff = false;
    if (this._callTimerInterval) { clearInterval(this._callTimerInterval); this._callTimerInterval = null; }
    this._callStartTime = null;
    const ui = this.querySelector("#in-call-ui");
    if (ui) ui.style.display = "none";
    const muteBtn = this.querySelector("#mute-btn");
    if (muteBtn) { muteBtn.textContent = "🎤 Mute"; muteBtn.style.background = "#FF9800"; }
    const camBtn = this.querySelector("#cam-btn");
    if (camBtn) { camBtn.textContent = "📷 Cam Off"; camBtn.style.background = "#2196F3"; }
    this._renderDevices();
    this._renderLastCall();
  }

  _showInCallUI(deviceId) {
    const device = this._devices.find(d => d.device_id === deviceId);
    const name = device ? device.display_name : deviceId;
    const ui = this.querySelector("#in-call-ui");
    if (ui) ui.style.display = "block";
    this._updateCallStatus(`Calling ${name}...`);
    this._callStartTime = Date.now();
    this._callTimerInterval = setInterval(() => {
      const elapsed = Math.floor((Date.now() - this._callStartTime) / 1000);
      const timerEl = this.querySelector("#call-timer");
      if (timerEl) timerEl.textContent = `${String(Math.floor(elapsed / 60)).padStart(2, "0")}:${String(elapsed % 60).padStart(2, "0")}`;
    }, 1000);
    this._renderDevices();
  }

  _updateCallStatus(text) {
    const el = this.querySelector("#call-status");
    if (el) el.textContent = text;
  }

  _toggleMute() {
    if (!this._room) return;
    this._muted = !this._muted;
    this._room.localParticipant.setMicrophoneEnabled(!this._muted);
    const btn = this.querySelector("#mute-btn");
    if (btn) { btn.textContent = this._muted ? "🔇 Unmute" : "🎤 Mute"; btn.style.background = this._muted ? "#666" : "#FF9800"; }
  }

  _toggleCamera() {
    if (!this._room) return;
    this._cameraOff = !this._cameraOff;
    this._room.localParticipant.setCameraEnabled(!this._cameraOff);
    const btn = this.querySelector("#cam-btn");
    if (btn) { btn.textContent = this._cameraOff ? "📷 Cam On" : "📷 Cam Off"; btn.style.background = this._cameraOff ? "#666" : "#2196F3"; }
    const lv = this.querySelector("#local-video");
    if (lv) lv.style.display = this._cameraOff ? "none" : "block";
  }

  _renderLastCall() {
    const logDiv = this.querySelector("#call-log");
    if (!logDiv) return;
    const info = this._lastCallInfo;
    if (info) {
      logDiv.innerHTML = `
        <div style="font-size:12px;color:#888;margin-bottom:4px;">Last Call</div>
        <div style="background:#f5f5f5;border-radius:8px;padding:10px;font-size:13px;">
          📞 ${info.from} → ${info.to} · ${info.duration} · ${info.startedAt.toLocaleTimeString()}
        </div>
      `;
    }
  }

  async _sendAnnounce() {
    const input = this.querySelector("#announce-input");
    const message = input.value.trim();
    if (!message) return;

    // Send announcement directly to each online device via token server
    const onlineDevices = this._devices.filter(d => d.status === "online");
    for (const d of onlineDevices) {
      await this._apiFetch("/signal", {
        method: "POST",
        body: JSON.stringify({ type: "announcement", from: "ha-dashboard", to: d.device_id, message }),
      });
    }
    input.value = "";
  }

  _update() {
    // Only update last call from HA sensor if we don't have a local one
    if (this._lastCallInfo) return;
    const lastCallEntity = Object.keys(this._hass.states).find(
      e => e.startsWith("sensor.hermes_intercom_last_call")
    );
    if (lastCallEntity) {
      const state = this._hass.states[lastCallEntity];
      const logDiv = this.querySelector("#call-log");
      if (logDiv && state.state !== "No calls") {
        const attrs = state.attributes;
        logDiv.innerHTML = `
          <div style="font-size:12px;color:#888;margin-bottom:4px;">Last Call</div>
          <div style="background:#f5f5f5;border-radius:8px;padding:10px;font-size:13px;">
            📞 ${attrs.from || "?"} → ${attrs.to || "?"} ·
            ${attrs.status || "unknown"} ·
            ${attrs.started_at ? new Date(attrs.started_at).toLocaleTimeString() : ""}
          </div>
        `;
      }
    }
  }

  disconnectedCallback() {
    if (this._pollTimer) clearInterval(this._pollTimer);
    this._cleanupCall();
  }

  getCardSize() {
    return 3;
  }

  static getStubConfig() {
    return {
      token_server_url: "http://192.168.211.153:8090",
      livekit_url: "ws://192.168.211.153:7880",
      api_key: "",
    };
  }
}

customElements.define("hermes-intercom-card", HermesIntercomCard);

window.customCards = window.customCards || [];
window.customCards.push({
  type: "hermes-intercom-card",
  name: "Hermes Intercom",
  description: "Intercom with real voice calls between HA dashboard and tablets via LiveKit.",
});
