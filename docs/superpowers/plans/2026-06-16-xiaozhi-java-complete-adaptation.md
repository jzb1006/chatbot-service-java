# 小智 Java 服务端完整适配实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将 `chatbot-service-java` 从已跑通基础语音闭环，补齐为完整适配小智 ESP32 固件协议、真实设备会话和远程发布门禁的 Java 服务端。

**架构：** 保持当前 Java 21 + Spring Boot 3 多模块 thin gateway 结构，继续由 `chatbot-voice-gateway` 承担小智 WebSocket 协议和会话编排，由 `chatbot-speech-api` 承担 ASR/TTS 转码和 Provider。新增行为集中在协议编解码、可配置音频参数、会话控制和失败事件，不引入 MySQL、Redis、管理后台或重型设备平台注册中心。

**技术栈：** Java 21、Spring Boot 3.4、Spring WebSocket、Maven 多模块、JUnit 5、AssertJ、Jackson、Concentus Opus、腾讯云 ASR/TTS、Hermes Agent。

---

## 范围边界

本计划只修改 `/Users/jiangzhibin/workspace/chatbot-service-java`。不修改 `/Users/jiangzhibin/workspace/xiaozhi-esp32` 固件仓库，不做 Git commit，不做 Git push。

当前已知事实：

- 小智 WebSocket 主入口已是 `/xiaozhi/v1`，并兼容 `/ws/xiaozhi/v1` 与 `/ws/xiaozhi/v1/`。
- 服务端已支持设备先发 `hello`，服务端再返回 `hello`。
- 服务端已支持上行 binary v1/v2/v3 Opus 帧解包。
- 腾讯云 ASR 与腾讯云 TTS 已完成真实链路测试。
- 远程发布和真机验证门禁已经写入 `docs/superpowers/plans/2026-06-16-xiaozhi-firmware-backend-task-checklist.md`。

本计划要补齐：

- 下行 TTS binary v1/v2/v3 编码，与上行协议对称。
- `audio_params` 配置化，默认值保持当前可用的 `opus/16000/1/60`。
- 设备会话控制，支持连续会话和新会话。
- 失败事件回传，避免固件只靠超时或日志判断错误。
- 配置模板、任务清单和发布验证记录同步。

## 文件结构

- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiMessageCodec.java`
  - 职责：小智 JSON 控制帧和 binary 音频帧编解码。新增下行 `encodeAudioFrame(...)`。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiServerHello.java`
  - 职责：构造服务端 hello。改为接收配置化 `XiaozhiAudioParams`。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiAudioParams.java`
  - 职责：描述小智音频参数。保留默认值，作为配置对象的默认基线。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiServerEventFactory.java`
  - 职责：生成服务端下发给固件的 JSON 事件。新增 session 和 error 事件。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeans.java`
  - 职责：语音网关 Bean 装配。新增音频参数配置 Bean。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSession.java`
  - 职责：保存单个 WebSocket 连接的状态、协议版本、音频帧和对话 ID。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
  - 职责：小智会话生命周期、音频回合、ASR -> Hermes -> TTS 编排。新增下行编码、会话控制和失败事件。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-bootstrap/src/main/resources/application.yml`
  - 职责：默认运行配置。新增 `chatbot.voice.audio.*`。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/deploy/chatbot-service.env.example`
  - 职责：远程 env-file 模板。新增音频参数和 ASR Provider 配置示例。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/docs/superpowers/plans/2026-06-16-xiaozhi-firmware-backend-task-checklist.md`
  - 职责：对接小智固件的执行清单。同步新增任务状态、验收标准和真机测试记录项。
- 修改测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/protocol/XiaozhiMessageCodecTest.java`
- 修改测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/protocol/XiaozhiServerEventFactoryTest.java`
- 修改测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeansTest.java`
- 修改测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`
- 修改测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiWebSocketHandlerTest.java`

## 任务 1：下行 binary v1/v2/v3 编码

**文件：**
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiMessageCodec.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
- 测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/protocol/XiaozhiMessageCodecTest.java`
- 测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`

- [ ] **步骤 1：编写下行编码失败测试**

在 `XiaozhiMessageCodecTest` 增加：

