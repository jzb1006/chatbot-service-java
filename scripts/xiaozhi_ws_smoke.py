#!/usr/bin/env python3
"""小智 WebSocket 服务端 smoke 测试脚本。"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import secrets
import socket
import ssl
import struct
import sys
import time
from dataclasses import dataclass
from typing import Iterable
from urllib.parse import urlparse


DEFAULT_PATHS = ("/xiaozhi/v1", "/ws/xiaozhi/v1", "/ws/xiaozhi/v1/")
OPCODE_TEXT = 0x1
OPCODE_BINARY = 0x2
OPCODE_CLOSE = 0x8
OPCODE_PING = 0x9
OPCODE_PONG = 0xA
VALID_OPUS_SILENCE_FRAME = base64.b64decode("+P/+")


@dataclass(frozen=True)
class Frame:
    opcode: int
    payload: bytes


class WebSocketSmokeError(RuntimeError):
    """WebSocket smoke 执行失败。"""


class MinimalWebSocket:
    """只覆盖本 smoke 所需的最小 WebSocket 客户端。"""

    def __init__(self, url: str, headers: dict[str, str], timeout: float) -> None:
        self.url = url
        self.headers = headers
        self.timeout = timeout
        self.sock: socket.socket | ssl.SSLSocket | None = None

    def __enter__(self) -> "MinimalWebSocket":
        self.connect()
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        if self.sock is not None:
            try:
                self.send_frame(OPCODE_CLOSE, b"")
            except OSError:
                pass
            self.sock.close()

    def connect(self) -> None:
        parsed = urlparse(self.url)
        if parsed.scheme not in {"ws", "wss"}:
            raise WebSocketSmokeError(f"unsupported websocket scheme: {parsed.scheme}")
        host = parsed.hostname
        if host is None:
            raise WebSocketSmokeError(f"missing websocket host: {self.url}")
        port = parsed.port or (443 if parsed.scheme == "wss" else 80)
        path = parsed.path or "/"
        if parsed.query:
            path = f"{path}?{parsed.query}"

        raw_sock = socket.create_connection((host, port), timeout=self.timeout)
        raw_sock.settimeout(self.timeout)
        if parsed.scheme == "wss":
            context = ssl.create_default_context()
            self.sock = context.wrap_socket(raw_sock, server_hostname=host)
        else:
            self.sock = raw_sock

        key = base64.b64encode(secrets.token_bytes(16)).decode("ascii")
        host_header = host if parsed.port is None else f"{host}:{port}"
        request_headers = {
            "Host": host_header,
            "Upgrade": "websocket",
            "Connection": "Upgrade",
            "Sec-WebSocket-Key": key,
            "Sec-WebSocket-Version": "13",
            **self.headers,
        }
        request = [f"GET {path} HTTP/1.1"]
        request.extend(f"{name}: {value}" for name, value in request_headers.items() if value)
        request.append("")
        request.append("")
        self._send_raw("\r\n".join(request).encode("ascii"))

        response = self._recv_until(b"\r\n\r\n")
        header_text = response.decode("iso-8859-1", errors="replace")
        status_line = header_text.split("\r\n", 1)[0]
        if " 101 " not in status_line:
            raise WebSocketSmokeError(f"websocket handshake failed: {status_line}")
        accept = self._parse_header(header_text, "Sec-WebSocket-Accept")
        expected_accept = base64.b64encode(
            hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode("ascii")).digest()
        ).decode("ascii")
        if accept != expected_accept:
            raise WebSocketSmokeError("websocket handshake failed: invalid Sec-WebSocket-Accept")

    def send_text(self, payload: dict[str, object]) -> None:
        self.send_frame(OPCODE_TEXT, json.dumps(payload, separators=(",", ":")).encode("utf-8"))

    def send_binary(self, payload: bytes) -> None:
        self.send_frame(OPCODE_BINARY, payload)

    def send_frame(self, opcode: int, payload: bytes) -> None:
        mask_key = secrets.token_bytes(4)
        header = bytearray([0x80 | opcode])
        length = len(payload)
        if length <= 125:
            header.append(0x80 | length)
        elif length <= 0xFFFF:
            header.extend([0x80 | 126])
            header.extend(struct.pack("!H", length))
        else:
            header.extend([0x80 | 127])
            header.extend(struct.pack("!Q", length))
        masked = bytes(byte ^ mask_key[index % 4] for index, byte in enumerate(payload))
        self._send_raw(bytes(header) + mask_key + masked)

    def recv_frame(self) -> Frame:
        first_two = self._recv_exact(2)
        first, second = first_two
        opcode = first & 0x0F
        masked = bool(second & 0x80)
        length = second & 0x7F
        if length == 126:
            length = struct.unpack("!H", self._recv_exact(2))[0]
        elif length == 127:
            length = struct.unpack("!Q", self._recv_exact(8))[0]
        mask_key = self._recv_exact(4) if masked else b""
        payload = self._recv_exact(length)
        if masked:
            payload = bytes(byte ^ mask_key[index % 4] for index, byte in enumerate(payload))
        return Frame(opcode, payload)

    def recv_data_frame(self) -> Frame:
        while True:
            frame = self.recv_frame()
            if frame.opcode == OPCODE_PING:
                self.send_frame(OPCODE_PONG, frame.payload)
                continue
            if frame.opcode == OPCODE_CLOSE:
                raise WebSocketSmokeError("server closed websocket")
            if frame.opcode in {OPCODE_TEXT, OPCODE_BINARY}:
                return frame

    def _send_raw(self, data: bytes) -> None:
        if self.sock is None:
            raise WebSocketSmokeError("websocket is not connected")
        self.sock.sendall(data)

    def _recv_exact(self, size: int) -> bytes:
        if self.sock is None:
            raise WebSocketSmokeError("websocket is not connected")
        chunks = bytearray()
        while len(chunks) < size:
            chunk = self.sock.recv(size - len(chunks))
            if not chunk:
                raise WebSocketSmokeError("websocket connection closed unexpectedly")
            chunks.extend(chunk)
        return bytes(chunks)

    def _recv_until(self, marker: bytes) -> bytes:
        if self.sock is None:
            raise WebSocketSmokeError("websocket is not connected")
        chunks = bytearray()
        while marker not in chunks:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise WebSocketSmokeError("websocket connection closed during handshake")
            chunks.extend(chunk)
        return bytes(chunks)

    @staticmethod
    def _parse_header(header_text: str, name: str) -> str | None:
        prefix = f"{name.lower()}:"
        for line in header_text.split("\r\n"):
            if line.lower().startswith(prefix):
                return line.split(":", 1)[1].strip()
        return None


def smoke_url(url: str, args: argparse.Namespace) -> None:
    headers = {
        "Protocol-Version": str(args.protocol_version),
        "Device-Id": args.device_id,
        "Client-Id": args.client_id,
    }
    authorization = resolve_authorization(args)
    if authorization:
        headers["Authorization"] = authorization

    with MinimalWebSocket(url, headers, args.timeout) as websocket:
        websocket.send_text({
            "type": "hello",
            "version": args.protocol_version,
            "features": {"mcp": True},
            "transport": "websocket",
            "audio_params": {
                "format": "opus",
                "sample_rate": 16000,
                "channels": 1,
                "frame_duration": 60,
            },
        })
        server_hello = recv_json_event(websocket, args.timeout)
        require(server_hello.get("type") == "hello", f"{url}: missing server hello")

        websocket.send_text({
            "session_id": server_hello.get("session_id", args.client_id),
            "type": "listen",
            "state": "start",
            "mode": "manual",
        })
        websocket.send_binary(VALID_OPUS_SILENCE_FRAME)
        websocket.send_text({
            "session_id": server_hello.get("session_id", args.client_id),
            "type": "listen",
            "state": "stop",
        })

        seen = collect_events_until_tts_stop(websocket, args.timeout)
        require("stt" in seen, f"{url}: missing stt event")
        require("llm" in seen, f"{url}: missing llm event")
        require("tts.start" in seen, f"{url}: missing tts.start event")
        require("binary" in seen, f"{url}: missing tts binary audio frame")
        require("tts.stop" in seen, f"{url}: missing tts.stop event")


def recv_json_event(websocket: MinimalWebSocket, timeout: float) -> dict[str, object]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        frame = websocket.recv_data_frame()
        if frame.opcode != OPCODE_TEXT:
            continue
        return json.loads(frame.payload.decode("utf-8"))
    raise WebSocketSmokeError("timeout waiting for json event")


def collect_events_until_tts_stop(websocket: MinimalWebSocket, timeout: float) -> set[str]:
    deadline = time.monotonic() + timeout
    seen: set[str] = set()
    while time.monotonic() < deadline:
        frame = websocket.recv_data_frame()
        if frame.opcode == OPCODE_BINARY:
            seen.add("binary")
            continue
        event = json.loads(frame.payload.decode("utf-8"))
        event_type = event.get("type")
        state = event.get("state")
        if event_type == "tts" and state:
            seen.add(f"tts.{state}")
            if state == "stop":
                return seen
        elif isinstance(event_type, str):
            seen.add(event_type)
    raise WebSocketSmokeError("timeout waiting for tts.stop")


def resolve_urls(args: argparse.Namespace) -> list[str]:
    if args.url:
        return args.url
    base_url = args.base_url.rstrip("/")
    return [f"{base_url}{path}" for path in DEFAULT_PATHS]


def resolve_authorization(args: argparse.Namespace) -> str:
    if args.authorization:
        return args.authorization
    token = args.token or os.getenv("XIAOZHI_WEBSOCKET_TOKEN", "")
    if not token:
        return ""
    if token.lower().startswith("bearer "):
        return token
    return f"Bearer {token}"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise WebSocketSmokeError(message)


def parse_args(argv: Iterable[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="小智 WebSocket smoke 测试")
    parser.add_argument("--base-url", default="ws://127.0.0.1:8766", help="服务端基础地址")
    parser.add_argument("--url", action="append", help="指定完整 WebSocket URL，可重复传入")
    parser.add_argument("--token", default="", help="设备 token，默认读取 XIAOZHI_WEBSOCKET_TOKEN")
    parser.add_argument("--authorization", default="", help="完整 Authorization 头，传入后覆盖 --token")
    parser.add_argument("--device-id", default="smoke-device-1", help="Device-Id 请求头")
    parser.add_argument("--client-id", default="smoke-client-1", help="Client-Id 请求头")
    parser.add_argument("--protocol-version", type=int, default=1, help="Protocol-Version 请求头和 hello version")
    parser.add_argument("--timeout", type=float, default=10.0, help="单次连接超时时间，单位秒")
    return parser.parse_args(list(argv))


def main(argv: Iterable[str]) -> int:
    args = parse_args(argv)
    urls = resolve_urls(args)
    for url in urls:
        try:
            smoke_url(url, args)
            print(f"OK {url}")
        except Exception as exc:
            print(f"FAIL {url}: {exc}", file=sys.stderr)
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
