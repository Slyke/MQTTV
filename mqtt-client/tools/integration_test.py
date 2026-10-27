#!/usr/bin/env python3
"""Standby-safe APK tests against the configured broker. Restores the original TLS config."""
import argparse
import copy
import datetime
import json
import pathlib
import queue
import subprocess
import sys
import threading
import time
import uuid
import urllib.parse
import paho.mqtt.client as mqtt
from mqtt_config import read_config, configure

ROOT = pathlib.Path(__file__).resolve().parents[1]
PACKAGE = "net.phoxis.tvmqtt"

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--config", default=str(ROOT / "config.local.json"))
    args = parser.parse_args()
    original, directory = read_config(args.config)
    base = original["mqtt"]["baseTopic"] + "/validation-" + uuid.uuid4().hex[:8]
    work = ROOT / ".integration"
    work.mkdir(mode=0o700, exist_ok=True)
    runtime = work / "runtime.local.json"
    messages = queue.Queue()
    connected = threading.Event()
    subscribed = threading.Event()
    results = []
    online_sessions = {}
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="tv-validator-" + uuid.uuid4().hex[:12])
    host, port, keepalive = configure(client, original, directory)

    def on_connect(client, userdata, flags, reason, properties):
        if reason.is_failure:
            return
        connected.set()
        client.subscribe(original["mqtt"]["baseTopic"] + "/#", 1)

    def on_subscribe(client, userdata, mid, reasons, properties):
        if not any(reason.is_failure for reason in reasons):
            subscribed.set()

    def on_message(client, userdata, message):
        body = message.payload.decode(errors="replace")
        if message.topic.endswith("/tele/LWT"):
            try:
                value = json.loads(body)
                if value.get("online"):
                    online_sessions[message.topic[:-len("/tele/LWT")]] = value.get("session_id")
            except ValueError:
                pass
        messages.put((time.monotonic(), message.topic, body, message.retain))

    client.on_connect, client.on_subscribe, client.on_message = on_connect, on_subscribe, on_message
    client.connect(host, port, keepalive)
    client.loop_start()
    if not connected.wait(10) or not subscribed.wait(10):
        raise RuntimeError("Could not connect/subscribe to the configured broker")

    backlog = []
    def wait(predicate, timeout=20):
        for index, item in enumerate(backlog):
            if predicate(item):
                return backlog.pop(index)
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("Timed out waiting for the app's MQTT response")
            item = messages.get(timeout=remaining)
            if predicate(item):
                return item
            backlog.append(item)

    def record(name):
        results.append(name)
        print("PASS " + name, flush=True)

    def presence(message, namespace, online, marker=0):
        if message[0] < marker or message[1] != namespace + "/tele/LWT" or message[3]:
            return False
        try:
            return json.loads(message[2]).get("online") is online
        except ValueError:
            return False

    def retained_snapshot(topic):
        complete = threading.Event()
        output = []
        probe = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="tv-retained-" + uuid.uuid4().hex[:10])
        probe_host, probe_port, probe_keepalive = configure(probe, original, directory)
        probe.on_connect = lambda client, *args: client.subscribe(topic, 1)
        def received(client, userdata, message):
            if message.retain and message.topic == topic:
                output.append(json.loads(message.payload))
                complete.set()
        probe.on_message = received
        try:
            probe.connect(probe_host, probe_port, probe_keepalive)
            probe.loop_start()
            if not complete.wait(10):
                raise TimeoutError("Retained snapshot was not available to a new subscriber")
            return output[0]
        finally:
            probe.disconnect()
            probe.loop_stop()

    def install(config, install_apk=False):
        runtime.write_text(json.dumps(config))
        runtime.chmod(0o600)
        command = [sys.executable, str(ROOT / "tools/install.py"), "--serial", args.serial,
                   "--config", str(runtime), "--start", "--grant-status-access"]
        if not install_apk:
            command.append("--skip-apk")
        previous_session = online_sessions.get(base)
        marker = time.monotonic()
        subprocess.run(command, check=True, stdout=subprocess.DEVNULL)
        wait(lambda m: presence(m, base, True, marker) and json.loads(m[2]).get("session_id") != previous_session)
        return marker

    def command(action, value=None, id=None, **extra):
        result = {"id": id or str(uuid.uuid4()), "action": action, **extra}
        if value is not None:
            result["value"] = value
        return result

    def exchange(payload, require_duplicate=False):
        client.publish(base + "/cmnd/COMMAND", json.dumps(payload), qos=1, retain=False).wait_for_publish(5)
        def matches(message):
            if message[1] != base + "/stat/RESULT":
                return False
            try:
                ack = json.loads(message[2])
                return ack.get("command_id") == payload.get("id") and (not require_duplicate or ack.get("duplicate", False))
            except ValueError: return False
        return json.loads(wait(matches)[2])

    def expect_error(payload, code):
        ack = exchange(payload)
        assert not ack["ok"] and ack["error"]["code"] == code, ack
        return ack

    test = copy.deepcopy(original)
    test["mqtt"]["baseTopic"] = base
    test["mqtt"]["clientId"] = original["mqtt"]["clientId"] + "-validation"
    test.setdefault("service", {})["statePollSeconds"] = 2
    test["service"]["stateHeartbeatSeconds"] = 4
    test["service"]["stateRetain"] = True
    try:
        marker = install(test, install_apk=True)
        ack = exchange(command("status"))
        assert ack["ok"] and ack["result"]["power"] == "standby", ack
        assert ack["result"]["memory"]["available_bytes"] > 0
        for key in ("received_at", "completed_at", "timestamp"):
            datetime.datetime.fromisoformat(ack[key].replace("Z", "+00:00"))
        record("TLS status round trip, UTC timestamps, memory and standby")
        state = json.loads(wait(lambda m: m[0] >= marker and m[1] == base + "/tele/STATE")[2])
        assert state["power"] == "standby" and "foreground_app" in state and "media" in state
        record("retained state contains TV/app/media/memory telemetry")

        network = ack["result"]["network"]
        expected_ip = args.serial.rsplit(":", 1)[0]
        assert any(expected_ip in addresses for addresses in network["ips"].values()), network
        assert network["interfaces"] and network["active_interface"]
        assert ack["result"]["storage"]["total_bytes"] > 0
        assert ack["result"]["system"]["android_sdk"] >= 26
        for secret in (original.get("api", {}).get("passkey"), original["mqtt"].get("password"),
                       original.get("tv", {}).get("preSharedKey")):
            if secret:
                assert secret not in json.dumps(ack["result"])
        record("per-interface device IPs, storage/system stats and secret-free telemetry")
        datetime.datetime.fromisoformat(state["expires_at"].replace("Z", "+00:00"))
        heartbeat_started = time.monotonic()
        later = json.loads(wait(lambda m: m[0] >= heartbeat_started + test["service"]["stateHeartbeatSeconds"] - 0.25 and
            m[1] == base + "/tele/STATE" and json.loads(m[2]).get("timestamp", "") > state["timestamp"], 12)[2])
        assert later["stale_after_seconds"] > test["service"]["stateHeartbeatSeconds"]
        record("freshness heartbeat advances state timestamp and expiry")

        repeated = command("power_off")
        first = exchange(repeated)
        assert first["ok"], first
        duplicate = exchange({**repeated, "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat()}, require_duplicate=True)
        assert duplicate["ok"] and duplicate["duplicate"], duplicate
        record("safe power command and duplicate suppression")
        expect_error({**repeated, "action": "power_on"}, "id_conflict")
        record("reused command id cannot change the action")
        expect_error(command("volume_set", 101), "invalid_value")
        expect_error(command("left", timestamp="2000-01-01T00:00:00Z"), "expired_command")
        expect_error(command("unsupported_action"), "unknown_action")
        record("invalid, stale and unsupported commands are rejected before side effects")

        install(test)
        duplicate = exchange(repeated, require_duplicate=True)
        assert duplicate["ok"] and duplicate["duplicate"], duplicate
        record("deduplication survives service/process restart")

        # Displace only this test client identity, then immediately release it.
        previous_session = online_sessions.get(base)
        marker = time.monotonic()
        displaced = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=test["mqtt"]["clientId"])
        other_host, other_port, other_keepalive = configure(displaced, test, directory)
        takeover = threading.Event()
        displaced.on_connect = lambda *arguments: takeover.set()
        displaced.connect(other_host, other_port, other_keepalive)
        displaced.loop_start()
        assert takeover.wait(10)
        displaced.disconnect()
        displaced.loop_stop()
        wait(lambda m: presence(m, base, True, marker) and json.loads(m[2]).get("session_id") != previous_session, 25)
        assert exchange(command("status"))["ok"]
        record("automatic reconnect and resubscribe after a dropped connection")

        marker = time.monotonic()
        subprocess.run(["adb", "-s", args.serial, "shell", "am", "force-stop", PACKAGE], check=True)
        lost = json.loads(wait(lambda m: presence(m, base, False, marker))[2])
        assert lost["reason"] == "connection_lost" and lost["timestamp"] is None, lost
        assert lost["metadata_scope"] == "will_registration" and lost["last_update"] and lost["last_ips"], lost
        snapshot = retained_snapshot(base + "/tele/STATE")
        assert snapshot["network"]["ips"] and snapshot["timestamp"]
        retained_presence = retained_snapshot(base + "/tele/LWT")
        assert not retained_presence["online"]
        record("broker emits retained JSON LWT with labelled IP snapshot; latest retained state survives")
        retained = command("status")
        client.publish(base + "/cmnd/COMMAND", json.dumps(retained), qos=1, retain=True).wait_for_publish(5)
        install(test)
        rejected = json.loads(wait(lambda m: m[1] == base + "/stat/RESULT" and json.loads(m[2]).get("command_id") == retained["id"])[2])
        assert rejected["error"]["code"] == "retained_command", rejected
        client.publish(base + "/cmnd/COMMAND", b"", qos=1, retain=True).wait_for_publish(5)
        record("retained commands are rejected and diagnostic retained command is cleared")

        plaintext = copy.deepcopy(test)
        uri = urllib.parse.urlparse(original["mqtt"]["uri"])
        plaintext["mqtt"]["uri"] = "mqtt://" + uri.hostname + ":1883"
        install(plaintext)
        assert exchange(command("status"))["ok"]
        assert exchange(command("power_off"))["ok"]
        record("plaintext MQTT status and authenticated local Sony command")

        transient = copy.deepcopy(test)
        transient["service"]["stateRetain"] = False
        install(transient)
        # Live state still arrives, while a new subscriber must not receive a retained STATE.
        assert exchange(command("status"))["ok"]
        probe = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="tv-nonretain-" + uuid.uuid4().hex[:8])
        unexpected = threading.Event()
        ph, pp, pk = configure(probe, original, directory)
        probe.on_connect = lambda client, *args: client.subscribe(base + "/tele/STATE", 1)
        probe.on_message = lambda client, userdata, message: unexpected.set() if message.retain and message.payload else None
        try:
            probe.connect(ph, pp, pk); probe.loop_start()
            assert not unexpected.wait(2), "Non-retained mode left an old retained STATE"
        finally:
            probe.disconnect(); probe.loop_stop()
        record("state retention can be disabled and clears the old retained snapshot")
    finally:
        # Always clear a diagnostic retained command and restore the user's config.
        try: client.publish(base + "/cmnd/COMMAND", b"", qos=1, retain=True).wait_for_publish(5)
        except Exception: pass
        previous_session = online_sessions.get(original["mqtt"]["baseTopic"])
        marker = time.monotonic()
        subprocess.run([sys.executable, str(ROOT / "tools/install.py"), "--serial", args.serial,
                        "--config", str(pathlib.Path(args.config).resolve()), "--skip-apk", "--start",
                        "--grant-status-access"], check=True, stdout=subprocess.DEVNULL)
        try:
            original_base = original["mqtt"]["baseTopic"]
            wait(lambda m: presence(m, original_base, True, marker) and json.loads(m[2]).get("session_id") != previous_session, 20)
            record("original TLS configuration restored and service online")
            for suffix in ("cmnd/COMMAND", "tele/STATE", "tele/LWT"):
                client.publish(base + "/" + suffix, b"", qos=1, retain=True).wait_for_publish(5)
        finally:
            client.disconnect()
            client.loop_stop()
            if runtime.exists():
                runtime.unlink()
            (work / "results.json").write_text(json.dumps({"passed": results}, indent=2))
    print(str(len(results)) + " integration checks passed; screen was never woken")

if __name__ == "__main__":
    main()
