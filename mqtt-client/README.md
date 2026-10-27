# TV MQTT & API Client

Android 8+ client for MQTT, authenticated REST/WebSocket control and live telemetry. Setup opens only when launched; background commands do not take focus.

## Setup

Install the APK from [GitHub Releases](https://github.com/Slyke/MQTTV/releases), open **Connection settings**, choose a driver and configure MQTT and/or the API. **Save & apply**, then **Start**.

- **MQTT:** URI, unique client ID, username/password and optional base topic. Certificate import/keepalive are under **Advanced MQTT options**.
- **API:** generated passkey, listener address and HTTP/HTTPS switches/ports.
- **Driver:** Sony local API URL/pre-shared key, or Shield Accessibility/developer control and volume mode.

An empty base topic becomes **tvs/<normalized-device-name>/**. Living Room TV becomes **tvs/living-room-tv/**; trailing slash is optional. Renaming the system device affects blank topics after service reload. Each TV needs a unique client ID.

On Shield, use **Enable on-device control** and enable **TV remote control** in Android Accessibility settings. Usage Access is optional for foreground-app telemetry. For native Shield playback controls, enable **Allow playback control & media reporting** in **Advanced options** (Android notification access). The same permission also provides media metadata; notification contents are not read. Imports and permission tools are in **Advanced options**. **Driver setup & permissions** explains the selected Sony/Shield driver, links to Accessibility and Developer options, and distinguishes normal setup from the optional ADB fallback. Its debug instructions cover enabling Developer options through About/Build, USB/network debugging, authorization and the development-key setup.

Settings save atomically; Cancel discards edits. Credentials, HTTPS identity and command journal survive upgrades signed with the same key.

## Phone remote and keys

**Open on phone** shows a QR URL with the current TV LAN IP and API port. The page/server run directly on the TV.

New setups bind **0.0.0.0**, enable HTTP/WS on **8788**, and leave HTTPS/WSS on **8789** disabled. HTTPS is preferred in the QR when enabled. Both listeners disabled hides the button. DHCP changes require a new browser URL/QR, without reconfiguring the listener.

The passkey travels in a URL fragment, is removed after login and is exchanged via **X-API-Key** for an HttpOnly, SameSite browser session. It is not kept in localStorage or state. Rotation invalidates sessions.

An absent passkey is generated from 32 random bytes. **Regenerate → Save & apply** activates a replacement; Cancel retains the old value. MQTT passwords and Sony keys remain manually entered, with QR display available. These controls never regenerate ADB keys.

HTTPS creates a persistent RSA-2048 self-signed AndroidKeyStore identity. **Public key QR** and **Certificate QR** show public PEM and fingerprints. **Regenerate HTTPS key pair** prepares a replacement; Save & apply activates it. The private key is never displayed or exported. Update client pins after rotation. Clearing app data/uninstalling removes configuration and identity.

The phone page includes navigation, playback, text editing, state/log accordions and light/dark themes saved in browser localStorage. WebSocket streams state/acks; ordinary commands can fall back to REST using the same ID.

## JSON configuration

On-TV settings are the normal path. For import/development, copy config.example.json or config.shield.example.json to a Git-ignored *.local.json file.

| Field | Meaning |
| --- | --- |
| mqtt.uri | mqtts://broker.example:8883 for TLS; mqtt://broker.example:1883 for plaintext |
| mqtt.clientId | Unique client ID |
| mqtt.username / password | Broker credentials |
| mqtt.baseTopic | Optional; defaults to tvs/<device-name>/ |
| mqtt.tls.insecureSkipVerify | TLS without certificate/hostname validation |
| mqtt.tls.caFile / caCertificatePem | Imported or inline PEM for verified self-signed TLS |
| mqtt.tls.trustSystemCAs | Also trust system roots during verification |
| tv.driver | sony-bravia or nvidia-shield |
| tv.baseUrl / preSharedKey | Sony local HTTP API and matching key |
| tv.timeoutMs | Control timeout |
| tv.controlMethod | Shield native (default Accessibility) or explicit adb fallback |
| tv.volumeControl | Shield system (default TV/receiver route) or device (local PCM stream) |
| tv.adb.enabled / port / keyFile | Optional loopback developer connection, with app-private authorized key |
| api.passkey | API header credential; generated if blank |
| api.bindAddress | Default 0.0.0.0 |
| api.http.enabled / port | New setup defaults true / 8788 |
| api.https.enabled / port | Defaults false / 8789 |
| api.maxConnections / maxWebSockets | Bounded HTTP/WS connections |
| api.requestTimeoutMs | Maximum command/state wait |
| service.statePollSeconds | Default 5 |
| service.stateHeartbeatSeconds | Default 60; 0 disables heartbeat |
| service.stateRetain | Default true |
| service.memoryChangeThresholdMb | Available-memory change threshold; default 8 MiB |
| service.startOnBoot | Restart an enabled service after boot |
| service.keepCpuAwake / keepWifiAwake | Partial CPU/Wi-Fi locks; do not turn on screen |
| service.deduplicationEntries | Persistent recent IDs; default 256 |
| service.maximumCommandAgeSeconds | Default 120; 0 disables age check |

TLS accepts mqtts://, ssl:// and tls://; plaintext accepts mqtt:// and tcp://. Scheme determines encryption independently of port. For verified self-signed TLS, disable insecureSkipVerify and provide the matching certificate/CA. Examples permit unverified TLS for private-LAN deployments.

## Drivers and limits

**Sony:** enable compatible local IP-control APIs and configure the pre-shared key. HTTP/IRCC commands are authenticated. Standard text uses the TV text-form API or focused Android field. Custom keyboards may return Illegal State and require the optional developer fallback.

**Shield:** Accessibility handles Home/Back, focused D-pad/click actions and standard editable fields. System APIs handle audio, power and app launching. Playback commands target the foreground app's loaded Android media session, or the current playing session when the foreground app has none. Play/Pause use explicit transport commands rather than generic media-key broadcasts. When the foreground player has no usable session, the client can use Accessibility to invoke a single enabled playback button with an exact semantic label. This also handles players whose published media session is stale. It uses standard localized Leanback labels where available, with no coordinates or app-specific view IDs. Controls faded out by a player can still work if Android exposes their standard click actions. Visible controls take precedence; hidden controls are used only when unambiguous. The app does not reveal controls, tap screen coordinates or inject keys. If an app removes hidden controls from Accessibility entirely, this path is unavailable. Missing or ambiguous controls return an error. Acks identify the target package and method android_media_session or android_accessibility_playback. They confirm dispatch; actual playback state is reported separately in media telemetry. Apps without usable Accessibility nodes return an unsupported-control error. The app never silently enables ADB.

The default **system** volume mode requests relative keys through Android. Whether the connected TV/receiver responds depends on its support and configuration. A physical remote's IR controls do not imply app access to its IR blaster. Emitting a CEC message does not prove the TV adjusted volume.

External volume support is reported as volume_support=unknown. Relative buttons remain available to request volume through Android. Neither the CEC setting nor fixed local HDMI output proves that a connected TV/receiver will accept those requests. State reports hdmi_cec_volume_enabled as configuration information only. An acknowledgment confirms dispatch to Android, not a verified volume change on the television. Known local failures, such as fixed PCM output or an unavailable developer connection, may disable controls and return errors.

External volume/mute readback is unavailable: volume/muted are null, volume_scope is external and volume_readback is false. Absolute volume and explicit mute-on/off are rejected. Local stream diagnostics remain separate under device_volume.

The optional **device** volume mode controls/reports the Shield PCM stream. It does not control bitstreamed Dolby/DTS or represent attached-TV volume. Shield HDMI input switching is unavailable. Power describes the Shield; attached-display behavior depends on CEC.

For custom apps needing developer fallback, explicitly choose **Developer mode (ADB fallback)**, enable network debugging and install an app-private key already authorized on that TV:

~~~sh
python3 tools/install.py --serial 192.168.1.50:5555 --start \
  --grant-text-access --text-adb-key /path/to/authorized/adbkey
~~~

This helper requires a debug APK. Connections use fixed loopback; the API accepts control/text, not arbitrary shell commands. ADB custom-keyboard text supports printable ASCII; standard accessible fields support Unicode. Text/field contents are not returned in acks. Backspace supports batches; Select All depends on the app.

Deep standby can suspend networking/service despite restart requests and wake locks. Force-stop requires opening/starting the app again.

## MQTT topics and commands

For base **tvs/living-room**, append:

| Topic | Payload | Retained |
| --- | --- | --- |
| cmnd/COMMAND | JSON command | Never |
| stat/RESULT | Acknowledgment | No |
| tele/STATE | TV/device state | Configurable; default yes |
| tele/LWT | Availability, connection-time metadata | Yes |

Broker ACLs must cover the namespace, e.g. tvs/living-room/# or tvs/#. Commands/acks use QoS 1. Retained commands are rejected; clean sessions prevent an offline input backlog. A private disk outbox retries MQTT acks after reconnect.

~~~json
{"id":"phone-001","action":"text","value":"search text"}
~~~

| Action | Value |
| --- | --- |
| status / status_refresh | Omit |
| power_on / power_off | Omit |
| volume_up / volume_down / mute | Omit |
| volume_set | Integer 0–100; driver/mode dependent |
| mute_on / mute_off | Driver/mode dependent |
| input | Sony HDMI name or input URI |
| key | up/down/left/right/ok/back/home, supported playback/navigation keys |
| Directional/media names | Also accepted directly as actions |
| apps | Returns advertised titles/URIs |
| launch | Title/URI from discovery |
| text | Up to 240 characters |
| backspace | Omit or count 1–240 |
| select_all | Omit; supported focused fields |

Optional timestamp/expires_at use current ISO-8601 UTC values. Reuse an ID only to retry the same command. Acks include command_id, action, ok, received_at, completed_at, timestamp, duration_ms and result/error. A lost response leaves execution uncertain: retry the same ID.

## REST and WebSocket

Pass **X-API-Key**. The TV phone page can use its authenticated browser session. Query strings do not authenticate.

| Method/path | Result |
| --- | --- |
| GET / | Static phone remote |
| POST /api/session | Header-authenticated browser login |
| GET /api/state | {"state": {...}} |
| POST /api/command | Same MQTT object → raw acknowledgment |
| WebSocket /api/ws | Raw commands in; {"ack": {...}} and {"state": {...}} out |
| GET /api/public-key | Public PEM, certificate, validity, fingerprints |
| GET /api/certificate | Public certificate PEM |

All data/identity endpoints require authentication. MQTT/REST/WS share one serialized processor and persistent journal. REST/WS remain available without the broker. Limits include 16 KiB commands, bounded peers/queues and socket timeouts.

## State and availability

State includes timestamp, power, supported volume/input fields, available foreground app/media, memory, network IPs per interface, primary IP, storage, uptime and system information. Unavailable readings are null or carry capability/readiness metadata. Meaningful changes and optional heartbeats publish updates; RAM changes use the configured threshold.

Check retained state against its timestamp and availability. The will is registered at CONNECT: last_update and last_ips describe that registration snapshot, failure timestamp is null, and metadata_scope identifies the scope. It cannot measure the moment of an unexpected disconnect. Combine LWT with the latest retained STATE; subscribers can record their receive time.

## Build and checks

Requires JDK 17, SDK platform 35/build-tools 35.0.0. Set ANDROID_HOME or an ignored local.properties. The Gradle wrapper/dependencies are pinned.

~~~sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
~~~

Development APK: app/build/outputs/apk/debug/app-debug.apk. Debug-only tools use run-as and a shell-protected start receiver.

Official downloads are signed, non-debuggable builds. Provide **MQTTV_KEYSTORE_FILE**, **MQTTV_STORE_PASSWORD**, **MQTTV_KEY_ALIAS** and **MQTTV_KEY_PASSWORD**, then run:

~~~sh
./gradlew :app:assembleRelease :app:lintRelease
~~~

Without signing variables a direct Gradle release APK is unsigned. Keep the same key for updates. The root build-all.py accepts --android-variant release to collect a signed APK alongside the Tizen WGT; that mode requires all four signing variables. The default combined build uses a development APK.

Optional developer tools:

~~~sh
python3 -m pip install -r tools/requirements.txt
python3 tools/send.py status
python3 tools/integration_test.py --serial 192.168.1.50:5555
python3 tools/api_integration_test.py --serial 192.168.1.50:5555
~~~

Live suites need an already-authorized debug installation/private config. They restore temporary settings and avoid waking the TV. Normal installation uses an APK installer and on-TV settings, without development tools.

See [third-party notices](THIRD_PARTY.md).
