# 小智服务端结束会话意图实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 当用户在普通对话或播放期 barge-in 中表达“退出、不聊了、回头聊”等结束会话意图时，由 Hermes agent 返回结构化事件，Java 服务端播报告别语并主动关闭当前 WebSocket，让设备回到待机并等待下一次唤醒词重新开始。

**架构：** 自然语言语义判断仍由 Hermes agent 完成，Java 只消费 `xiaozhi.agent_event` 结构化事件，不在本地维护“退出吧、滚吧、停止”等词表。普通回合在 Hermes 流中收到 `session_end` 事件后播放 `confirmation_text` 并关闭 WebSocket；播放期 barge-in 的 ASR 文本只允许进入一个专用控制意图请求，若 Hermes 返回 `session_end`，服务端取消当前 TTS、播放告别语、关闭 WebSocket，不把这段带回声的文本当作普通问题继续聊天。

**技术栈：** Java 21、Spring Boot 3.4、Spring WebSocket、Maven 多模块、JUnit 5、AssertJ、现有 `HermesAgentEventExtractor`、`XiaozhiTtsRuntime`、`XiaozhiVoiceSessionService`、小智 WebSocket 协议。

---

## 关键结论

- 当前固件侧已经可以在播放期发送 `listen.start mode=barge_in` 并上传音频；服务端已有 barge-in ASR/取消当前 TTS 的基础链路。
- 本计划不是替代 `2026-06-19-xiaozhi-server-barge-in-interrupt.md`，而是在其基础上增加“结束会话控制意图”。
- “停止”不能简单等同于退出会话：`停止播放` 应优先是音乐控制，`别说了/停一下` 应是打断当前 TTS，`不聊了/退出吧/回头聊` 才是结束会话。
- Java 服务端不做自然语言词表判断，避免把 `停止播放`、`停一下`、脏话插入、ASR 误识别误判成关闭连接。
- Hermes agent 应输出结构化事件：

```text
event: xiaozhi.agent_event
data: {"action":"session_end","confirmation_text":"回头再聊"}
```

- Java 侧只识别 `action=session_end`，读取 `confirmation_text`；为空时使用配置默认文案。
- WebSocket 关闭必须在告别 TTS 播放结束之后执行；如果 TTS 失败，也应关闭 WebSocket，不能让会话停在半结束状态。
- 不新增 `goodbye` 等固件未知 JSON 事件；结束会话的协议信号就是告别 TTS 后主动关闭 WebSocket，避免违反“不修改固件协议”的非目标。
- 播放期 barge-in 退出路径不能把 barge-in ASR 文本直接提交普通聊天。它只能用于向 Hermes 询问“这是控制意图吗”，并且只接受结构化 `session_end` 或忽略。
- 执行阶段已直接在当前工作区完成；当前改动包含本计划文档、`sessionend` 新包、voice-gateway 生产代码和对应测试。未提交、未推送、未创建分支。
- 评审中额外修正：barge-in 控制意图请求不能只受 `session-end.enabled` 控制；当音乐 handler 可用时，即使 `session_end` 灰度开关关闭，也应允许 Hermes 返回 `music_stop/music_pause/music_resume` 并走现有音乐控制路径。

## 成功标准

