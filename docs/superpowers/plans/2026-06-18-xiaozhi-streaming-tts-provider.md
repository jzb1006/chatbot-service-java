# 小智流式 TTS Provider 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将小智后端 TTS 从“等 Hermes 完整输出后逐句同步合成”升级为“Hermes 分句产出后立即喂入流式 TTS Provider，音频帧边返回边下发设备”。

**架构：** 新增 `StreamingTextToSpeechClient` 边界，腾讯云实现使用 `wss://tts.cloud.tencent.com/stream_wsv2` 的流式文本语音合成接口。腾讯云返回的 PCM binary 只在 `chatbot-speech-api` 内部可见，provider 必须增量编码为小智协议需要的 Opus 16k mono 60ms 帧后再回调 voice-gateway。`XiaozhiVoiceSessionService` 在 Hermes 流解析过程中把完整句子即时写入流式 TTS 会话；未启用流式 provider 时继续走现有同步 TTS fallback。

**技术栈：** Java 21、Spring Boot 3.4、Spring WebSocket、Java `java.net.http.WebSocket`、Maven 多模块、JUnit 5、AssertJ、Jackson、Concentus Opus、腾讯云流式文本语音合成、Hermes SSE。

---

## 关键依据

- 腾讯云流式文本语音合成最近更新时间为 2026-03-27，接口地址是 `wss://tts.cloud.tencent.com/stream_wsv2?{请求参数}`。
- 该接口采用 WebSocket，支持流式文本输入；音频通过 binary 帧返回，文本状态通过 JSON text 帧返回。
- 客户端需等待 `ready=1` 后发送 `ACTION_SYNTHESIS` 文本；全部文本发送完后发送 `ACTION_COMPLETE`；收到 `final=1` 后关闭 WebSocket。
- 腾讯云流式接口错误码包含 `10006`（输入文本包含 SSML）、`10007`（输入文本超过最大长度限制）、`10008`（流式文本通道已关闭）、`10009`（流式输入文本超时），实现必须把这些情况作为可测试的 provider 契约，而不是只写日志。
- 当前仓库已有同步 TTS 主线：`Hermes stream -> XiaozhiHermesStreamTextExtractor -> XiaozhiSentenceSegmenter -> TextToSpeechClient.synthesize(...) -> XiaozhiTtsPlayback -> WebSocket binary`。
- 当前仓库已有配置约定：`CHATBOT_VOICE_DEFAULT_VOICE_ID`、`CHATBOT_VOICE_TTS_DEFAULT_SPEED`、`TENCENT_CLOUD_APP_ID`、`TENCENT_CLOUD_SECRET_ID`、`TENCENT_CLOUD_SECRET_KEY`、`TENCENT_CLOUD_TTS_VOICE_TYPE`。本计划不得新增另一套非 `TENCENT_CLOUD_` 前缀的腾讯 TTS 环境变量命名。
- 本计划不包含 git commit、push 或分支操作；实现完成后需由用户明确确认再处理提交。

## 成功标准

- Hermes 第一条完整句子产生后，后端不再等待 Hermes 全部结束，而是立即进入流式 TTS 合成。
- 小智 WebSocket 一轮回答仍只发送一次 `tts.start` 和一次 `tts.stop`。
- 每个被送入 TTS 的句子仍对应一个小智 `tts.sentence_start`，便于固件和调试台观察。
- `StreamingTextToSpeechListener.onAudioFrame(...)` 对 voice-gateway 暴露的必须是已编码 Opus 帧；腾讯云 PCM 不能泄漏到 voice-gateway。
- 腾讯云 binary PCM 音频被增量转为 Opus 16k mono 60ms 帧，不再等待完整句子音频全部返回；同一流式会话内编码器需要保留 Opus 编码状态。
- `abort`、`session.new`、连接关闭时能关闭腾讯云 TTS WebSocket，并且不会继续下发旧轮次音频；runtime 必须同时管理 playback 和 active streaming session。
- 保留现有同步 `TextToSpeechClient` 作为 fallback，`provider=tencent-streaming` 时同步腾讯 TTS 仍可作为流式失败 fallback，fake provider 和现有测试不被打断。
- `notifyDevice(...)` 主动播报和提醒回推路径要么复用流式 runtime，要么明确走同步 fallback；不能出现对话流式、提醒仍不可用的配置分裂。
- 验证命令使用 `mvn -pl chatbot-speech-api,chatbot-voice-gateway -am test`，预期 `BUILD SUCCESS`。

## 文件结构

- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/StreamingTextToSpeechClient.java`
  - 职责：流式 TTS provider 入口，打开一次可写文本、可回调音频帧的 TTS 会话。
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/StreamingTextToSpeechSession.java`
  - 职责：抽象 provider 会话生命周期，包含 `sendText`、`complete`、`cancel`、`awaitFinal`。
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/StreamingTextToSpeechListener.java`
  - 职责：接收 provider ready、已编码 Opus 音频帧、completed、failed 回调。
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/StreamingPcmToOpusEncoder.java`
  - 职责：把腾讯云流式 PCM binary 增量切成 16k mono 60ms PCM，再编码成 Opus 帧。
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentCloudStreamingTextToSpeechClient.java`
  - 职责：腾讯云 `stream_wsv2` WebSocket 实现。
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentStreamingTextToSpeechConfig.java`
  - 职责：腾讯云流式 TTS 配置，包含 appId、secretId、secretKey、voiceType、codec、sampleRate、timeout、speed、volume。
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentStreamingTtsSigner.java`
  - 职责：生成 `TextToStreamAudioWSv2` 请求 URL 和 Signature。
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentStreamingTtsTransport.java`
  - 职责：隔离 Java WebSocket，便于单测用 fake transport。
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentStreamingTtsTextGuard.java`
  - 职责：在发送 `ACTION_SYNTHESIS` 前拒绝 SSML 并限制单次文本长度，避免触发腾讯云 `10006`/`10007`。
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/tts/XiaozhiTtsRuntime.java`
  - 职责：新增流式播放入口，管理一轮 `tts.start`、句子写入、音频回调、`tts.stop`、active streaming session 和同步 fallback。
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/tts/XiaozhiStreamingTtsRequest.java`
  - 职责：封装流式 TTS 播放请求，字段与 `XiaozhiTtsRequest` 对齐，包含 `expectedPlaybackGeneration`。
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/tts/XiaozhiTtsSentenceSink.java`
  - 职责：向流式 runtime 写入已分句文本并标记文本输入完成。
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiTtsPlayback.java`
  - 职责：新增可复用的流式 `startSentence` 和 `playFrame`，让音频帧可以边到边发。
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
  - 职责：流式 provider 可用时把 Hermes 分句产出即时写入流式 TTS；不可用时保留现有攒句后同步播放路径；主动播报路径与对话路径保持同一 provider 策略。
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeans.java`
  - 职责：按配置启用流式腾讯云 TTS；未启用时继续使用同步 TTS。
