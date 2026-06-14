# Chatbot Service Java 中间件重写实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在 `/Users/jiangzhibin/workspace/chatbot-service-java` 中创建 Java 21 + Spring Boot 3 多模块服务，重写设备文本网关和小智语音网关两个中间件。

**架构：** 单一 Spring Boot 进程承载 REST 文本网关和 WebSocket 语音网关。Hermes、ASR、TTS 全部通过接口隔离，第一阶段使用 Fake Provider 保证协议和测试先跑通。

**技术栈：** Java 21、Spring Boot 3、Maven 多模块、JUnit 5、Mockito、Lombok、Spring Web、Spring WebSocket、虚拟线程。

---

## 文件结构

```text
/Users/jiangzhibin/workspace/chatbot-service-java/
  pom.xml
  README.md
  docs/architecture/hermes-chatbot-service-architecture.md
  chatbot-bootstrap/pom.xml
  chatbot-bootstrap/src/main/java/com/jzb/chatbot/bootstrap/ChatbotApplication.java
  chatbot-bootstrap/src/main/resources/application.yml
  chatbot-common/pom.xml
  chatbot-common/src/main/java/com/jzb/chatbot/common/id/DeviceId.java
  chatbot-common/src/main/java/com/jzb/chatbot/common/id/SessionId.java
  chatbot-common/src/main/java/com/jzb/chatbot/common/id/ConversationId.java
  chatbot-common/src/main/java/com/jzb/chatbot/common/id/VoiceId.java
  chatbot-hermes-adapter/pom.xml
  chatbot-hermes-adapter/src/main/java/com/jzb/chatbot/hermes/HermesClient.java
  chatbot-hermes-adapter/src/main/java/com/jzb/chatbot/hermes/FakeHermesClient.java
  chatbot-device-gateway/pom.xml
  chatbot-device-gateway/src/main/java/com/jzb/chatbot/device/DeviceChatController.java
  chatbot-device-gateway/src/main/java/com/jzb/chatbot/device/DeviceChatService.java
  chatbot-voice-gateway/pom.xml
  chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiWebSocketConfig.java
  chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiWebSocketHandler.java
  chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiMessageCodec.java
  chatbot-speech-api/pom.xml
  chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/SpeechToTextClient.java
  chatbot-speech-api/src/main/java/com/jzb/chatbot/speech/TextToSpeechClient.java
```

## 任务 1：创建 Maven 多模块骨架

**文件：**
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/pom.xml`
- 创建：各模块 `pom.xml`
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/README.md`

- [ ] **步骤 1：编写父 POM**

父 POM 使用 Java 21、Spring Boot 3、Lombok、JUnit 5，并声明六个模块：

```xml
<modules>
    <module>chatbot-common</module>
    <module>chatbot-hermes-adapter</module>
    <module>chatbot-speech-api</module>
    <module>chatbot-device-gateway</module>
    <module>chatbot-voice-gateway</module>
    <module>chatbot-bootstrap</module>
</modules>
```

- [ ] **步骤 2：创建模块 POM**

模块依赖方向必须保持：

```text
bootstrap -> device-gateway, voice-gateway
device-gateway -> common, hermes-adapter
voice-gateway -> common, hermes-adapter, speech-api
hermes-adapter -> common
speech-api -> common
common -> 无业务模块依赖
```

- [ ] **步骤 3：验证 Maven 结构**

运行：

```bash
cd /Users/jiangzhibin/workspace/chatbot-service-java
mvn -q -DskipTests compile
```

预期：退出码为 0。

## 任务 2：实现 common 标识值对象

**文件：**
- 创建：`chatbot-common/src/main/java/com/jzb/chatbot/common/id/DeviceId.java`
- 创建：`chatbot-common/src/main/java/com/jzb/chatbot/common/id/SessionId.java`
- 创建：`chatbot-common/src/main/java/com/jzb/chatbot/common/id/ConversationId.java`
- 创建：`chatbot-common/src/main/java/com/jzb/chatbot/common/id/VoiceId.java`
- 测试：`chatbot-common/src/test/java/com/jzb/chatbot/common/id/IdentifierTest.java`

- [ ] **步骤 1：编写失败测试**

```java
class IdentifierTest {

    @Test
    void shouldRejectBlankDeviceId() {
        assertThatThrownBy(() -> new DeviceId(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deviceId");
    }

    @Test
    void shouldKeepConversationIdValue() {
        var conversationId = new ConversationId("conv-1");
        assertThat(conversationId.value()).isEqualTo("conv-1");
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
mvn -pl chatbot-common test
```

预期：编译失败或测试失败，原因是值对象尚未创建。

- [ ] **步骤 3：实现 record 值对象**