- 普通用户回合中，Hermes 返回 `session_end` 事件时，服务端不再继续普通回复合成，而是播放告别语并主动关闭当前 `WebSocketSession`。
- 播放期 barge-in ASR 得到“退出吧/不聊了/回头聊”等语义时，Java 通过 Hermes 结构化控制意图请求确认 `session_end`，然后取消当前 TTS、播放告别语、主动关闭 WebSocket。
- 播放期 barge-in ASR 得到“停一下/别说了”等非退出控制语义时，仍按现有 barge-in 打断路径处理，进入 follow-up，不关闭 WebSocket。
- 播放期 barge-in ASR 得到“停止播放/别放歌了”时，不走 `session_end`，应由 Hermes 输出 `music_stop` 并沿现有音乐控制路径处理。
- 服务端关闭 WebSocket 后，`XiaozhiWebSocketHandler.afterConnectionClosed()` 触发 `sessionService.close(session)`，清理 session、MCP bridge、device session 映射、音乐和 TTS 状态。
- `session_end` 默认关闭开关为 `false`，线上通过环境变量灰度打开。
- 验证命令通过：

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=HermesAgentEventExtractorTest,XiaozhiHermesAgentEventTextFilterTest,XiaozhiSessionEndActionTest,XiaozhiVoiceGatewayBeansTest,XiaozhiVoiceSessionTest,XiaozhiVoiceSessionServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-bootstrap -am test
```

## 非目标

- 不修改固件协议；继续使用现有 `listen.start mode=barge_in` 和服务端主动 close WebSocket。
- 不在 Java 本地维护中文退出词表。
- 不把 “stop/停止” 统一映射成结束会话。
- 不新增独立 REST API。
- 不改 Hermes agent 之外的自然语言理解职责。
- 不在未确认范围内提交、推送或创建分支。

## 文件结构

- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/hermes/HermesAgentEvent.java`
  - 职责：增加 `reason` 字段，保持 `session_end`、`music_*`、`create_reminder` 事件共用一个结构化载体。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/hermes/HermesAgentEventExtractor.java`
  - 职责：解析 `reason`，继续忽略无效/无关 SSE。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/sessionend/XiaozhiSessionEndProperties.java`
  - 职责：配置 `enabled`、`defaultConfirmationText`、`closeStatusCode`、`closeReason`。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/sessionend/XiaozhiSessionEndAction.java`
  - 职责：从 `HermesAgentEvent` 转成可执行结束动作，统一默认文案。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeans.java`
  - 职责：绑定 `chatbot.voice.session-end.*` 配置 bean。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-bootstrap/src/main/resources/application.yml`
  - 职责：新增默认关闭的 `chatbot.voice.session-end.*` 配置。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSession.java`
  - 职责：增加播放期退出专用原子迁移方法，取消当前播放后进入 `PROCESSING`，避免告别 TTS 被旧播放代际清理。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
  - 职责：消费 `session_end` 事件、播放告别语、关闭 WebSocket；播放期 barge-in 命中退出意图时走同一结束会话动作。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/hermes/HermesAgentEventExtractorTest.java`
  - 职责：覆盖 `session_end` SSE 解析。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiHermesAgentEventTextFilterTest.java`
  - 职责：覆盖被 Responses API 包进文本增量的 `session_end` 事件过滤。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/sessionend/XiaozhiSessionEndActionTest.java`
  - 职责：覆盖默认文案、空文案、非 `session_end` 事件。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeansTest.java`
  - 职责：覆盖配置默认关闭和自定义值绑定。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionTest.java`
  - 职责：覆盖播放期退出状态迁移。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`
  - 职责：覆盖普通回合和 barge-in 回合的告别播报与 WebSocket close。

## 协议与 Hermes 合约

### Hermes 结构化事件

普通回合和播放期控制意图都使用同一种事件格式：

```text
event: xiaozhi.agent_event
data: {"action":"session_end","confirmation_text":"回头再聊","reason":"user_requested_exit"}
```

字段语义：

```text
action: 必须等于 session_end
confirmation_text: 需要播报给用户的告别语；为空时 Java 使用默认文案
reason: 日志字段，用于区分 user_requested_exit、abusive_exit、cancel_conversation 等来源
```

### Java 执行动作

```text
1. 校验 chatbot.voice.session-end.enabled=true
2. 如果当前在播放普通回复，取消当前 TTS
3. 播报 confirmation_text 或默认告别语
4. 关闭 WebSocketSession，CloseStatus 使用配置值
5. 由现有 afterConnectionClosed -> sessionService.close(session) 完成资源清理
```

### barge-in 控制意图请求

播放期 barge-in ASR 文本不能进入普通聊天，只能走一个控制意图请求。请求文本建议固定为：

```text
用户在设备播放回答时插话，原始 ASR 文本如下。请只判断是否是结束会话意图、音乐控制意图或普通打断；若是结束会话，只输出 xiaozhi.agent_event: session_end，不要输出自然语言正文。
ASR: 退出吧
```

Hermes 的允许输出：

```text
session_end: Java 播报告别语并关闭 WebSocket
music_stop/music_pause/music_resume: Java 走现有音乐控制，不关闭 WebSocket
无结构化事件: Java 保持现有 barge-in 打断行为，进入 follow-up
```

## 任务 1：扩展 Hermes agent event 模型

