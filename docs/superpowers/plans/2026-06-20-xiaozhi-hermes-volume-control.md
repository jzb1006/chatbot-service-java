# 小智 Hermes 音量控制服务端方案

> **面向 AI 代理的工作者：** 本文是方案文档，不是实现计划。未经用户确认，不执行代码修改、部署、远程配置变更、提交或推送。

**目标：** 让用户可以通过语音控制小智设备音量，例如“音量调到 50”“大一点”“静音”，并保持“AI 相关能力由 Hermes agent 处理”的边界。

**架构：** Hermes 负责自然语言意图识别、相对音量计算和工具编排；Java voice-gateway 保持 MCP 薄桥，只暴露稳定网关工具，不在 Java 侧写中文语义规则；固件设备 MCP 工具执行最终 `self.audio_speaker.set_volume`。

**技术栈：** Java 21、Spring Boot 3、Spring WebSocket、小智 MCP HTTP JSON-RPC 适配入口、Hermes API-server、设备 MCP `tools/list` / `tools/call`。

---

## 关键结论

- 当前服务端已经有小智设备 MCP 薄桥，不需要为音量单独新增业务控制器。
- Hermes 可见的稳定工具包括 `xiaozhi_list_online_devices`、`xiaozhi_list_device_tools`、`xiaozhi_call_device_tool`。
- Java 侧 `xiaozhi_call_device_tool` 会把 `deviceId`、设备工具 `name` 和 `arguments` 转成设备 MCP `tools/call`，并通过在线 WebSocket 下发给固件。
- 音量语义应放在 Hermes：Java 不判断“大一点、小一点、静音”，只负责把 Hermes 的确定性工具调用转发给设备。
- 第一版不新增 Java 本地 `xiaozhi_set_volume` 便利工具，避免把设备能力复制到服务端；除非 Hermes 对嵌套调用稳定性差，再考虑新增薄包装工具。
- **当前不能直接进入完成态开发。** 2026-06-20 live 验证显示，服务端和 Hermes MCP 入口可用，但真实在线设备对 MCP RPC `initialize` / `tools/list` 没有在 10 秒内回包；在设备 MCP RPC 可用前，不应执行 `set_volume` 或宣称音量控制闭环完成。

## 当前 live 验证结果

验证时间：2026-06-20 08:39-09:10 Asia/Shanghai。

已通过：

- 本地 Java MCP 基线通过：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn \
  -pl chatbot-voice-gateway \
  -am \
  -Dtest=XiaozhiMcpGatewayToolServiceTest,XiaozhiMcpJsonRpcControllerTest,XiaozhiMcpBridgeTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

结果：`Tests run: 14, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。

- 远程服务健康检查通过：`/actuator/health -> {"status":"UP"}`。
- 远程运行态 MCP token 存在，容器环境中 `XIAOZHI_MCP_ADMIN_TOKEN` 与 `XIAOZHI_MCP_HERMES_TOKEN` 均已设置。
- `/api/hermes/xiaozhi/mcp` 的 `tools/list` 能返回四个稳定网关工具。
- `GET /api/xiaozhi/devices` 返回当前在线设备：`9c:cc:01:40:1c:d8`。
- 已补服务端 MCP readiness 门禁：`GET /api/xiaozhi/devices` 和 `xiaozhi_list_online_devices` 保留 `devices` 数组，并额外返回 `deviceSessions[].mcpReady`；设备未声明 MCP ready 时，设备 MCP RPC 会快速返回 `device mcp is not ready`，不再盲等 10 秒。

阻塞：

- 对真实设备 `9c:cc:01:40:1c:d8` 调用直连设备 MCP RPC `initialize` 返回 `504 mcp request timed out`。
- 对同一设备调用直连设备 MCP RPC `tools/list` 返回 `504 mcp request timed out`。
- 通过 Hermes 网关工具 `xiaozhi_list_device_tools` 调用同一设备时，返回 `isError=true`，底层原因同样是设备 MCP RPC 超时。
- 远程 Hermes 最近日志未观察到小智 MCP / 音量工具调用痕迹；即使补充 Hermes 指令，当前设备 MCP RPC 阻塞仍会导致音量控制无法闭环。

结论：

- 服务端 Java 薄桥方向成立。
- 进入实现前必须先解决真实设备 MCP RPC 不回包的问题。
- “设备在线”只能证明 WebSocket 会话还在，不能证明该会话能处理 MCP 请求。

## 调用链

```text
用户语音
  -> ASR 文本
  -> Hermes 判断音量控制意图
  -> Hermes 调用 /api/hermes/xiaozhi/mcp
  -> xiaozhi_list_online_devices
  -> xiaozhi_call_device_tool(name=self.get_device_status)
  -> Hermes 计算目标音量
  -> xiaozhi_call_device_tool(name=self.audio_speaker.set_volume)
  -> Java WebSocket MCP bridge
  -> 固件 AudioCodec::SetOutputVolume
