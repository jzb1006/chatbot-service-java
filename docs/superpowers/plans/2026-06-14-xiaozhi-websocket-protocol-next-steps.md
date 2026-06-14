# 小智 WebSocket 协议后续实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将 `chatbot-service-java` 的小智 WebSocket 网关从协议骨架推进到可与 `/Users/jiangzhibin/workspace/xiaozhi-esp32` 固件联调的最小语音闭环。

**架构：** Java 服务只承担协议网关、音频会话编排、ASR/TTS/Hermes 转接职责。Hermes 已提供工具调用和编排能力，因此本计划不实现 Java 侧 MCP Server；`type=mcp` 仅作为保留消息类型记录或透传扩展点，不进入 MVP。

**技术栈：** Java 21、Spring Boot 3、Spring WebSocket、JUnit 5、AssertJ、Mockito、Jackson、Lombok。

---

## 已明确的决策

- 小智固件仓库路径：`/Users/jiangzhibin/workspace/xiaozhi-esp32`。
- Java 中间件仓库路径：`/Users/jiangzhibin/workspace/chatbot-service-java`。
- WebSocket 入口保持：`/xiaozhi/v1`。
- 固件握手流程以 `main/protocols/websocket_protocol.cc` 为准：设备连接成功后先发 `type=hello`，服务端再回复 `type=hello`。
- 服务端 hello 字段使用 `audio_params`，不是当前 Java 代码里的 `audio`。
- MCP 不作为 Java 中间件 MVP 能力。收到 `type=mcp` 可以记录并忽略；服务端不主动下发 `type=mcp`。
- Hermes 负责 LLM、记忆、工具调用和上层编排；Java 语音网关只向 Hermes 发送文本请求并接收文本响应。
- 第一阶段使用 Fake ASR/TTS 保证协议可测，真实 Provider 后续替换。

## 文件结构

- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiWebSocketHandler.java`
  - 职责：WebSocket 入口层，只做协议分发、异常关闭和调用会话服务。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSession.java`
  - 职责：单个 WebSocket 音频会话状态、音频帧缓存、协议版本、设备标识。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
  - 职责：处理 hello、listen、abort、binary audio，并编排 ASR -> Hermes -> TTS。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiClientHello.java`
  - 职责：解析设备 hello。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiServerHello.java`
  - 职责：返回符合固件预期的 `audio_params`。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiClientMessage.java`
  - 职责：覆盖 `hello/listen/abort/mcp` 的通用字段。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiMessageCodec.java`
  - 职责：文本控制帧编解码、服务端事件构造、binary v1/v2/v3 解包和封包。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiAudioFrame.java`
  - 职责：保存解包后的 Opus payload、timestamp、协议版本。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiServerEventFactory.java`
  - 职责：生成 `stt`、`llm`、`tts.start`、`tts.sentence_start`、`tts.stop` 等服务端 JSON。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/SpeechToTextClient.java`
  - 职责：从单帧接口调整为一轮音频帧转文本接口。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TextToSpeechClient.java`
  - 职责：从单 ByteBuffer 调整为可迭代音频帧，便于 WebSocket 分片下发。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/FakeSpeechToTextClient.java`
  - 职责：协议联调阶段返回固定文本。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/FakeTextToSpeechClient.java`
  - 职责：协议联调阶段返回固定 Opus-like byte frames。
- 测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/protocol/XiaozhiMessageCodecTest.java`
- 测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiWebSocketHandlerTest.java`
- 测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`
- 测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-speech-api/src/test/java/com/jzb/chatbot/speech/FakeSpeechClientTest.java`

## 整体规划概述

### 阶段 1：协议握手兼容

修正当前服务端主动 hello 的行为，改为收到设备 hello 后回服务端 hello。输出字段与固件一致，尤其是 `audio_params`、`session_id`、`transport=websocket`。

### 阶段 2：控制帧和音频帧

支持 `listen.start`、`listen.stop`、`listen.detect`、`abort`，并实现 binary v1/v2/v3 解包。`mcp` 只记录并忽略，不做 Java 侧工具协议。

### 阶段 3：最小语音闭环

在 `listen.stop` 后把本轮音频交给 ASR，ASR 文本交给 Hermes，Hermes 响应交给 TTS，再向设备发送 `stt`、`llm`、`tts` 控制帧和二进制音频帧。

### 阶段 4：联调和收口