**文件：**
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/hermes/HermesAgentEvent.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/hermes/HermesAgentEventExtractor.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/hermes/HermesAgentEventExtractorTest.java`

- [ ] **步骤 1：编写失败测试**

在 `HermesAgentEventExtractorTest` 增加：

```java
@Test
void shouldExtractSessionEndEventFromHermesSse() {
    var extractor = new HermesAgentEventExtractor();

    var events = extractor.accept("""
            event: xiaozhi.agent_event
            data: {"action":"session_end","confirmation_text":"回头再聊","reason":"user_requested_exit"}

            """);

    assertThat(events).containsExactly(new HermesAgentEvent(
            "session_end",
            null,
            0L,
            "回头再聊",
            null,
            null,
            null,
            0L,
            "user_requested_exit"
    ));
}
```

- [ ] **步骤 2：运行测试确认失败**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=HermesAgentEventExtractorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：

```text
constructor HermesAgentEvent cannot be applied to given types
```

- [ ] **步骤 3：扩展 record 和 extractor**

`HermesAgentEvent.java` 改为：

```java
public record HermesAgentEvent(
        String action,
        String message,
        long delaySeconds,
        String confirmationText,
        String mediaUrl,
        String title,
        String artist,
        long positionSeconds,
        String reason
) {
}
```

`HermesAgentEventExtractor.extractEvent(...)` 构造器补最后一个参数：

```java
return java.util.Optional.of(new HermesAgentEvent(
        root.path("action").asText(null),
        root.path("message").asText(null),
        root.path("delay_seconds").asLong(0L),
        root.path("confirmation_text").asText(null),
        root.path("media_url").asText(null),
        root.path("title").asText(null),
        root.path("artist").asText(null),
        root.path("position_seconds").asLong(0L),
        root.path("reason").asText(null)
));
```

同步更新现有测试里所有 `new HermesAgentEvent(...)`，尾部补 `null`。

- [ ] **步骤 4：运行测试验证通过**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=HermesAgentEventExtractorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：`BUILD SUCCESS`

## 任务 2：过滤文本增量里的 session_end 事件

**文件：**
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiHermesAgentEventTextFilterTest.java`

- [ ] **步骤 1：编写测试**

增加：

```java
@Test
void shouldExtractEmbeddedSessionEndEventAndSuppressEventText() {
    var filter = new XiaozhiHermesAgentEventTextFilter();

    var result = filter.accept("""
            event: xiaozhi.agent_event
            data: {"action":"session_end","confirmation_text":"回头再聊","reason":"user_requested_exit"}

            """);

    assertThat(result.text()).isEmpty();
    assertThat(result.events()).singleElement().satisfies(event -> {
        assertThat(event.action()).isEqualTo("session_end");
        assertThat(event.confirmationText()).isEqualTo("回头再聊");
        assertThat(event.reason()).isEqualTo("user_requested_exit");
    });
}
```

- [ ] **步骤 2：运行测试**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiHermesAgentEventTextFilterTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：`BUILD SUCCESS`。如果任务 1 已正确扩展 event 模型，过滤器无需改实现。

## 任务 3：新增 session-end 配置与动作对象

**文件：**
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/sessionend/XiaozhiSessionEndProperties.java`
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/sessionend/XiaozhiSessionEndAction.java`
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/sessionend/XiaozhiSessionEndActionTest.java`

- [ ] **步骤 1：编写失败测试**

```java
package com.jzb.chatbot.voice.sessionend;

import static org.assertj.core.api.Assertions.assertThat;

import com.jzb.chatbot.voice.hermes.HermesAgentEvent;
import org.junit.jupiter.api.Test;

class XiaozhiSessionEndActionTest {

    private final XiaozhiSessionEndProperties properties =
            new XiaozhiSessionEndProperties(true, "回头再聊", 1000, "session ended");

    @Test
    void shouldCreateActionFromSessionEndEvent() {
        var event = new HermesAgentEvent(
                "session_end", null, 0, "下次再聊", null, null, null, 0, "user_requested_exit"
        );

        var action = XiaozhiSessionEndAction.from(event, properties);

        assertThat(action).isNotNull();
        assertThat(action.confirmationText()).isEqualTo("下次再聊");
        assertThat(action.reason()).isEqualTo("user_requested_exit");
    }

    @Test
    void shouldUseDefaultConfirmationWhenEventTextIsBlank() {
        var event = new HermesAgentEvent(
                "session_end", null, 0, " ", null, null, null, 0, null
        );

        var action = XiaozhiSessionEndAction.from(event, properties);

        assertThat(action.confirmationText()).isEqualTo("回头再聊");
        assertThat(action.reason()).isEqualTo("session_end");
    }

    @Test
    void shouldIgnoreWhenDisabledOrNotSessionEnd() {
        assertThat(XiaozhiSessionEndAction.from(
                new HermesAgentEvent("music_stop", null, 0, null, null, null, null, 0, null),
                properties
        )).isNull();

        assertThat(XiaozhiSessionEndAction.from(
                new HermesAgentEvent("session_end", null, 0, "回头再聊", null, null, null, 0, null),
                new XiaozhiSessionEndProperties(false, "回头再聊", 1000, "session ended")
        )).isNull();
    }
}
```

- [ ] **步骤 2：运行测试确认失败**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiSessionEndActionTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：

```text
package com.jzb.chatbot.voice.sessionend does not exist
```

- [ ] **步骤 3：实现配置 record**

```java
package com.jzb.chatbot.voice.sessionend;

