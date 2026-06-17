# 小智 ASR 完整化与 Hermes 边界实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将 `chatbot-service-java` 的 ASR 从“listen stop 后整段一句话识别”升级为“边收音频边推给实时 ASR，listen stop 后收敛结果”的完整语音输入链路，同时保证语义理解、意图识别、工具编排、记忆、情绪策略等 AI 能力全部交由 Hermes agent 处理。

**架构：** Java 服务只承担小智协议、WebSocket 会话、Opus/PCM 转码、ASR/TTS Provider 适配、设备动作执行和 Hermes 调用边界。Hermes agent 负责所有 AI 决策：用户意图、提醒创建意图、情绪判断、工具选择、对话记忆、多模态理解和业务推理；Java 不再用正则或关键词解析用户语义。

**技术栈：** Java 21、Spring Boot 3.4、Spring WebSocket、Maven 多模块、JUnit 5、AssertJ、Jackson、Concentus Opus、腾讯云实时 ASR SDK、腾讯云 TTS、Hermes Responses API。

---

## 关键约束

- 当前项目根目录：`/Users/jiangzhibin/workspace/chatbot-service-java`。
- 参考项目根目录：`/Users/jiangzhibin/workspace/xiaozhi-esp32-server-java`，只参考 ASR/VAD/TTS 管道行为，不搬运 MySQL、Redis、管理后台、角色模型或 Java 内置 Agent。
- 本计划将 ASR/TTS Provider 视为语音 I/O 基础设施。Java 可以适配外部语音服务，但不得引入本地语义 AI、LLM、意图解析、关键词兜底或工具选择策略。
- Java 不引入参考项目的 Vosk、FunASR、本地 Silero VAD 模型；这些属于本地 AI 模型运行时，会扩大部署复杂度并违背 Hermes 作为 AI 能力中心的边界。
- 第一轮不做服务端模型 VAD。音频回合边界仍由小智设备 `listen start` / `listen stop` 驱动，但 ASR 调用从整段聚合改为实时推流，降低尾部等待并为服务端 VAD 接入保留清晰接口。
- 现有 `XiaozhiReminderIntent.parse(...)` 是 Java 本地语义解析，应在本计划内移出会话主链路；提醒动作只能来自 Hermes agent 的结构化事件。
- 所有 Maven 模块测试命令默认使用 `-am` 同时构建依赖模块；当前本地仓库未安装 `chatbot-common:0.0.1-SNAPSHOT` 时，单独 `-pl chatbot-speech-api` 会失败。
- Hermes agent 事件是本计划的显式契约：Java 只消费 `xiaozhi.agent_event`，并通过 fake Hermes 流和服务测试验证该契约；如果真实 Hermes 尚未发出该事件，提醒功能不能宣称完成。
- WebSocket smoke 必须发送可被 `StreamingOpusToPcmDecoder` 解码的真实 Opus 帧；不能再使用任意字节伪造音频帧。
- 不主动执行 `git commit`、`git push`、分支创建或重置操作，除非用户明确要求。

## 参考差异摘要

| 维度 | 当前项目 | 参考项目 | 本计划取舍 |
|---|---|---|---|
| ASR 输入 | `List<ByteBuffer>` 聚合整段音频 | `Flux<byte[]>` PCM 流 | 引入 Java 自有轻量音频流，不引入 Reactor |
| 触发边界 | 客户端 `listen stop` 后识别 | VAD 自动切段 | 第一轮保留客户端边界，ASR 内部实时推流 |
| Provider | 腾讯云一句话识别 + fake | 腾讯、阿里、FunASR、讯飞、火山、Vosk | 第一轮新增腾讯实时 ASR，保留 fake 和一句话识别回退 |
| 本地模型 | 无 | Vosk、Silero VAD | 不引入本地 AI 模型 |
| AI 语义 | Java 有提醒正则兜底 | Java Persona/IntentService | 统一交给 Hermes agent |
| 结果模型 | `String` | `SttResult` 支持情绪 | Java 只接收文本；情绪、意图由 Hermes 返回 |

## 文件结构

- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/SpeechToTextResult.java`
  - 职责：ASR 结果值对象，Java 只依赖文本和观测字段，不承载语义 AI 信息。
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/SpeechToTextAudioStream.java`
  - 职责：无 Reactor 依赖的 PCM 音频流，支持写入、完成、超时读取和关闭。
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/StreamingSpeechToTextClient.java`
  - 职责：实时 ASR Provider 边界。
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/FakeStreamingSpeechToTextClient.java`
  - 职责：测试和本地联调使用的实时 ASR 假实现。
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/StreamingOpusToPcmDecoder.java`
  - 职责：单个会话内复用 Opus decoder，将上行 Opus 帧逐帧解码为 16-bit little-endian PCM。
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentRealtimeSpeechToTextClient.java`
  - 职责：腾讯云实时 ASR WebSocket SDK 适配器。
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentRealtimeSpeechToTextConfig.java`
  - 职责：腾讯实时 ASR 配置。
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentRealtimeSpeechRecognitionListener.java`
  - 职责：包内实时 ASR 识别事件监听器，隔离 SDK listener。
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentRealtimeSpeechRecognizer.java`
  - 职责：包内可替换的腾讯实时识别器边界，用于隔离 SDK 对象并测试生命周期。
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentRealtimeSpeechRecognizerFactory.java`
  - 职责：创建真实 SDK recognizer，生产代码只依赖工厂接口。
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentSdkRealtimeSpeechRecognizerFactory.java`
  - 职责：真实腾讯 SDK recognizer 工厂，唯一直接依赖 `SpeechRecognizer` 的生产类。
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentSdkRealtimeSpeechRecognizer.java`
  - 职责：真实腾讯 SDK recognizer 包装，统一异常处理。
- 修改：`chatbot-speech-api/pom.xml`
  - 职责：新增腾讯实时 ASR SDK 依赖。
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiAsrMode.java`
  - 职责：ASR 模式值对象，显式控制 sentence/streaming 路由。
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSession.java`
  - 职责：保存活动 ASR 流、流式 Opus decoder 和 ASR 线程状态。
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
  - 职责：按 `chatbot.voice.asr.mode` 路由 sentence/streaming 回合；把 streaming 模式下的 `listen start` / binary / `listen stop` 改造成流式 ASR 回合；移除本地提醒语义解析。
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/hermes/HermesAgentEvent.java`
  - 职责：Hermes agent 返回的结构化事件，例如创建设备提醒。
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/hermes/HermesAgentEventExtractor.java`
  - 职责：从 Hermes SSE 中提取文本增量和结构化事件。
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/reminder/XiaozhiReminderIntent.java`
  - 职责：从会话主链路移除；保留类时只作为历史兼容测试对象，不被生产路径调用。
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/reminder/XiaozhiReminderRequestedEvent.java`
  - 职责：保留提醒执行事件，事件来源改为 Hermes 结构化动作。
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeans.java`
  - 职责：按配置选择 fake、腾讯一句话识别、腾讯实时识别。
- 修改：`chatbot-bootstrap/src/main/resources/application.yml`
  - 职责：新增 ASR 模式、实时 ASR 配置和 Hermes agent 事件开关。
- 修改：`deploy/chatbot-service.env.example`
  - 职责：新增部署环境变量示例。
- 测试：`chatbot-speech-api/src/test/java/com/jzb/chatbot/speech/SpeechToTextAudioStreamTest.java`
- 测试：`chatbot-speech-api/src/test/java/com/jzb/chatbot/speech/StreamingOpusToPcmDecoderTest.java`
- 测试：`chatbot-speech-api/src/test/java/com/jzb/chatbot/speech/TencentRealtimeSpeechToTextClientTest.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/hermes/HermesAgentEventExtractorTest.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeansTest.java`

## 阶段 1：研究

### 任务 1：固化上下文快照

**文件：**
- 创建：`.zcf/plan/current/xiaozhi-asr-hermes-context.md`

- [ ] **步骤 1：写入上下文快照**

创建文件内容：

```markdown
# xiaozhi-asr-hermes - 上下文快照

## 技术栈

- Java 21
- Spring Boot 3.4.0
- Spring WebSocket
- Maven 多模块
- JUnit 5 + AssertJ
- Jackson
- Concentus Opus
- 腾讯云 ASR/TTS SDK
- Hermes Responses API

## 受影响模块/文件

