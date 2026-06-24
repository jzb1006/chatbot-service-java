# ESP32-S3 语音端点检测与服务端低延迟实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 基于当前 ESP32-S3 硬件方案，解决“小智下达命令到返回结果时间太长”的问题，避免语音回合因为端点检测失败拖到服务端最大时长。

**架构：** 第一阶段以 `chatbot-service-java` 服务端为主，收敛 `XiaozhiAutoListenEndpoint` 和 `chatbot.voice.auto-stop.*` 参数，让语音结束能在服务端稳定切分。ESP32-S3 固件继续负责唤醒、采集和音频上行，固件侧 AFE/VAD 日志作为观测输入，不作为第一刀主改动。

**技术栈：** Java 21、Spring Boot、WebSocket、Opus/PCM RMS endpoint detector、Sherpa ASR sidecar、ESP32-S3 + INMP441 + MAX98357A。

---

## 背景与结论

用户已经切到 ESP32-S3 硬件，并明确后续不再用 C6 做这套方案。当前文档落在 `chatbot-service-java`，因为第一阶段主实施面是服务端：auto-stop 端点检测、配置参数、日志指标和远程 `device_gateway` 生效链路。

已知慢链路的高信号证据是：服务端日志出现 `reason=MAX_DURATION_REACHED`，`audioMillis` 接近 60 秒，`asrMillis` 接近 60 秒；同时 Hermes 多数只耗时数秒。这说明主因不是语义模型，也不是 TTS，而是语音端点检测没有及时判定“用户说完了”。

当前服务端默认配置里 `CHATBOT_VOICE_AUTO_STOP_MAX_DURATION` 是 `60s`。这对防止无限等待有价值，但作为正常短命令的兜底过长。第一阶段应把它从“正常路径会撞到的上限”改成“异常路径才触发的兜底”。

## 当前硬件与边界

- 硬件：ESP32-S3-WROOM-1-N16R8。
- 固件板型：`bread-compact-wifi`。
- 麦克风：INMP441。
- 功放：MAX98357A。
- 后续不再维护 MuseLab C6 方案兼容性。
- 不恢复 C6 的固定 4.5 秒本地 auto-stop。
- 不在第一阶段强行启用完整设备侧 AEC。
- 不把 WebRTC VAD 或 Silero VAD 作为第一阶段主路径。

## 服务端代码锚点

- `chatbot-bootstrap/src/main/resources/application.yml`
  - `chatbot.voice.auto-stop.enabled`
  - `chatbot.voice.auto-stop.min-speech-duration`
  - `chatbot.voice.auto-stop.silence-duration`
  - `chatbot.voice.auto-stop.speech-rms-threshold`
  - `chatbot.voice.auto-stop.no-speech-timeout`
  - `chatbot.voice.auto-stop.max-duration`
- `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiAutoStopProperties.java`
  - auto-stop 配置默认值和参数归一化。
- `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiAutoListenEndpoint.java`
  - 当前基于 Opus 解码后的 PCM RMS 做端点检测。
  - 返回 `END_OF_UTTERANCE`、`NO_SPEECH_TIMEOUT`、`MAX_DURATION_REACHED`。