```java
@Test
void shouldEncodeBinaryV1AsRawOpusPayload() {
    var encoded = codec.encodeAudioFrame(1, 1234, ByteBuffer.wrap(new byte[] {1, 2, 3}));

    assertThat(toBytes(encoded)).containsExactly(1, 2, 3);
}

@Test
void shouldEncodeBinaryV2Header() {
    var encoded = codec.encodeAudioFrame(2, 1234, ByteBuffer.wrap(new byte[] {4, 5, 6}));
    var buffer = encoded.slice();

    assertThat(Short.toUnsignedInt(buffer.getShort())).isEqualTo(2);
    assertThat(Short.toUnsignedInt(buffer.getShort())).isZero();
    assertThat(buffer.getInt()).isZero();
    assertThat(Integer.toUnsignedLong(buffer.getInt())).isEqualTo(1234L);
    assertThat(buffer.getInt()).isEqualTo(3);
    assertThat(toBytes(buffer)).containsExactly(4, 5, 6);
}

@Test
void shouldEncodeBinaryV3Header() {
    var encoded = codec.encodeAudioFrame(3, 1234, ByteBuffer.wrap(new byte[] {7, 8}));
    var buffer = encoded.slice();

    assertThat(Byte.toUnsignedInt(buffer.get())).isZero();
    assertThat(Byte.toUnsignedInt(buffer.get())).isZero();
    assertThat(Short.toUnsignedInt(buffer.getShort())).isEqualTo(2);
    assertThat(toBytes(buffer)).containsExactly(7, 8);
}

private byte[] toBytes(ByteBuffer buffer) {
    var input = buffer.slice();
    var bytes = new byte[input.remaining()];
    input.get(bytes);
    return bytes;
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -Dtest=XiaozhiMessageCodecTest test
```

预期：FAIL，编译报错包含 `cannot find symbol` 和 `encodeAudioFrame`。

- [ ] **步骤 3：实现最少编码逻辑**

在 `XiaozhiMessageCodec` 增加：

```java
public ByteBuffer encodeAudioFrame(int protocolVersion, long timestamp, ByteBuffer payload) {
    var input = payload == null ? ByteBuffer.allocate(0) : payload.slice();
    var bytes = new byte[input.remaining()];
    input.get(bytes);
    if (protocolVersion == 2) {
        var output = ByteBuffer.allocate(16 + bytes.length);
        output.putShort((short) 2);
        output.putShort((short) 0);
        output.putInt(0);
        output.putInt((int) timestamp);
        output.putInt(bytes.length);
        output.put(bytes);
        output.flip();
        return output;
    }
    if (protocolVersion == 3) {
        var output = ByteBuffer.allocate(4 + bytes.length);
        output.put((byte) 0);
        output.put((byte) 0);
        output.putShort((short) bytes.length);
        output.put(bytes);
        output.flip();
        return output;
    }
    return ByteBuffer.wrap(bytes);
}
```

- [ ] **步骤 4：运行 codec 测试验证通过**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -Dtest=XiaozhiMessageCodecTest test
```

预期：PASS。

- [ ] **步骤 5：编写会话服务下行编码失败测试**

在 `XiaozhiVoiceSessionServiceTest` 增加：

```java
@Test
void shouldEncodeTtsBinaryWithCurrentProtocolVersion() {
    var session = openSessionWithProtocolVersion(3);
    service.handleText(session, new XiaozhiClientMessage(
            "listen", "start", "manual", null, null, "ws-session-1", null
    ));
    service.handleBinary(session, ByteBuffer.wrap(new byte[] {1, 2, 3}));

    service.handleText(session, new XiaozhiClientMessage(
            "listen", "stop", null, null, null, "ws-session-1", null
    ));

    assertThat(session.getSentMessages())
            .filteredOn(BinaryMessage.class::isInstance)
            .singleElement()
            .satisfies(message -> {
                var payload = ((BinaryMessage) message).getPayload().slice();
                assertThat(Byte.toUnsignedInt(payload.get())).isZero();
                assertThat(Byte.toUnsignedInt(payload.get())).isZero();
                assertThat(Short.toUnsignedInt(payload.getShort())).isGreaterThan(0);
            });
}