- chatbot-speech-api
- chatbot-voice-gateway
- chatbot-hermes-adapter
- chatbot-bootstrap/src/main/resources/application.yml
- deploy/chatbot-service.env.example

## 项目约定

- 默认中文输出。
- 先读后写，理解现有代码再修改。
- 未经用户明确要求，不执行 git commit、git push、git reset、分支操作。
- Java 代码遵循现有 JDK 21、Lombok、JUnit 5 风格。
- AI 语义能力全部交给 Hermes agent，Java 只做协议、音频、调度和动作执行。

## 需求范围

将 ASR 从整段一句话识别升级为实时推流 ASR；移除 Java 本地语义解析；Hermes agent 返回文本和结构化动作。

## 成功标准

- fake 模式保持现有测试可用。
- 腾讯一句话 ASR 作为兼容回退。
- 腾讯实时 ASR 可通过单元测试验证请求参数和流生命周期。
- WebSocket 回合在 listen start 后创建 ASR 流，在 binary 帧到达时写入 PCM，在 listen stop 后完成流并进入 Hermes/TTS。
- 提醒意图只来自 Hermes 结构化事件，Java 不解析用户自然语言。
```

- [ ] **步骤 2：检查快照存在**

运行：

```bash
test -f ".zcf/plan/current/xiaozhi-asr-hermes-context.md"
```

预期：退出码为 `0`。

## 阶段 2：构思

### 任务 2：选择 ASR 架构方案

**方案 A：继续整段一句话识别**

- 优点：改动最小。
- 缺点：延迟高，不能边收边识别，和参考项目差距最大。

**方案 B：客户端边界 + 实时 ASR 推流**

- 优点：保留当前协议稳定性，改动集中；ASR Provider 能边收 PCM 边识别；不引入本地 AI 模型。
- 缺点：仍依赖设备端 `listen stop` 结束回合。

**方案 C：服务端 VAD + 实时 ASR 推流**

- 优点：最接近参考项目体验。
- 缺点：需要本地 VAD 模型或外部 VAD 服务，超出 Hermes 边界和当前部署复杂度。

**选择：方案 B。**

验收标准：

```text
XiaozhiVoiceSessionService 在 listen start 创建 ASR 音频流。
binary 帧到达时立即解码并写入 ASR 流。
listen stop 只负责完成 ASR 流，不再收集整段 ByteBuffer 后调用一句话识别。
Hermes agent 决定意图和动作；Java 不再调用 XiaozhiReminderIntent.parse(...)。
```

## 阶段 3：计划

### 任务 3：定义流式 ASR 基础类型

**文件：**
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/SpeechToTextResult.java`
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/SpeechToTextAudioStream.java`
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/StreamingSpeechToTextClient.java`
- 测试：`chatbot-speech-api/src/test/java/com/jzb/chatbot/speech/SpeechToTextAudioStreamTest.java`

- [ ] **步骤 1：编写失败测试**

创建 `SpeechToTextAudioStreamTest`：

```java
package com.jzb.chatbot.speech;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SpeechToTextAudioStreamTest {

    @Test
    void shouldReadWrittenPcmChunksAndThenEnd() {
        var stream = new SpeechToTextAudioStream();

        stream.write(new byte[] {1, 2});
        stream.complete();

        var first = stream.take(Duration.ofMillis(100));
        var second = stream.take(Duration.ofMillis(100));

        assertThat(first).containsExactly(1, 2);
        assertThat(stream.isEnd(second)).isTrue();
    }

    @Test
    void shouldIgnoreWritesAfterComplete() {
        var stream = new SpeechToTextAudioStream();

        stream.complete();
        stream.write(new byte[] {9});

        assertThat(stream.isEnd(stream.take(Duration.ofMillis(100)))).isTrue();
        assertThat(stream.isEnd(stream.take(Duration.ofMillis(10)))).isTrue();
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-speech-api -am -Dtest=SpeechToTextAudioStreamTest test
```

预期：FAIL，编译错误包含 `cannot find symbol` 和 `SpeechToTextAudioStream`。

- [ ] **步骤 3：实现结果对象**

创建 `SpeechToTextResult.java`：

```java
package com.jzb.chatbot.speech;

/**
 * ASR 识别结果。
 * <p>
 * 仅承载语音输入基础设施字段；意图、情绪和工具动作由 Hermes agent 返回。
 *
 * @author jiangzhibin
 * @since 2026-06-17 00:00:00
 */
public record SpeechToTextResult(String text, String provider, long audioMillis) {

    public SpeechToTextResult {
        text = text == null ? "" : text;
        provider = provider == null || provider.isBlank() ? "unknown" : provider;
        if (audioMillis < 0) {
            audioMillis = 0;
        }
    }

    public static SpeechToTextResult blank(String provider) {
        return new SpeechToTextResult("", provider, 0);
    }
}
```

- [ ] **步骤 4：实现音频流**

创建 `SpeechToTextAudioStream.java`：

```java
package com.jzb.chatbot.speech;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ASR PCM 音频流。
 * <p>
 * 生产者写入 16-bit little-endian PCM，消费者按块读取，避免引入 Reactor 依赖。
 *
 * @author jiangzhibin
 * @since 2026-06-17 00:00:00
 */
public final class SpeechToTextAudioStream implements AutoCloseable {

    private static final byte[] END = new byte[0];

    private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
    private final AtomicBoolean completed = new AtomicBoolean();

    public void write(byte[] pcm) {
        if (pcm == null || pcm.length == 0 || completed.get()) {
            return;
        }
        chunks.offer(pcm.clone());
    }

    public void complete() {
        if (completed.compareAndSet(false, true)) {
            chunks.offer(END);
        }
    }

    public byte[] take(Duration timeout) {
        if (completed.get() && chunks.isEmpty()) {
            return END;
        }
        try {
            var chunk = chunks.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return chunk == null ? END : chunk;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return END;
        }
    }

    public boolean isEnd(byte[] chunk) {
        return chunk == END;
    }

    @Override
    public void close() {
        complete();
    }
}
```

- [ ] **步骤 5：实现流式 ASR 接口**

创建 `StreamingSpeechToTextClient.java`：

```java
package com.jzb.chatbot.speech;

/**
 * 流式语音识别客户端边界。
 * <p>
 * Provider 消费 PCM 音频流并返回文本结果。
 *
 * @author jiangzhibin
 * @since 2026-06-17 00:00:00
 */
public interface StreamingSpeechToTextClient {

    SpeechToTextResult transcribe(SpeechToTextAudioStream audioStream);
}
```

- [ ] **步骤 6：运行测试验证通过**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-speech-api -am -Dtest=SpeechToTextAudioStreamTest test
```

预期：PASS。

### 任务 4：实现会话级 Opus 流式解码

**文件：**
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/StreamingOpusToPcmDecoder.java`
- 测试：`chatbot-speech-api/src/test/java/com/jzb/chatbot/speech/StreamingOpusToPcmDecoderTest.java`

- [ ] **步骤 1：编写失败测试**

创建 `StreamingOpusToPcmDecoderTest`：

```java
package com.jzb.chatbot.speech;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class StreamingOpusToPcmDecoderTest {

    @Test
    void shouldDecodeSingleXiaozhiOpusFrameToPcm() throws Exception {
        var decoder = new StreamingOpusToPcmDecoder(16000);

        var pcm = decoder.decode(ByteBuffer.wrap(encodeOpusFrame()));

        assertThat(pcm).hasSize(960 * Short.BYTES);
        assertThat(Arrays.equals(pcm, new byte[pcm.length])).isFalse();
    }

    private byte[] encodeOpusFrame() throws Exception {
        var samples = new short[960];
        for (var index = 0; index < samples.length; index++) {
            samples[index] = (short) (Math.sin(index / 8.0) * 6_000);
        }
        var encoder = new OpusEncoder(16000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        var output = new byte[4096];
        var encodedBytes = encoder.encode(samples, 0, samples.length, output, 0, output.length);
        return Arrays.copyOf(output, encodedBytes);
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-speech-api -am -Dtest=StreamingOpusToPcmDecoderTest test
```

预期：FAIL，编译错误包含 `StreamingOpusToPcmDecoder`。

- [ ] **步骤 3：实现解码器**

创建 `StreamingOpusToPcmDecoder.java`：

