# 小智音乐播放完整优化任务计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。本文档只规划实现路线，不包含 git commit、push、分支切换或生产环境写操作；相关操作必须等用户明确确认。

**目标：** 把现有“单 URL 音乐播放通路”升级为可用的智能音箱音乐播放能力，系统性修复播放中无法唤醒、继续播放无缓存、音质差、经常播放旧推荐歌曲、播放状态不可观测等问题。

**架构：** Hermes 继续负责自然语言理解、歌曲选择和音乐控制决策；音乐源工具负责确定性搜索、候选约束和 URL 解析；Java voice-gateway 负责播放状态机、音频解码/编码、WebSocket 下发、播放期控制和观测；固件负责 media lifecycle、播放期唤醒上行和设备端播放窗口。优化路线不再沿用“最小可行性播放”，而是补齐播放器状态、选曲契约、音质参数、barge-in 控制和端到端验收闭环。

**技术栈：** Java 21、Spring Boot 3.4、Spring WebSocket、Maven 多模块、JUnit 5、AssertJ、Jackson、Concentus Opus、ffmpeg、Hermes SSE、stdio MCP、Node.js `node:test`、ESP-IDF、XiaoZhi WebSocket protocol。

---

## 已明确的决策

- 音乐自然语言意图继续交给 Hermes，不在 Java 中做歌名识别、意图分类或语义兜底。
- Java 必须从“播放任务 map”升级为“单设备播放器状态机”，保留当前曲目、最近曲目、暂停来源、播放代际、播放进度和失败原因。
- `music_resume` 不能只恢复内存中的活动任务；当当前任务已停止但存在最近可恢复曲目时，应按策略重新播放或断点续播。
- 播放期唤醒不能再二选一地“忽略 auto listen”或“直接 stopMusic”；目标行为是先暂停音乐、创建控制识别窗口，再根据用户意图恢复、暂停、停止、切歌或进入普通对话。
- 音质优化必须独立于 TTS，音乐不得走 TTS provider。当前 16 kHz mono + `OPUS_APPLICATION_VOIP` 是音质差的主要服务端原因，必须引入音乐专用编码配置。
- 选曲必须强约束本轮 query/title/artist。工具没有足够匹配时返回 `not_found` 或候选确认，不能默认播放第一条、热门榜单或旧上下文推荐。
- `media.start kind=music` -> binary Opus frames -> `media.stop kind=music` 的生命周期保留，但要补充 `media.pause`、`media.resume`、`media.error` 或等价事件，便于固件和调试台观测。
- 计划实施必须 TDD：每个 Java/Node 行为变更先补失败测试，再实现最少代码，再扩展回归测试。
- 当前工作区已有与 ASR 相关的未提交改动，实施音乐计划时必须控制文件范围，避免改动或格式化无关文件。

## 当前问题与根因归纳

| 问题 | 现象 | 已定位原因 | 影响面 |
| --- | --- | --- | --- |
| 播放时无法唤醒助手 | 播歌时叫助手没反应，或一唤醒就停歌 | `listen.start mode=auto` 在音乐播放中被忽略；普通 `listen.start` 会调用 `stopMusic()`；真正控制路径依赖 `barge_in`，但音乐播放期控制没有完整状态机 | Java service、固件上行、Hermes 控制意图 |
| 继续播放提示无缓存 | 歌停后说继续播放，提示没有缓存歌曲 | `music_resume` 只清除当前 `PlaybackTask` 暂停标记；`stop()`、播放结束、WebSocket 关闭都会 `tasks.remove(deviceId)`，没有最近曲目记录 | Java 播放状态、Hermes 回复策略 |
| 音质差 | 音乐像语音通话，细节损失明显 | ffmpeg 强制 `-ac 1 -ar 16000`，Opus 使用 `OPUS_APPLICATION_VOIP`，编码器无 bitrate/signal/complexity 配置 | Java 音频链路、固件输出采样率 |
| 经常播放之前推荐的歌曲 | 本轮点歌却播旧候选、热门榜单或上一轮推荐 | Hermes 上下文可能携带旧候选；MCP 搜索 fallback 和候选选择约束太松；缺少 selected source 的 requestId/trace | Hermes、MCP、日志观测 |
| 播放体验不可观测 | 很难判断是没选到歌、没下发、下发音质差还是设备丢帧 | 日志只覆盖 request/resumed/finished，缺少 URL 解析、候选评分、编码参数、播放状态迁移和设备端 media ack | 运维、调试、回归测试 |

## 整体规划概述

### 项目目标

交付一套完整的音乐播放优化路线，使小智设备具备以下能力：

