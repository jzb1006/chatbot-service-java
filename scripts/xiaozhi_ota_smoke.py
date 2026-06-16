#!/usr/bin/env python3
import argparse
import json
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument("--url", required=True)
parser.add_argument("--device-id", default="smoke-device-1")
parser.add_argument("--client-id", default="smoke-client-1")
args = parser.parse_args()

request = urllib.request.Request(
    args.url,
    data=b"{}",
    headers={
        "Content-Type": "application/json",
        "Device-Id": args.device_id,
        "Client-Id": args.client_id,
        "Activation-Version": "1",
        "User-Agent": "xiaozhi-ota-smoke/1.0",
    },
    method="POST",
)

with urllib.request.urlopen(request, timeout=10) as response:
    body = json.loads(response.read().decode("utf-8"))

assert "websocket" in body, body
assert "server_time" in body, body
assert "firmware" in body, body
print(json.dumps(body, ensure_ascii=False, indent=2))