```java
package com.jzb.chatbot.speech;

import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 会话级 Opus 到 PCM 解码器。
 * <p>
 * 每个语音会话持有一个实例，保持 Opus decoder 状态连续。
 *
 * @author jiangzhibin
 * @since 2026-06-17 00:00:00
 */
public final class StreamingOpusToPcmDecoder {

    private static final int CHANNELS = 1;
    private static final int FRAME_DURATION_MS = 60;

    private final OpusDecoder decoder;
    private final int frameSamples;

    public StreamingOpusToPcmDecoder(int sampleRate) {
        try {
            this.decoder = new OpusDecoder(sampleRate, CHANNELS);
            this.frameSamples = sampleRate * FRAME_DURATION_MS / 1000;
        } catch (OpusException exception) {
            throw new IllegalStateException("Failed to create Opus decoder", exception);
        }
    }

    public byte[] decode(ByteBuffer frame) {
        if (frame == null || !frame.hasRemaining()) {
            return new byte[0];
        }
        try {
            var input = frame.slice();
            var opus = new byte[input.remaining()];
            input.get(opus);
            var pcm = new short[frameSamples];
            var decodedSamples = decoder.decode(opus, 0, opus.length, pcm, 0, frameSamples, false);
            var bytes = ByteBuffer.allocate(decodedSamples * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            for (var index = 0; index < decodedSamples; index++) {
                bytes.putShort(pcm[index]);
            }
            return bytes.array();
        } catch (OpusException exception) {
            throw new IllegalStateException("Failed to decode Opus frame", exception);
        }
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-speech-api -am -Dtest=StreamingOpusToPcmDecoderTest test
```

预期：PASS。

## 阶段 4：执行

### 任务 5：接入腾讯实时 ASR Provider

**文件：**
- 修改：`chatbot-speech-api/pom.xml`
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentRealtimeSpeechToTextConfig.java`
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentRealtimeSpeechRecognitionListener.java`
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentRealtimeSpeechRecognizer.java`
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentRealtimeSpeechRecognizerFactory.java`
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentSdkRealtimeSpeechRecognizerFactory.java`
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentSdkRealtimeSpeechRecognizer.java`
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentRealtimeSpeechToTextClient.java`
- 测试：`chatbot-speech-api/src/test/java/com/jzb/chatbot/speech/TencentRealtimeSpeechToTextClientTest.java`

- [ ] **步骤 1：增加腾讯实时 ASR SDK 依赖**

在 `chatbot-speech-api/pom.xml` 的 `<dependencies>` 中加入：

```xml
<dependency>
    <groupId>com.tencentcloudapi</groupId>
    <artifactId>tencentcloud-speech-sdk-java</artifactId>
    <version>1.0.53</version>
</dependency>
```

- [ ] **步骤 2：创建配置对象**

创建 `TencentRealtimeSpeechToTextConfig.java`：

```java
package com.jzb.chatbot.speech;

import java.time.Duration;

/**
 * 腾讯云实时 ASR 配置。
 *
 * @author jiangzhibin
 * @since 2026-06-17 00:00:00
 */