- `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
  - `xiaozhi auto-stop armed`
  - `xiaozhi auto-stop detected utterance end`
  - `xiaozhi auto-stop detected streaming utterance end`
  - `xiaozhi conversation turn`
  - `xiaozhi streaming conversation turn`
- `chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiAutoListenEndpointTest.java`
  - endpoint detector 单测入口。
- `chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeansTest.java`
  - auto-stop 配置绑定测试入口。

## 推荐方案

采用“服务端 auto-stop 先收敛 + S3 固件侧 AFE/VAD 观测 + Sherpa endpointing 后续接入”的分阶段方案。

第一阶段不改硬件主链路，不做大规模算法替换。先让现有 Java endpoint detector 对真实 S3 音频给出更可靠的结束判定，并把 60 秒兜底降到不会严重影响体验的范围。

建议目标值：

```text
正常短命令 asrMillis < 8000
空唤醒不进入 Hermes
正常回合不稳定触发 MAX_DURATION_REACHED
最大句长兜底先收敛到 10s-15s
尾部静音结束窗口先保持 800ms-1200ms
```

建议首轮配置：

```text
CHATBOT_VOICE_AUTO_STOP_ENABLED=true
CHATBOT_VOICE_AUTO_STOP_MIN_SPEECH_DURATION=180ms
CHATBOT_VOICE_AUTO_STOP_SILENCE_DURATION=900ms
CHATBOT_VOICE_AUTO_STOP_SPEECH_RMS_THRESHOLD=0.01
CHATBOT_VOICE_AUTO_STOP_NO_SPEECH_TIMEOUT=6s
CHATBOT_VOICE_AUTO_STOP_MAX_DURATION=15s
```

如果实机日志显示待机噪声导致 `speechStarted=true`，再按真实 `peakRms` 调整 `speech-rms-threshold`，不要盲目继续缩短 `max-duration`。

## 文件职责

- 修改：`chatbot-bootstrap/src/main/resources/application.yml`
  - 把默认 `CHATBOT_VOICE_AUTO_STOP_MAX_DURATION` 从 60 秒收敛到服务端可接受的体验兜底值。
  - 视实测结果调整 `NO_SPEECH_TIMEOUT` 默认值。
- 修改：`deploy/chatbot-service.env.example`
  - 补齐或同步 auto-stop 环境变量样例，避免远程部署只改代码默认值但运行态仍吃旧 env-file。
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiAutoListenEndpoint.java`
  - 只在现有 RMS 方案无法解释实机日志时修改。
  - 优先增加可观测字段或修正判断顺序，不引入大型 VAD 依赖。
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
  - 如日志不足，补充 endpoint 检测结果和配置快照。
  - 保持日志频率为 turn-level，不逐帧打印。
- 修改：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiAutoListenEndpointTest.java`
  - 覆盖短语音、空唤醒、噪声、长句兜底。
- 修改：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeansTest.java`
  - 覆盖 auto-stop 默认值和配置绑定。
- 后续固件协同：`/Users/jiangzhibin/workspace/xiaozhi-esp32`
  - 只补 S3 侧 AFE/VAD/RMS 观测日志。
  - 不把固件作为第一阶段主改动仓库。

## 任务 1：固定服务端现状基线

**文件：**
- 读取：`chatbot-bootstrap/src/main/resources/application.yml`
- 读取：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiAutoStopProperties.java`
- 读取：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiAutoListenEndpoint.java`
- 读取：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`

- [ ] **步骤 1：确认当前默认参数**

运行：

```bash
sed -n '43,50p' "chatbot-bootstrap/src/main/resources/application.yml"
sed -n '1,90p' "chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiAutoStopProperties.java"
```

预期看到：

```text
max-duration: ${CHATBOT_VOICE_AUTO_STOP_MAX_DURATION:60s}
Duration.ofSeconds(60)
```

- [ ] **步骤 2：确认 endpoint detector 判断顺序**

运行：

```bash
sed -n '1,120p' "chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiAutoListenEndpoint.java"
```

预期看到：

```text
totalMillis >= maxDurationMillis -> MAX_DURATION_REACHED
frameRms >= speechRmsThreshold -> candidateSpeechMillis
!speechStarted -> NO_SPEECH_TIMEOUT
silenceAfterSpeechMillis >= silenceMillis -> END_OF_UTTERANCE
```

- [ ] **步骤 3：确认日志字段足够支撑实机判断**

运行：

```bash
rg -n "auto-stop armed|auto-stop detected|conversation turn|asrMillis|peakRms|speechStarted" \
  "chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java"
