# 小智固件接入服务端任务清单（可执行版）

## 文档状态

- 文档用途：作为当前 Java 服务端对接小智 ESP32 固件的执行清单。
- 当前结论：可以开始执行第一轮 Sprint；腾讯云 ASR 接入、真机 TTS 播放验收和 OTA 仍受外部条件约束。
- 执行原则：先完成不依赖硬件的软件闭环，再做真机端到端联调。
- 代码边界：当前仓库只做 Java 服务端中间件，不迁入 ESP32 固件代码。
- 参考边界：只摘取 `joey-zhou/xiaozhi-esp32-server-java` 的协议和实现思路，不迁移其 MySQL、Redis、后台管理和双进程架构。

## 已明确的决策

- 当前仓库只负责 Java 服务端中间件，不迁入 ESP32 固件代码。
- 小智固件语音主入口使用 WebSocket：`/xiaozhi/v1`；同时兼容 `/ws/xiaozhi/v1` 和 `/ws/xiaozhi/v1/`。
- 固件协议以 `/Users/jiangzhibin/workspace/xiaozhi-esp32/main/protocols/websocket_protocol.cc` 为准。
- 固件 WebSocket 握手头保持一致：`Authorization`、`Protocol-Version`、`Device-Id`、`Client-Id`。
- Java 侧保持 thin gateway：协议接入、音频会话状态、ASR/TTS/Hermes 转接，不引入重型平台能力。
- 语音识别首版使用腾讯云 ASR；实现方式参考当前腾讯云 TTS 的 Provider 配置、Bean 装配和 Fake 回退模式，其他 ASR 平台后续再扩展。
- MCP 不是基础语音闭环阻塞项；如后续需要设备控制，优先做 Hermes 与小智设备之间的薄透传桥。
- 当前优先目标是硬件刷小智固件后可完成真实语音对话闭环。
- 采用参考实现摘取路线：参考 `joey-zhou/xiaozhi-esp32-server-java` 的 ASR、TTS、OTA、MCP 和打断实现，不整库迁移，不引入其 MySQL、Redis、后台管理和双进程架构。

## 当前完成状态

- [x] WebSocket 主入口 `/xiaozhi/v1` 已存在。
- [x] WebSocket 兼容入口 `/ws/xiaozhi/v1` 和 `/ws/xiaozhi/v1/` 已注册。
- [x] 固件握手头 `Authorization`、`Protocol-Version`、`Device-Id`、`Client-Id` 已读取到会话。
- [x] Hermes 请求已优先使用 `Device-Id` 作为设备标识。
- [x] 设备先发 `hello`、服务端返回 `hello`，且使用 `audio_params` 字段。
- [x] WebSocket binary v1/v2/v3 Opus 帧解包已有测试覆盖。
- [x] Fake ASR / Fake TTS 可支撑本地协议闭环测试。
- [x] 腾讯云 TTS 已接入，服务器上已配置腾讯云参数。
- [x] WebSocket token 已支持通过配置强制鉴权，兼容 `Bearer <token>` 和纯 token。
- [x] ASR 首版已接入腾讯云一句话识别 Provider，默认仍保留 Fake 回退；真实云识别待部署配置后验证。
- [ ] 腾讯云 TTS 尚未完成小智真机播放验收。
- [ ] OTA / 激活配置接口尚未实现。
- [ ] MCP 目前只记录并忽略，尚未做 Hermes 到设备的薄透传。

## 执行条件与阻塞项

| 类别 | 当前状态 | 对执行的影响 | 处理方式 |
|------|----------|--------------|----------|
| 硬件真机 | 未在本文档中确认可用 | 阻塞真机播放、唤醒、麦克风、扬声器验收 | 第一轮先做本地和公网 WebSocket smoke |
| ASR Provider | 已实现腾讯云一句话识别 Provider | 不再阻塞服务端代码；阻塞点转为云参数和真实音频识别验证 | 配置 `chatbot.voice.asr.provider=tencent` 后使用腾讯云 |
| TTS 配置 | 腾讯云 TTS 已接入且服务器已配置 | 不再阻塞服务端实现；仍需验证真机播放 | 保留 Fake 回退，硬件到位后做播放验收 |
| WebSocket token 来源 | 需要服务端配置来源 | 阻塞强制鉴权 | 第一轮用环境变量或现有设备配置做最小实现 |
| OTA 是否需要 | 未决定 | 不阻塞手工配置固件联调 | 首版默认手工配置，OTA 作为可选任务 |
| MCP 是否需要 | 不进入基础闭环 | 不阻塞语音对话 | 语音闭环稳定后再追加 |