- 点歌结果准确，不被旧推荐或热门候选污染。
- 播放、暂停、继续、停止、切歌、重播、播放状态查询语义稳定。
- 播放期间可以唤醒助手，音乐按用户意图暂停、恢复或切换。
- 音质至少达到“可接受音乐播放”水平，后续可按设备能力升级到更高采样率。
- 服务端、Hermes 工具和固件日志能定位每一次播放失败或误播。
- 所有核心行为有自动化测试和端到端 smoke，关键路径支持远程和实机验收。

### 技术栈

- **Java 服务端：** `chatbot-voice-gateway`、`chatbot-speech-api`、`chatbot-bootstrap`
- **Hermes/MCP：** buguyy stdio MCP、Hermes SSE `xiaozhi.agent_event`
- **音频链路：** ffmpeg、Concentus Opus、WebSocket binary v1/v2/v3
- **固件链路：** `/Users/jiangzhibin/workspace/xiaozhi-esp32` 中的 `media_control`、`application.cc`、WebSocket protocol
- **验证工具：** Maven、Node.js `node:test`、`scripts/xiaozhi_ws_smoke.py`、`scripts/hermes_xiaozhi_mcp_smoke.py`、串口 monitor、远程容器日志

### 主要阶段

1. **契约与观测基线：** 明确音乐事件、状态机、选曲 trace、音频参数和验收指标。
2. **选曲正确性：** 收紧 MCP 搜索、候选评分、Hermes 输出约束，避免旧推荐误播。
3. **播放状态机：** 引入持久于 WebSocket task 的设备播放器状态，支持继续播放、重播、切歌和状态查询。
4. **播放期唤醒与控制：** 统一 auto listen、barge-in 和普通控制意图，不再粗暴忽略或停歌。
5. **音质优化：** 引入音乐专用 Opus 编码配置、采样率协商、端到端音频质量 smoke。
6. **固件协同：** 完善 media lifecycle、播放期 uplink、设备端状态和采样率匹配。
7. **灰度发布与回归验收：** 本地测试、远程 smoke、实机验收、日志看板和回滚策略。

## 范围边界

### 包含

- Java 播放状态机、音频链路、协议事件、配置和测试。
- Hermes/MCP 选曲工具的强匹配、候选确认、错误返回和测试。
- 固件必要协同点的计划和验收标准。
- 远程容器与实机验证流程。
- 音质、状态、误播、唤醒控制的回归用例。

### 不包含

- 不新增付费音乐平台账号、版权绕过或破解接口。
- 不把音乐音频走 TTS provider。
- 不在 Java 中做自然语言语义判断。
- 不默认引入数据库；若需要跨进程持久化，先通过配置开关和轻量存储评估后再执行。
- 不在本计划中执行 git commit、push、生产配置写入或远程重启。

## 目标状态机

### 设备播放器状态

```text
STOPPED
  -> RESOLVING       用户点歌，Hermes/MCP 正在选曲
  -> BUFFERING       Java 已拿到 media_url，正在打开源和启动 ffmpeg
  -> PLAYING         正在下发音乐帧
  -> PAUSED_MANUAL   用户主动暂停
  -> PAUSED_TTS      TTS 临时播报导致暂停
  -> PAUSED_CONTROL  播放期唤醒/控制识别窗口暂停
  -> ENDED           自然播完，保留最近曲目
  -> FAILED          播放失败，保留失败原因和最近有效曲目
```

### 状态迁移原则

- `music_play` 新曲目：取消旧播放代际，记录 last playable track，进入 `BUFFERING`。
- `music_pause`：只暂停当前活动任务；无活动任务但有最近曲目时返回“当前没有正在播放”。
- `music_resume`：优先恢复活动任务；无活动任务但有最近曲目时根据策略重播或断点续播。
- `music_stop`：停止当前任务，但保留最近曲目和 last intent，便于“继续播放”或“再播一遍”。
- WebSocket 关闭：停止活动下发，保留最近曲目元数据；是否允许重连后恢复由配置决定。
- 自然结束：状态为 `ENDED`，`继续播放` 默认从头播放，`再放一遍` 也从头播放。
- 播放失败：状态为 `FAILED`，同一曲目短时间内避免无限重试。

## 音乐事件契约

### Hermes -> Java

```json
{
  "action": "music_play",
  "request_id": "music-20260621-001",
  "title": "晴天",
  "artist": "周杰伦",
  "media_url": "https://car-er.kuwo.cn/...",
  "source": "buguyy",
  "confidence": 0.93,
  "match_reason": "artist_title_exact",
  "confirmation_text": "找到周杰伦的晴天，现在播放。"
}
```