public record TencentRealtimeSpeechToTextConfig(
        String appId,
        String secretId,
        String secretKey,
        String engineModelType,
        int sampleRate,
        Duration chunkTimeout,
        Duration recognitionTimeout
) {

    public TencentRealtimeSpeechToTextConfig {
        if (engineModelType == null || engineModelType.isBlank()) {
            engineModelType = "16k_zh";
        }
        if (sampleRate <= 0) {
            sampleRate = 16000;
        }
        if (chunkTimeout == null || chunkTimeout.isNegative() || chunkTimeout.isZero()) {
            chunkTimeout = Duration.ofMillis(100);
        }
        if (recognitionTimeout == null || recognitionTimeout.isNegative() || recognitionTimeout.isZero()) {
            recognitionTimeout = Duration.ofSeconds(90);
        }
    }
}
```

- [ ] **步骤 3：编写 SDK 参数映射测试**

创建 `TencentRealtimeSpeechToTextClientTest.java`：

```java
package com.jzb.chatbot.speech;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TencentRealtimeSpeechToTextClientTest {

    @Test
    void shouldUse16kChinesePcmDefaults() {
        var config = new TencentRealtimeSpeechToTextConfig(
                "app-id",
                "secret-id",
                "secret-key",
                "",
                0,
                Duration.ZERO,
                Duration.ZERO
        );

        assertThat(config.engineModelType()).isEqualTo("16k_zh");
        assertThat(config.sampleRate()).isEqualTo(16000);
        assertThat(config.chunkTimeout()).isEqualTo(Duration.ofMillis(100));
        assertThat(config.recognitionTimeout()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void shouldWriteAudioStopAndCloseRecognizer() {
        var recognizer = new CapturingRecognizer();
        var client = new TencentRealtimeSpeechToTextClient(
                new TencentRealtimeSpeechToTextConfig(
                        "app-id",
                        "secret-id",
                        "secret-key",
                        "16k_zh",
                        16000,
                        Duration.ofMillis(10),
                        Duration.ofSeconds(1)
                ),
                (config, listener) -> {
                    recognizer.listener = listener;
                    return recognizer;
                }
        );
        var stream = new SpeechToTextAudioStream();

        stream.write(new byte[] {1, 2, 3});
        stream.complete();
        var result = client.transcribe(stream);

        assertThat(result.text()).isEqualTo("测试文本");
        assertThat(recognizer.started).isTrue();
        assertThat(recognizer.writes).containsExactly(new byte[] {1, 2, 3});
        assertThat(recognizer.stopped).isTrue();
        assertThat(recognizer.closed).isTrue();
    }

    private static final class CapturingRecognizer implements TencentRealtimeSpeechRecognizer {
        private final java.util.List<byte[]> writes = new java.util.ArrayList<>();
        private boolean started;
        private boolean stopped;
        private boolean closed;
        private TencentRealtimeSpeechRecognitionListener listener;

        @Override
        public void start() {
            this.started = true;
        }

        @Override
        public void write(byte[] pcm) {
            writes.add(pcm.clone());
        }

        @Override
        public void stop() {
            stopped = true;
            listener.onSentenceEnd("测试文本");
            listener.onComplete("测试文本");
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-speech-api -am -Dtest=TencentRealtimeSpeechToTextClientTest test
```

预期：PASS。

- [ ] **步骤 5：实现腾讯 recognizer 边界**

创建 `TencentRealtimeSpeechRecognitionListener.java`：

```java
package com.jzb.chatbot.speech;

/**
 * 腾讯云实时 ASR 识别事件监听器。
 * <p>
 * 隔离 SDK listener，方便测试音频写入、停止和关闭生命周期。
 *
 * @author jiangzhibin
 * @since 2026-06-17 00:00:00
 */
interface TencentRealtimeSpeechRecognitionListener {

    void onSentenceEnd(String text);

    void onComplete(String text);

    void onFail(String message);
}
```

创建 `TencentRealtimeSpeechRecognizer.java`：

```java
package com.jzb.chatbot.speech;

/**
 * 腾讯云实时 ASR 单次识别器。
 * <p>
 * 每次语音回合创建一个实例，客户端负责 start/write/stop/close 生命周期。
 *
 * @author jiangzhibin
 * @since 2026-06-17 00:00:00
 */
interface TencentRealtimeSpeechRecognizer extends AutoCloseable {

    void start();

    void write(byte[] pcm);

    void stop();

    @Override
    void close();
}
```

创建 `TencentRealtimeSpeechRecognizerFactory.java`：

```java
package com.jzb.chatbot.speech;

/**
 * 腾讯云实时 ASR 识别器工厂。
 *
 * @author jiangzhibin
 * @since 2026-06-17 00:00:00
 */
interface TencentRealtimeSpeechRecognizerFactory {

    TencentRealtimeSpeechRecognizer create(
            TencentRealtimeSpeechToTextConfig config,
            TencentRealtimeSpeechRecognitionListener listener
    );
}
```

创建真实 SDK 工厂 `TencentSdkRealtimeSpeechRecognizerFactory.java`，只在此类中直接使用 `SpeechRecognizer`、`SpeechRecognizerRequest`、`SpeechClient` 和 `Credential`。

```java
package com.jzb.chatbot.speech;

import com.tencent.asrv2.SpeechRecognizer;
import com.tencent.asrv2.SpeechRecognizerListener;
import com.tencent.asrv2.SpeechRecognizerRequest;
import com.tencent.asrv2.SpeechRecognizerResponse;
import com.tencent.core.ws.Credential;
import com.tencent.core.ws.SpeechClient;
import java.util.UUID;

/**
 * 腾讯云 SDK 实时 ASR 识别器工厂。
 *
 * @author jiangzhibin
 * @since 2026-06-17 00:00:00
 */
class TencentSdkRealtimeSpeechRecognizerFactory implements TencentRealtimeSpeechRecognizerFactory {

    private static final String WS_API_URL = "wss://asr.cloud.tencent.com/asr/v2/";

    private final SpeechClient speechClient = new SpeechClient(WS_API_URL);

    @Override
    public TencentRealtimeSpeechRecognizer create(
            TencentRealtimeSpeechToTextConfig config,
            TencentRealtimeSpeechRecognitionListener listener
    ) {
        var request = SpeechRecognizerRequest.init();
        request.setEngineModelType(config.engineModelType());
        request.setVoiceFormat(1);
        request.setVoiceId(UUID.randomUUID().toString());
        var credential = new Credential(config.appId(), config.secretId(), config.secretKey());
        var sdkRecognizer = new SpeechRecognizer(speechClient, credential, request, sdkListener(listener));
        return new TencentSdkRealtimeSpeechRecognizer(sdkRecognizer);
    }

    private SpeechRecognizerListener sdkListener(TencentRealtimeSpeechRecognitionListener listener) {
        return new SpeechRecognizerListener() {
            @Override
            public void onSentenceEnd(SpeechRecognizerResponse response) {
                listener.onSentenceEnd(text(response));
            }

            @Override
            public void onRecognitionComplete(SpeechRecognizerResponse response) {
                listener.onComplete(text(response));
            }

            @Override
            public void onFail(SpeechRecognizerResponse response) {
                listener.onFail(response.getMessage());
            }
        };
    }

    private String text(SpeechRecognizerResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getVoiceTextStr() == null) {
            return "";
        }
        return response.getResult().getVoiceTextStr();
    }
}
```

创建 SDK recognizer 包装类：

```java
package com.jzb.chatbot.speech;

import com.tencent.asrv2.SpeechRecognizer;

/**
 * 腾讯云 SDK 实时 ASR 单次识别器包装。
 *
 * @author jiangzhibin
 * @since 2026-06-17 00:00:00
 */
class TencentSdkRealtimeSpeechRecognizer implements TencentRealtimeSpeechRecognizer {

    private final SpeechRecognizer sdkRecognizer;

    TencentSdkRealtimeSpeechRecognizer(SpeechRecognizer sdkRecognizer) {
        this.sdkRecognizer = sdkRecognizer;
    }

    @Override
    public void start() {
        try {
            sdkRecognizer.start();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to start Tencent realtime ASR", exception);
        }
    }

    @Override
    public void write(byte[] pcm) {
        sdkRecognizer.write(pcm);
    }

    @Override
    public void stop() {
        try {
            sdkRecognizer.stop();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to stop Tencent realtime ASR", exception);
        }
    }

    @Override
    public void close() {
        sdkRecognizer.close();
    }
}
```

- [ ] **步骤 6：实现腾讯实时 ASR 客户端**

创建 `TencentRealtimeSpeechToTextClient.java`。实现时以参考项目 `TencentSttService` 为行为参考，但只保留 Provider 适配，不引入参考项目的配置中心和 AI 模块。关键结构如下：

```java
package com.jzb.chatbot.speech;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 腾讯云实时 ASR 客户端。
 *
 * @author jiangzhibin
 * @since 2026-06-17 00:00:00
 */
public class TencentRealtimeSpeechToTextClient implements StreamingSpeechToTextClient {

    private final TencentRealtimeSpeechToTextConfig config;
    private final TencentRealtimeSpeechRecognizerFactory recognizerFactory;

    public TencentRealtimeSpeechToTextClient(TencentRealtimeSpeechToTextConfig config) {
        this(config, new TencentSdkRealtimeSpeechRecognizerFactory());
    }

    TencentRealtimeSpeechToTextClient(
            TencentRealtimeSpeechToTextConfig config,
            TencentRealtimeSpeechRecognizerFactory recognizerFactory
    ) {
        this.config = config;
        this.recognizerFactory = recognizerFactory;
    }

    @Override
    public SpeechToTextResult transcribe(SpeechToTextAudioStream audioStream) {
        if (audioStream == null) {
            return SpeechToTextResult.blank("tencent-realtime");
        }
        var finalResult = new AtomicReference<>("");
        var completed = new CountDownLatch(1);
        var listener = listener(finalResult, completed);
        var recognizer = recognizerFactory.create(config, listener);
        try {
            recognizer.start();
            Thread.startVirtualThread(() -> writeAudio(audioStream, recognizer));
            completed.await(config.recognitionTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return new SpeechToTextResult(finalResult.get(), "tencent-realtime", 0);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Tencent realtime ASR interrupted", exception);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Tencent realtime ASR failed", exception);
        } finally {
            recognizer.close();
        }
    }

    private TencentRealtimeSpeechRecognitionListener listener(AtomicReference<String> finalResult, CountDownLatch completed) {
        return new TencentRealtimeSpeechRecognitionListener() {
            @Override
            public void onSentenceEnd(String text) {
                if (text != null && !text.isBlank()) {
                    finalResult.set(text);
                }
            }

            @Override
            public void onComplete(String text) {
                if (text != null && !text.isBlank()) {
                    finalResult.set(text);
                }
                completed.countDown();
            }

            @Override
            public void onFail(String message) {
                completed.countDown();
            }
        };
    }

    private void writeAudio(SpeechToTextAudioStream audioStream, TencentRealtimeSpeechRecognizer recognizer) {
        while (true) {
            var chunk = audioStream.take(config.chunkTimeout());
            if (audioStream.isEnd(chunk)) {
                recognizer.stop();
                return;
            }
            recognizer.write(chunk);
        }
    }
}
```

- [ ] **步骤 7：运行模块测试**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-speech-api -am test
```

预期：PASS。

### 任务 6：为实时 ASR 提供 fake 实现和 Bean 选择

**文件：**
- 创建：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/FakeStreamingSpeechToTextClient.java`
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiAsrMode.java`
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeans.java`
- 修改：`chatbot-bootstrap/src/main/resources/application.yml`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeansTest.java`

- [ ] **步骤 1：创建 fake 实时 ASR**

```java
package com.jzb.chatbot.speech;

/**
 * Fake 流式 ASR 客户端。
 *
 * @author jiangzhibin
 * @since 2026-06-17 00:00:00
 */
public class FakeStreamingSpeechToTextClient implements StreamingSpeechToTextClient {

    @Override
    public SpeechToTextResult transcribe(SpeechToTextAudioStream audioStream) {
        if (audioStream != null) {
            while (!audioStream.isEnd(audioStream.take(java.time.Duration.ofMillis(10)))) {
                // drain fake audio stream
            }
        }
        return new SpeechToTextResult("ping", "fake", 0);
    }
}
```

- [ ] **步骤 2：创建 ASR 模式值对象**

创建 `XiaozhiAsrMode.java`：

```java
package com.jzb.chatbot.voice;

/**
 * 小智 ASR 模式。
 * <p>
 * 控制会话服务使用兼容的一句话识别路径还是实时流式识别路径。
 *
 * @author jiangzhibin
 * @since 2026-06-17 00:00:00
 */
public record XiaozhiAsrMode(String value) {

    public XiaozhiAsrMode {
        value = value == null || value.isBlank() ? "sentence" : value.trim().toLowerCase();
        if (!"sentence".equals(value) && !"streaming".equals(value)) {
            throw new IllegalArgumentException("Unsupported Xiaozhi ASR mode: " + value);
        }
    }

    public boolean streaming() {
        return "streaming".equals(value);
    }
}
```

- [ ] **步骤 3：扩展 Bean 配置**

在 `XiaozhiVoiceGatewayBeans` 增加 `StreamingSpeechToTextClient` Bean：

```java
@Bean
XiaozhiAsrMode xiaozhiAsrMode(
        @Value("${chatbot.voice.asr.mode:sentence}") String mode
) {
    return new XiaozhiAsrMode(mode);
}

@Bean
StreamingSpeechToTextClient streamingSpeechToTextClient(
        @Value("${chatbot.voice.asr.mode:sentence}") String mode,
        @Value("${chatbot.voice.asr.provider:fake}") String provider,
        @Value("${chatbot.voice.asr.tencent.app-id:}") String appId,
        @Value("${chatbot.voice.asr.tencent.secret-id:}") String secretId,
        @Value("${chatbot.voice.asr.tencent.secret-key:}") String secretKey,
        @Value("${chatbot.voice.asr.tencent.engine-model-type:16k_zh}") String engineModelType,
        @Value("${chatbot.voice.asr.tencent.sample-rate:16000}") int sampleRate,
        @Value("${chatbot.voice.asr.tencent.chunk-timeout-millis:100}") long chunkTimeoutMillis,
        @Value("${chatbot.voice.asr.tencent.recognition-timeout-seconds:90}") long recognitionTimeoutSeconds
) {
    if (!"streaming".equalsIgnoreCase(mode) || !"tencent".equalsIgnoreCase(provider)) {
        return new FakeStreamingSpeechToTextClient();
    }
    if (appId.isBlank() || secretId.isBlank() || secretKey.isBlank()) {
        throw new IllegalStateException("Tencent realtime ASR requires app-id, secret-id and secret-key");
    }
    return new TencentRealtimeSpeechToTextClient(new TencentRealtimeSpeechToTextConfig(
            appId,
            secretId,
            secretKey,
            engineModelType,
            sampleRate,
            Duration.ofMillis(chunkTimeoutMillis),
            Duration.ofSeconds(recognitionTimeoutSeconds)
    ));
}
```

- [ ] **步骤 4：补充配置**

在 `application.yml` 的 `chatbot.voice.asr` 下调整为：

```yaml
    asr:
      mode: ${CHATBOT_VOICE_ASR_MODE:sentence}
      provider: ${CHATBOT_VOICE_ASR_PROVIDER:fake}
      tencent:
        app-id: ${TENCENT_CLOUD_APP_ID:}
        secret-id: ${TENCENT_CLOUD_SECRET_ID:}
        secret-key: ${TENCENT_CLOUD_SECRET_KEY:}
        region: ap-guangzhou
        endpoint: asr.tencentcloudapi.com
        engine-model-type: ${TENCENT_CLOUD_ASR_ENGINE_MODEL_TYPE:16k_zh}
        voice-format: ${TENCENT_CLOUD_ASR_VOICE_FORMAT:pcm}
        sample-rate: 16000
        timeout-seconds: 15
        chunk-timeout-millis: ${TENCENT_CLOUD_ASR_CHUNK_TIMEOUT_MILLIS:100}
        recognition-timeout-seconds: ${TENCENT_CLOUD_ASR_RECOGNITION_TIMEOUT_SECONDS:90}
```

- [ ] **步骤 5：编写 Bean 测试**

在 `XiaozhiVoiceGatewayBeansTest` 增加断言：

```java
@Test
void shouldCreateFakeStreamingAsrByDefault() {
    contextRunner.run(context -> assertThat(context)
            .hasSingleBean(StreamingSpeechToTextClient.class)
            .getBean(StreamingSpeechToTextClient.class)
            .isInstanceOf(FakeStreamingSpeechToTextClient.class));
}

@Test
void shouldCreateStreamingAsrModeWhenConfigured() {
    contextRunner
            .withPropertyValues("chatbot.voice.asr.mode=streaming")
            .run(context -> assertThat(context.getBean(XiaozhiAsrMode.class).streaming()).isTrue());
}
```

- [ ] **步骤 6：运行测试**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceGatewayBeansTest test
```

预期：PASS。

### 任务 7：改造 WebSocket 会话为流式 ASR 回合

**文件：**
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSession.java`
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`

- [ ] **步骤 1：编写 listen start 创建 ASR 流测试**

在 `XiaozhiVoiceSessionServiceTest` 补充 imports：

```java
import com.jzb.chatbot.speech.FakeStreamingSpeechToTextClient;
import com.jzb.chatbot.speech.SpeechToTextAudioStream;
import com.jzb.chatbot.speech.SpeechToTextResult;
import com.jzb.chatbot.speech.StreamingSpeechToTextClient;
import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
```

在 `XiaozhiVoiceSessionServiceTest` 增加：

```java
@Test
void shouldWritePcmChunksToStreamingAsrBeforeListenStop() throws Exception {
    var asrClient = new CapturingStreamingSpeechToTextClient();
    var serviceWithStreamingAsr = serviceWithStreamingAsr(asrClient);
    var session = openSession(serviceWithStreamingAsr);

    serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
            "listen", "start", "manual", null, null, "ws-session-1", null
    ));
    serviceWithStreamingAsr.handleBinary(session, ByteBuffer.wrap(encodeOpusFrame()));
    serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
            "listen", "stop", null, null, null, "ws-session-1", null
    ));

    assertThat(asrClient.chunkCount()).isGreaterThanOrEqualTo(1);
    assertThat(serviceWithStreamingAsr.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
}
```

测试辅助类：

```java
private static final class CapturingStreamingSpeechToTextClient implements StreamingSpeechToTextClient {
    private final AtomicInteger chunkCount = new AtomicInteger();

    @Override
    public SpeechToTextResult transcribe(SpeechToTextAudioStream audioStream) {
        while (true) {
            var chunk = audioStream.take(Duration.ofMillis(100));
            if (audioStream.isEnd(chunk)) {
                return new SpeechToTextResult("ping", "test", 0);
            }
            chunkCount.incrementAndGet();
        }
    }

    int chunkCount() {
        return chunkCount.get();
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceSessionServiceTest test
```

预期：FAIL，构造器缺少 `StreamingSpeechToTextClient` 或没有流式写入行为。

- [ ] **步骤 3：扩展会话状态**

在 `XiaozhiVoiceSession` 增加：

```java
private SpeechToTextAudioStream asrStream;
private StreamingOpusToPcmDecoder opusDecoder;

public SpeechToTextAudioStream startAsrStream(int sampleRate) {
    asrStream = new SpeechToTextAudioStream();
    opusDecoder = new StreamingOpusToPcmDecoder(sampleRate);
    state = State.LISTENING;
    return asrStream;
}

public void writeAudioFrameToAsr(XiaozhiAudioFrame frame) {
    if (asrStream == null || opusDecoder == null || frame == null) {
        return;
    }
    var pcm = opusDecoder.decode(ByteBuffer.wrap(frame.payload()));
    asrStream.write(pcm);
}

public SpeechToTextAudioStream completeAsrStream() {
    var current = asrStream;
    if (current != null) {
        current.complete();
    }
    return current;
}

public void clearAsrStream() {
    asrStream = null;
    opusDecoder = null;
}
```

- [ ] **步骤 4：调整会话服务构造器和字段**

在 `XiaozhiVoiceSessionService` 增加字段：

```java
private final XiaozhiAsrMode asrMode;
private final StreamingSpeechToTextClient streamingSpeechToTextClient;
private final XiaozhiAudioParams audioParams;
```

在构造器注入中加入这两个依赖。测试构造器同步补齐：

```java
new XiaozhiAsrMode("sentence"),
new FakeStreamingSpeechToTextClient(),
XiaozhiAudioParams.defaults()
```

- [ ] **步骤 5：编写模式路由测试**

在 `XiaozhiVoiceSessionServiceTest` 增加：

```java
@Test
void shouldKeepSentencePathWhenAsrModeIsSentence() {
    var speechClient = new RecordingSpeechToTextClient("sentence text");
    var streamingClient = new CapturingStreamingSpeechToTextClient();
    var serviceWithSentenceMode = serviceWithAsrMode("sentence", speechClient, streamingClient);
    var session = openSession(serviceWithSentenceMode);

    runSingleTurn(serviceWithSentenceMode, session);

    assertThat(speechClient.callCount()).isEqualTo(1);
    assertThat(streamingClient.chunkCount()).isZero();
}

@Test
void shouldUseStreamingPathWhenAsrModeIsStreaming() throws Exception {
    var speechClient = new RecordingSpeechToTextClient("sentence text");
    var streamingClient = new CapturingStreamingSpeechToTextClient();
    var serviceWithStreamingMode = serviceWithAsrMode("streaming", speechClient, streamingClient);
    var session = openSession(serviceWithStreamingMode);

    runSingleTurnWithOpus(serviceWithStreamingMode, session);
    awaitIdle(serviceWithStreamingMode, session);

    assertThat(speechClient.callCount()).isZero();
    assertThat(streamingClient.chunkCount()).isGreaterThanOrEqualTo(1);
}
```

测试辅助方法：

```java
private XiaozhiVoiceSessionService serviceWithAsrMode(
        String mode,
        SpeechToTextClient speechClient,
        StreamingSpeechToTextClient streamingClient
) {
    return new XiaozhiVoiceSessionService(
            codec,
            new XiaozhiAsrMode(mode),
            speechClient,
            streamingClient,
            new FakeHermesClient(),
            new FakeTextToSpeechClient(),
            new XiaozhiServerEventFactory(new ObjectMapper()),
            new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner"),
            new XiaozhiVoiceTokenAuth(""),
            newMcpBridge(),
            XiaozhiAudioParams.defaults()
    );
}
```

新增测试辅助：

```java
private void runSingleTurnWithOpus(XiaozhiVoiceSessionService service, TestWebSocketSession session) {
    service.handleText(session, new XiaozhiClientMessage(
            "listen", "start", "manual", null, null, "ws-session-1", null
    ));
    service.handleBinary(session, ByteBuffer.wrap(encodeOpusFrame()));
    service.handleText(session, new XiaozhiClientMessage(
            "listen", "stop", null, null, null, "ws-session-1", null
    ));
}

private byte[] encodeOpusFrame() throws Exception {
    var samples = new short[960];
    for (var index = 0; index < samples.length; index++) {
        samples[index] = (short) (Math.sin(index / 8.0) * 6_000);
    }
    var encoder = new OpusEncoder(16000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
    var output = new byte[4096];
    var encodedBytes = encoder.encode(samples, 0, samples.length, output, 0, output.length);
    return Arrays.copyOf(output, encodedBytes);
}

private void awaitIdle(XiaozhiVoiceSessionService service, TestWebSocketSession session) {
    var deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
    while (System.nanoTime() < deadline) {
        if (service.getSession(session.getId()).state() == XiaozhiVoiceSession.State.IDLE) {
            return;
        }
        try {
            Thread.sleep(Duration.ofMillis(10));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for idle session", exception);
        }
    }
    throw new AssertionError("session did not become idle");
}

private static final class RecordingSpeechToTextClient implements SpeechToTextClient {
    private final String text;
    private final AtomicInteger callCount = new AtomicInteger();

    private RecordingSpeechToTextClient(String text) {
        this.text = text;
    }

    @Override
    public String transcribe(List<ByteBuffer> audioFrames) {
        callCount.incrementAndGet();
        return text;
    }

    int callCount() {
        return callCount.get();
    }
}
```

- [ ] **步骤 6：实现模式路由和流式 ASR 回合**

把 `handleText` 中 `listen start` 分支改成：

```java
if ("listen".equals(message.type()) && "start".equals(message.state())) {
    if (!asrMode.streaming()) {
        voiceSession.markListening();
        log.info("xiaozhi listen started, sessionId={}, deviceId={}, mode={}",
                webSocketSession.getId(), voiceSession.deviceId(), message.mode());
        return;
    }
    var audioStream = voiceSession.startAsrStream(audioParams.sampleRate());
    Thread.startVirtualThread(() -> processStreamingTurn(webSocketSession, voiceSession, audioStream));
    log.info("xiaozhi listen started, sessionId={}, deviceId={}, mode={}",
            webSocketSession.getId(), voiceSession.deviceId(), message.mode());
    return;
}
```

把 `listen stop` 分支改成：

```java
if ("listen".equals(message.type()) && "stop".equals(message.state())) {
    voiceSession.markProcessing();
    if (!asrMode.streaming()) {
        processSentenceTurn(webSocketSession, voiceSession);
        return;
    }
    voiceSession.completeAsrStream();
    return;
}
```

把 `handleBinary` 中监听状态写入改成：

```java
if (voiceSession.state() == XiaozhiVoiceSession.State.LISTENING) {
    if (!asrMode.streaming()) {
        voiceSession.addAudioFrame(frame);
        return;
    }
    voiceSession.writeAudioFrameToAsr(frame);
    return;
}
```

将旧 `processTurn(...)` 重命名为 `processSentenceTurn(...)`，保留一句话 ASR 回退能力。

新增 `processStreamingTurn`：

```java
private void processStreamingTurn(
        WebSocketSession webSocketSession,
        XiaozhiVoiceSession voiceSession,
        SpeechToTextAudioStream audioStream
) {
    var asrStartedAt = System.nanoTime();
    try {
        var result = streamingSpeechToTextClient.transcribe(audioStream);
        var userText = result.text();
        var asrMillis = elapsedMillis(asrStartedAt);
        if (userText == null || userText.isBlank()) {
            log.warn("xiaozhi streaming asr returned blank text, sessionId={}, deviceId={}, asrMillis={}",
                    webSocketSession.getId(), voiceSession.deviceId(), asrMillis);
            trySendText(webSocketSession, eventFactory.error(voiceSession.sessionId(), "asr_empty", "未识别到语音"));
            voiceSession.markIdle();
            return;
        }
        sendText(webSocketSession, eventFactory.stt(voiceSession.sessionId(), userText));
        var turnStartedAt = System.nanoTime();
        var chatResult = streamChatAndSpeak(webSocketSession, voiceSession, userText, 0, asrMillis, turnStartedAt);
        if (chatResult.failed()) {
            return;
        }
        log.info("xiaozhi streaming conversation turn, sessionId={}, deviceId={}, conversationId={}, userText={}, assistantText={}",
                webSocketSession.getId(), voiceSession.deviceId(), voiceSession.conversationId(), userText, chatResult.reply());
    } catch (RuntimeException exception) {
        log.warn("xiaozhi streaming asr failed, sessionId={}, deviceId={}, asrMillis={}, message={}",
                webSocketSession.getId(), voiceSession.deviceId(), elapsedMillis(asrStartedAt), exception.getMessage(), exception);
        trySendText(webSocketSession, eventFactory.error(voiceSession.sessionId(), "asr_failed", "语音识别失败"));
        voiceSession.markIdle();
    } finally {
        voiceSession.clearAsrStream();
    }
}
```

- [ ] **步骤 7：运行会话测试**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceSessionServiceTest test
```

预期：PASS。

### 任务 8：把本地提醒语义解析迁移到 Hermes 结构化事件

**文件：**
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/hermes/HermesAgentEvent.java`
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/hermes/HermesAgentEventExtractor.java`
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/hermes/HermesAgentEventExtractorTest.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`

- [ ] **步骤 1：定义 Hermes 事件格式**

Hermes agent 通过 SSE 返回：

```text
event: xiaozhi.agent_event
data: {"action":"create_reminder","message":"喝水","delay_seconds":60,"confirmation_text":"1分钟后提醒你喝水"}
```

Java 只识别 `action` 和基础字段，不推断用户语义。Hermes agent 侧必须按此事件名和字段返回事件；真实 Hermes 未返回该事件时，Java 不创建提醒。

- [ ] **步骤 2：编写事件提取测试**

创建 `HermesAgentEventExtractorTest.java`：

```java
package com.jzb.chatbot.voice.hermes;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HermesAgentEventExtractorTest {

    @Test
    void shouldExtractReminderEventFromHermesSse() {
        var extractor = new HermesAgentEventExtractor();

        var events = extractor.accept("""
                event: xiaozhi.agent_event
                data: {"action":"create_reminder","message":"喝水","delay_seconds":60,"confirmation_text":"1分钟后提醒你喝水"}

                """);

        assertThat(events)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.action()).isEqualTo("create_reminder");
                    assertThat(event.message()).isEqualTo("喝水");
                    assertThat(event.delaySeconds()).isEqualTo(60);
                    assertThat(event.confirmationText()).isEqualTo("1分钟后提醒你喝水");
                });
    }
}
```

- [ ] **步骤 3：创建事件对象**

```java
package com.jzb.chatbot.voice.hermes;

/**
 * Hermes agent 结构化事件。
 * <p>
 * Java 只执行 Hermes 明确返回的动作，不解析用户自然语言。
 *
 * @author jiangzhibin
 * @since 2026-06-17 00:00:00
 */
public record HermesAgentEvent(
        String action,
        String message,
        long delaySeconds,
        String confirmationText
) {
}
```

- [ ] **步骤 4：实现事件提取器**

```java
package com.jzb.chatbot.voice.hermes;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Hermes agent SSE 事件提取器。
 *
 * @author jiangzhibin
 * @since 2026-06-17 00:00:00
 */
public class HermesAgentEventExtractor {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final StringBuilder buffer = new StringBuilder();

    public List<HermesAgentEvent> accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return List.of();
        }
        buffer.append(chunk.replace("\r\n", "\n"));
        var events = new ArrayList<HermesAgentEvent>();
        var boundary = buffer.indexOf("\n\n");
        while (boundary >= 0) {
            var event = buffer.substring(0, boundary);
            buffer.delete(0, boundary + 2);
            extract(event).ifPresent(events::add);
            boundary = buffer.indexOf("\n\n");
        }
        return List.copyOf(events);
    }

    private java.util.Optional<HermesAgentEvent> extract(String event) {
        if (!event.contains("event: xiaozhi.agent_event")) {
            return java.util.Optional.empty();
        }
        var data = new StringBuilder();
        for (var line : event.split("\n")) {
            if (line.startsWith("data:")) {
                data.append(line.substring("data:".length()).trim());
            }
        }
        if (data.isEmpty()) {
            return java.util.Optional.empty();
        }
        try {
            var root = OBJECT_MAPPER.readTree(data.toString());
            return java.util.Optional.of(new HermesAgentEvent(
                    root.path("action").asText(""),
                    root.path("message").asText(""),
                    root.path("delay_seconds").asLong(0),
                    root.path("confirmation_text").asText("")
            ));
        } catch (IOException exception) {
            return java.util.Optional.empty();
        }
    }
}
```

- [ ] **步骤 5：移除本地提醒解析调用**

在 `XiaozhiVoiceSessionService.processStreamingTurn(...)` 和旧 `processTurn(...)` 中删除：

```java
var reminderIntent = XiaozhiReminderIntent.parse(userText);
if (reminderIntent != null) {
    scheduleReminder(webSocketSession, voiceSession, reminderIntent, audioFrameCount, asrMillis);
    return;
}
```

保留 `scheduleReminder(...)` 的执行能力，但调用来源改为 Hermes event：

```java
private void handleHermesAgentEvent(
        WebSocketSession webSocketSession,
        XiaozhiVoiceSession voiceSession,
        HermesAgentEvent event,
        int audioFrameCount,
        long asrMillis
) {
    if (!"create_reminder".equals(event.action())) {
        return;
    }
    scheduleReminder(
            webSocketSession,
            voiceSession,
            new XiaozhiReminderIntent(event.message(), event.delaySeconds(), event.confirmationText()),
            audioFrameCount,
            asrMillis
    );
}
```

- [ ] **步骤 6：在 Hermes 流式处理里同时提取文本和事件**

在 `streamChatAndSpeak(...)` 中创建两个提取器：

```java
var textExtractor = new XiaozhiHermesStreamTextExtractor();
var eventExtractor = new HermesAgentEventExtractor();
```

处理每个 chunk 时：

```java
for (var event : eventExtractor.accept(chunk)) {
    handleHermesAgentEvent(webSocketSession, voiceSession, event, audioFrameCount, asrMillis);
}
for (var text : textExtractor.accept(chunk)) {
    reply.append(text);
    if (!speakSentences(webSocketSession, voiceSession, playback, segmenter.accept(text), ttsStartedAt)) {
        return TurnResult.cancelled(reply.toString());
    }
}
```

- [ ] **步骤 7：编写会话测试验证 Java 不解析提醒语义**

在 `XiaozhiVoiceSessionServiceTest` 增加：

```java
@Test
void shouldNotCreateReminderFromLocalTextParsingWhenHermesDoesNotReturnAction() {
    var publishedEvents = new ArrayList<Object>();
    var serviceWithoutHermesAction = serviceWithSentenceAsrAndHermes(
            "1分钟后提醒我喝水",
            new StreamingHermesClient("我会继续对话。")
    );
    serviceWithoutHermesAction.setApplicationEventPublisher(publishedEvents::add);
    var session = openSession(serviceWithoutHermesAction);

    runSingleTurn(serviceWithoutHermesAction, session);

    assertThat(publishedEvents).isEmpty();
}
```

- [ ] **步骤 8：编写 Hermes 事件驱动提醒测试**

在 `XiaozhiVoiceSessionServiceTest` 增加：

```java
@Test
void shouldCreateReminderOnlyFromHermesAgentEvent() {
    var publishedEvents = new ArrayList<Object>();
    var hermesClient = new HermesAgentEventClient("""
            event: xiaozhi.agent_event
            data: {"action":"create_reminder","message":"喝水","delay_seconds":60,"confirmation_text":"1分钟后提醒你喝水"}

            """, """
            event: response.output_text.delta
            data: {"delta":"1分钟后提醒你喝水"}

            """);
    var serviceWithHermesAction = serviceWithSentenceAsrAndHermes("ping", hermesClient);
    serviceWithHermesAction.setApplicationEventPublisher(publishedEvents::add);
    var session = openSession(serviceWithHermesAction);

    runSingleTurn(serviceWithHermesAction, session);

    assertThat(publishedEvents)
            .singleElement()
            .isInstanceOfSatisfying(XiaozhiReminderRequestedEvent.class, event -> {
                assertThat(event.message()).isEqualTo("喝水");
                assertThat(event.delaySeconds()).isEqualTo(60);
            });
    assertThat(session.textPayloads())
            .anySatisfy(payload -> assertThat(payload)
                    .contains("\"type\":\"tts\"", "\"state\":\"sentence_start\"", "1分钟后提醒你喝水"));
}
```

测试辅助 client：

```java
private static final class HermesAgentEventClient implements HermesClient {
    private final List<String> events;