## 整体规划概述

### 项目目标

在 ESP32 硬件刷入小智 AI 固件后，使设备可以稳定连接当前 Java 服务，通过 WebSocket 完成真实语音输入、Hermes 对话、TTS 音频播放，并具备基础鉴权、配置和联调验证能力。

### 技术栈

- Java 21
- Spring Boot 3
- Spring WebSocket
- Maven 多模块
- Hermes Agent
- Opus 16k 单声道 60ms 音频帧
- 腾讯云 TTS 或等价 TTS Provider
- 腾讯云 ASR，后续可扩展其他 ASR Provider
- ESP-IDF 小智固件
- 参考实现：`https://github.com/joey-zhou/xiaozhi-esp32-server-java`

### 主要阶段

1. 第一轮可执行 Sprint：WebSocket 鉴权、运行观测日志、失败路径测试、联调脚本。
2. 基础语音闭环补齐：接入腾讯云 ASR，确认 TTS 真机可播放。
3. 真机连接与配置：硬件刷小智固件后完成公网端到端联调，按需决定 OTA/激活配置。
4. 增强能力：按需补 MCP 透传、打断优化、设备管理等非首发能力。

## 第一轮 Sprint：当前可直接执行

> 目标：不依赖真机，先把服务端接入边界、鉴权、日志和自动化验证补齐；ASR 首版按腾讯云 Provider 实现，TTS 使用服务器现有腾讯云配置做后续公网验证。

### Sprint 1.1：WebSocket token 鉴权

- 对应任务：任务 2.1。
- 当前状态：已完成本地实现与自动化测试；公网 smoke 和真机连接待部署后执行。
- 推荐实现：使用配置项提供服务端期望 token；固件侧 `websocket.token` 经 `Authorization: Bearer <token>` 传入。
- 最小范围：
  - 校验 `Authorization` 是否存在。
  - 兼容带 `Bearer ` 前缀和纯 token 两种输入。
  - 鉴权失败时拒绝握手或关闭连接。
  - 日志只输出鉴权结果，不输出 token 原文。
- 验收证据：
  - 无 token 连接失败。
  - 错 token 连接失败。
  - 正确 token 可完成 `hello -> listen.start -> listen.stop`。
  - `mvn -pl chatbot-voice-gateway -am test` 通过。

### Sprint 1.2：运行观测日志

- 对应任务：任务 3.2。
- 当前状态：已完成本地实现；日志覆盖连接、hello、listen、回合耗时、空 ASR、Hermes/TTS 异常和非法 binary frame。
- 推荐实现：在 `XiaozhiWebSocketHandler` 和 `XiaozhiVoiceSessionService` 增加关键节点日志。
- 最小范围：
  - 连接建立：session id、device id、client id、protocol version。
  - hello：协议版本、音频参数。
  - 语音回合：音频帧数量、ASR/Hermes/TTS 耗时。
  - 异常：ASR 空结果、Hermes 异常、TTS 异常、非法 binary frame。
- 验收证据：
  - 自动化测试不泄漏 token。
  - 本地 smoke 日志能定位连接、识别、对话、合成四个阶段。

### Sprint 1.3：真实闭环失败路径测试

- 对应任务：任务 1.3。
- 当前状态：已完成自动化测试；真实 ASR 和真机播放仍依赖后续条件。
- 推荐实现：继续保留 Fake ASR/TTS，补异常分支测试，不引入真实厂商依赖。
- 最小范围：
  - ASR 返回空文本时不调用 Hermes，并给出可定位日志。
  - Hermes 异常时会话能回到可恢复状态。
  - TTS 返回空音频时仍发送明确的结束状态。
  - 非 listening 状态收到 binary frame 不污染下一轮音频缓存。
- 验收证据：
  - `XiaozhiVoiceSessionServiceTest` 覆盖上述分支。
  - `mvn -pl chatbot-voice-gateway -am test` 通过。

### Sprint 1.4：联调脚本与公网 smoke

