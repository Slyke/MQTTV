# Simple remote style preview

Requires Node 20+. Run node server.mjs from this directory (optional port argument, default 8800), or start.ps1 on Windows. The Windows launcher accepts -NodePath; stop.ps1 stops its recorded process.

Open http://localhost:8800/ on the PC, or http://PC_LAN_IP:8800/ from a phone. The server binds 0.0.0.0. Use /mobile?width=390 for a phone-width view; widths 320, 360, 430 and 768 are also available.

The preview serves the APK's actual HTML/CSS/JS with simulated state and commands in browser memory. It does not read private configuration or contact a TV/broker; its Content Security Policy blocks those connections.

Open Acknowledgments & logs for simulated power, online/transport and name controls. Appearance selects the Original theme or included palette families, plus light/dark mode. Browser localStorage preserves the choice. New UI strings are cataloged in en-US.json.

The /artwork page previews the APK's source SVG icon and TV banner. This server is a development tool, not the TV control service.
