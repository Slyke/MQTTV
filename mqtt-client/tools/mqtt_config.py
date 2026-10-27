"""Shared configuration for the diagnostic sender and integration tests."""
import json
import pathlib
import ssl
import urllib.parse

def read_config(path):
    path = pathlib.Path(path).resolve()
    return json.loads(path.read_text()), path.parent

def configure(client, config, directory):
    mqtt = config["mqtt"]
    uri = urllib.parse.urlparse(mqtt["uri"])
    secure = uri.scheme in ("mqtts", "ssl", "tls")
    if uri.scheme not in ("mqtt", "tcp", "mqtts", "ssl", "tls"):
        raise ValueError("Unsupported MQTT URI")
    if mqtt.get("username"):
        client.username_pw_set(mqtt["username"], mqtt.get("password", ""))
    if secure:
        settings = mqtt.get("tls", {})
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        if settings.get("insecureSkipVerify", False):
            context.check_hostname = False
            context.verify_mode = ssl.CERT_NONE
        else:
            if settings.get("trustSystemCAs", True):
                context.load_default_certs()
            if settings.get("caFile"):
                context.load_verify_locations(cafile=str(directory / settings["caFile"]))
            if settings.get("caCertificatePem"):
                context.load_verify_locations(cadata=settings["caCertificatePem"])
        client.tls_set_context(context)
        client.tls_insecure_set(settings.get("insecureSkipVerify", False))
    return uri.hostname, uri.port or (8883 if secure else 1883), mqtt.get("keepAliveSeconds", 30)