private TestWebSocketSession openSessionWithProtocolVersion(int protocolVersion) {
    var session = new TestWebSocketSession("ws-session-1");
    service.open(session);
    service.handleHello(session, new XiaozhiClientHello(
            "hello",
            protocolVersion,
            Map.of("mcp", true),
            "websocket",
            XiaozhiAudioParams.defaults()
    ));
    return session;
}
```

- [ ] **步骤 6：运行会话测试验证失败**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -Dtest=XiaozhiVoiceSessionServiceTest test
```

预期：FAIL，断言下行二进制帧前 4 字节不是 v3 头部。

- [ ] **步骤 7：在下发 TTS 时使用 codec 编码**

将 `XiaozhiVoiceSessionService` 中：

```java
webSocketSession.sendMessage(new BinaryMessage(frame));
```

改为：

```java
webSocketSession.sendMessage(new BinaryMessage(
        codec.encodeAudioFrame(voiceSession.protocolVersion(), 0, frame)
));
```

- [ ] **步骤 8：运行任务 1 测试验证通过**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -Dtest=XiaozhiMessageCodecTest,XiaozhiVoiceSessionServiceTest test
```

预期：PASS。

## 任务 2：`audio_params` 配置化

**文件：**
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiAudioParams.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiServerHello.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiMessageCodec.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiWebSocketHandler.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeans.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-bootstrap/src/main/resources/application.yml`
- 测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/protocol/XiaozhiMessageCodecTest.java`
- 测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeansTest.java`
- 测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiWebSocketHandlerTest.java`

- [ ] **步骤 1：编写 server hello 配置化失败测试**

在 `XiaozhiMessageCodecTest` 增加：

```java
@Test
void shouldBuildServerHelloWithConfiguredAudioParams() throws Exception {
    var params = new XiaozhiAudioParams("opus", 24000, 1, 60);

    var json = codec.encodeServerHello("audio-1", params);

    assertThat(json).contains("\"sample_rate\":24000");
    assertThat(json).contains("\"channels\":1");
    assertThat(json).contains("\"frame_duration\":60");
}
```

- [ ] **步骤 2：运行 codec 测试验证失败**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -Dtest=XiaozhiMessageCodecTest test
```

预期：FAIL，编译报错包含 `encodeServerHello(String, XiaozhiAudioParams)` 不存在。

- [ ] **步骤 3：实现 server hello 参数化**

将 `XiaozhiServerHello.websocket` 调整为：

```java
public static XiaozhiServerHello websocket(String sessionId, XiaozhiAudioParams audioParams) {
    return new XiaozhiServerHello(
            "hello",
            "websocket",
            sessionId,
            audioParams == null ? XiaozhiAudioParams.defaults() : audioParams
    );
}
```

在 `XiaozhiMessageCodec` 保留旧方法并新增重载：

```java
public String encodeServerHello(String sessionId) throws JsonProcessingException {
    return encodeServerHello(sessionId, XiaozhiAudioParams.defaults());
}

public String encodeServerHello(String sessionId, XiaozhiAudioParams audioParams) throws JsonProcessingException {
    return objectMapper.writeValueAsString(XiaozhiServerHello.websocket(sessionId, audioParams));
}
```

- [ ] **步骤 4：运行 codec 测试验证通过**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -Dtest=XiaozhiMessageCodecTest test
```

预期：PASS。

- [ ] **步骤 5：编写配置 Bean 失败测试**

在 `XiaozhiVoiceGatewayBeansTest` 增加：

```java
@Test
void shouldCreateConfiguredXiaozhiAudioParams() {
    contextRunner
            .withPropertyValues(
                    "chatbot.voice.audio.format=opus",
                    "chatbot.voice.audio.sample-rate=24000",
                    "chatbot.voice.audio.channels=1",
                    "chatbot.voice.audio.frame-duration=60"
            )
            .run(context -> {
                var params = context.getBean(XiaozhiAudioParams.class);

                assertThat(params.format()).isEqualTo("opus");
                assertThat(params.sampleRate()).isEqualTo(24000);
                assertThat(params.channels()).isEqualTo(1);
                assertThat(params.frameDuration()).isEqualTo(60);
            });
}
```

同时导入：

```java
import com.jzb.chatbot.voice.protocol.XiaozhiAudioParams;
```

- [ ] **步骤 6：运行 Bean 测试验证失败**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -Dtest=XiaozhiVoiceGatewayBeansTest test
```

