# 小智服务端播放期打断实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 支持 MuseLab C6 在 TTS 播放期间上传 `barge_in` 音频，服务端识别到真实用户说话后取消当前 TTS 播放，并让设备进入限时续聊窗口继续收干净语音。

**架构：** 固件播放期发送 `listen.start mode=barge_in` 后，Java voice-gateway 在 `SPEAKING` 状态接受二进制音频并创建独立的 barge-in ASR 流。barge-in ASR 只用于“是否打断”的快速判定，第一版不直接把混有回声的文本提交给 Hermes；判定通过后在 playback generation 仍匹配时取消 `XiaozhiTtsRuntime`、终止当前播放代际、下发 TTS stop，并把 session 切到 `LISTENING`，由固件 4.5 秒 follow-up 窗口收下一句干净问题。

**技术栈：** Java 21、Spring Boot 3.4、Spring WebSocket、Maven 多模块、JUnit 5、AssertJ、Concentus Opus、现有 `StreamingSpeechToTextClient`、`XiaozhiTtsRuntime`、小智 WebSocket 协议。

---

## 关键结论

- 当前服务端 `handleBinary()` 只在 `XiaozhiVoiceSession.State.LISTENING` 接受音频；播放期音频会被忽略，因此固件即使上传也不会触发打断。
- 方案 C 的服务端第一版应把播放期 ASR 文本作为“打断信号”，而不是直接作为用户下一句。原因是 C6 无本地 AEC，播放期音频会混入 TTS 回声。
- 真正打断发生后，应立即取消 TTS，并让设备进入短 follow-up 窗口，用户可以自然补说完整问题。
- 误触发控制必须放在服务端：空文本、过短文本、与当前 TTS 文本高度相似、刚开始播放的短冷却期、重复触发都不应打断。
- 当前 `StreamingSpeechToTextClient` 的真实接口是 `transcribe(SpeechToTextAudioStream audioStream)`，不存在 `recognize(turn.audioStream(), deviceId)`；计划里的实现和测试必须按 `transcribe(...)` 编写。
- `XiaozhiClientMessage` 当前是 7 参数 record：`type, state, mode, reason, text, sessionId, payload`。所有测试里手写消息都必须包含最后的 `payload` 参数，例如 `new XiaozhiClientMessage("listen", "start", "barge_in", null, null, "ws-session-1", null)`。
- `ttsRuntime.cancel(sessionId)` 是会话级取消，会同时取消 active playback 和 active streaming session；barge-in ASR 结果返回时必须先校验 session 仍在线、当前状态仍是 `SPEAKING`、`turn.playbackGeneration()` 仍是当前活跃播放代际，避免误伤后续提醒、下一轮 TTS 或音乐恢复后的新状态。
- 第一版默认关闭：`chatbot.voice.barge-in.enabled=false` 时，`listen.start mode=barge_in` 必须在入口直接忽略，不创建 ASR 流，避免默认配置下产生额外资源消耗或行为歧义。
- `XiaozhiVoiceSessionService` 当前有多个构造器和测试直接 `new XiaozhiVoiceSessionService(...)`；新增 `XiaozhiBargeInDetector` 依赖时必须保留旧构造器的 disabled detector 委托，并只让 Spring `@Autowired` 构造器使用真实 bean。
- barge-in 打断状态切换必须由 `XiaozhiVoiceSession` 提供原子方法完成；不要在 service 里先 `cancelCurrentTurnPlayback(...)` 再 `markListening()`，否则 TTS finally、通知播放或下一轮播放可能交错覆盖状态。
- 播放期测试不能复用当前 `BoundaryBlockingTtsRuntime.awaitSpeaking()` 作为“已经 SPEAKING”的证据；该 helper 在进入 `super.play(...)` 之前阻塞，此时 session 还未必进入 `SPEAKING`。

## 参考项目对照

- `/Users/jiangzhibin/workspace/xiaozhi-esp32-server-java/xiaozhi-dialogue/src/main/java/com/xiaozhi/communication/common/MessageHandler.java` 的 `handleListenMessage()` 在普通 `listen.start` 初始化 `VadService`/`AecService`，在 `ListenState.Text` 和 `AbortMessage` 发布 `ChatAbortedEvent`，不是 `mode=barge_in` 的服务端 ASR 判定实现。
- `/Users/jiangzhibin/workspace/xiaozhi-esp32-server-java/xiaozhi-dialogue/src/main/java/com/xiaozhi/dialogue/DialogueService.java` 的 `processAudioData()` 先由 `VadService` 检测 `SPEECH_START`，再调用 `startStt(...)` 并在 `Persona.isActive()` 时 `abortDialogue(...)`。可借鉴的是“先建新输入流，再打断活跃播放/上游管道”的顺序；不可照搬的是参考项目依赖服务端 VAD/AEC 和 Reactor `Sinks.Many<byte[]>`，当前项目已有 `SpeechToTextAudioStream` 和固件侧 `mode=barge_in` 信号。
- `/Users/jiangzhibin/workspace/xiaozhi-esp32-server-java/xiaozhi-dialogue/src/main/java/com/xiaozhi/dialogue/playback/Synthesizer.java` 和 `ScheduledPlayer.java` 把“上游合成仍活跃”和“播放器仍有内容”作为打断判断的一部分。当前项目对应锚点是 `XiaozhiVoiceSession.activePlaybackGeneration`、`XiaozhiTtsRuntime.activePlaybacks/activeStreamingSessions` 和 `XiaozhiTtsPlayback.cancel()`，实现时必须保留 generation 守卫。
- `/Users/jiangzhibin/workspace/xiaozhi-esp32-server-java/xiaozhi-dialogue/src/main/java/com/xiaozhi/dialogue/audio/VadService.java` 在 `TtsPlaybackCompletedEvent` 后重置 VAD 隐状态，说明参考项目也承认 TTS 回声会污染服务端语音检测。当前第一版不引入服务端 AEC/VAD 模型，只做 ASR 文本过滤和 follow-up 窗口，是更小的 KISS/YAGNI 方案。

