# 小智 Hermes 音乐播放实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让 Hermes agent 处理音乐意图与音乐源，Java voice-gateway 只作为独立音频播放执行器把 Hermes 返回的音乐音频下发到小智设备。

**架构：** 音乐能力不进入 TTS provider、`TextToSpeechClient` 或 `StreamingTextToSpeechClient`，也不把音乐音频包装成 TTS 文本。Hermes 通过 `xiaozhi.agent_event` 返回结构化 `music_*` 动作；Java 校验动作参数后交给独立 `XiaozhiMusicPlaybackRuntime`。TTS runtime 只通过窄协作接口暂停/恢复音乐，音乐 runtime 通过 `media_url` 拉取音频，使用 `ffmpeg` 解码为 16k mono PCM，复用现有 `StreamingPcmToOpusEncoder` 输出 Opus 帧，再通过小智 WebSocket binary 下发。

**技术栈：** Java 21、Spring Boot 3.4、Spring WebSocket、Maven 多模块、JUnit 5、AssertJ、Jackson、Concentus Opus、`ffmpeg`、Hermes SSE、WebSocket binary。

---

## 关键决策

- 音乐意图、歌曲搜索、歌单选择、音乐源解析全部由 Hermes agent 负责。
- Java 第一版只接受 Hermes 返回的 `media_url`，不调用外部音乐平台 API，不做歌曲搜索。
- Hermes 必须具备 `music_search` 工具或等价音乐源解析能力，把歌名/歌手解析成合法 `media_url`；仅靠固定提示词不能稳定生成音乐源。
- 第一版音乐源候选为 Jamendo、Audius 或自建白名单媒体库，实施前只选择一个作为主源。未选定主源并完成 Hermes 工具接入前，不进入 Java 播放实现。
- Hermes 禁止编造 `media_url`。如果用户只给歌名/歌手，Hermes 必须先调用 `music_search`；工具没有返回可播放 URL 时，只能回复简短失败文本，不能输出 `music_play`。
- 音乐播放不走 `TextToSpeechClient`、`StreamingTextToSpeechClient` 或任何 TTS provider，避免把长音频误当 TTS 文本。
- `XiaozhiTtsRuntime` 允许持有一个可选的 music runtime 协作接口，仅用于在发送 `tts.start` 前暂停音乐、发送 `tts.stop` 后恢复音乐；不得调用音乐解码、音乐帧发送或音乐源解析。
- 音乐 runtime 可以复用 `XiaozhiMessageCodec.encodeAudioFrame(...)` 和 `StreamingPcmToOpusEncoder`，但必须有独立生命周期和取消逻辑。
- 第一版不改 ESP32 固件，不新增设备端 MCP 工具；如果固件必须依赖 `tts.start/tts.stop` 才播放 binary，Java 只能把这些帧作为协议兼容控制帧使用，不能把音乐接入 TTS provider。
- 音乐播放默认关闭，通过配置显式启用；容器镜像必须包含 `ffmpeg` 后才能打开。
- TTS 与音乐共用同一 WebSocket session，下发必须避免并发 `sendMessage`。TTS 开始前的音乐暂停必须等待音乐帧发送进入静默状态，最多等待一个 Opus 帧周期加安全余量。
- `media_url` 只允许白名单公网 HTTP/HTTPS 域名。即使 host 命中白名单，也必须解析 DNS 后拒绝 loopback、link-local、private、multicast、carrier-grade NAT 等地址；第一版不跟随 3xx redirect。
- 本计划不包含 git commit、push 或分支操作；实现完成后需由用户明确确认再处理提交。

## 成功标准

- Hermes 返回 `music_play` 且带合法 `media_url` 时，设备收到连续 binary Opus 音频帧。
- Hermes 的 `music_search` 工具能把歌名/歌手解析为 `title`、`artist`、`media_url`、`source`、`license`；工具找不到结果时 Hermes 不输出 `music_play`。
- Hermes 返回 `music_pause`、`music_resume`、`music_stop` 时，Java 更新对应设备的音乐播放状态，且不会影响 TTS runtime 的内部状态。
- 用户开始新一轮语音输入、`abort`、`session.new`、WebSocket 关闭时，当前设备音乐播放能被取消，不继续下发旧音频。
- TTS 播报开始时，音乐自动暂停并确认没有音乐线程正在写 WebSocket；TTS 播报结束后，只有非手动暂停的音乐才自动恢复。
- Java 不根据自然语言创建音乐动作；Hermes 不返回 `music_*` 事件时 Java 不播放音乐。
- `media_url` 必须是 `http` 或 `https`，禁止本地文件、内网地址、DNS 解析到私网地址、3xx redirect 和非白名单协议。
- `chatbot.voice.music.allowed-hosts` 必须与选定音乐源的实际音频域名一致；未配置白名单时音乐播放保持关闭。
- 验证命令使用 `/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-speech-api,chatbot-voice-gateway -am test`，预期 `BUILD SUCCESS`。

## 非目标

- 不实现音乐平台搜索、推荐、榜单、歌词、歌单管理。
- Java 不实现音乐源搜索、解析、聚合或 fallback；这些都由 Hermes 工具承担。
- 不实现缓存、断点续播、下一首、随机播放。
- 不改小智固件，不依赖设备端新增 `self.music.play_song` 工具。
- 不把音乐音频转成文本，不经由 TTS provider 合成。
- 不新增数据库表或持久化播放状态。

## Hermes 事件契约

Hermes 在输出 `music_play` 前必须先具备音乐源：

```json
{"tool":"music_search","arguments":{"query":"周杰伦 稻香","title":"稻香","artist":"周杰伦"}}
```

`music_search` 返回：

```json
{"title":"稻香","artist":"周杰伦","media_url":"https://cdn.example.com/daoxiang.mp3","source":"jamendo","license":"cc-by"}
```

工具约束：

- `media_url` 必须来自已选定的白名单音乐源，不能由模型编造。
- 返回字段至少包含 `title`、`artist`、`media_url`、`source`、`license`。
- 找不到可播放 URL 时返回空结果或明确错误，Hermes 只能回复普通文本，不输出 `music_play`。
- 第一版候选源只允许选一个：Jamendo、Audius 或自建白名单媒体库；不要同时接多个源做聚合排序。

Hermes 通过 SSE chunk 返回：

```text
event: xiaozhi.agent_event
data: {"action":"music_play","title":"稻香","artist":"周杰伦","media_url":"https://example.com/daoxiang.mp3","confirmation_text":"开始播放稻香"}
```

支持动作：

```json
{"action":"music_play","title":"稻香","artist":"周杰伦","media_url":"https://example.com/daoxiang.mp3","confirmation_text":"开始播放稻香"}
{"action":"music_pause"}
{"action":"music_resume"}
{"action":"music_stop"}
{"action":"music_seek","position_seconds":60}
```

第一版只要求 `music_play`、`music_pause`、`music_resume`、`music_stop` 闭环；`music_seek` 先解析和拒绝为明确错误，不实现跳转。

## 在线音乐源选型边界

第一版只选一个音乐源接入 Hermes `music_search` 工具，不在 Java 侧做多源聚合、排序或 fallback。