补充固件兼容测试、异常路径、README 联调说明，保证本地服务可被小智固件配置连接。

## 任务 1：修正 WebSocket 握手协议

**文件：**
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiWebSocketHandler.java`
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiClientHello.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiServerHello.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiMessageCodec.java`
- 测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/protocol/XiaozhiMessageCodecTest.java`
- 测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiWebSocketHandlerTest.java`

- [x] **步骤 1：改写失败测试，确认连接建立后不主动发送 hello**

在 `XiaozhiWebSocketHandlerTest` 中将原 `shouldSendHelloAfterConnectionEstablished` 改为：

```java
@Test
void shouldNotSendHelloBeforeClientHello() throws Exception {
    var codec = new XiaozhiMessageCodec(new ObjectMapper());
    var sessionService = new XiaozhiVoiceSessionService(codec);
    var handler = new XiaozhiWebSocketHandler(codec, sessionService);
    var session = new TestWebSocketSession("ws-session-1");

    handler.afterConnectionEstablished(session);

    assertThat(session.getSentMessages()).isEmpty();
}
```

- [x] **步骤 2：新增设备 hello 编解码测试**

在 `XiaozhiMessageCodecTest` 中加入：

```java
@Test
void shouldParseClientHello() throws Exception {
    var message = """
            {
              "type": "hello",
              "version": 2,
              "features": {"mcp": true},
              "transport": "websocket",
              "audio_params": {
                "format": "opus",
                "sample_rate": 16000,
                "channels": 1,
                "frame_duration": 60
              }
            }
            """;

    var hello = codec.decodeClientHello(message);

    assertThat(hello.type()).isEqualTo("hello");
    assertThat(hello.version()).isEqualTo(2);
    assertThat(hello.transport()).isEqualTo("websocket");
    assertThat(hello.audioParams().format()).isEqualTo("opus");
    assertThat(hello.audioParams().sampleRate()).isEqualTo(16000);
}
```

- [x] **步骤 3：新增服务端 hello 字段测试**

在 `XiaozhiMessageCodecTest` 中加入：

```java
@Test
void shouldBuildServerHelloWithAudioParamsField() throws Exception {
    var json = codec.encodeServerHello("audio-session-1");

    assertThat(json).contains("\"type\":\"hello\"");
    assertThat(json).contains("\"transport\":\"websocket\"");
    assertThat(json).contains("\"session_id\":\"audio-session-1\"");
    assertThat(json).contains("\"audio_params\"");
    assertThat(json).doesNotContain("\"audio\"");
}
```

- [x] **步骤 4：运行测试验证失败**

运行：

```bash
cd "/Users/jiangzhibin/workspace/chatbot-service-java"
mvn -pl chatbot-voice-gateway -am test
```

预期：测试失败，原因是当前 Handler 主动发送 hello，且 `XiaozhiServerHello` 使用字段 `audio`。

- [x] **步骤 5：实现设备 hello DTO**

创建 `XiaozhiClientHello.java`：

```java
package com.jzb.chatbot.voice.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * 小智设备 hello 消息。
 *
 * @author jiangzhibin
 * @since 2026-06-14 00:00:00
 */
public record XiaozhiClientHello(
        String type,
        int version,
        Map<String, Boolean> features,
        String transport,
        @JsonProperty("audio_params") XiaozhiAudioParams audioParams
) {
}
```

- [x] **步骤 6：修正服务端 hello DTO**

将 `XiaozhiServerHello` 改为：

```java
public record XiaozhiServerHello(
        String type,
        String transport,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("audio_params") XiaozhiAudioParams audioParams
) {

    public static XiaozhiServerHello websocket(String sessionId) {
        return new XiaozhiServerHello(
                "hello",
                "websocket",
                sessionId,
                XiaozhiAudioParams.defaults()
        );
    }
}
```

- [x] **步骤 7：修正 codec 方法**

将 `XiaozhiMessageCodec` 调整为：

```java
public String encodeServerHello(String sessionId) throws JsonProcessingException {
    return objectMapper.writeValueAsString(XiaozhiServerHello.websocket(sessionId));
}

public XiaozhiClientHello decodeClientHello(String text) throws JsonProcessingException {
    return objectMapper.readValue(text, XiaozhiClientHello.class);
}
```

- [x] **步骤 8：修正 Handler 握手行为**

`afterConnectionEstablished` 只登记连接，不发送消息。`handleTextMessage` 收到 `type=hello` 后再回复服务端 hello。