预期：FAIL，启动失败或找不到 `XiaozhiAudioParams` Bean。

- [ ] **步骤 7：新增配置 Bean**

在 `XiaozhiVoiceGatewayBeans` 增加：

```java
@Bean
XiaozhiAudioParams xiaozhiAudioParams(
        @Value("${chatbot.voice.audio.format:opus}") String format,
        @Value("${chatbot.voice.audio.sample-rate:16000}") int sampleRate,
        @Value("${chatbot.voice.audio.channels:1}") int channels,
        @Value("${chatbot.voice.audio.frame-duration:60}") int frameDuration
) {
    return new XiaozhiAudioParams(format, sampleRate, channels, frameDuration);
}
```

并导入：

```java
import com.jzb.chatbot.voice.protocol.XiaozhiAudioParams;
```

- [ ] **步骤 8：让 WebSocket Handler 使用配置化参数**

修改 `XiaozhiWebSocketHandler` 构造依赖，增加：

```java
private final XiaozhiAudioParams audioParams;
```

并将 hello 回包从：

```java
session.sendMessage(new TextMessage(codec.encodeServerHello(session.getId())));
```

改为：

```java
session.sendMessage(new TextMessage(codec.encodeServerHello(session.getId(), audioParams)));
```

所有 `new XiaozhiWebSocketHandler(...)` 测试构造处补第三个参数 `XiaozhiAudioParams.defaults()`。

- [ ] **步骤 9：更新默认配置**

在 `application.yml` 的 `chatbot.voice` 下新增：

```yaml
    audio:
      format: ${CHATBOT_VOICE_AUDIO_FORMAT:opus}
      sample-rate: ${CHATBOT_VOICE_AUDIO_SAMPLE_RATE:16000}
      channels: ${CHATBOT_VOICE_AUDIO_CHANNELS:1}
      frame-duration: ${CHATBOT_VOICE_AUDIO_FRAME_DURATION:60}
```

- [ ] **步骤 10：运行任务 2 测试验证通过**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am test
```

预期：PASS。

## 任务 3：设备会话控制

**文件：**
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSession.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiServerEventFactory.java`
- 测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`

- [ ] **步骤 1：编写连续会话和新会话失败测试**

在 `XiaozhiVoiceSessionServiceTest` 增加：

```java
@Test
void shouldKeepSameConversationUntilSessionNewRequested() {
    var hermesClient = new RecordingHermesClient();
    var serviceWithRecordingHermes = new XiaozhiVoiceSessionService(
            codec,
            new FakeSpeechToTextClient(),
            hermesClient,
            new FakeTextToSpeechClient(),
            new XiaozhiServerEventFactory(new ObjectMapper()),
            new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner"),
            new XiaozhiVoiceTokenAuth("")
    );
    var session = openSession(serviceWithRecordingHermes);

    runSingleTurn(serviceWithRecordingHermes, session);
    runSingleTurn(serviceWithRecordingHermes, session);
    serviceWithRecordingHermes.handleText(session, new XiaozhiClientMessage(
            "session", "new", null, null, null, "ws-session-1", null
    ));
    runSingleTurn(serviceWithRecordingHermes, session);

    assertThat(hermesClient.conversationIds()).hasSize(3);
    assertThat(hermesClient.conversationIds().get(0)).isEqualTo(hermesClient.conversationIds().get(1));
    assertThat(hermesClient.conversationIds().get(2)).isNotEqualTo(hermesClient.conversationIds().get(0));
}

private void runSingleTurn(XiaozhiVoiceSessionService service, TestWebSocketSession session) {
    service.handleText(session, new XiaozhiClientMessage(
            "listen", "start", "manual", null, null, "ws-session-1", null
    ));
    service.handleBinary(session, ByteBuffer.wrap(new byte[] {1, 2, 3}));
    service.handleText(session, new XiaozhiClientMessage(
            "listen", "stop", null, null, null, "ws-session-1", null
    ));
}
```

新增测试辅助类：

```java
private static class RecordingHermesClient implements HermesClient {

    private final java.util.ArrayList<String> conversationIds = new java.util.ArrayList<>();