```

预期至少覆盖：

```text
minSpeechMillis
silenceMillis
noSpeechTimeoutMillis
maxDurationMillis
rmsThreshold
reason
audioMillis
peakRms
speechStarted
asrMillis
userText
assistantText
```

## 任务 2：用测试锁定目标行为

**文件：**
- 修改：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiAutoListenEndpointTest.java`
- 修改：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeansTest.java`

- [ ] **步骤 1：补短命令结束测试**

在 `XiaozhiAutoListenEndpointTest` 增加或核对等价用例：

```java
@Test
void shouldEndUtteranceAfterSpeechAndTrailingSilence() {
    var endpoint = new XiaozhiAutoListenEndpoint(
            16000,
            60,
            new XiaozhiAutoStopProperties(
                    true,
                    Duration.ofMillis(180),
                    Duration.ofMillis(900),
                    0.01,
                    Duration.ofSeconds(6),
                    Duration.ofSeconds(15)
            )
    );

    var results = new ArrayList<XiaozhiAutoListenEndpoint.Result>();
    acceptToneFrames(endpoint, results, 5);
    acceptSilenceFrames(endpoint, results, 20);

    assertThat(results).contains(XiaozhiAutoListenEndpoint.Result.END_OF_UTTERANCE);
    assertThat(results).doesNotContain(XiaozhiAutoListenEndpoint.Result.MAX_DURATION_REACHED);
}
```

- [ ] **步骤 2：补空唤醒测试**

在 `XiaozhiAutoListenEndpointTest` 增加或核对等价用例：

```java
@Test
void shouldReturnNoSpeechTimeoutWhenNoSpeechDetected() {
    var endpoint = new XiaozhiAutoListenEndpoint(
            16000,
            60,
            new XiaozhiAutoStopProperties(
                    true,
                    Duration.ofMillis(180),
                    Duration.ofMillis(900),
                    0.01,
                    Duration.ofMillis(600),
                    Duration.ofSeconds(15)
            )
    );

    var results = new ArrayList<XiaozhiAutoListenEndpoint.Result>();
    acceptSilenceFrames(endpoint, results, 20);

    assertThat(results).contains(XiaozhiAutoListenEndpoint.Result.NO_SPEECH_TIMEOUT);
}
```

- [ ] **步骤 3：补最大时长兜底测试**

在 `XiaozhiAutoListenEndpointTest` 增加或核对等价用例：

```java
@Test
void shouldUseMaxDurationOnlyAsFallback() {
    var endpoint = new XiaozhiAutoListenEndpoint(
            16000,
            60,
            new XiaozhiAutoStopProperties(
                    true,
                    Duration.ofMillis(180),
                    Duration.ofSeconds(30),
                    0.01,
                    Duration.ofSeconds(6),
                    Duration.ofMillis(900)
            )
    );

    var results = new ArrayList<XiaozhiAutoListenEndpoint.Result>();
    acceptToneFrames(endpoint, results, 20);

    assertThat(results).contains(XiaozhiAutoListenEndpoint.Result.MAX_DURATION_REACHED);
}
```

- [ ] **步骤 4：运行 endpoint 单测**

运行：

```bash
mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiAutoListenEndpointTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：

```text
BUILD SUCCESS
```

## 任务 3：收敛服务端默认参数

**文件：**
- 修改：`chatbot-bootstrap/src/main/resources/application.yml`
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiAutoStopProperties.java`
- 修改：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeansTest.java`

- [ ] **步骤 1：修改 `application.yml` 默认值**

把：

```yaml
no-speech-timeout: ${CHATBOT_VOICE_AUTO_STOP_NO_SPEECH_TIMEOUT:8s}
max-duration: ${CHATBOT_VOICE_AUTO_STOP_MAX_DURATION:60s}
```

改为：

```yaml
no-speech-timeout: ${CHATBOT_VOICE_AUTO_STOP_NO_SPEECH_TIMEOUT:6s}
max-duration: ${CHATBOT_VOICE_AUTO_STOP_MAX_DURATION:15s}
```

