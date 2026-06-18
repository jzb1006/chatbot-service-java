# 小智完整 TTS 播放链路与 Hermes 边界实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将当前精简的小智 TTS 网关升级为接近参考项目完整运行时体验的播放链路，同时明确所有 AI 相关能力只交给 Hermes Agent 处理。

**架构：** Java 服务只承担小智协议、WebSocket 会话、ASR/TTS Provider、音频编码、播放调度、设备通知和 Hermes 调用边界。LLM 推理、Agent 工具编排、意图理解、记忆、多模态理解、情绪策略和业务智能全部由 Hermes Agent 返回文本或结构化事件，Java 服务不得内置 AI 决策逻辑。

**技术栈：** Java 21、Spring Boot 3.4、Spring WebSocket、Maven 多模块、JUnit 5、AssertJ、Jackson、Concentus Opus、腾讯云 TTS、Hermes Responses API。

---

## 已明确的决策

- 当前项目根目录：`/Users/jiangzhibin/workspace/chatbot-service-java`。
- 参考项目根目录：`/Users/jiangzhibin/workspace/xiaozhi-esp32-server-java`，只作为行为参考，不直接搬运后台、数据库、Redis 或完整角色管理系统。
- 小智 WebSocket 协议入口保持当前 `/xiaozhi/v1`，兼容已有 `/ws/xiaozhi/v1`。
- AI 能力统一由 Hermes Agent 承担。Java 侧只透传 `deviceId`、`conversationId`、用户文本、设备事件和必要上下文。
- TTS 第一阶段以腾讯云为真实 Provider，保留 `fake` Provider 用于测试；其他 Provider 只预留扩展边界，不在第一轮实现。
- 当前已存在 `XiaozhiTtsPlayback`、`TextToSpeechClient`、`TencentCloudTextToSpeechClient`、`HermesClient.streamChat(...)`，后续开发应优先在这些边界上演进，避免大规模重写。
- TTS 参数对象迁移必须保持兼容：当前测试桩和既有调用方仍可只实现 `synthesize(String, VoiceId)`，新增 `TextToSpeechOptions` 先通过默认方法接入。
- 设备级默认音色的唯一配置来源是现有 `chatbot.voice.default-voice-id`；`chatbot.voice.tts.tencent.voice-type` 只作为腾讯云 Provider 在收到 `VoiceId("default")` 时的 fallback，不作为设备配置来源。
- Hermes `device_action` 结构化事件是独立集成任务，不阻塞 TTS runtime、播放队列、通知播放和真机 TTS 主线验收。
- 不主动执行 `git commit`、`git push` 或分支操作，除非用户明确要求。

## 开发入口门禁

进入开发阶段前必须满足：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl "chatbot-speech-api,chatbot-hermes-adapter,chatbot-voice-gateway" -am test
```

预期：`BUILD SUCCESS`。当前基线已验证通过：`chatbot-common`、`chatbot-hermes-adapter`、`chatbot-speech-api`、`chatbot-voice-gateway` 共 87 个测试通过。

开发执行顺序：

```text
先完成任务 4-9，形成可验证的完整 TTS 播放主线。
任务 10 作为 Hermes 结构化事件独立集成任务，只在 Hermes Agent 明确支持 device_action 后启用完整实现。
任务 11-13 可在任务 4-9 完成后先执行，用于验证 TTS 主线。
```

## 整体规划概述

### 项目目标

把 TTS 从“每句同步合成并直接发送”的精简实现，升级为“可配置音色、可排队、可打断、可观测、协议稳定”的完整播放运行时。完成后，设备端应稳定看到 `tts start`、逐句 `sentence_start`、连续 Opus 二进制帧、`tts stop`，并能在 Hermes 流式输出、设备打断、TTS 异常和设备通知场景下保持状态一致。

### 技术边界

Java 服务允许实现：

- 小智 WebSocket 协议和二进制音频帧编解码。
- ASR 音频输入和 TTS 音频输出。
- 播放队列、句间隔、帧发送节奏、取消和状态机。
- Hermes HTTP/SSE 调用适配。
- 设备事件、MCP bridge、提醒回调、播放通知。

Java 服务禁止实现：

- LLM prompt 编排、Agent planning、工具选择策略。
- 意图理解、语义分类、上下文记忆、知识库检索。
- 基于文本内容的智能情绪判断。
- 多轮对话智能状态和业务规则推理。

需要这些能力时，扩展 `HermesRequest` / `HermesResponse` 或 Hermes SSE 事件协议，让 Hermes Agent 返回结构化结果，Java 只做协议适配。

### 主要阶段

1. **研究阶段**：固化当前链路、参考项目差异和 Hermes 职责边界。
2. **构思阶段**：选择轻量播放运行时方案，避免照搬参考项目重型后台。
3. **计划阶段**：按接口、播放、配置、测试、验收拆分任务。
4. **执行阶段**：TDD 增量实现，每个任务保持可回滚。
5. **测试阶段**：单测、模块测试、WebSocket smoke、真机验证。
6. **优化阶段**：收敛重复逻辑、观测指标和异常处理。
7. **评审阶段**：代码审查、影响范围、文档归档。

## 范围边界

本计划覆盖：

- TTS Provider 边界调整。
- 播放调度器抽象。
- 设备级语音配置。
- Hermes AI 边界明确化。
- WebSocket 协议事件和音频帧验收。
- 测试与远程部署验证清单。

本计划不覆盖：

- 参考项目的管理后台、角色 CRUD、MySQL 表结构、Redis 发布订阅。
- 引入完整 Spring AI TTS 生态。
- 新增非腾讯云 TTS Provider 的真实实现。
- 修改小智 ESP32 固件仓库。
- 修改 Hermes Agent 内部实现；如需 Hermes 配合，只在计划中定义接口需求。

## 文件结构

- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
  - 职责：会话入口编排。后续应减少直接播放细节，把 TTS 播放委托给播放运行时。
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSession.java`
  - 职责：会话状态、协议版本、音频帧、播放句柄、会话标识。
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiTtsPlayback.java`
  - 职责：当前播放控制器。计划中逐步演进为更清晰的播放执行单元。
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/tts/XiaozhiTtsRuntime.java`
  - 职责：TTS 播放运行时入口，接收文本句子流并管理播放队列、开始、停止和取消。
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/tts/XiaozhiTtsRequest.java`
  - 职责：封装播放请求，包括 `sessionId`、`deviceId`、`conversationId`、`voiceId`、文本和通知类型。
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/tts/XiaozhiVoiceProfile.java`
  - 职责：设备级语音配置，第一阶段包含 `voiceId`、`speed`、`pitch`，TTS Provider 只使用已支持字段。
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/tts/XiaozhiVoiceProfileResolver.java`
  - 职责：根据设备解析语音配置。第一阶段从配置文件读取默认值，后续可替换为设备配置源。
- 修改：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TextToSpeechClient.java`
  - 职责：TTS Provider 接口。以兼容方式扩展参数对象，避免一次性打断既有调用方和测试桩。
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TextToSpeechOptions.java`
  - 职责：TTS 参数对象，包含 `VoiceId`、`speed`、`pitch`，第一阶段不强制所有 Provider 完整支持。
- 修改：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentCloudTextToSpeechClient.java`
  - 职责：腾讯云 TextToVoice 到 Opus 帧转换。支持从 `TextToSpeechOptions` 获取 voice type。