    @Override
    public HermesResponse chat(HermesRequest request, HermesClientConfig config) {
        conversationIds.add(request.conversationId().value());
        return new HermesResponse(request.conversationId(), "pong");
    }

    @Override
    public Stream<String> streamChat(HermesRequest request, HermesClientConfig config) {
        return Stream.of();
    }

    private List<String> conversationIds() {
        return List.copyOf(conversationIds);
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -Dtest=XiaozhiVoiceSessionServiceTest test
```

预期：FAIL，第三次 Hermes conversation 仍等于 `conv-<deviceId>`。

- [ ] **步骤 3：在会话状态中保存 conversationId**

在 `XiaozhiVoiceSession` 增加字段和方法：

```java
private String conversationId;
private long conversationSequence;

public String conversationId() {
    if (conversationId == null || conversationId.isBlank()) {
        conversationId = "conv-" + deviceId();
    }
    return conversationId;
}

public String startNewConversation() {
    conversationSequence++;
    conversationId = "conv-" + deviceId() + "-" + conversationSequence;
    audioFrames.clear();
    state = State.IDLE;
    return conversationId;
}

public String clearConversation() {
    conversationId = "conv-" + deviceId();
    conversationSequence = 0;
    audioFrames.clear();
    state = State.IDLE;
    return conversationId;
}
```

- [ ] **步骤 4：使用会话中的 conversationId 调 Hermes**

将 `XiaozhiVoiceSessionService` 中：

```java
new ConversationId("conv-" + voiceSession.deviceId())
```

改为：

```java
new ConversationId(voiceSession.conversationId())
```

- [ ] **步骤 5：处理 session 控制帧**

在 `XiaozhiVoiceSessionService.handleText(...)` 中增加：

```java
if ("session".equals(message.type()) && "new".equals(message.state())) {
    var conversationId = voiceSession.startNewConversation();
    sendText(webSocketSession, eventFactory.session(voiceSession.sessionId(), conversationId));
    log.info("xiaozhi conversation started, sessionId={}, deviceId={}, conversationId={}",
            webSocketSession.getId(), voiceSession.deviceId(), conversationId);
    return;
}
if ("session".equals(message.type()) && "clear".equals(message.state())) {
    var conversationId = voiceSession.clearConversation();
    sendText(webSocketSession, eventFactory.session(voiceSession.sessionId(), conversationId));
    log.info("xiaozhi conversation cleared, sessionId={}, deviceId={}, conversationId={}",
            webSocketSession.getId(), voiceSession.deviceId(), conversationId);
    return;
}
```

如果没有 `sendText` 辅助方法，先以内联 `try/catch` 实现，随后在任务 4 统一提取。

- [ ] **步骤 6：新增 session 事件工厂**

在 `XiaozhiServerEventFactory` 增加：

```java
public String session(String sessionId, String conversationId) {
    return objectMapper.createObjectNode()
            .put("session_id", sessionId)
            .put("type", "session")
            .put("state", "ready")
            .put("conversation_id", conversationId)
            .toString();
}
```

- [ ] **步骤 7：运行任务 3 测试验证通过**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -Dtest=XiaozhiVoiceSessionServiceTest test
```

预期：PASS。

## 任务 4：失败事件回传

**文件：**
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiServerEventFactory.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
- 测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/protocol/XiaozhiServerEventFactoryTest.java`
- 测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`

- [ ] **步骤 1：编写 error 事件工厂失败测试**

在 `XiaozhiServerEventFactoryTest` 增加：

```java
@Test
void shouldBuildErrorEvent() {
    var json = factory.error("s1", "asr_empty", "未识别到语音");

    assertThat(json).contains("\"type\":\"error\"");
    assertThat(json).contains("\"code\":\"asr_empty\"");
    assertThat(json).contains("\"message\":\"未识别到语音\"");
}
```

- [ ] **步骤 2：运行事件工厂测试验证失败**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -Dtest=XiaozhiServerEventFactoryTest test
```

预期：FAIL，编译报错包含 `error` 方法不存在。

- [ ] **步骤 3：实现 error 事件工厂**

在 `XiaozhiServerEventFactory` 增加：

```java
public String error(String sessionId, String code, String message) {
    return objectMapper.createObjectNode()
            .put("session_id", sessionId)
            .put("type", "error")
            .put("code", code)
            .put("message", message)
            .toString();
}
```

- [ ] **步骤 4：运行事件工厂测试验证通过**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -Dtest=XiaozhiServerEventFactoryTest test
```

预期：PASS。

- [ ] **步骤 5：编写失败分支回传测试**

在 `XiaozhiVoiceSessionServiceTest` 中调整或新增：

```java
@Test
void shouldSendErrorEventWhenAsrReturnsBlankText() {
    var serviceWithBlankAsr = new XiaozhiVoiceSessionService(
            codec,
            audioFrames -> " ",
            new CapturingHermesClient(),
            new FakeTextToSpeechClient(),
            new XiaozhiServerEventFactory(new ObjectMapper()),
            new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner"),
            new XiaozhiVoiceTokenAuth("")
    );
    var session = openSession(serviceWithBlankAsr);

    runSingleTurn(serviceWithBlankAsr, session);

    assertThat(session.getSentMessages())
            .filteredOn(TextMessage.class::isInstance)
            .extracting(message -> message.getPayload().toString())
            .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"error\"", "\"code\":\"asr_empty\""));
    assertThat(serviceWithBlankAsr.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
}

@Test
void shouldSendErrorEventWhenHermesFails() {
    var serviceWithFailingHermes = new XiaozhiVoiceSessionService(
            codec,
            new FakeSpeechToTextClient(),
            new FailingHermesClient(),
            new FakeTextToSpeechClient(),
            new XiaozhiServerEventFactory(new ObjectMapper()),
            new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner"),
            new XiaozhiVoiceTokenAuth("")
    );
    var session = openSession(serviceWithFailingHermes);

    runSingleTurn(serviceWithFailingHermes, session);

    assertThat(session.getSentMessages())
            .filteredOn(TextMessage.class::isInstance)
            .extracting(message -> message.getPayload().toString())
            .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"error\"", "\"code\":\"hermes_failed\""));
    assertThat(serviceWithFailingHermes.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
}
```

- [ ] **步骤 6：运行会话测试验证失败**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -Dtest=XiaozhiVoiceSessionServiceTest test
```

预期：FAIL，找不到 error 事件或断言没有匹配 payload。

- [ ] **步骤 7：在失败分支发送 error 事件**

在 `XiaozhiVoiceSessionService` 中提取发送文本辅助方法：

```java
private void sendText(WebSocketSession webSocketSession, String payload) {
    try {
        webSocketSession.sendMessage(new TextMessage(payload));
    } catch (IOException exception) {
        throw new IllegalStateException("Failed to send xiaozhi websocket message", exception);
    }
}
```

将已有 `webSocketSession.sendMessage(new TextMessage(...))` 逐步改成 `sendText(...)`。

ASR 空结果分支增加：

```java
sendText(webSocketSession, eventFactory.error(voiceSession.sessionId(), "asr_empty", "未识别到语音"));
```

`catch (RuntimeException exception)` 分支按阶段发送：

- Hermes 调用处异常：`hermes_failed`
- TTS 合成处异常：`tts_failed`
- ASR 调用处异常：`asr_failed`

实现时推荐将 `processTurn` 拆成私有小方法：

- `transcribe(...)`
- `chat(...)`
- `synthesizeSpeech(...)`

每个方法只处理自己的错误事件，避免一个大 `catch` 无法区分来源。

- [ ] **步骤 8：运行任务 4 测试验证通过**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -Dtest=XiaozhiServerEventFactoryTest,XiaozhiVoiceSessionServiceTest test
```

预期：PASS。

## 任务 5：配置模板和任务清单同步

**文件：**
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/deploy/chatbot-service.env.example`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/docs/superpowers/plans/2026-06-16-xiaozhi-firmware-backend-task-checklist.md`

- [ ] **步骤 1：更新 env 模板**

在 `deploy/chatbot-service.env.example` 增加：

```bash
# XiaoZhi WebSocket
XIAOZHI_WEBSOCKET_TOKEN=
CHATBOT_VOICE_AUDIO_FORMAT=opus
CHATBOT_VOICE_AUDIO_SAMPLE_RATE=16000
CHATBOT_VOICE_AUDIO_CHANNELS=1
CHATBOT_VOICE_AUDIO_FRAME_DURATION=60

# Tencent Cloud ASR
CHATBOT_VOICE_ASR_PROVIDER=tencent
TENCENT_CLOUD_ASR_ENGINE_MODEL_TYPE=16k_zh
TENCENT_CLOUD_ASR_VOICE_FORMAT=pcm
```

保留现有 TTS 配置，不写入真实密钥。

- [ ] **步骤 2：更新任务清单完成状态**

在 `2026-06-16-xiaozhi-firmware-backend-task-checklist.md` 中新增或调整：

- 下行 binary v2/v3 编码任务。
- `audio_params` 配置化任务。
- 设备会话控制任务。
- 失败事件回传任务。
- 真机测试记录中增加 `Protocol-Version`、`audio_params`、`conversation_id`、错误事件验证。

- [ ] **步骤 3：校验文档无占位符**

运行：

```bash
rg -n "TODO|待定|后续补充|placeholder" docs/superpowers/plans/2026-06-16-xiaozhi-firmware-backend-task-checklist.md deploy/chatbot-service.env.example
```

预期：没有输出。若有输出，删除占位符或改成明确内容。

## 任务 6：完整验证与审查

**文件：**
- 验证范围：`/Users/jiangzhibin/workspace/chatbot-service-java`

- [ ] **步骤 1：运行 voice-gateway 及依赖测试**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am test
```

预期：`BUILD SUCCESS`，测试失败数为 0。

- [ ] **步骤 2：运行 bootstrap 构建**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-bootstrap -am -DskipTests package
```

预期：`BUILD SUCCESS`。

- [ ] **步骤 3：检查格式空白问题**

运行：

```bash
git diff --check
```

预期：无输出。

- [ ] **步骤 4：查看变更范围**

运行：

```bash
git status --short
git diff --stat
```

预期：只包含本计划列出的 Java、测试、配置和文档文件。

- [ ] **步骤 5：Java 审查**

按 `java-reviewer` 规则审查当前 Java 变更。重点检查：

- WebSocket 会话状态并发读写是否仍局限在单 session 对象。
- token 和密钥没有进入日志。
- 配置默认值是否保持向后兼容。
- binary v2/v3 编码是否与 `/Users/jiangzhibin/workspace/xiaozhi-esp32/main/protocols/protocol.h` 一致。
- 失败事件不会导致二次异常覆盖原始异常。

发现 BLOCK 或重要 WARN 后先修复，再重复步骤 1 到步骤 4。

## 发布与真机验证门禁

如果本计划实现后要发布到远程服务器，必须按以下顺序执行：

1. 本地 `chatbot-voice-gateway` 测试通过。
2. `chatbot-bootstrap` 构建通过。
3. 备份远程旧 jar 到 `/opt/chatbot-service-java-runtime/backups/`。
4. 替换 `/opt/chatbot-service-java-runtime/chatbot-service.jar`。
5. 使用 `--env-file /opt/chatbot-service-java-runtime/chatbot-service.env` 重建并重启 `device_gateway`。
6. 验证 `http://203.195.202.54:8766/actuator/health` 返回 `{"status":"UP"}`。
7. 使用公网 WebSocket smoke 验证 `/xiaozhi/v1` hello。
8. 使用真机验证 `Protocol-Version=1/2/3` 中至少当前固件实际使用版本。
9. 真机验证 ASR、Hermes 回复、TTS 播放、session new、失败事件。
10. 将测试时间、Git commit、设备 ID、协议版本、audio params、识别文本、TTS 播放结果、失败现象写入 `2026-06-16-xiaozhi-firmware-backend-task-checklist.md` 的真机测试记录。

## 自检结果

- 规格覆盖度：协议完整性、音频参数、会话控制、失败事件、配置模板、发布门禁均有对应任务。
- 占位符扫描：本文档没有 `TODO`、`待定`、`后续补充`、`placeholder`。
- 类型一致性：计划中使用的新增方法名为 `encodeAudioFrame`、`encodeServerHello(String, XiaozhiAudioParams)`、`session`、`error`、`conversationId`、`startNewConversation`、`clearConversation`，在对应任务中均定义了签名。