public record XiaozhiSessionEndProperties(
        boolean enabled,
        String defaultConfirmationText,
        int closeStatusCode,
        String closeReason
) {

    public XiaozhiSessionEndProperties {
        if (defaultConfirmationText == null || defaultConfirmationText.isBlank()) {
            defaultConfirmationText = "回头再聊";
        }
        if (closeStatusCode <= 0) {
            closeStatusCode = 1000;
        }
        if (closeReason == null || closeReason.isBlank()) {
            closeReason = "session ended";
        }
    }
}
```

- [ ] **步骤 4：实现动作对象**

```java
package com.jzb.chatbot.voice.sessionend;

import com.jzb.chatbot.voice.hermes.HermesAgentEvent;

public record XiaozhiSessionEndAction(String confirmationText, String reason) {

    public static XiaozhiSessionEndAction from(
            HermesAgentEvent event,
            XiaozhiSessionEndProperties properties
    ) {
        if (event == null || properties == null || !properties.enabled()) {
            return null;
        }
        if (!"session_end".equals(event.action())) {
            return null;
        }
        var confirmationText = event.confirmationText();
        if (confirmationText == null || confirmationText.isBlank()) {
            confirmationText = properties.defaultConfirmationText();
        }
        var reason = event.reason();
        if (reason == null || reason.isBlank()) {
            reason = "session_end";
        }
        return new XiaozhiSessionEndAction(confirmationText, reason);
    }
}
```

- [ ] **步骤 5：运行测试验证通过**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiSessionEndActionTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：`BUILD SUCCESS`

## 任务 4：绑定配置默认关闭

**文件：**
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeans.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-bootstrap/src/main/resources/application.yml`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeansTest.java`

- [ ] **步骤 1：编写失败测试**

在 `XiaozhiVoiceGatewayBeansTest` 增加：

```java
@Test
void shouldCreateSessionEndPropertiesWithDefaultDisabled() {
    contextRunner.run(context -> {
        assertThat(context).hasSingleBean(com.jzb.chatbot.voice.sessionend.XiaozhiSessionEndProperties.class);

        var properties = context.getBean(com.jzb.chatbot.voice.sessionend.XiaozhiSessionEndProperties.class);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.defaultConfirmationText()).isEqualTo("回头再聊");
        assertThat(properties.closeStatusCode()).isEqualTo(1000);
        assertThat(properties.closeReason()).isEqualTo("session ended");
    });
}

@Test
void shouldCreateConfiguredSessionEndProperties() {
    contextRunner
            .withPropertyValues(
                    "chatbot.voice.session-end.enabled=true",
                    "chatbot.voice.session-end.default-confirmation-text=下次再聊",
                    "chatbot.voice.session-end.close-status-code=1000",
                    "chatbot.voice.session-end.close-reason=user requested exit"
            )
            .run(context -> {
                var properties = context.getBean(com.jzb.chatbot.voice.sessionend.XiaozhiSessionEndProperties.class);

                assertThat(properties.enabled()).isTrue();
                assertThat(properties.defaultConfirmationText()).isEqualTo("下次再聊");
                assertThat(properties.closeStatusCode()).isEqualTo(1000);
                assertThat(properties.closeReason()).isEqualTo("user requested exit");
            });
}
```

- [ ] **步骤 2：运行测试确认失败**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceGatewayBeansTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：context 中没有 `XiaozhiSessionEndProperties` bean。

- [ ] **步骤 3：新增 bean**

`XiaozhiVoiceGatewayBeans.java` 增加 import：

```java
import com.jzb.chatbot.voice.sessionend.XiaozhiSessionEndProperties;
```

新增 bean：

```java
@Bean
XiaozhiSessionEndProperties xiaozhiSessionEndProperties(Environment environment) {
    var binder = Binder.get(environment);
    return new XiaozhiSessionEndProperties(
            binder.bind("chatbot.voice.session-end.enabled", Boolean.class).orElse(false),
            binder.bind("chatbot.voice.session-end.default-confirmation-text", String.class).orElse("回头再聊"),
            binder.bind("chatbot.voice.session-end.close-status-code", Integer.class).orElse(1000),
            binder.bind("chatbot.voice.session-end.close-reason", String.class).orElse("session ended")
    );
}
```

- [ ] **步骤 4：新增 application.yml 默认配置**

在 `chatbot.voice` 下新增：

```yaml
    session-end:
      enabled: ${CHATBOT_VOICE_SESSION_END_ENABLED:false}
      default-confirmation-text: ${CHATBOT_VOICE_SESSION_END_DEFAULT_CONFIRMATION_TEXT:回头再聊}
      close-status-code: ${CHATBOT_VOICE_SESSION_END_CLOSE_STATUS_CODE:1000}
      close-reason: ${CHATBOT_VOICE_SESSION_END_CLOSE_REASON:session ended}