- 修改：`chatbot-hermes-adapter/src/main/java/com/jzb/chatbot/hermes/HermesRequest.java`
  - 职责：Hermes 请求边界。只增加上下文字段，不加入 Java 侧 AI 规则。
- 修改：`chatbot-hermes-adapter/src/main/java/com/jzb/chatbot/hermes/HermesResponse.java`
  - 职责：Hermes 响应边界。为后续结构化事件预留显式字段，但 Java 不解释 AI 语义。
- 修改：`chatbot-hermes-adapter/src/main/java/com/jzb/chatbot/hermes/HttpHermesClient.java`
  - 职责：Responses API 适配。只负责序列化请求、解析文本和结构化事件。
- 修改：`chatbot-bootstrap/src/main/resources/application.yml`
  - 职责：默认语音配置、TTS 默认 speed/pitch 和 Hermes 边界配置。音色默认值复用现有 `chatbot.voice.default-voice-id`。
- 修改：`deploy/chatbot-service.env.example`
  - 职责：部署环境变量示例。
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiTtsRuntimeTest.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceProfileResolverTest.java`
- 测试：`chatbot-speech-api/src/test/java/com/jzb/chatbot/speech/TencentCloudTextToSpeechClientTest.java`
- 测试：`chatbot-hermes-adapter/src/test/java/com/jzb/chatbot/hermes/HttpHermesClientTest.java`

## 研究阶段任务

### 任务 1：建立当前链路快照

**文件：**
- 修改：`docs/superpowers/plans/2026-06-17-xiaozhi-complete-tts-hermes-boundary.md`

- [ ] **步骤 1：记录当前 TTS 链路**

确认并记录以下事实：

```text
Hermes SSE chunk
  -> XiaozhiHermesStreamTextExtractor
  -> XiaozhiSentenceSegmenter
  -> TextToSpeechClient.synthesize(sentence, VoiceId("default"))
  -> XiaozhiTtsPlayback.playSentence(...)
  -> XiaozhiMessageCodec.encodeAudioFrame(...)
  -> WebSocket BinaryMessage
```

- [ ] **步骤 2：记录参考项目可借鉴能力**

确认只借鉴以下行为：

```text
TTS Provider 工厂
语音参数 voice/speed/pitch
播放队列
虚拟线程或独立发送线程
前 2 帧 burst prebuffer
句间隔
stop 延迟
打断清理
TTS stop 触发状态收敛
```

- [ ] **步骤 3：记录明确不借鉴内容**

确认不借鉴以下内容：

```text
MySQL 角色表
管理后台角色 CRUD
Redis 配置订阅
Java 内置 Persona/Agent 智能编排
Java 内置工具选择策略
Java 内置情绪语义判断
```

## 构思阶段任务

### 任务 2：确定播放运行时方案

**推荐方案：轻量播放运行时。**

在当前 `XiaozhiVoiceSessionService` 旁新增 `XiaozhiTtsRuntime`，把播放队列、句间隔、取消、状态收敛迁出去；保留现有 `TextToSpeechClient` 和 `XiaozhiMessageCodec`。这样能获得参考项目核心播放体验，又不引入参考项目的重型领域模型。

备选方案：

- **方案 A：直接增强 `XiaozhiTtsPlayback`**
  - 优点：改动最小。
  - 缺点：`XiaozhiVoiceSessionService` 继续承担过多职责，后续通知、队列、打断会继续膨胀。
- **方案 B：新增 `XiaozhiTtsRuntime`**
  - 优点：职责清晰，便于测试和扩展，符合 SRP。
  - 缺点：需要调整现有测试和注入方式。
- **方案 C：照搬参考项目 `Synthesizer` / `Player` 结构**
  - 优点：功能完整。
  - 缺点：引入文件式 TTS、Reactor、角色模型和后台假设，超出当前项目需要。

选择：方案 B。

验收标准：

```text
XiaozhiVoiceSessionService 不直接管理帧发送节奏。
XiaozhiTtsRuntime 可以独立单测播放队列、取消、stop 只发送一次。
Hermes 只提供文本或结构化事件，不被 TTS runtime 依赖。
```

## 计划阶段任务

### 任务 3：定义 Hermes AI 边界契约

**文件：**
- 修改：`chatbot-hermes-adapter/src/main/java/com/jzb/chatbot/hermes/HermesRequest.java`
- 修改：`chatbot-hermes-adapter/src/main/java/com/jzb/chatbot/hermes/HermesResponse.java`
- 修改：`chatbot-hermes-adapter/src/main/java/com/jzb/chatbot/hermes/HttpHermesClient.java`
- 测试：`chatbot-hermes-adapter/src/test/java/com/jzb/chatbot/hermes/HttpHermesClientTest.java`

- [ ] **步骤 1：编写边界说明测试**

在 `HttpHermesClientTest` 增加测试，验证 Java 只把上下文发给 Hermes，不在本地生成 AI 决策字段：

```java
@Test
void shouldSendDeviceAndConversationContextToHermesWithoutLocalAiDecision() throws Exception {
    var receivedBody = new AtomicReference<String>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/responses", exchange -> {
        receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        var body = """
                {"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"已处理"}]}]}
                """;
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    });
    server.start();
    var client = new HttpHermesClient(new ObjectMapper());

    client.chat(
            new HermesRequest(new DeviceId("device-1"), new ConversationId("conv-1"), "打开客厅灯"),
            new HermesClientConfig(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/",
                    "hermes-agent",
                    "secret-key",
                    Duration.ofSeconds(3),
                    "owner"
            )
    );

    assertThat(receivedBody.get()).contains("\"input\":\"打开客厅灯\"");
    assertThat(receivedBody.get()).contains("\"conversation\":\"conv-1\"");
    assertThat(receivedBody.get()).doesNotContain("intent");
    assertThat(receivedBody.get()).doesNotContain("localDecision");
}
```

- [ ] **步骤 2：运行测试确认当前行为**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-hermes-adapter -Dtest=HttpHermesClientTest test
```

