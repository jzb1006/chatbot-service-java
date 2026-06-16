# chatbot-service-java

Java 服务端中间件，用于承载设备文本网关和小智语音网关。固件代码不放在本仓库。

## 模块

- `chatbot-common`：共享标识值对象。
- `chatbot-hermes-adapter`：Hermes 对话适配接口和第一阶段 Fake 实现。
- `chatbot-speech-api`：ASR/TTS Provider 抽象。
- `chatbot-device-gateway`：设备文本 REST 网关。
- `chatbot-voice-gateway`：小智 WebSocket 协议网关。
- `chatbot-bootstrap`：Spring Boot 聚合启动模块。

## 本地命令

```bash
mvn test
mvn -pl chatbot-bootstrap -am -DskipTests package
java -jar chatbot-bootstrap/target/chatbot-bootstrap-0.0.1-SNAPSHOT.jar
```

如果本机未把 Maven 放入 `PATH`，当前机器可使用：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn test
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-bootstrap -am -DskipTests package
java -jar chatbot-bootstrap/target/chatbot-bootstrap-0.0.1-SNAPSHOT.jar
```

## REST 文本接口

```bash
curl -s -X POST http://127.0.0.1:8766/api/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Device-Token: <device-token>' \
  -d '{"device_id":"device-1","conversation_id":"conv-1","prompt":"ping"}'
```

预期响应：

```json
{"device_id":"device-1","conversation_id":"conv-1","answer":"pong"}
```

## WebSocket

小智 ESP32 WebSocket 入口：

```text
ws://<server-host>:8766/xiaozhi/v1
ws://<server-host>:8766/ws/xiaozhi/v1
ws://<server-host>:8766/ws/xiaozhi/v1/
```

推荐继续使用 `/xiaozhi/v1` 作为当前项目主路径；`/ws/xiaozhi/v1` 和 `/ws/xiaozhi/v1/`
用于兼容小智固件常见配置和参考服务端路径。

当前协议能力：

- 设备发送 `hello` 后，服务端返回 `hello`，字段包含 `transport=websocket`、`session_id` 和 `audio_params`。
- 支持 `listen.start`、`listen.stop`、`listen.detect`、`abort` 控制帧。
- 支持 WebSocket binary v1/v2/v3 Opus 帧解包。
- `listen.stop` 后使用 ASR -> Hermes -> TTS 完成最小闭环，ASR 默认 fake，可配置腾讯云一句话识别。
- 下发 `stt`、`llm`、`tts.start`、`tts.sentence_start`、二进制音频帧、`tts.stop`。
- 握手时读取小智固件请求头：`Authorization`、`Protocol-Version`、`Device-Id`、`Client-Id`。
- 可通过 `chatbot.voice.websocket.token` / `XIAOZHI_WEBSOCKET_TOKEN` 强制校验 WebSocket token。
- Hermes 请求优先使用 `Device-Id` 作为设备标识，缺失时回退到 WebSocket session id。

固件侧需要配置：

```text
websocket.url=ws://<server-host>:8766/xiaozhi/v1
websocket.token=<device-token>
websocket.version=1
```

`websocket.token` 会由固件转换为 `Authorization: Bearer <token>`；当前版本已保存该头部，
服务端配置 `XIAOZHI_WEBSOCKET_TOKEN` 后会强制鉴权。鉴权兼容 `Bearer <token>` 和纯 token；
未配置 token 时保留本地调试兼容行为。

本地 WebSocket smoke：

```bash
python3 scripts/xiaozhi_ws_smoke.py \
  --base-url ws://127.0.0.1:8766 \
  --token '<device-token>'