- 对应任务：任务 2.3 的软件预演部分。
- 当前状态：已新增标准库 Python smoke 脚本；本地服务已通过，公网服务待部署后执行。
- 推荐实现：新增或完善一个本地 WebSocket smoke 脚本，模拟固件握手头和控制帧。
- 最小范围：
  - 支持 `/xiaozhi/v1`、`/ws/xiaozhi/v1`、`/ws/xiaozhi/v1/` 三个路径。
  - 支持传入 token、device id、client id、protocol version。
  - 校验服务端 hello、stt、llm、tts.start、tts.stop。
- 验收证据：
  - 本地服务通过 smoke。
  - 部署到公网后，通过 `203.195.202.54:8766` 复测。

## 统一验证命令

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am test
```

如本机 Maven 已在 `PATH` 中，也可以使用：

```bash
mvn -pl chatbot-voice-gateway -am test
```

发布到远程前需要额外验证：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-bootstrap -am -DskipTests package
```

## 发布与真机测试门禁

任一 Sprint 任务完成后，如果改动会影响 WebSocket 协议、ASR、Hermes 调用、TTS 下发、鉴权、配置或部署行为，必须执行以下门禁，不能只停留在本地测试：

1. 本地自动化测试通过。
2. 构建 `chatbot-bootstrap` 可部署包。
3. 部署到服务器 `203.195.202.54:8766`。
4. 执行公网 WebSocket smoke，覆盖至少一个固件兼容路径。
5. 使用小智真机完成一次端到端语音测试。
6. 将测试结果记录到本文档的「真机测试记录」章节。

真机测试至少记录：

- 测试时间。
- 部署版本或 Git commit。
- 服务器地址与 WebSocket 路径。
- 固件设备 ID / Client ID。
- token 鉴权结果。
- 设备 hello / server hello 是否成功。
- ASR 识别文本。
- Hermes 回复摘要。
- TTS 是否正常播放。
- 失败现象、日志位置和下一步处理。

## 详细任务分解

### 阶段 1：基础语音闭环补齐