```json
{"action":"music_pause","request_id":"music-20260621-002"}
{"action":"music_resume","request_id":"music-20260621-003"}
{"action":"music_stop","request_id":"music-20260621-004"}
{"action":"music_replay","request_id":"music-20260621-005"}
{"action":"music_next","request_id":"music-20260621-006"}
{"action":"music_status","request_id":"music-20260621-007"}
```

### MCP -> Hermes

```json
{
  "status": "ready_to_play",
  "requestId": "music-20260621-001",
  "query": "周杰伦 晴天",
  "selected": {
    "title": "晴天",
    "artist": "周杰伦",
    "id": "11261780",
    "score": 97,
    "matchReason": "artist_title_exact"
  },
  "candidates": [],
  "mediaUrl": "https://car-er.kuwo.cn/...",
  "redactedAudioUrl": "https://car-er.kuwo.cn/...",
  "ttsText": "找到周杰伦的晴天，现在播放。"
}
```

弱匹配或多个候选时：

```json
{
  "status": "needs_confirmation",
  "query": "晴天",
  "reason": "multiple_candidates",
  "candidates": [
    {"title":"晴天","artist":"周杰伦","score":72},
    {"title":"晴天","artist":"其他歌手","score":61}
  ],
  "ttsText": "找到多个晴天，你要周杰伦的晴天吗？"
}
```

不允许用 `needs_confirmation` 结果输出 `music_play`。

### Java -> 设备

现有事件保留：

```json
{"type":"media","state":"start","kind":"music","title":"晴天","artist":"周杰伦"}
{"type":"media","state":"stop","kind":"music"}
```

新增或等价扩展：

```json
{"type":"media","state":"pause","kind":"music","source":"control"}
{"type":"media","state":"resume","kind":"music"}
{"type":"media","state":"error","kind":"music","code":"music_playback_failed","message":"音乐暂时无法播放"}
```

## 详细任务分解

### 阶段 1：契约与观测基线

- **任务 1.1：补充音乐事件契约测试**
  - 目标：让 `HermesAgentEventExtractor` 支持 `request_id`、`source`、`confidence`、`match_reason` 等字段，并保持坏 JSON 容错。
  - 输入：现有 `HermesAgentEvent`、`HermesAgentEventExtractorTest`。
  - 输出：失败测试先覆盖新字段，随后实现通过。
  - 涉及文件：
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/hermes/HermesAgentEvent.java`
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/hermes/HermesAgentEventExtractor.java`
    - `chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/hermes/HermesAgentEventExtractorTest.java`
  - 验收标准：
    - `music_play` 能提取 `requestId/source/confidence/matchReason`。
    - 旧字段 `title/artist/mediaUrl/confirmationText` 兼容。
  - 验证命令：
    ```bash
    /Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=HermesAgentEventExtractorTest -Dsurefire.failIfNoSpecifiedTests=false test
    ```

- **任务 1.2：定义播放器快照与日志字段**
  - 目标：建立统一状态快照，所有播放迁移日志都包含 `deviceId/requestId/trackId/title/artist/status/generation/source/mediaHost/audioProfile`。
  - 输入：现有 `XiaozhiMusicPlaybackState`、`XiaozhiMusicPlaybackRuntime`。
  - 输出：`XiaozhiMusicPlaybackSnapshot` 或扩展后的 state record；日志字段标准化。
  - 涉及文件：
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/XiaozhiMusicPlaybackState.java`
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/XiaozhiMusicPlaybackRuntime.java`
    - `chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/music/XiaozhiMusicPlaybackRuntimeTest.java`
  - 验收标准：
    - 测试能断言状态快照包含最近曲目和失败原因。
    - 日志不输出完整带 token 的 URL，只输出 host 和 redacted URL。
  - 验证命令：
    ```bash
    /Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiMusicPlaybackRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false test
    ```

- **任务 1.3：补充端到端 trace id**
  - 目标：让 MCP、Hermes event、Java 播放任务和设备 media event 使用同一个 `requestId`，定位误播和旧候选污染。
  - 输入：Hermes event、MCP 工具返回、Java request。
  - 输出：`XiaozhiMusicPlaybackRequest` 增加 `requestId/source/redactedMediaUrl`。
  - 涉及文件：
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/XiaozhiMusicPlaybackRequest.java`
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/XiaozhiMusicActionHandler.java`
    - `chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/music/XiaozhiMusicPlaybackRuntimeTest.java`
  - 验收标准：
    - 同一播放请求的 request id 出现在 requested、media.start、finished/failed 日志。
    - 缺失 request id 时 Java 生成本地 id，但日志标记 `requestIdSource=generated`。

