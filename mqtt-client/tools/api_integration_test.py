#!/usr/bin/env python3
"""Standby-safe API tests over temporary ADB loopback forwards; restores user config."""
import argparse
import base64
import copy
import hashlib
import http.client
import json
import pathlib
import queue
import socket
import ssl
import subprocess
import sys
import threading
import time
import uuid

import paho.mqtt.client as mqtt
from websockets.sync.client import connect as websocket
from websockets.exceptions import InvalidStatus, ConnectionClosed
from mqtt_config import read_config, configure

ROOT = pathlib.Path(__file__).resolve().parents[1]
PACKAGE = "net.phoxis.tvmqtt"

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--config", default=str(ROOT / "config.local.json"))
    args = parser.parse_args()
    original, directory = read_config(args.config)
    profile = copy.deepcopy(original)
    base = original["mqtt"]["baseTopic"] + "/api-validation-" + uuid.uuid4().hex[:8]
    profile["mqtt"]["baseTopic"] = base
    profile["mqtt"]["clientId"] += "-api-validation"
    profile["api"]["bindAddress"] = "127.0.0.1"
    profile["api"]["http"]["enabled"] = False
    profile["api"]["https"]["enabled"] = True
    profile["service"]["statePollSeconds"] = 2
    profile["service"]["stateHeartbeatSeconds"] = 4
    work = ROOT / ".integration"
    work.mkdir(mode=0o700, exist_ok=True)
    runtime = work / "api-runtime.local.json"
    certificate_file = work / "api-public-certificate.pem"
    adb = ["adb", "-s", args.serial]
    forwards = []
    results = []
    context = ssl._create_unverified_context()
    messages = queue.Queue()
    subscribed = threading.Event()
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="tv-api-test-" + uuid.uuid4().hex[:10])
    host, port, keepalive = configure(client, original, directory)
    def on_connect(client, userdata, flags, reason, properties):
        if not reason.is_failure:
            client.subscribe(original["mqtt"]["baseTopic"] + "/#", 1)
    client.on_connect = on_connect
    client.on_subscribe = lambda *a: subscribed.set()
    client.on_message = lambda client, userdata, msg: messages.put((msg.topic, msg.payload, msg.retain))
    client.connect(host, port, keepalive)
    client.loop_start()
    assert subscribed.wait(10), "MQTT test observer could not subscribe"
    http_port = https_port = None

    def record(label):
        results.append(label)
        print("PASS " + label, flush=True)

    def install(config, apk=False):
        runtime.write_text(json.dumps(config))
        runtime.chmod(0o600)
        command = [sys.executable, str(ROOT / "tools/install.py"), "--serial", args.serial,
                   "--config", str(runtime), "--start"]
        if not apk:
            command.append("--skip-apk")
        subprocess.run(command, check=True, stdout=subprocess.DEVNULL)

    def request(path, secure=True, method="GET", body=None, key=True, config=None):
        current = config or profile
        headers = {}
        if key is True:
            headers["X-API-Key"] = current["api"]["passkey"]
        elif isinstance(key, str):
            headers["X-API-Key"] = key
        if isinstance(body, dict):
            body = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        if secure:
            connection = http.client.HTTPSConnection("127.0.0.1", https_port, context=context, timeout=5)
        else:
            connection = http.client.HTTPConnection("127.0.0.1", http_port, timeout=5)
        try:
            connection.request(method, path, body=body, headers=headers)
            response = connection.getresponse()
            raw = response.read()
            try:
                value = json.loads(raw)
            except ValueError:
                value = raw.decode()
            return response.status, value
        finally:
            connection.close()

    def ready(config, secure=True):
        deadline = time.monotonic() + 25
        while True:
            try:
                code, value = request("/api/state", secure, config=config)
                if code == 200 and value["state"]["api"]["ready"]:
                    assert value["state"]["power"] in ("active", "standby"), "Power status must be readable"
                    return value["state"]
            except (OSError, ValueError, KeyError, http.client.HTTPException):
                pass
            if time.monotonic() >= deadline:
                raise TimeoutError("API did not become ready")
            threading.Event().wait(.25)

    def open_ws(secure=True, key=True, config=None):
        current = config or profile
        headers = {"X-API-Key": current["api"]["passkey"]} if key else {}
        options = {"additional_headers": headers, "open_timeout": 5, "close_timeout": 1, "proxy": None}
        if secure:
            options["ssl"] = context
        return websocket(("wss" if secure else "ws") + "://127.0.0.1:" +
                         str(https_port if secure else http_port) + "/api/ws", **options)

    def ws_wait(connection, predicate, timeout=12):
        deadline = time.monotonic() + timeout
        while True:
            value = json.loads(connection.recv(timeout=max(.1, deadline - time.monotonic())))
            if predicate(value):
                return value
            if time.monotonic() >= deadline:
                raise TimeoutError("Missing WebSocket event")

    def command(action="status"):
        return {"id": str(uuid.uuid4()), "action": action}

    def mqtt_ack(payload):
        client.publish(base + "/cmnd/COMMAND", json.dumps(payload), qos=1, retain=False).wait_for_publish(5)
        deadline = time.monotonic() + 15
        while True:
            topic, data, retained = messages.get(timeout=max(.1, deadline - time.monotonic()))
            if topic == base + "/stat/RESULT":
                value = json.loads(data)
                if value.get("command_id") == payload["id"]:
                    return value

    def fails(secure):
        try:
            request("/api/state", secure)
        except (OSError, http.client.HTTPException):
            return True
        return False

    try:
        install(profile, apk=True)
        for remote in (profile["api"]["http"]["port"], profile["api"]["https"]["port"]):
            result = subprocess.run(adb + ["forward", "tcp:0", "tcp:" + str(remote)], capture_output=True, check=True)
            forwards.append(int(result.stdout.strip()))
        http_port, https_port = forwards
        ready(profile)
        ready(profile, True)
        record("HTTPS returns current state using the state envelope")

        for secure in (True,):
            for path in ("/api/state", "/api/public-key", "/api/certificate"):
                assert request(path, secure, key=False)[0] == 401
                assert request(path, secure, key="wrong-key")[0] == 401
            assert request("/api/command", secure, "POST", command(), key=False)[0] == 401
            assert request("/api/state?passkey=" + profile["api"]["passkey"], secure, key=False)[0] == 401
        record("REST, key and certificate routes require the header; query credentials are rejected")

        code, identity = request("/api/public-key", True)
        assert code == 200 and identity["persistent"] and identity["storage"] == "AndroidKeyStore"
        assert identity["private_key_exportable"] is False
        cert_der = ssl.PEM_cert_to_DER_cert(identity["certificate_pem"])
        fingerprint = hashlib.sha256(cert_der).hexdigest()
        assert fingerprint == identity["certificate_sha256"]
        public_der = base64.b64decode("".join(line for line in identity["public_key_pem"].splitlines() if not line.startswith("---")))
        assert hashlib.sha256(public_der).hexdigest() == identity["public_key_sha256"]
        with context.wrap_socket(socket.create_connection(("127.0.0.1", https_port), timeout=5), server_hostname="localhost") as peer:
            assert hashlib.sha256(peer.getpeercert(binary_form=True)).hexdigest() == fingerprint
        certificate_file.write_text(identity["certificate_pem"])
        subprocess.run(["openssl", "verify", "-CAfile", str(certificate_file), str(certificate_file)], check=True, capture_output=True)
        assert request("/api/certificate", True)[1] == identity["certificate_pem"]
        record("TV-generated self-signed certificate verifies and matches advertised public key and HTTPS peer")

        for secure in (True,):
            try:
                with open_ws(secure, key=False):
                    raise AssertionError("Unauthenticated WebSocket was accepted")
            except InvalidStatus as error:
                assert error.response.status_code == 401
        record("WSS rejects unauthenticated upgrade before switching protocols")

        shared = command()
        code, ack = request("/api/command", True, "POST", shared)
        assert code == 200 and ack["ok"] and ack["command_id"] == shared["id"]
        with open_ws() as plain, open_ws(True) as encrypted:
            first = ws_wait(plain, lambda value: "state" in value)["state"]
            second = ws_wait(encrypted, lambda value: "state" in value)["state"]
            assert first["power"] in ("active","standby") and second["power"] in ("active","standby")
            plain.send(json.dumps(shared))
            duplicate = ws_wait(plain, lambda value: value.get("ack", {}).get("command_id") == shared["id"])["ack"]
            assert duplicate["ok"] and duplicate["duplicate"]
            assert mqtt_ack(shared)["duplicate"]
            record("REST, WebSocket and MQTT share command IDs and persistent duplicate suppression")

            request("/api/command", True, "POST", command())
            updated = ws_wait(encrypted, lambda value: value.get("state", {}).get("timestamp", "") > second["timestamp"])["state"]
            assert updated["power"] in ("active","standby") and updated["network"]["ips"]
            record("REST commands stream updated state to connected WSS clients")

            text = json.dumps({**command("status"), "note": "caf\u00e9"}, ensure_ascii=False).encode()
            split = text.index(b"\xc3") + 1
            fragmented_id = json.loads(text)["id"]
            plain.send([text[:split], text[split:]], text=True)
            fragmented = ws_wait(plain, lambda value: value.get("ack", {}).get("command_id") == fragmented_id)["ack"]
            assert fragmented["ok"]
            record("fragmented UTF-8 commands are reconstructed without changing command semantics")

            started = time.monotonic()
            while time.monotonic() - started < 17:
                event = json.loads(encrypted.recv(timeout=8))
                assert isinstance(event, dict)
            encrypted.send(json.dumps(command("status")))
            assert ws_wait(encrypted, lambda value: value.get("ack", {}).get("action") == "status")["ack"]["ok"]
            record("state heartbeat and WebSocket ping/pong keep an idle stream alive")

        with open_ws() as large:
            ws_wait(large, lambda value: "state" in value)
            try:
                large.send("x" * 17000)
                large.recv(timeout=5)
                raise AssertionError("Oversize command frame was not closed")
            except ConnectionClosed:
                pass
        assert request("/api/state")[0] == 200
        record("oversize WebSocket input is closed without stopping the service")

        install(profile)
        ready(profile, True)
        assert request("/api/public-key", True)[1]["certificate_sha256"] == fingerprint
        assert request("/api/command", True, "POST", shared)[1]["duplicate"]
        record("HTTPS private identity and cross-transport command history survive process restart")

        changed = copy.deepcopy(profile)
        changed["api"]["passkey"] += "-temporary"
        install(changed)
        ready(changed, True)
        assert request("/api/state", True, key=profile["api"]["passkey"], config=changed)[0] == 401
        assert request("/api/public-key", True, config=changed)[1]["certificate_sha256"] == fingerprint
        record("changing API passkey preserves the HTTPS identity and revokes the old header")

        https_only = copy.deepcopy(profile)
        https_only["api"]["http"]["enabled"] = False
        install(https_only)
        ready(https_only, True)
        assert fails(False)
        record("HTTPS-only mode has no HTTP listener")

        offline = copy.deepcopy(profile)
        offline["mqtt"]["uri"] = "mqtt://127.0.0.1:19999"
        install(offline)
        ready(offline, True)
        with open_ws(True, config=offline) as live:
            ws_wait(live, lambda value: "state" in value)
            item = command()
            live.send(json.dumps(item))
            assert ws_wait(live, lambda value: value.get("ack", {}).get("command_id") == item["id"])["ack"]["ok"]
            assert request("/api/state", True, config=offline)[0] == 200
        journal = subprocess.run(adb + ["shell", "run-as", PACKAGE, "cat", "files/command-journal.json"], capture_output=True, check=True)
        assert not json.loads(journal.stdout)["outbox"]
        record("REST/WSS keep working without MQTT and API acknowledgements do not fill its outbox")
    finally:
        subprocess.run([sys.executable, str(ROOT / "tools/install.py"), "--serial", args.serial,
                        "--config", str(pathlib.Path(args.config).resolve()), "--skip-apk", "--start"], check=True, stdout=subprocess.DEVNULL)
        for local in forwards:
            subprocess.run(adb + ["forward", "--remove", "tcp:" + str(local)], check=False)
        for suffix in ("cmnd/COMMAND", "tele/STATE", "tele/LWT"):
            try:
                client.publish(base + "/" + suffix, b"", qos=1, retain=True).wait_for_publish(5)
            except Exception:
                pass
        client.disconnect()
        client.loop_stop()
        runtime.unlink(missing_ok=True)
        certificate_file.unlink(missing_ok=True)
        (work / "api-results.json").write_text(json.dumps({"passed": results}, indent=2))
    print(str(len(results)) + " read-only API integration checks passed; configured HTTPS listener restored; playback untouched")

if __name__ == "__main__":
    main()