- [ ] **步骤 2：修改 `XiaozhiAutoStopProperties` 默认值**

把构造器 fallback 和 `defaults()` 中的：

```java
Duration.ofSeconds(8),
Duration.ofSeconds(60)
```

改为：

```java
Duration.ofSeconds(6),
Duration.ofSeconds(15)
```

- [ ] **步骤 3：更新配置绑定测试**

在 `XiaozhiVoiceGatewayBeansTest` 中保留显式覆盖配置的测试，新增或核对默认配置测试：

```java
@Test
void shouldBindDefaultAutoStopProperties() {
    new ApplicationContextRunner()
            .withUserConfiguration(XiaozhiVoiceGatewayBeans.class)
            .run(context -> {
                var properties = context.getBean(XiaozhiAutoStopProperties.class);

                assertThat(properties.enabled()).isTrue();
                assertThat(properties.minSpeechDuration()).isEqualTo(Duration.ofMillis(180));
                assertThat(properties.silenceDuration()).isEqualTo(Duration.ofMillis(900));
                assertThat(properties.speechRmsThreshold()).isEqualTo(0.01);
                assertThat(properties.noSpeechTimeout()).isEqualTo(Duration.ofSeconds(6));
                assertThat(properties.maxDuration()).isEqualTo(Duration.ofSeconds(15));
            });
}
```

- [ ] **步骤 4：运行配置绑定测试**

运行：

```bash
mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceGatewayBeansTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：

```text
BUILD SUCCESS
```

## 任务 4：同步部署样例与运行态说明

**文件：**
- 修改：`deploy/chatbot-service.env.example`
- 修改：`docs/superpowers/plans/2026-06-24-s3-voice-endpoint-server-auto-stop-plan.md`

- [ ] **步骤 1：在 env example 中补齐 auto-stop 参数**

在 `deploy/chatbot-service.env.example` 增加或核对：

```text
CHATBOT_VOICE_AUTO_STOP_ENABLED=true
CHATBOT_VOICE_AUTO_STOP_MIN_SPEECH_DURATION=180ms
CHATBOT_VOICE_AUTO_STOP_SILENCE_DURATION=900ms
CHATBOT_VOICE_AUTO_STOP_SPEECH_RMS_THRESHOLD=0.01
CHATBOT_VOICE_AUTO_STOP_NO_SPEECH_TIMEOUT=6s
CHATBOT_VOICE_AUTO_STOP_MAX_DURATION=15s
```

- [ ] **步骤 2：远程运行态变更必须改 env-file 并重建容器**

远程 `device_gateway` 当前稳定运行态依赖：

```text
/opt/chatbot-service-java-runtime/chatbot-service.env
device_gateway
device_gateway_java:latest
hermes-net
/opt/device_gateway/logs:/app/logs
```

如果只改 repo 默认值，远程运行中的 env-file 仍可能覆盖默认值。改远程参数后需要重建容器，而不是只 `docker restart`。

## 任务 5：实机日志验收

**文件：**
- 读取：远程 `/opt/device_gateway/logs/chatbot-service.log`

- [ ] **步骤 1：执行 S3 实机测试语句**

按顺序测试：

```text
现在几点？
讲个笑话。
帮我介绍一下今天适合做什么。
唤醒后不说话。
说一句话，中间停顿 1 秒，再继续。
```

- [ ] **步骤 2：查服务端最新日志**

在远程 `device_gateway` 所在机器执行：

```bash
tail -n 2000 "/opt/device_gateway/logs/chatbot-service.log" | rg \
  "xiaozhi websocket connected|xiaozhi wake word detected|xiaozhi listen started|xiaozhi auto-stop armed|xiaozhi auto-stop detected|xiaozhi streaming conversation turn|xiaozhi conversation turn|xiaozhi streaming asr returned blank text|xiaozhi asr returned blank text"