## 成功标准

- 收到 `{"type":"listen","state":"start","mode":"barge_in"}` 时，如果 barge-in 开关启用且当前 session 正在 `SPEAKING`，服务端创建 barge-in ASR 流，不调用 `stopMusic()`，不进入普通用户回合。
- barge-in 开关关闭时，`listen.start/stop mode=barge_in` 被记录并忽略，不创建 `SpeechToTextAudioStream`，不影响当前 TTS。
- `SPEAKING` 期间收到 binary 音频时，不再统一忽略；只写入当前 barge-in ASR 流。
- barge-in ASR 返回有效非空文本后，服务端只有在当前 session 仍处于同一 playback generation 时才调用 `ttsRuntime.cancel(sessionId)`，标记当前 turn abort，并把 session 切到 `LISTENING`。
- 空 ASR、过短 ASR、与当前播报文本相似度过高、冷却期内 ASR 不触发打断。
- 收到 `{"type":"listen","state":"stop","mode":"barge_in"}` 或 barge-in ASR 超时时，只完成/清理 barge-in ASR 流，不进入普通 `handleListenStop()`，不向 Hermes 发起新回合。
- 打断触发后，不把这段 barge-in 文本直接发给 Hermes；下一轮问题由固件 follow-up 窗口通过普通 `LISTENING` 路径进入。
- 原有普通 `listen.start`、`listen.stop`、streaming ASR、TTS、音乐播放测试继续通过。
- 验证命令通过：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceSessionServiceTest,XiaozhiVoiceSessionTest -Dsurefire.failIfNoSpecifiedTests=false test
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-speech-api,chatbot-voice-gateway -am test
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-bootstrap -am test
```

## 非目标

- 不在 Java 本地做用户意图识别。
- 不把 barge-in 文本直接作为完整问题提交 Hermes。
- 不实现服务端 AEC 或声源分离。
- 不修改 Hermes agent 的语义决策边界。
- 不改音乐播放链路，除非现有 `stopMusic()` 会错误拦截 barge-in。
- 不做 git commit、push 或分支操作；实现后由用户明确确认再处理。

## 文件结构

- 不修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiClientMessage.java`
  - 现状：已有 `mode` 字段，record 构造器为 7 参数；实现和测试只消费该字段，不新增协议 DTO 字段。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSession.java`
  - 职责：增加 barge-in ASR turn 生命周期、播放期打断状态、当前播报文本快照和冷却时间戳。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
  - 职责：处理 `listen.start/stop mode=barge_in`，在 `SPEAKING` 期间接收 binary，执行 barge-in ASR 判定和 TTS 取消；新增依赖后同步更新现有多个测试构造 helper。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/bargein/XiaozhiBargeInTurn.java`
  - 职责：封装 barge-in turn id、playback generation、音频流、Opus decoder 和创建时间。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/bargein/XiaozhiBargeInDecision.java`
  - 职责：表达 `INTERRUPT`、`IGNORE` 和忽略原因。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/bargein/XiaozhiBargeInDetector.java`
  - 职责：根据 ASR 文本、当前 TTS 文本、冷却窗口和长度阈值判断是否打断；不做语义理解。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/bargein/XiaozhiBargeInProperties.java`
  - 职责：配置 enabled、minTextLength、cooldownMs、similarityThreshold、asrTimeout。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
  - 职责补充：所有公开构造器新增或委托 `XiaozhiBargeInDetector` 依赖；旧构造器使用 disabled detector 保持测试和现有调用兼容，Spring 构造器注入真实 bean。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeans.java`
  - 职责：注册 barge-in detector 和配置 bean。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-bootstrap/src/main/resources/application.yml`
  - 职责：新增 `chatbot.voice.barge-in.*` 默认配置；第一版默认关闭，按设备或环境灰度打开。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionTest.java`
  - 职责：覆盖 session 级 barge-in 状态机。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`
  - 职责：覆盖 WebSocket message/binary 到 TTS cancel 的端到端服务层行为。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiWebSocketHandlerTest.java`
  - 职责：同步直接构造 `XiaozhiVoiceSessionService` 的测试依赖，避免新增构造参数后编译失败。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/bargein/XiaozhiBargeInDetectorTest.java`
  - 职责：覆盖误触发过滤规则。

## 协议约定

固件播放期上行开始：

```json
{"type":"listen","state":"start","mode":"barge_in"}
```

固件播放期上行结束：

```json
{"type":"listen","state":"stop","mode":"barge_in"}
```

服务端处理要求：

```text
listen.start mode=barge_in: 开关启用且当前 SPEAKING 时，只创建 barge-in ASR turn，不取消 TTS，不 stopMusic，不进入普通 LISTENING 回合。
listen.start mode=barge_in: 开关关闭时记录 debug/info 后直接返回，不创建 ASR turn。
binary while SPEAKING: 只写入 active barge-in turn 的 SpeechToTextAudioStream。
listen.stop mode=barge_in: 只 complete 当前 barge-in audioStream，不调用普通 handleListenStop()。
asrTimeout 到期: 如果 active turn 仍匹配，complete 当前 barge-in audioStream 并等待 transcribe 返回。
```

播放期音频：

```text
WebSocket binary: 小智协议封装后的 Opus frame
```

服务端打断后的目标行为：

```text
1. 通过 session 原子方法确认 active barge-in turn 仍匹配
2. cancel 当前 TTS runtime
3. 在同一个 session 原子方法里标记当前播放代际失效并切到 LISTENING
4. 不把 barge-in ASR 文本提交 Hermes
5. 等固件 follow-up 窗口发送普通音频和 listen.stop
```

## 任务 1：补 detector 单元测试和最小实现

**文件：**
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/bargein/XiaozhiBargeInDecision.java`
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/bargein/XiaozhiBargeInDetector.java`
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/bargein/XiaozhiBargeInProperties.java`
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/bargein/XiaozhiBargeInDetectorTest.java`

- [ ] **步骤 1：编写失败测试**

测试文件核心用例：

```java
class XiaozhiBargeInDetectorTest {