预期：PASS。失败时只允许修正请求序列化或测试断言，不允许在 Java 侧新增 `intent`、`localDecision`、`emotion_by_text` 这类 AI 决策字段。

- [ ] **步骤 3：扩展请求上下文时保持透传语义**

如果需要补充设备上下文，只允许增加明确透传字段：

```java
public record HermesRequest(
        DeviceId deviceId,
        ConversationId conversationId,
        String text,
        Map<String, Object> context
) {
    public HermesRequest(DeviceId deviceId, ConversationId conversationId, String text) {
        this(deviceId, conversationId, text, Map.of());
    }
}
```

`context` 中允许放：

```text
device_id
protocol_version
audio_format
listen_mode
client_id
```

`context` 中禁止放由 Java 判断出的：

```text
intent
tool_plan
memory
semantic_tags
emotion_by_text
```

### 任务 4：引入 TTS 参数对象

**文件：**
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TextToSpeechOptions.java`
- 修改：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TextToSpeechClient.java`
- 修改：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentCloudTextToSpeechClient.java`
- 修改：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/FakeTextToSpeechClient.java`
- 测试：`chatbot-speech-api/src/test/java/com/jzb/chatbot/speech/TencentCloudTextToSpeechClientTest.java`
- 测试：`chatbot-speech-api/src/test/java/com/jzb/chatbot/speech/FakeSpeechClientTest.java`

- [ ] **步骤 1：编写 options 默认值和兼容接口测试**

在 `TencentCloudTextToSpeechClientTest` 或新增 `TextToSpeechOptionsTest` 中增加：

```java
@Test
void shouldUseDefaultVoiceOptions() {
    var options = TextToSpeechOptions.defaults();

    assertThat(options.voiceId()).isEqualTo(new VoiceId("default"));
    assertThat(options.speed()).isEqualTo(1.0);
    assertThat(options.pitch()).isEqualTo(1.0);
}
```

在 `FakeSpeechClientTest` 增加旧调用兼容验证：

```java
@Test
void shouldKeepVoiceIdSynthesizeCallCompatible() {
    var client = new FakeTextToSpeechClient();

    var frames = client.synthesize("pong", new VoiceId("default"));

    assertThat(frames).isNotEmpty();
}
```

- [ ] **步骤 2：创建最小参数对象**

```java
public record TextToSpeechOptions(VoiceId voiceId, double speed, double pitch) {

    public TextToSpeechOptions {
        if (voiceId == null) {
            voiceId = new VoiceId("default");
        }
        if (speed <= 0) {
            throw new IllegalArgumentException("speed must be positive");
        }
        if (pitch <= 0) {
            throw new IllegalArgumentException("pitch must be positive");
        }
    }

    public static TextToSpeechOptions defaults() {
        return new TextToSpeechOptions(new VoiceId("default"), 1.0, 1.0);
    }
}
```

- [ ] **步骤 3：扩展接口并保持旧实现兼容**

当前项目中 `XiaozhiVoiceSessionServiceTest` 有多个内部测试桩只实现 `synthesize(String, VoiceId)`。第一轮接口迁移必须保留旧抽象方法，并把新 options 方法做成默认方法，避免一次性打断所有测试桩：

```java
List<ByteBuffer> synthesize(String text, VoiceId voiceId);

default List<ByteBuffer> synthesize(String text, TextToSpeechOptions options) {
    var effectiveOptions = options == null ? TextToSpeechOptions.defaults() : options;
    return synthesize(text, effectiveOptions.voiceId());
}
```

验收：

```text
TencentCloudTextToSpeechClient 和 FakeTextToSpeechClient 可继续只实现 VoiceId 版本。
现有 RecordingTextToSpeechClient / BlockingTextToSpeechClient / EmptyTextToSpeechClient / FailingTextToSpeechClient 测试桩不需要在任务 4 修改。
后续 XiaozhiTtsRuntime 调用 options 版本时，旧 Provider 仍能工作。
```

- [ ] **步骤 4：更新腾讯云实现**

不强制 `TencentCloudTextToSpeechClient` 在任务 4 立即改为重写 options 版本。若需要测试 voice id 传递，优先继续验证旧 `synthesize(String, VoiceId)` 路径。

第一阶段不把 `speed`、`pitch` 映射到腾讯云请求，避免引入未经验证参数。

- [ ] **步骤 5：运行 speech 模块测试**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-speech-api -am test
```

预期：PASS。

### 任务 5：引入设备语音配置解析

**文件：**
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/tts/XiaozhiVoiceProfile.java`
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/tts/XiaozhiVoiceProfileResolver.java`
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeans.java`
- 修改：`chatbot-bootstrap/src/main/resources/application.yml`
- 修改：`deploy/chatbot-service.env.example`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceProfileResolverTest.java`

- [ ] **步骤 1：编写默认 profile 测试**

```java
@Test
void shouldResolveDefaultVoiceProfile() {
    var resolver = new XiaozhiVoiceProfileResolver(new VoiceId("default"), 1.0, 1.0);

    var profile = resolver.resolve("device-1");

    assertThat(profile.voiceId()).isEqualTo(new VoiceId("default"));
    assertThat(profile.speed()).isEqualTo(1.0);
    assertThat(profile.pitch()).isEqualTo(1.0);
}
```

- [ ] **步骤 2：实现最小配置解析**

```java
public record XiaozhiVoiceProfile(VoiceId voiceId, double speed, double pitch) {