| 候选源 | 适用性 | 接入要求 | 第一版结论 |
| --- | --- | --- | --- |
| Jamendo | 有公开曲库、搜索 API、音频 URL 和授权字段，适合作为第一版可控音乐源 | 需要 `client_id`；Hermes 工具必须读取 `audio` 或等价可播放 URL，并返回 license；Java `allowed-hosts` 必须配置实际音频域名 | 优先评估 |
| Audius | 有公开音乐 catalog、REST API/SDK，可搜索和流式播放曲目 | 需要 API key；Hermes 工具必须把 track 查询结果转换为最终可播放 `media_url`，并记录来源 | 备选 |
| 自建白名单媒体库 | 音频、版权和域名最可控，适合 demo、私有音频或固定歌单 | 需要维护曲目索引和 HTTPS 静态资源域名；Hermes 工具只查自建索引 | 授权/稳定性优先时使用 |
| Free Music Archive | 公开资料显示历史上提供过 app API，但当前可用性和接口稳定性不适合作为第一版依赖 | 实施前必须重新确认当前 API、授权和可播放 URL | 暂不选 |

执行前闸门：

```text
必须先确定 selected_music_source=jamendo|audius|self_hosted
必须拿到一次 music_search 成功样例
必须记录 media_url 的最终 host
必须把最终 host 写入 chatbot.voice.music.allowed-hosts
必须验证工具失败时 Hermes 不输出 music_play
```

## 文件结构

- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/hermes/HermesAgentEvent.java`
  - 职责：扩展 Hermes agent 事件字段，承载音乐动作需要的 `mediaUrl`、`title`、`artist`、`positionSeconds`。
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/hermes/HermesAgentEventExtractor.java`
  - 职责：从 `xiaozhi.agent_event` 中提取音乐动作字段，坏 JSON 和未知字段继续容错忽略。
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/XiaozhiMusicAction.java`
  - 职责：音乐动作值对象，封装 action、title、artist、mediaUrl、positionSeconds、confirmationText。
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/XiaozhiMusicActionHandler.java`
  - 职责：把 Hermes agent event 转成音乐 runtime 调用；不做自然语言理解。
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/XiaozhiMusicPlaybackRuntime.java`
  - 职责：管理每台设备的音乐播放任务、暂停/恢复/停止、TTS 自动暂停协作。
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/XiaozhiMusicPlaybackRequest.java`
  - 职责：封装一次音乐播放请求，包含 WebSocket session、voice session、title、artist、mediaUrl。
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/XiaozhiMusicPlaybackState.java`
  - 职责：记录单设备当前音乐播放状态，区分 `PLAYING`、`PAUSED_MANUAL`、`PAUSED_TTS`、`STOPPED`。
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/MusicAudioSource.java`
  - 职责：校验并打开合法 `media_url` 输入流；执行 scheme、host allowlist、DNS 地址和 redirect 安全校验。
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/MusicHostResolver.java`
  - 职责：封装 DNS 解析 seam，生产使用系统 DNS，测试注入固定地址。
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/FfmpegMusicDecoder.java`
  - 职责：启动 `ffmpeg` 子进程，从 Java 已校验的输入流读取媒体，把音频解码为 16k mono signed 16-bit little-endian PCM。
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/MusicFrameSender.java`
  - 职责：把 PCM 增量送入 `StreamingPcmToOpusEncoder`，按 60ms 节奏通过 WebSocket binary 下发，并在暂停时停止发帧。
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/XiaozhiMusicPlaybackProperties.java`
  - 职责：音乐播放配置，包含 enabled、ffmpegPath、connectTimeout、maxDuration、allowedHosts。
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
  - 职责：收到 Hermes 音乐事件时调用 music handler；TTS 开始/结束时通知 music runtime 自动暂停/恢复。
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSession.java`
  - 职责：在 `markListening()`、`startAsrStream()`、`startNewConversation()`、`clearConversation()` 中暴露音乐停止协作点，避免旧音乐继续播放。
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeans.java`
  - 职责：注册音乐 runtime、handler、decoder、properties bean。
- 修改：`chatbot-bootstrap/src/main/resources/application.yml`
  - 职责：新增 `chatbot.voice.music.*` 默认配置，默认关闭。
- 修改：`Dockerfile`
  - 职责：运行时镜像安装 `ffmpeg`，供音乐解码使用。
- 修改：`deploy/Dockerfile`
  - 职责：部署镜像安装 `ffmpeg`，保持本地与远程一致。

## 任务 0：确认 Hermes 音乐源工具前置条件

**文件：**
- 修改：Hermes agent 系统提示词或工具配置文件，具体路径以远程 Hermes live 配置为准
- 测试：Hermes `music_search` 工具手工 smoke

- [ ] **步骤 1：选择第一版音乐源**

只选择一个主源：

```text
候选 A：Jamendo
候选 B：Audius
候选 C：自建白名单媒体库
```

优先级：

```text
1. Jamendo：优先评估，目标是用公开曲库、音频 URL 和 license 字段快速闭环。
2. Audius：当 Jamendo 曲库覆盖不足或接入限制不合适时评估。
3. 自建白名单媒体库：当版权、稳定性或演示可控性优先时使用。
```

选择标准：

```text
必须能通过工具返回可直接播放的 http/https media_url
必须能给出 source 和 license
必须能把实际音频域名配置进 Java allowed-hosts
不需要 Java 调用该音乐源 API
```

- [ ] **步骤 2：定义 Hermes 工具契约**

Hermes 工具名固定为 `music_search`，输入：

```json
{"query":"周杰伦 稻香","title":"稻香","artist":"周杰伦"}
```

成功输出：

```json
{"title":"稻香","artist":"周杰伦","media_url":"https://cdn.example.com/daoxiang.mp3","source":"jamendo","license":"cc-by"}
```

失败输出：

```json
{"error":"not_found","message":"没有找到可播放的音乐源"}
```

- [ ] **步骤 3：更新 Hermes 提示词规则**

将以下规则写入 Hermes agent 的系统提示词或工具路由规则：

```text
当用户请求播放音乐：
1. 如果用户提供合法 http/https URL，且域名属于允许的音乐源域名，可以输出 music_play。
2. 如果用户只提供歌名、歌手或模糊描述，必须先调用 music_search。
3. 只有 music_search 返回 media_url 时，才能输出 xiaozhi.agent_event/music_play。
4. 如果 music_search 失败或没有 media_url，只回复简短失败文本，不输出 music_play。
5. 禁止编造 media_url。
6. 禁止输出 file://、localhost、内网地址或非白名单域名。
```

- [ ] **步骤 4：运行 Hermes 工具 smoke**

用 Hermes 工具调用验证：

```json
{"tool":"music_search","arguments":{"query":"测试音乐","title":"测试音乐","artist":""}}
```

预期：

```text
成功：返回 title、artist、media_url、source、license
失败：返回 error=not_found，且 Hermes 不输出 music_play
```

记录实际 `media_url` host，后续必须写入 `chatbot.voice.music.allowed-hosts`。

- [ ] **步骤 5：冻结音乐源选择**

在实施 Java 播放链路前，写下最终选择：

```text
selected_music_source=<jamendo|audius|self_hosted>
music_search_success_sample=<一次真实成功返回>
allowed_hosts=<media_url 最终音频域名列表>
license_policy=<只播放工具明确返回 license 的结果>
```

未完成这一步时，不进入任务 1。

## 任务 1：扩展 Hermes 音乐事件契约

**文件：**
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/hermes/HermesAgentEvent.java`
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/hermes/HermesAgentEventExtractor.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/hermes/HermesAgentEventExtractorTest.java`