### 阶段 2：选曲正确性与 MCP 工具约束

- **任务 2.1：收紧 buguyy MCP 候选评分**
  - 目标：避免组合词搜不到时播放热门、旧推荐或无关第一条。
  - 输入：`playMusic({ keyword, title, artist })`。
  - 输出：显式 `status=ready_to_play|needs_confirmation|not_found`，并输出 `score/matchReason`。
  - 涉及文件：
    - `/Users/jiangzhibin/Documents/Codex/2026-06-18/https-buguyy-top/outputs/buguyy-mcp/src/buguyy-client.js`
    - `/Users/jiangzhibin/Documents/Codex/2026-06-18/https-buguyy-top/outputs/buguyy-mcp/test/buguyy-client.test.js`
  - TDD 用例：
    - `keyword=周杰伦 晴天`，候选包含 `周杰伦/晴天` 时返回 `ready_to_play`。
    - 只有歌手命中但歌名不命中时返回 `needs_confirmation` 或 `not_found`，不能播放第一条。
    - 用户明确“热门歌曲”时才允许 `get_hotlist`。
  - 验证命令：
    ```bash
    cd "/Users/jiangzhibin/Documents/Codex/2026-06-18/https-buguyy-top/outputs/buguyy-mcp"
    node --test
    ```

- **任务 2.2：为 MCP 增加上下文隔离**
  - 目标：每次点歌请求只基于本轮 query/title/artist 选择，不读取旧候选。
  - 输入：当前无状态 stdio MCP。
  - 输出：工具返回显式 `requestId` 和 `queryFingerprint`，Hermes prompt 要求不得复用旧 candidates。
  - 涉及文件：
    - `/Users/jiangzhibin/Documents/Codex/2026-06-18/https-buguyy-top/outputs/buguyy-mcp/src/index.js`
    - `/Users/jiangzhibin/Documents/Codex/2026-06-18/https-buguyy-top/outputs/buguyy-mcp/src/buguyy-client.js`
    - `/Users/jiangzhibin/Documents/Codex/2026-06-18/https-buguyy-top/outputs/buguyy-mcp/README.md`
  - 验收标准：
    - 两次连续 search 的返回 requestId 不同。
    - 第二次请求不会包含第一次候选，除非上游搜索本身返回同一首。

- **任务 2.3：更新 Hermes 音乐工具提示词与失败策略**
  - 目标：Hermes 遇到 `needs_confirmation/not_found` 时只播 TTS 提示，不输出 `music_play`。
  - 输入：Hermes tool 配置、SOUL/工具说明或远程配置文件。
  - 输出：工具说明和系统提示明确“只有 `ready_to_play` 可转 `music_play`”。
  - 涉及文件：
    - 本地文档：`docs/superpowers/plans/2026-06-21-xiaozhi-music-complete-optimization.md`
    - 远程 Hermes 配置位置需实施时通过 live state 确认。
  - 验收标准：
    - “播放周杰伦的晴天”输出 `music_play`。
    - “播放刚才推荐的那首”在没有明确最近推荐上下文时追问，不直接播旧候选。
    - “来首热门歌”才允许走 hotlist。

### 阶段 3：Java 播放状态机与缓存

- **任务 3.1：引入设备播放器状态仓库**
  - 目标：从 `Map<String, PlaybackTask>` 扩展为 `Map<String, DeviceMusicPlayer>`，记录当前任务和最近曲目。
  - 输入：现有 `XiaozhiMusicPlaybackRuntime.tasks`。
  - 输出：`DeviceMusicPlayer` 或等价内部类，包含 `activeTask`、`lastTrack`、`status`、`generation`、`positionMillis`、`failure`。
  - 涉及文件：
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/XiaozhiMusicPlaybackRuntime.java`
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/XiaozhiMusicPlaybackState.java`
    - `chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/music/XiaozhiMusicPlaybackRuntimeTest.java`
  - 验收标准：
    - `stop(deviceId)` 后 `state(deviceId)` 不丢 `lastTrack`。
    - WebSocket close 停止活动播放，但最近曲目仍可查询。

- **任务 3.2：实现继续播放和重播语义**
  - 目标：`music_resume` 在无 active task 但有 last track 时按配置恢复。
  - 输入：`XiaozhiMusicActionHandler` 的 `music_resume`。
  - 输出：`resumeOrReplayLast(deviceId, source)` 或等价方法；区分 `resume active`、`replay last`、`no cache`。
  - 涉及文件：
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/XiaozhiMusicActionHandler.java`
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/XiaozhiMusicPlaybackRuntime.java`
    - `chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/music/XiaozhiMusicPlaybackRuntimeTest.java`
  - 验收标准：
    - 暂停中继续：恢复同一 task，不重新打开 URL。
    - 停止后继续：使用 last track 重开播放，日志标记 `resumeMode=replay_last`。
    - 没有 last track：返回可听 TTS 提示“没有可继续播放的歌曲”。

