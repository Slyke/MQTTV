(() => {
  let power = "active", reachable = true, connection = "both", listener, controls;
  const state = {
    device_id: "living-room-tv", device_name: "Living room", reachable: true, power: "active", volume: 5, muted: false,
    content: { title: "Home screen" }, memory: { available_mb: 1248, total_mb: 3072 },
    network: { primary_ip: "192.168.1.50", ips: { wlan0: ["192.168.1.50", "fd00::50"] } },
    capabilities: { input: true, text: true, navigation: true }, control: { method: "style_preview", ready: true },
    timestamp: new Date().toISOString()
  };
  const transport = () => ({ rest: connection === "both" || connection === "rest", websocket: connection === "both" || connection === "ws" });
  const snapshot = () => JSON.parse(JSON.stringify({ ...state, reachable, power }));
  const notify = () => {
    state.timestamp = new Date().toISOString();
    if (controls) {
      controls.querySelector('[aria-label="Preview power"]').value = power;
      controls.querySelector('[aria-label="Preview tv online"]').value = reachable === null ? "unknown" : (reachable ? "on" : "off");
      controls.querySelector('[aria-label="Preview app connection"]').value = connection;
    }
    if (listener) listener({ state: snapshot(), transport: transport() });
  };
  const select = ({ parent, label, values, current, change }) => {
    const field = document.createElement("label");
    const title = document.createElement("span");
    title.textContent = label;
    const input = document.createElement("select");
    input.setAttribute("aria-label", "Preview " + label.toLowerCase());
    for (const [value, text] of values) {
      const option = document.createElement("option");
      option.value = value;
      option.textContent = text;
      input.append(option);
    }
    input.value = current;
    input.addEventListener("change", () => { change({ value: input.value }); notify(); });
    field.append(title, input);
    parent.append(field);
  };
  window.tvRemotePreview = {
    state: snapshot, transport,
    subscribe: callback => { listener = callback; },
    command: async ({ command }) => {
      if (connection === "none") throw new Error("Demo app disconnected. Change the connection in Preview states.");
      if (command.action === "power_on") power = "active";
      if (command.action === "power_off") power = "standby";
      if (command.action === "volume_up") state.volume = Math.min(100, state.volume + 1);
      if (command.action === "volume_down") state.volume = Math.max(0, state.volume - 1);
      if (command.action === "mute") state.muted = !state.muted;
      if (command.action === "text") state.preview_text = command.value;
      if (command.action === "backspace") state.preview_text = (state.preview_text ?? "").slice(0, -(command.value ?? 1));
      if (command.action === "input") state.content.title = "HDMI " + command.value.split("=").pop();
      notify();
      return { command_id: command.id, action: command.action, ok: true, timestamp: new Date().toISOString(),
        received_at: command.timestamp, completed_at: new Date().toISOString(), result: { accepted: true, method: "style_preview" } };
    },
    publicKey: () => ({ preview: true, public_key_pem: "No real key in this style preview.", storage: "Demo data only. No TV or broker connection." }),
    mount: ({ container }) => {
      const heading = document.createElement("h3");
      heading.textContent = "Preview states";
      const fields = document.createElement("div");
      fields.className = "preview-settings";
      select({ parent: fields, label: "Power", values: [["active", "On"], ["standby", "Off"], ["unknown", "Unknown"]], current: power, change: ({ value }) => { power = value; } });
      select({ parent: fields, label: "TV online", values: [["on", "Online"], ["off", "Offline"], ["unknown", "Unknown"]], current: "on", change: ({ value }) => { reachable = value === "unknown" ? null : value === "on"; } });
      select({ parent: fields, label: "App connection", values: [["both", "REST + WS"], ["rest", "REST only"], ["ws", "WS only"], ["none", "Disconnected"]], current: connection, change: ({ value }) => { connection = value; } });
      const field = document.createElement("label");
      field.textContent = "TV name";
      const name = document.createElement("input");
      name.value = state.device_name;
      name.maxLength = 64;
      name.setAttribute("aria-label", "Preview TV name");
      name.addEventListener("input", () => { state.device_name = name.value; notify(); });
      field.append(name);
      fields.append(field);
      container.append(heading, fields);
      controls = fields;
    }
  };
})();