每个值对象使用 Java `record`，构造器校验空值和空白字符串。

```java
public record DeviceId(String value) {

    public DeviceId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("deviceId must not be blank");
        }
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
mvn -pl chatbot-common test
```

预期：`IdentifierTest` 通过。

## 任务 3：实现 Hermes 适配接口和 Fake 实现

**文件：**
- 创建：`chatbot-hermes-adapter/src/main/java/com/jzb/chatbot/hermes/HermesClient.java`
- 创建：`chatbot-hermes-adapter/src/main/java/com/jzb/chatbot/hermes/HermesRequest.java`
- 创建：`chatbot-hermes-adapter/src/main/java/com/jzb/chatbot/hermes/HermesResponse.java`
- 创建：`chatbot-hermes-adapter/src/main/java/com/jzb/chatbot/hermes/FakeHermesClient.java`
- 测试：`chatbot-hermes-adapter/src/test/java/com/jzb/chatbot/hermes/FakeHermesClientTest.java`

- [ ] **步骤 1：编写失败测试**

```java
class FakeHermesClientTest {

    @Test
    void shouldEchoTextWithConversationId() {
        var client = new FakeHermesClient();
        var response = client.chat(new HermesRequest(
                new DeviceId("device-1"),
                new ConversationId("conv-1"),
                "ping"
        ));

        assertThat(response.conversationId()).isEqualTo(new ConversationId("conv-1"));
        assertThat(response.text()).isEqualTo("pong");
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
mvn -pl chatbot-hermes-adapter -am test
```

预期：缺少 `HermesClient`、`HermesRequest`、`FakeHermesClient`。

- [ ] **步骤 3：实现最小接口**

```java
public interface HermesClient {

    HermesResponse chat(HermesRequest request);
}
```

