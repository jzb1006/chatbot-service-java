#!/usr/bin/env python3
"""Hermes/Xiaozhi WebSocket 联动 smoke 测试脚本。"""

from __future__ import annotations

import sys
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import xiaozhi_ws_smoke  # noqa: E402


def main(argv: list[str]) -> int:
    args = xiaozhi_ws_smoke.parse_args(argv, description="Hermes/Xiaozhi WebSocket 联动 smoke 入口")
    urls = xiaozhi_ws_smoke.resolve_urls(args)
    for url in urls:
        try:
            stats = xiaozhi_ws_smoke.run_smoke_url(url, args)
            # 脚本只能从 WebSocket 侧观测 Hermes SSE 的下游 LLM 事件，hermes_sse_output 是代理指标。
            hermes_sse_output_proxy = stats.llm_count >= 1
            observed = xiaozhi_ws_smoke.format_bool(hermes_sse_output_proxy)
            print(f"hermes_sse_output={observed}")
            print(f"ws_llm_event_observed={observed}")
            xiaozhi_ws_smoke.print_smoke_stats(stats)
            xiaozhi_ws_smoke.require(hermes_sse_output_proxy, f"{url}: missing Hermes SSE output proxy")
            print(f"OK {url}")
        except Exception as exc:
            print(f"FAIL {url}: {exc}", file=sys.stderr)
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