    private HermesAgentEventClient(String... events) {
        this.events = List.of(events);
    }

    @Override
    public HermesResponse chat(HermesRequest request, HermesClientConfig config) {
        return new HermesResponse(request.conversationId(), "");
    }

    @Override
    public Stream<String> streamChat(HermesRequest request, HermesClientConfig config) {
        return events.stream();
    }
}
```

测试辅助 service：

```java
private XiaozhiVoiceSessionService serviceWithSentenceAsrAndHermes(String asrText, HermesClient hermesClient) {
    return new XiaozhiVoiceSessionService(
            codec,
            new XiaozhiAsrMode("sentence"),
            audioFrames -> asrText,
            new FakeStreamingSpeechToTextClient(),
            hermesClient,
            new FakeTextToSpeechClient(),
            new XiaozhiServerEventFactory(new ObjectMapper()),
            new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner"),
            new XiaozhiVoiceTokenAuth(""),
            newMcpBridge(),
            XiaozhiAudioParams.defaults()
    );
}
```

- [ ] **步骤 9：运行测试**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=HermesAgentEventExtractorTest,XiaozhiVoiceSessionServiceTest test
```

预期：PASS。

## 阶段 5：测试

### 任务 9：模块级测试与 smoke 验证

**文件：**
- 修改：`scripts/xiaozhi_ws_smoke.py`
- 修改：`deploy/chatbot-service.env.example`