    private final XiaozhiBargeInDetector detector = new XiaozhiBargeInDetector(
            new XiaozhiBargeInProperties(true, 2, 500, 0.82, Duration.ofSeconds(2))
    );

    @Test
    void shouldInterruptForNonEmptyUserSpeechAfterCooldown() {
        var decision = detector.decide("等一下", "今天天气晴朗，适合出门。", 800);

        assertThat(decision.interrupt()).isTrue();
        assertThat(decision.reason()).isEqualTo("user_speech_detected");
    }

    @Test
    void shouldIgnoreBlankText() {
        var decision = detector.decide("   ", "今天天气晴朗。", 800);

        assertThat(decision.interrupt()).isFalse();
        assertThat(decision.reason()).isEqualTo("blank_text");
    }

    @Test
    void shouldIgnoreTextDuringCooldown() {
        var decision = detector.decide("等一下", "今天天气晴朗。", 200);

        assertThat(decision.interrupt()).isFalse();
        assertThat(decision.reason()).isEqualTo("cooldown");
    }

    @Test
    void shouldIgnoreEchoLikeText() {
        var decision = detector.decide("今天天气晴朗适合出门", "今天天气晴朗，适合出门。", 800);

        assertThat(decision.interrupt()).isFalse();
        assertThat(decision.reason()).isEqualTo("echo_like_text");
    }
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiBargeInDetectorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：

```text
Compilation failure: package com.jzb.chatbot.voice.bargein does not exist
```

- [ ] **步骤 3：实现最小 detector**

`XiaozhiBargeInDecision.java`：

```java
package com.jzb.chatbot.voice.bargein;

public record XiaozhiBargeInDecision(boolean interrupt, String reason) {

    public static XiaozhiBargeInDecision interrupt(String reason) {
        return new XiaozhiBargeInDecision(true, reason);
    }

    public static XiaozhiBargeInDecision ignore(String reason) {
        return new XiaozhiBargeInDecision(false, reason);
    }
}
```

`XiaozhiBargeInProperties.java`：

```java
package com.jzb.chatbot.voice.bargein;

import java.time.Duration;

public record XiaozhiBargeInProperties(
        boolean enabled,
        int minTextLength,
        long cooldownMs,
        double similarityThreshold,
        Duration asrTimeout
) {
}
```

`XiaozhiBargeInDetector.java`：

```java
package com.jzb.chatbot.voice.bargein;

public class XiaozhiBargeInDetector {

    private final XiaozhiBargeInProperties properties;

    public XiaozhiBargeInDetector(XiaozhiBargeInProperties properties) {
        this.properties = properties;
    }

    public XiaozhiBargeInProperties properties() {
        return properties;
    }

    public XiaozhiBargeInDecision decide(String asrText, String speakingText, long elapsedPlaybackMs) {
        if (!properties.enabled()) {
            return XiaozhiBargeInDecision.ignore("disabled");
        }
        var text = normalize(asrText);
        if (text.isBlank()) {
            return XiaozhiBargeInDecision.ignore("blank_text");
        }
        if (text.length() < properties.minTextLength()) {
            return XiaozhiBargeInDecision.ignore("too_short");
        }
        if (elapsedPlaybackMs < properties.cooldownMs()) {
            return XiaozhiBargeInDecision.ignore("cooldown");
        }
        if (similarity(text, normalize(speakingText)) >= properties.similarityThreshold()) {
            return XiaozhiBargeInDecision.ignore("echo_like_text");
        }
        return XiaozhiBargeInDecision.interrupt("user_speech_detected");
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\p{Punct}\\p{P}\\s]+", "");
    }

    private double similarity(String left, String right) {
        if (left.isBlank() || right.isBlank()) {
            return 0.0;
        }
        var shorter = left.length() <= right.length() ? left : right;
        var longer = left.length() > right.length() ? left : right;
        if (longer.contains(shorter)) {
            return (double) shorter.length() / longer.length();
        }
        var same = 0;
        for (var index = 0; index < Math.min(left.length(), right.length()); index++) {
            if (left.charAt(index) == right.charAt(index)) {
                same++;
            }
        }
        return (double) same / Math.max(left.length(), right.length());
    }
}
```

- [ ] **步骤 4：运行测试确认通过**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiBargeInDetectorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：

```text
[INFO] BUILD SUCCESS
```

## 任务 2：给 session 增加 barge-in turn 生命周期

**文件：**
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/bargein/XiaozhiBargeInTurn.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSession.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionTest.java`

- [ ] **步骤 1：编写失败测试**

在 `XiaozhiVoiceSessionTest` 增加：

```java
@Test
void shouldStartBargeInTurnOnlyWhileSpeaking() {
    var session = new XiaozhiVoiceSession("session-1");
    session.updateHandshake(null, "device-1", null, 1);

    assertThat(session.startBargeInTurn(16_000)).isNull();

    var playbackGeneration = session.markSpeaking();
    var turn = session.startBargeInTurn(16_000);

    assertThat(turn).isNotNull();
    assertThat(turn.playbackGeneration()).isEqualTo(playbackGeneration);
    assertThat(session.activeBargeInTurn()).isSameAs(turn);
}

@Test
void shouldClearBargeInTurnWhenMarkListening() {
    var session = new XiaozhiVoiceSession("session-1");
    session.updateHandshake(null, "device-1", null, 1);
    session.markSpeaking();
    session.startBargeInTurn(16_000);

    session.markListening();

    assertThat(session.activeBargeInTurn()).isNull();
    assertThat(session.state()).isEqualTo(XiaozhiVoiceSession.State.LISTENING);
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceSessionTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：编译失败，缺少 `startBargeInTurn` 等方法。

- [ ] **步骤 3：实现 `XiaozhiBargeInTurn`**

```java
package com.jzb.chatbot.voice.bargein;

import com.jzb.chatbot.speech.SpeechToTextAudioStream;
import com.jzb.chatbot.speech.StreamingOpusToPcmDecoder;

public record XiaozhiBargeInTurn(
        long sequence,
        long playbackGeneration,
        String deviceId,
        SpeechToTextAudioStream audioStream,
        StreamingOpusToPcmDecoder opusDecoder,
        long startedAtEpochMillis
) {

    public boolean matches(XiaozhiBargeInTurn other) {
        return other != null && sequence == other.sequence && playbackGeneration == other.playbackGeneration;
    }
}
```

- [ ] **步骤 4：在 `XiaozhiVoiceSession` 增加字段和方法**

增加字段：

```java
private long bargeInTurnSequence;
private XiaozhiBargeInTurn bargeInTurn;
private String currentSpeakingText = "";
private long currentSpeakingStartedAtEpochMillis;
```

在所有会清理当前回合的方法中调用：

```java
private void clearBargeInTurnLocked() {
    if (bargeInTurn == null) {
        return;
    }
    bargeInTurn.audioStream().complete();
    bargeInTurn = null;
}
```

新增方法：

```java
public synchronized XiaozhiBargeInTurn startBargeInTurn(int sampleRate) {
    if (state != State.SPEAKING || activePlaybackGeneration == NO_PLAYBACK_GENERATION) {
        return null;
    }
    clearBargeInTurnLocked();
    bargeInTurn = new XiaozhiBargeInTurn(
            ++bargeInTurnSequence,
            activePlaybackGeneration,
            deviceId(),
            new SpeechToTextAudioStream(),
            new StreamingOpusToPcmDecoder(sampleRate),
            System.currentTimeMillis()
    );
    return bargeInTurn;
}

public synchronized XiaozhiBargeInTurn activeBargeInTurn() {
    return bargeInTurn;
}

public synchronized boolean activeBargeInTurnMatches(XiaozhiBargeInTurn turn) {
    return bargeInTurn != null
            && bargeInTurn.matches(turn)
            && state == State.SPEAKING
            && activePlaybackGeneration == turn.playbackGeneration();
}

public synchronized boolean completeBargeInTurn(XiaozhiBargeInTurn turn) {
    if (bargeInTurn == null || !bargeInTurn.matches(turn)) {
        return false;
    }
    bargeInTurn.audioStream().complete();
    return true;
}

public synchronized boolean cancelPlaybackAndListenIfBargeInTurnActive(XiaozhiBargeInTurn turn) {
    if (!activeBargeInTurnMatches(turn)) {
        return false;
    }
    requestAbortLocked();
    cancelPlaybackLocked();
    clearBargeInTurnLocked();
    turnGeneration++;
    activePlaybackGeneration = NO_PLAYBACK_GENERATION;
    state = State.LISTENING;
    audioFrames.clear();
    clearCurrentSpeakingLocked();
    return true;
}

public void writeAudioFrameToBargeIn(XiaozhiAudioFrame frame) {
    var currentTurn = activeBargeInTurn();
    if (currentTurn == null || frame == null) {
        return;
    }
    var pcm = currentTurn.opusDecoder().decode(ByteBuffer.wrap(frame.payload()));
    currentTurn.audioStream().write(pcm);
}

public synchronized void clearBargeInTurn(XiaozhiBargeInTurn turn) {
    if (bargeInTurn == null || !bargeInTurn.matches(turn)) {
        return;
    }
    clearBargeInTurnLocked();
}

public synchronized void updateCurrentSpeakingText(String text) {
    currentSpeakingText = text == null ? "" : text;
}

public synchronized void appendCurrentSpeakingText(String text) {
    if (text != null && !text.isBlank()) {
        currentSpeakingText = currentSpeakingText + text;
    }
}

public synchronized String currentSpeakingText() {
    return currentSpeakingText;
}

public synchronized long currentSpeakingElapsedMillis() {
    if (currentSpeakingStartedAtEpochMillis <= 0) {
        return 0;
    }
    return System.currentTimeMillis() - currentSpeakingStartedAtEpochMillis;
}

private void clearCurrentSpeakingLocked() {
    currentSpeakingText = "";
    currentSpeakingStartedAtEpochMillis = 0;
}
```

在 `beginSpeakingPlayback()` 中设置 `currentSpeakingStartedAtEpochMillis = System.currentTimeMillis()`。在 `markListening()`、`startAsrStream()`、`tryDrainAudioFramesForProcessing()`、`markIdleInternal()`、`requestAbortLocked()`、`cancelPlaybackLocked()` 中清理 barge-in turn，并清空 `currentSpeakingText/currentSpeakingStartedAtEpochMillis`，避免旧流泄漏。`requestAbortLocked()` 只标记普通 turn abort，不能单独作为 barge-in generation 守卫；barge-in 取消必须使用 `activeBargeInTurnMatches(turn)`。

`cancelPlaybackAndListenIfBargeInTurnActive(...)` 是打断成功后的唯一状态切换入口，service 不应自行组合 `cancelCurrentTurnPlayback(...)` 和 `markListening()`。

- [ ] **步骤 5：运行 session 测试**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceSessionTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：`BUILD SUCCESS`。

## 任务 3：服务层识别 `listen.start/stop mode=barge_in`

**文件：**
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`

- [ ] **步骤 1：编写失败测试**

在 `XiaozhiVoiceSessionServiceTest` 增加：

```java
@Test
void shouldStartBargeInTurnWhenListenStartBargeInDuringSpeaking() {
    var serviceWithBargeIn = newService(
            new FakeSpeechToTextClient(),
            new FakeHermesClient(),
            new FakeTextToSpeechClient(),
            new XiaozhiAsrMode("streaming"),
            new ImmediateStreamingSpeechToTextClient("", "streaming-provider"),
            new XiaozhiBargeInDetector(new XiaozhiBargeInProperties(true, 2, 500, 0.82, Duration.ofMillis(200)))
    );
    var session = openSession(serviceWithBargeIn);
    serviceWithBargeIn.getSession(session.getId()).markSpeaking();

    serviceWithBargeIn.handleText(session, new XiaozhiClientMessage(
            "listen", "start", "barge_in", null, null, "ws-session-1", null
    ));

    assertThat(serviceWithBargeIn.getSession(session.getId()).activeBargeInTurn()).isNotNull();
}

@Test
void shouldIgnoreBargeInStartWhenDisabled() {
    var serviceWithDisabledBargeIn = newService(
            new FakeSpeechToTextClient(),
            new FakeHermesClient(),
            new FakeTextToSpeechClient(),
            new XiaozhiAsrMode("streaming"),
            new ImmediateStreamingSpeechToTextClient("", "streaming-provider"),
            new XiaozhiBargeInDetector(new XiaozhiBargeInProperties(false, 2, 500, 0.82, Duration.ofMillis(200)))
    );
    var session = openSession(serviceWithDisabledBargeIn);
    serviceWithDisabledBargeIn.getSession(session.getId()).markSpeaking();

    serviceWithDisabledBargeIn.handleText(session, new XiaozhiClientMessage(
            "listen", "start", "barge_in", null, null, "ws-session-1", null
    ));

    assertThat(serviceWithDisabledBargeIn.getSession(session.getId()).activeBargeInTurn()).isNull();
}
```

测试类里 `XiaozhiClientMessage` 必须使用当前 7 参数 record 构造器。`FakeSpeechToTextClient` 当前没有文本构造器，不要写 `new FakeSpeechToTextClient("你好")`。这里不要复用当前 `BoundaryBlockingTtsRuntime.awaitSpeaking()` 作为前置条件；它在进入 `super.play(...)` 前阻塞，不能证明 session 已经进入 `SPEAKING`。

- [ ] **步骤 2：运行测试确认失败**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceSessionServiceTest#shouldStartBargeInTurnWhenListenStartBargeInDuringSpeaking -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：第一个测试中 barge-in turn 为 null，测试失败；第二个测试在实现入口开关前可能误创建 turn。

- [ ] **步骤 3：调整 `handleListenStart`**

在普通 listen start 前增加：

```java
if ("barge_in".equals(message.mode())) {
    handleBargeInStart(webSocketSession, voiceSession);
    return;
}
```

在普通 listen stop 前增加：

```java
if ("barge_in".equals(message.mode())) {
    handleBargeInStop(webSocketSession, voiceSession);
    return;
}
```

新增方法：

```java
private void handleBargeInStart(WebSocketSession webSocketSession, XiaozhiVoiceSession voiceSession) {
    if (!bargeInDetector.properties().enabled()) {
        log.debug("ignore xiaozhi barge-in start because disabled, sessionId={}, deviceId={}",
                webSocketSession.getId(), voiceSession.deviceId());
        return;
    }
    var turn = voiceSession.startBargeInTurn(audioParams.sampleRate());
    if (turn == null) {
        log.debug("ignore xiaozhi barge-in start outside speaking, sessionId={}, deviceId={}, state={}",
                webSocketSession.getId(), voiceSession.deviceId(), voiceSession.state());
        return;
    }
    Thread.startVirtualThread(() -> processBargeInTurn(webSocketSession, voiceSession, turn));
    Thread.startVirtualThread(() -> completeBargeInOnTimeout(voiceSession, turn));
    log.info("xiaozhi barge-in started, sessionId={}, deviceId={}, playbackGeneration={}",
            webSocketSession.getId(), voiceSession.deviceId(), turn.playbackGeneration());
}

private void handleBargeInStop(WebSocketSession webSocketSession, XiaozhiVoiceSession voiceSession) {
    var turn = voiceSession.activeBargeInTurn();
    if (turn == null) {
        log.debug("ignore xiaozhi barge-in stop without active turn, sessionId={}, deviceId={}, state={}",
                webSocketSession.getId(), voiceSession.deviceId(), voiceSession.state());
        return;
    }
    voiceSession.completeBargeInTurn(turn);
    log.info("xiaozhi barge-in stopped, sessionId={}, deviceId={}, playbackGeneration={}",
            webSocketSession.getId(), voiceSession.deviceId(), turn.playbackGeneration());
}

private void completeBargeInOnTimeout(XiaozhiVoiceSession voiceSession, XiaozhiBargeInTurn turn) {
    try {
        Thread.sleep(bargeInDetector.properties().asrTimeout());
    } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return;
    }
    if (voiceSession.completeBargeInTurn(turn)) {
        log.info("xiaozhi barge-in timed out, sessionId={}, deviceId={}, playbackGeneration={}",
                voiceSession.sessionId(), voiceSession.deviceId(), turn.playbackGeneration());
    }
}
```

此分支不得调用 `stopMusic(voiceSession)` 和 `cancelCurrentTurnPlayback(voiceSession)`，否则刚开始 barge-in 就会取消播放，无法让 detector 判定。`completeBargeInOnTimeout(...)` 需要读取 `XiaozhiBargeInProperties.asrTimeout()`；可以让 `XiaozhiBargeInDetector` 暴露 `properties()`，或把 properties 作为 `XiaozhiVoiceSessionService` 的独立依赖，二选一即可，不引入额外调度框架。

- [ ] **步骤 4：补 `XiaozhiVoiceSessionService` 构造器兼容**

新增字段：

```java
private final XiaozhiBargeInDetector bargeInDetector;
```

给旧构造器委托 disabled detector，避免现有测试直接构造点一次性全部失败：

```java
private static XiaozhiBargeInDetector disabledBargeInDetector() {
    return new XiaozhiBargeInDetector(new XiaozhiBargeInProperties(
            false,
            2,
            500,
            0.82,
            Duration.ofSeconds(2)
    ));
}
```

Spring `@Autowired` 构造器新增参数：

```java
XiaozhiBargeInDetector bargeInDetector
```

并传给最终全参数构造器。测试 helper 需要新增这个重载：

```java
private XiaozhiVoiceSessionService newService(
        SpeechToTextClient speechToTextClient,
        HermesClient hermesClient,
        TextToSpeechClient textToSpeechClient,
        XiaozhiAsrMode asrMode,
        StreamingSpeechToTextClient streamingSpeechToTextClient,
        XiaozhiBargeInDetector bargeInDetector
) {
    var eventFactory = new XiaozhiServerEventFactory(new ObjectMapper());
    return new XiaozhiVoiceSessionService(
            codec,
            speechToTextClient,
            hermesClient,
            new XiaozhiTtsRuntime(textToSpeechClient, codec, eventFactory),
            eventFactory,
            new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner"),
            new XiaozhiVoiceTokenAuth(""),
            newMcpBridge(),
            asrMode,
            streamingSpeechToTextClient,
            XiaozhiAudioParams.defaults(),
            new XiaozhiVoiceProfileResolver(new VoiceId("default"), 1.0, 1.0),
            bargeInDetector
    );
}
```

同时更新 `XiaozhiWebSocketHandlerTest` 中直接 `new XiaozhiVoiceSessionService(...)` 的调用；如果保留旧构造器委托，该测试可不改，但必须跑测试确认。

任务 5 需要注入自定义 `XiaozhiTtsRuntime`，因此还要新增 runtime 版 helper：

```java
private XiaozhiVoiceSessionService newService(
        SpeechToTextClient speechToTextClient,
        HermesClient hermesClient,
        XiaozhiTtsRuntime ttsRuntime,
        XiaozhiServerEventFactory eventFactory,
        XiaozhiAsrMode asrMode,
        StreamingSpeechToTextClient streamingSpeechToTextClient,
        XiaozhiBargeInDetector bargeInDetector
) {
    return new XiaozhiVoiceSessionService(
            codec,
            speechToTextClient,
            hermesClient,
            ttsRuntime,
            eventFactory,
            new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner"),
            new XiaozhiVoiceTokenAuth(""),
            newMcpBridge(),
            asrMode,
            streamingSpeechToTextClient,
            XiaozhiAudioParams.defaults(),
            new XiaozhiVoiceProfileResolver(new VoiceId("default"), 1.0, 1.0),
            bargeInDetector
    );
}
```

- [ ] **步骤 5：运行测试确认通过**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceSessionServiceTest#shouldStartBargeInTurnWhenListenStartBargeInDuringSpeaking -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：`BUILD SUCCESS`。

## 任务 4：`SPEAKING` 期间 binary 写入 barge-in ASR

**文件：**
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`

- [ ] **步骤 1：编写失败测试**

测试核心：

```java
@Test
void shouldAcceptBinaryForActiveBargeInTurnWhileSpeaking() {
    var streamingSpeech = new ImmediateStreamingSpeechToTextClient("等一下", "streaming-provider");
    var serviceWithStreamingAsr = newService(
            new FakeSpeechToTextClient(),
            new FakeHermesClient(),
            new FakeTextToSpeechClient(),
            new XiaozhiAsrMode("streaming"),
            streamingSpeech,
            new XiaozhiBargeInDetector(new XiaozhiBargeInProperties(true, 2, 0, 0.82, Duration.ofMillis(200)))
    );
    var session = openSession(serviceWithStreamingAsr);

    serviceWithStreamingAsr.getSession(session.getId()).markSpeaking();
    serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
            "listen", "start", "barge_in", null, null, "ws-session-1", null
    ));
    serviceWithStreamingAsr.handleBinary(session, ByteBuffer.wrap(new byte[] {(byte) 0xf8, (byte) 0xff, (byte) 0xfe}));
    serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
            "listen", "stop", "barge_in", null, null, "ws-session-1", null
    ));

    assertThat(streamingSpeech.callCount()).isEqualTo(1);
}
```

复用任务 3 新增的 `newService(..., XiaozhiAsrMode, StreamingSpeechToTextClient, XiaozhiBargeInDetector)` 测试 helper。重点是 binary 不再被 `outside listening` 忽略。测试音频必须使用可被 `StreamingOpusToPcmDecoder` 接受的 Opus 帧；不要使用随机 `{1,2,3}`。

- [ ] **步骤 2：运行测试确认失败**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceSessionServiceTest#shouldAcceptBinaryForActiveBargeInTurnWhileSpeaking -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：ASR 未收到音频。

- [ ] **步骤 3：调整 `handleBinary`**

把当前逻辑扩展为：

```java
if (voiceSession.state() == XiaozhiVoiceSession.State.LISTENING) {
    if (asrMode.streaming()) {
        voiceSession.writeAudioFrameToAsr(frame);
    } else {
        voiceSession.addAudioFrameIfListening(frame);
    }
    return;
}
if (voiceSession.state() == XiaozhiVoiceSession.State.SPEAKING
        && voiceSession.activeBargeInTurn() != null) {
    voiceSession.writeAudioFrameToBargeIn(frame);
    return;
}
```

保留最后的 debug ignore 日志。

- [ ] **步骤 4：运行测试确认通过**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceSessionServiceTest#shouldAcceptBinaryForActiveBargeInTurnWhileSpeaking -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：`BUILD SUCCESS`。

## 任务 5：处理 barge-in ASR 结果并取消 TTS

**文件：**
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSession.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`

- [ ] **步骤 1：编写失败测试**

测试核心：

```java
@Test
void shouldCancelTtsAndEnterListeningWhenBargeInDetected() throws Exception {
    var eventFactory = new XiaozhiServerEventFactory(new ObjectMapper());
    var ttsRuntime = new RecordingCancelTtsRuntime(new FakeTextToSpeechClient(), codec, eventFactory);
    var streamingSpeech = new ImmediateStreamingSpeechToTextClient("等一下", "streaming-provider");
    var serviceWithBargeIn = newService(
            new FakeSpeechToTextClient(),
            new FakeHermesClient(),
            ttsRuntime,
            eventFactory,
            new XiaozhiAsrMode("streaming"),
            streamingSpeech,
            new XiaozhiBargeInDetector(new XiaozhiBargeInProperties(true, 2, 0, 0.82, Duration.ofMillis(200)))
    );
    var session = openSession(serviceWithBargeIn);
    var voiceSession = serviceWithBargeIn.getSession(session.getId());
    voiceSession.markSpeaking();
    voiceSession.updateCurrentSpeakingText("这是一段很长的回答。");

    serviceWithBargeIn.handleText(session, new XiaozhiClientMessage(
            "listen", "start", "barge_in", null, null, "ws-session-1", null
    ));
    serviceWithBargeIn.handleText(session, new XiaozhiClientMessage(
            "listen", "stop", "barge_in", null, null, "ws-session-1", null
    ));

    assertThat(ttsRuntime.awaitCancelled()).isTrue();
    assertThat(serviceWithBargeIn.getSession(session.getId()).state())
            .isEqualTo(XiaozhiVoiceSession.State.LISTENING);
}
```

新增专用测试 helper，不复用 `BoundaryBlockingTtsRuntime`：

```java
private static class RecordingCancelTtsRuntime extends XiaozhiTtsRuntime {