```

- [ ] **步骤 5：运行测试验证通过**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceGatewayBeansTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：`BUILD SUCCESS`

## 任务 5：明确不扩展固件 JSON 协议

**文件：**
- 不修改 `XiaozhiServerEventFactory`

- [ ] **步骤 1：保留现有服务端事件工厂**

不新增 `goodbye`、`session_end` 等固件未知 JSON 事件。验收信号由现有 TTS 事件和 `WebSocketSession.close(...)` 组成：

```text
tts.start -> tts.sentence_start(告别语) -> binary audio -> tts.stop -> websocket close
```

- [ ] **步骤 2：实现阶段不得修改事件工厂**

运行最终 diff 检查时确认 `XiaozhiServerEventFactory.java` 没有为本功能新增未知协议事件。

## 任务 6：补 session 原子状态迁移

**文件：**
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSession.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionTest.java`

- [ ] **步骤 1：编写失败测试**

在 `XiaozhiVoiceSessionTest` 增加：

```java
@Test
void shouldPrepareSessionEndPlaybackFromSpeaking() {
    var session = new XiaozhiVoiceSession("ws-session-1");
    session.markSpeaking();
    session.updateCurrentSpeakingText("这是一段正在播放的回答。");

    assertThat(session.prepareSessionEndPlayback()).isTrue();

    assertThat(session.state()).isEqualTo(XiaozhiVoiceSession.State.PROCESSING);
    assertThat(session.currentSpeakingText()).isEmpty();
}

@Test
void shouldIgnoreSessionEndPlaybackWhenAlreadyIdle() {
    var session = new XiaozhiVoiceSession("ws-session-1");

    assertThat(session.prepareSessionEndPlayback()).isFalse();
    assertThat(session.state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
}
```

- [ ] **步骤 2：运行测试确认失败**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceSessionTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：

```text
cannot find symbol: method prepareSessionEndPlayback()
```

- [ ] **步骤 3：实现原子方法**

在 `XiaozhiVoiceSession` 增加：

```java
public synchronized boolean prepareSessionEndPlayback() {
    if (state == State.IDLE) {
        return false;
    }
    if (playback != null) {
        playback.cancel();
        playback = null;
    }
    terminateAsrStreamLocked();
    clearBargeInTurnLocked();
    clearCurrentSpeakingLocked();
    audioFrames.clear();
    activePlaybackGeneration = NO_PLAYBACK_GENERATION;
    turnGeneration++;
    state = State.PROCESSING;
    return true;
}
```

说明：这里进入 `PROCESSING` 是为了让告别 TTS 使用现有 `speakWithRuntime(...)` 的普通回复路径，并避免 `Idle` 下通知播放路径和普通回合清理路径交错。

- [ ] **步骤 4：运行测试验证通过**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceSessionTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：`BUILD SUCCESS`

## 任务 7：普通回合处理 session_end

