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
- `listen.stop` 后使用 Fake ASR -> Hermes -> Fake TTS 完成最小闭环。
- 下发 `stt`、`llm`、`tts.start`、`tts.sentence_start`、二进制音频帧、`tts.stop`。
- 握手时读取小智固件请求头：`Authorization`、`Protocol-Version`、`Device-Id`、`Client-Id`。
- Hermes 请求优先使用 `Device-Id` 作为设备标识，缺失时回退到 WebSocket session id。

固件侧需要配置：

```text
websocket.url=ws://<server-host>:8766/xiaozhi/v1
websocket.token=<device-token>
websocket.version=1
```

`websocket.token` 会由固件转换为 `Authorization: Bearer <token>`；当前版本已保存该头部，
但还未执行强制鉴权，真实 token 校验仍属于后续任务。

MCP 边界：

- Java 中间件不实现 MCP Server。
- 收到 `type=mcp` 只记录并忽略。
- Hermes 负责工具调用、编排和记忆。
- 后续如需控制设备本身，只做 Hermes 与小智设备之间的薄透传桥。

## 暂不支持

- 不接真实 ASR 厂商。
- 不实现 Java 侧 MCP Server。
- 不提供管理后台。
- 不接 MySQL/Redis。
- 不做 RAG、长期记忆存储和 OTA。
- 不做声纹识别。
- 不迁移固件代码。

## 腾讯云 TTS

默认使用腾讯云语音合成。部署时需要提供腾讯云密钥，本地协议调试或测试可将 `chatbot.voice.tts.provider` 覆盖为 `fake`。

```yaml
chatbot:
  voice:
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