    public TextToSpeechOptions toTtsOptions() {
        return new TextToSpeechOptions(voiceId, speed, pitch);
    }
}
```

```java
public class XiaozhiVoiceProfileResolver {

    private final XiaozhiVoiceProfile defaultProfile;

    public XiaozhiVoiceProfileResolver(VoiceId defaultVoiceId, double defaultSpeed, double defaultPitch) {
        this.defaultProfile = new XiaozhiVoiceProfile(defaultVoiceId, defaultSpeed, defaultPitch);
    }

    public XiaozhiVoiceProfile resolve(String deviceId) {
        return defaultProfile;
    }
}
```

- [ ] **步骤 3：接入现有默认音色配置并只新增 speed/pitch**

当前 `application.yml` 已有：

```yaml
chatbot:
  voice:
    default-voice-id: default
```

保持 `chatbot.voice.default-voice-id` 作为设备默认音色的唯一来源，不新增 `chatbot.voice.tts.default-voice-id`。

在 `application.yml` 的 `chatbot.voice.tts` 下只增加：

```yaml
chatbot:
  voice:
    tts:
      default-speed: ${CHATBOT_VOICE_TTS_DEFAULT_SPEED:1.0}
      default-pitch: ${CHATBOT_VOICE_TTS_DEFAULT_PITCH:1.0}
```

如果需要让部署环境覆盖默认设备音色，修改现有配置为：

```yaml
chatbot:
  voice:
    default-voice-id: ${CHATBOT_VOICE_DEFAULT_VOICE_ID:default}
```

在 `deploy/chatbot-service.env.example` 增加：

```env
CHATBOT_VOICE_DEFAULT_VOICE_ID=default
CHATBOT_VOICE_TTS_DEFAULT_SPEED=1.0
CHATBOT_VOICE_TTS_DEFAULT_PITCH=1.0
```

保留现有 `TENCENT_CLOUD_TTS_VOICE_TYPE=101001`，它只用于腾讯云 Provider 在请求音色为 `default` 时的真实音色 fallback。

- [ ] **步骤 4：注册 resolver Bean**

在 `XiaozhiVoiceGatewayBeans` 中新增：

```java
@Bean
XiaozhiVoiceProfileResolver xiaozhiVoiceProfileResolver(
        @Value("${chatbot.voice.default-voice-id:default}") String defaultVoiceId,
        @Value("${chatbot.voice.tts.default-speed:1.0}") double defaultSpeed,
        @Value("${chatbot.voice.tts.default-pitch:1.0}") double defaultPitch
) {
    return new XiaozhiVoiceProfileResolver(new VoiceId(defaultVoiceId), defaultSpeed, defaultPitch);
}
```

- [ ] **步骤 5：运行 voice gateway Bean 测试**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -Dtest=XiaozhiVoiceGatewayBeansTest,XiaozhiVoiceProfileResolverTest test
```

预期：PASS。

## 执行阶段任务

### 任务 6：新增轻量 TTS 播放运行时

**文件：**
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/tts/XiaozhiTtsRuntime.java`
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/tts/XiaozhiTtsRequest.java`
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiTtsPlayback.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiTtsRuntimeTest.java`

- [ ] **步骤 1：定义 request 最小字段**

`XiaozhiTtsRequest` 第一阶段只承载播放运行时真实需要的字段：

```java
public record XiaozhiTtsRequest(
        WebSocketSession webSocketSession,
        XiaozhiVoiceSession voiceSession,
        List<String> sentences,
        TextToSpeechOptions options
) {
    public XiaozhiTtsRequest {
        if (webSocketSession == null) {
            throw new IllegalArgumentException("webSocketSession must not be null");
        }
        if (voiceSession == null) {
            throw new IllegalArgumentException("voiceSession must not be null");
        }
        sentences = sentences == null ? List.of() : List.copyOf(sentences);
        options = options == null ? TextToSpeechOptions.defaults() : options;
    }
}
```

说明：

```text
deviceId、conversationId 从 XiaozhiVoiceSession 读取，不在 request 中重复保存。
notification / conversation 等播放来源先不做枚举，日志需要时由调用方追加上下文。
```

- [ ] **步骤 2：编写 stop 只发送一次测试**

```java
@Test
void shouldSendTtsStopOnlyOnceWhenPlaybackFinishes() {
    var runtime = newRuntimeWithFakeTts();
    var session = openSession();
    var voiceSession = new XiaozhiVoiceSession(session.getId());

    runtime.speak(new XiaozhiTtsRequest(session, voiceSession, List.of("你好"), TextToSpeechOptions.defaults()));

    assertThat(textPayloads(session))
            .filteredOn(payload -> payload.contains("\"type\":\"tts\"") && payload.contains("\"state\":\"stop\""))
            .hasSize(1);
}
```

- [ ] **步骤 3：编写取消测试**

```java
@Test
void shouldCancelQueuedSentencesAfterAbort() throws Exception {
    var ttsClient = new BlockingTextToSpeechClient();
    var runtime = newRuntime(ttsClient);
    var session = openSession();
    var voiceSession = new XiaozhiVoiceSession(session.getId());

    var thread = Thread.startVirtualThread(() -> runtime.speak(new XiaozhiTtsRequest(
            session,
            voiceSession,
            List.of("第一句内容很完整。", "第二句内容也完整。"),
            TextToSpeechOptions.defaults()
    )));

    assertThat(ttsClient.awaitFirstCall()).isTrue();
    runtime.cancel(session.getId());
    ttsClient.releaseFirstCall();
    thread.join(2_000L);

    assertThat(ttsClient.texts()).containsExactly("第一句内容很完整。");
}
```

- [ ] **步骤 4：实现运行时入口**

核心职责：

```text
speak(request)
  -> send llm emotion neutral 或 Hermes 返回的显式 emotion
  -> send tts start
  -> for each sentence
       -> synthesize(sentence, options)
       -> playback.playSentence(sentence, frames)
  -> send tts stop