- 修改：`chatbot-bootstrap/src/main/resources/application.yml`
  - 职责：新增流式 TTS 配置项默认值。
- 修改：`deploy/chatbot-service.env.example`
  - 职责：新增部署环境变量示例。
- 修改：`scripts/xiaozhi_ws_smoke.py`
  - 职责：输出首个 `tts.sentence_start`、首个 binary、`tts.stop` 的相对耗时。

## 任务 1：定义流式 TTS Provider 边界

**文件：**
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/StreamingTextToSpeechClient.java`
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/StreamingTextToSpeechSession.java`
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/StreamingTextToSpeechListener.java`
- 测试：`chatbot-speech-api/src/test/java/com/jzb/chatbot/speech/StreamingTextToSpeechClientContractTest.java`

- [x] **步骤 1：编写失败的接口契约测试**

```java
package com.jzb.chatbot.speech;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StreamingTextToSpeechClientContractTest {

    @Test
    void shouldExposeMinimalStreamingSessionContractWithOpusFrames() {
        var listener = new RecordingListener();
        StreamingTextToSpeechClient client = (options, callback) -> new InMemorySession(callback);

        try (var session = client.open(TextToSpeechOptions.defaults(), listener)) {
            session.sendText("第一句。");
            session.complete();
            assertThat(session.awaitFinal(Duration.ofMillis(10))).isTrue();
        }

        assertThat(listener.ready).isTrue();
        assertThat(listener.frames).hasSize(1);
        assertThat(listener.frames.getFirst().remaining()).isGreaterThan(0);
        assertThat(listener.completed).isTrue();
    }

    private static final class InMemorySession implements StreamingTextToSpeechSession {
        private final StreamingTextToSpeechListener listener;
        private boolean completed;

        private InMemorySession(StreamingTextToSpeechListener listener) {
            this.listener = listener;
            this.listener.onReady();
        }

        @Override
        public void sendText(String text) {
            // listener 接收的是 provider 已经编码完成的小智 Opus 帧，不是腾讯云 PCM。
            listener.onAudioFrame(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        }

        @Override
        public void complete() {
            completed = true;
            listener.onCompleted();
        }

        @Override
        public void cancel() {
            completed = true;
        }

        @Override
        public boolean awaitFinal(Duration timeout) {
            return completed;
        }

        @Override
        public void close() {
            cancel();
        }
    }

    private static final class RecordingListener implements StreamingTextToSpeechListener {
        private boolean ready;
        private boolean completed;
        private final List<ByteBuffer> frames = new ArrayList<>();

        @Override
        public void onReady() {
            ready = true;
        }

        @Override
        public void onAudioFrame(ByteBuffer frame) {
            frames.add(frame);
        }

        @Override
        public void onCompleted() {
            completed = true;
        }

        @Override
        public void onFailed(RuntimeException exception) {
            throw exception;
        }
    }
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
mvn -pl chatbot-speech-api -am -Dtest=StreamingTextToSpeechClientContractTest test
```

预期：编译失败，缺少 `StreamingTextToSpeechClient`、`StreamingTextToSpeechSession`、`StreamingTextToSpeechListener`。

- [x] **步骤 3：实现最小接口**

```java
package com.jzb.chatbot.speech;

public interface StreamingTextToSpeechClient {
    StreamingTextToSpeechSession open(
            TextToSpeechOptions options,
            StreamingTextToSpeechListener listener
    );
}
```

```java
package com.jzb.chatbot.speech;

import java.time.Duration;

public interface StreamingTextToSpeechSession extends AutoCloseable {
    void sendText(String text);
    void complete();
    void cancel();
    boolean awaitFinal(Duration timeout);

    @Override
    void close();
}
```

```java
package com.jzb.chatbot.speech;

import java.nio.ByteBuffer;

public interface StreamingTextToSpeechListener {
    void onReady();

    /**
     * 接收已编码的小智 Opus 音频帧。
     *
     * @param frame Opus 16k mono 60ms frame
     */
    void onAudioFrame(ByteBuffer frame);

    void onCompleted();

    void onFailed(RuntimeException exception);
}
```

- [x] **步骤 4：运行测试验证通过**

运行：

```bash
mvn -pl chatbot-speech-api -am -Dtest=StreamingTextToSpeechClientContractTest test
```

预期：`BUILD SUCCESS`。

## 任务 2：实现流式 PCM 到 Opus 增量编码

**文件：**
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/StreamingPcmToOpusEncoder.java`
- 测试：`chatbot-speech-api/src/test/java/com/jzb/chatbot/speech/StreamingPcmToOpusEncoderTest.java`

- [x] **步骤 1：编写失败测试**

```java
package com.jzb.chatbot.speech;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class StreamingPcmToOpusEncoderTest {

    @Test
    void shouldEmitOneOpusFrameWhenSixtyMillisecondsPcmArrives() {
        var encoder = new StreamingPcmToOpusEncoder(16000, 60);
        var pcm = ByteBuffer.allocate(960 * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (var index = 0; index < 960; index++) {
            pcm.putShort((short) 0);
        }

        var frames = encoder.accept(pcm.array());

        assertThat(frames).hasSize(1);
        assertThat(frames.getFirst().remaining()).isGreaterThan(0);
        assertThat(encoder.flush()).isEmpty();
    }

    @Test
    void shouldBufferPartialPcmUntilFrameIsComplete() {
        var encoder = new StreamingPcmToOpusEncoder(16000, 60);
        var halfFrame = new byte[480 * Short.BYTES];

        assertThat(encoder.accept(halfFrame)).isEmpty();
        assertThat(encoder.accept(halfFrame)).hasSize(1);
    }

    @Test
    void shouldPadRemainingPcmWhenFlushed() {
        var encoder = new StreamingPcmToOpusEncoder(16000, 60);
        var partial = new byte[120 * Short.BYTES];

        assertThat(encoder.accept(partial)).isEmpty();
        assertThat(encoder.flush()).hasSize(1);
        assertThat(encoder.flush()).isEmpty();
    }
}
```

- [x] **步骤 2：运行测试验证失败**

```bash
mvn -pl chatbot-speech-api -am -Dtest=StreamingPcmToOpusEncoderTest test
```

预期：编译失败，缺少 `StreamingPcmToOpusEncoder`。

- [x] **步骤 3：实现编码器**

实现要求：

```text
frameBytes = sampleRate * frameDurationMs / 1000 * 2
accept(byte[]) 只编码完整 60ms PCM
flush() 对剩余 PCM 右侧补零并编码一次
底层复用已有 PcmToOpusEncoder.encode(...)
```

核心结构：

```java
public final class StreamingPcmToOpusEncoder {
    private final int frameBytes;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    public StreamingPcmToOpusEncoder(int sampleRate, int frameDurationMs) {
        this.frameBytes = sampleRate * frameDurationMs / 1000 * Short.BYTES;
    }

    public List<ByteBuffer> accept(byte[] pcm) {
        pending.writeBytes(pcm);
        return drainCompleteFrames(false);
    }

    public List<ByteBuffer> flush() {
        return drainCompleteFrames(true);
    }
}
```

- [x] **步骤 4：运行测试验证通过**

```bash
mvn -pl chatbot-speech-api -am -Dtest=StreamingPcmToOpusEncoderTest test
```

预期：`BUILD SUCCESS`。

## 任务 3：实现腾讯云流式 TTS 签名和请求 URL

**文件：**
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentStreamingTextToSpeechConfig.java`
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentStreamingTtsSigner.java`
- 测试：`chatbot-speech-api/src/test/java/com/jzb/chatbot/speech/TencentStreamingTtsSignerTest.java`

- [x] **步骤 1：编写失败测试**

```java
package com.jzb.chatbot.speech;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TencentStreamingTtsSignerTest {

    @Test
    void shouldBuildSignedStreamWebSocketUrl() {
        var config = new TencentStreamingTextToSpeechConfig(
                1300000000,
                "secret-id",
                "secret-key",
                "101001",
                "pcm",
                16000,
                0.0,
                0.0,
                java.time.Duration.ofSeconds(30)
        );
        var clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);
        var signer = new TencentStreamingTtsSigner(config, clock);

        var uri = signer.sign("session-1");

        assertThat(uri.toString()).startsWith("wss://tts.cloud.tencent.com/stream_wsv2?");
        assertThat(uri.getQuery()).contains(
                "Action=TextToStreamAudioWSv2",
                "AppId=1300000000",
                "Codec=pcm",
                "SampleRate=16000",
                "SecretId=secret-id",
                "SessionId=session-1",
                "VoiceType=101001",
                "Speed=0.0",
                "Signature="
        );
    }
}
```

- [x] **步骤 2：运行测试验证失败**

```bash
mvn -pl chatbot-speech-api -am -Dtest=TencentStreamingTtsSignerTest test
```

预期：编译失败，缺少配置和签名类。

- [x] **步骤 3：实现签名**

实现要求：

```text
Action 固定 TextToStreamAudioWSv2
域名固定 tts.cloud.tencent.com/stream_wsv2
方法固定 GET
参数按字典序排序后参与 HMAC-SHA1 + Base64
Signature 必须 URL encode
Expired = Timestamp + 3600
Codec 第一阶段固定 pcm
SampleRate 第一阶段固定 16000
```

腾讯云 `Speed` 入参范围是 `[-2, 6]`，当前项目 `TextToSpeechOptions.speed()` 是倍速语义；第一阶段映射：

```text
0.6 -> -2
0.8 -> -1
1.0 -> 0
1.2 -> 1
1.5 -> 2
2.5 -> 6
其他值保留两位小数并钳制在 [-2, 6]
```

签名测试需补充 `shouldMapTextToSpeechSpeedToTencentSpeed()`：

```java
@Test
void shouldMapTextToSpeechSpeedToTencentSpeed() {
    assertThat(TencentStreamingTtsSigner.toTencentSpeed(0.6)).isEqualTo(-2.0);
    assertThat(TencentStreamingTtsSigner.toTencentSpeed(0.8)).isEqualTo(-1.0);
    assertThat(TencentStreamingTtsSigner.toTencentSpeed(1.0)).isEqualTo(0.0);
    assertThat(TencentStreamingTtsSigner.toTencentSpeed(1.2)).isEqualTo(1.0);
    assertThat(TencentStreamingTtsSigner.toTencentSpeed(1.5)).isEqualTo(2.0);
    assertThat(TencentStreamingTtsSigner.toTencentSpeed(9.0)).isEqualTo(6.0);
}
```

- [x] **步骤 4：运行测试验证通过**

```bash
mvn -pl chatbot-speech-api -am -Dtest=TencentStreamingTtsSignerTest test
```

预期：`BUILD SUCCESS`。

## 任务 4：实现腾讯云流式 TTS WebSocket 会话

**文件：**
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentStreamingTtsTransport.java`
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentStreamingTtsTextGuard.java`
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentCloudStreamingTextToSpeechClient.java`
- 测试：`chatbot-speech-api/src/test/java/com/jzb/chatbot/speech/TencentCloudStreamingTextToSpeechClientTest.java`

- [x] **步骤 1：编写失败测试**

```java
package com.jzb.chatbot.speech;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TencentCloudStreamingTextToSpeechClientTest {

    @Test
    void shouldWaitReadyThenSendSynthesisAndCompleteCommands() {
        var transport = new FakeTencentStreamingTtsTransport();
        var client = new TencentCloudStreamingTextToSpeechClient(
                sessionId -> java.net.URI.create("wss://tts.cloud.tencent.com/stream_wsv2?SessionId=" + sessionId),
                transport
        );
        var listener = new RecordingListener();

        try (var session = client.open(TextToSpeechOptions.defaults(), listener)) {
            transport.emitText("{\"code\":0,\"ready\":1,\"final\":0}");
            session.sendText("第一句。");
            session.complete();
            transport.emitText("{\"code\":0,\"ready\":0,\"final\":1}");

            assertThat(session.awaitFinal(Duration.ofMillis(100))).isTrue();
        }

        assertThat(listener.ready).isTrue();
        assertThat(listener.completed).isTrue();
        assertThat(transport.sentText).anySatisfy(payload -> assertThat(payload)
                .contains("\"action\":\"ACTION_SYNTHESIS\"", "\"data\":\"第一句。\""));
        assertThat(transport.sentText).anySatisfy(payload -> assertThat(payload)
                .contains("\"action\":\"ACTION_COMPLETE\""));
    }

    @Test
    void shouldConvertBinaryPcmToOpusFrames() {
        var transport = new FakeTencentStreamingTtsTransport();
        var client = new TencentCloudStreamingTextToSpeechClient(
                sessionId -> java.net.URI.create("wss://tts.cloud.tencent.com/stream_wsv2?SessionId=" + sessionId),
                transport
        );
        var listener = new RecordingListener();

        try (var ignored = client.open(TextToSpeechOptions.defaults(), listener)) {
            transport.emitText("{\"code\":0,\"ready\":1,\"final\":0}");
            transport.emitBinary(ByteBuffer.allocate(960 * Short.BYTES));
        }

        assertThat(listener.frames).hasSize(1);
    }

    @Test
    void shouldFailOnNonZeroTencentCode() {
        var transport = new FakeTencentStreamingTtsTransport();
        var client = new TencentCloudStreamingTextToSpeechClient(
                sessionId -> java.net.URI.create("wss://tts.cloud.tencent.com/stream_wsv2?SessionId=" + sessionId),
                transport
        );
        var listener = new RecordingListener();

        try (var ignored = client.open(TextToSpeechOptions.defaults(), listener)) {
            transport.emitText("{\"code\":10001,\"message\":\"参数不合法\"}");
        }

        assertThat(listener.failure).hasMessageContaining("10001");
    }

    @Test
    void shouldRejectSsmlBeforeSendingTextToTencent() {
        var transport = new FakeTencentStreamingTtsTransport();
        var client = new TencentCloudStreamingTextToSpeechClient(
                sessionId -> java.net.URI.create("wss://tts.cloud.tencent.com/stream_wsv2?SessionId=" + sessionId),
                transport
        );
        var listener = new RecordingListener();

        try (var session = client.open(TextToSpeechOptions.defaults(), listener)) {
            transport.emitText("{\"code\":0,\"ready\":1,\"final\":0}");
            session.sendText("<speak>第一句。</speak>");
        }

        assertThat(listener.failure).hasMessageContaining("SSML");
        assertThat(transport.sentText).noneSatisfy(payload -> assertThat(payload)
                .contains("\"action\":\"ACTION_SYNTHESIS\""));
    }

    @Test
    void shouldRejectOversizedTextBeforeSendingTextToTencent() {
        var transport = new FakeTencentStreamingTtsTransport();
        var client = new TencentCloudStreamingTextToSpeechClient(
                sessionId -> java.net.URI.create("wss://tts.cloud.tencent.com/stream_wsv2?SessionId=" + sessionId),
                transport,
                new TencentStreamingTtsTextGuard(8)
        );
        var listener = new RecordingListener();

        try (var session = client.open(TextToSpeechOptions.defaults(), listener)) {
            transport.emitText("{\"code\":0,\"ready\":1,\"final\":0}");
            session.sendText("这句话超过八个字符。");
        }

        assertThat(listener.failure).hasMessageContaining("too long");
        assertThat(transport.sentText).noneSatisfy(payload -> assertThat(payload)
                .contains("\"action\":\"ACTION_SYNTHESIS\""));
    }
}
```

- [x] **步骤 2：运行测试验证失败**

```bash
mvn -pl chatbot-speech-api -am -Dtest=TencentCloudStreamingTextToSpeechClientTest test
```

预期：编译失败，缺少腾讯云流式 client 和 fake transport 依赖类型。

- [x] **步骤 3：实现 client 行为**

实现要求：

```text
open() 建立上游 WebSocket，生成一次 SessionId。
收到 ready=1 后调用 listener.onReady()。
sendText(text) 先通过 TencentStreamingTtsTextGuard 校验 SSML 和单次文本长度，再发送 {"session_id":..., "message_id":..., "action":"ACTION_SYNTHESIS", "data": text}。
complete() 发送 ACTION_COMPLETE。
收到 binary PCM 时交给 StreamingPcmToOpusEncoder.accept(...)，逐帧回调 listener.onAudioFrame(...)。
收到 final=1 时 flush 编码器、listener.onCompleted()、关闭上游 WebSocket。
heartbeat=1 只忽略。
code != 0 时 listener.onFailed(...) 并关闭上游 WebSocket；错误消息保留 code、message、request_id，便于排查 10006/10007/10008/10009。
cancel() 发送 ACTION_RESET 后关闭上游 WebSocket。
close() 必须幂等，重复 close/cancel/complete 不得重复关闭或重复回调 completed/failed。
```

- [x] **步骤 4：运行测试验证通过**

```bash
mvn -pl chatbot-speech-api -am -Dtest=TencentCloudStreamingTextToSpeechClientTest test
```

预期：`BUILD SUCCESS`。

## 任务 5：让 XiaozhiTtsPlayback 支持流式音频帧下发

**文件：**
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiTtsPlayback.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiTtsRuntimeTest.java`

- [x] **步骤 1：编写失败测试**

在 `XiaozhiTtsRuntimeTest` 增加：

```java
@Test
void shouldSendStreamingFramesAfterSentenceStart() throws Exception {
    var runtime = newRuntimeWithStreamingTts(new PushStreamingTextToSpeechClient());
    var session = openSession();
    var voiceSession = new XiaozhiVoiceSession(session.getId());

    var thread = Thread.startVirtualThread(() -> runtime.playStreaming(new XiaozhiStreamingTtsRequest(
            session,
            voiceSession,
            TextToSpeechOptions.defaults(),
            () -> false
    ), sentenceSink -> {
        sentenceSink.accept("第一句内容。");
        sentenceSink.complete();
    }));
    thread.join(2_000L);

    assertThat(thread.isAlive()).isFalse();
    assertThat(sentenceStartTexts(session)).containsExactly("第一句内容。");
    assertThat(binaryMessages(session)).isNotEmpty();
    assertThat(textPayloads(session))
            .filteredOn(payload -> payload.contains("\"type\":\"tts\"") && payload.contains("\"state\":\"stop\""))
            .hasSize(1);
}
```

- [x] **步骤 2：运行测试验证失败**

```bash
mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiTtsRuntimeTest#shouldSendStreamingFramesAfterSentenceStart test
```

预期：编译失败，缺少 `playStreaming`、`XiaozhiStreamingTtsRequest` 或 playback 流式方法。

- [x] **步骤 3：提取播放帧方法**

在 `XiaozhiTtsPlayback` 新增：

```java
public boolean startSentence(String sentence) throws IOException {
    if (cancelled()) {
        return false;
    }
    if (sentFrames > 0) {
        playPosition += SENTENCE_GAP_NS;
        waitForFrameTime();
    }
    if (cancelled()) {
        return false;
    }
    sendText(eventFactory.ttsSentenceStart(voiceSession.sessionId(), sentence));
    startedSentences++;
    return true;
}

public boolean playFrame(ByteBuffer frame) throws IOException {
    if (cancelled() || frame == null || !frame.hasRemaining()) {
        return false;
    }
    waitForFrameTime();
    if (cancelled()) {
        return false;
    }
    webSocketSession.sendMessage(new BinaryMessage(
            codec.encodeAudioFrame(voiceSession.protocolVersion(), 0, frame)
    ));
    sentFrames++;
    playPosition += OPUS_FRAME_SEND_INTERVAL_NS;
    return true;
}
```

然后让现有 `playSentence(...)` 复用 `startSentence(...)` 和 `playFrame(...)`，保持旧测试行为不变。

- [x] **步骤 4：运行相关测试**

```bash
mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiTtsRuntimeTest test
```

预期：`BUILD SUCCESS`。

## 任务 6：新增 Xiaozhi 流式 TTS Runtime

**文件：**
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/tts/XiaozhiStreamingTtsRequest.java`
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/tts/XiaozhiTtsSentenceSink.java`
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/tts/XiaozhiTtsRuntime.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiTtsRuntimeTest.java`

- [x] **步骤 1：编写失败测试**

增加取消场景：

```java
@Test
void shouldCancelStreamingTtsSessionWhenRuntimeIsCancelled() throws Exception {
    var streamingClient = new BlockingStreamingTextToSpeechClient();
    var runtime = newRuntimeWithStreamingTts(streamingClient);
    var session = new TimingWebSocketSession("ws-session-1");
    var voiceSession = new XiaozhiVoiceSession(session.getId());

    var thread = Thread.startVirtualThread(() -> runtime.playStreaming(new XiaozhiStreamingTtsRequest(
            session,
            voiceSession,
            TextToSpeechOptions.defaults(),
            () -> false
    ), sentenceSink -> {
        sentenceSink.accept("第一句内容。");
        streamingClient.awaitFirstText();
    }));

    assertThat(streamingClient.awaitFirstText()).isTrue();
    runtime.cancel(session.getId());
    thread.join(2_000L);

    assertThat(thread.isAlive()).isFalse();
    assertThat(streamingClient.cancelled()).isTrue();
    assertThat(session.stopSentAt()).isPositive();
}

@Test
void shouldFallbackToSynchronousTtsWhenStreamingProviderFailsBeforeAudio() {
    var streamingClient = new FailingStreamingTextToSpeechClient();
    var fallbackClient = new RecordingTextToSpeechClient();
    var runtime = newRuntimeWithStreamingTts(streamingClient, fallbackClient);
    var session = openSession();
    var voiceSession = new XiaozhiVoiceSession(session.getId());

    var result = runtime.playStreaming(new XiaozhiStreamingTtsRequest(
            session,
            voiceSession,
            TextToSpeechOptions.defaults(),
            () -> false
    ), sentenceSink -> {
        sentenceSink.accept("第一句内容。");
        sentenceSink.complete();
    });

    assertThat(result.played()).isTrue();
    assertThat(fallbackClient.texts()).containsExactly("第一句内容。");
    assertThat(sentenceStartTexts(session)).containsExactly("第一句内容。");
    assertThat(binaryMessages(session)).isNotEmpty();
}
```

测试 helper 约束：

```text
newRuntimeWithStreamingTts(streamingClient) 使用 new FakeTextToSpeechClient() 作为同步 fallback。
newRuntimeWithStreamingTts(streamingClient, fallbackClient) 使用同一个 codec/eventFactory 构造 XiaozhiTtsRuntime。
BlockingStreamingTextToSpeechClient 记录 sendText 文本，awaitFirstText() 返回 true 后保持 awaitFinal 阻塞，cancel() 后释放。
FailingStreamingTextToSpeechClient 在 sendText 前或 sendText 时触发 listener.onFailed(...)，且不发送任何 audio frame。
```

- [x] **步骤 2：运行测试验证失败**

```bash
mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiTtsRuntimeTest#shouldCancelStreamingTtsSessionWhenRuntimeIsCancelled test
```

预期：编译失败。

- [x] **步骤 3：实现流式 runtime**

实现形态：

```java
public XiaozhiTtsResult playStreaming(
        XiaozhiStreamingTtsRequest request,
        Consumer<XiaozhiTtsSentenceSink> sentenceProducer
) {
    // 1. beginRuntimePlayback
    // 2. send llm emotion + tts.start
    // 3. open StreamingTextToSpeechSession
    // 4. sentenceProducer 每 accept 一句：
    //    - playback.startSentence(sentence)
    //    - streamingSession.sendText(sentence)
    // 5. listener.onAudioFrame(frame) -> playback.playFrame(frame)
    // 6. producer 完成 -> streamingSession.complete()
    // 7. awaitFinal 后 send tts.stop
    // 8. streaming 未产生任何 audio 前失败时，使用同步 TextToSpeechClient fallback 播放已接收句子
    // 9. finally 清理 activePlaybacks / activeStreamingSessions / voiceSession playback
}
```

约束：

```text
XiaozhiTtsRuntime 构造函数新增 StreamingTextToSpeechClient 参数；为空或 fake 时 playStreaming 直接走同步 play fallback。
新增 activeStreamingSessions: Map<String, StreamingTextToSpeechSession>，key 与 activePlaybacks 一致。
onAudioFrame 必须串行发送到 XiaozhiTtsPlayback，避免 WebSocketSession 并发 send。
onAudioFrame 入参已经是 Opus 帧，不能再调用 PcmToOpusEncoder。
sentenceProducer 抛异常时 cancel 上游 TTS session。
cancel(sessionId) 必须同时 cancel playback 和 streaming session。
streaming 失败但已经向设备发送过任意 binary 时，不允许同步 fallback，避免重复朗读；只发送一次 tts.stop 并返回 failed/cancelled。
tts.stop 仍只发一次。
```

- [x] **步骤 4：运行 runtime 测试**

```bash
mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiTtsRuntimeTest test
```

预期：`BUILD SUCCESS`。

## 任务 7：把 Hermes 流改成边分句边喂流式 TTS

**文件：**
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`

- [x] **步骤 1：编写失败测试**

目标：证明第一句 TTS 在 Hermes 流未结束前已经启动。

```java
@Test
void shouldStartStreamingTtsBeforeHermesStreamCompletes() {
    var hermes = new BlockingHermesClient(List.of(
            "{\"type\":\"response.output_text.delta\",\"delta\":\"第一句内容。\"}",
            "{\"type\":\"response.output_text.delta\",\"delta\":\"第二句稍后。\"}"
    ));
    var streamingTts = new RecordingStreamingTextToSpeechClient();
    var eventFactory = new XiaozhiServerEventFactory(new ObjectMapper());
    var runtime = new XiaozhiTtsRuntime(
            new FakeTextToSpeechClient(),
            streamingTts,
            codec,
            eventFactory
    );
    var service = newService(new RecordingSpeechToTextClient(), hermes, runtime, eventFactory);
    var session = openSession(service);

    runSingleTurnAsyncUntilHermesBlocks(service, session);

    assertThat(streamingTts.texts()).containsExactly("第一句内容。");
    hermes.release();
    assertThat(awaitIdle(service, session)).isTrue();
    assertThat(streamingTts.texts()).containsExactly("第一句内容。", "第二句稍后。");
}
```

- [x] **步骤 2：运行测试验证失败**

```bash
mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceSessionServiceTest#shouldStartStreamingTtsBeforeHermesStreamCompletes test
```

预期：失败，当前实现只有 Hermes 完整结束后才调用 TTS。

- [x] **步骤 3：改造 `streamChatAndSpeak`**

核心改造：

```java
var playbackResult = ttsRuntime.playStreaming(request, sentenceSink -> {
    try (var chunks = hermesClient.streamChat(...)) {
        for (var chunk : (Iterable<String>) chunks::iterator) {
            if (turnCancelled(webSocketSession, voiceSession, turnGeneration) || !turnGuard.active()) {
                throw new XiaozhiTtsTurnCancelledException();
            }
            for (var event : eventExtractor.accept(chunk)) {
                var confirmationText = handleHermesAgentEvent(voiceSession, turnGuard, event);
                if (reminderConfirmationText == null && confirmationText != null && !confirmationText.isBlank()) {
                    reminderConfirmationText = confirmationText;
                }
            }
            for (var text : extractor.accept(chunk)) {
                reply.append(text);
                for (var sentence : segmenter.accept(text)) {
                    sentenceSink.accept(sentence);
                }
            }
        }
    }
    for (var text : extractor.flush()) {
        reply.append(text);
        for (var sentence : segmenter.accept(text)) {
            sentenceSink.accept(sentence);
        }
    }
    var finalSentence = segmenter.flush();
    if (!finalSentence.isBlank()) {
        sentenceSink.accept(finalSentence);
    }
    if (reply.toString().isBlank() && reminderConfirmationText != null && !reminderConfirmationText.isBlank()) {
        reply.append(reminderConfirmationText);
        sentenceSink.accept(reminderConfirmationText);
    }
    sentenceSink.complete();
});
```

保留：

```text
reply 完整拼接用于日志。
Hermes agent event 处理逻辑。
turnCancelled / turnGuard 检查。
reminderConfirmationText fallback。
异常时发送 hermes_failed 或 tts_failed。
流式 provider 不可用时，`streamChatAndSpeak` 保持当前攒句后 `speakWithRuntime(...)` 的同步路径。
```

- [x] **步骤 4：运行服务测试**

```bash
mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceSessionServiceTest test
```

预期：`BUILD SUCCESS`。

## 任务 8：统一主动播报与提醒回推路径

**文件：**
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`

- [x] **步骤 1：编写失败测试**

在 `XiaozhiVoiceSessionServiceTest` 增加：

```java
@Test
void shouldUseStreamingRuntimeForNotificationWhenStreamingTtsIsEnabled() {
    var eventFactory = new XiaozhiServerEventFactory(new ObjectMapper());
    var streamingTts = new RecordingStreamingTextToSpeechClient();
    var runtime = new XiaozhiTtsRuntime(
            new FakeTextToSpeechClient(),
            streamingTts,
            codec,
            eventFactory
    );
    var serviceWithStreamingRuntime = newService(
            new FakeSpeechToTextClient(),
            new FakeHermesClient(),
            runtime,
            eventFactory
    );
    var headers = new HttpHeaders();
    headers.set("Device-Id", "device-1");
    var session = new TestWebSocketSession("ws-session-1", URI.create("ws://127.0.0.1/xiaozhi/v1"), headers);
    serviceWithStreamingRuntime.open(session);
    serviceWithStreamingRuntime.handleHello(session, new XiaozhiClientHello(
            "hello",
            1,
            Map.of("mcp", true),
            "websocket",
            XiaozhiAudioParams.defaults()
    ));

    var notified = serviceWithStreamingRuntime.notifyDevice("device-1", "提醒时间到了。");

    assertThat(notified).isTrue();
    assertThat(streamingTts.texts()).containsExactly("提醒时间到了。");
    assertThat(textPayloads(session))
            .filteredOn(payload -> payload.contains("\"type\":\"tts\"") && payload.contains("\"state\":\"stop\""))
            .hasSize(1);
}
```

- [x] **步骤 2：运行测试验证失败**

```bash
mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceSessionServiceTest#shouldUseStreamingRuntimeForNotificationWhenStreamingTtsIsEnabled test
```

预期：失败，`notifyDevice(...)` 仍只调用同步 runtime 入口。

- [x] **步骤 3：改造 `notifyDevice(...)`**

实现要求：

```text
notifyDevice 保留当前 busy 检查、tryBeginNotificationPlayback 和 profile 解析。
当 XiaozhiTtsRuntime.streamingEnabled() 为 true 时，调用 playStreaming(...)，sentenceProducer 只 accept 一句播报文本后 complete。
当 streamingEnabled() 为 false 时，保持当前 speak(new XiaozhiTtsRequest(...)) 路径。
两条路径都必须传入同一个 playbackGeneration 和 notificationCancelled(...) guard。
finally 中仍调用 voiceSession.completePlayback(playbackGeneration)，不能由 runtime 外泄状态。
```

- [x] **步骤 4：运行服务测试**

```bash
mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceSessionServiceTest test
```

预期：`BUILD SUCCESS`。

## 任务 9：接入 Spring Bean 和配置

**文件：**
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeans.java`
- 修改：`chatbot-bootstrap/src/main/resources/application.yml`
- 修改：`deploy/chatbot-service.env.example`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeansTest.java`

- [x] **步骤 1：编写失败测试**

```java
@Test
void shouldCreateStreamingTencentTtsClientWhenProviderIsTencentStreaming() {
    var context = new AnnotationConfigApplicationContext();
    TestPropertyValues.of(
            "chatbot.voice.tts.provider=tencent-streaming",
            "chatbot.voice.tts.tencent.app-id=1300000000",
            "chatbot.voice.tts.tencent.secret-id=secret-id",
            "chatbot.voice.tts.tencent.secret-key=secret-key",
            "chatbot.voice.tts.tencent.voice-type=101001",
            "chatbot.voice.default-voice-id=101001"
    ).applyTo(context);
    context.register(XiaozhiVoiceGatewayBeans.class);
    context.refresh();

    assertThat(context.getBean(StreamingTextToSpeechClient.class))
            .isInstanceOf(TencentCloudStreamingTextToSpeechClient.class);
}
```

- [x] **步骤 2：运行测试验证失败**

```bash
mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceGatewayBeansTest#shouldCreateStreamingTencentTtsClientWhenProviderIsTencentStreaming test
```

预期：失败，缺少 bean 或配置。

- [x] **步骤 3：新增配置项**

`application.yml` 在现有 `chatbot.voice.tts` 下补充，不重命名已有 `TENCENT_CLOUD_*` 环境变量：

```yaml
chatbot:
  voice:
    default-voice-id: ${CHATBOT_VOICE_DEFAULT_VOICE_ID:default}
    tts:
      provider: ${CHATBOT_VOICE_TTS_PROVIDER:tencent}
      tencent:
        app-id: ${TENCENT_CLOUD_APP_ID:}
        secret-id: ${TENCENT_CLOUD_SECRET_ID:}
        secret-key: ${TENCENT_CLOUD_SECRET_KEY:}
        voice-type: ${TENCENT_CLOUD_TTS_VOICE_TYPE:603004}
        codec: pcm
        sample-rate: 16000
        stream-timeout-seconds: 30
```

`deploy/chatbot-service.env.example` 新增：

```bash
CHATBOT_VOICE_TTS_PROVIDER=tencent-streaming
TENCENT_CLOUD_APP_ID=
TENCENT_CLOUD_SECRET_ID=
TENCENT_CLOUD_SECRET_KEY=
TENCENT_CLOUD_TTS_VOICE_TYPE=603004
```

- [x] **步骤 4：运行 bean 测试**

```bash
mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceGatewayBeansTest test
```

预期：`BUILD SUCCESS`。

## 任务 10：增强 smoke 脚本延迟观测

**文件：**
- 修改：`scripts/xiaozhi_ws_smoke.py`
- 测试：`scripts/test_xiaozhi_ws_smoke.py`

- [x] **步骤 1：编写失败测试**

```python
def test_smoke_stats_reports_tts_latency_milestones(self):
    stats = xiaozhi_ws_smoke.SmokeStats()
    stats.tts_start_at = 1.0
    stats.first_sentence_start_at = 1.2
    stats.first_binary_at = 1.5
    stats.tts_stop_at = 2.0

    fields = dict(stats.fields())

    self.assertEqual("200", fields["first_sentence_start_ms"])
    self.assertEqual("500", fields["first_binary_ms"])
    self.assertEqual("1000", fields["tts_total_ms"])
```

- [x] **步骤 2：运行测试验证失败**

```bash
PYTHONPATH="scripts" python3 -m unittest "scripts/test_xiaozhi_ws_smoke.py"
```

预期：失败，缺少字段。

- [x] **步骤 3：实现延迟字段**

新增统计：

```text
tts_start_at
first_sentence_start_at
first_binary_at
tts_stop_at
first_sentence_start_ms = first_sentence_start_at - tts_start_at
first_binary_ms = first_binary_at - tts_start_at
tts_total_ms = tts_stop_at - tts_start_at
```

- [x] **步骤 4：运行脚本测试**

```bash
PYTHONPATH="scripts" python3 -m unittest "scripts/test_xiaozhi_ws_smoke.py"
```

预期：`OK`。

## 任务 11：完整回归与远程联调

**文件：**
- 不新增文件。

- [x] **步骤 1：运行 Python 测试**

```bash
python3 -m unittest discover -s "tools/ws_debug_console" -p "test_*.py"
PYTHONPATH="scripts" python3 -m unittest "scripts/test_xiaozhi_ws_smoke.py"
```

预期：两个命令均 `OK`。

- [x] **步骤 2：运行 Maven 模块测试**

```bash
mvn -pl chatbot-speech-api,chatbot-voice-gateway -am test
```

预期：`BUILD SUCCESS`，失败数为 0。

- [ ] **步骤 3：本地 smoke 验证**

```bash
python3 scripts/xiaozhi_ws_smoke.py \
  --url "ws://127.0.0.1:8766/xiaozhi/v1" \
  --token "$TOKEN" \
  --input-text "你好，请用两句话介绍一下你自己。" \
  --timeout 90
```

预期：

```text
hello_received=true
tts_start_count=1
tts_sentence_start_count>=1
binary_frame_count>=1
tts_stop_count=1
first_binary_ms 小于 tts_total_ms
tts_duplicate_sentence_count=0
```

- [ ] **步骤 4：远程联调验证**

仅在用户确认远程环境可用且 token 已安全提供后执行：

```bash
python3 scripts/xiaozhi_ws_smoke.py \
  --url "ws://203.195.202.54:8766/xiaozhi/v1" \
  --token "$TOKEN" \
  --input-text "你好，请用两句话介绍一下你自己。" \
  --timeout 90
```

预期同本地 smoke。命令输出不得打印 token。

## 风险与回滚

- 腾讯云流式接口不支持 SSML，Hermes 输出如包含 SSML 必须在 Java 边界拒绝或清洗；第一阶段按纯文本处理。
- 腾讯云服务按标点断句；Hermes 输出末尾必须 flush 最后半句并补必要结束标点，避免 provider 长时间缓存。
- 当前设备链路要求 Opus 16k mono 60ms 帧，因此腾讯云流式接口第一阶段使用 `Codec=pcm`、`SampleRate=16000`，由后端增量编码 Opus。
- 如果流式 provider 失败，保留同步 `TextToSpeechClient` fallback；配置切回 `chatbot.voice.tts.provider=tencent` 或 `fake` 即可回滚。
- 如果流式音频回调与 WebSocket 下发出现并发问题，必须把 `onAudioFrame` 串行化到单线程/虚拟线程队列，不能直接并发 `sendMessage`。

## 最终验收清单

- [x] Hermes 未结束前，第一句完整句子已经触发腾讯云 `ACTION_SYNTHESIS`。
- [x] 第一段腾讯云 binary PCM 返回后，后端立即编码并下发小智 binary Opus。
- [x] 一轮回答只出现一次 `tts.start` 和一次 `tts.stop`。
- [x] `abort` 后没有旧轮次 binary 继续下发。
- [x] `mvn -pl chatbot-speech-api,chatbot-voice-gateway -am test` 通过。
- [x] smoke 输出 `first_binary_ms`，可用于对比优化前后首包延迟。