- **任务 3.3：支持播放状态查询**
  - 目标：Hermes 可查询当前播放内容，避免问“现在播的什么”时胡答。
  - 输入：`music_status` event。
  - 输出：Java 返回状态事件或直接可听 confirmation text。
  - 涉及文件：
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/XiaozhiMusicActionHandler.java`
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiServerEventFactory.java`
    - `chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/music/XiaozhiMusicPlaybackRuntimeTest.java`
  - 验收标准：
    - 播放中可返回歌名、歌手、状态。
    - 停止但有最近曲目时返回“刚才播放的是 ……”。

- **任务 3.4：补齐错误状态和重试边界**
  - 目标：播放失败后保留失败原因，不让 `继续播放` 无限重试同一个坏 URL。
  - 输入：`sendPlaybackFailure()`。
  - 输出：failure code、retryAfter、lastSuccessfulTrack。
  - 涉及文件：
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/XiaozhiMusicPlaybackRuntime.java`
    - `chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/music/XiaozhiMusicPlaybackRuntimeTest.java`
  - 验收标准：
    - host 不允许、HTTP 失败、ffmpeg 解码失败分别有不同 code。
    - 同一失败 URL 短时间内继续播放时给 TTS 提示，不重复打源站。

### 阶段 4：播放期唤醒与控制

- **任务 4.1：定义音乐播放期输入策略**
  - 目标：统一处理 `listen.start mode=auto`、`mode=barge_in` 和普通 listen。
  - 输入：现有 `handleListenStart()`。
  - 输出：播放中收到 listen 时进入 `PAUSED_CONTROL`，创建控制识别窗口，不直接 stopMusic。
  - 涉及文件：
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
    - `chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`
  - 验收标准：
    - `auto listen + PLAYING` 不再只 ignore；配置开启后暂停音乐并等待控制输入。
    - 配置关闭时保留旧行为，便于回滚。

- **任务 4.2：扩展 barge-in 控制意图**
  - 目标：播放期间用户说“暂停”“继续”“下一首”“换一首”“别放了”时，不进入普通问答。
  - 输入：`tryHandleBargeInControlIntent()`。
  - 输出：Hermes prompt 明确输出 `music_pause/music_resume/music_stop/music_next/music_play` 或普通打断。
  - 涉及文件：
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
    - `chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`
  - 验收标准：
    - 播放中说“暂停一下”只暂停音乐，不结束会话。
    - 播放中说“继续播放”恢复音乐。
    - 播放中说“换成周杰伦晴天”触发新 `music_play`，旧 generation 被取消。

- **任务 4.3：补充控制窗口超时恢复**
  - 目标：唤醒后用户没说清或 ASR 空文本时，音乐自动恢复。
  - 输入：barge-in timeout、ASR blank handling。
  - 输出：`PAUSED_CONTROL` 超时恢复到 `PLAYING`，并记录原因。
  - 涉及文件：
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/XiaozhiMusicPlaybackRuntime.java`
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
  - 验收标准：
    - 空 ASR 不导致永久暂停。
    - 控制失败不清空 last track。

### 阶段 5：音质优化

- **任务 5.1：为 Opus 编码器增加 profile**
  - 目标：TTS 继续使用语音 profile，音乐使用 audio profile。
  - 输入：`StreamingPcmToOpusEncoder(int sampleRate, int frameDurationMs)`。
  - 输出：新增 `StreamingPcmToOpusEncoder.Options` 或 `OpusEncodingProfile`，支持 application、signal、bitrate、complexity、VBR。
  - 涉及文件：
    - `chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/StreamingPcmToOpusEncoder.java`
    - `chatbot-speech-api/src/test/java/com/jzb/chatbot/speech/StreamingPcmToOpusEncoderTest.java`
  - 验收标准：
    - 默认构造器行为不变。
    - 音乐 profile 使用 `OPUS_APPLICATION_AUDIO`、`OPUS_SIGNAL_MUSIC`、更高 bitrate、complexity。
    - 测试通过并能验证 profile 被应用。