- [ ] **步骤 1：补充 env 示例**

在 `deploy/chatbot-service.env.example` 增加：

```bash
# ASR mode: fake | tencent sentence | tencent streaming
CHATBOT_VOICE_ASR_MODE=streaming
CHATBOT_VOICE_ASR_PROVIDER=tencent
TENCENT_CLOUD_APP_ID=
TENCENT_CLOUD_SECRET_ID=
TENCENT_CLOUD_SECRET_KEY=
TENCENT_CLOUD_ASR_ENGINE_MODEL_TYPE=16k_zh
TENCENT_CLOUD_ASR_CHUNK_TIMEOUT_MILLIS=100
TENCENT_CLOUD_ASR_RECOGNITION_TIMEOUT_SECONDS=90
```

- [ ] **步骤 2：让 WebSocket smoke 发送合法 Opus 帧**

在 `scripts/xiaozhi_ws_smoke.py` 增加常量：

```python
VALID_OPUS_SILENCE_FRAME = base64.b64decode("+P/+")
```

将 `smoke_url(...)` 中原来的非法二进制音频：

```python
websocket.send_binary(b"\x01\x02\x03")
```

替换为：

```python
websocket.send_binary(VALID_OPUS_SILENCE_FRAME)
```

说明：`+P/+` 已用项目当前 Concentus 1.0.2 验证可解码，能避免 streaming 模式下 `StreamingOpusToPcmDecoder` 因伪音频帧抛 `corrupted stream`。