- [x] **任务 1.1：接入腾讯云 ASR Provider**
  - 目标：替换当前 `FakeSpeechToTextClient`，让设备真实语音可以转成文本。
  - 输入：小智固件上行 Opus 音频帧、腾讯云 ASR 配置、音频解码/转码结果。
  - 输出：`SpeechToTextClient` 的腾讯云实现。
  - 当前状态：已完成 Provider、配置、Bean 装配和单元测试；真实云识别待部署配置后验证。
  - 依赖条件：腾讯云 ASR 参数和真实小智音频样本；如复用现有腾讯云账号，则设置对应环境变量。
  - 涉及文件：
    - `chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/SpeechToTextClient.java`
    - `chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentCloudSpeechToTextClient.java`
    - `chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentCloudSpeechToTextConfig.java`
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeans.java`
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
  - 参考实现：
    - 当前项目 TTS 装配：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeans.java`
    - 当前项目 TTS 实现：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentCloudTextToSpeechClient.java`
    - `xiaozhi-ai/src/main/java/com/xiaozhi/ai/stt/SttServiceFactory.java`
    - `xiaozhi-ai/src/main/java/com/xiaozhi/ai/stt/providers/TencentSttService.java`
  - 推荐配置形态：
    - `chatbot.voice.asr.provider=tencent`
    - `chatbot.voice.asr.tencent.secret-id`
    - `chatbot.voice.asr.tencent.secret-key`
    - `chatbot.voice.asr.tencent.region`
    - `chatbot.voice.asr.tencent.endpoint`
    - `chatbot.voice.asr.tencent.sample-rate=16000`
  - 验收标准：
    - WebSocket 收到设备音频后，ASR 返回真实用户语音文本。（待部署配置和真实音频验证）
    - 保留 Fake ASR 作为本地测试回退通道。
    - `chatbot.voice.asr.provider=fake` 时不访问腾讯云。
    - `chatbot.voice.asr.provider=tencent` 且密钥缺失时启动失败信息明确。
    - ASR 失败时向日志输出可定位错误，不导致 WebSocket 会话异常泄漏。
    - `mvn -pl chatbot-voice-gateway -am test` 通过。
  - 预估工作量：1-2 天。

- [ ] **任务 1.2：确认 TTS 真机可播放**
  - 目标：验证腾讯云 TTS 返回 PCM 后，经 Opus 编码下发给小智固件可正常播放。
  - 输入：服务器已配置的腾讯云 TTS 参数、测试文本、小智真机或固件模拟链路。
  - 输出：可播放的 Opus 二进制帧。
  - 当前状态：部分可执行；腾讯云 TTS 已接入且服务器已配置，真机播放仍依赖硬件或等价播放验证环境。
  - 依赖条件：可运行的小智真机或等价播放验证环境。
  - 涉及文件：
    - `chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentCloudTextToSpeechClient.java`
    - `chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/PcmToOpusEncoder.java`
    - `chatbot-bootstrap/src/main/resources/application.yml`
  - 参考实现：
    - `xiaozhi-ai/src/main/java/com/xiaozhi/ai/tts/TtsServiceFactory.java`
    - `xiaozhi-ai/src/main/java/com/xiaozhi/ai/tts/providers/TencentTtsService.java`
    - `xiaozhi-dialogue/src/main/java/com/xiaozhi/dialogue/playback/Player.java`
    - `xiaozhi-common/src/main/java/com/xiaozhi/utils/OpusProcessor.java`
  - 验收标准：
    - 服务端发送 `tts.start`、`tts.sentence_start`、二进制音频、`tts.stop` 顺序正确。
    - 真机可以听到完整 TTS 回复。
    - 未配置 TTS 密钥时启动行为明确，不能误以为 fake 音频是真实可播放音频。
    - `mvn -pl chatbot-speech-api -am test` 和 `mvn -pl chatbot-voice-gateway -am test` 通过。
  - 预估工作量：0.5-1 天。

- [x] **任务 1.3：补充真实闭环测试**
  - 目标：覆盖 `listen.start -> binary audio -> listen.stop -> ASR -> Hermes -> TTS` 主流程。
  - 输入：测试音频帧、Fake Hermes、Fake/真实 Speech Provider。
  - 输出：自动化测试和联调脚本。
  - 当前状态：已完成自动化测试和联调脚本。
  - 依赖条件：无外部依赖。
  - 涉及文件：
    - `chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`
    - `chatbot-speech-api/src/test/java/com/jzb/chatbot/speech/*`
  - 验收标准：
    - Maven 测试通过。
    - 失败路径覆盖 ASR 空结果、TTS 空结果、Hermes 异常。
    - 保留 Fake ASR/TTS 作为默认测试通道。
  - 预估工作量：0.5-1 天。

### 阶段 2：真机连接与安全接入

- [x] **任务 2.1：实现 WebSocket 鉴权**
  - 目标：校验小智固件握手时携带的 `Authorization` 或等价 token。
  - 输入：固件侧 `websocket.token`、服务端配置文件中的 device token。
  - 输出：WebSocket 握手拦截与认证逻辑。
  - 当前状态：已完成本地实现与自动化测试；配置 `chatbot.voice.websocket.token` 后强制校验 token。
  - 依赖条件：首版使用环境变量 `XIAOZHI_WEBSOCKET_TOKEN` 提供全局 token。
  - 涉及文件：
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiWebSocketConfig.java`
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiWebSocketHandler.java`
    - `chatbot-device-gateway/src/main/java/com/jzb/chatbot/device/config/*`
  - 参考实现：
    - `xiaozhi-dialogue/src/main/java/com/xiaozhi/communication/server/websocket/WebSocketHandler.java`
  - 约束：
    - 参考实现主要校验 `device-id`，其 OTA 返回的 `websocket.token` 为空；当前项目需要补齐真实 token 校验，不能原样照搬。
  - 验收标准：
    - 无 token 或 token 错误的连接被拒绝。
    - 合法固件连接不受影响。
    - 日志记录设备 ID、客户端 ID 和认证结果，不打印敏感 token。
    - `/xiaozhi/v1`、`/ws/xiaozhi/v1`、`/ws/xiaozhi/v1/` 三个路径鉴权行为一致。
    - `mvn -pl chatbot-voice-gateway -am test` 通过。
  - 预估工作量：0.5-1 天。

- [x] **任务 2.2：建立设备标识与会话映射**
  - 目标：使用 `Device-Id` / `Client-Id` 作为稳定设备标识，而不是只依赖 WebSocket session id。
  - 输入：WebSocket 握手请求头。
  - 输出：语音会话中的设备上下文。
  - 当前状态：已完成；已读取 `Protocol-Version`、`Device-Id`、`Client-Id`，Hermes 请求优先使用 `Device-Id`，后续只需在真机联调时验证真实设备头。
  - 涉及文件：
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSession.java`
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
  - 参考实现：
    - `xiaozhi-dialogue/src/main/java/com/xiaozhi/communication/common/SessionManager.java`
    - `xiaozhi-dialogue/src/main/java/com/xiaozhi/communication/common/MessageHandler.java`
  - 验收标准：
    - Hermes 请求中的 device id 使用真实设备标识。
    - 多设备同时连接时上下文互不污染。
  - 预估工作量：0.5 天。

- [ ] **任务 2.3：执行真机端到端联调**
  - 目标：硬件刷小智固件后，通过公网 Java 服务完成一次真实语音对话。
  - 输入：已刷固件设备、Wi-Fi、WebSocket URL、token、ASR/TTS 配置。
  - 输出：端到端联调记录，追加到本文档「真机测试记录」章节。
  - 当前状态：分两步执行；软件 smoke 可先做，真机验收依赖硬件到位。
  - 依赖条件：已刷小智固件的硬件、网络、token、ASR/TTS 配置。
  - 验收标准：
    - 固件连接 `ws://203.195.202.54:8766/xiaozhi/v1` 或 `ws://203.195.202.54:8766/ws/xiaozhi/v1/` 成功。
    - 服务端收到设备 hello 并返回 server hello。
    - 设备说话后，服务端日志能看到 ASR 文本和 Hermes 回复。
    - 设备能播放 TTS 回复。
    - 公网服务日志和 smoke 输出一并归档到联调记录。
    - 测试结果必须包含通过/失败结论；失败时必须记录下一步处理项。
  - 预估工作量：0.5-1 天，依赖硬件到位。

### 阶段 3：固件配置与运维支撑

- [x] **任务 3.1：确定固件配置方式**
  - 目标：决定首版使用手工配置 WebSocket，还是实现 OTA/激活接口下发配置。
  - 输入：硬件刷机方式、是否需要多设备批量配置、是否需要远程更新。
  - 输出：配置方案决策。
  - 当前状态：已决策；首版使用手工配置 `websocket.url/token/version`，OTA 不进入第一轮 Sprint。
  - 推荐结论：首版使用手工配置 `websocket.url/token/version`，硬件联调稳定后再决定是否做 OTA。
  - 涉及文件：
    - `README.md`
    - `docs/architecture/hermes-chatbot-service-architecture.md`
  - 验收标准：
    - 文档明确固件侧需要配置的 `websocket.url`、`websocket.token`、`websocket.version`。
    - 明确 OTA 是否进入当前迭代。
  - 预估工作量：0.5 天。

- [x] **任务 3.2：补齐运行观测日志**
  - 目标：让真机联调失败时可以快速定位是连接、ASR、Hermes、TTS 还是音频格式问题。
  - 输入：当前 WebSocket 会话日志。
  - 输出：结构化关键日志。
  - 当前状态：已完成本地实现；日志覆盖连接、hello、listen、回合耗时、空 ASR、Hermes/TTS 异常和非法 binary frame。
  - 依赖条件：无外部依赖。
  - 涉及文件：
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiWebSocketHandler.java`
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
  - 验收标准：
    - 每个语音回合都有 session id、device id、音频帧数量、ASR 耗时、Hermes 耗时、TTS 耗时。
    - 日志不输出密钥、token、完整敏感配置。
    - 自动化测试覆盖 token 脱敏或至少确认日志实现不直接输出 `Authorization` 原文。
  - 预估工作量：0.5 天。

- [ ] **任务 3.3：可选实现 OTA/激活配置接口**
  - 目标：如果需要贴近小智官方体验，由服务端向固件下发 websocket 配置和版本信息。
  - 输入：小智固件 OTA 协议、设备激活策略、固件版本信息。
  - 输出：最小 OTA/激活配置 API。
  - 当前状态：暂缓；不进入第一轮 Sprint。
  - 依赖条件：确认是否需要批量设备配置或远程配置下发。
  - 涉及文件：
    - 新模块或现有 `chatbot-device-gateway`
    - `chatbot-bootstrap/src/main/resources/application.yml`
    - `docs/architecture/hermes-chatbot-service-architecture.md`
  - 参考实现：
    - `xiaozhi-server/src/main/java/com/xiaozhi/device/DeviceController.java`
    - `xiaozhi-server/src/main/java/com/xiaozhi/device/DeviceAppService.java`
  - 约束：
    - 当前项目首版只做最小配置下发，不复制用户、角色、设备绑定后台。
  - 验收标准：
    - 固件可从服务端获取 websocket 配置。
    - 不引入数据库前提下，配置可由文件或环境变量驱动。
    - 未选择 OTA 前，不影响手工配置固件连接 WebSocket。
  - 预估工作量：1-2 天。

### 阶段 4：增强能力

- [ ] **任务 4.1：MCP 薄透传桥**
  - 目标：在需要设备控制时，让 Hermes 与小智设备之间可以交换 `type=mcp` 消息。
  - 输入：Hermes 工具调用能力、小智固件 MCP 消息格式。
  - 输出：MCP 消息转发逻辑。
  - 当前状态：暂缓；不进入基础语音闭环。
  - 依赖条件：Hermes 侧明确需要调用设备能力。
  - 涉及文件：
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
    - `chatbot-hermes-adapter/src/main/java/com/jzb/chatbot/hermes/*`
  - 参考实现：
    - `xiaozhi-dialogue/src/main/java/com/xiaozhi/dialogue/llm/tool/mcp/device/DeviceMcpService.java`
    - `xiaozhi-dialogue/src/main/java/com/xiaozhi/dialogue/llm/tool/mcp/device/DeviceMcpHolder.java`
  - 验收标准：
    - Java 不重复实现完整 MCP Server。
    - 设备端工具调用可以被 Hermes 触发并返回结果。
  - 预估工作量：1-2 天。

- [ ] **任务 4.2：优化打断与并发回合**
  - 目标：支持用户打断 TTS、连续唤醒、异常关闭时资源清理。
  - 输入：`abort` 控制帧、WebSocket 关闭事件、TTS 下发状态。
  - 输出：更稳健的会话状态机。
  - 当前状态：暂缓；基础闭环和日志完成后再做。
  - 依赖条件：需要真实或模拟的长 TTS 播放场景。
  - 涉及文件：
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSession.java`
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
  - 参考实现：
    - `xiaozhi-dialogue/src/main/java/com/xiaozhi/dialogue/DialogueService.java`
    - `xiaozhi-dialogue/src/main/java/com/xiaozhi/dialogue/playback/Player.java`
    - `xiaozhi-dialogue/src/main/java/com/xiaozhi/dialogue/playback/FileSynthesizer.java`
  - 验收标准：
    - TTS 播放中收到 `abort` 后停止继续下发音频。
    - 会话关闭后缓存音频和任务状态被清理。
  - 预估工作量：1 天。

- [ ] **任务 4.3：设备管理最小化**
  - 目标：仅在多设备使用时增加必要的设备配置隔离。
  - 输入：设备 ID、token、音色、用户归属等配置需求。
  - 输出：最小设备配置模型。
  - 当前状态：暂缓；单设备阶段不引入管理后台或数据库。
  - 依赖条件：多设备 token、音色或用户归属配置需求明确。
  - 涉及文件：
    - `chatbot-device-gateway/src/main/java/com/jzb/chatbot/device/config/*`
    - `chatbot-common/src/main/java/com/jzb/chatbot/common/id/*`
  - 验收标准：
    - 不引入管理后台和数据库，除非多设备配置已无法用文件维护。
    - 单设备路径不被复杂化。
  - 预估工作量：1 天。

## 推荐执行顺序

1. 已完成：WebSocket 兼容路径、固件握手头读取、`Device-Id` 会话映射。
2. 已完成：WebSocket token 鉴权。
3. 已完成：运行观测日志。
4. 已完成：真实闭环失败路径测试。
5. 已完成：本地 WebSocket smoke 脚本，覆盖三个连接路径和握手头。
6. 已完成：按 TTS 模式接入腾讯云 ASR。
7. 任务实现完成后：构建并部署到服务器 `203.195.202.54:8766`。
8. 部署后：用公网 WebSocket smoke 验证服务端链路。
9. 硬件到位后：执行小智真机端到端语音测试，并记录测试结果。
10. 真机联调稳定后：决定是否进入 OTA/激活配置接口。
11. 需要设备控制时：追加 MCP 薄透传桥。

## 当前不建议立即做的事项

- 不建议直接引入 MySQL、Redis、后台管理系统。
- 不建议把 `xiaozhi-esp32-server-java` 的整套重型平台搬进当前仓库。
- 不建议在没有真机验证前扩大到声纹、视觉、Assets、复杂 OTA 等功能。
- 不建议在 Java 网关中重复实现完整 MCP Server。
- 不建议把 `xiaozhi-esp32-server-java` 的空 token 策略和设备绑定后台原样带入当前项目；`/ws/xiaozhi/v1/` 仅作为固件兼容路径保留。

## 需要进一步明确的问题

### 问题 1：WebSocket token 配置来源

**已决策**：

- 首版使用环境变量配置单个全局 token：`XIAOZHI_WEBSOCKET_TOKEN`。
- 服务端配置项：`chatbot.voice.websocket.token`。
- 多设备 token 隔离待真机联调稳定后再升级。

**状态**：已实现，兼容 `Bearer <token>` 和纯 token。

### 问题 2：腾讯云 ASR 接口形态

**已决策**：

- 首版使用腾讯云一句话识别，将本轮 Opus 音频聚合后提交。
- 后续需要边说边识别时再升级为实时语音识别流式接口。

**状态**：已实现腾讯云一句话识别 Provider，真实云识别待部署配置和真实音频验证。

### 问题 3：固件配置方式

**已决策**：

- 首版手工配置 `websocket.url/token/version`。
- OTA/激活配置接口不进入第一轮 Sprint。

**状态**：已在 README 和当前清单记录配置方式；真机联调稳定后再决定是否做 OTA。

### 问题 4：MCP 是否进入本轮迭代

**已决策**：

- 本轮不做 MCP，只保证真实语音聊天闭环。
- 需要设备控制时，再追加 Hermes 到小智设备的 MCP 薄透传桥。

**状态**：暂缓，不进入基础闭环。

## 用户反馈区域

请在此区域补充您对整体规划的意见和建议：

```text
用户补充内容：

---

---

---
```

## 真机测试记录

> 每次任务实现完成并部署后，在这里追加一条记录。

### 测试记录模板

```text
测试时间：
部署版本 / Git commit：
服务器地址：
WebSocket 路径：
固件设备 ID：
Client ID：
token 鉴权结果：
设备 hello / server hello：
ASR 识别文本：
Hermes 回复摘要：
TTS 播放结果：
最终结论：
失败现象与日志位置：
下一步处理：
```

### 2026-06-16 公网 WebSocket smoke

```text
测试时间：2026-06-16 08:04:34 +08:00
部署版本 / Git commit：cf00744；远程镜像 sha256:bb472626accb098d884efc5367829eb1542fda626d32c1cd2219953042332cb1
服务器地址：203.195.202.54:8766
WebSocket 路径：/xiaozhi/v1、/ws/xiaozhi/v1、/ws/xiaozhi/v1/
固件设备 ID：smoke-device-1
Client ID：smoke-client-1
token 鉴权结果：当前远程未配置 XIAOZHI_WEBSOCKET_TOKEN，authRequired=false，连接通过
设备 hello / server hello：通过；server hello 返回 audio_params(format=opus, sample_rate=16000, channels=1, frame_duration=60)
ASR 识别文本：ping（当前远程未配置 CHATBOT_VOICE_ASR_PROVIDER=tencent，走 Fake ASR）
Hermes 回复摘要：pong
TTS 播放结果：公网 smoke 收到 tts.start、tts.sentence_start、17 个二进制音频帧、tts.stop；未做真机扬声器播放验收
最终结论：公网 WebSocket 软件链路通过；真机 TTS 播放验收仍未完成
失败现象与日志位置：首次重启后 3 秒 health 检查出现一次连接 reset，随后 /actuator/health 返回 UP；容器日志见 device_gateway
下一步处理：硬件到位后使用小智真机连接 ws://203.195.202.54:8766/xiaozhi/v1，补充真实麦克风 ASR 与扬声器播放记录
```

### 2026-06-16 远程 ASR 配置切换

```text
测试时间：2026-06-16 08:44:53 +08:00
部署版本 / Git commit：cf00744；远程镜像 sha256:bb472626accb098d884efc5367829eb1542fda626d32c1cd2219953042332cb1
服务器地址：203.195.202.54:8766
配置变更：/opt/chatbot-service-java-runtime/chatbot-service.env 增加 CHATBOT_VOICE_ASR_PROVIDER=tencent、TENCENT_CLOUD_ASR_ENGINE_MODEL_TYPE=16k_zh
配置备份：/opt/chatbot-service-java-runtime/backups/chatbot-service.env.20260616084252
WebSocket 路径：/xiaozhi/v1
固件设备 ID：asr-config-probe
Client ID：asr-config-probe
token 鉴权结果：当前远程未配置 XIAOZHI_WEBSOCKET_TOKEN，authRequired=false，连接通过
设备 hello / server hello：通过；server hello 返回 audio_params(format=opus, sample_rate=16000, channels=1, frame_duration=60)
ASR 识别文本：未发送音频，不触发真实 ASR 调用
Hermes 回复摘要：未触发
TTS 播放结果：未触发
最终结论：远程已切换为腾讯云真实 ASR Provider，服务启动与 WebSocket 握手正常
失败现象与日志位置：第一次手写探针帧长度编码错误，产生一条 invalid control frame；修正探针后 hello 验证通过，容器日志见 device_gateway
下一步处理：使用真实小智设备或有效 Opus 音频样本执行 listen.stop，验证腾讯云 ASR 真实识别结果
```

### 2026-06-16 真实 ASR 调用测试

```text
测试时间：2026-06-16 08:53:24 +08:00
部署版本 / Git commit：cf00744；远程镜像 sha256:bb472626accb098d884efc5367829eb1542fda626d32c1cd2219953042332cb1
服务器地址：203.195.202.54:8766
WebSocket 路径：/xiaozhi/v1
固件设备 ID：real-asr-opus-test
Client ID：real-asr-opus-test
token 鉴权结果：当前远程未配置 XIAOZHI_WEBSOCKET_TOKEN，authRequired=false，连接通过
设备 hello / server hello：通过；server hello 返回 audio_params(format=opus, sample_rate=16000, channels=1, frame_duration=60)
ASR 识别文本：未返回识别文本；腾讯云 ASR 拒绝 VoiceFormat=opus
Hermes 回复摘要：未触发
TTS 播放结果：未触发
最终结论：真实 ASR 调用已触发，但当前服务端 ASR 配置/封装不符合腾讯云一句话识别要求；服务本身仍保持 UP
失败现象与日志位置：device_gateway 日志报 TencentCloudSDKException: SentenceRecognitionReqNoData.VoiceFormat: opus not in list: [mp3,wav,pcm,m4a,speex,silk,aac,ogg-opus,amr]
下一步处理：将 ASR voice-format 改为腾讯云支持的 ogg-opus，并补齐小智裸 Opus 帧到 Ogg Opus/PCM 的服务端转换；或改用腾讯云实时语音识别接口处理裸 Opus 流
```

### 2026-06-16 真实 ASR 修复后公网全链路测试

```text
测试时间：2026-06-16 09:15:46 +08:00
部署版本 / Git commit：cf00744（本地未提交改动）；远程镜像 sha256:63f456d8b1846878efc1e300b210c3b6f1a6573e29a10270bab58eddb27f0365
服务器地址：203.195.202.54:8766
配置变更：远程 /opt/chatbot-service-java-runtime/chatbot-service.env 明确 TENCENT_CLOUD_ASR_VOICE_FORMAT=pcm；权限 600
配置备份：/opt/chatbot-service-java-runtime/backups/chatbot-service.env.20260616091440
WebSocket 路径：/xiaozhi/v1
固件设备 ID：real-asr-pcm-decode-test-2
Client ID：real-asr-pcm-decode-test-2
token 鉴权结果：当前远程未配置 XIAOZHI_WEBSOCKET_TOKEN，authRequired=false，连接通过
设备 hello / server hello：通过；server hello 返回 audio_params(format=opus, sample_rate=16000, channels=1, frame_duration=60)
ASR 识别文本：你好，小智，请回答，今天天气怎么样？
Hermes 回复摘要：Hermes 正常回复，说明“我是 Hermes Agent”，并提示需要城市才能查询天气
TTS 播放结果：收到 tts.start、tts.sentence_start、445 个二进制 TTS 音频帧、tts.stop
最终结论：公网 WebSocket 软件链路通过；小智裸 Opus 上行已由服务端解码为 16k PCM 并成功调用腾讯云一句话识别，随后 Hermes 与腾讯云 TTS 全链路完成
失败现象与日志位置：修复前曾出现 input sample rate not supported；修复后 device_gateway 日志显示 xiaozhi turn completed, audioFrames=65, ttsFrames=445, asrMillis=445, hermesMillis=3617, ttsMillis=8867
下一步处理：硬件到位后用真实小智设备复测麦克风采集质量与扬声器播放效果；软件公网链路已通过
```
