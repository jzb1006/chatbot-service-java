#!/usr/bin/env python3
import argparse
import json
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument("--url", required=True)
parser.add_argument("--device-id", required=True)
parser.add_argument("--token", default="")
args = parser.parse_args()

payload = json.dumps({
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/list",
    "params": {"withUserTools": True},
}).encode("utf-8")

request = urllib.request.Request(
    f"{args.url.rstrip('/')}/api/xiaozhi/devices/{args.device_id}/mcp",
    data=payload,
    headers={
        "Content-Type": "application/json",
        "X-MCP-Admin-Token": args.token,
    },
    method="POST",
)

with urllib.request.urlopen(request, timeout=10) as response:
    body = json.loads(response.read().decode("utf-8"))

print(json.dumps(body, ensure_ascii=False, indent=2))