- [ ] **步骤 3：运行 speech 模块测试**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-speech-api -am test
```

预期：PASS。

- [ ] **步骤 4：运行 voice 模块测试**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am test
```

预期：PASS。

- [ ] **步骤 5：运行聚合测试**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-bootstrap -am test
```

预期：PASS。

- [ ] **步骤 6：本地 fake ASR WebSocket smoke**

运行：

```bash
CHATBOT_VOICE_ASR_MODE=streaming \
CHATBOT_VOICE_ASR_PROVIDER=fake \
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-bootstrap -am spring-boot:run
```

另开终端运行：

```bash
python3 scripts/xiaozhi_ws_smoke.py --url ws://127.0.0.1:8766/xiaozhi/v1
```

预期：smoke 输出包含 `stt`、`tts start`、至少一个 binary TTS frame、`tts stop`。

## 阶段 6：优化

### 任务 10：收敛职责和观测字段

**文件：**
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
- 修改：`chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TencentRealtimeSpeechToTextClient.java`

- [ ] **步骤 1：日志字段统一**

ASR 成功日志必须包含：

```text
sessionId
deviceId
conversationId
asrProvider
asrMillis
userText
```

ASR 失败日志必须包含：

```text
sessionId
deviceId
asrProvider
asrMillis
exception message
```

- [ ] **步骤 2：消除重复 ASR 主链路**

保留旧 `processTurn(...)` 作为 `sentence` 回退路径时，方法名改为：

```java
private void processSentenceTurn(...)
```

流式路径命名为：

```java
private void processStreamingTurn(...)
```

`handleText` 只根据 `chatbot.voice.asr.mode` 选择其中一条路径。

- [ ] **步骤 3：运行 targeted 测试**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-speech-api,chatbot-voice-gateway -am test
```