- [ ] **步骤 1：编写失败测试，验证音乐事件字段可解析**

在 `HermesAgentEventExtractorTest` 增加：

```java
@Test
void shouldExtractMusicPlayEvent() {
    var extractor = new HermesAgentEventExtractor();

    var events = extractor.accept("""
            event: xiaozhi.agent_event
            data: {"action":"music_play","title":"稻香","artist":"周杰伦","media_url":"https://example.com/daoxiang.mp3","confirmation_text":"开始播放稻香"}

            """);

    assertThat(events).containsExactly(new HermesAgentEvent(
            "music_play",
            null,
            0,
            "开始播放稻香",
            "https://example.com/daoxiang.mp3",
            "稻香",
            "周杰伦",
            0
    ));
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=HermesAgentEventExtractorTest#shouldExtractMusicPlayEvent test
```

预期：FAIL，`HermesAgentEvent` 构造参数数量不匹配或字段不存在。

- [ ] **步骤 3：扩展事件 record**

将 `HermesAgentEvent` 改为：

```java
public record HermesAgentEvent(
        String action,
        String message,
        long delaySeconds,
        String confirmationText,
        String mediaUrl,
        String title,
        String artist,
        long positionSeconds
) {
}
```

- [ ] **步骤 4：扩展 extractor 字段读取**

在 `HermesAgentEventExtractor.extractEvent(...)` 中读取：

```java
return java.util.Optional.of(new HermesAgentEvent(
        root.path("action").asText(null),
        root.path("message").asText(null),
        root.path("delay_seconds").asLong(0L),
        root.path("confirmation_text").asText(null),
        root.path("media_url").asText(null),
        root.path("title").asText(null),
        root.path("artist").asText(null),
        root.path("position_seconds").asLong(0L)
));
```

同步更新既有测试中的 `new HermesAgentEvent(...)` 断言，补齐新增参数的默认值。