**文件：**
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`

- [ ] **步骤 1：编写失败测试**

在 `XiaozhiVoiceSessionServiceTest` 增加：

```java
@Test
void shouldSpeakGoodbyeAndCloseWebSocketWhenHermesRequestsSessionEnd() {
    var serviceWithSessionEnd = newServiceWithSessionEnd(
            new StaticSseHermesClient("""
                    event: xiaozhi.agent_event
                    data: {"action":"session_end","confirmation_text":"回头再聊","reason":"user_requested_exit"}

                    """),
            new RecordingTextToSpeechClient(),
            true
    );
    var session = openSession(serviceWithSessionEnd);

    runSingleTurn(serviceWithSessionEnd, session);

    assertThat(session.isOpen()).isFalse();
    assertThat(session.getCloseStatus()).isNotNull();
    assertThat(textPayloads(session)).anySatisfy(payload -> assertThat(payload)
            .contains("\"type\":\"tts\"", "\"state\":\"sentence_start\"", "\"text\":\"回头再聊\""));
}
```

新增测试 helper：

```java
private XiaozhiVoiceSessionService newServiceWithSessionEnd(
        HermesClient hermesClient,
        TextToSpeechClient textToSpeechClient,
        boolean enabled
) {
    var eventFactory = new XiaozhiServerEventFactory(new ObjectMapper());
    return new XiaozhiVoiceSessionService(
            codec,
            new FakeSpeechToTextClient(),
            hermesClient,
            new XiaozhiTtsRuntime(textToSpeechClient, codec, eventFactory),
            eventFactory,
            new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner"),
            new XiaozhiVoiceTokenAuth(""),
            newMcpBridge(),
            new XiaozhiAsrMode("sentence"),
            new FakeStreamingSpeechToTextClient(),
            XiaozhiAudioParams.defaults(),
            new XiaozhiVoiceProfileResolver(new VoiceId("default"), 1.0, 1.0),
            new XiaozhiBargeInDetector(new XiaozhiBargeInProperties(false, 2, 500, 0.82, Duration.ofSeconds(2))),
            null,
            null,
            new com.jzb.chatbot.voice.sessionend.XiaozhiSessionEndProperties(
                    enabled, "回头再聊", 1000, "session ended"
            )
    );
}
```

如果当前 `XiaozhiVoiceSessionService` 构造器还没有 session-end 参数，本测试应先失败。

- [ ] **步骤 2：运行测试确认失败**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceSessionServiceTest#shouldSpeakGoodbyeAndCloseWebSocketWhenHermesRequestsSessionEnd -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：构造器或行为失败。

- [ ] **步骤 3：给 service 注入配置**

`XiaozhiVoiceSessionService` 增加字段：

```java
private final XiaozhiSessionEndProperties sessionEndProperties;
```

所有公开构造器最终委托到主构造器；旧构造器使用：

```java
new XiaozhiSessionEndProperties(false, "回头再聊", 1000, "session ended")
```

Spring `@Autowired` 构造器新增参数：

```java
XiaozhiSessionEndProperties sessionEndProperties
```

主构造器赋值：

```java
this.sessionEndProperties = sessionEndProperties;
```

- [ ] **步骤 4：新增关闭方法**

在 `XiaozhiVoiceSessionService` 增加：

```java
private boolean handleSessionEndAction(
        WebSocketSession webSocketSession,
        XiaozhiVoiceSession voiceSession,
        TurnGuard turnGuard,
        HermesAgentEvent event
) {
    var action = XiaozhiSessionEndAction.from(event, sessionEndProperties);
    if (action == null || !turnGuard.active()) {
        return false;
    }
    executeSessionEnd(webSocketSession, voiceSession, action, turnGuard);
    return true;
}

private void executeSessionEnd(
        WebSocketSession webSocketSession,
        XiaozhiVoiceSession voiceSession,
        XiaozhiSessionEndAction action,
        TurnGuard turnGuard
) {
    stopMusic(voiceSession);
    ttsRuntime.cancel(voiceSession.sessionId());
    voiceSession.prepareSessionEndPlayback();
    var generation = voiceSession.markProcessing();
    speakWithRuntime(
            webSocketSession,
            voiceSession,
            generation,
            turnGuard,
            List.of(action.confirmationText()),
            System.nanoTime(),
            "语音合成失败"
    );
    closeWebSocket(webSocketSession);
}

private void closeWebSocket(WebSocketSession webSocketSession) {
    try {
        if (webSocketSession.isOpen()) {
            webSocketSession.close(new org.springframework.web.socket.CloseStatus(
                    sessionEndProperties.closeStatusCode(),
                    sessionEndProperties.closeReason()
            ));
        }
    } catch (IOException exception) {
        log.warn("xiaozhi websocket session-end close failed, sessionId={}, message={}",
                webSocketSession.getId(), exception.getMessage(), exception);
    }
}
```

- [ ] **步骤 5：接入 Hermes event 处理**

修改 `handleHermesAgentEvent(...)` 的开头，顺序必须在音乐和提醒之前：

```java
if (handleSessionEndAction(webSocketSession, voiceSession, turnGuard, event)) {
    return null;
}
```

并在 `streamChatAndSpeak(...)` / `acceptHermesChunk(...)` 的事件处理后通过 `turnGuard.active()` 和 `turnCancelled(...)` 停止后续文本合成。若 `executeSessionEnd(...)` 关闭了 WebSocket，后续循环应自然因 `turnGuard` 或 session 状态失效停止；如不够稳定，新增一个 `SessionEndRequestedException` 中断 Hermes 流。

- [ ] **步骤 6：运行测试验证通过**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceSessionServiceTest#shouldSpeakGoodbyeAndCloseWebSocketWhenHermesRequestsSessionEnd -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：`BUILD SUCCESS`

## 任务 8：barge-in 退出意图走 Hermes 控制判断

**文件：**
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`