预期：PASS。

## 阶段 7：评审

### 任务 11：代码审查与交付记录

**文件：**
- 修改：`docs/superpowers/plans/2026-06-17-xiaozhi-asr-hermes-complete-workflow.md`
- 修改：`docs/superpowers/plans/2026-06-16-xiaozhi-firmware-backend-task-checklist.md`

- [x] **步骤 1：审查 Hermes 边界**

运行：

```bash
rg -n "ReminderIntent\\.parse|IntentService|keyword|关键词|情绪判断|semantic|意图" "chatbot-voice-gateway" "chatbot-speech-api" "chatbot-hermes-adapter"
```

预期：

```text
生产路径中没有 XiaozhiReminderIntent.parse 调用。
生产路径中没有 Java 本地意图服务或关键词语义判断。
```

- [x] **步骤 2：审查 ASR 模式切换**

运行：

```bash
rg -n "CHATBOT_VOICE_ASR_MODE|chatbot.voice.asr.mode|StreamingSpeechToTextClient|SpeechToTextClient" .
```

预期：

```text
application.yml、env example、Bean 配置、会话服务和测试均能看到模式切换链路。
```

- [x] **步骤 3：生成实现记录**

在本文件末尾追加：

```markdown
## 实现记录

- 代码完成时间：使用 `date "+%Y-%m-%d %H:%M:%S %z"` 记录。
- 测试命令：
  - `/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-speech-api -am test`
  - `/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am test`
  - `/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-bootstrap -am test`
- Hermes 边界验证：生产路径无 Java 本地意图解析。
- 真机验证：记录设备 ID、协议版本、ASR 模式、识别文本、Hermes 回复、TTS 播放结果。
```

## 风险与门禁

- **BLOCK：** 生产路径仍调用 `XiaozhiReminderIntent.parse(...)`，表示 Java 仍在做语义 AI。
- **BLOCK：** 流式 ASR 线程在 `listen stop` 后不能退出，可能导致会话泄漏。
- **BLOCK：** Opus decoder 每帧新建，导致状态不连续和性能浪费。
- **BLOCK：** `chatbot.voice.asr.mode=sentence` 时仍触发 streaming ASR，或 `streaming` 时同时触发一句话 ASR，表示模式路由不明确。
- **BLOCK：** Hermes agent 未返回 `xiaozhi.agent_event` 时 Java 仍创建提醒，表示本地语义解析没有完全移出生产路径。
- **BLOCK：** WebSocket smoke 仍发送非法二进制伪音频，不能覆盖真实 `StreamingOpusToPcmDecoder` 行为。
- **WARN：** 腾讯实时 ASR 凭证缺失时应启动失败并给出明确异常，不能静默回退到 fake。
- **WARN：** `sentence` 回退路径保留时必须用配置显式选择，不能和 `streaming` 同时处理同一回合。

## 完成定义

- `chatbot-speech-api`、`chatbot-voice-gateway`、`chatbot-bootstrap -am` 测试通过。
- fake streaming ASR 下 WebSocket smoke 通过。
- 腾讯 streaming ASR 配置完整时可启动，缺少 `app-id`、`secret-id`、`secret-key` 时给出明确启动异常。
- 生产路径不再从用户自然语言中本地解析提醒、意图或情绪。
- Hermes agent 可以通过 `xiaozhi.agent_event` 返回 `create_reminder`，Java 只执行该明确动作；Hermes 不返回事件时 Java 不创建提醒。
- `scripts/xiaozhi_ws_smoke.py` 发送合法 Opus 帧，streaming fake smoke 覆盖 Opus 解码、ASR、Hermes、TTS 全链路。
- 计划执行记录和真机验证结果写回本文件或固件后端任务清单。

## 实现记录

- 代码完成时间：2026-06-18 05:12:39 +0800。
- 测试命令：
  - `/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-speech-api -am test`：PASS（任务 9 已执行）。
  - `/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am test`：PASS（任务 9 已执行）。
  - `/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-bootstrap -am test`：PASS（任务 9 已执行）。
  - `/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-speech-api,chatbot-voice-gateway -am test`：PASS（任务 10 已执行，本地 surefire 报告显示 `chatbot-speech-api` 35 tests / 0 failures / 0 errors，`chatbot-voice-gateway` 113 tests / 0 failures / 0 errors）。
- Hermes 边界验证：执行 `rg -n "ReminderIntent\\.parse|IntentService|keyword|关键词|情绪判断|semantic|意图" "chatbot-voice-gateway" "chatbot-speech-api" "chatbot-hermes-adapter"`。结果只命中 `SpeechToTextResult` 关于意图归属 Hermes 的 Javadoc、`XiaozhiReminderIntent` 类型自身和测试中的 `XiaozhiReminderIntent.parse(...)`；生产路径未发现 `XiaozhiReminderIntent.parse(...)` 调用，也未发现 Java 本地 `IntentService` 或关键词语义判断主链路。
- ASR 模式切换验证：执行 `rg -n "CHATBOT_VOICE_ASR_MODE|chatbot.voice.asr.mode|StreamingSpeechToTextClient|SpeechToTextClient" .`。结果覆盖 `chatbot-bootstrap/src/main/resources/application.yml`、`deploy/chatbot-service.env.example`、`XiaozhiVoiceGatewayBeans` Bean 装配、`XiaozhiVoiceSessionService` 会话路由，以及 `XiaozhiVoiceGatewayBeansTest`、`XiaozhiVoiceSessionServiceTest`、`XiaozhiWebSocketHandlerTest` 等测试。
- 软件 smoke 验证：已完成一次本地软件链路 smoke，输出 `OK ws://127.0.0.1:8766/xiaozhi/v1`；启动方式为 `java -jar` 加本地 Hermes mock，不是 `spring-boot:run`，也不是物理真机麦克风或扬声器验收。
- 真机验证：未执行物理真机验证；设备 ID、协议版本、ASR 模式、识别文本、Hermes 回复和 TTS 播放结果仍需硬件复测后补充。
- 已知非阻断项：
  - `mvn -pl chatbot-bootstrap -am spring-boot:run` 在 reactor 下会先作用到根 POM，根项目无 main class，作为启动方式 concern 记录，不阻断本轮软件验证。
  - `deploy/chatbot-service.env.example` 中 ASR mode 注释仍写作 `fake | tencent sentence | tencent streaming`，表达不够准确，运行不受影响。
  - `SpeechToTextResult.audioMillis` 当前表示识别调用耗时，不是音频物理时长；对外暴露前建议补文档说明或重命名。