- **任务 5.2：音乐音频参数配置化**
  - 目标：去掉 `MusicFrameSender` 和 `FfmpegMusicDecoder` 中硬编码的 `16000/mono/60ms`。
  - 输入：`XiaozhiMusicPlaybackProperties`。
  - 输出：`sampleRate`、`channels`、`frameDurationMs`、`bitrateBps`、`opusApplication`、`opusSignal`、`complexity` 配置。
  - 涉及文件：
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/XiaozhiMusicPlaybackProperties.java`
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/FfmpegMusicDecoder.java`
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/music/MusicFrameSender.java`
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeans.java`
    - `chatbot-bootstrap/src/main/resources/application.yml`
    - `deploy/chatbot-service.env.example`
  - 验收标准：
    - 默认仍为兼容模式，避免未升级固件时破坏播放。
    - 可通过环境变量切换 `CHATBOT_VOICE_MUSIC_SAMPLE_RATE=24000`。
    - bootstrap 测试覆盖配置绑定。

- **任务 5.3：建立音频质量 smoke**
  - 目标：用同一首短音频生成服务端输出，离线解码为 WAV，比较采样率、时长、RMS、削波比例。
  - 输入：测试音频 URL 或本地 fixture。
  - 输出：`scripts/xiaozhi_music_audio_smoke.py` 或 JUnit 集成测试。
  - 涉及文件：
    - `scripts/xiaozhi_music_audio_smoke.py`
    - `tools/ws_debug_console/DecodeOpusCapture.java`
  - 验收标准：
    - 输出 WAV 时长误差 < 3%。
    - 削波比例低于阈值。
    - 日志记录编码 profile。

- **任务 5.4：评估 24 kHz 与 48 kHz 升级**
  - 目标：确定 MuseLab C6 当前固件能否稳定接收 24 kHz 音乐帧。
  - 输入：固件 `AUDIO_OUTPUT_SAMPLE_RATE`、WebSocket hello audio params、server audio params。
  - 输出：采样率兼容矩阵和灰度配置建议。
  - 涉及文件：
    - `/Users/jiangzhibin/workspace/xiaozhi-esp32/main/protocols/websocket_protocol.cc`
    - `/Users/jiangzhibin/workspace/xiaozhi-esp32/main/application.cc`
    - `chatbot-bootstrap/src/main/resources/application.yml`
  - 验收标准：
    - 16 kHz、24 kHz 分别实机播放 30 秒无异常。
    - 如果固件仍上报 warning，记录是否影响播放和音质。

### 阶段 6：固件协同

- **任务 6.1：补齐 media pause/resume/error 接收**
  - 目标：设备端能理解服务端音乐暂停、恢复和失败事件。
  - 输入：现有 `media_control.cc`。
  - 输出：扩展 `MediaControl` 状态，应用层按 kind=music 更新播放状态。
  - 涉及文件：
    - `/Users/jiangzhibin/workspace/xiaozhi-esp32/main/media_control.h`
    - `/Users/jiangzhibin/workspace/xiaozhi-esp32/main/media_control.cc`
    - `/Users/jiangzhibin/workspace/xiaozhi-esp32/main/application.cc`
  - 验收标准：
    - 收到 pause 后停止消耗音乐帧或进入静音等待。
    - 收到 resume 后恢复播放窗口。
    - 收到 error 后退出 music playback state。

- **任务 6.2：完善播放期唤醒 uplink**
  - 目标：音乐播放中保留 wake-word-only interrupt，并在唤醒后上传控制音频。
  - 输入：现有 C6 barge-in 实现。
  - 输出：music playback 下的 `listen.start mode=barge_in` 或专用 `mode=music_control`。
  - 涉及文件：
    - `/Users/jiangzhibin/workspace/xiaozhi-esp32/main/application.cc`
    - `/Users/jiangzhibin/workspace/xiaozhi-esp32/main/audio/audio_service.cc`
    - `/Users/jiangzhibin/workspace/xiaozhi-esp32/main/protocols/protocol.h`
  - 验收标准：
    - 播放音乐时喊唤醒词，音乐暂停，后续控制语音能到服务端。
    - 控制窗口结束后，按服务端事件恢复或停止。

- **任务 6.3：固件侧采样率与 decoder reset 边界**
  - 目标：避免播放期控制误触发 `ResetDecoder()` 清空音乐队列。
  - 输入：已知 C6 `EnableVoiceProcessing(true)` 会 reset decoder 的边界。
  - 输出：音乐控制上行使用 non-resetting 路径。
  - 涉及文件：
    - `/Users/jiangzhibin/workspace/xiaozhi-esp32/main/audio/audio_service.h`
    - `/Users/jiangzhibin/workspace/xiaozhi-esp32/main/audio/audio_service.cc`
  - 验收标准：
    - 音乐播放中进入控制识别不丢后续可恢复状态。
    - 播放结束后再次唤醒仍能听到指令。