```java
@Override
public void afterConnectionEstablished(WebSocketSession session) {
    sessionService.open(session);
}

@Override
protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    var clientMessage = codec.decodeText(message.getPayload());
    if ("hello".equals(clientMessage.type())) {
        var hello = codec.decodeClientHello(message.getPayload());
        sessionService.handleHello(session, hello);
        session.sendMessage(new TextMessage(codec.encodeServerHello(session.getId())));
        return;
    }
    sessionService.handleText(session, clientMessage);
}
```

- [x] **步骤 9：运行测试验证通过**

运行：

```bash
cd "/Users/jiangzhibin/workspace/chatbot-service-java"
mvn -pl chatbot-voice-gateway -am test
```

预期：`XiaozhiMessageCodecTest` 和 `XiaozhiWebSocketHandlerTest` 通过。

## 任务 2：实现控制帧状态机

**文件：**
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSession.java`
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiClientMessage.java`
- 测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`

- [x] **步骤 1：扩展客户端消息 DTO**

将 `XiaozhiClientMessage` 扩展为：

```java
public record XiaozhiClientMessage(
        String type,
        String state,
        String mode,
        String reason,
        String text,
        @JsonProperty("session_id") String sessionId,
        JsonNode payload
) {
}
```

- [x] **步骤 2：编写 listen.start 测试**

创建 `XiaozhiVoiceSessionServiceTest`：

```java
@Test
void shouldEnterListeningWhenListenStartReceived() {
    var service = new XiaozhiVoiceSessionService(new XiaozhiMessageCodec(new ObjectMapper()));
    var session = new TestWebSocketSession("ws-session-1");
    service.open(session);
    service.handleHello(session, new XiaozhiClientHello(
            "hello",
            1,
            Map.of("mcp", true),
            "websocket",
            XiaozhiAudioParams.defaults()
    ));

    service.handleText(session, new XiaozhiClientMessage(
            "listen", "start", "manual", null, null, "ws-session-1", null
    ));

    assertThat(service.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.LISTENING);
}
```

- [x] **步骤 3：编写 listen.stop 测试**

```java
@Test
void shouldMarkSessionReadyToProcessWhenListenStopReceived() {
    var service = new XiaozhiVoiceSessionService(new XiaozhiMessageCodec(new ObjectMapper()));
    var session = new TestWebSocketSession("ws-session-1");
    service.open(session);
    service.handleText(session, new XiaozhiClientMessage(
            "listen", "start", "manual", null, null, "ws-session-1", null
    ));

    service.handleText(session, new XiaozhiClientMessage(
            "listen", "stop", null, null, null, "ws-session-1", null
    ));

    assertThat(service.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.PROCESSING);
}
```

- [x] **步骤 4：编写 abort 测试**

```java
@Test
void shouldClearSpeakingStateWhenAbortReceived() {
    var service = new XiaozhiVoiceSessionService(new XiaozhiMessageCodec(new ObjectMapper()));
    var session = new TestWebSocketSession("ws-session-1");
    service.open(session);
    service.getSession(session.getId()).markSpeaking();

    service.handleText(session, new XiaozhiClientMessage(
            "abort", null, null, "wake_word_detected", null, "ws-session-1", null
    ));

    assertThat(service.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
}
```

- [x] **步骤 5：运行测试验证失败**

运行：

```bash
cd "/Users/jiangzhibin/workspace/chatbot-service-java"
mvn -pl chatbot-voice-gateway -am test
```

预期：缺少 `XiaozhiVoiceSession`、`XiaozhiVoiceSessionService`。

- [x] **步骤 6：实现会话对象**

创建 `XiaozhiVoiceSession.java`：

```java
package com.jzb.chatbot.voice;

import com.jzb.chatbot.voice.protocol.XiaozhiAudioFrame;
import java.util.ArrayList;
import java.util.List;

public class XiaozhiVoiceSession {

    public enum State {
        IDLE,
        LISTENING,
        PROCESSING,
        SPEAKING
    }

    private final String sessionId;
    private final List<XiaozhiAudioFrame> audioFrames = new ArrayList<>();
    private State state = State.IDLE;
    private int protocolVersion = 1;

    public XiaozhiVoiceSession(String sessionId) {
        this.sessionId = sessionId;
    }

    public String sessionId() {
        return sessionId;
    }

    public State state() {
        return state;
    }

    public int protocolVersion() {
        return protocolVersion;
    }

    public void updateProtocolVersion(int protocolVersion) {
        this.protocolVersion = protocolVersion <= 0 ? 1 : protocolVersion;
    }

    public void markListening() {
        state = State.LISTENING;
        audioFrames.clear();
    }

    public void markProcessing() {
        state = State.PROCESSING;
    }

    public void markSpeaking() {
        state = State.SPEAKING;
    }

    public void markIdle() {
        state = State.IDLE;
        audioFrames.clear();
    }

    public void addAudioFrame(XiaozhiAudioFrame frame) {
        audioFrames.add(frame);
    }

    public List<XiaozhiAudioFrame> drainAudioFrames() {
        var frames = List.copyOf(audioFrames);
        audioFrames.clear();
        return frames;
    }
}
```

- [x] **步骤 7：实现会话服务的控制帧处理**

创建 `XiaozhiVoiceSessionService.java`：

```java
package com.jzb.chatbot.voice;

import com.jzb.chatbot.voice.protocol.XiaozhiClientHello;
import com.jzb.chatbot.voice.protocol.XiaozhiClientMessage;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

@Slf4j
@Service
@RequiredArgsConstructor
public class XiaozhiVoiceSessionService {

    private final XiaozhiMessageCodec codec;
    private final Map<String, XiaozhiVoiceSession> sessions = new ConcurrentHashMap<>();

    public void open(WebSocketSession session) {
        sessions.put(session.getId(), new XiaozhiVoiceSession(session.getId()));
    }

    public XiaozhiVoiceSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public void handleHello(WebSocketSession webSocketSession, XiaozhiClientHello hello) {
        getSession(webSocketSession.getId()).updateProtocolVersion(hello.version());
    }

    public void handleText(WebSocketSession webSocketSession, XiaozhiClientMessage message) {
        var voiceSession = getSession(webSocketSession.getId());
        if ("listen".equals(message.type()) && "start".equals(message.state())) {
            voiceSession.markListening();
            return;
        }
        if ("listen".equals(message.type()) && "stop".equals(message.state())) {
            voiceSession.markProcessing();
            return;
        }
        if ("listen".equals(message.type()) && "detect".equals(message.state())) {
            log.info("xiaozhi wake word detected, sessionId={}, text={}", webSocketSession.getId(), message.text());
            return;
        }
        if ("abort".equals(message.type())) {
            voiceSession.markIdle();
            return;
        }
        if ("mcp".equals(message.type())) {
            log.debug("ignore xiaozhi mcp message in java gateway, sessionId={}", webSocketSession.getId());
        }
    }
}
```

- [x] **步骤 8：运行测试验证通过**

运行：

```bash
cd "/Users/jiangzhibin/workspace/chatbot-service-java"
mvn -pl chatbot-voice-gateway -am test
```

预期：控制帧状态机测试通过。

## 任务 3：实现 WebSocket binary v1/v2/v3 音频帧解包

**文件：**
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiAudioFrame.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiMessageCodec.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiWebSocketHandler.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
- 测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/protocol/XiaozhiMessageCodecTest.java`

- [x] **步骤 1：编写 v1 解包测试**

```java
@Test
void shouldDecodeBinaryV1AsRawOpusPayload() {
    var payload = ByteBuffer.wrap(new byte[] {1, 2, 3});

    var frame = codec.decodeAudioFrame(1, payload);

    assertThat(frame.version()).isEqualTo(1);
    assertThat(frame.timestamp()).isZero();
    assertThat(frame.payload()).containsExactly(1, 2, 3);
}
```

- [x] **步骤 2：编写 v2 解包测试**

```java
@Test
void shouldDecodeBinaryV2Header() {
    var payload = ByteBuffer.allocate(16 + 3);
    payload.putShort((short) 2);
    payload.putShort((short) 0);
    payload.putInt(0);
    payload.putInt(1234);
    payload.putInt(3);
    payload.put(new byte[] {4, 5, 6});
    payload.flip();

    var frame = codec.decodeAudioFrame(2, payload);

    assertThat(frame.version()).isEqualTo(2);
    assertThat(frame.timestamp()).isEqualTo(1234);
    assertThat(frame.payload()).containsExactly(4, 5, 6);
}
```

- [x] **步骤 3：编写 v3 解包测试**

```java
@Test
void shouldDecodeBinaryV3Header() {
    var payload = ByteBuffer.allocate(4 + 2);
    payload.put((byte) 0);
    payload.put((byte) 0);
    payload.putShort((short) 2);
    payload.put(new byte[] {7, 8});
    payload.flip();

    var frame = codec.decodeAudioFrame(3, payload);

    assertThat(frame.version()).isEqualTo(3);
    assertThat(frame.timestamp()).isZero();
    assertThat(frame.payload()).containsExactly(7, 8);
}
```

- [x] **步骤 4：运行测试验证失败**

运行：

```bash
cd "/Users/jiangzhibin/workspace/chatbot-service-java"
mvn -pl chatbot-voice-gateway -am test
```

预期：缺少 `decodeAudioFrame` 和 `XiaozhiAudioFrame`。

- [x] **步骤 5：实现音频帧 record**

创建 `XiaozhiAudioFrame.java`：

```java
package com.jzb.chatbot.voice.protocol;

public record XiaozhiAudioFrame(
        int version,
        long timestamp,
        byte[] payload
) {
}
```

- [x] **步骤 6：实现 binary 解包**

在 `XiaozhiMessageCodec` 中加入：

```java
public XiaozhiAudioFrame decodeAudioFrame(int protocolVersion, ByteBuffer buffer) {
    var input = buffer.slice();
    if (protocolVersion == 2) {
        input.getShort();
        input.getShort();
        input.getInt();
        var timestamp = Integer.toUnsignedLong(input.getInt());
        var payloadSize = input.getInt();
        var payload = new byte[payloadSize];
        input.get(payload);
        return new XiaozhiAudioFrame(2, timestamp, payload);
    }
    if (protocolVersion == 3) {
        input.get();
        input.get();
        var payloadSize = Short.toUnsignedInt(input.getShort());
        var payload = new byte[payloadSize];
        input.get(payload);
        return new XiaozhiAudioFrame(3, 0, payload);
    }
    var payload = new byte[input.remaining()];
    input.get(payload);
    return new XiaozhiAudioFrame(1, 0, payload);
}
```

- [x] **步骤 7：将 Handler 二进制帧交给会话服务**

```java
@Override
protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
    sessionService.handleBinary(session, message.getPayload());
}
```

- [x] **步骤 8：在会话服务中保存监听期间音频帧**

```java
public void handleBinary(WebSocketSession webSocketSession, ByteBuffer payload) {
    var voiceSession = getSession(webSocketSession.getId());
    var frame = codec.decodeAudioFrame(voiceSession.protocolVersion(), payload);
    if (voiceSession.state() == XiaozhiVoiceSession.State.LISTENING) {
        voiceSession.addAudioFrame(frame);
    }
}
```

- [x] **步骤 9：运行测试验证通过**

运行：

```bash
cd "/Users/jiangzhibin/workspace/chatbot-service-java"
mvn -pl chatbot-voice-gateway -am test
```

预期：binary 解包测试通过。

## 任务 4：调整语音 Provider 接口并提供 Fake 实现

**文件：**
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/SpeechToTextClient.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TextToSpeechClient.java`
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/FakeSpeechToTextClient.java`
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/FakeTextToSpeechClient.java`
- 测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-speech-api/src/test/java/com/jzb/chatbot/speech/FakeSpeechClientTest.java`

- [x] **步骤 1：编写 Fake ASR 测试**

```java
@Test
void shouldReturnFixedTextForAnyAudioFrames() {
    var client = new FakeSpeechToTextClient();

    var text = client.transcribe(List.of(
            ByteBuffer.wrap(new byte[] {1, 2, 3})
    ));

    assertThat(text).isEqualTo("ping");
}
```

- [x] **步骤 2：编写 Fake TTS 测试**

```java
@Test
void shouldReturnDeterministicAudioFrames() {
    var client = new FakeTextToSpeechClient();

    var frames = client.synthesize("pong", new VoiceId("default"));

    assertThat(frames).hasSize(1);
    assertThat(frames.getFirst().remaining()).isGreaterThan(0);
}
```

- [x] **步骤 3：运行测试验证失败**

运行：

```bash
cd "/Users/jiangzhibin/workspace/chatbot-service-java"
mvn -pl chatbot-speech-api -am test
```

预期：缺少 Fake Provider，接口签名仍是单 ByteBuffer。

- [x] **步骤 4：调整 ASR 接口**

```java
public interface SpeechToTextClient {

    String transcribe(List<ByteBuffer> audioFrames);
}
```

- [x] **步骤 5：调整 TTS 接口**

```java
public interface TextToSpeechClient {

    List<ByteBuffer> synthesize(String text, VoiceId voiceId);
}
```

- [x] **步骤 6：实现 Fake ASR**

```java
package com.jzb.chatbot.speech;

import java.nio.ByteBuffer;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class FakeSpeechToTextClient implements SpeechToTextClient {

    @Override
    public String transcribe(List<ByteBuffer> audioFrames) {
        return "ping";
    }
}
```

- [x] **步骤 7：实现 Fake TTS**

```java
package com.jzb.chatbot.speech;

import com.jzb.chatbot.common.id.VoiceId;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class FakeTextToSpeechClient implements TextToSpeechClient {

    @Override
    public List<ByteBuffer> synthesize(String text, VoiceId voiceId) {
        return List.of(ByteBuffer.wrap(("fake-opus:" + text).getBytes(StandardCharsets.UTF_8)));
    }
}
```

- [x] **步骤 8：运行测试验证通过**

运行：

```bash
cd "/Users/jiangzhibin/workspace/chatbot-service-java"
mvn -pl chatbot-speech-api -am test
```

预期：Fake ASR/TTS 测试通过。

## 任务 5：实现服务端下行事件和最小语音闭环

**文件：**
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiServerEventFactory.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/pom.xml`
- 测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`

- [x] **步骤 1：补充 voice-gateway 依赖**

确认 `chatbot-voice-gateway/pom.xml` 已依赖：

```xml
<dependency>
    <groupId>com.jzb</groupId>
    <artifactId>chatbot-hermes-adapter</artifactId>
    <version>${project.version}</version>
</dependency>
<dependency>
    <groupId>com.jzb</groupId>
    <artifactId>chatbot-speech-api</artifactId>
    <version>${project.version}</version>
</dependency>
```

- [x] **步骤 2：编写服务端事件工厂测试**

```java
@Test
void shouldBuildTtsSentenceStartEvent() throws Exception {
    var factory = new XiaozhiServerEventFactory(new ObjectMapper());

    var json = factory.ttsSentenceStart("s1", "pong");

    assertThat(json).contains("\"type\":\"tts\"");
    assertThat(json).contains("\"state\":\"sentence_start\"");
    assertThat(json).contains("\"text\":\"pong\"");
}
```

- [x] **步骤 3：编写 listen.stop 触发闭环测试**

```java
@Test
void shouldSendSttHermesAndTtsEventsWhenListenStops() {
    var webSocketSession = new TestWebSocketSession("ws-session-1");
    var service = new XiaozhiVoiceSessionService(
            new XiaozhiMessageCodec(new ObjectMapper()),
            new FakeSpeechToTextClient(),
            new FakeHermesClient(),
            new FakeTextToSpeechClient(),
            new XiaozhiServerEventFactory(new ObjectMapper())
    );
    service.open(webSocketSession);
    service.handleText(webSocketSession, new XiaozhiClientMessage(
            "listen", "start", "manual", null, null, "ws-session-1", null
    ));

    service.handleText(webSocketSession, new XiaozhiClientMessage(
            "listen", "stop", null, null, null, "ws-session-1", null
    ));

    assertThat(webSocketSession.getSentMessages())
            .extracting(message -> message.getPayload().toString())
            .anyMatch(payload -> payload.contains("\"type\":\"stt\""))
            .anyMatch(payload -> payload.contains("\"type\":\"tts\"") && payload.contains("\"state\":\"start\""))
            .anyMatch(payload -> payload.contains("\"type\":\"tts\"") && payload.contains("\"state\":\"sentence_start\""))
            .anyMatch(payload -> payload.contains("\"type\":\"tts\"") && payload.contains("\"state\":\"stop\""));
}
```

- [x] **步骤 4：运行测试验证失败**

运行：

```bash
cd "/Users/jiangzhibin/workspace/chatbot-service-java"
mvn -pl chatbot-voice-gateway -am test
```

预期：缺少事件工厂和闭环编排。

- [x] **步骤 5：实现服务端事件工厂**

```java
package com.jzb.chatbot.voice.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class XiaozhiServerEventFactory {

    private final ObjectMapper objectMapper;

    public String stt(String sessionId, String text) throws JsonProcessingException {
        return objectMapper.createObjectNode()
                .put("session_id", sessionId)
                .put("type", "stt")
                .put("text", text)
                .toString();
    }

    public String llmEmotion(String sessionId, String emotion) {
        return objectMapper.createObjectNode()
                .put("session_id", sessionId)
                .put("type", "llm")
                .put("emotion", emotion)
                .toString();
    }

    public String ttsStart(String sessionId) {
        return ttsState(sessionId, "start");
    }

    public String ttsStop(String sessionId) {
        return ttsState(sessionId, "stop");
    }

    public String ttsSentenceStart(String sessionId, String text) {
        return objectMapper.createObjectNode()
                .put("session_id", sessionId)
                .put("type", "tts")
                .put("state", "sentence_start")
                .put("text", text)
                .toString();
    }

    private String ttsState(String sessionId, String state) {
        return objectMapper.createObjectNode()
                .put("session_id", sessionId)
                .put("type", "tts")
                .put("state", state)
                .toString();
    }
}
```

- [x] **步骤 6：在会话服务中编排 ASR -> Hermes -> TTS**

`listen.stop` 时调用：

```java
private void processTurn(WebSocketSession webSocketSession, XiaozhiVoiceSession voiceSession) throws IOException {
    var audioFrames = voiceSession.drainAudioFrames().stream()
            .map(frame -> ByteBuffer.wrap(frame.payload()))
            .toList();
    var userText = speechToTextClient.transcribe(audioFrames);
    webSocketSession.sendMessage(new TextMessage(eventFactory.stt(voiceSession.sessionId(), userText)));

    var response = hermesClient.chat(new HermesRequest(
            new DeviceId(webSocketSession.getId()),
            new ConversationId("conv-" + webSocketSession.getId()),
            userText
    ));
    var reply = response.text();

    voiceSession.markSpeaking();
    webSocketSession.sendMessage(new TextMessage(eventFactory.llmEmotion(voiceSession.sessionId(), "neutral")));
    webSocketSession.sendMessage(new TextMessage(eventFactory.ttsStart(voiceSession.sessionId())));
    webSocketSession.sendMessage(new TextMessage(eventFactory.ttsSentenceStart(voiceSession.sessionId(), reply)));
    for (var frame : textToSpeechClient.synthesize(reply, new VoiceId("default"))) {
        webSocketSession.sendMessage(new BinaryMessage(frame));
    }
    webSocketSession.sendMessage(new TextMessage(eventFactory.ttsStop(voiceSession.sessionId())));
    voiceSession.markIdle();
}
```

- [x] **步骤 7：运行测试验证通过**

运行：

```bash
cd "/Users/jiangzhibin/workspace/chatbot-service-java"
mvn -pl chatbot-voice-gateway -am test
```

预期：最小语音闭环测试通过。

## 任务 6：补充异常处理和联调文档

**文件：**
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/README.md`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/docs/architecture/hermes-chatbot-service-architecture.md`
- 测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiWebSocketHandlerTest.java`

- [x] **步骤 1：编写无效 JSON 关闭连接测试**

```java
@Test
void shouldCloseSessionWhenInvalidJsonReceived() throws Exception {
    var codec = new XiaozhiMessageCodec(new ObjectMapper());
    var sessionService = new XiaozhiVoiceSessionService(codec);
    var handler = new XiaozhiWebSocketHandler(codec, sessionService);
    var session = new TestWebSocketSession("ws-session-1");
    handler.afterConnectionEstablished(session);

    handler.handleMessage(session, new TextMessage("{bad-json"));

    assertThat(session.isOpen()).isFalse();
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
cd "/Users/jiangzhibin/workspace/chatbot-service-java"
mvn -pl chatbot-voice-gateway -am test
```

预期：无效 JSON 尚未关闭连接。

- [x] **步骤 3：在 Handler 中处理协议错误**

```java
@Override
protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    try {
        var clientMessage = codec.decodeText(message.getPayload());
        if ("hello".equals(clientMessage.type())) {
            var hello = codec.decodeClientHello(message.getPayload());
            sessionService.handleHello(session, hello);
            session.sendMessage(new TextMessage(codec.encodeServerHello(session.getId())));
            return;
        }
        sessionService.handleText(session, clientMessage);
    } catch (JsonProcessingException ex) {
        log.warn("invalid xiaozhi control frame, sessionId={}", session.getId(), ex);
        session.close(CloseStatus.BAD_DATA);
    }
}
```

- [x] **步骤 4：更新 README 联调说明**

在 `README.md` 增加：

```markdown
## 小智 ESP32 WebSocket 联调

服务端入口：

```text
ws://<server-host>:<server-port>/xiaozhi/v1
```

当前 MVP 范围：

- 支持设备 hello -> 服务端 hello。
- 支持 `listen.start`、`listen.stop`、`listen.detect`、`abort`。
- 支持 WebSocket binary v1/v2/v3 Opus 帧解包。
- 使用 Fake ASR/TTS 完成协议闭环。
- 不实现 Java 侧 MCP Server；Hermes 负责工具调用和编排。

固件侧需要配置：

- `websocket.url`
- `websocket.token`
- `websocket.version`
```

- [x] **步骤 5：更新架构文档 MCP 边界**

在 `docs/architecture/hermes-chatbot-service-architecture.md` 的 `chatbot-voice-gateway` 职责中明确：

```markdown
MCP 边界：

- MVP 不实现 Java 侧 MCP Server。
- 收到 `type=mcp` 只记录并忽略。
- 后续如需设备控制，仅实现 Hermes 与设备之间的薄透传桥。
```

- [x] **步骤 6：运行全量测试**

运行：

```bash
cd "/Users/jiangzhibin/workspace/chatbot-service-java"
mvn test
```

预期：全部测试通过。

## 验收标准

- WebSocket 连接建立后，服务端不会在设备 hello 前主动发送 hello。
- 设备发送 `type=hello` 后，服务端返回包含 `type=hello`、`transport=websocket`、`session_id`、`audio_params` 的 JSON。
- `audio_params.format=opus`、`sample_rate=16000`、`channels=1`、`frame_duration=60`。
- `listen.start` 进入 LISTENING 状态并清空上一轮音频。
- binary v1/v2/v3 音频帧能解包成统一的 `XiaozhiAudioFrame`。
- `listen.stop` 后触发 ASR -> Hermes -> TTS，服务端依次下发 `stt`、`llm`、`tts.start`、`tts.sentence_start`、binary audio、`tts.stop`。
- `abort` 能中止当前 speaking 状态并回到 idle。
- `type=mcp` 不触发 Java 工具调用逻辑。
- `mvn test` 通过。

## 风险和处理

- **固件协议字段漂移：** 以 `/Users/jiangzhibin/workspace/xiaozhi-esp32/main/protocols/websocket_protocol.cc` 和 `/Users/jiangzhibin/workspace/xiaozhi-esp32/main/application.cc` 为准，文档仅作为辅助。
- **Fake TTS 不是有效 Opus：** MVP 只验证服务端下发顺序和 WebSocket 行为；真机播放前必须替换成真实 Opus TTS 或明确可播放的测试音频。
- **Spring WebSocket Handler 过胖：** Handler 只保留入口分发，状态和编排放进 `XiaozhiVoiceSessionService`。
- **MCP 范围膨胀：** 当前不实现 Java MCP Server。需要设备控制时，只做 Hermes 到小智设备的薄透传桥。
- **真实 ASR/TTS 延迟：** 后续接真实 Provider 时再引入异步流式处理；本计划不提前引入复杂队列。

## 需要进一步明确的问题

### 问题 1：真实 TTS Provider 选择

**推荐方案：**

- 方案 A：先继续 Fake TTS，只做协议和状态机联调。优点是最快、风险最低；缺点是真机不能听到有效声音。
- 方案 B：直接接一个能输出 Opus 的 TTS Provider。优点是真机体验更完整；缺点是会把协议联调和厂商接入耦合。

**等待用户选择：**

```text
请选择您偏好的方案，或提供其他建议：
[ ] 方案 A
[ ] 方案 B
[ ] 其他方案：________
```

### 问题 2：真实 ASR Provider 选择

**推荐方案：**

- 方案 A：先继续 Fake ASR，固定返回 `ping`。优点是闭环测试稳定；缺点是不验证真实语音识别。
- 方案 B：接真实 ASR，并确认输入要求是 Opus 还是 PCM。优点是更接近最终链路；缺点是需要处理转码、采样率和网络错误。

**等待用户选择：**

```text
请选择您偏好的方案，或提供其他建议：
[ ] 方案 A
[ ] 方案 B
[ ] 其他方案：________
```

## 用户反馈区域

请在此区域补充您对整体规划的意见和建议：

```text
用户补充内容：

---

---

---
```