cancel(sessionId)
  -> cancel active playback
```

第一阶段情绪只允许两种来源：

```text
固定 neutral
Hermes 显式返回的结构化 emotion 字段
```

禁止 Java 从文本内容推断情绪。

- [ ] **步骤 5：运行 runtime 测试**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -Dtest=XiaozhiTtsRuntimeTest test
```

预期：PASS。

### 任务 7：让会话服务委托播放运行时

**文件：**
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSession.java`
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeans.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`

- [ ] **步骤 1：保留现有行为测试**

运行当前已有测试作为重构保护：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -Dtest=XiaozhiVoiceSessionServiceTest test
```

预期：重构前 PASS。

- [ ] **步骤 2：替换直接调用**

把 `XiaozhiVoiceSessionService` 中的以下职责迁到 `XiaozhiTtsRuntime`：

```text
创建 XiaozhiTtsPlayback
发送 tts start
逐句调用 textToSpeechClient
发送 tts stop
统计 ttsFrames
取消当前播放
```

`XiaozhiVoiceSessionService` 调用 runtime 前先解析设备语音配置：

```java
var profile = voiceProfileResolver.resolve(voiceSession.deviceId());
ttsRuntime.speak(new XiaozhiTtsRequest(
        webSocketSession,
        voiceSession,
        sentences,
        profile.toTtsOptions()
));
```

`XiaozhiVoiceSessionService` 保留：

```text
WebSocket 生命周期
hello / listen / abort / mcp 事件处理
ASR 调用
Hermes 调用
Hermes 文本分句
会话状态切换
错误事件下发
```

- [ ] **步骤 3：确保 Hermes 仍是 AI 唯一入口**

在 `streamChatAndSpeak(...)` 中保持如下边界：

```text
userText -> HermesRequest -> Hermes streamChat
Hermes chunks -> text extractor -> sentence segmenter
sentences -> TTS runtime
```

不得新增本地关键字意图识别。当前已有本地提醒解析属于临时能力，后续迁移见任务 10。

- [ ] **步骤 4：运行会话测试**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -Dtest=XiaozhiVoiceSessionServiceTest test
```

预期：PASS。

### 任务 8：补齐播放队列、句间隔和 stop 延迟

**文件：**
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/tts/XiaozhiTtsRuntime.java`
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiTtsPlayback.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiTtsRuntimeTest.java`

- [ ] **步骤 1：编写多句顺序测试**

```java
@Test
void shouldPlaySentencesInOrder() {
    var ttsClient = new RecordingTextToSpeechClient();
    var runtime = newRuntime(ttsClient);
    var session = openSession();

    runtime.speak(new XiaozhiTtsRequest(
            session,
            new XiaozhiVoiceSession(session.getId()),
            List.of("第一句内容。", "第二句内容。"),
            TextToSpeechOptions.defaults()
    ));

    assertThat(ttsClient.texts()).containsExactly("第一句内容。", "第二句内容。");
    assertThat(sentenceStartTexts(session)).containsExactly("第一句内容。", "第二句内容。");
}
```

- [ ] **步骤 2：增加句间隔常量**

在播放运行时或 `XiaozhiTtsPlayback` 中定义：

```java
private static final long SENTENCE_GAP_NS = 60_000_000L * 5;
private static final long STOP_DELAY_MS = 120L;
```

执行要求：

```text
句间隔只影响发送节奏，不发送空音频帧。
stop 延迟只在自然播放完成后执行。
abort 取消时不等待 stop 延迟。
```

- [ ] **步骤 3：运行播放测试**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -Dtest=XiaozhiTtsRuntimeTest test
```

预期：PASS。

### 任务 9：支持设备通知与会话忙碌策略

**文件：**
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/tts/XiaozhiTtsRuntime.java`
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/tts/XiaozhiVoiceProfileResolver.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`

- [ ] **步骤 1：编写忙碌跳过测试**

```java
@Test
void shouldSkipNotificationWhenSessionIsSpeaking() {
    var session = openSession(service);
    service.getSession(session.getId()).markSpeaking();

    var sent = service.notifyDevice("device-1", "提醒时间到了");

    assertThat(sent).isFalse();
}
```

- [ ] **步骤 2：编写空闲通知测试**

```java
@Test
void shouldPlayNotificationWhenSessionIsIdle() {
    var session = openSession(service);

    var sent = service.notifyDevice("device-1", "提醒时间到了");

    assertThat(sent).isTrue();
    assertThat(textPayloads(session))
            .anySatisfy(payload -> assertThat(payload).contains("\"state\":\"sentence_start\"", "提醒时间到了"));
}
```

- [ ] **步骤 3：实现通知委托**

`notifyDevice(...)` 不直接创建播放对象，统一调用 `XiaozhiTtsRuntime.speak(...)`：

```java
var profile = voiceProfileResolver.resolve(voiceSession.deviceId());
return ttsRuntime.speak(new XiaozhiTtsRequest(
        webSocketSession,
        voiceSession,
        List.of(text),
        profile.toTtsOptions()
));
```

- [ ] **步骤 4：运行通知测试**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -Dtest=XiaozhiVoiceSessionServiceTest test
```

预期：PASS。

### 任务 10：Hermes 结构化事件独立集成

