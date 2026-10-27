#!/usr/bin/env python3
"""Install the debug APK and private JSON config without opening the TV screen."""
import argparse
import json
import os
import pathlib
import shlex
import subprocess
import sys

PACKAGE = "net.phoxis.tvmqtt"
ROOT = pathlib.Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="ADB serial, e.g. 192.168.1.50:5555")
    parser.add_argument("--config", default=str(ROOT / "config.local.json"))
    parser.add_argument("--apk", default=str(ROOT / "app/build/outputs/apk/debug/app-debug.apk"))
    parser.add_argument("--skip-apk", action="store_true")
    parser.add_argument("--start", action="store_true")
    parser.add_argument("--grant-status-access", action="store_true",
                        help="Grant usage access and notification-listener access for app/media telemetry")
    parser.add_argument("--grant-text-access", action="store_true",
                        help="Enable focused-field Android text entry without changing the keyboard")
    parser.add_argument("--text-adb-key", help="Already-authorized PKCS#8 ADB key for app-private TV-local text injection")
    parser.add_argument("--adb-server-port", type=int, help="Optional separate host ADB server port")
    parser.add_argument("--push-install", action="store_true", help="Explicit push followed by device-side pm install")
    args = parser.parse_args()
    config_path = pathlib.Path(args.config).resolve()
    data = config_path.read_bytes()
    config = json.loads(data)
    env = os.environ.copy()
    key = ROOT.parent / "adb-keys/adbkey"
    if key.exists():
        env.setdefault("ADB_VENDOR_KEYS", str(key))
    adb_base = ["adb"] + (["-P",str(args.adb_server_port)] if args.adb_server_port else [])
    adb = adb_base + ["-s", args.serial]
    def run(parts, data=None, check=True):
        result = subprocess.run(parts, input=data, env=env, capture_output=True)
        if check and result.returncode:
            raise RuntimeError("ADB operation failed: " + (result.stdout+result.stderr).decode(errors="replace").strip()[:400])
        return result
    def shell(command, data=None, check=True):
        return run(adb + ["shell", "-T", command], data, check)
    def control(action):
        return run(adb + ["shell", "am", "broadcast", "-f", "0x20", "-a", PACKAGE + ".DEBUG_" + action,
                          "-n", PACKAGE + "/.DebugReceiver"], check=False)
    run(adb_base + ["connect", args.serial])
    if not args.skip_apk:
        if args.push_install:
            target="/data/local/tmp/mqttv-"+PACKAGE+".apk"
            try:
                run(adb + ["push",str(pathlib.Path(args.apk).resolve()),target])
                run(adb + ["shell","pm","install","-r",target])
            finally:
                run(adb + ["shell","rm","-f",target],check=False)
        else:
            run(adb + ["install", "--no-streaming", "-r", str(pathlib.Path(args.apk).resolve())])
        print("APK installed")
    control("STOP")
    # Force-stop completes old worker shutdown before changing its configuration.
    run(adb + ["shell", "am", "force-stop", PACKAGE])
    command = "run-as " + PACKAGE + " sh -c " + shlex.quote("mkdir -p files && cat > files/config.json")
    shell(command, data)
    tls = config["mqtt"].get("tls", {})
    if tls.get("caFile") and not tls.get("insecureSkipVerify", False):
        name = tls["caFile"]
        if pathlib.Path(name).name != name or ".." in name:
            raise ValueError("caFile must be a simple file name")
        cert = (config_path.parent / name).read_bytes()
        shell("run-as " + PACKAGE + " sh -c " + shlex.quote("cat > files/" + shlex.quote(name)), cert)
    if args.grant_status_access:
        run(adb + ["shell", "appops", "set", PACKAGE, "GET_USAGE_STATS", "allow"])
        component = PACKAGE + "/" + PACKAGE + ".MediaAccessService"
        existing = shell("settings get secure enabled_notification_listeners").stdout.decode().strip()
        listeners = [] if existing in ("", "null") else existing.split(":")
        if component not in listeners:
            listeners.append(component)
            shell("settings put secure enabled_notification_listeners " + shlex.quote(":".join(listeners)))
        run(adb + ["shell","cmd","notification","allow_listener",component],check=False)
        print("Current-app and media-session reporting enabled")
    text_adb = config.get("tv", {}).get("adb", config.get("tv", {}).get("textAdb", {}))
    if args.text_adb_key:
        if not text_adb.get("enabled"):
            raise ValueError("Enable tv.adb or tv.textAdb before installing its key")
        name = text_adb.get("keyFile", "text-adbkey.pem")
        if pathlib.Path(name).name != name or ".." in name:
            raise ValueError("Text key must be an app-private file name")
        key_data = pathlib.Path(args.text_adb_key).read_bytes()
        if b"-----BEGIN PRIVATE KEY-----" not in key_data or len(key_data) > 16384:
            raise ValueError("Expected a PKCS#8 private key")
        shell("run-as " + PACKAGE + " sh -c " + shlex.quote("umask 077; cat > files/" + shlex.quote(name)), key_data)
        print("Already-authorized ADB key installed in app-private storage; key was not printed")
    if args.grant_text_access:
        component = PACKAGE + "/" + PACKAGE + ".RemoteTextService"
        existing = shell("settings get secure enabled_accessibility_services").stdout.decode().strip()
        services = [] if existing in ("", "null") else existing.split(":")
        if component not in services:
            services.append(component)
            shell("settings put secure enabled_accessibility_services " + shlex.quote(":".join(services)))
        shell("settings put secure accessibility_enabled 1")
        print("Focused text entry enabled; existing keyboard and accessibility services preserved")
    print("Private configuration installed; credential values were not printed")
    if args.start:
        result = control("START")
        if result.returncode:
            raise RuntimeError("Could not start the debug service; use Start in the setup screen")
        print("Background service start requested; no activity opened")

if __name__ == "__main__":
    main()