- [ ] **步骤 1：编写失败测试**

```java
@Test
void shouldCloseWebSocketWhenBargeInTextIsSessionEndIntent() {
    var streamingSpeech = new ImmediateStreamingSpeechToTextClient("退出吧", "streaming-provider");
    var hermesClient = new StaticSseHermesClient("""
            event: xiaozhi.agent_event
            data: {"action":"session_end","confirmation_text":"回头再聊","reason":"user_requested_exit"}

            """);
    var serviceWithSessionEnd = newServiceWithBargeInAndSessionEnd(
            hermesClient,
            streamingSpeech,
            new RecordingTextToSpeechClient()
    );
    var session = openSession(serviceWithSessionEnd);
    var voiceSession = serviceWithSessionEnd.getSession(session.getId());
    voiceSession.markSpeaking();
    voiceSession.updateCurrentSpeakingText("这是一段正在播放的回答。");

    serviceWithSessionEnd.handleText(session, new XiaozhiClientMessage(
            "listen", "start", "barge_in", null, null, "ws-session-1", null
    ));
    serviceWithSessionEnd.handleText(session, new XiaozhiClientMessage(
            "listen", "stop", "barge_in", null, null, "ws-session-1", null
    ));

    assertThat(awaitClosed(session)).isTrue();
    assertThat(textPayloads(session)).anySatisfy(payload -> assertThat(payload)
            .contains("\"type\":\"tts\"", "\"state\":\"sentence_start\"", "\"text\":\"回头再聊\""));
}
```

新增 helper：

```java
private boolean awaitClosed(TestWebSocketSession session) {
    var deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
    while (System.nanoTime() < deadline) {
        if (!session.isOpen()) {
            return true;
        }
        try {
            Thread.sleep(10);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    return !session.isOpen();
}
```

- [ ] **步骤 2：运行测试确认失败**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceSessionServiceTest#shouldCloseWebSocketWhenBargeInTextIsSessionEndIntent -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：WebSocket 未关闭。

- [ ] **步骤 3：实现 barge-in 控制意图查询**

在 `processBargeInTurn(...)` 中，`decision.interrupt()` 为 true 之后、`cancelPlaybackAndListenIfBargeInTurnActive(...)` 之前，先调用：

```java
if (tryHandleBargeInControlIntent(webSocketSession, voiceSession, turn, text)) {
    return;
}
```

新增：

```java
private boolean tryHandleBargeInControlIntent(
        WebSocketSession webSocketSession,
        XiaozhiVoiceSession voiceSession,
        XiaozhiBargeInTurn turn,
        String text
) {
    if (!sessionEndProperties.enabled()) {
        return false;
    }
    if (!voiceSession.activeBargeInTurnMatches(turn)) {
        return false;
    }
    var prompt = """
            用户在设备播放回答时插话，原始 ASR 文本如下。
            请只判断是否是结束会话意图、音乐控制意图或普通打断。
            如果是结束会话，只输出 xiaozhi.agent_event: session_end，不要输出自然语言正文。
            ASR: %s
            """.formatted(text);
    try (var chunks = hermesClient.streamChat(new HermesRequest(
            new DeviceId(voiceSession.deviceId()),
            new ConversationId(voiceSession.conversationId()),
            prompt
    ), hermesClientConfig)) {
        var extractor = new HermesAgentEventExtractor();
        for (var chunk : (Iterable<String>) chunks::iterator) {
            for (var event : extractor.accept(chunk)) {
                var action = XiaozhiSessionEndAction.from(event, sessionEndProperties);
                if (action != null && voiceSession.activeBargeInTurnMatches(turn)) {
                    ttsRuntime.cancel(voiceSession.sessionId(), turn.playbackGeneration());
                    executeSessionEnd(webSocketSession, voiceSession, action, TurnGuard.none());
                    return true;
                }
                if (musicActionHandler != null && musicActionHandler.handle(webSocketSession, voiceSession, event)) {
                    return true;
                }
            }
        }
    }
    return false;
}
```

约束：如果 Hermes 无结构化事件或调用失败，返回 `false`，继续现有 barge-in 打断路径，不关闭 WebSocket。

- [ ] **步骤 4：运行测试验证通过**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceSessionServiceTest#shouldCloseWebSocketWhenBargeInTextIsSessionEndIntent -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：`BUILD SUCCESS`

## 任务 9：防止“停止播放”误关会话

