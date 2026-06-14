# Hermes Chatbot Service Java 架构方案

## 目标

`chatbot-service-java` 作为服务端中间件仓库，重写当前两类中间件能力：

1. 设备文本网关：承接现有 `/api/chat`、`/api/chat/stream`、`/api/conversations/new` 能力，服务 Arduino/ESP32 调试固件和后续设备文本链路。
2. 小智语音网关：承接小智 ESP32 WebSocket 协议，处理控制帧、Opus 音频帧、ASR、Hermes 对话、流式 TTS 和音色切换。

固件相关代码继续放在固件仓库，例如 `/Users/jiangzhibin/Documents/ardiuno` 和 `/Users/jiangzhibin/workspace/xiaozhi-hermes-firmware`。本仓库只放 Java 服务端。

## 设计原则

- 保持轻量：不引入管理后台、MySQL、Redis、RAG、OTA 和监控平台，除非后续有明确需求。
- Hermes 优先：Hermes Agent 负责 LLM、记忆、工具、人格和连续对话。
- 协议分层：设备协议、对话编排、ASR、TTS、Hermes 适配互相隔离。
- 可替换 Provider：ASR/TTS/Hermes 通过接口接入，先做最小实现，再扩展具体厂商。
- Java 21 优先：使用 Spring Boot 3、虚拟线程、record、构造器注入、清晰 DTO。

## 推荐模块

```text
chatbot-service-java/
  pom.xml
  chatbot-bootstrap/
  chatbot-common/
  chatbot-device-gateway/
  chatbot-voice-gateway/
  chatbot-hermes-adapter/
  chatbot-speech-api/
```

### chatbot-bootstrap

Spring Boot 启动模块，聚合 REST、WebSocket、配置和健康检查。

职责：

- 提供唯一启动入口。
- 装配设备文本网关和语音网关。
- 暴露 `/actuator/health`。
- 管理配置项，例如 Hermes 地址、设备令牌、默认音色、音频参数。

### chatbot-common

共享基础模型和工具，不承载业务流程。

职责：

- 会话标识模型：`DeviceId`、`SessionId`、`ConversationId`、`SpeakerId`、`VoiceId`。
- 统一错误：认证失败、协议错误、Provider 调用失败。
- 时间、ID 生成、轻量配置常量。

### chatbot-device-gateway

文本设备网关，迁移现有 Python `device_gateway` 的薄客户端能力。

职责：

- `POST /api/chat`
- `POST /api/chat/stream`
- `POST /api/conversations/new`
- 设备 token 校验。
- 将文本请求转给 Hermes 适配层。

不负责：

- 不保存长期记忆。
- 不实现模型编排。
- 不做管理后台。

### chatbot-voice-gateway

小智 WebSocket 协议网关。

职责：

- 接收小智 ESP32 WebSocket 连接。
- 处理 `hello`、`listen`、`abort`、`iot` 等 JSON 控制帧。
- 接收二进制 Opus 音频帧。
- 下发 `tts.start`、`tts.sentence_start`、音频帧、`tts.stop`。
- 维护音频会话生命周期。

不负责：

- 不直接实现 Hermes 记忆。
- 不把 ASR/TTS 厂商逻辑写死到 WebSocket Handler。

### chatbot-hermes-adapter

Hermes Agent 适配层。

职责：

- 调用 Hermes `/v1/responses`。
- 支持非流式文本响应。
- 支持流式文本 delta。
- 传递 `conversation_id` 维持连续对话。
- 通过 `X-Hermes-Session-Key` 或等价配置传递用户/声纹身份。

### chatbot-speech-api

语音 Provider 抽象层。

职责：

- `SpeechToTextClient`：Opus/PCM 音频到文本。
- `TextToSpeechClient`：文本到音频流。
- `VoiceCatalog`：管理 `voice_id` 到厂商音色参数的映射。

第一阶段可以只提供 Fake Provider，用于协议联调和自动化测试。

## 核心数据流

### 文本链路

```text
ESP32 Arduino 固件
  -> POST /api/chat 或 /api/chat/stream
  -> DeviceChatController
  -> ChatOrchestrator
  -> HermesClient
  -> Hermes Agent
```

### 语音链路

```text
小智 ESP32 固件
  -> WebSocket /xiaozhi/v1
  -> XiaozhiWebSocketHandler
  -> AudioSession
  -> SpeechToTextClient
  -> HermesClient
  -> TextToSpeechClient
  -> WebSocket 音频帧下发
```

## 会话模型

| 标识 | 生命周期 | 归属 | 用途 |
|------|----------|------|------|
| `device_id` | 设备长期 | 固件/服务端 | 设备认证、配置隔离 |
| `session_id` | WebSocket 或一次音频连接 | 语音网关 | 音频状态和打断控制 |
| `conversation_id` | 连续对话 | Hermes | 多轮上下文 |
| `speaker_id` | 用户身份 | 声纹识别后续阶段 | 多人记忆隔离 |
| `voice_id` | 可配置音色 | TTS | 切换人物音色 |

## MVP 范围

第一阶段只做服务骨架和协议骨架：

- Maven 多模块 Spring Boot 3 项目。
- Java 21。
- 文本网关三个 REST API 的 DTO 和 Fake Hermes 测试实现。
- 小智 WebSocket 握手、控制帧解析、二进制音频帧接收。
- Fake TTS 下发控制事件，不接真实音频合成。
- 内存会话表，不接数据库。

## 明确暂不做

- 不做后台管理页面。
- 不接 MySQL/Redis。
- 不做 RAG 和长期记忆存储。
- 不做 OTA。
- 不做生产级监控平台。
- 不做声纹识别模型集成。
- 不迁移固件代码到 Java 仓库。

## 后续演进

1. 接真实 Hermes `/v1/responses`。
2. 接真实流式 TTS。
3. 接 ASR。
4. 用 `voice_id` 做多人物音色配置。
5. 声纹识别产出 `speaker_id` 后，再把身份传给 Hermes。
6. 需要多设备管理时再评估数据库和管理端。