    private final CountDownLatch cancelled = new CountDownLatch(1);

    private RecordingCancelTtsRuntime(
            TextToSpeechClient textToSpeechClient,
            XiaozhiMessageCodec codec,
            XiaozhiServerEventFactory eventFactory
    ) {
        super(textToSpeechClient, codec, eventFactory);
    }

    @Override
    public void cancel(String sessionId) {
        cancelled.countDown();
        super.cancel(sessionId);
    }

    private boolean awaitCancelled() {
        return await(cancelled, Duration.ofSeconds(1));
    }
}
```

这个用例只验证 service 在 barge-in detector 判定通过后调用 `ttsRuntime.cancel(sessionId)` 并把 session 原子切到 `LISTENING`；不要在这里混入 Hermes streaming 和真实 TTS 播放时序。`BoundaryBlockingTtsRuntime` 仍可留给已有测试使用。

- [ ] **步骤 2：运行测试确认失败**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceSessionServiceTest#shouldCancelTtsAndEnterListeningWhenBargeInDetected -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：TTS 未取消或状态仍为 `SPEAKING`。

- [ ] **步骤 3：实现 `processBargeInTurn`**

新增方法：

```java
private void processBargeInTurn(
        WebSocketSession webSocketSession,
        XiaozhiVoiceSession voiceSession,
        XiaozhiBargeInTurn turn
) {
    try {
        var result = streamingSpeechToTextClient.transcribe(turn.audioStream());
        if (!voiceSession.activeBargeInTurnMatches(turn)) {
            return;
        }
        var elapsedMs = voiceSession.currentSpeakingElapsedMillis();
        var decision = bargeInDetector.decide(
                result == null ? "" : result.text(),
                voiceSession.currentSpeakingText(),
                elapsedMs
        );
        if (!decision.interrupt()) {
            log.info("xiaozhi barge-in ignored, sessionId={}, deviceId={}, reason={}, text={}",
                    webSocketSession.getId(), voiceSession.deviceId(), decision.reason(), result == null ? "" : result.text());
            return;
        }
        if (!voiceSession.activeBargeInTurnMatches(turn)) {
            return;
        }
        log.info("xiaozhi barge-in detected, sessionId={}, deviceId={}, text={}",
                webSocketSession.getId(), voiceSession.deviceId(), result == null ? "" : result.text());
        ttsRuntime.cancel(voiceSession.sessionId());
        if (!voiceSession.cancelPlaybackAndListenIfBargeInTurnActive(turn)) {
            return;
        }
    } catch (Exception ex) {
        log.warn("xiaozhi barge-in failed, sessionId={}, deviceId={}",
                webSocketSession.getId(), voiceSession.deviceId(), ex);
    } finally {
        voiceSession.clearBargeInTurn(turn);
    }
}
```

注意：

```text
这里不调用 Hermes
这里不发送这段 ASR 文本给普通 processSentenceTurn
这里不调用 stopMusic；音乐播放是否暂停/停止仍只由 Hermes structured event 决定
这里不能在 generation 已变化后 markListening，否则会覆盖提醒或下一轮播放状态
状态切换必须使用 cancelPlaybackAndListenIfBargeInTurnActive(turn)，不要直接组合 cancelCurrentTurnPlayback(...) 和 markListening()
```

- [ ] **步骤 4：记录当前 TTS 文本用于回声过滤**

在 TTS 播放开始前，把正在播报的文本写入 session：

```java
voiceSession.updateCurrentSpeakingText(String.join("", sentences));
```

流式 TTS 路径中，在 `sentenceSink.accept(sentence)` 前后使用 `voiceSession.appendCurrentSpeakingText(sentence)` 累积当前将要播报的句子；同步 TTS 路径可一次性写入 `sentences` 拼接文本。不要把 Hermes 原始事件 JSON 或音乐事件文本写入 `currentSpeakingText`。

在播放结束、取消、进入 idle/listening 时清空：

```java
voiceSession.updateCurrentSpeakingText("");
```

- [ ] **步骤 5：运行测试确认通过**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceSessionServiceTest#shouldCancelTtsAndEnterListeningWhenBargeInDetected -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：`BUILD SUCCESS`。

## 任务 6：配置、Bean 和默认开关

**文件：**
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeans.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-bootstrap/src/main/resources/application.yml`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeansTest.java`

- [ ] **步骤 1：编写 Bean 测试**

增加：

```java
@Test
void shouldCreateBargeInDetectorWithDefaultProperties() {
    contextRunner.run(context -> {
        assertThat(context).hasSingleBean(XiaozhiBargeInDetector.class);
        assertThat(context).hasSingleBean(XiaozhiBargeInProperties.class);
        assertThat(context.getBean(XiaozhiBargeInProperties.class).enabled()).isFalse();
    });
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceGatewayBeansTest#shouldCreateBargeInDetectorWithDefaultProperties -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：缺少 bean。

- [ ] **步骤 3：注册配置和 bean**

在 beans 配置类中增加：

```java
@Bean
XiaozhiBargeInProperties xiaozhiBargeInProperties(Environment environment) {
    var binder = Binder.get(environment);
    return new XiaozhiBargeInProperties(
            binder.bind("chatbot.voice.barge-in.enabled", Boolean.class).orElse(false),
            binder.bind("chatbot.voice.barge-in.min-text-length", Integer.class).orElse(2),
            binder.bind("chatbot.voice.barge-in.cooldown-ms", Long.class).orElse(500L),
            binder.bind("chatbot.voice.barge-in.similarity-threshold", Double.class).orElse(0.82),
            binder.bind("chatbot.voice.barge-in.asr-timeout", Duration.class).orElse(Duration.ofSeconds(2))
    );
}

@Bean
XiaozhiBargeInDetector xiaozhiBargeInDetector(XiaozhiBargeInProperties properties) {
    return new XiaozhiBargeInDetector(properties);
}
```

沿用当前 `XiaozhiMusicPlaybackProperties` 的 `Binder` 风格，不引入新的 `@ConfigurationProperties` 绑定方式。

- [ ] **步骤 4：补默认配置**

在 `application.yml` 增加：

```yaml
chatbot:
  voice:
    barge-in:
      enabled: false
      min-text-length: 2
      cooldown-ms: 500
      similarity-threshold: 0.82
      asr-timeout: 2s
```

当前 `application.yml` 已有 `chatbot.voice` 节点，应合并到该节点下，不重复顶层；建议放在 `audio` 与 `music` 之间，保持配置可读性。

- [ ] **步骤 5：运行测试确认通过**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceGatewayBeansTest#shouldCreateBargeInDetectorWithDefaultProperties -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：`BUILD SUCCESS`。

- [ ] **步骤 6：运行 bootstrap 配置回归**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-bootstrap -am test
```

预期：`BUILD SUCCESS`。

## 任务 7：全量回归和联调验证

**文件：**
- 不修改文件

- [ ] **步骤 1：服务端定向回归**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceSessionServiceTest,XiaozhiVoiceSessionTest,XiaozhiBargeInDetectorTest,XiaozhiVoiceGatewayBeansTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：

```text
[INFO] BUILD SUCCESS
```

- [ ] **步骤 2：语音模块全量回归**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-speech-api,chatbot-voice-gateway -am test
```

预期：

```text
[INFO] BUILD SUCCESS
```

- [ ] **步骤 3：bootstrap 配置回归**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-bootstrap -am test
```

预期：

```text
[INFO] BUILD SUCCESS
```

- [ ] **步骤 4：端到端日志验证**

固件播放期发送 `mode=barge_in` 后，服务端应出现：

```text
xiaozhi barge-in started, sessionId=..., deviceId=..., playbackGeneration=...
```

用户真实说话后应出现：

```text
xiaozhi barge-in detected, sessionId=..., deviceId=..., text=...
xiaozhi turn playback cancelled, sessionId=...
```

误触发时应出现：

```text
xiaozhi barge-in ignored, sessionId=..., reason=echo_like_text
```

- [ ] **步骤 5：确认不会把混响文本送 Hermes**

查日志：

```bash
rg -n "xiaozhi hermes|barge-in detected|barge-in ignored" "/opt/device_gateway/logs/chatbot-service.log"
```

预期：

```text
barge-in detected 后没有立刻出现以 barge-in ASR 文本发起的新 Hermes 请求
只有 follow-up 普通 listen.stop 后才进入 Hermes
```

## 风险与控制

- C6 无 AEC，播放期 ASR 容易识别出 TTS 自己的声音。第一版必须保守：barge-in ASR 只用来停播，不直接进入新问题。
- `ttsRuntime.cancel(sessionId)` 需要和当前播放 generation 对齐，避免取消新一轮通知或提醒播报。
- 如果 streaming ASR provider 对短音频不稳定，需要增加 2 秒超时和 finally 清理，避免 barge-in turn 泄漏。
- `listen.start mode=barge_in` 不能复用普通 `handleListenStart()` 的 `stopMusic()` 和 `cancelCurrentTurnPlayback()`，否则收到开始标记就立即打断。
- 如果固件在 `Speaking` 中启用 voice processor 会导致本地 decoder reset，服务端可能看不到足够音频；需与固件计划一起联调。
- 线上打开前建议先按设备或配置灰度，观察 `barge-in ignored` 与 `barge-in detected` 比例。

## 自检清单

- [ ] `mode=barge_in` 只在 `SPEAKING` 有效。
- [ ] `SPEAKING` 期间 binary 写入 barge-in ASR，不进入普通 audioFrames。
- [ ] 空文本、短文本、冷却期、回声相似文本不会取消 TTS。
- [ ] 打断后状态变为 `LISTENING`，等待固件 follow-up 窗口。
- [ ] barge-in ASR 文本不直接提交 Hermes。
- [ ] 现有普通 ASR/TTS/音乐/提醒路径测试通过。