- [ ] **步骤 5：运行测试验证通过**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=HermesAgentEventExtractorTest test
```

预期：PASS。

## 任务 2：定义音乐动作模型和 URL 安全校验

**文件：**
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/XiaozhiMusicAction.java`
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/XiaozhiMusicPlaybackProperties.java`
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/MusicHostResolver.java`
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/MusicAudioSource.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/music/MusicAudioSourceTest.java`

- [ ] **步骤 1：编写失败测试，拒绝非 HTTP URL**

创建 `MusicAudioSourceTest`：

```java
package com.jzb.chatbot.voice.music;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MusicAudioSourceTest {

    @Test
    void shouldRejectLocalFileUrl() {
        var source = new MusicAudioSource(new XiaozhiMusicPlaybackProperties(
                true,
                "ffmpeg",
                Duration.ofSeconds(3),
                Duration.ofMinutes(5),
                Set.of("example.com")
        ), host -> List.of(InetAddress.getByName("93.184.216.34")));

        assertThatThrownBy(() -> source.validate("file:///tmp/song.mp3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("music media_url must use http or https");
    }

    @Test
    void shouldRejectHostOutsideAllowList() {
        var source = new MusicAudioSource(new XiaozhiMusicPlaybackProperties(
                true,
                "ffmpeg",
                Duration.ofSeconds(3),
                Duration.ofMinutes(5),
                Set.of("example.com")
        ), host -> List.of(InetAddress.getByName("93.184.216.34")));

        assertThatThrownBy(() -> source.validate("https://evil.example.org/song.mp3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("music media_url host is not allowed");
    }

    @Test
    void shouldRejectAllowedHostResolvedToPrivateAddress() {
        var source = new MusicAudioSource(new XiaozhiMusicPlaybackProperties(
                true,
                "ffmpeg",
                Duration.ofSeconds(3),
                Duration.ofMinutes(5),
                Set.of("example.com")
        ), host -> List.of(InetAddress.getByName("127.0.0.1")));

        assertThatThrownBy(() -> source.validate("https://example.com/song.mp3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("music media_url resolved to non-public address");
    }

    @Test
    void shouldCreateHttpClientWithoutRedirectFollowing() {
        var source = new MusicAudioSource(new XiaozhiMusicPlaybackProperties(
                true,
                "ffmpeg",
                Duration.ofSeconds(3),
                Duration.ofMinutes(5),
                Set.of("example.com")
        ), host -> List.of(InetAddress.getByName("93.184.216.34")));

        assertThat(source.followRedirects()).isEqualTo(java.net.http.HttpClient.Redirect.NEVER);
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=MusicAudioSourceTest test
```

预期：FAIL，类不存在。

- [ ] **步骤 3：创建配置 record**

创建 `XiaozhiMusicPlaybackProperties`：

```java
package com.jzb.chatbot.voice.music;

import java.time.Duration;
import java.util.Set;

/**
 * 小智音乐播放配置。
 * <p>
 * 控制音乐播放开关、ffmpeg 路径、超时和允许访问的媒体域名。
 *
 * @author jiangzhibin
 * @since 2026-06-18 00:00:00
 */
public record XiaozhiMusicPlaybackProperties(
        boolean enabled,
        String ffmpegPath,
        Duration connectTimeout,
        Duration maxDuration,
        Set<String> allowedHosts
) {
}
```

- [ ] **步骤 4：创建音乐动作 record**

创建 `XiaozhiMusicAction`：

```java
package com.jzb.chatbot.voice.music;

import com.jzb.chatbot.voice.hermes.HermesAgentEvent;

/**
 * 小智音乐动作。
 * <p>
 * 承载 Hermes agent 返回的音乐播放控制指令，Java 不从自然语言中推断音乐意图。
 *
 * @author jiangzhibin
 * @since 2026-06-18 00:00:00
 */
public record XiaozhiMusicAction(
        String action,
        String title,
        String artist,
        String mediaUrl,
        long positionSeconds,
        String confirmationText
) {

    public static XiaozhiMusicAction from(HermesAgentEvent event) {
        return new XiaozhiMusicAction(
                event.action(),
                event.title(),
                event.artist(),
                event.mediaUrl(),
                event.positionSeconds(),
                event.confirmationText()
        );
    }
}
```

- [ ] **步骤 5：创建 URL 校验类**

创建 `MusicHostResolver`：

```java
package com.jzb.chatbot.voice.music;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * 音乐媒体域名解析器。
 * <p>
 * 封装 DNS 解析，便于测试覆盖 SSRF 防护边界。
 *
 * @author jiangzhibin
 * @since 2026-06-18 00:00:00
 */
@FunctionalInterface
public interface MusicHostResolver {

    List<InetAddress> resolve(String host) throws UnknownHostException;

    static MusicHostResolver system() {
        return host -> List.of(InetAddress.getAllByName(host));
    }
}
```

创建 `MusicAudioSource`：

```java
package com.jzb.chatbot.voice.music;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * 音乐音频源。
 * <p>
 * 负责校验 Hermes 返回的媒体地址，避免 voice-gateway 访问本地文件或非授权地址。
 *
 * @author jiangzhibin
 * @since 2026-06-18 00:00:00
 */
public class MusicAudioSource {

    private final XiaozhiMusicPlaybackProperties properties;
    private final MusicHostResolver hostResolver;
    private final HttpClient httpClient;

    public MusicAudioSource(XiaozhiMusicPlaybackProperties properties, MusicHostResolver hostResolver) {
        this.properties = properties;
        this.hostResolver = hostResolver == null ? MusicHostResolver.system() : hostResolver;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public URI validate(String mediaUrl) {
        if (mediaUrl == null || mediaUrl.isBlank()) {
            throw new IllegalArgumentException("music media_url is required");
        }
        var uri = URI.create(mediaUrl);
        var scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("music media_url must use http or https");
        }
        var host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("music media_url host is required");
        }
        var allowedHosts = properties.allowedHosts();
        if (allowedHosts != null && !allowedHosts.isEmpty() && !allowedHosts.contains(host)) {
            throw new IllegalArgumentException("music media_url host is not allowed");
        }
        validateResolvedAddresses(host);
        return uri;
    }

    public HttpClient.Redirect followRedirects() {
        return httpClient.followRedirects();
    }

    public OpenedMusic open(String mediaUrl) throws IOException {
        var uri = validate(mediaUrl);
        var request = HttpRequest.newBuilder(uri)
                .timeout(properties.connectTimeout())
                .GET()
                .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                response.body().close();
                throw new IllegalArgumentException("music media_url redirect is not allowed");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw new IllegalArgumentException("music media_url returned non-success status");
            }
            return new OpenedMusic(uri, response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while opening music media_url", exception);
        }
    }

    private void validateResolvedAddresses(String host) {
        try {
            var addresses = hostResolver.resolve(host);
            if (addresses == null || addresses.isEmpty()) {
                throw new IllegalArgumentException("music media_url host cannot be resolved");
            }
            for (var address : addresses) {
                if (!publicAddress(address)) {
                    throw new IllegalArgumentException("music media_url resolved to non-public address");
                }
            }
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("music media_url host cannot be resolved", exception);
        }
    }

    private boolean publicAddress(InetAddress address) {
        return !address.isAnyLocalAddress()
                && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress()
                && !address.isSiteLocalAddress()
                && !address.isMulticastAddress()
                && !carrierGradeNat(address);
    }

    private boolean carrierGradeNat(InetAddress address) {
        var bytes = address.getAddress();
        return bytes.length == 4
                && Byte.toUnsignedInt(bytes[0]) == 100
                && Byte.toUnsignedInt(bytes[1]) >= 64
                && Byte.toUnsignedInt(bytes[1]) <= 127;
    }

    public record OpenedMusic(URI uri, java.io.InputStream inputStream) implements AutoCloseable {

        @Override
        public void close() throws IOException {
            inputStream.close();
        }
    }
}
```

- [ ] **步骤 6：运行测试验证通过**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=MusicAudioSourceTest test
```

预期：PASS。

## 任务 3：实现 ffmpeg 音频解码边界

**文件：**
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/FfmpegMusicDecoder.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/music/FfmpegMusicDecoderTest.java`

- [ ] **步骤 1：编写失败测试，验证命令参数不经过 shell 拼接**

创建 `FfmpegMusicDecoderTest`：

```java
package com.jzb.chatbot.voice.music;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FfmpegMusicDecoderTest {

    @Test
    void shouldBuildFfmpegCommandWithoutShell() {
        var decoder = new FfmpegMusicDecoder("ffmpeg");

        assertThat(decoder.command())
                .containsExactly(
                        "ffmpeg",
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-i",
                        "pipe:0",
                        "-vn",
                        "-ac",
                        "1",
                        "-ar",
                        "16000",
                        "-f",
                        "s16le",
                        "pipe:1"
                );
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=FfmpegMusicDecoderTest test
```

预期：FAIL，类不存在。

- [ ] **步骤 3：创建 decoder**

创建 `FfmpegMusicDecoder`：

```java
package com.jzb.chatbot.voice.music;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * ffmpeg 音乐解码器。
 * <p>
 * 将远端媒体解码为小智播放链路需要的 16k mono signed 16-bit little-endian PCM。
 *
 * @author jiangzhibin
 * @since 2026-06-18 00:00:00
 */
public class FfmpegMusicDecoder {

    private final String ffmpegPath;

    public FfmpegMusicDecoder(String ffmpegPath) {
        this.ffmpegPath = ffmpegPath == null || ffmpegPath.isBlank() ? "ffmpeg" : ffmpegPath;
    }

    public List<String> command() {
        return List.of(
                ffmpegPath,
                "-hide_banner",
                "-loglevel",
                "error",
                "-i",
                "pipe:0",
                "-vn",
                "-ac",
                "1",
                "-ar",
                "16000",
                "-f",
                "s16le",
                "pipe:1"
        );
    }

    public DecodedMusic decode(InputStream mediaStream) throws IOException {
        var process = new ProcessBuilder(command())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        Thread.ofVirtual().name("xiaozhi-music-ffmpeg-stdin").start(() -> copyToProcess(mediaStream, process));
        return new DecodedMusic(process, process.getInputStream());
    }

    private void copyToProcess(InputStream mediaStream, Process process) {
        try (mediaStream; var stdin = process.getOutputStream()) {
            mediaStream.transferTo(stdin);
        } catch (IOException exception) {
            process.destroy();
        }
    }

    public record DecodedMusic(Process process, InputStream pcmStream) implements AutoCloseable {

        @Override
        public void close() throws IOException {
            pcmStream.close();
            if (process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(500L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
        }
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=FfmpegMusicDecoderTest test
```

预期：PASS。

## 任务 4：实现音乐帧发送器

**文件：**
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/MusicFrameSender.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/music/MusicFrameSenderTest.java`

- [ ] **步骤 1：编写失败测试，验证 PCM 会转为 binary 下发**

创建 `MusicFrameSenderTest`：

```java
package com.jzb.chatbot.voice.music;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jzb.chatbot.voice.TestWebSocketSession;
import com.jzb.chatbot.voice.XiaozhiVoiceSession;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

class MusicFrameSenderTest {

    @Test
    void shouldSendBinaryOpusFramesFromPcm() throws Exception {
        var webSocketSession = new TestWebSocketSession("ws-session-1");
        var voiceSession = new XiaozhiVoiceSession("session-1");
        voiceSession.updateProtocolVersion(1);
        var sender = new MusicFrameSender(new XiaozhiMessageCodec(new ObjectMapper()));
        var pcm = new byte[16000 / 1000 * 60 * Short.BYTES];

        var sentFrames = sender.send(
                webSocketSession,
                voiceSession,
                new ByteArrayInputStream(pcm),
                () -> false,
                () -> false
        );

        assertThat(sentFrames).isGreaterThan(0);
        assertThat(webSocketSession.getSentMessages()).filteredOn(org.springframework.web.socket.BinaryMessage.class::isInstance).isNotEmpty();
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=MusicFrameSenderTest test
```

预期：FAIL，类不存在。

- [ ] **步骤 3：创建 frame sender**

创建 `MusicFrameSender`，复用 `StreamingPcmToOpusEncoder`：

```java
package com.jzb.chatbot.voice.music;

import com.jzb.chatbot.speech.StreamingPcmToOpusEncoder;
import com.jzb.chatbot.voice.XiaozhiVoiceSession;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 音乐音频帧发送器。
 * <p>
 * 将 PCM 流增量编码为 Opus，并通过小智 WebSocket binary 下发。
 *
 * @author jiangzhibin
 * @since 2026-06-18 00:00:00
 */
public class MusicFrameSender {

    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_DURATION_MS = 60;
    private static final int READ_BUFFER_BYTES = SAMPLE_RATE / 1000 * FRAME_DURATION_MS * Short.BYTES;

    private final XiaozhiMessageCodec codec;

    public MusicFrameSender(XiaozhiMessageCodec codec) {
        this.codec = codec;
    }

    public int send(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            InputStream pcmStream,
            BooleanSupplier paused,
            BooleanSupplier cancelled
    ) throws IOException {
        return send(webSocketSession, voiceSession, pcmStream, paused, cancelled, () -> {
        }, () -> {
        });
    }

    public int send(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            InputStream pcmStream,
            BooleanSupplier paused,
            BooleanSupplier cancelled,
            Runnable beforeFrameSend,
            Runnable afterFrameSend
    ) throws IOException {
        var encoder = new StreamingPcmToOpusEncoder(SAMPLE_RATE, FRAME_DURATION_MS);
        var buffer = new byte[READ_BUFFER_BYTES];
        var sentFrames = 0;
        var read = pcmStream.read(buffer);
        while (read >= 0 && !cancelled.getAsBoolean()) {
            waitIfPaused(paused, cancelled);
            for (var frame : encoder.accept(read == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, read))) {
                if (cancelled.getAsBoolean()) {
                    return sentFrames;
                }
                beforeFrameSend.run();
                try {
                    webSocketSession.sendMessage(new BinaryMessage(
                            codec.encodeAudioFrame(voiceSession.protocolVersion(), 0, frame)
                    ));
                } finally {
                    afterFrameSend.run();
                }
                sentFrames++;
                sleepFrameInterval(cancelled);
            }
            read = pcmStream.read(buffer);
        }
        for (var frame : encoder.flush()) {
            if (cancelled.getAsBoolean()) {
                return sentFrames;
            }
            beforeFrameSend.run();
            try {
                webSocketSession.sendMessage(new BinaryMessage(
                        codec.encodeAudioFrame(voiceSession.protocolVersion(), 0, frame)
                ));
            } finally {
                afterFrameSend.run();
            }
            sentFrames++;
        }
        return sentFrames;
    }

    private void waitIfPaused(BooleanSupplier paused, BooleanSupplier cancelled) {
        while (paused.getAsBoolean() && !cancelled.getAsBoolean()) {
            sleepMillis(20L);
        }
    }

    private void sleepFrameInterval(BooleanSupplier cancelled) {
        if (!cancelled.getAsBoolean()) {
            sleepMillis(FRAME_DURATION_MS);
        }
    }

    private void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=MusicFrameSenderTest test
```

预期：PASS。

## 任务 5：实现音乐播放 runtime

**文件：**
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/XiaozhiMusicPlaybackRequest.java`
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/XiaozhiMusicPlaybackState.java`
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/XiaozhiMusicPlaybackRuntime.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/music/XiaozhiMusicPlaybackRuntimeTest.java`

- [ ] **步骤 1：编写失败测试，播放新音乐会停止旧任务**

创建 `XiaozhiMusicPlaybackRuntimeTest`：

```java
package com.jzb.chatbot.voice.music;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jzb.chatbot.voice.TestWebSocketSession;
import com.jzb.chatbot.voice.XiaozhiVoiceSession;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class XiaozhiMusicPlaybackRuntimeTest {

    @Test
    void shouldStopPreviousPlaybackWhenNewMusicStarts() {
        var properties = new XiaozhiMusicPlaybackProperties(
                true,
                "ffmpeg",
                Duration.ofSeconds(3),
                Duration.ofMinutes(5),
                Set.of("example.com")
        );
        var runtime = new XiaozhiMusicPlaybackRuntime(
                new MusicAudioSource(properties, host -> List.of(InetAddress.getByName("93.184.216.34"))),
                new TestFfmpegMusicDecoder(),
                new MusicFrameSender(new XiaozhiMessageCodec(new ObjectMapper())),
                properties
        );
        var webSocketSession = new TestWebSocketSession("ws-session-1");
        var voiceSession = new XiaozhiVoiceSession("session-1");
        voiceSession.updateHandshake(null, "device-1", "client-1", 1);

        runtime.play(new XiaozhiMusicPlaybackRequest(
                webSocketSession,
                voiceSession,
                "第一首",
                "歌手",
                "https://example.com/one.mp3"
        ));
        runtime.play(new XiaozhiMusicPlaybackRequest(
                webSocketSession,
                voiceSession,
                "第二首",
                "歌手",
                "https://example.com/two.mp3"
        ));

        assertThat(runtime.state("device-1").title()).isEqualTo("第二首");
    }
}
```

测试中的 `DummyProcess` 可作为 test 内部类实现，所有抽象方法返回安全默认值。`TestFfmpegMusicDecoder` 继承 `FfmpegMusicDecoder`，覆盖 `decode(...)` 返回 `DummyProcess` 和空 PCM 流。

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiMusicPlaybackRuntimeTest test
```

预期：FAIL，类不存在。

- [ ] **步骤 3：创建播放请求 record**

创建 `XiaozhiMusicPlaybackRequest`：

```java
package com.jzb.chatbot.voice.music;

import com.jzb.chatbot.voice.XiaozhiVoiceSession;
import org.springframework.web.socket.WebSocketSession;

public record XiaozhiMusicPlaybackRequest(
        WebSocketSession webSocketSession,
        XiaozhiVoiceSession voiceSession,
        String title,
        String artist,
        String mediaUrl
) {
}
```

- [ ] **步骤 4：创建播放状态 record**

创建 `XiaozhiMusicPlaybackState`：

```java
package com.jzb.chatbot.voice.music;

public record XiaozhiMusicPlaybackState(
        String deviceId,
        String title,
        String artist,
        Status status,
        PauseSource pauseSource
) {

    public enum Status {
        PLAYING,
        PAUSED,
        STOPPED
    }

    public enum PauseSource {
        MANUAL,
        TTS
    }
}
```

- [ ] **步骤 5：创建 runtime 主体**

实现 `XiaozhiMusicPlaybackRuntime`：

```java
package com.jzb.chatbot.voice.music;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class XiaozhiMusicPlaybackRuntime {

    private static final long PAUSE_IDLE_TIMEOUT_MS = 90L;
    private static final long PAUSE_IDLE_POLL_MS = 10L;

    private final MusicAudioSource audioSource;
    private final FfmpegMusicDecoder decoder;
    private final MusicFrameSender frameSender;
    private final XiaozhiMusicPlaybackProperties properties;
    private final Map<String, PlaybackTask> tasks = new ConcurrentHashMap<>();

    public XiaozhiMusicPlaybackRuntime(
            MusicAudioSource audioSource,
            FfmpegMusicDecoder decoder,
            MusicFrameSender frameSender,
            XiaozhiMusicPlaybackProperties properties
    ) {
        this.audioSource = audioSource;
        this.decoder = decoder;
        this.frameSender = frameSender;
        this.properties = properties;
    }

    public void play(XiaozhiMusicPlaybackRequest request) {
        var deviceId = request.voiceSession().deviceId();
        stop(deviceId);
        var task = new PlaybackTask(request);
        tasks.put(deviceId, task);
        Thread.ofVirtual().name("xiaozhi-music-" + deviceId).start(() -> run(deviceId, task));
    }

    public void pause(String deviceId, XiaozhiMusicPlaybackState.PauseSource source) {
        var task = tasks.get(deviceId);
        if (task != null) {
            task.pause(source);
            task.awaitIdle(PAUSE_IDLE_TIMEOUT_MS);
        }
    }

    public void resume(String deviceId, XiaozhiMusicPlaybackState.PauseSource source) {
        var task = tasks.get(deviceId);
        if (task != null) {
            task.resume(source);
        }
    }

    public void stop(String deviceId) {
        var task = tasks.remove(deviceId);
        if (task != null) {
            task.cancel();
        }
    }

    public XiaozhiMusicPlaybackState state(String deviceId) {
        var task = tasks.get(deviceId);
        return task == null ? new XiaozhiMusicPlaybackState(
                deviceId, null, null, XiaozhiMusicPlaybackState.Status.STOPPED, null
        ) : task.state();
    }

    private void run(String deviceId, PlaybackTask task) {
        try {
            var deadline = System.nanoTime() + properties.maxDuration().toNanos();
            try (var opened = audioSource.open(task.request.mediaUrl());
                    var decoded = decoder.decode(opened.inputStream())) {
                frameSender.send(
                        task.request.webSocketSession(),
                        task.request.voiceSession(),
                        decoded.pcmStream(),
                        task::paused,
                        () -> task.cancelled() || expired(deadline),
                        task::markSending,
                        task::markIdle
                );
            }
        } catch (IOException | RuntimeException exception) {
            log.warn("xiaozhi music playback failed, deviceId={}, title={}, message={}",
                    deviceId, task.request.title(), exception.getMessage(), exception);
        } finally {
            tasks.remove(deviceId, task);
        }
    }

    private boolean expired(long deadline) {
        return System.nanoTime() >= deadline;
    }

    private static final class PlaybackTask {
        private final XiaozhiMusicPlaybackRequest request;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean sending = new AtomicBoolean();
        private volatile XiaozhiMusicPlaybackState.PauseSource pauseSource;

        private PlaybackTask(XiaozhiMusicPlaybackRequest request) {
            this.request = request;
        }

        private void pause(XiaozhiMusicPlaybackState.PauseSource source) {
            pauseSource = source;
        }

        private void resume(XiaozhiMusicPlaybackState.PauseSource source) {
            if (pauseSource == source) {
                pauseSource = null;
            }
        }

        private void cancel() {
            cancelled.set(true);
        }

        private boolean cancelled() {
            return cancelled.get();
        }

        private boolean paused() {
            return pauseSource != null;
        }

        private void markSending() {
            sending.set(true);
        }

        private void markIdle() {
            sending.set(false);
        }

        private void awaitIdle(long timeoutMillis) {
            var deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
            while (sending.get() && System.nanoTime() < deadline) {
                sleep(PAUSE_IDLE_POLL_MS);
            }
        }

        private void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                cancel();
            }
        }

        private XiaozhiMusicPlaybackState state() {
            return new XiaozhiMusicPlaybackState(
                    request.voiceSession().deviceId(),
                    request.title(),
                    request.artist(),
                    paused() ? XiaozhiMusicPlaybackState.Status.PAUSED : XiaozhiMusicPlaybackState.Status.PLAYING,
                    pauseSource
            );
        }
    }
}
```

实现时删除未使用 import。若测试不需要 `Executors`，不要保留。

- [ ] **步骤 6：运行测试验证通过**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiMusicPlaybackRuntimeTest test
```

预期：PASS。

## 任务 6：把 Hermes 音乐动作接入会话服务

**文件：**
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/XiaozhiMusicActionHandler.java`
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`

- [ ] **步骤 1：编写失败测试，Hermes 音乐事件不会进入 TTS**

在 `XiaozhiVoiceSessionServiceTest` 增加测试：

```java
@Test
void shouldHandleMusicPlayFromHermesAgentEventWithoutTtsSynthesis() {
    var hermesClient = new HermesAgentEventClient("""
            event: xiaozhi.agent_event
            data: {"action":"music_play","title":"稻香","artist":"周杰伦","media_url":"https://example.com/daoxiang.mp3","confirmation_text":"开始播放稻香"}

            """);
    var ttsClient = new CapturingTextToSpeechClient();
    var musicRuntime = new CapturingMusicPlaybackRuntime();
    var service = newServiceWithHermesAndMusic(hermesClient, ttsClient, musicRuntime);
    var session = handshakenSession("device-1");

    runSingleTurn(service, session, "播放周杰伦的稻香");

    assertThat(ttsClient.requests()).isEmpty();
    assertThat(musicRuntime.playedTitles()).containsExactly("稻香");
}
```

如果当前测试工厂没有 `newServiceWithHermesAndMusic(...)`，在测试内新增最小重载。不要为了测试改生产构造器为可空大杂烩；优先显式注入 `XiaozhiMusicActionHandler`。

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceSessionServiceTest#shouldHandleMusicPlayFromHermesAgentEventWithoutTtsSynthesis test
```

预期：FAIL，音乐 handler 不存在或事件被忽略。

- [ ] **步骤 3：创建音乐 action handler**

创建 `XiaozhiMusicActionHandler`：

```java
package com.jzb.chatbot.voice.music;

import com.jzb.chatbot.voice.XiaozhiVoiceSession;
import com.jzb.chatbot.voice.hermes.HermesAgentEvent;
import org.springframework.web.socket.WebSocketSession;

/**
 * 小智音乐动作处理器。
 * <p>
 * 只执行 Hermes agent 返回的结构化音乐动作，不从自然语言中推断音乐意图。
 *
 * @author jiangzhibin
 * @since 2026-06-18 00:00:00
 */
public class XiaozhiMusicActionHandler {

    private final XiaozhiMusicPlaybackRuntime musicPlaybackRuntime;

    public XiaozhiMusicActionHandler(XiaozhiMusicPlaybackRuntime musicPlaybackRuntime) {
        this.musicPlaybackRuntime = musicPlaybackRuntime;
    }

    public boolean handle(WebSocketSession webSocketSession, XiaozhiVoiceSession voiceSession, HermesAgentEvent event) {
        if (event == null || event.action() == null || !event.action().startsWith("music_")) {
            return false;
        }
        var deviceId = voiceSession.deviceId();
        switch (event.action()) {
            case "music_play" -> musicPlaybackRuntime.play(new XiaozhiMusicPlaybackRequest(
                    webSocketSession,
                    voiceSession,
                    event.title(),
                    event.artist(),
                    event.mediaUrl()
            ));
            case "music_pause" -> musicPlaybackRuntime.pause(deviceId, XiaozhiMusicPlaybackState.PauseSource.MANUAL);
            case "music_resume" -> musicPlaybackRuntime.resume(deviceId, XiaozhiMusicPlaybackState.PauseSource.MANUAL);
            case "music_stop" -> musicPlaybackRuntime.stop(deviceId);
            default -> {
                return false;
            }
        }
        return true;
    }
}
```

- [ ] **步骤 4：修改会话服务事件处理**

将 `handleHermesAgentEvent(...)` 增加 `WebSocketSession` 参数，先尝试音乐 handler：

```java
if (musicActionHandler.handle(webSocketSession, voiceSession, event)) {
    return event.confirmationText();
}
```

然后保留现有 `create_reminder` 逻辑。所有调用点同步传入 `webSocketSession`。

- [ ] **步骤 5：运行测试验证通过**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceSessionServiceTest#shouldHandleMusicPlayFromHermesAgentEventWithoutTtsSynthesis test
```

预期：PASS。

## 任务 7：处理 TTS 与音乐播放互斥

**文件：**
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/XiaozhiMusicPlaybackCoordinator.java`
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/tts/XiaozhiTtsRuntime.java`
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/XiaozhiMusicPlaybackRuntime.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiTtsRuntimeTest.java`

- [ ] **步骤 1：编写失败测试，TTS 播报期间音乐暂停后自动恢复**

在 `XiaozhiTtsRuntimeTest` 增加：

```java
@Test
void shouldPauseMusicDuringTtsAndResumeAfterTts() {
    var musicCoordinator = new CapturingMusicPlaybackCoordinator();
    var runtime = new XiaozhiTtsRuntime(
            new FakeTextToSpeechClient(),
            codec,
            eventFactory,
            musicCoordinator
    );
    var request = ttsRequestForDevice("device-1", "你好。");

    runtime.play(request);

    assertThat(musicCoordinator.events()).containsExactly(
            "pause:device-1:TTS",
            "resume:device-1:TTS"
    );
}
```

`CapturingMusicPlaybackCoordinator.pause(...)` 在记录事件后直接返回；真实 runtime 会在 `pause(...)` 内等待音乐发送静默。不要把音乐解码、URL 校验或帧发送逻辑引入 `XiaozhiTtsRuntime` 测试。

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiTtsRuntimeTest#shouldPauseMusicDuringTtsAndResumeAfterTts test
```

预期：FAIL，构造器或协作逻辑不存在。

- [ ] **步骤 3：创建音乐播放协作接口**

创建 `XiaozhiMusicPlaybackCoordinator`：

```java
package com.jzb.chatbot.voice.music;

/**
 * 小智音乐播放协作接口。
 * <p>
 * 暴露给 TTS runtime 的最小互斥能力，避免 TTS 依赖音乐播放实现细节。
 *
 * @author jiangzhibin
 * @since 2026-06-18 00:00:00
 */
public interface XiaozhiMusicPlaybackCoordinator {

    void pause(String deviceId, XiaozhiMusicPlaybackState.PauseSource source);

    void resume(String deviceId, XiaozhiMusicPlaybackState.PauseSource source);
}
```

让 `XiaozhiMusicPlaybackRuntime implements XiaozhiMusicPlaybackCoordinator`。`pause(..., TTS)` 必须在设置暂停状态后等待 `MusicFrameSender` 的当前帧发送退出，等待上限使用 `PAUSE_IDLE_TIMEOUT_MS`。

- [ ] **步骤 4：给 TTS runtime 增加可选 coordinator 依赖**

在 `XiaozhiTtsRuntime` 增加构造器重载，保留现有构造器兼容。字段类型必须是 `XiaozhiMusicPlaybackCoordinator`，不能是 `XiaozhiMusicPlaybackRuntime`。TTS 开始发送 `tts.start` 前调用：

```java
pauseMusicForTts(voiceSession.deviceId());
```

在 finally 发送 `tts.stop` 后调用：

```java
resumeMusicAfterTts(voiceSession.deviceId());
```

辅助方法：

```java
private void pauseMusicForTts(String deviceId) {
    if (musicPlaybackCoordinator != null) {
        musicPlaybackCoordinator.pause(deviceId, XiaozhiMusicPlaybackState.PauseSource.TTS);
    }
}

private void resumeMusicAfterTts(String deviceId) {
    if (musicPlaybackCoordinator != null) {
        musicPlaybackCoordinator.resume(deviceId, XiaozhiMusicPlaybackState.PauseSource.TTS);
    }
}
```

手动暂停的音乐不应被 TTS 恢复，因为 `XiaozhiMusicPlaybackRuntime.resume(..., TTS)` 只恢复 pauseSource 为 `TTS` 的任务。`pauseMusicForTts(...)` 必须放在发送 `tts.start` 前，确保 TTS 和音乐不会并发写同一个 WebSocket。

- [ ] **步骤 5：运行测试验证通过**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiTtsRuntimeTest#shouldPauseMusicDuringTtsAndResumeAfterTts test
```

预期：PASS。

## 任务 8：会话切换和断开时停止音乐

**文件：**
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiWebSocketHandler.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiWebSocketHandlerTest.java`

- [ ] **步骤 1：编写失败测试，开始监听会停止音乐**

在 `XiaozhiVoiceSessionServiceTest` 增加：

```java
@Test
void shouldStopMusicWhenUserStartsListening() {
    var musicRuntime = new CapturingMusicPlaybackRuntime();
    var service = newServiceWithMusic(musicRuntime);
    var session = handshakenSession("device-1");

    service.handleText(session, """
            {"type":"listen","state":"start","mode":"manual"}
            """);

    assertThat(musicRuntime.events()).contains("stop:device-1");
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceSessionServiceTest#shouldStopMusicWhenUserStartsListening test
```

预期：FAIL，未调用 music runtime。

- [ ] **步骤 3：在会话服务状态切换处停止音乐**

在处理 `listen.start`、`abort`、`session.new`、`session.clear` 的分支中调用：

```java
musicPlaybackRuntime.stop(voiceSession.deviceId());
```

`XiaozhiVoiceSessionService` 可以持有可选 `XiaozhiMusicPlaybackRuntime`，因为这里需要 `stop(...)` 能力；没有音乐 bean 时保持现有行为。不要在普通 Hermes 文本回复结束时停止音乐。

- [ ] **步骤 4：连接关闭时停止音乐**

在 `XiaozhiVoiceSessionService.close(session)` 中依据 session 的 `deviceId` 调用 `musicPlaybackRuntime.stop(deviceId)`。`XiaozhiWebSocketHandler.afterConnectionClosed(...)` 已经委托 `sessionService.close(session)`，优先保持 handler 不直接感知音乐 runtime。

- [ ] **步骤 5：运行测试验证通过**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceSessionServiceTest#shouldStopMusicWhenUserStartsListening,XiaozhiWebSocketHandlerTest test
```

预期：PASS。

## 任务 9：注册配置和 Bean

**文件：**
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeans.java`
- 修改：`chatbot-bootstrap/src/main/resources/application.yml`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeansTest.java`

- [ ] **步骤 1：编写失败测试，默认关闭音乐 runtime**

在 `XiaozhiVoiceGatewayBeansTest` 增加：

```java
@Test
void shouldDisableMusicPlaybackByDefault() {
    var contextRunner = voiceGatewayContextRunner();

    contextRunner.run(context -> assertThat(context).doesNotHaveBean(XiaozhiMusicPlaybackRuntime.class));
}
```

- [ ] **步骤 2：编写失败测试，显式启用后注册音乐 runtime**

同文件增加：

```java
@Test
void shouldCreateMusicPlaybackRuntimeWhenEnabled() {
    var contextRunner = voiceGatewayContextRunner()
            .withPropertyValues(
                    "chatbot.voice.music.enabled=true",
                    "chatbot.voice.music.ffmpeg-path=ffmpeg",
                    "chatbot.voice.music.allowed-hosts[0]=example.com"
            );

    contextRunner.run(context -> assertThat(context).hasSingleBean(XiaozhiMusicPlaybackRuntime.class));
}
```

- [ ] **步骤 3：运行测试验证失败**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceGatewayBeansTest test
```

预期：FAIL，bean 或配置不存在。

- [ ] **步骤 4：注册配置和 bean**

在 `XiaozhiVoiceGatewayBeans` 中使用 `@ConditionalOnProperty(name = "chatbot.voice.music.enabled", havingValue = "true")` 注册：

```java
XiaozhiMusicPlaybackProperties
MusicHostResolver
MusicAudioSource
FfmpegMusicDecoder
MusicFrameSender
XiaozhiMusicPlaybackCoordinator
XiaozhiMusicPlaybackRuntime
XiaozhiMusicActionHandler
```

`MusicHostResolver` 使用 `MusicHostResolver.system()`。`MusicAudioSource` 构造时传入 `XiaozhiMusicPlaybackProperties` 和 `MusicHostResolver`。`XiaozhiMusicPlaybackRuntime` 构造时传入 `MusicAudioSource`、`FfmpegMusicDecoder`、`MusicFrameSender` 和 properties。

已存在的 `XiaozhiTtsRuntime` bean 构造时传入可选 `XiaozhiMusicPlaybackCoordinator`，字段类型保持 coordinator 接口。没有音乐 bean 时保持现有行为。

- [ ] **步骤 5：补 application 默认配置**

在 `chatbot-bootstrap/src/main/resources/application.yml` 增加：

```yaml
chatbot:
  voice:
    music:
      enabled: ${CHATBOT_VOICE_MUSIC_ENABLED:false}
      ffmpeg-path: ${CHATBOT_VOICE_MUSIC_FFMPEG_PATH:ffmpeg}
      connect-timeout: ${CHATBOT_VOICE_MUSIC_CONNECT_TIMEOUT:3s}
      max-duration: ${CHATBOT_VOICE_MUSIC_MAX_DURATION:5m}
      allowed-hosts: ${CHATBOT_VOICE_MUSIC_ALLOWED_HOSTS:}
```

如果现有 `chatbot.voice` 已存在，应合并到现有层级，不重复创建顶层。

- [ ] **步骤 6：运行测试验证通过**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceGatewayBeansTest test
```

预期：PASS。

## 任务 10：部署镜像补 ffmpeg

**文件：**
- 修改：`Dockerfile`
- 修改：`deploy/Dockerfile`

- [ ] **步骤 1：检查运行时基础镜像包管理器**

运行：

```bash
docker run --rm eclipse-temurin:21-jre sh -lc "command -v apt-get || command -v microdnf || command -v apk"
```

预期：输出可用包管理器路径。当前计划按 Debian/Ubuntu 系基础镜像处理。

- [ ] **步骤 2：修改运行时镜像安装 ffmpeg**

在两个 Dockerfile 的运行时阶段 `FROM eclipse-temurin:21-jre` 后增加：

```dockerfile
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*
```

不要在 Maven build stage 安装 ffmpeg。

- [ ] **步骤 3：构建镜像验证 ffmpeg 可用**

运行：

```bash
docker build -t chatbot-service-java:music-ffmpeg .
docker run --rm chatbot-service-java:music-ffmpeg ffmpeg -version
```

预期：`ffmpeg version ...`。

## 任务 11：端到端测试与回归

**文件：**
- 验证：Hermes `music_search` 工具 smoke
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/hermes/HermesAgentEventExtractorTest.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/music/*Test.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiTtsRuntimeTest.java`

- [ ] **步骤 1：运行 voice-gateway 音乐相关测试**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am \
  -Dtest=HermesAgentEventExtractorTest,MusicAudioSourceTest,FfmpegMusicDecoderTest,MusicFrameSenderTest,XiaozhiMusicPlaybackRuntimeTest,XiaozhiVoiceSessionServiceTest,XiaozhiTtsRuntimeTest test
```

预期：PASS。

- [ ] **步骤 2：运行完整相关模块测试**

运行：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-speech-api,chatbot-voice-gateway -am test
```

预期：`BUILD SUCCESS`。

- [ ] **步骤 3：本地 smoke 验证**

先验证 Hermes 音乐源工具：

```json
{"tool":"music_search","arguments":{"query":"测试音乐","title":"测试音乐","artist":""}}
```

预期成功时至少包含：

```json
{"title":"测试音乐","artist":"","media_url":"https://cdn.example.com/test.mp3","source":"selected-source","license":"known-license"}
```

再使用本地 mock Hermes 返回：

```text
event: xiaozhi.agent_event
data: {"action":"music_play","title":"测试音乐","media_url":"https://example.com/test.mp3","confirmation_text":"开始播放测试音乐"}
```

预期：

```text
music_play 事件被解析
TTS client 没有收到合成请求
WebSocket session 收到 binary 音频帧
listen.start 后音乐停止
```

工具失败 smoke：

```json
{"error":"not_found","message":"没有找到可播放的音乐源"}
```

预期：

```text
Hermes 只返回普通失败文本
不会输出 music_play
Java 不会触发音乐播放
```

真机验收不能用 fake audio 代替，需要实际设备确认扬声器持续播放音乐音频。

## 风险与处理

- 只有固定提示词但没有音乐源工具时，Hermes 可能编造 `media_url`：必须先完成 `music_search` 工具接入，且提示词明确禁止编造 URL；工具失败时不输出 `music_play`。
- 在线音乐源可用性和授权范围会变化：第一版只接一个白名单源，并要求 `music_search` 返回 `source` 和 `license`；不做多源聚合和版权兜底。
- 外部 `media_url` 会带来 SSRF 风险：Java 侧 `MusicAudioSource` 负责 scheme、host allowlist、DNS 公网地址和 redirect 校验；`ffmpeg` 只读 Java 已打开的 stdin，不直接访问 URL。
- 小智固件可能只在收到 `tts.start` 后播放 binary：如果验证发现必须发送控制帧，只在 `MusicFrameSender` 外围发送兼容控制帧，不调用 TTS provider，不生成 `tts.sentence_start`。
- 长音频可能占用连接和 CPU：第一版通过 `max-duration` 限制播放时长，超时后停止播放任务并关闭 `ffmpeg` 进程。
- 音乐和 TTS 共用同一个 WebSocket：TTS 开始前通过 `XiaozhiMusicPlaybackCoordinator.pause(..., TTS)` 暂停音乐并等待当前音乐帧发送静默，避免两个线程同时 `sendMessage`。
- Hermes 返回失效 URL：Java 记录 warning 并可播报短文本错误，但错误播报仍走正常 TTS，不重试音乐源搜索。
- 手动暂停和 TTS 自动暂停容易冲突：runtime 用 `PauseSource.MANUAL` 与 `PauseSource.TTS` 区分，TTS 只能恢复自己暂停的音乐。

## 外部参考

- Jamendo tracks API：`https://developer.jamendo.com/v3.0/tracks`
- Jamendo tracks file API：`https://developer.jamendo.com/v3.0/tracks/file`
- Audius developer docs：`https://docs.audius.co/`
- `py-xiaozhi` 音乐工具文档：`https://github.com/huangjunsen0406/py-xiaozhi/blob/main/documents/docs/zh/mcp/music.md`
- `py-xiaozhi` 音乐 MCP 工具：`https://github.com/huangjunsen0406/py-xiaozhi/blob/main/src/mcp/tools/music/_tools.py`
- `py-xiaozhi` 音乐播放器：`https://github.com/huangjunsen0406/py-xiaozhi/blob/main/src/mcp/tools/music/music_player.py`
- 固件侧音乐方案参考：`https://github.com/wuooo339/xiaozhimusic`
- 固件侧 `self.music.play_song` 参考：`https://github.com/Maggotxy/xiaozhi-esp32-music`