**文件：**
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/reminder/XiaozhiReminderIntent.java`
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
- 修改：`chatbot-hermes-adapter/src/main/java/com/jzb/chatbot/hermes/HermesResponse.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`
- 测试：`chatbot-hermes-adapter/src/test/java/com/jzb/chatbot/hermes/HttpHermesClientTest.java`

**执行门禁：**

任务 10 不属于 TTS 主线进入开发阶段的阻塞项。只有在 Hermes Agent 已明确能通过 Responses API `output` 返回 `device_action` 对象时，才执行步骤 3-5；否则只执行步骤 1，把当前本地提醒解析标记为兼容层。

- [ ] **步骤 1：标记当前本地提醒解析为待迁移兼容层**

保留 `XiaozhiReminderIntent.parse(...)`，但只作为兼容分支，并在类注释中说明：

```text
临时兼容：提醒意图最终应由 Hermes Agent 通过结构化事件返回。
Java 不继续扩展自然语言规则。
```

- [ ] **步骤 2：定义 Hermes 结构化事件**

Hermes 可返回：

```json
{
  "type": "device_action",
  "name": "schedule_reminder",
  "arguments": {
    "delay_seconds": 60,
    "message": "喝水",
    "confirmation_text": "一分钟后提醒你喝水"
  }
}
```

Java 只做字段校验和事件发布，不做自然语言理解。

- [ ] **步骤 3：增加结构化事件解析测试**

在 `HttpHermesClientTest` 增加对 `output` 中结构化事件的解析测试：

```java
@Test
void shouldParseHermesDeviceActionAsStructuredEvent() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/responses", exchange -> {
        var body = """
                {
                  "output": [
                    {
                      "type": "message",
                      "role": "assistant",
                      "content": [
                        {"type": "output_text", "text": "一分钟后提醒你喝水"}
                      ]
                    },
                    {
                      "type": "device_action",
                      "name": "schedule_reminder",
                      "arguments": {
                        "delay_seconds": 60,
                        "message": "喝水",
                        "confirmation_text": "一分钟后提醒你喝水"
                      }
                    }
                  ]
                }
                """;
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    });
    server.start();

    var response = new HttpHermesClient(new ObjectMapper()).chat(
            new HermesRequest(new DeviceId("device-1"), new ConversationId("conv-1"), "一分钟后提醒我喝水"),
            new HermesClientConfig(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/",
                    "hermes-agent",
                    "secret-key",
                    Duration.ofSeconds(3),
                    "owner"
            )
    );

    assertThat(response.text()).isEqualTo("一分钟后提醒你喝水");
    assertThat(response.events()).singleElement().satisfies(event -> {
        assertThat(event.type()).isEqualTo("device_action");
        assertThat(event.name()).isEqualTo("schedule_reminder");
        assertThat(event.arguments().get("delay_seconds")).isEqualTo(60);
    });
}
```

验收条件：

```text
assistant output_text 仍能提取为播报文本。
device_action 原样进入 HermesResponse.events()。
Java 不根据文本补推 action。
```

- [ ] **步骤 4：在会话服务消费 Hermes 事件**

当 Hermes 返回 `schedule_reminder` 事件时：

```text
发布 XiaozhiReminderRequestedEvent
播报 Hermes 返回的 confirmation_text
```

当 Hermes 没有返回结构化事件时：

```text
按普通对话播报文本
```

- [ ] **步骤 5：运行提醒相关测试**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway,chatbot-hermes-adapter -am test
```

预期：PASS。

## 测试阶段任务

### 任务 11：模块级回归测试

**文件：**
- 测试：`chatbot-speech-api/src/test/java/com/jzb/chatbot/speech/TencentCloudTextToSpeechClientTest.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiTtsRuntimeTest.java`
- 测试：`chatbot-hermes-adapter/src/test/java/com/jzb/chatbot/hermes/HttpHermesClientTest.java`

- [ ] **步骤 1：运行 speech-api 测试**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-speech-api -am test
```

预期：PASS。

- [ ] **步骤 2：运行 hermes-adapter 测试**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-hermes-adapter -am test
```

预期：PASS。

- [ ] **步骤 3：运行 voice-gateway 测试**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am test
```

预期：PASS。

- [ ] **步骤 4：运行全量测试**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn test
```

预期：PASS。

### 任务 12：WebSocket smoke 验证

**文件：**
- 修改：`scripts/xiaozhi_ws_smoke.py`
- 修改：`scripts/hermes_xiaozhi_mcp_smoke.py`

- [ ] **步骤 1：增加 TTS 帧统计输出**

`xiaozhi_ws_smoke.py` 输出以下字段：

```text
hello_received=true
tts_start_count=1
tts_sentence_start_count>=1
binary_frame_count>=1
tts_stop_count=1
error_count=0
```

- [ ] **步骤 2：本地 smoke**

启动服务后运行：

```bash
python3 scripts/xiaozhi_ws_smoke.py --url ws://127.0.0.1:8766/xiaozhi/v1 --device-id local-smoke-device
```

预期：

```text
hello_received=true
binary_frame_count 大于 0
error_count=0
```

- [ ] **步骤 3：Hermes 联动 smoke**

```bash
python3 scripts/hermes_xiaozhi_mcp_smoke.py --url ws://127.0.0.1:8766/xiaozhi/v1 --device-id local-hermes-smoke-device
```

预期：

```text
Hermes SSE 有输出
TTS 正常 start / sentence_start / binary / stop
Java 日志不出现本地 AI intent 判断
```

### 任务 13：真机验收

**文件：**
- 修改：`docs/superpowers/plans/2026-06-16-xiaozhi-firmware-backend-task-checklist.md`

- [ ] **步骤 1：部署前检查**

确认：

```text
TENCENT_CLOUD_SECRET_ID 已配置
TENCENT_CLOUD_SECRET_KEY 已配置
CHATBOT_VOICE_DEFAULT_VOICE_ID 已配置或保持默认 default
TENCENT_CLOUD_TTS_VOICE_TYPE 已配置为 default 音色 fallback
Hermes 容器健康
Java gateway 健康
OTA 返回 ws://203.195.202.54:8766/xiaozhi/v1
```

- [ ] **步骤 2：真机首轮对话**

验收：

```text
设备能完成 hello
设备能发送 listen start / audio / listen stop
服务端能返回 stt
Hermes 能返回文本
设备能播放 TTS
服务端日志包含 ttsFrames > 0
```

- [ ] **步骤 3：真机打断**

验收：

```text
播放中发送 abort
服务端只发送一次 tts stop
后续句子不继续合成
session 回到 IDLE 或 LISTENING
下一轮对话可继续
```