`FakeHermesClient` 对输入 `ping` 返回 `pong`，其他输入返回原文本。

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
mvn -pl chatbot-hermes-adapter -am test
```

预期：Hermes 适配测试通过。

## 任务 4：实现设备文本 REST 网关

**文件：**
- 创建：`chatbot-device-gateway/src/main/java/com/jzb/chatbot/device/DeviceChatController.java`
- 创建：`chatbot-device-gateway/src/main/java/com/jzb/chatbot/device/DeviceChatService.java`
- 创建：`chatbot-device-gateway/src/main/java/com/jzb/chatbot/device/dto/DeviceChatRequest.java`
- 创建：`chatbot-device-gateway/src/main/java/com/jzb/chatbot/device/dto/DeviceChatResponse.java`
- 测试：`chatbot-device-gateway/src/test/java/com/jzb/chatbot/device/DeviceChatControllerTest.java`

- [ ] **步骤 1：编写失败的 MVC 测试**

```java
@WebMvcTest(DeviceChatController.class)
class DeviceChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeviceChatService chatService;

    @Test
    void shouldReturnChatResponse() throws Exception {
        given(chatService.chat(any())).willReturn(new DeviceChatResponse("conv-1", "pong"));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"device_id":"device-1","conversation_id":"conv-1","message":"ping"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation_id").value("conv-1"))
                .andExpect(jsonPath("$.reply").value("pong"));
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
mvn -pl chatbot-device-gateway -am test
```

预期：缺少 Controller 和 DTO。

- [ ] **步骤 3：实现最小 REST API**

实现 `POST /api/chat`，字段保持与现有固件文本协议兼容：

```json
{
  "device_id": "device-1",
  "conversation_id": "conv-1",
  "message": "ping"
}
```

响应：

```json
{
  "conversation_id": "conv-1",
  "reply": "pong"
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
mvn -pl chatbot-device-gateway -am test
```

预期：REST 测试通过。

## 任务 5：实现小智协议 Codec

**文件：**
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiAudioParams.java`
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiServerHello.java`
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiClientMessage.java`
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiMessageCodec.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/protocol/XiaozhiMessageCodecTest.java`

- [ ] **步骤 1：编写失败测试**

```java
class XiaozhiMessageCodecTest {

    private final XiaozhiMessageCodec codec = new XiaozhiMessageCodec(new ObjectMapper());

    @Test
    void shouldBuildServerHello() throws Exception {
        var json = codec.encodeServerHello("audio-1", "conv-1");

        assertThat(json).contains("\"type\":\"hello\"");
        assertThat(json).contains("\"transport\":\"websocket\"");
        assertThat(json).contains("\"session_id\":\"audio-1\"");
        assertThat(json).contains("\"conversation_id\":\"conv-1\"");
        assertThat(json).contains("\"format\":\"opus\"");
    }

    @Test
    void shouldParseListenStart() throws Exception {
        var message = codec.decodeText("{\"type\":\"listen\",\"state\":\"start\",\"session_id\":\"s1\"}");

        assertThat(message.type()).isEqualTo("listen");
        assertThat(message.state()).isEqualTo("start");
        assertThat(message.sessionId()).isEqualTo("s1");
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
mvn -pl chatbot-voice-gateway -am test
```

预期：协议类不存在。

- [ ] **步骤 3：实现 Codec**

默认音频参数：

```json
{
  "format": "opus",
  "sample_rate": 16000,
  "channels": 1,
  "frame_duration": 60
}
```

JSON 控制帧只做字段解析和基本校验；二进制音频帧在 Handler 层作为 `ByteBuffer` 接收。

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
mvn -pl chatbot-voice-gateway -am test
```

预期：Codec 测试通过。

## 任务 6：实现小智 WebSocket Handler 骨架

**文件：**
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiWebSocketConfig.java`
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiWebSocketHandler.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiWebSocketHandlerTest.java`

- [ ] **步骤 1：编写失败测试**

```java
class XiaozhiWebSocketHandlerTest {

    @Test
    void shouldSendHelloAfterConnectionEstablished() throws Exception {
        var codec = new XiaozhiMessageCodec(new ObjectMapper());
        var handler = new XiaozhiWebSocketHandler(codec);
        var session = new TestWebSocketSession("ws-session-1");

        handler.afterConnectionEstablished(session);

        assertThat(session.getSentMessages()).hasSize(1);
        assertThat(session.getSentMessages().getFirst().getPayload().toString())
                .contains("\"type\":\"hello\"");
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
mvn -pl chatbot-voice-gateway -am test
```

预期：缺少 Handler。

- [ ] **步骤 3：实现最小 Handler**

`afterConnectionEstablished` 生成 `session_id` 和 `conversation_id`，立即发送 server hello。

`handleTextMessage` 对 `listen.start` 返回 ack：

```json
{"type":"ack","ack_type":"listen","ack_state":"start"}
```

`handleBinaryMessage` 第一阶段只记录帧长度，不做 ASR。

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
mvn -pl chatbot-voice-gateway -am test
```

预期：Handler 测试通过。

## 任务 7：聚合启动模块

**文件：**
- 创建：`chatbot-bootstrap/src/main/java/com/jzb/chatbot/bootstrap/ChatbotApplication.java`
- 创建：`chatbot-bootstrap/src/main/resources/application.yml`
- 测试：`chatbot-bootstrap/src/test/java/com/jzb/chatbot/bootstrap/ChatbotApplicationTest.java`

- [ ] **步骤 1：编写失败的上下文测试**

```java
@SpringBootTest
class ChatbotApplicationTest {

    @Test
    void contextLoads() {
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
mvn -pl chatbot-bootstrap -am test
```

预期：缺少启动类或 Bean 装配。

- [ ] **步骤 3：实现启动类和配置**

启动类：

```java
@SpringBootApplication(scanBasePackages = "com.jzb.chatbot")
public class ChatbotApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatbotApplication.class, args);
    }
}
```

`application.yml`：

```yaml
server:
  port: 8092
spring:
  threads:
    virtual:
      enabled: true
chatbot:
  hermes:
    base-url: http://127.0.0.1:8765
  voice:
    default-voice-id: default
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
mvn -pl chatbot-bootstrap -am test
```

预期：Spring 上下文启动成功。

## 任务 8：端到端最小验证

**文件：**
- 修改：`README.md`

- [ ] **步骤 1：运行全量测试**

运行：

```bash
cd /Users/jiangzhibin/workspace/chatbot-service-java
mvn test
```

预期：所有模块测试通过。

- [ ] **步骤 2：启动服务**

运行：

```bash
mvn -pl chatbot-bootstrap spring-boot:run
```

预期：服务监听 `8092`。

- [ ] **步骤 3：验证 REST 文本接口**

运行：

```bash
curl -s -X POST http://127.0.0.1:8092/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"device_id":"device-1","conversation_id":"conv-1","message":"ping"}'
```

预期响应：

```json
{"conversation_id":"conv-1","reply":"pong"}
```

- [ ] **步骤 4：记录 README**

README 必须包含：

- 项目定位：Java 服务端中间件，不放固件。
- 模块说明。
- 本地运行命令。
- REST 测试命令。
- WebSocket 路径 `/xiaozhi/v1`。
- 暂不支持能力清单。

## 自检结果

- 本计划覆盖 Maven 骨架、文本中间件、小智语音中间件、Hermes 适配边界和启动验证。
- 第一阶段不引入数据库、后台、RAG、OTA，符合轻量重写目标。
- 任务均以测试先行，每个模块可以独立验证。
- 固件代码不迁入本仓库，仓库职责清晰。
