#!/usr/bin/env python3
"""Send one non-retained command and wait for its correlated acknowledgement."""
import argparse
import datetime
import json
import pathlib
import queue
import sys
import threading
import uuid
import paho.mqtt.client as mqtt
from mqtt_config import read_config, configure

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action")
    parser.add_argument("--value", help="String, number, or JSON command value")
    parser.add_argument("--id", default=None)
    parser.add_argument("--config", default=str(pathlib.Path(__file__).resolve().parents[1] / "config.local.json"))
    parser.add_argument("--timeout", type=int, default=20)
    args = parser.parse_args()
    config, directory = read_config(args.config)
    base = config["mqtt"]["baseTopic"]
    command = {"id": args.id or str(uuid.uuid4()), "action": args.action,
               "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z")}
    if args.value is not None:
        try: command["value"] = json.loads(args.value)
        except json.JSONDecodeError: command["value"] = args.value
    responses = queue.Queue()
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="tv-sender-" + uuid.uuid4().hex[:12])
    def connected(client, userdata, flags, reason, properties):
        if reason.is_failure:
            responses.put(RuntimeError("Broker rejected the connection"))
        else:
            client.subscribe(base + "/stat/RESULT", 1)
    def subscribed(client, userdata, mid, reasons, properties):
        if any(reason.is_failure for reason in reasons):
            responses.put(RuntimeError("Broker denied the acknowledgement subscription"))
        else:
            client.publish(base + "/cmnd/COMMAND", json.dumps(command), qos=1, retain=False)
    def received(client, userdata, message):
        try:
            ack = json.loads(message.payload)
            if ack.get("command_id") == command["id"]:
                responses.put(ack)
        except (ValueError, UnicodeError):
            pass
    client.on_connect, client.on_subscribe, client.on_message = connected, subscribed, received
    host, port, keepalive = configure(client, config, directory)
    try:
        client.connect(host, port, keepalive)
        client.loop_start()
        ack = responses.get(timeout=args.timeout)
        if isinstance(ack, Exception):
            raise ack
        print(json.dumps(ack, indent=2))
        return 0 if ack.get("ok") else 1
    except queue.Empty:
        print("No acknowledgement received before timeout", file=sys.stderr)
        return 2
    finally:
        client.disconnect()
        client.loop_stop()

if __name__ == "__main__":
    sys.exit(main())