### 阶段 7：配置、部署和观测

- **任务 7.1：补齐配置项和文档**
  - 目标：所有音乐优化都可灰度开关控制。
  - 输入：`application.yml`、`deploy/chatbot-service.env.example`。
  - 输出：新增配置说明。
  - 涉及文件：
    - `chatbot-bootstrap/src/main/resources/application.yml`
    - `deploy/chatbot-service.env.example`
    - `README.md`
  - 配置建议：
    ```properties
    CHATBOT_VOICE_MUSIC_ENABLED=true
    CHATBOT_VOICE_MUSIC_ALLOWED_HOSTS=kuwo.cn
    CHATBOT_VOICE_MUSIC_SAMPLE_RATE=16000
    CHATBOT_VOICE_MUSIC_OPUS_APPLICATION=audio
    CHATBOT_VOICE_MUSIC_OPUS_SIGNAL=music
    CHATBOT_VOICE_MUSIC_BITRATE_BPS=64000
    CHATBOT_VOICE_MUSIC_CONTROL_BARGE_IN_ENABLED=true
    CHATBOT_VOICE_MUSIC_RESUME_LAST_ENABLED=true
    CHATBOT_VOICE_MUSIC_LAST_TRACK_TTL=30m
    ```
  - 验收标准：
    - 配置默认保守，开启需显式设置。
    - env example 与 binder 测试一致。

- **任务 7.2：增加运行态诊断接口或 MCP 工具**
  - 目标：能查询设备当前音乐状态、最近曲目、播放失败原因和 mcpReady。
  - 输入：现有 device MCP 或 voice-gateway controller。
  - 输出：`xiaozhi_music_state` 工具或受控 HTTP endpoint。
  - 涉及文件：
    - `tools/xiaozhi-device-mcp/src/index.js`
    - `chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpGatewayToolService.java`
  - 验收标准：
    - 查询返回 `status/title/artist/requestId/source/failure/updatedAt`。
    - 无设备在线时给明确错误。

- **任务 7.3：远程部署 smoke 流程**
  - 目标：部署后用 live state 验证，而不是只看构建成功。
  - 输入：远程 `device_gateway` 运行环境。
  - 输出：固定 smoke 步骤。
  - 验收标准：
    - `/actuator/health` 通过。
    - 带 token WebSocket hello 成功。
    - `music_search` 返回 ready_to_play。
    - Java 日志出现同一 requestId 的 requested/buffering/media.start/resumed/finished。
    - 实机听到目标歌曲，不是旧推荐歌曲。

### 阶段 8：自动化回归与验收矩阵

- **任务 8.1：Java 单元与集成测试矩阵**
  - 目标：覆盖状态机、音质 profile、控制窗口和错误路径。
  - 输入：现有测试。
  - 输出：测试列表和命令。
  - 验证命令：
    ```bash
    /Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-speech-api,chatbot-voice-gateway,chatbot-bootstrap -am test
    ```
  - 必测用例：
    - 新歌播放取消旧 generation。
    - 手动暂停优先级高于 TTS 恢复。
    - 停止后继续使用 last track。
    - 播放失败保留 failure code。
    - 音乐 profile 使用 AUDIO/MUSIC。
    - 播放期 auto listen 不秒停。

- **任务 8.2：MCP 选曲测试矩阵**
  - 目标：防止旧推荐、弱匹配和 hotlist 污染。
  - 验证命令：
    ```bash
    cd "/Users/jiangzhibin/Documents/Codex/2026-06-18/https-buguyy-top/outputs/buguyy-mcp"
    node --test
    ```
  - 必测用例：
    - 精确歌手 + 歌名。
    - 只有歌名，多个候选需要确认。
    - 只有歌手，不自动播放第一首。
    - “热门”显式请求才使用 hotlist。
    - 连续两次不同 query 不复用旧 candidates。

- **任务 8.3：实机验收脚本**
  - 目标：覆盖用户真实抱怨的场景。
  - 验收短语：
    - “播放周杰伦的晴天”。
    - 播放中喊唤醒词，再说“暂停一下”。
    - 暂停后说“继续播放”。
    - 停止后说“继续播放”。
    - 播放中说“换一首陈奕迅的十年”。
    - 播放中说“现在播的什么”。
    - “来首热门歌”，确认不会污染下一次精确点歌。
  - 验收标准：
    - 每条用例能在日志中按 requestId 串起 Hermes/MCP/Java/设备。
    - 目标歌曲和口播内容一致。
    - 音乐播放 30 秒内无 `sentFrames=1` 秒停。

