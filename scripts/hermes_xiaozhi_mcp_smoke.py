#!/usr/bin/env python3
import argparse
import json
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument("--url", required=True)
parser.add_argument("--token", default="")
args = parser.parse_args()

payload = json.dumps({
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/list",
}).encode("utf-8")

request = urllib.request.Request(
    f"{args.url.rstrip('/')}/api/hermes/xiaozhi/mcp",
    data=payload,
    headers={
        "Content-Type": "application/json",
        "Authorization": f"Bearer {args.token}",
    },
    method="POST",
)

with urllib.request.urlopen(request, timeout=10) as response:
    body = json.loads(response.read().decode("utf-8"))

tools = body["result"]["tools"]
names = [tool["name"] for tool in tools]
assert "xiaozhi_list_online_devices" in names, body
assert "xiaozhi_list_device_tools" in names, body
assert "xiaozhi_call_device_tool" in names, body
print(json.dumps(body, ensure_ascii=False, indent=2))