```

## 现有代码锚点

- `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpGatewayToolService.java`
  - `gatewayTools()` 暴露 Hermes 可见的稳定工具。
  - `callDeviceTool(...)` 将 Hermes 调用转为设备 MCP `tools/call`。
- `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpJsonRpcService.java`
  - 处理 `initialize`、`tools/list`、`tools/call`。
- `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpBridge.java`
  - 维护在线设备 WebSocket session。
  - 通过 `eventFactory.mcp(...)` 向设备下发 MCP payload。
- `chatbot-bootstrap/src/main/resources/application.yml`
  - `chatbot.voice.mcp.auth-required`
  - `chatbot.voice.mcp.admin-token`
  - `chatbot.voice.mcp.hermes-token`
- `README.md`
  - 记录 `/api/hermes/xiaozhi/mcp` 和四个稳定工具。

## Hermes 工具使用策略

Hermes 应遵守以下规则：

1. 用户要求绝对音量时，直接设置：
   - “音量调到 50” -> `volume=50`
   - “音量最大” -> `volume=100`
   - “静音” -> `volume=0`

2. 用户要求相对音量时，先读设备状态：
   - “大一点” -> 读取当前值，目标值 `min(current + 10, 100)`
   - “小一点” -> 读取当前值，目标值 `max(current - 10, 0)`

3. 在线设备数量处理：
   - 只有一个在线设备时，可以直接使用该设备。
   - 多个在线设备时，需要基于会话绑定的 deviceId；如果没有绑定，不应随机选择。

4. TTS 回复要短：
   - 成功：`已调到 50。`
   - 静音：`已静音。`
   - 失败：`设备不在线。`

## JSON-RPC 示例

Hermes 入口：

```text
POST /api/hermes/xiaozhi/mcp
Authorization: Bearer ${XIAOZHI_MCP_HERMES_TOKEN}
Content-Type: application/json
```

列在线设备：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "xiaozhi_list_online_devices",
    "arguments": {}
  }
}
```

读取当前音量：

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "xiaozhi_call_device_tool",
    "arguments": {
      "deviceId": "${ONLINE_DEVICE_ID}",
      "name": "self.get_device_status",
      "arguments": {}
    }
  }
}
```

设置音量：

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "xiaozhi_call_device_tool",
    "arguments": {
      "deviceId": "${ONLINE_DEVICE_ID}",
      "name": "self.audio_speaker.set_volume",
      "arguments": {
        "volume": 50
      }
    }
  }
}
```

## 需要补充的 Hermes 指令

应在 Hermes 面向小智设备的系统提示、记忆或工具使用说明中补充：

```text
当用户要求控制小智音量时，使用 xiaozhi_call_device_tool 调用设备 MCP 工具。
绝对音量直接调用 self.audio_speaker.set_volume，volume 范围 0-100。
相对音量必须先调用 self.get_device_status 获取 audio_speaker.volume，再按 10 为步进计算目标值。
不要用普通聊天回答代替工具调用。
成功后只用一句短文本确认，适合 TTS 播报。
```

## 成功标准

前置门禁：

- 真实设备在线后，直连设备 MCP RPC `initialize` 必须返回 JSON-RPC result。
- 真实设备在线后，直连设备 MCP RPC `tools/list` 必须返回 tools 数组。
- 设备 tools 数组必须包含 `self.get_device_status` 与 `self.audio_speaker.set_volume`。
- `GET /api/xiaozhi/devices` 或 `xiaozhi_list_online_devices` 中对应设备的 `mcpReady` 必须为 `true`。
- 上述门禁未通过时，不执行 `set_volume`，不把任务标记为可开发完成。

功能成功标准：

- `/api/hermes/xiaozhi/mcp` 的 `tools/list` 能返回 `xiaozhi_call_device_tool`。
- `xiaozhi_list_online_devices` 能返回当前连接设备 ID。
- `xiaozhi_list_device_tools` 能在设备工具列表中看到 `self.get_device_status` 与 `self.audio_speaker.set_volume`。
- 通过 `xiaozhi_call_device_tool` 调用 `self.audio_speaker.set_volume` 后，设备状态中的 `audio_speaker.volume` 变为目标值。
- 用户说“音量调到 50”，Hermes 实际发起工具调用，而不是只文本回复。
- 用户说“大一点/小一点”时，Hermes 先读当前音量再设置新音量。
- 失败场景返回短文本，不抛出长堆栈给 TTS。

## 验证步骤

1. 服务健康检查：

```bash
curl -fsS "http://203.195.202.54:8766/actuator/health"
```

2. 确认设备在线：