**文件：**
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`

- [ ] **步骤 1：编写测试**

```java
@Test
void shouldNotCloseWebSocketWhenBargeInControlIntentIsMusicStop() {
    var streamingSpeech = new ImmediateStreamingSpeechToTextClient("停止播放", "streaming-provider");
    var hermesClient = new StaticSseHermesClient("""
            event: xiaozhi.agent_event
            data: {"action":"music_stop","confirmation_text":"已停止播放"}

            """);
    var serviceWithSessionEnd = newServiceWithBargeInAndSessionEnd(
            hermesClient,
            streamingSpeech,
            new RecordingTextToSpeechClient()
    );
    var session = openSession(serviceWithSessionEnd);
    var voiceSession = serviceWithSessionEnd.getSession(session.getId());
    voiceSession.markSpeaking();
    voiceSession.updateCurrentSpeakingText("这是一段正在播放的回答。");

    serviceWithSessionEnd.handleText(session, new XiaozhiClientMessage(
            "listen", "start", "barge_in", null, null, "ws-session-1", null
    ));
    serviceWithSessionEnd.handleText(session, new XiaozhiClientMessage(
            "listen", "stop", "barge_in", null, null, "ws-session-1", null
    ));

    assertThat(session.isOpen()).isTrue();
    assertThat(textPayloads(session)).noneSatisfy(payload -> assertThat(payload)
            .contains("\"text\":\"回头再聊\""));
}
```

- [ ] **步骤 2：运行测试**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=XiaozhiVoiceSessionServiceTest#shouldNotCloseWebSocketWhenBargeInControlIntentIsMusicStop -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：`BUILD SUCCESS`

## 任务 10：补 bootstrap 回归

**文件：**
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-bootstrap/src/test/java/com/jzb/chatbot/bootstrap/ChatbotApplicationTest.java`

- [ ] **步骤 1：确认应用上下文测试覆盖新增配置**

如果 `ChatbotApplicationTest` 已经只做 `contextLoads`，无需增加复杂断言。新增配置 bean 后，运行 bootstrap 测试即可覆盖 Spring wiring。

- [ ] **步骤 2：运行 bootstrap 测试**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-bootstrap -am test
```

预期：`BUILD SUCCESS`

## 任务 11：全量定向验证

**文件：**
- 不修改文件

- [ ] **步骤 1：运行 voice-gateway 定向测试**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-voice-gateway -am -Dtest=HermesAgentEventExtractorTest,XiaozhiHermesAgentEventTextFilterTest,XiaozhiSessionEndActionTest,XiaozhiVoiceGatewayBeansTest,XiaozhiVoiceSessionTest,XiaozhiVoiceSessionServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：`BUILD SUCCESS`

- [ ] **步骤 2：运行 bootstrap 回归**

```bash
/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn -pl chatbot-bootstrap -am test
```

预期：`BUILD SUCCESS`

- [ ] **步骤 3：人工 smoke 清单**

服务端部署后用真实设备或 WebSocket 调试台验证：

```text
1. 普通唤醒后说“退出吧”：设备播报“回头再聊”，WebSocket 关闭，设备回 Idle。
2. 再次说唤醒词：设备重新建立 WebSocket，发送 hello 和 session new，能正常继续聊天。
3. 播放 TTS 时说“退出吧”：当前 TTS 中断，播报告别语，WebSocket 关闭。
4. 播放 TTS 时说“停一下”：当前 TTS 中断，WebSocket 不关闭，进入 follow-up。
5. 播音乐时说“停止播放”：音乐停止，WebSocket 不关闭。
```

## Definition of Done

- Java 本地不包含中文退出词表。
- `session_end` 只从 Hermes agent 结构化事件触发。
- 告别语播放完成后主动关闭 WebSocket。
- 不新增固件未知 JSON 事件；不修改 `XiaozhiServerEventFactory` 来承载结束会话语义。
- `停止播放` 不会关闭聊天会话。
- 默认配置关闭，可通过 `CHATBOT_VOICE_SESSION_END_ENABLED=true` 灰度开启。
- 所有验证命令通过。

## 执行记录

- 2026-06-19：计划评估结论为可行，但必须收敛为 Hermes `session_end` 结构化事件 + 告别 TTS 后主动 close WebSocket，不新增固件未知 JSON 事件。
- 2026-06-19：已完成 Java 实现和测试覆盖；生产代码未引入中文退出词表，`session_end` 执行仍受 `CHATBOT_VOICE_SESSION_END_ENABLED` 控制。
- 2026-06-19：补充红绿验证：`shouldHandleBargeInMusicStopWhenSessionEndIsDisabled` 在旧 gate 下失败，恢复修正后通过，锁定“音乐控制不被 session-end 开关误禁用”的边界。
