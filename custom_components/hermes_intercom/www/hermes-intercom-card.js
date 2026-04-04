/**
 * Hermes Intercom Card for Home Assistant Lovelace
 *
 * Shows all intercom tablets with status, call buttons, and a quick announce bar.
 *
 * Usage in Lovelace YAML:
 *   type: custom:hermes-intercom-card
 *   token_server_url: http://192.168.211.153:8090
 */

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
    this._pollInterval = config.poll_interval || 5000;
  }

  _initialize() {
    this.innerHTML = `
      <ha-card header="Intercom">
        <div class="card-content">
          <div id="devices" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:12px;"></div>
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

    // Event delegation for all call buttons (inline onclick won't work without Shadow DOM)
    this.addEventListener("click", (e) => {
      const callBtn = e.target.closest("[data-call-device]");
      if (callBtn) {
        this._callDevice(callBtn.dataset.callDevice);
      }
    });

    this._devices = [];
    this._startPolling();
  }

  _startPolling() {
    this._fetchDevices();
    this._pollTimer = setInterval(() => this._fetchDevices(), this._pollInterval);
  }

  async _fetchDevices() {
    try {
      const resp = await fetch(`${this._tokenServerUrl}/devices`);
      const data = await resp.json();
      this._devices = data.devices || [];
      this._renderDevices();
    } catch (e) {
      console.warn("Hermes Intercom: fetch failed", e);
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

      const canCall = isOnline && d.call_state === "idle";

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
      const canCall = isOnline && d.call_state === "idle";
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

  async _callDevice(deviceId) {
    if (!this._hass) return;
    await this._hass.callService("hermes_intercom", "call", {
      target: deviceId,
      source: "homeassistant",
    });
  }

  async _sendAnnounce() {
    const input = this.querySelector("#announce-input");
    const message = input.value.trim();
    if (!message || !this._hass) return;

    await this._hass.callService("hermes_intercom", "broadcast", {
      message: message,
      targets: "all",
    });
    input.value = "";
  }

  _update() {
    // Update call log from last_call sensor
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
  }

  getCardSize() {
    return 3;
  }

  static getStubConfig() {
    return { token_server_url: "http://192.168.211.153:8090" };
  }
}

customElements.define("hermes-intercom-card", HermesIntercomCard);

window.customCards = window.customCards || [];
window.customCards.push({
  type: "hermes-intercom-card",
  name: "Hermes Intercom",
  description: "Shows intercom tablets with status, call buttons, and quick announce.",
});