```

- [ ] **步骤 3：按指标判断**

通过标准：

```text
正常短命令 asrMillis < 8000
正常短命令 reason 不稳定出现 MAX_DURATION_REACHED
空唤醒走 NO_SPEECH_TIMEOUT 或等价空输入处理
有 userText 且 Hermes/TTS 正常进入
```

失败判断：

```text
asrMillis 接近 15000 或 60000：端点检测仍未及时结束
reason=MAX_DURATION_REACHED 且 speechStarted=true：尾部静音或阈值判断需要继续校准
reason=NO_SPEECH_TIMEOUT 但用户确实说话：阈值过高、音频上行异常或 ASR 输入为空
userText 为空：继续查音频上行、ASR stream 和 endpoint 切分
```

## 任务 6：按实测结果选择下一刀

**文件：**
- 可能修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiAutoListenEndpoint.java`
- 可能修改：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiAutoListenEndpointTest.java`
- 可能协同：`/Users/jiangzhibin/workspace/xiaozhi-esp32`

- [ ] **步骤 1：如果仍撞 max-duration**

处理顺序：

```text
先看 peakRms 和 speechStarted
再判断 speech-rms-threshold 是否过低
再判断 silence-duration 是否过长
最后再考虑引入更强 VAD
```

- [ ] **步骤 2：如果空唤醒进入 Hermes**

处理顺序：

```text
核查 NO_SPEECH_TIMEOUT 是否生效
核查 speechStarted 是否被噪声误触发
核查 peakRms 是否高于阈值
必要时提高 speech-rms-threshold
```

- [ ] **步骤 3：如果短暂停顿被过早截断**

处理顺序：

```text
把 silence-duration 从 900ms 调到 1200ms
保留 max-duration 15s
用“停顿 1 秒继续说”实机复测
```

- [ ] **步骤 4：如果播放期间出现自听**

这时再进入固件侧 AEC 或设备侧 VAD/AEC 评估。前置条件：

```text
日志能证明播放期间麦克风把本机声音送回 ASR
当前硬件能提供稳定播放参考路径
麦克风和喇叭位置具备基本物理隔离
```

## Sherpa Endpointing 后续方向

如果 Sherpa ASR sidecar 支持 endpointing，后续更合理的边界是：

```text
S3 固件负责稳定采集和上行
Sherpa 负责 ASR-aware endpointing
Java auto-stop 保留 no-speech 和 max-duration 兜底
```

不要在没有实测前直接删除 Java auto-stop。固件、网络、ASR sidecar 任一环节异常时，服务端仍需要兜底保护。

## 成功标准

- 短命令从唤醒后到开始回复不再出现 60 秒级等待。
- 服务端最新真实日志中，正常语音回合不再稳定走 `MAX_DURATION_REACHED`。
- 空唤醒不进入 Hermes 生成回答。
- 长句不会被固定 4.5 秒截断。
- 用户说完后不会继续等到最大时长。
- 用户中间短暂停顿时不会过早截断。
- 远程 `device_gateway` 的运行态 env-file 与 repo 文档保持一致。

## 非目标

- 不恢复 C6 方案。
- 不在固件里做中文语义判断。
- 不把播放音乐当作 TTS 路径处理。
- 不在第一阶段强行启用设备侧 AEC。
- 不把服务端 WebRTC VAD 或 Silero VAD 作为第一优先级。
- 不提交、不推送、不刷机，除非用户明确要求。

## 推荐下一步

1. 先按任务 1 到任务 3 收敛服务端默认参数和测试。
2. 改远程 env-file 并重建 `device_gateway`，确保运行态真的使用新参数。
3. 用 S3 实机跑任务 5 的语句和日志验收。
4. 如果仍慢，再按任务 6 判断是阈值、尾部静音、音频上行还是 ASR endpointing 问题。