```bash
curl -fsS \
  -H "X-MCP-Admin-Token: ${XIAOZHI_MCP_ADMIN_TOKEN}" \
  "http://203.195.202.54:8766/api/xiaozhi/devices"
```

预期：响应包含 `devices` 和 `deviceSessions`，目标设备的 `mcpReady=true`。

3. 确认 Hermes MCP 入口工具列表：

```bash
curl -fsS -X POST \
  -H "Authorization: Bearer ${XIAOZHI_MCP_HERMES_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' \
  "http://203.195.202.54:8766/api/hermes/xiaozhi/mcp"
```

4. 先验证设备 MCP RPC 初始化。

```bash
curl -fsS -X POST \
  -H "X-MCP-Admin-Token: ${XIAOZHI_MCP_ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":201,"method":"initialize","params":{"capabilities":{}}}' \
  "http://203.195.202.54:8766/api/xiaozhi/devices/${ONLINE_DEVICE_ID}/mcp/rpc"
```

预期：HTTP 200，响应体包含 `result.protocolVersion`。

5. 再验证设备 MCP `tools/list`。

```bash
curl -fsS -X POST \
  -H "X-MCP-Admin-Token: ${XIAOZHI_MCP_ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":202,"method":"tools/list","params":{"withUserTools":true}}' \
  "http://203.195.202.54:8766/api/xiaozhi/devices/${ONLINE_DEVICE_ID}/mcp/rpc"
```

预期：HTTP 200，响应体包含：

```text
self.get_device_status
self.audio_speaker.set_volume
```

如果这一步返回 `504 mcp request timed out`，停止验证，不执行后续音量设置。

6. 通过 Hermes 网关读取设备工具列表。

```bash
curl -fsS -X POST \
  -H "Authorization: Bearer ${XIAOZHI_MCP_HERMES_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"xiaozhi_list_device_tools\",\"arguments\":{\"deviceId\":\"${ONLINE_DEVICE_ID}\"}}}" \
  "http://203.195.202.54:8766/api/hermes/xiaozhi/mcp"
```

7. 调用设备工具设置音量为 `50`。

8. 再调用 `self.get_device_status` 验证 `audio_speaker.volume=50`。

9. 用真实语音或 synthesized input 验证：

```text
音量调到 50
声音大一点
声音小一点
静音
恢复声音
```

日志重点看：

```text
xiaozhi mcp
tools/call
self.audio_speaker.set_volume
Set output volume to
```

## 风险与处理

- 如果 Hermes 没有配置 `/api/hermes/xiaozhi/mcp` 工具入口，它不会主动调用设备工具。处理：先在 Hermes runtime 增加该 MCP HTTP JSON-RPC 工具源或等价工具说明。
- 如果远程 `XIAOZHI_MCP_HERMES_TOKEN` 未设置或不一致，Hermes 调用会鉴权失败。处理：核对运行中容器 env-file 和 `application.yml` 绑定。
- 如果设备未发送新会话或已断线，`xiaozhi_list_online_devices` 为空。处理：先验证 WebSocket hello 和设备在线。
- 如果设备在线但 `mcpReady=false`，说明服务端尚未收到该会话 hello 中的 `features.mcp=true`，此时调用设备工具会快速失败并返回 `device mcp is not ready`。
- 如果 `xiaozhi_list_online_devices` 有设备，但 `/api/xiaozhi/devices/{deviceId}/mcp/rpc` 返回 `mcp request timed out`，说明服务端能找到在线 WebSocket session，但设备没有返回 MCP JSON-RPC response。处理：优先检查固件当前烧录版本是否包含 MCP server、设备 hello 是否声明 `features.mcp=true`、设备是否实际进入 `McpServer::ParseMessage(...)`。
- 服务端已把 hello 的 `features.mcp` 持久化为 `mcpReady`，但 `mcpReady=true` 只表示设备声明支持 MCP，不等价于 `tools/list` 一定会回包；最终验收仍必须使用 `/mcp/rpc`。
- `scripts/xiaozhi_mcp_smoke.py` 只调用 fire-and-forget `/api/xiaozhi/devices/{deviceId}/mcp`，返回 `{"status":"sent"}` 只能证明服务端已下发，不能证明设备收到、处理或回包。音量控制验收必须使用 `/mcp/rpc`。
- 如果多个设备在线且 Hermes 没有当前会话的 deviceId，可能调错设备。处理：优先把当前 WebSocket deviceId 放进 Hermes 上下文；第一版禁止随机选择。
- 如果“静音后重启仍静音”是硬需求，需要固件侧另行处理 `AudioCodec::Start()` 对 `output_volume<=0` 的保护逻辑。

## 非目标

- 不在 Java 中实现中文音量意图解析。
- 不在 Java 中复制固件的音量状态。
- 不新增数据库、后台管理或设备注册系统。
- 不改 TTS、ASR、音乐播放主链路。
- 不执行远程部署、提交或推送。