## 风险与缓解措施

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 固件不支持 24 kHz 下行音乐 | 升采样后无声或卡顿 | 默认保持 16 kHz 兼容；24 kHz 走灰度配置和实机验收 |
| 播放期 uplink 回声导致误识别 | 误暂停、误切歌 | 控制窗口只识别短控制意图；空文本/相似文本/低置信度自动恢复 |
| MCP 上游搜索不稳定 | 点歌失败或误播 | 强匹配 + `needs_confirmation`；日志记录候选；允许替换音乐源工具 |
| URL token 过期 | 继续播放失败 | last track 保存元数据，不只保存 URL；恢复时可触发重新解析 |
| 状态机过复杂 | 回归风险上升 | 小类拆分、TDD、每阶段保持可运行；不把语义判断塞进 Java |
| WebSocket 并发发送 | 音频帧和事件交错 | 继续使用单 session send 串行化策略；暂停等待 idle 后发 TTS/control |
| 工作区已有未提交改动 | 误改无关 ASR 文件 | 每次实施前 `git status --short`，只编辑计划涉及文件 |

## 关键路径

1. 先完成阶段 1 和阶段 2，否则误播问题无法定位。
2. 再完成阶段 3，否则继续播放和缓存问题无法解决。
3. 阶段 4 依赖阶段 3 的状态机，不能先做播放期唤醒。
4. 阶段 5 可以和阶段 2 并行，但 24 kHz 升级必须等阶段 6 固件验证。
5. 阶段 7、8 是交付闸门，不能省略。

## 默认执行决策

以下决策作为本轮开发默认值执行；后续如果用户明确调整，再按新增需求单独变更。默认值优先遵循 KISS/YAGNI，避免在第一轮把状态持久化、设备协商和弱匹配策略一起做重。

### 决策 1：最近曲目缓存

- **本轮采用：方案 A，只做内存缓存。**
- 理由：解决“停止后继续播放”和“自然结束后重播”不需要跨进程持久化；内存缓存足够覆盖当前实机体验问题，且无文件锁、损坏恢复和隐私清理成本。
- 非目标：本轮不写 `/app/data` JSON，不接数据库或 Redis。
- 后续升级触发条件：用户明确要求容器重启后仍能继续播放，或多实例部署需要共享播放状态。

备选方案：

- 方案 B：使用现有 `/app/data` 下的轻量 JSON 文件。优点是可跨重启；缺点是要处理文件锁、损坏恢复和隐私清理。
- 方案 C：接入数据库或 Redis。优点是扩展性好；缺点是对当前设备级播放状态过重。

### 决策 2：音质第一阶段目标采样率

- **本轮采用：方案 A，保持 16 kHz，但切到 `OPUS_APPLICATION_AUDIO`、`OPUS_SIGNAL_MUSIC` 和更高 bitrate。**
- 理由：当前 C6 播放链路已经验证 16 kHz 可用；先把 Opus application/signal/bitrate 配置纠正，能降低回归风险并快速改善“语音通话感”。
- 非目标：本轮不默认切 24 kHz，不实现设备能力协商。
- 后续升级触发条件：阶段 6 实机验证 24 kHz 连续播放稳定，且固件上报的 audio params 与服务端配置一致。

备选方案：

- 方案 B：默认 24 kHz mono。优点是更贴近 C6 输出采样率；缺点是需要固件和协议实机验证。
- 方案 C：按设备能力协商 16 kHz/24 kHz。优点是长期正确；缺点是实现量更大。

### 决策 3：选曲弱匹配策略

- **本轮采用：方案 A，弱匹配必须追问确认。**
- 理由：用户当前主要痛点是误播旧推荐和无关歌曲；第一轮宁可多一轮确认，也不能继续播放错误歌曲。
- 非目标：本轮不调置信度阈值，不做“始终播放最高分”。
- 后续升级触发条件：有足够真实日志样本后，再评估是否引入高置信自动播放阈值。

备选方案：

- 方案 B：置信度超过阈值自动播放，否则追问。优点是体验平衡；缺点是阈值需要调优。
- 方案 C：始终播放最高分。优点是最快；缺点是会复现当前误播问题。

## 用户反馈区域

请在此区域补充您对整体规划的意见和建议：

```text
用户补充内容：

---

---

---
```

## 执行交接

建议执行方式：

1. 使用子代理驱动逐阶段实现，阶段 1、2、3 完成后先做一次代码审查。
2. 音质与固件协同阶段必须插入实机验收，不以单元测试通过作为完成标准。
3. 每个阶段结束后输出影响范围和回归清单，但不自动 commit、push 或部署，除非用户明确确认。
