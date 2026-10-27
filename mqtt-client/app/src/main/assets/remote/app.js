(() => {
  const root = document.documentElement;
  const palettes = new Set(["original", "d4rk", "terminal", "bubbly", "paper", "ocean", "retro", "professional", "reference", "contrast", "tron"]);
  // Resolve saved appearance before CSS/first paint. Credentials are never stored here.
  try {
    const palette = localStorage.getItem("tvmqtt-simple-palette");
    const mode = localStorage.getItem("tvmqtt-simple-mode");
    root.dataset.palette = palettes.has(palette) ? palette : "original";
    root.dataset.theme = ["dark", "light"].includes(mode) ? mode : "dark";
  } catch {
    root.dataset.palette = "original";
    root.dataset.theme = "dark";
  }

  const initialize = () => {
    const $ = selector => document.querySelector(selector);
    const preview = window.tvRemotePreview;
    const notice = $("#notice");
    const pending = new Map();
    const seenAcks = new Set();
    let state, live, retry, signedIn = false, logCount = 0;
    let transport = { rest: false, websocket: false };
    let lastRestRead = 0;
    const uid = () => "wui-" + Array.from(crypto.getRandomValues(new Uint8Array(16)), value => value.toString(16).padStart(2, "0")).join("");
    const labels = { volume_up: "Volume up", volume_down: "Volume down", power_on: "Power on", power_off: "Power off", fast_forward: "Fast forward", select_all: "Select all" };
    const actionLabel = ({ action }) => labels[action] ?? (action.charAt(0).toUpperCase() + action.slice(1).replaceAll("_", " "));
    const makeCommand = ({ action, value }) => ({
      id: uid(), action, ...(value === undefined ? {} : { value }),
      timestamp: new Date().toISOString(), expires_at: new Date(Date.now() + 20000).toISOString()
    });

    const addLog = ({ message, tone = "neutral", detail = "", timestamp = new Date().toISOString() }) => {
      $("#acks .empty-log")?.remove();
      const row = document.createElement("li");
      row.dataset.tone = tone;
      const title = document.createElement("div");
      title.className = "log-title";
      const text = document.createElement("span");
      text.className = "log-message";
      text.textContent = message;
      const time = document.createElement("time");
      time.dateTime = timestamp;
      time.textContent = new Date(timestamp).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
      title.append(text, time);
      row.append(title);
      if (detail) {
        const meta = document.createElement("div");
        meta.className = "log-meta";
        meta.textContent = detail;
        row.append(meta);
      }
      $("#acks").prepend(row);
      while ($("#acks").children.length > 50) $("#acks").lastChild.remove();
      logCount++;
      $("#log-count").textContent = String(logCount);
    };
    const fail = ({ error }) => {
      const message = error.message ?? String(error);
      notice.textContent = message;
      addLog({ message, tone: "danger" });
    };
    const setIndicator = ({ selector, status, label, description }) => {
      const node = $(selector);
      node.dataset.status = status;
      node.querySelector("span").textContent = label;
      node.setAttribute("aria-label", description + ": " + label);
      node.title = description + ": " + label;
    };
    const render = () => {
      const stale = state?.expires_at && Date.parse(state.expires_at) <= Date.now();
      const power = stale ? "unknown" : (state?.power ?? "unknown");
      const powerStatus = power === "active" ? "on" : (power === "standby" ? "off" : "unknown");
      setIndicator({ selector: "#power", status: powerStatus, label: { on: "On", off: "Off", unknown: "Unknown" }[powerStatus], description: "Power" });
      const appConnected = transport.rest || transport.websocket;
      const tvStatus = (stale || !appConnected || !state) ? "unknown" : (state.reachable === true ? "on" : (state.reachable === false ? "off" : "unknown"));
      setIndicator({ selector: "#connection", status: tvStatus, label: { on: "Online", off: "Offline", unknown: "Unknown" }[tvStatus], description: "TV online" });
      $("#volume").textContent = state?.capabilities?.volume_keys === false ? "Volume unavailable" : state?.volume_scope === "external" ? "Volume TV/receiver" : ("Volume " + (state?.volume ?? "\u2014") + (state?.muted ? " \u00b7 muted" : ""));
      $("#volume").title = state?.volume_support === "unknown" ? "TV/receiver volume support is unknown" : "";
      const connections = [transport.rest ? "REST" : "", transport.websocket ? "WS" : ""].filter(Boolean);
      $("#transport-label").textContent = connections.length ? connections.join(" + ") : "Offline";
      $("#link-status").classList.toggle("is-connected", Boolean(appConnected));
      $("#link-status").setAttribute("aria-label", connections.length ? "App connected over " + connections.join(" and ") : "App disconnected");
      $("#link-status").title = connections.length ? "App connected over " + connections.join(" and ") : "App disconnected";
      $("#subtitle").textContent = state?.device_name ?? state?.device_id ?? "Your TV";
      $("#watching").textContent = state?.media?.display_title ?? state?.media?.title ?? state?.content?.title ?? state?.foreground_app?.label ?? "\u2014";
      $("#memory").textContent = state?.memory ? state.memory.available_mb + " / " + state.memory.total_mb + " MiB available" : "\u2014";
      $("#network").textContent = state?.network?.primary_ip ?? "\u2014";
      $("#updated").textContent = state?.timestamp ? new Date(state.timestamp).toLocaleString() + (stale ? " (stale)" : "") : "\u2014";
      $("#control-setup").hidden = state?.control?.error !== "control_access_unavailable";
      document.querySelectorAll('[data-input], [data-key="input"]').forEach(button => {
        button.disabled = state?.capabilities?.input === false;
      });
      document.querySelectorAll('[data-key="volumeDown"], [data-key="volumeUp"], [data-key="mute"]').forEach(button => {
        button.disabled = state?.capabilities?.volume_keys === false;
        button.title = button.disabled ? "Volume control unavailable in this configuration" : button.getAttribute("aria-label");
      });
      $("#state-json").textContent = state ? JSON.stringify({ state }, null, 2) : "Waiting for state";
      $("#availability-json").textContent = JSON.stringify({
        tv_reachable: state?.reachable ?? null, rest_connected: transport.rest,
        websocket_connected: transport.websocket, stale: Boolean(stale), timestamp: state?.timestamp ?? null
      }, null, 2);
    };
    const acknowledgement = ({ ack, via }) => {
      if (!ack || seenAcks.has(ack.command_id + ":" + ack.timestamp)) return;
      seenAcks.add(ack.command_id + ":" + ack.timestamp);
      if (seenAcks.size > 100) seenAcks.delete(seenAcks.values().next().value);
      addLog({
        message: (ack.ok ? "Acknowledged " : "Failed ") + actionLabel({ action: ack.action }),
        tone: ack.ok ? "mid" : "danger", timestamp: ack.timestamp,
        detail: via.toUpperCase() + " \u00b7 " + ack.command_id + (ack.error ? " \u00b7 " + ack.error.message : "")
      });
      $("#ack-json").textContent = JSON.stringify(ack, null, 2);
    };
    const authenticationLost = () => {
      signedIn = false;
      transport = { rest: false, websocket: false };
      clearTimeout(retry);
      if (live) live.close();
      $("#login-panel").hidden = false;
      $("#remote").hidden = true;
      render();
    };
    // Callers own visible failure handling; a failed request never sends a command twice with a new ID.
    const request = async ({ path, options = {} }) => {
      const response = await fetch(path, { ...options, signal: AbortSignal.timeout(15000) });
      const value = await response.json();
      if (!response.ok) {
        if (response.status === 401) authenticationLost();
        throw new Error(value.error?.message ?? "Request failed");
      }
      return value;
    };
    const readState = async () => {
      try {
        state = (await request({ path: "/api/state" })).state;
        transport.rest = true;
        lastRestRead = Date.now();
        render();
      } catch (error) {
        transport.rest = false;
        render();
        throw error;
      }
    };
    const post = ({ cmd }) => request({ path: "/api/command", options: {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(cmd)
    } });
    const connectLive = () => {
      clearTimeout(retry);
      if (!signedIn) return;
      live = new WebSocket((location.protocol === "https:" ? "wss://" : "ws://") + location.host + "/api/ws");
      live.onopen = () => {
        transport.websocket = true;
        addLog({ message: "WebSocket connected", tone: "mid" });
        render();
      };
      live.onmessage = event => {
        try {
          const data = JSON.parse(event.data);
          if (data.state) state = data.state;
          if (data.ack) {
            acknowledgement({ ack: data.ack, via: "WS" });
            const item = pending.get(data.ack.command_id);
            if (item) {
              clearTimeout(item.timer);
              pending.delete(data.ack.command_id);
              item.resolve(data.ack);
            }
          }
          if (data.error && data.command_id) {
            const item = pending.get(data.command_id);
            if (item) {
              clearTimeout(item.timer);
              pending.delete(data.command_id);
              item.reject(new Error(data.error.message));
            }
          }
          render();
        } catch (error) { fail({ error }); }
      };
      live.onclose = () => {
        transport.websocket = false;
        render();
        for (const item of pending.values()) {
          clearTimeout(item.timer);
          item.reject(new Error("WebSocket disconnected"));
        }
        pending.clear();
        if (signedIn) {
          addLog({ message: "WebSocket disconnected. Retrying; REST remains available." });
          retry = setTimeout(connectLive, 2000);
        }
      };
    };
    const execute = async ({ cmd }) => {
      let ack, via = "REST";
      if (preview) {
        via = transport.websocket ? "WS" : "REST";
        ack = await preview.command({ command: cmd });
        state = preview.state();
      } else if (live?.readyState === WebSocket.OPEN) {
        via = "WS";
        try {
          ack = await new Promise((resolve, reject) => {
            const timer = setTimeout(() => {
              pending.delete(cmd.id);
              return reject(new Error("WebSocket timed out"));
            }, 7000);
            pending.set(cmd.id, { resolve, reject, timer });
            live.send(JSON.stringify(cmd));
          });
        } catch {
          // Retry the same ID over REST; deduplication is shared by both transports.
          via = "REST";
          ack = await post({ cmd });
        }
      } else ack = await post({ cmd });
      acknowledgement({ ack, via });
      render();
      if (!ack.ok) throw new Error(ack.error?.message ?? "TV rejected the command");
      return ack;
    };
    const send = async ({ action, value }) => {
      notice.textContent = "Sending " + actionLabel({ action }).toLowerCase() + "...";
      const ack = await execute({ cmd: makeCommand({ action, value }) });
      notice.textContent = "Acknowledged " + actionLabel({ action }).toLowerCase();
      return ack;
    };
    document.querySelectorAll("[data-key]").forEach(button => button.addEventListener("click", async () => {
      try {
        const key = button.dataset.key;
        if (key === "power") {
          if (!["active", "standby"].includes(state?.power) || (state?.expires_at && Date.parse(state.expires_at) <= Date.now())) {
            throw new Error("Refresh state before toggling power.");
          }
          await send({ action: state.power === "active" ? "power_off" : "power_on" });
          return;
        }
        const actions = { volumeUp: "volume_up", volumeDown: "volume_down", forward: "fast_forward" };
        if (key === "input") await send({ action: "key", value: "input" });
        else await send({ action: actions[key] ?? key });
      } catch (error) { fail({ error }); }
    }));
    document.querySelectorAll("[data-input]").forEach(button => button.addEventListener("click", () => {
      send({ action: "input", value: button.dataset.input }).catch(error => fail({ error }));
    }));
    const sendText = async () => {
      const field = $("#text");
      if (!field.value) return;
      const value = field.value;
      await send({ action: "text", value });
      if (field.value === value) field.value = "";
    };
    $("#send").addEventListener("click", () => sendText().catch(error => fail({ error })));
    $("#text").addEventListener("keydown", event => {
      if (event.key === "Enter") sendText().catch(error => fail({ error }));
    });
    let queuedDeletes = 0, deleting = false, deleteTimer, holdDelay, holdRepeat;
    const stopDeleting = () => { clearTimeout(holdDelay); clearInterval(holdRepeat); };
    const flushDeletes = async () => {
      clearTimeout(deleteTimer);
      deleteTimer = null;
      if (deleting || !queuedDeletes) return;
      deleting = true;
      const count = queuedDeletes;
      queuedDeletes = 0;
      try { await send({ action: "backspace", value: count }); }
      catch (error) { queuedDeletes = 0; stopDeleting(); fail({ error }); }
      finally { deleting = false; if (queuedDeletes) void flushDeletes(); }
    };
    const queueDelete = () => {
      queuedDeletes = Math.min(240, queuedDeletes + 1);
      notice.textContent = "Deleting on TV...";
      if (!deleting && !deleteTimer) deleteTimer = setTimeout(() => { void flushDeletes(); }, 50);
    };
    const deleteButton = $("#backspace");
    deleteButton.addEventListener("pointerdown", event => {
      if (event.button !== 0) return;
      event.preventDefault();
      stopDeleting();
      deleteButton.setPointerCapture(event.pointerId);
      queueDelete();
      holdDelay = setTimeout(() => { holdRepeat = setInterval(queueDelete, 90); }, 350);
    });
    ["pointerup", "pointercancel", "lostpointercapture"].forEach(type => deleteButton.addEventListener(type, stopDeleting));
    deleteButton.addEventListener("click", event => { if (event.detail === 0) queueDelete(); });
    deleteButton.addEventListener("contextmenu", event => event.preventDefault());
    window.addEventListener("blur", stopDeleting);
    document.addEventListener("visibilitychange", () => { if (document.hidden) stopDeleting(); });
    $("#select-all").addEventListener("click", () => send({ action: "select_all" }).catch(error => fail({ error })));
    $("#refresh").addEventListener("click", async () => {
      $("#refresh").classList.add("is-busy");
      try {
        if (preview) { state = preview.state(); render(); }
        else await readState();
        notice.textContent = "Current state received";
      } catch (error) { fail({ error }); }
      finally { $("#refresh").classList.remove("is-busy"); }
    });
    $("#public-key").addEventListener("click", async () => {
      try {
        const value = preview ? preview.publicKey() : await request({ path: "/api/public-key" });
        $("#key-json").textContent = JSON.stringify(value, null, 2);
        $("#key-details").open = true;
      } catch (error) { fail({ error }); }
    });

    const syncAppearance = () => {
      $("#theme-selector").value = root.dataset.palette;
      $("#mode-selector").value = root.dataset.theme;
      $("#theme-helper").textContent = $("#theme-selector").selectedOptions[0].textContent + " / " + $("#mode-selector").selectedOptions[0].textContent;
    };
    const saveAppearance = () => {
      root.dataset.palette = $("#theme-selector").value;
      root.dataset.theme = $("#mode-selector").value;
      try {
        localStorage.setItem("tvmqtt-simple-palette", root.dataset.palette);
        localStorage.setItem("tvmqtt-simple-mode", root.dataset.theme);
      } catch { addLog({ message: "Appearance changed. Browser storage is unavailable, so it will not be saved." }); }
      syncAppearance();
    };
    $("#theme-selector").addEventListener("change", saveAppearance);
    $("#mode-selector").addEventListener("change", saveAppearance);
    syncAppearance();

    const start = async () => {
      await readState();
      signedIn = true;
      $("#login-panel").hidden = true;
      $("#remote").hidden = false;
      addLog({ message: "REST connected", tone: "mid" });
      connectLive();
    };
    const login = async ({ key }) => {
      await request({ path: "/api/session", options: { method: "POST", headers: { "X-API-Key": key } } });
      await start();
    };
    $("#login-form").addEventListener("submit", event => {
      event.preventDefault();
      const key = $("#token").value;
      $("#token").value = "";
      login({ key }).catch(error => { $("#login-error").textContent = error.message; });
    });
    const bootstrap = new URLSearchParams(location.hash.slice(1)).get("key");
    if (location.hash) history.replaceState(null, "", location.pathname + location.search);
    if (preview) {
      state = preview.state();
      transport = preview.transport();
      signedIn = true;
      $("#remote").hidden = false;
      $("#preview-note").hidden = false;
      $("#preview-controls").hidden = false;
      preview.subscribe(({ state: nextState, transport: nextTransport }) => {
        state = nextState;
        transport = nextTransport;
        render();
      });
      preview.mount({ container: $("#preview-controls") });
      addLog({ message: "Style preview ready", detail: "Demo data only. No TV or broker connections." });
      render();
    } else if (bootstrap) {
      login({ key: bootstrap }).catch(error => { $("#login-panel").hidden = false; $("#login-error").textContent = error.message; });
    } else {
      start().catch(() => { $("#login-panel").hidden = false; });
    }
    setInterval(() => {
      if (preview) return;
      if (signedIn && (!transport.websocket || Date.now() - lastRestRead > 15000)) {
        readState().catch(() => {});
      } else render();
    }, 2500);
  };
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", initialize, { once: true });
  else initialize();
})();