```

默认会覆盖 `/xiaozhi/v1`、`/ws/xiaozhi/v1` 和 `/ws/xiaozhi/v1/` 三个路径。

## 小智 OTA / 激活配置

固件侧 `ota_url` 指向：

```text
http://<server-host>:8766/api/ota/check
```

服务端返回 `websocket`、`server_time` 和 `firmware`。如果 `XIAOZHI_OTA_ACTIVATION_REQUIRED=true`，
响应会包含 `activation.challenge`，固件随后调用：

```text
POST /api/ota/check/activate
```

生产环境返回 `websocket.token` 前应配置 `XIAOZHI_OTA_ALLOWED_DEVICE_IDS`
或 `XIAOZHI_OTA_ALLOWED_SERIAL_NUMBERS`，避免向未知设备下发 token。
`XIAOZHI_OTA_WEBSOCKET_TOKEN` 默认继承 `XIAOZHI_WEBSOCKET_TOKEN`；如果没有配置
OTA 设备白名单，服务会拒绝启动，避免误把 WebSocket token 暴露给任意 OTA 请求。

本地 OTA smoke：

```bash
python3 scripts/xiaozhi_ota_smoke.py \
  --url http://127.0.0.1:8766/api/ota/check \
  --device-id smoke-device-1 \
  --client-id smoke-client-1
```

## 小智 MCP 薄桥

Java 服务端不实现设备业务工具，只通过已连接的小智 WebSocket 设备转发 JSON-RPC payload。
Hermes 接入时使用 Java 暴露的“小智设备 MCP 网关”HTTP JSON-RPC 入口。

列出在线设备：

```bash
curl -H "X-MCP-Admin-Token: <admin-token>" \
  http://203.195.202.54:8766/api/xiaozhi/devices
```

下发 `tools/list`：

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  -H "X-MCP-Admin-Token: <admin-token>" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"withUserTools":true}}' \
  http://203.195.202.54:8766/api/xiaozhi/devices/<device-id>/mcp
```

Hermes HTTP JSON-RPC 适配入口：

```text
POST http://203.195.202.54:8766/api/hermes/xiaozhi/mcp
Authorization: Bearer <hermes-mcp-token>
```

Hermes 侧 `tools/list` 会看到三个稳定工具：

- `xiaozhi_list_online_devices`
- `xiaozhi_list_device_tools`
- `xiaozhi_call_device_tool`

MCP 边界：

- Java 中间件不实现 MCP Server。
- Java 侧只做 Hermes 与在线小智设备之间的 JSON-RPC 薄透传桥。
- Hermes 负责工具调用、编排和记忆。
- 当前入口是 Spring MVC HTTP JSON-RPC endpoint，不是完整 MCP stdio/SSE transport。

## 暂不支持

- 不支持流式实时 ASR；当前真实 ASR 首版使用腾讯云一句话识别。
- 不实现完整 MCP stdio/SSE transport。
- 不提供管理后台。
- 不接 MySQL/Redis。
- 不做 RAG、长期记忆存储和 OTA 包上传后台。
- 不做声纹识别。
- 不迁移固件代码。

## 腾讯云 TTS

默认使用腾讯云语音合成。部署时需要提供腾讯云密钥，本地协议调试或测试可将 `chatbot.voice.tts.provider` 覆盖为 `fake`。

```yaml
chatbot:
  voice:
    websocket:
      token: ${XIAOZHI_WEBSOCKET_TOKEN:}
    tts:
      provider: tencent
      tencent:
        secret-id: ${TENCENT_CLOUD_SECRET_ID}
        secret-key: ${TENCENT_CLOUD_SECRET_KEY}
        region: ap-guangzhou
        endpoint: tts.tencentcloudapi.com
        voice-type: ${TENCENT_CLOUD_TTS_VOICE_TYPE:101001}
        codec: pcm
        sample-rate: 16000
```

腾讯云返回 PCM，服务端会编码成小智 WebSocket 使用的 Opus 帧后发送给设备。

容器部署时使用 env-file 注入：

```bash
docker run --env-file /opt/chatbot-service-java-runtime/chatbot-service.env ...
```

模板见 `deploy/chatbot-service.env.example`，真实 env 文件不要提交到 Git。

## 腾讯云 ASR

默认 ASR Provider 是 `fake`，用于本地协议 smoke。需要真实识别时配置腾讯云一句话识别：

```yaml
chatbot:
  voice:
    asr:
      provider: tencent
      tencent:
        secret-id: ${TENCENT_CLOUD_SECRET_ID}
        secret-key: ${TENCENT_CLOUD_SECRET_KEY}
        region: ap-guangzhou
        endpoint: asr.tencentcloudapi.com
        engine-model-type: ${TENCENT_CLOUD_ASR_ENGINE_MODEL_TYPE:16k_zh}
        voice-format: opus
        sample-rate: 16000
```