- [ ] **步骤 4：真机通知**

验收：

```text
设备空闲时 notifyDevice 可播报
设备忙碌时 notifyDevice 返回 false
日志能区分 skipped 和 sent
```

## 优化阶段任务

### 任务 14：收敛重复逻辑和观测指标

**文件：**
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/tts/XiaozhiTtsRuntime.java`
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiServerEventFactory.java`

- [ ] **步骤 1：消除重复 stop 发送逻辑**

验收：

```text
只有 TTS runtime 或 playback 中一个位置负责 stop 幂等。
XiaozhiVoiceSessionService 不直接拼接 tts stop。
错误路径仍能发 stop + error。
```

- [ ] **步骤 2：统一播放日志字段**

每轮完成日志包含：

```text
sessionId
deviceId
conversationId
sentenceCount
ttsFrames
asrMillis
hermesMillis
ttsMillis
cancelled
```

- [ ] **步骤 3：检查 YAGNI**

移除第一阶段未使用的重型抽象：

```text
数据库配置源
多 Provider 工厂真实实现
音频文件落盘
Spring AI TTS Adapter
Redis 配置订阅
```

## 评审阶段任务

### 任务 15：代码评审与影响范围

**文件：**
- 修改：`docs/superpowers/plans/2026-06-17-xiaozhi-complete-tts-hermes-boundary.md`

- [x] **步骤 1：运行 Java 审查**

适用 `java-reviewer`。重点检查：

```text
WebSocket 并发状态
播放取消竞态
Thread.sleep 中断处理
TTS stop 幂等
Hermes stream 关闭
ByteBuffer 复用安全
异常路径是否恢复 session 状态
```

- [x] **步骤 2：生成影响范围**

适用 `/impact-scope` 时，影响范围至少包含：

```text
小智 WebSocket 对话播放链路
设备通知播放链路
Hermes 响应解析边界
腾讯云 TTS 调用参数
部署环境变量
```

- [x] **步骤 3：更新完成记录**

在本文末尾追加：

```markdown
## 完成记录

- 实施日期：
- 验证命令：
- 真机设备：
- 远程环境：
- 已知风险：
```

## 完成记录

- 实施日期：2026-06-18 08:25:13 CST
- 验证命令：
  - `/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl "chatbot-voice-gateway" -am -Dtest=XiaozhiVoiceSessionServiceTest#shouldSuppressOldTurnTtsFailureAfterNewListenStarts -Dsurefire.failIfNoSpecifiedTests=false test`：1 个测试通过，验证旧 turn TTS 失败不会向新 `LISTENING` 回合发送旧 `tts_failed`。
  - `/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl "chatbot-voice-gateway" -am -Dtest=XiaozhiVoiceSessionServiceTest,XiaozhiTtsRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false test`：72 个测试通过。
  - `/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl "chatbot-voice-gateway" -am test`：143 个测试通过。
  - `git diff --check`：无 whitespace 错误。
- 真机设备：未执行真机首轮对话、打断、通知播放验收；仍需现场用小智设备验证 `tts start`、`sentence_start`、Opus 二进制帧、`tts stop`、打断和提醒通知。
- 远程环境：
  - 本轮健康检查：`http://203.195.202.54:8766/actuator/health` 返回 `{"status":"UP"}`。
  - 本轮只读 GET `http://203.195.202.54:8766/api/ota/check` 返回 405，说明该 OTA 检查入口不接受 GET；任务 13 已记录 OTA WebSocket 为 `ws://203.195.202.54:8766/xiaozhi/v1`。
  - 任务 13 远程检查记录：`device_gateway`、`hermes` 容器运行中；`TENCENT_CLOUD_SECRET_ID=SET`、`TENCENT_CLOUD_SECRET_KEY=SET`、`CHATBOT_VOICE_DEFAULT_VOICE_ID=MISSING`（应用使用 `default` 默认值）、`TENCENT_CLOUD_TTS_VOICE_TYPE=SET`。
- 已知风险：
  - Hermes SSE stream 当前使用阻塞读取；`abort`、`close`、`listen.start`、`session.new`、`session.clear` 只能设置取消状态和停止 TTS，不能主动关闭正在阻塞的 Hermes SSE iterator。当前有 request timeout 兜底，chunk 返回后不会继续 TTS，但释放不够及时。
  - `speed`、`pitch` 当前已完成配置解析和 `TextToSpeechOptions` 边界传递，腾讯云 TTS Provider 第一阶段尚未映射为真实请求参数。
  - 真机首轮对话、打断和通知播放未在本轮完成，需要现场继续验收。
  - 为关闭旧 turn `tts_failed` 与新 `listen.start` 之间的竞态，普通 turn 的 TTS 失败事件发送被放入 `XiaozhiVoiceSession` 会话锁内；当前范围仅覆盖失败事件发送，后续不要扩大该锁内 I/O 范围。

`<!-- IMPACT-SCOPE:START -->`
## 影响范围

**改动性质**：新功能开发-混合（扩展现有方法 / 共享组件变更 / 接口契约变更）

**新增功能验收点**（本次新增功能要测什么）
- 小智 WebSocket 对话播放链路：`listen.start` + 二进制音频 + `listen.stop` 后，应完成 ASR -> Hermes streaming -> TTS runtime 播放，设备侧能收到 `tts start`、逐句 `sentence_start`、连续 Opus 二进制帧、`tts stop`。入口：`/xiaozhi/v1`、`/ws/xiaozhi/v1`。
- 对话打断链路：播放中收到 `abort`、新 `listen.start`、`session.new` 或 `session.clear` 时，旧播放应取消，`tts stop` 幂等发送，旧 turn 的 TTS 失败不得向新 `LISTENING` 回合发送旧 `tts_failed`。入口：小智 WebSocket 控制帧。
- 设备通知播放链路：空闲会话收到提醒通知时应通过同一 TTS runtime 播放；会话忙碌或播放失败时应返回失败且恢复 session 状态。入口：`XiaozhiVoiceSessionService.notifyDevice(...)`。
- Hermes 响应解析边界：Java 侧只解析 Hermes Responses API 输出文本与结构化事件边界，不新增本地 AI 决策；本地提醒解析仅作为兼容层。入口：`HttpHermesClient` / `XiaozhiVoiceSessionService`。
- 腾讯云 TTS 调用参数：默认音色继续由 `chatbot.voice.default-voice-id` 统一解析，新增 `speed`、`pitch` 配置边界和 `TextToSpeechOptions` 兼容入口。入口：`TextToSpeechClient` / `TencentCloudTextToSpeechClient`。
- 部署环境变量：部署示例新增 TTS 默认 speed/pitch 与腾讯云 TTS voice type 配置项，需在测试环境确认真实 env 与应用默认值一致。入口：`deploy/chatbot-service.env.example`、`application.yml`。

