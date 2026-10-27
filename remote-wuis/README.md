# PC browser relays

Two optional Node development apps share a remote-control layout:

- **API**: browser HTTP/WS → Node → TV REST/WSS. Node adds the API-key header and accepts the TV's self-signed certificate.
- **MQTT**: browser HTTP/WS → Node → broker → TV. It subscribes to state, availability and acknowledgments.

For the simple phone remote, use **Open on phone** on the TV instead; these relays require a running PC.

## Configure and run

Requires Node 20+. Copy config.example.json to a Git-ignored config.local.json. Point tvConfigPath at the TV's private Android JSON configuration and tvApiHost at its LAN address. Credentials stay in Node.

Set bindAddress to the PC's LAN address (or 0.0.0.0), with distinct apiPort and mqttPort. Defaults are 8787/8788. Allow those ports through the PC firewall when needed. With requireLogin true, choose a separate random accessToken of at least 24 characters. Disabling login exposes control to anyone who can reach the relay.

```sh
npm ci --ignore-scripts
npm run api
# In another terminal:
npm run mqtt
```

Open http://PC_LAN_IP:8787 or :8788. On Windows, start.ps1 runs both processes in the background and stop.ps1 stops the recorded processes. Pass -NodePath when needed. Multiple profiles need different ports and instance names:

```powershell
.\start.ps1 -ConfigPath .\config.living-room.local.json -Instance living-room
.\stop.ps1 -Instance living-room
```

CLI entry points also accept --config PATH. Logs/PIDs are under ignored .runtime/. A private convenience URL with #token=ACCESS_TOKEN logs in and removes the fragment after setting an HttpOnly, SameSite cookie.

## Controls and validation

State, media/memory/network information, acks and transport tests are available. App buttons depend on advertised app discovery. Text editing acts on the focused TV field; custom keyboards may need explicit developer fallback. Backspace presses can be batched; Select All is app-dependent.

MQTT commands are nonretained QoS 1 with IDs and expiry. The relay refuses commands while disconnected or state is stale, avoiding an offline input backlog. After an uncertain timeout, retry the original ID.

```sh
node tools/smoke.mjs
node tools/smoke.mjs config.living-room.local.json
```

These read-only live checks cover authentication, state streaming, REST/WSS/MQTT status acknowledgments, deduplication and public identity retrieval. They need running relays, a reachable broker and the configured TV.
