# MQTTV for Samsung Tizen — experimental

Tizen counterpart to the Android client, sharing MQTT command/ack/state topics and the header-authenticated REST/WebSocket API. This source is experimental: complete installation, control behavior and startup/standby persistence still require physical-TV validation.

## Build

With JDK 17, Android SDK 35, Python 3.10+ and Node 20+, run from the repository root:

```sh
npm --prefix tizen-client ci --ignore-scripts
python3 tools/build-all.py
```

Android and Tizen build concurrently using the Android Gradle version. Outputs/checksums go to ignored dist/, with logs in .integration/builds/. GitHub Actions builds both targets independently; CI artifacts are development packages, separate from official Android releases.

For a maintained signed Android APK in the combined build, provide the Android signing variables and add --android-variant release. Add --tizen-profile PROFILE and --tizen-cli /path/to/tizen to sign the WGT using an existing authorized profile. Without a Tizen profile the WGT remains explicitly unsigned.

For Tizen alone:

```sh
npm ci --ignore-scripts
npm run check
npm test
npm run build
```

## Packages and installation limits

- **.unsigned.wgt** contains the TV settings UI and service. It must be signed with a certificate accepted by the target TV before installation. It is not an ordinary APK or an install-ready stock-TV release.
- **.service.tar.gz** is an optional development artifact for custom owner-administered environments, enabled with --service-bundle. It is not built by default or a verified stock-TV installer.

Use an existing Tizen SDK signing profile with `TIZEN_CLI=/path/to/tizen npm run build -- --sign PROFILE`. The optional --on-boot setting requires a profile authorizing privileged startup. By default the WGT starts its service when the UI opens. Packages include no Samsung binaries, device credentials or private certificates.

## Configuration and API

Use config.example.json. The samsung-tizen driver connects to the TV's local loopback services. Blank base topics become tvs/<normalized-device-name>; choose a unique MQTT client ID. MQTT is optional when the API is enabled.

Data endpoints require X-API-Key: GET /api/state, POST /api/command, WebSocket /api/ws, GET /api/public-key and GET /api/certificate. Browser settings at /settings authenticate before reading credentials; /api/settings and /api/pair use the same header.

The TV UI includes a Samsung-specific guide under **Advanced options**, covering Developer Mode, certificates, installation and local pairing. Android Accessibility does not apply to Tizen. The [Samsung TV device guide](https://developer.samsung.com/smarttv/develop/getting-started/using-sdk/tv-device.html) and [certificate guide](https://developer.samsung.com/smarttv/develop/getting-started/setting-up-sdk/creating-certificates.html) describe the SDK installation requirements.

The packaged TV UI uses a separate loopback-only setup listener on 8790 and an installation-specific app-private key. Save & apply reloads listeners. HTTPS identities are software keys in app-private storage; preserve them with configuration during upgrades. Private keys are never returned by public-identity endpoints.

## Current limits

Control code covers navigation/media keys, power, volume, HDMI key selection, app IDs and native IME text. Socket acceptance does not establish a visible effect. Generic Backspace and Select All are explicitly unsupported pending reliable platform support. Foreground app/media and current input are marked unavailable rather than guessed. Free-memory metrics differ from Android's reclaimable-memory measurement.

Tests use fixture drivers and local protocol/API simulations. An optional live-broker fixture check is available as node tools/mqtt-smoke.cjs /path/to/private-config.json; it uses an isolated namespace and cleans up retained topics without controlling a TV.

Runtime dependencies are pinned and their license notices are included in both formats.
