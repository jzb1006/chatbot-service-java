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
curl -s -X POST http://127.0.0.1:8092/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"device_id":"device-1","conversation_id":"conv-1","message":"ping"}'
```

预期响应：

```json
{"conversation_id":"conv-1","reply":"pong"}
```

## WebSocket

小智 ESP32 WebSocket 入口：

```text
/xiaozhi/v1
```

建立连接后服务端会发送 `hello` 控制帧。第一阶段只支持 `listen.start` ack 和二进制音频帧接收日志。

## 暂不支持

- 不接真实 Hermes 服务。
- 不接真实 ASR/TTS 厂商。
- 不提供管理后台。
- 不接 MySQL/Redis。
- 不做 RAG、长期记忆存储和 OTA。
- 不做声纹识别。
- 不迁移固件代码。