**直接影响场景**（老功能回归）
- 小智 WebSocket 首轮对话、连续多轮对话、会话新建/清空、wake word abort。入口：`/xiaozhi/v1`、`/ws/xiaozhi/v1`。
- 小智设备提醒通知播放与忙碌跳过策略。入口：`notifyDevice(...)`。
- Hermes SSE 文本分句播放、Hermes 异常、Hermes 取消后的状态恢复。入口：`HermesClient.streamChat(...)`。
- 腾讯云 TTS 与 fake TTS Provider 的兼容调用。入口：`TextToSpeechClient.synthesize(...)`。
- 远程部署配置加载：ASR/TTS provider、腾讯云密钥、默认音色、speed/pitch、音频格式。入口：Spring Boot 配置。

**关联影响场景**（建议回归）
- WebSocket 二进制音频帧处理：只在 `LISTENING` 态收音，非监听态帧应被忽略。原因：会话状态切换与音频缓存 drain 改为原子操作。
- TTS stop 幂等和发送异常日志。原因：stop 发送集中到 TTS runtime，异常路径需要保留 Throwable。
- MCP/提醒兼容层。原因：提醒事件仍由 Java 兼容解析触发，但播报改走 TTS runtime。
- smoke 脚本。原因：WebSocket smoke 增加 TTS/协议观测点，需与新播放链路保持一致。

**接口字段变更**（调用方需同步；无变更写"无"）
- WebSocket `/xiaozhi/v1`、`/ws/xiaozhi/v1`
  - 改语义 `error.code=tts_failed`：旧 turn 已被新 `listen.start` 取消后不再发送给新回合；当前 turn TTS 失败和通知 TTS 失败仍会发送。
  - 改语义 `tts start` / `sentence_start` / `stop`：对话播放和通知播放统一由 TTS runtime 管理，stop 保持幂等。
- Java TTS Provider 边界
  - 新增 `TextToSpeechOptions.voiceId/speed/pitch`：旧调用方仍可实现 `synthesize(String, VoiceId)`；新调用方可传 options。
  - 改语义 `VoiceId("default")`：设备默认音色来源统一为 `chatbot.voice.default-voice-id`，腾讯云 `voice-type` 仅作为 Provider fallback。
- 部署配置
  - 新增 `CHATBOT_VOICE_TTS_DEFAULT_SPEED`、`CHATBOT_VOICE_TTS_DEFAULT_PITCH`。
  - 新增/明确 `TENCENT_CLOUD_TTS_VOICE_TYPE`、`CHATBOT_VOICE_DEFAULT_VOICE_ID` 示例配置。

**数据与兼容性**
- 历史数据：无数据库、缓存、MQ、迁移脚本变更。
- 配置开关：无新增功能开关；保留 `fake` Provider 默认能力，腾讯云真实 Provider 依赖现有密钥配置。
- 兼容性：`TextToSpeechClient.synthesize(String, VoiceId)` 保留默认兼容路径；WebSocket URL 保持不变。

**明确不影响**
- 管理后台、角色 CRUD、MySQL 角色表、Redis 配置订阅：未引入相关实现。
- Hermes Agent 内部推理、工具编排、意图理解、记忆：Java 侧不实现 AI 决策，只保留协议适配边界。
- ESP32 固件仓库：本计划未修改固件代码；真机验收需另行执行。
`<!-- IMPACT-SCOPE:END -->`

## 需要进一步明确的问题

### 问题 1：设备级语音配置来源

**推荐方案：**

- 方案 A：第一阶段只使用全局默认配置。实现快、风险低，满足当前真机验证。
- 方案 B：用本地 JSON 配置按 `deviceId` 覆盖 voice/speed/pitch。无需数据库，但要设计配置热更新或重启生效策略。
- 方案 C：引入参考项目式角色配置和数据库。能力完整，但明显超出当前网关边界。

**当前计划选择：** 方案 A。第一阶段只使用 `chatbot.voice.default-voice-id`、`chatbot.voice.tts.default-speed`、`chatbot.voice.tts.default-pitch`，不引入设备级数据库或 JSON 覆盖。

### 问题 2：Hermes 结构化事件格式

**推荐方案：**

- 方案 A：沿用 Responses API `output`，由 Hermes 增加 `device_action` 类型对象。Java 解析明确，扩展成本低。
- 方案 B：Hermes 把结构化事件编码在文本 JSON 中。实现快，但容易污染播报文本。
- 方案 C：新增独立 Hermes 工具回调 API。边界清晰，但需要 Hermes 侧配合更多。

**当前计划选择：** 方案 A，但它是任务 10 的独立集成门禁，不阻塞任务 4-9 的 TTS 主线开发。Hermes Agent 未确认返回 `device_action` 前，只标记本地提醒解析为兼容层。

### 问题 3：TTS 是否落盘

**推荐方案：**

- 方案 A：不落盘，只保留内存 Opus 帧。简单、延迟低，符合当前网关定位。
- 方案 B：可选落盘用于排障。便于复盘音频问题，但要处理清理、隐私和磁盘风险。
- 方案 C：完全参考项目，所有 TTS 先生成文件再播放。功能完整，但增加 I/O 和复杂度。

**当前计划选择：** 方案 A。

## 用户反馈区域

请在此区域补充您对整体规划的意见和建议：

```text
用户补充内容：

---

---

---
```
