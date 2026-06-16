# 小智 OTA 与 MCP 薄桥实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [x]`）语法来跟踪进度。

**目标：** 在 `chatbot-service-java` 内补齐小智固件 OTA/激活配置接口和 MCP 双向薄透传能力，使设备能从 Java 服务端获取 WebSocket 配置、版本信息，并让 Hermes Agent 通过 HTTP JSON-RPC 适配入口发现和调用在线小智设备的 MCP 工具。

**架构：** 保持当前 Java 21 + Spring Boot 3 多模块 thin gateway。OTA/激活放在 `chatbot-device-gateway`，通过 REST 返回小智固件 `Ota::CheckVersion()` 可识别的 JSON；设备 MCP 放在 `chatbot-voice-gateway`，复用 `/xiaozhi/v1` WebSocket 会话做 JSON-RPC payload 转发、会话索引和超时等待；Hermes 适配层只暴露面向 Hermes 的小型 HTTP JSON-RPC 工具入口，把 Hermes 的 `tools/list` / `tools/call` 映射到在线设备，不在 Java 侧重新实现设备工具本身，也不实现完整 MCP stdio/SSE transport。

**技术栈：** Java 21、Spring Boot 3.4、Spring Web、Spring WebSocket、Spring `@Value` Bean 配置、Maven 多模块、JUnit 5、AssertJ、Mockito、Jackson、ConcurrentHashMap、CompletableFuture、AtomicLong。

---

## 范围边界

本计划只修改 `/Users/jiangzhibin/workspace/chatbot-service-java`。不修改 `/Users/jiangzhibin/workspace/xiaozhi-esp32` 固件仓库，不引入 MySQL、Redis、后台管理系统、文件上传后台或设备注册平台。

固件依据：

- OTA 检查请求来自 `/Users/jiangzhibin/workspace/xiaozhi-esp32/main/ota.cc` 的 `Ota::CheckVersion()`。
- 激活请求来自 `/Users/jiangzhibin/workspace/xiaozhi-esp32/main/ota.cc` 的 `Ota::Activate()`，激活 URL 是 OTA URL 后追加 `/activate`。
- WebSocket MCP 入站解析来自 `/Users/jiangzhibin/workspace/xiaozhi-esp32/main/application.cc` 对 `type=mcp` 的处理。
- 设备 MCP 出站格式来自 `/Users/jiangzhibin/workspace/xiaozhi-esp32/main/protocols/protocol.cc` 的 `Protocol::SendMcpMessage()`。
- 设备 MCP 请求来自 `/Users/jiangzhibin/workspace/xiaozhi-esp32/main/mcp_server.cc`，支持 `initialize`、`tools/list`、`tools/call`，且 `id` 必须是数字。

本计划要补齐：

- OTA check REST 接口，返回 `websocket`、`firmware`、`server_time`、按需 `activation`。
- 激活 REST 接口，支持 challenge 生成、设备绑定、TTL 校验和 200/202 状态语义；首版只校验 challenge、设备身份与 TTL，不验证 ESP HMAC。
- 固件二进制安全下发，限定目录读取，禁止任意路径。
- OTA 与 WebSocket token 的脱敏、允许设备策略和启动期安全检查。
- MCP WebSocket 会话注册表，按 `Device-Id` 定位在线设备。
- MCP fire-and-forget 下发和 JSON-RPC request/response 等待。
- MCP 入站 response 与 notification 处理。
- Hermes 面向的 HTTP JSON-RPC 适配入口，支持 `initialize`、`tools/list`、`tools/call`。
- Hermes 可调用的稳定工具：列出在线设备、列出指定设备工具、调用指定设备工具。
- 联调脚本、README 和现有任务清单同步。

本计划不包含：

- Java 侧重新实现设备业务工具。
- 完整 MCP stdio/SSE transport 或 MCP Inspector 可直接连接的 MCP Server。
- 生产级 ESP HMAC 激活校验。
- 设备工具 schema 的长期持久化索引。
- 动态为每台设备生成独立 Hermes 工具清单。
- OTA 包上传页面。
- 多租户设备平台。
- Git commit、push 或分支操作。

## 推荐设计

推荐采用“OTA REST + MCP WebSocket 薄桥”方案。

对比方案：

- 方案 A：只写文档，继续手工配置固件。实现成本最低，但无法解决 OTA/激活接口缺口，也无法从 Hermes 调设备工具。
- 方案 B：引入完整设备平台。能力最全，但会引入数据库、后台、权限系统和部署复杂度，偏离当前 thin gateway 定位。
- 方案 C：在现有模块内做薄实现。OTA 只返回固件需要的配置和版本信息，设备 MCP 只转发 JSON-RPC payload，Hermes HTTP JSON-RPC 适配入口只暴露 3 个稳定网关工具，最符合当前仓库边界。

本计划采用方案 C。

## 文件结构

### OTA / 激活配置

- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-device-gateway/src/main/java/com/jzb/chatbot/device/ota/XiaozhiOtaController.java`
  - 职责：暴露 OTA check、activate 和固件下载 REST 入口。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-device-gateway/src/main/java/com/jzb/chatbot/device/ota/XiaozhiOtaService.java`
  - 职责：按设备身份构造固件可识别的 OTA 响应。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-device-gateway/src/main/java/com/jzb/chatbot/device/ota/XiaozhiOtaProperties.java`
  - 职责：承载 OTA、WebSocket 配置和安全策略。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-device-gateway/src/main/java/com/jzb/chatbot/device/ota/XiaozhiOtaBeans.java`
  - 职责：按当前项目的 `@Value` Bean 风格绑定 `chatbot.ota.*` 配置，创建 `XiaozhiOtaProperties` Bean。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-device-gateway/src/main/java/com/jzb/chatbot/device/ota/OtaDeviceIdentity.java`
  - 职责：封装 `Device-Id`、`Client-Id`、`Serial-Number`、`Activation-Version`、`User-Agent`。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-device-gateway/src/main/java/com/jzb/chatbot/device/ota/XiaozhiActivationStore.java`
  - 职责：激活 challenge 存储接口。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-device-gateway/src/main/java/com/jzb/chatbot/device/ota/InMemoryXiaozhiActivationStore.java`
  - 职责：基于内存和 TTL 的首版激活状态存储。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-device-gateway/src/main/java/com/jzb/chatbot/device/ota/XiaozhiOtaResponseFactory.java`
  - 职责：用 Jackson 构造固件需要的 JSON 字段，避免 controller 拼 Map。
- 测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-device-gateway/src/test/java/com/jzb/chatbot/device/ota/XiaozhiOtaControllerTest.java`
- 测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-device-gateway/src/test/java/com/jzb/chatbot/device/ota/XiaozhiOtaServiceTest.java`

### MCP 薄桥

- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpBridge.java`
  - 职责：维护在线 WebSocket 会话、发送 MCP payload、处理 JSON-RPC response。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpGatewayToolService.java`
  - 职责：把 Hermes 的稳定工具调用映射为设备 MCP `tools/list` 和 `tools/call`。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpJsonRpcController.java`
  - 职责：暴露 Hermes 可接入的 HTTP JSON-RPC 适配入口。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpJsonRpcService.java`
  - 职责：处理 `initialize`、`tools/list`、`tools/call`，返回 Hermes 可消费的 JSON-RPC。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpController.java`
  - 职责：暴露运维侧可调用的 MCP REST 入口。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpAdminAuth.java`
  - 职责：校验 MCP 管理 token 和 Hermes token，避免任意公网调用设备工具。
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpPendingRequest.java`
  - 职责：记录等待 response 的 JSON-RPC 请求。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
  - 职责：连接建立后注册设备会话，关闭时注销，收到 `type=mcp` 时交给 bridge。
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiServerEventFactory.java`
  - 职责：新增 `mcp(sessionId, payload)`，生成服务端下发的 `type=mcp` 控制帧。
- 测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpBridgeTest.java`
- 测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpControllerTest.java`
- 测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpJsonRpcControllerTest.java`
- 测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpGatewayToolServiceTest.java`
- 测试：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`

### 配置、脚本和文档

- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-bootstrap/src/main/resources/application.yml`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/deploy/chatbot-service.env.example`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/README.md`
- 修改：`/Users/jiangzhibin/workspace/chatbot-service-java/docs/superpowers/plans/2026-06-16-xiaozhi-firmware-backend-task-checklist.md`
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/scripts/xiaozhi_ota_smoke.py`
- 创建：`/Users/jiangzhibin/workspace/chatbot-service-java/scripts/xiaozhi_mcp_smoke.py`

## 任务 1：OTA 配置模型与响应工厂

**文件：**
- 创建：`chatbot-device-gateway/src/main/java/com/jzb/chatbot/device/ota/XiaozhiOtaProperties.java`
- 创建：`chatbot-device-gateway/src/main/java/com/jzb/chatbot/device/ota/XiaozhiOtaBeans.java`
- 创建：`chatbot-device-gateway/src/main/java/com/jzb/chatbot/device/ota/OtaDeviceIdentity.java`
- 创建：`chatbot-device-gateway/src/main/java/com/jzb/chatbot/device/ota/XiaozhiOtaResponseFactory.java`
- 测试：`chatbot-device-gateway/src/test/java/com/jzb/chatbot/device/ota/XiaozhiOtaServiceTest.java`

- [x] **步骤 1：编写 OTA 响应 JSON 失败测试**

在 `XiaozhiOtaServiceTest` 中先写响应字段测试：

```java
@Test
void shouldBuildOtaResponseWithWebsocketServerTimeAndEmptyFirmwareWhenNoUpgrade() {
    var objectMapper = new ObjectMapper();
    var properties = XiaozhiOtaProperties.defaults()
            .withWebsocketUrl("ws://203.195.202.54:8766/xiaozhi/v1")
            .withWebsocketToken("device-token")
            .withWebsocketVersion(3);
    var factory = new XiaozhiOtaResponseFactory(objectMapper);

    var response = factory.checkResponse(
            new OtaDeviceIdentity("device-1", "client-1", "", "1", "xiaozhi/1.0.0"),
            properties,
            false,
            null
    );

    assertThat(response.path("websocket").path("url").asText())
            .isEqualTo("ws://203.195.202.54:8766/xiaozhi/v1");
    assertThat(response.path("websocket").path("token").asText()).isEqualTo("device-token");
    assertThat(response.path("websocket").path("version").asInt()).isEqualTo(3);
    assertThat(response.path("server_time").path("timestamp").isNumber()).isTrue();
    assertThat(response.has("activation")).isFalse();
    assertThat(response.path("firmware").path("version").asText()).isEmpty();
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
"/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn" -pl chatbot-device-gateway -am -Dtest=XiaozhiOtaServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：FAIL，编译报错包含 `XiaozhiOtaProperties` 或 `XiaozhiOtaResponseFactory` 不存在。

- [x] **步骤 3：实现配置 record**

创建 `XiaozhiOtaProperties`，先使用不可变 record，避免手写 getter：

```java
public record XiaozhiOtaProperties(
        boolean enabled,
        String websocketUrl,
        String websocketToken,
        int websocketVersion,
        String firmwareVersion,
        String firmwareUrl,
        boolean firmwareForce,
        Path firmwareDirectory,
        boolean activationRequired,
        String activationMessage,
        Duration activationTtl,
        List<String> allowedDeviceIds,
        List<String> allowedSerialNumbers
) {
    public XiaozhiOtaProperties {
        if (websocketUrl == null) {
            websocketUrl = "";
        }
        if (websocketToken == null) {
            websocketToken = "";
        }
        if (websocketVersion <= 0) {
            websocketVersion = 1;
        }
        if (firmwareVersion == null) {
            firmwareVersion = "";
        }
        if (firmwareUrl == null) {
            firmwareUrl = "";
        }
        if (firmwareDirectory == null) {
            firmwareDirectory = Path.of("/app/firmware");
        }
        if (activationMessage == null || activationMessage.isBlank()) {
            activationMessage = "请在服务端完成设备激活";
        }
        if (activationTtl == null || activationTtl.isNegative() || activationTtl.isZero()) {
            activationTtl = Duration.ofSeconds(30);
        }
        allowedDeviceIds = allowedDeviceIds == null ? List.of() : List.copyOf(allowedDeviceIds);
        allowedSerialNumbers = allowedSerialNumbers == null ? List.of() : List.copyOf(allowedSerialNumbers);
    }

    public static XiaozhiOtaProperties defaults() {
        return new XiaozhiOtaProperties(
                true,
                "",
                "",
                1,
                "",
                "",
                false,
                Path.of("/app/firmware"),
                false,
                "请在服务端完成设备激活",
                Duration.ofSeconds(30),
                List.of(),
                List.of()
        );
    }

    public XiaozhiOtaProperties withWebsocketUrl(String value) {
        return new XiaozhiOtaProperties(
                enabled, value, websocketToken, websocketVersion,
                firmwareVersion, firmwareUrl, firmwareForce, firmwareDirectory,
                activationRequired, activationMessage, activationTtl,
                allowedDeviceIds, allowedSerialNumbers
        );
    }

    public XiaozhiOtaProperties withWebsocketToken(String value) {
        return new XiaozhiOtaProperties(
                enabled, websocketUrl, value, websocketVersion,
                firmwareVersion, firmwareUrl, firmwareForce, firmwareDirectory,
                activationRequired, activationMessage, activationTtl,
                allowedDeviceIds, allowedSerialNumbers
        );
    }

    public XiaozhiOtaProperties withWebsocketVersion(int value) {
        return new XiaozhiOtaProperties(
                enabled, websocketUrl, websocketToken, value,
                firmwareVersion, firmwareUrl, firmwareForce, firmwareDirectory,
                activationRequired, activationMessage, activationTtl,
                allowedDeviceIds, allowedSerialNumbers
        );
    }

    public XiaozhiOtaProperties withFirmwareVersion(String value) {
        return new XiaozhiOtaProperties(
                enabled, websocketUrl, websocketToken, websocketVersion,
                value, firmwareUrl, firmwareForce, firmwareDirectory,
                activationRequired, activationMessage, activationTtl,
                allowedDeviceIds, allowedSerialNumbers
        );
    }

    public XiaozhiOtaProperties withFirmwareUrl(String value) {
        return new XiaozhiOtaProperties(
                enabled, websocketUrl, websocketToken, websocketVersion,
                firmwareVersion, value, firmwareForce, firmwareDirectory,
                activationRequired, activationMessage, activationTtl,
                allowedDeviceIds, allowedSerialNumbers
        );
    }

    public XiaozhiOtaProperties withAllowedDeviceIds(List<String> values) {
        return new XiaozhiOtaProperties(
                enabled, websocketUrl, websocketToken, websocketVersion,
                firmwareVersion, firmwareUrl, firmwareForce, firmwareDirectory,
                activationRequired, activationMessage, activationTtl,
                values, allowedSerialNumbers
        );
    }

    public XiaozhiOtaProperties withAllowedSerialNumbers(List<String> values) {
        return new XiaozhiOtaProperties(
                enabled, websocketUrl, websocketToken, websocketVersion,
                firmwareVersion, firmwareUrl, firmwareForce, firmwareDirectory,
                activationRequired, activationMessage, activationTtl,
                allowedDeviceIds, values
        );
    }

    public boolean exposesWebsocketTokenWithoutAllowlist() {
        return !websocketToken.isBlank() && allowedDeviceIds.isEmpty() && allowedSerialNumbers.isEmpty();
    }
}
```

- [x] **步骤 4：实现设备身份 record**

创建 `OtaDeviceIdentity`：

```java
public record OtaDeviceIdentity(
        String deviceId,
        String clientId,
        String serialNumber,
        String activationVersion,
        String userAgent
) {
    public OtaDeviceIdentity {
        deviceId = blankToEmpty(deviceId);
        clientId = blankToEmpty(clientId);
        serialNumber = blankToEmpty(serialNumber);
        activationVersion = blankToEmpty(activationVersion);
        userAgent = blankToEmpty(userAgent);
    }

    public boolean hasStableIdentity() {
        return !deviceId.isBlank() || !serialNumber.isBlank();
    }

    private static String blankToEmpty(String value) {
        return value == null || value.isBlank() ? "" : value;
    }
}
```

- [x] **步骤 5：实现 OTA 配置 Bean**

创建 `XiaozhiOtaBeans`：

```java
@Configuration
public class XiaozhiOtaBeans {

    @Bean
    XiaozhiOtaProperties xiaozhiOtaProperties(
            @Value("${chatbot.ota.enabled:true}") boolean enabled,
            @Value("${chatbot.ota.websocket.url:}") String websocketUrl,
            @Value("${chatbot.ota.websocket.token:}") String websocketToken,
            @Value("${chatbot.ota.websocket.version:1}") int websocketVersion,
            @Value("${chatbot.ota.firmware.version:}") String firmwareVersion,
            @Value("${chatbot.ota.firmware.url:}") String firmwareUrl,
            @Value("${chatbot.ota.firmware.force:false}") boolean firmwareForce,
            @Value("${chatbot.ota.firmware.directory:/app/firmware}") Path firmwareDirectory,
            @Value("${chatbot.ota.activation.required:false}") boolean activationRequired,
            @Value("${chatbot.ota.activation.message:请在服务端完成设备激活}") String activationMessage,
            @Value("${chatbot.ota.activation.ttl-seconds:30}") long activationTtlSeconds,
            @Value("${chatbot.ota.security.allowed-device-ids:}") String allowedDeviceIds,
            @Value("${chatbot.ota.security.allowed-serial-numbers:}") String allowedSerialNumbers
    ) {
        var properties = new XiaozhiOtaProperties(
                enabled,
                websocketUrl,
                websocketToken,
                websocketVersion,
                firmwareVersion,
                firmwareUrl,
                firmwareForce,
                firmwareDirectory,
                activationRequired,
                activationMessage,
                Duration.ofSeconds(activationTtlSeconds),
                csv(allowedDeviceIds),
                csv(allowedSerialNumbers)
        );
        if (properties.exposesWebsocketTokenWithoutAllowlist()) {
            throw new IllegalStateException("Xiaozhi OTA websocket token requires device or serial allowlist");
        }
        return properties;
    }

    private List<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }
}
```

- [x] **步骤 6：实现响应工厂**

创建 `XiaozhiOtaResponseFactory`：

```java
@Component
@RequiredArgsConstructor
public class XiaozhiOtaResponseFactory {

    private final ObjectMapper objectMapper;

    public ObjectNode checkResponse(
            OtaDeviceIdentity identity,
            XiaozhiOtaProperties properties,
            boolean upgradeAvailable,
            String activationChallenge
    ) {
        var root = objectMapper.createObjectNode();
        root.set("server_time", serverTime());
        root.set("websocket", websocket(properties));
        root.set("firmware", firmware(properties, upgradeAvailable));
        if (activationChallenge != null && !activationChallenge.isBlank()) {
            root.set("activation", activation(properties, activationChallenge));
        }
        return root;
    }

    public ObjectNode disabledResponse() {
        var root = objectMapper.createObjectNode();
        root.set("server_time", serverTime());
        root.set("websocket", objectMapper.createObjectNode());
        root.set("firmware", objectMapper.createObjectNode()
                .put("version", "")
                .put("url", ""));
        return root;
    }

    private ObjectNode websocket(XiaozhiOtaProperties properties) {
        var websocket = objectMapper.createObjectNode();
        websocket.put("url", properties.websocketUrl());
        websocket.put("token", properties.websocketToken());
        websocket.put("version", properties.websocketVersion());
        return websocket;
    }

    private ObjectNode firmware(XiaozhiOtaProperties properties, boolean upgradeAvailable) {
        var firmware = objectMapper.createObjectNode();
        firmware.put("version", upgradeAvailable ? properties.firmwareVersion() : "");
        firmware.put("url", upgradeAvailable ? properties.firmwareUrl() : "");
        if (properties.firmwareForce()) {
            firmware.put("force", 1);
        }
        return firmware;
    }

    private ObjectNode activation(XiaozhiOtaProperties properties, String challenge) {
        var activation = objectMapper.createObjectNode();
        activation.put("message", properties.activationMessage());
        activation.put("challenge", challenge);
        activation.put("timeout_ms", properties.activationTtl().toMillis());
        return activation;
    }

    private ObjectNode serverTime() {
        var serverTime = objectMapper.createObjectNode();
        serverTime.put("timestamp", Instant.now().toEpochMilli());
        serverTime.put("timezone_offset", 480);
        return serverTime;
    }
}
```

- [x] **步骤 7：运行测试验证通过**

运行：

```bash
"/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn" -pl chatbot-device-gateway -am -Dtest=XiaozhiOtaServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：PASS。

## 任务 2：OTA check 与 activation REST 接口

**文件：**
- 创建：`chatbot-device-gateway/src/main/java/com/jzb/chatbot/device/ota/XiaozhiOtaController.java`
- 创建：`chatbot-device-gateway/src/main/java/com/jzb/chatbot/device/ota/XiaozhiOtaService.java`
- 创建：`chatbot-device-gateway/src/main/java/com/jzb/chatbot/device/ota/XiaozhiActivationStore.java`
- 创建：`chatbot-device-gateway/src/main/java/com/jzb/chatbot/device/ota/InMemoryXiaozhiActivationStore.java`
- 测试：`chatbot-device-gateway/src/test/java/com/jzb/chatbot/device/ota/XiaozhiOtaControllerTest.java`
- 测试：`chatbot-device-gateway/src/test/java/com/jzb/chatbot/device/ota/XiaozhiOtaServiceTest.java`

- [x] **步骤 1：编写 check 接口失败测试**

创建 `XiaozhiOtaControllerTest`：

```java
@WebMvcTest(XiaozhiOtaController.class)
class XiaozhiOtaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private XiaozhiOtaService otaService;

    @Test
    void shouldReturnOtaCheckResponse() throws Exception {
        given(otaService.check(any(), any())).willReturn(new ObjectMapper().readTree("""
                {
                  "server_time": {"timestamp": 1760000000000, "timezone_offset": 480},
                  "websocket": {"url": "ws://203.195.202.54:8766/xiaozhi/v1", "token": "device-token", "version": 3},
                  "firmware": {"version": "", "url": ""}
                }
                """));

        mockMvc.perform(post("/api/ota/check")
                        .header("Device-Id", "device-1")
                        .header("Client-Id", "client-1")
                        .header("Activation-Version", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.websocket.url").value("ws://203.195.202.54:8766/xiaozhi/v1"))
                .andExpect(jsonPath("$.websocket.version").value(3))
                .andExpect(jsonPath("$.server_time.timestamp").isNumber());
    }
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
"/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn" -pl chatbot-device-gateway -am -Dtest=XiaozhiOtaControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：FAIL，编译报错包含 `XiaozhiOtaController` 不存在。

- [x] **步骤 3：实现 Controller**

创建 `XiaozhiOtaController`：

```java
@RestController
@RequiredArgsConstructor
public class XiaozhiOtaController {

    private final XiaozhiOtaService otaService;

    @PostMapping("/api/ota/check")
    public JsonNode check(
            @RequestHeader(value = "Device-Id", defaultValue = "") String deviceId,
            @RequestHeader(value = "Client-Id", defaultValue = "") String clientId,
            @RequestHeader(value = "Serial-Number", defaultValue = "") String serialNumber,
            @RequestHeader(value = "Activation-Version", defaultValue = "1") String activationVersion,
            @RequestHeader(value = "User-Agent", defaultValue = "") String userAgent,
            @RequestBody(required = false) JsonNode body
    ) {
        var identity = new OtaDeviceIdentity(deviceId, clientId, serialNumber, activationVersion, userAgent);
        return otaService.check(identity, body);
    }

    @PostMapping("/api/ota/check/activate")
    public ResponseEntity<?> activate(
            @RequestHeader(value = "Device-Id", defaultValue = "") String deviceId,
            @RequestHeader(value = "Client-Id", defaultValue = "") String clientId,
            @RequestHeader(value = "Serial-Number", defaultValue = "") String serialNumber,
            @RequestBody JsonNode payload
    ) {
        var identity = new OtaDeviceIdentity(deviceId, clientId, serialNumber, "", "");
        var status = otaService.activate(identity, payload);
        if (status == XiaozhiOtaService.ActivationStatus.PENDING) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "pending"));
        }
        if (status == XiaozhiOtaService.ActivationStatus.REJECTED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "activation rejected"));
        }
        return ResponseEntity.ok(Map.of("status", "activated"));
    }
}
```

- [x] **步骤 4：实现激活存储接口**

创建 `XiaozhiActivationStore`：

```java
public interface XiaozhiActivationStore {

    String createChallenge(OtaDeviceIdentity identity, Duration ttl);

    boolean activate(OtaDeviceIdentity identity, String challenge);

    boolean isActivated(OtaDeviceIdentity identity);

    void remove(OtaDeviceIdentity identity);
}
```

创建 `InMemoryXiaozhiActivationStore`：

```java
@Component
public class InMemoryXiaozhiActivationStore implements XiaozhiActivationStore {

    private final Map<String, ActivationRecord> records = new ConcurrentHashMap<>();

    @Override
    public String createChallenge(OtaDeviceIdentity identity, Duration ttl) {
        var challenge = UUID.randomUUID().toString();
        records.put(key(identity), new ActivationRecord(challenge, Instant.now().plus(ttl), false));
        return challenge;
    }

    @Override
    public boolean activate(OtaDeviceIdentity identity, String challenge) {
        var record = records.get(key(identity));
        if (record == null || record.expiresAt().isBefore(Instant.now())) {
            return false;
        }
        if (!record.challenge().equals(challenge)) {
            return false;
        }
        records.put(key(identity), new ActivationRecord(challenge, record.expiresAt(), true));
        return true;
    }

    @Override
    public boolean isActivated(OtaDeviceIdentity identity) {
        var record = records.get(key(identity));
        return record != null && record.activated();
    }

    @Override
    public void remove(OtaDeviceIdentity identity) {
        records.remove(key(identity));
    }

    private String key(OtaDeviceIdentity identity) {
        if (!identity.serialNumber().isBlank()) {
            return "serial:" + identity.serialNumber();
        }
        return "device:" + identity.deviceId();
    }

    private record ActivationRecord(String challenge, Instant expiresAt, boolean activated) {
    }
}
```

- [x] **步骤 5：实现 OTA Service**

创建 `XiaozhiOtaService`：

```java
@Service
@RequiredArgsConstructor
public class XiaozhiOtaService {

    public enum ActivationStatus {
        ACTIVATED,
        PENDING,
        REJECTED
    }

    private final XiaozhiOtaProperties properties;
    private final XiaozhiActivationStore activationStore;
    private final XiaozhiOtaResponseFactory responseFactory;

    public JsonNode check(OtaDeviceIdentity identity, JsonNode body) {
        if (!properties.enabled()) {
            return responseFactory.disabledResponse();
        }
        var allowed = allowed(identity);
        var safeProperties = allowed
                ? properties
                : properties.withWebsocketToken("").withFirmwareUrl("").withFirmwareVersion("");
        var challenge = allowed ? activationChallenge(identity) : "";
        var upgradeAvailable = !safeProperties.firmwareVersion().isBlank() && !safeProperties.firmwareUrl().isBlank();
        return responseFactory.checkResponse(identity, safeProperties, upgradeAvailable, challenge);
    }

    public ActivationStatus activate(OtaDeviceIdentity identity, JsonNode payload) {
        if (!properties.activationRequired()) {
            return ActivationStatus.ACTIVATED;
        }
        var challenge = payload.path("challenge").asText("");
        if (challenge.isBlank()) {
            return ActivationStatus.PENDING;
        }
        return activationStore.activate(identity, challenge)
                ? ActivationStatus.ACTIVATED
                : ActivationStatus.REJECTED;
    }

    private boolean allowed(OtaDeviceIdentity identity) {
        var deviceIds = properties.allowedDeviceIds();
        var serialNumbers = properties.allowedSerialNumbers();
        if (deviceIds.isEmpty() && serialNumbers.isEmpty()) {
            return true;
        }
        return deviceIds.contains(identity.deviceId()) || serialNumbers.contains(identity.serialNumber());
    }

    private String activationChallenge(OtaDeviceIdentity identity) {
        if (!properties.activationRequired()) {
            return "";
        }
        if (!allowed(identity)) {
            return "";
        }
        if (activationStore.isActivated(identity)) {
            return "";
        }
        return activationStore.createChallenge(identity, properties.activationTtl());
    }
}
```

首版激活只验证服务端签发的 challenge 是否属于当前设备身份并在 TTL 内；固件上传的 `algorithm`、`serial_number`、`hmac` 字段先保留透传读取能力，不做 ESP HMAC 校验。真实量产激活需要独立补充 efuse/HMAC 密钥管理方案，不能在本计划内声称已完成。

- [x] **步骤 6：增加激活成功和拒绝测试**

在 `XiaozhiOtaControllerTest` 增加：

```java
@Test
void shouldReturnAcceptedWhenActivationIsPending() throws Exception {
    given(otaService.activate(any(), any())).willReturn(XiaozhiOtaService.ActivationStatus.PENDING);

    mockMvc.perform(post("/api/ota/check/activate")
                    .header("Device-Id", "device-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("pending"));
}

@Test
void shouldReturnForbiddenWhenActivationIsRejected() throws Exception {
    given(otaService.activate(any(), any())).willReturn(XiaozhiOtaService.ActivationStatus.REJECTED);

    mockMvc.perform(post("/api/ota/check/activate")
                    .header("Device-Id", "device-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"challenge\":\"bad\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("activation rejected"));
}
```

- [x] **步骤 7：运行 OTA 接口测试**

运行：

```bash
"/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn" -pl chatbot-device-gateway -am -Dtest=XiaozhiOtaControllerTest,XiaozhiOtaServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：PASS。

## 任务 3：OTA 安全策略与固件文件下发

**文件：**
- 修改：`chatbot-device-gateway/src/main/java/com/jzb/chatbot/device/ota/XiaozhiOtaProperties.java`
- 修改：`chatbot-device-gateway/src/main/java/com/jzb/chatbot/device/ota/XiaozhiOtaService.java`
- 修改：`chatbot-device-gateway/src/main/java/com/jzb/chatbot/device/ota/XiaozhiOtaController.java`
- 测试：`chatbot-device-gateway/src/test/java/com/jzb/chatbot/device/ota/XiaozhiOtaControllerTest.java`
- 测试：`chatbot-device-gateway/src/test/java/com/jzb/chatbot/device/ota/XiaozhiOtaServiceTest.java`

- [x] **步骤 1：补充设备 allowlist 回归测试**

在 `XiaozhiOtaServiceTest` 增加：

```java
@Test
void shouldNotReturnWebsocketTokenWhenDeviceIsNotAllowed() {
    var properties = XiaozhiOtaProperties.defaults()
            .withWebsocketUrl("ws://203.195.202.54:8766/xiaozhi/v1")
            .withWebsocketToken("secret-token")
            .withAllowedDeviceIds(List.of("allowed-device"));
    var service = new XiaozhiOtaService(
            properties,
            new InMemoryXiaozhiActivationStore(),
            new XiaozhiOtaResponseFactory(new ObjectMapper())
    );

    var response = service.check(new OtaDeviceIdentity("other-device", "client-1", "", "1", ""), NullNode.instance);

    assertThat(response.path("websocket").path("token").asText()).isEmpty();
    assertThat(response.path("firmware").path("url").asText()).isEmpty();
}
```

- [x] **步骤 2：运行测试验证 allowlist 通过**

运行：

```bash
"/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn" -pl chatbot-device-gateway -am -Dtest=XiaozhiOtaServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：PASS，未在 allowlist 内的设备不会收到 `websocket.token` 或固件下载 URL。

- [x] **步骤 3：确认设备访问策略只保留一份实现**

确认 `XiaozhiOtaService` 的 `allowed` 方法已在任务 2 实现；本步骤只把测试覆盖到 allowlist 分支，不重复添加第二个 `check` 方法。

```java
assertThat(service.check(disallowedIdentity, NullNode.instance)
        .path("websocket").path("token").asText()).isEmpty();
```

- [x] **步骤 4：编写固件文件路径安全测试**

在 `XiaozhiOtaControllerTest` 增加：

```java
@Test
void shouldRejectFirmwarePathTraversal() throws Exception {
    mockMvc.perform(get("/api/ota/firmware/../secret.bin"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid firmware path"));
}
```

- [x] **步骤 5：实现固件文件下载接口**

在 `XiaozhiOtaController` 增加：

```java
@GetMapping("/api/ota/firmware/{fileName:.+}")
public ResponseEntity<?> firmware(@PathVariable String fileName) {
    return otaService.firmware(fileName)
            .<ResponseEntity<?>>map(resource -> ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(resource))
            .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "firmware not found")));
}
```

在 `XiaozhiOtaService` 增加：

```java
public Optional<Resource> firmware(String fileName) {
    if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
        throw new InvalidDeviceChatRequestException(HttpStatus.BAD_REQUEST, "invalid firmware path");
    }
    var target = properties.firmwareDirectory().resolve(fileName).normalize();
    var root = properties.firmwareDirectory().normalize();
    if (!target.startsWith(root) || !Files.isRegularFile(target)) {
        return Optional.empty();
    }
    return Optional.of(new FileSystemResource(target));
}
```

- [x] **步骤 6：运行 OTA 安全测试**

运行：

```bash
"/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn" -pl chatbot-device-gateway -am -Dtest=XiaozhiOtaControllerTest,XiaozhiOtaServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：PASS。

## 任务 4：MCP 服务端事件工厂与 WebSocket 会话注册

**文件：**
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/protocol/XiaozhiServerEventFactory.java`
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpBridge.java`
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/protocol/XiaozhiServerEventFactoryTest.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpBridgeTest.java`

- [x] **步骤 1：编写 MCP 下行事件失败测试**

在 `XiaozhiServerEventFactoryTest` 增加：

```java
@Test
void shouldBuildMcpEvent() throws Exception {
    var payload = new ObjectMapper().readTree("""
            {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"withUserTools":true}}
            """);

    var json = factory.mcp("s1", payload);

    assertThat(json).contains("\"session_id\":\"s1\"");
    assertThat(json).contains("\"type\":\"mcp\"");
    assertThat(json).contains("\"method\":\"tools/list\"");
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
"/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn" -pl chatbot-voice-gateway -am -Dtest=XiaozhiServerEventFactoryTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：FAIL，编译报错包含 `mcp` 方法不存在。

- [x] **步骤 3：实现 MCP 事件工厂**

在 `XiaozhiServerEventFactory` 增加：

```java
public String mcp(String sessionId, JsonNode payload) {
    var root = objectMapper.createObjectNode()
            .put("session_id", sessionId)
            .put("type", "mcp");
    root.set("payload", payload == null ? objectMapper.createObjectNode() : payload);
    return root.toString();
}
```

- [x] **步骤 4：编写 Bridge 注册和下发测试**

创建 `XiaozhiMcpBridgeTest`：

```java
class XiaozhiMcpBridgeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final XiaozhiServerEventFactory eventFactory = new XiaozhiServerEventFactory(objectMapper);
    private final XiaozhiMcpBridge bridge = new XiaozhiMcpBridge(eventFactory);

    @Test
    void shouldSendMcpPayloadToOnlineDevice() throws Exception {
        var session = new TestWebSocketSession("ws-session-1");
        bridge.register("device-1", "ws-session-1", session);

        bridge.send("device-1", objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                """));

        assertThat(session.getSentMessages())
                .singleElement()
                .satisfies(message -> assertThat(message.getPayload().toString())
                        .contains("\"type\":\"mcp\"", "\"method\":\"tools/list\""));
    }

    @Test
    void shouldReturnFalseWhenDeviceIsOffline() throws Exception {
        var sent = bridge.send("offline-device", objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                """));

        assertThat(sent).isFalse();
    }
}
```

- [x] **步骤 5：实现 MCP Bridge 注册和下发**

创建 `XiaozhiMcpBridge`：

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class XiaozhiMcpBridge {

    private final XiaozhiServerEventFactory eventFactory;
    private final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();

    public void register(String deviceId, String sessionId, WebSocketSession session) {
        sessions.put(deviceId, new DeviceSession(deviceId, sessionId, session));
    }

    public void unregister(String deviceId, String sessionId) {
        sessions.computeIfPresent(deviceId, (key, current) ->
                current.sessionId().equals(sessionId) ? null : current);
        cancelPending(deviceId, new IllegalStateException("device disconnected"));
    }

    public boolean send(String deviceId, JsonNode payload) {
        var deviceSession = sessions.get(deviceId);
        if (deviceSession == null || !deviceSession.session().isOpen()) {
            return false;
        }
        try {
            deviceSession.session().sendMessage(new TextMessage(eventFactory.mcp(deviceSession.sessionId(), payload)));
            return true;
        } catch (IOException exception) {
            log.warn("xiaozhi mcp send failed, deviceId={}, sessionId={}, message={}",
                    deviceId, deviceSession.sessionId(), exception.getMessage(), exception);
            return false;
        }
    }

    public List<String> onlineDeviceIds() {
        return sessions.values().stream()
                .filter(deviceSession -> deviceSession.session().isOpen())
                .map(DeviceSession::deviceId)
                .sorted()
                .toList();
    }

    private void cancelPending(String deviceId, RuntimeException reason) {
        // 任务 5 替换为真实 pending request 取消逻辑。
    }

    private record DeviceSession(String deviceId, String sessionId, WebSocketSession session) {
    }
}
```

- [x] **步骤 6：接入 WebSocket 会话生命周期**

修改 `XiaozhiVoiceSessionService` 构造参数，新增 `XiaozhiMcpBridge mcpBridge`。在 `open` 成功后注册：

```java
mcpBridge.register(voiceSession.deviceId(), session.getId(), session);
```

在 `close` 中注销：

```java
var voiceSession = sessions.remove(session.getId());
if (voiceSession != null) {
    mcpBridge.unregister(voiceSession.deviceId(), session.getId());
}
```

- [x] **步骤 7：运行 MCP Bridge 测试**

运行：

```bash
"/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn" -pl chatbot-voice-gateway -am -Dtest=XiaozhiServerEventFactoryTest,XiaozhiMcpBridgeTest,XiaozhiVoiceSessionServiceTest,XiaozhiWebSocketHandlerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：PASS。

## 任务 5：MCP JSON-RPC request/response 等待

**文件：**
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpPendingRequest.java`
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpBridge.java`
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionService.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpBridgeTest.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceSessionServiceTest.java`

- [x] **步骤 1：编写 response 关联失败测试**

在 `XiaozhiMcpBridgeTest` 增加：

```java
@Test
void shouldCompletePendingRequestWhenDeviceRespondsWithSameId() throws Exception {
    var session = new TestWebSocketSession("ws-session-1");
    bridge.register("device-1", "ws-session-1", session);
    var future = bridge.call("device-1", objectMapper.readTree("""
            {"jsonrpc":"2.0","id":7,"method":"tools/list"}
            """), Duration.ofSeconds(1));

    bridge.handleInbound("device-1", objectMapper.readTree("""
            {"jsonrpc":"2.0","id":7,"result":{"tools":[]}}
            """));

    assertThat(future).succeedsWithin(Duration.ofMillis(100))
            .satisfies(json -> assertThat(json.path("result").path("tools").isArray()).isTrue());
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
"/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn" -pl chatbot-voice-gateway -am -Dtest=XiaozhiMcpBridgeTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：FAIL，缺少 `call` 或 `handleInbound`。

- [x] **步骤 3：实现 pending request record**

创建 `XiaozhiMcpPendingRequest`：

```java
public record XiaozhiMcpPendingRequest(
        String deviceId,
        String requestId,
        Instant expiresAt,
        CompletableFuture<JsonNode> response
) {

    public boolean expired(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
```

- [x] **步骤 4：实现 call 和 response 完成**

在 `XiaozhiMcpBridge` 增加 `pendingRequests` 字段、`call` 和 `handleInbound` 方法，并把任务 4 中的空 `cancelPending` 方法替换为真实实现：

```java
private final Map<String, XiaozhiMcpPendingRequest> pendingRequests = new ConcurrentHashMap<>();

public CompletableFuture<JsonNode> call(String deviceId, JsonNode payload, Duration timeout) {
    var requestId = payload.path("id").asText("");
    if (requestId.isBlank()) {
        throw new IllegalArgumentException("mcp request id is required");
    }
    var future = new CompletableFuture<JsonNode>();
    pendingRequests.put(pendingKey(deviceId, requestId), new XiaozhiMcpPendingRequest(
            deviceId,
            requestId,
            Instant.now().plus(timeout),
            future
    ));
    if (!send(deviceId, payload)) {
        pendingRequests.remove(pendingKey(deviceId, requestId));
        future.completeExceptionally(new IllegalStateException("device is offline"));
    }
    future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
            .whenComplete((response, error) -> pendingRequests.remove(pendingKey(deviceId, requestId)));
    return future;
}

public void handleInbound(String deviceId, JsonNode payload) {
    cleanupExpired();
    var id = payload.path("id").asText("");
    if (id.isBlank()) {
        log.debug("xiaozhi mcp notification received, deviceId={}, payload={}", deviceId, payload);
        return;
    }
    var pending = pendingRequests.remove(pendingKey(deviceId, id));
    if (pending != null) {
        pending.response().complete(payload);
    }
}

private String pendingKey(String deviceId, String requestId) {
    return deviceId + ":" + requestId;
}

private void cleanupExpired() {
    var now = Instant.now();
    pendingRequests.entrySet().removeIf(entry -> {
        var expired = entry.getValue().expired(now);
        if (expired) {
            entry.getValue().response().completeExceptionally(new TimeoutException("mcp request timed out"));
        }
        return expired;
    });
}

private void cancelPending(String deviceId, RuntimeException reason) {
    pendingRequests.entrySet().removeIf(entry -> {
        var pending = entry.getValue();
        if (!pending.deviceId().equals(deviceId)) {
            return false;
        }
        pending.response().completeExceptionally(reason);
        return true;
    });
}
```

- [x] **步骤 5：把入站 `type=mcp` 交给 bridge**

修改 `XiaozhiVoiceSessionService.handleText` 的 MCP 分支：

```java
if ("mcp".equals(message.type())) {
    mcpBridge.handleInbound(voiceSession.deviceId(), message.payload());
    log.debug("xiaozhi mcp message bridged, sessionId={}, deviceId={}",
            webSocketSession.getId(), voiceSession.deviceId());
    return;
}
```

- [x] **步骤 6：运行 MCP 等待测试**

在 `XiaozhiMcpBridgeTest` 增加断连取消 pending 测试：

```java
@Test
void shouldCancelPendingRequestsWhenDeviceDisconnects() throws Exception {
    var session = new TestWebSocketSession("ws-session-1");
    bridge.register("device-1", "ws-session-1", session);
    var future = bridge.call("device-1", objectMapper.readTree("""
            {"jsonrpc":"2.0","id":8,"method":"tools/list"}
            """), Duration.ofSeconds(1));

    bridge.unregister("device-1", "ws-session-1");

    assertThat(future).failsWithin(Duration.ofMillis(100))
            .withThrowableThat()
            .withMessageContaining("device disconnected");
}
```

运行：

```bash
"/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn" -pl chatbot-voice-gateway -am -Dtest=XiaozhiMcpBridgeTest,XiaozhiVoiceSessionServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：PASS。

## 任务 6：MCP REST 管理接口与鉴权

**文件：**
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpController.java`
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpAdminAuth.java`
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeans.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpControllerTest.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeansTest.java`

- [x] **步骤 1：编写 MCP 管理接口鉴权失败测试**

创建 `XiaozhiMcpControllerTest`：

```java
@WebMvcTest(XiaozhiMcpController.class)
class XiaozhiMcpControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private XiaozhiMcpBridge bridge;

    @MockitoBean
    private XiaozhiMcpAdminAuth adminAuth;

    @Test
    void shouldRejectMissingAdminTokenWhenRequired() throws Exception {
        given(adminAuth.matches("")).willReturn(false);

        mockMvc.perform(post("/api/xiaozhi/devices/device-1/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("mcp admin token required"));
    }
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
"/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn" -pl chatbot-voice-gateway -am -Dtest=XiaozhiMcpControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：FAIL，缺少 controller 和 auth。

- [x] **步骤 3：实现 MCP admin auth**

创建 `XiaozhiMcpAdminAuth`：

```java
public record XiaozhiMcpAdminAuth(String adminToken, String hermesToken, boolean authRequired) {

    public boolean required() {
        return authRequired || adminToken != null && !adminToken.isBlank();
    }

    public boolean matches(String actualToken) {
        return matchesRequired(adminToken, actualToken);
    }

    public boolean matchesHermes(String authorizationHeader) {
        var token = authorizationHeader == null ? "" : authorizationHeader.replaceFirst("^Bearer\\s+", "");
        return matchesRequired(hermesToken, token);
    }

    private boolean matchesRequired(String expected, String actual) {
        if (!authRequired && (expected == null || expected.isBlank())) {
            return true;
        }
        if (expected == null || expected.isBlank()) {
            return false;
        }
        return expected.equals(actual);
    }
}
```

在 `XiaozhiVoiceGatewayBeans` 增加：

```java
@Bean
XiaozhiMcpAdminAuth xiaozhiMcpAdminAuth(
        @Value("${chatbot.voice.mcp.admin-token:}") String adminToken,
        @Value("${chatbot.voice.mcp.hermes-token:}") String hermesToken,
        @Value("${chatbot.voice.mcp.auth-required:false}") boolean authRequired
) {
    return new XiaozhiMcpAdminAuth(adminToken, hermesToken, authRequired);
}
```

- [x] **步骤 4：实现 MCP Controller**

创建 `XiaozhiMcpController`：

```java
@RestController
@RequiredArgsConstructor
public class XiaozhiMcpController {

    private final XiaozhiMcpBridge bridge;
    private final XiaozhiMcpAdminAuth adminAuth;

    @GetMapping("/api/xiaozhi/devices")
    public ResponseEntity<?> devices(@RequestHeader(value = "X-MCP-Admin-Token", defaultValue = "") String token) {
        if (!adminAuth.matches(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "mcp admin token required"));
        }
        return ResponseEntity.ok(Map.of("devices", bridge.onlineDeviceIds()));
    }

    @PostMapping("/api/xiaozhi/devices/{deviceId}/mcp")
    public ResponseEntity<?> send(
            @PathVariable String deviceId,
            @RequestHeader(value = "X-MCP-Admin-Token", defaultValue = "") String token,
            @RequestBody JsonNode payload
    ) {
        if (!adminAuth.matches(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "mcp admin token required"));
        }
        if (!bridge.send(deviceId, payload)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "device offline"));
        }
        return ResponseEntity.accepted().body(Map.of("status", "sent"));
    }

    @PostMapping("/api/xiaozhi/devices/{deviceId}/mcp/rpc")
    public ResponseEntity<?> call(
            @PathVariable String deviceId,
            @RequestHeader(value = "X-MCP-Admin-Token", defaultValue = "") String token,
            @RequestBody JsonNode payload
    ) throws Exception {
        if (!adminAuth.matches(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "mcp admin token required"));
        }
        try {
            return ResponseEntity.ok(bridge.call(deviceId, payload, Duration.ofSeconds(10)).get());
        } catch (ExecutionException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", exception.getCause().getMessage()));
        } catch (TimeoutException exception) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Map.of("error", "mcp request timed out"));
        }
    }
}
```

- [x] **步骤 5：补充成功、离线、超时测试**

在 `XiaozhiMcpControllerTest` 增加：

```java
@Test
void shouldSendMcpPayloadToDevice() throws Exception {
    given(adminAuth.matches("admin-token")).willReturn(true);
    given(bridge.send(eq("device-1"), any())).willReturn(true);

    mockMvc.perform(post("/api/xiaozhi/devices/device-1/mcp")
                    .header("X-MCP-Admin-Token", "admin-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("sent"));
}

@Test
void shouldReturnDeviceOfflineWhenBridgeCannotSend() throws Exception {
    given(adminAuth.matches("admin-token")).willReturn(true);
    given(bridge.send(eq("device-1"), any())).willReturn(false);

    mockMvc.perform(post("/api/xiaozhi/devices/device-1/mcp")
                    .header("X-MCP-Admin-Token", "admin-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("device offline"));
}
```

- [x] **步骤 6：运行 MCP Controller 测试**

运行：

```bash
"/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn" -pl chatbot-voice-gateway -am -Dtest=XiaozhiMcpControllerTest,XiaozhiVoiceGatewayBeansTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：PASS。

- [x] **步骤 7：补充 MCP 鉴权配置测试**

在 `XiaozhiVoiceGatewayBeansTest` 增加：

```java
@Test
void shouldRequireMcpAdminTokenWhenAuthRequiredIsTrue() {
    var auth = new XiaozhiMcpAdminAuth("", "", true);

    assertThat(auth.required()).isTrue();
    assertThat(auth.matches("")).isFalse();
}
```

## 任务 7：Hermes HTTP JSON-RPC 适配入口

**文件：**
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpGatewayToolService.java`
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpJsonRpcService.java`
- 创建：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpJsonRpcController.java`
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpAdminAuth.java`
- 修改：`chatbot-voice-gateway/src/main/java/com/jzb/chatbot/voice/XiaozhiVoiceGatewayBeans.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpGatewayToolServiceTest.java`
- 测试：`chatbot-voice-gateway/src/test/java/com/jzb/chatbot/voice/mcp/XiaozhiMcpJsonRpcControllerTest.java`

Hermes HTTP JSON-RPC 适配原则：

- Java 对 Hermes 暴露的是“小智设备 MCP 网关”，不是每个设备工具的静态 Java 实现。
- Hermes 的 `tools/list` 固定返回 3 个网关工具：`xiaozhi_list_online_devices`、`xiaozhi_list_device_tools`、`xiaozhi_call_device_tool`。
- `xiaozhi_list_device_tools` 内部向设备发送 MCP `tools/list`，返回设备原始工具 schema。
- `xiaozhi_call_device_tool` 内部向设备发送 MCP `tools/call`，`params.name` 使用设备原始工具名，`params.arguments` 使用 Hermes 传入参数。
- 对设备发送的 MCP request id 使用 `AtomicLong` 生成数字，兼容固件 `cJSON_IsNumber(id)` 校验。
- 该入口是 Spring MVC HTTP JSON-RPC endpoint，不是完整 MCP stdio/SSE transport；不能直接承诺 MCP Inspector 连接。

- [x] **步骤 1：编写 Hermes 工具列表失败测试**

创建 `XiaozhiMcpGatewayToolServiceTest`：

```java
class XiaozhiMcpGatewayToolServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final XiaozhiMcpBridge bridge = mock(XiaozhiMcpBridge.class);
    private final XiaozhiMcpGatewayToolService service = new XiaozhiMcpGatewayToolService(objectMapper, bridge);

    @Test
    void shouldExposeStableHermesGatewayTools() {
        var tools = service.gatewayTools();

        assertThat(tools)
                .extracting(tool -> tool.path("name").asText())
                .containsExactly(
                        "xiaozhi_list_online_devices",
                        "xiaozhi_list_device_tools",
                        "xiaozhi_call_device_tool"
                );
        assertThat(tools.get(2).path("inputSchema").path("properties").has("deviceId")).isTrue();
        assertThat(tools.get(2).path("inputSchema").path("properties").has("name")).isTrue();
        assertThat(tools.get(2).path("inputSchema").path("properties").has("arguments")).isTrue();
    }
}
```

- [x] **步骤 2：运行测试验证失败**

运行：

```bash
"/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn" -pl chatbot-voice-gateway -am -Dtest=XiaozhiMcpGatewayToolServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：FAIL，编译报错包含 `XiaozhiMcpGatewayToolService` 不存在。

- [x] **步骤 3：实现稳定网关工具 schema**

创建 `XiaozhiMcpGatewayToolService`：

```java
@Service
@RequiredArgsConstructor
public class XiaozhiMcpGatewayToolService {

    private final ObjectMapper objectMapper;
    private final XiaozhiMcpBridge bridge;
    private final AtomicLong requestIds = new AtomicLong(10000L);

    public ArrayNode gatewayTools() {
        var tools = objectMapper.createArrayNode();
        tools.add(tool(
                "xiaozhi_list_online_devices",
                "列出当前在线的小智设备 ID。返回 devices 数组。",
                objectMapper.createObjectNode()
        ));
        tools.add(tool(
                "xiaozhi_list_device_tools",
                "读取指定小智设备当前暴露的 MCP 工具列表。参数 deviceId 必填。",
                objectMapper.createObjectNode()
                        .set("deviceId", schema("string", "在线小智设备 ID"))
        ));
        tools.add(tool(
                "xiaozhi_call_device_tool",
                "调用指定小智设备上的 MCP 工具。参数 deviceId、name 必填，arguments 为设备工具参数对象。",
                objectMapper.createObjectNode()
                        .set("deviceId", schema("string", "在线小智设备 ID"))
                        .set("name", schema("string", "设备 MCP 工具原始名称，例如 self.get_device_status"))
                        .set("arguments", schema("object", "设备 MCP 工具参数对象"))
        ));
        return tools;
    }

    private ObjectNode tool(String name, String description, ObjectNode properties) {
        var schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", required(properties));

        var tool = objectMapper.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        tool.set("inputSchema", schema);
        return tool;
    }

    private ObjectNode schema(String type, String description) {
        var schema = objectMapper.createObjectNode();
        schema.put("type", type);
        schema.put("description", description);
        return schema;
    }

    private ArrayNode required(ObjectNode properties) {
        var required = objectMapper.createArrayNode();
        properties.fieldNames().forEachRemaining(name -> {
            if (!"arguments".equals(name)) {
                required.add(name);
            }
        });
        return required;
    }
}
```

- [x] **步骤 4：编写工具执行失败测试**

在 `XiaozhiMcpGatewayToolServiceTest` 增加：

```java
@Test
void shouldListOnlineDevices() {
    given(bridge.onlineDeviceIds()).willReturn(List.of("device-1", "device-2"));

    var result = service.call("xiaozhi_list_online_devices", objectMapper.createObjectNode(), Duration.ofSeconds(1));

    assertThat(result.path("devices")).extracting(JsonNode::asText).containsExactly("device-1", "device-2");
}

@Test
void shouldCallDeviceToolWithOriginalToolName() throws Exception {
    var deviceResponse = objectMapper.readTree("""
            {"jsonrpc":"2.0","id":10000,"result":{"content":[{"type":"text","text":"ok"}],"isError":false}}
            """);
    given(bridge.call(eq("device-1"), any(), any())).willReturn(CompletableFuture.completedFuture(deviceResponse));

    var args = objectMapper.readTree("""
            {"deviceId":"device-1","name":"self.get_device_status","arguments":{}}
            """);
    var result = service.call("xiaozhi_call_device_tool", args, Duration.ofSeconds(1));

    assertThat(result.path("content").get(0).path("text").asText()).isEqualTo("ok");
    var payloadCaptor = ArgumentCaptor.forClass(JsonNode.class);
    verify(bridge).call(eq("device-1"), payloadCaptor.capture(), any());
    assertThat(payloadCaptor.getValue().path("method").asText()).isEqualTo("tools/call");
    assertThat(payloadCaptor.getValue().path("params").path("name").asText()).isEqualTo("self.get_device_status");
    assertThat(payloadCaptor.getValue().path("id").isNumber()).isTrue();
}
```

- [x] **步骤 5：实现工具执行映射**

在 `XiaozhiMcpGatewayToolService` 增加：

```java
public JsonNode call(String toolName, JsonNode arguments, Duration timeout) {
    return switch (toolName) {
        case "xiaozhi_list_online_devices" -> listOnlineDevices();
        case "xiaozhi_list_device_tools" -> listDeviceTools(arguments, timeout);
        case "xiaozhi_call_device_tool" -> callDeviceTool(arguments, timeout);
        default -> throw new IllegalArgumentException("unknown xiaozhi gateway tool: " + toolName);
    };
}

private ObjectNode listOnlineDevices() {
    var result = objectMapper.createObjectNode();
    var devices = objectMapper.createArrayNode();
    bridge.onlineDeviceIds().forEach(devices::add);
    result.set("devices", devices);
    return result;
}

private JsonNode listDeviceTools(JsonNode arguments, Duration timeout) {
    var deviceId = requiredText(arguments, "deviceId");
    var payload = objectMapper.createObjectNode()
            .put("jsonrpc", "2.0")
            .put("id", requestIds.getAndIncrement())
            .put("method", "tools/list");
    payload.set("params", objectMapper.createObjectNode().put("withUserTools", true));
    return await(bridge.call(deviceId, payload, timeout), timeout).path("result");
}

private JsonNode callDeviceTool(JsonNode arguments, Duration timeout) {
    var deviceId = requiredText(arguments, "deviceId");
    var name = requiredText(arguments, "name");
    var payload = objectMapper.createObjectNode()
            .put("jsonrpc", "2.0")
            .put("id", requestIds.getAndIncrement())
            .put("method", "tools/call");
    var params = objectMapper.createObjectNode().put("name", name);
    params.set("arguments", arguments.path("arguments").isObject()
            ? arguments.path("arguments")
            : objectMapper.createObjectNode());
    payload.set("params", params);
    return await(bridge.call(deviceId, payload, timeout), timeout).path("result");
}

private String requiredText(JsonNode arguments, String field) {
    var value = arguments.path(field).asText("");
    if (value.isBlank()) {
        throw new IllegalArgumentException(field + " is required");
    }
    return value;
}

private JsonNode await(CompletableFuture<JsonNode> future, Duration timeout) {
    try {
        return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("xiaozhi mcp call interrupted", exception);
    } catch (ExecutionException exception) {
        throw new IllegalStateException(exception.getCause().getMessage(), exception.getCause());
    } catch (TimeoutException exception) {
        throw new IllegalStateException("xiaozhi mcp call timed out", exception);
    }
}
```

- [x] **步骤 6：编写 Hermes HTTP JSON-RPC 入口失败测试**

创建 `XiaozhiMcpJsonRpcControllerTest`：

```java
@WebMvcTest(XiaozhiMcpJsonRpcController.class)
class XiaozhiMcpJsonRpcControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private XiaozhiMcpJsonRpcService jsonRpcService;

    @MockitoBean
    private XiaozhiMcpAdminAuth adminAuth;

    @Test
    void shouldReturnToolsListForHermes() throws Exception {
        given(adminAuth.matchesHermes("Bearer hermes-token")).willReturn(true);
        given(jsonRpcService.handle(any())).willReturn(new ObjectMapper().readTree("""
                {"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"xiaozhi_list_online_devices"}]}}
                """));

        mockMvc.perform(post("/api/hermes/xiaozhi/mcp")
                        .header("Authorization", "Bearer hermes-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools[0].name").value("xiaozhi_list_online_devices"));
    }

    @Test
    void shouldRejectHermesWhenTokenIsInvalid() throws Exception {
        given(adminAuth.matchesHermes("Bearer bad")).willReturn(false);

        mockMvc.perform(post("/api/hermes/xiaozhi/mcp")
                        .header("Authorization", "Bearer bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("hermes mcp token required"));
    }
}
```

- [x] **步骤 7：确认 Hermes token 鉴权已接入**

`XiaozhiMcpAdminAuth` 已在任务 6 使用三参 record。这里只补充 Hermes token 行为测试：

```java
@Test
void shouldMatchHermesBearerToken() {
    var auth = new XiaozhiMcpAdminAuth("admin-token", "hermes-token", true);

    assertThat(auth.matchesHermes("Bearer hermes-token")).isTrue();
    assertThat(auth.matchesHermes("Bearer bad")).isFalse();
}
```

确认 `XiaozhiVoiceGatewayBeans` 已使用如下 Bean 定义：

```java
@Bean
XiaozhiMcpAdminAuth xiaozhiMcpAdminAuth(
        @Value("${chatbot.voice.mcp.admin-token:}") String adminToken,
        @Value("${chatbot.voice.mcp.hermes-token:}") String hermesToken,
        @Value("${chatbot.voice.mcp.auth-required:false}") boolean authRequired
) {
    return new XiaozhiMcpAdminAuth(adminToken, hermesToken, authRequired);
}
```

- [x] **步骤 8：实现 Hermes JSON-RPC Service**

创建 `XiaozhiMcpJsonRpcService`：

```java
@Service
@RequiredArgsConstructor
public class XiaozhiMcpJsonRpcService {

    private final ObjectMapper objectMapper;
    private final XiaozhiMcpGatewayToolService toolService;

    public ObjectNode handle(JsonNode request) {
        var id = request.path("id");
        var method = request.path("method").asText("");
        return switch (method) {
            case "initialize" -> result(id, initializeResult());
            case "tools/list" -> result(id, objectMapper.createObjectNode().set("tools", toolService.gatewayTools()));
            case "tools/call" -> result(id, callTool(request.path("params")));
            default -> error(id, -32601, "method not found: " + method);
        };
    }

    private ObjectNode initializeResult() {
        var result = objectMapper.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        result.set("capabilities", objectMapper.createObjectNode().set("tools", objectMapper.createObjectNode()));
        result.set("serverInfo", objectMapper.createObjectNode()
                .put("name", "chatbot-service-java-xiaozhi-mcp")
                .put("version", "0.0.1"));
        return result;
    }

    private JsonNode callTool(JsonNode params) {
        var name = params.path("name").asText("");
        var arguments = params.path("arguments").isObject() ? params.path("arguments") : objectMapper.createObjectNode();
        try {
            return toolResult(toolService.call(name, arguments, Duration.ofSeconds(10)), false);
        } catch (RuntimeException exception) {
            return toolText(exception.getMessage(), true);
        }
    }

    private ObjectNode toolResult(JsonNode result, boolean isError) {
        var content = objectMapper.createArrayNode();
        content.add(objectMapper.createObjectNode()
                .put("type", "text")
                .put("text", result.toString()));
        var response = objectMapper.createObjectNode();
        response.set("content", content);
        response.put("isError", isError);
        return response;
    }

    private ObjectNode toolText(String text, boolean isError) {
        var content = objectMapper.createArrayNode();
        content.add(objectMapper.createObjectNode()
                .put("type", "text")
                .put("text", text == null ? "" : text));
        var response = objectMapper.createObjectNode();
        response.set("content", content);
        response.put("isError", isError);
        return response;
    }

    private ObjectNode result(JsonNode id, JsonNode result) {
        var response = objectMapper.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        var response = objectMapper.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("error", objectMapper.createObjectNode().put("code", code).put("message", message));
        return response;
    }
}
```

- [x] **步骤 9：实现 Hermes JSON-RPC Controller**

创建 `XiaozhiMcpJsonRpcController`：

```java
@RestController
@RequiredArgsConstructor
public class XiaozhiMcpJsonRpcController {

    private final XiaozhiMcpJsonRpcService jsonRpcService;
    private final XiaozhiMcpAdminAuth adminAuth;

    @PostMapping("/api/hermes/xiaozhi/mcp")
    public ResponseEntity<?> handle(
            @RequestHeader(value = "Authorization", defaultValue = "") String authorization,
            @RequestBody JsonNode request
    ) {
        if (!adminAuth.matchesHermes(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "hermes mcp token required"));
        }
        return ResponseEntity.ok(jsonRpcService.handle(request));
    }
}
```

- [x] **步骤 10：运行 Hermes HTTP JSON-RPC 适配测试**

运行：

```bash
"/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn" -pl chatbot-voice-gateway -am -Dtest=XiaozhiMcpGatewayToolServiceTest,XiaozhiMcpJsonRpcControllerTest,XiaozhiVoiceGatewayBeansTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：PASS。此测试只证明 HTTP JSON-RPC 入口可用，不等价于 MCP Inspector 对完整 MCP transport 的验证。

## 任务 8：配置文件、环境变量和联调脚本

**文件：**
- 修改：`chatbot-bootstrap/src/main/resources/application.yml`
- 修改：`deploy/chatbot-service.env.example`
- 创建：`scripts/xiaozhi_ota_smoke.py`
- 创建：`scripts/xiaozhi_mcp_smoke.py`
- 创建：`scripts/hermes_xiaozhi_mcp_smoke.py`
- 测试：`chatbot-bootstrap/src/test/java/com/jzb/chatbot/bootstrap/ChatbotApplicationTest.java`

- [x] **步骤 1：补充 application.yml**

新增配置：

```yaml
chatbot:
  ota:
    enabled: ${XIAOZHI_OTA_ENABLED:true}
    websocket:
      url: ${XIAOZHI_OTA_WEBSOCKET_URL:}
      token: ${XIAOZHI_OTA_WEBSOCKET_TOKEN:${XIAOZHI_WEBSOCKET_TOKEN:}}
      version: ${XIAOZHI_OTA_WEBSOCKET_VERSION:1}
    firmware:
      version: ${XIAOZHI_OTA_FIRMWARE_VERSION:}
      url: ${XIAOZHI_OTA_FIRMWARE_URL:}
      force: ${XIAOZHI_OTA_FIRMWARE_FORCE:false}
      directory: ${XIAOZHI_OTA_FIRMWARE_DIR:/app/firmware}
    activation:
      required: ${XIAOZHI_OTA_ACTIVATION_REQUIRED:false}
      message: ${XIAOZHI_OTA_ACTIVATION_MESSAGE:请在服务端完成设备激活}
      ttl-seconds: ${XIAOZHI_OTA_ACTIVATION_TTL_SECONDS:30}
    security:
      allowed-device-ids: ${XIAOZHI_OTA_ALLOWED_DEVICE_IDS:}
      allowed-serial-numbers: ${XIAOZHI_OTA_ALLOWED_SERIAL_NUMBERS:}
  voice:
    mcp:
      auth-required: ${XIAOZHI_MCP_AUTH_REQUIRED:false}
      admin-token: ${XIAOZHI_MCP_ADMIN_TOKEN:}
      hermes-token: ${XIAOZHI_MCP_HERMES_TOKEN:${XIAOZHI_MCP_ADMIN_TOKEN:}}
```

- [x] **步骤 2：补充 env example**

在 `deploy/chatbot-service.env.example` 增加：

```dotenv
XIAOZHI_OTA_ENABLED=true
XIAOZHI_OTA_WEBSOCKET_URL=ws://203.195.202.54:8766/xiaozhi/v1
XIAOZHI_OTA_WEBSOCKET_TOKEN=
XIAOZHI_OTA_WEBSOCKET_VERSION=1
XIAOZHI_OTA_FIRMWARE_VERSION=
XIAOZHI_OTA_FIRMWARE_URL=
XIAOZHI_OTA_FIRMWARE_FORCE=false
XIAOZHI_OTA_FIRMWARE_DIR=/app/firmware
XIAOZHI_OTA_ACTIVATION_REQUIRED=false
XIAOZHI_OTA_ACTIVATION_TTL_SECONDS=30
XIAOZHI_OTA_ALLOWED_DEVICE_IDS=
XIAOZHI_OTA_ALLOWED_SERIAL_NUMBERS=
XIAOZHI_MCP_AUTH_REQUIRED=false
XIAOZHI_MCP_ADMIN_TOKEN=
XIAOZHI_MCP_HERMES_TOKEN=
```

- [x] **步骤 3：创建 OTA smoke 脚本**

创建 `scripts/xiaozhi_ota_smoke.py`，只用 Python 标准库：

```python
#!/usr/bin/env python3
import argparse
import json
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument("--url", required=True)
parser.add_argument("--device-id", default="smoke-device-1")
parser.add_argument("--client-id", default="smoke-client-1")
args = parser.parse_args()

request = urllib.request.Request(
    args.url,
    data=b"{}",
    headers={
        "Content-Type": "application/json",
        "Device-Id": args.device_id,
        "Client-Id": args.client_id,
        "Activation-Version": "1",
        "User-Agent": "xiaozhi-ota-smoke/1.0",
    },
    method="POST",
)

with urllib.request.urlopen(request, timeout=10) as response:
    body = json.loads(response.read().decode("utf-8"))

assert "websocket" in body, body
assert "server_time" in body, body
assert "firmware" in body, body
print(json.dumps(body, ensure_ascii=False, indent=2))
```

- [x] **步骤 4：创建 MCP smoke 脚本**

创建 `scripts/xiaozhi_mcp_smoke.py`，复用 REST MCP endpoint：

```python
#!/usr/bin/env python3
import argparse
import json
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument("--url", required=True)
parser.add_argument("--device-id", required=True)
parser.add_argument("--token", default="")
args = parser.parse_args()

payload = json.dumps({
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/list",
    "params": {"withUserTools": True}
}).encode("utf-8")

request = urllib.request.Request(
    f"{args.url.rstrip('/')}/api/xiaozhi/devices/{args.device_id}/mcp",
    data=payload,
    headers={
        "Content-Type": "application/json",
        "X-MCP-Admin-Token": args.token,
    },
    method="POST",
)

with urllib.request.urlopen(request, timeout=10) as response:
    body = json.loads(response.read().decode("utf-8"))

print(json.dumps(body, ensure_ascii=False, indent=2))
```

- [x] **步骤 5：创建 Hermes HTTP JSON-RPC smoke 脚本**

创建 `scripts/hermes_xiaozhi_mcp_smoke.py`，验证 Hermes 可接入的 JSON-RPC 入口：

```python
#!/usr/bin/env python3
import argparse
import json
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument("--url", required=True)
parser.add_argument("--token", default="")
args = parser.parse_args()

payload = json.dumps({
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/list"
}).encode("utf-8")

request = urllib.request.Request(
    f"{args.url.rstrip('/')}/api/hermes/xiaozhi/mcp",
    data=payload,
    headers={
        "Content-Type": "application/json",
        "Authorization": f"Bearer {args.token}",
    },
    method="POST",
)

with urllib.request.urlopen(request, timeout=10) as response:
    body = json.loads(response.read().decode("utf-8"))

tools = body["result"]["tools"]
names = [tool["name"] for tool in tools]
assert "xiaozhi_list_online_devices" in names, body
assert "xiaozhi_list_device_tools" in names, body
assert "xiaozhi_call_device_tool" in names, body
print(json.dumps(body, ensure_ascii=False, indent=2))
```

- [x] **步骤 6：运行启动测试**

运行：

```bash
"/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn" -pl chatbot-bootstrap -am test
```

预期：PASS，`ChatbotApplicationTest` 能加载新增配置。

## 任务 9：文档和任务清单同步

**文件：**
- 修改：`README.md`
- 修改：`docs/superpowers/plans/2026-06-16-xiaozhi-firmware-backend-task-checklist.md`

- [x] **步骤 1：更新 README 的 OTA 配置说明**

在 README 增加：

```markdown
### 小智 OTA / 激活配置

固件侧 `ota_url` 指向：

```text
http://<server-host>:8766/api/ota/check
```

服务端返回 `websocket`、`server_time` 和 `firmware`。如果 `XIAOZHI_OTA_ACTIVATION_REQUIRED=true`，返回 `activation.challenge`，固件会调用：

```text
POST /api/ota/check/activate
```

生产环境返回 `websocket.token` 前应配置 `XIAOZHI_OTA_ALLOWED_DEVICE_IDS` 或 `XIAOZHI_OTA_ALLOWED_SERIAL_NUMBERS`。
```

- [x] **步骤 2：更新 README 的 MCP 调用说明**

增加：

```markdown
### 小智 MCP 薄桥

Java 服务端不实现设备业务工具，只通过已连接的小智 WebSocket 设备转发 JSON-RPC payload。Hermes 接入时使用 Java 暴露的“小智设备 MCP 网关”入口。

列出在线设备：

```bash
curl -H "X-MCP-Admin-Token: <admin-token>" \
  http://203.195.202.54:8766/api/xiaozhi/devices
```

下发 `tools/list`：

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  -H "X-MCP-Admin-Token: <admin-token>" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"withUserTools":true}}' \
  http://203.195.202.54:8766/api/xiaozhi/devices/<device-id>/mcp
```

Hermes HTTP JSON-RPC 适配入口：

```text
POST http://203.195.202.54:8766/api/hermes/xiaozhi/mcp
Authorization: Bearer <hermes-mcp-token>
```

Hermes 侧 `tools/list` 会看到三个稳定工具：

- `xiaozhi_list_online_devices`
- `xiaozhi_list_device_tools`
- `xiaozhi_call_device_tool`
```

- [x] **步骤 3：同步任务清单状态**

在 `2026-06-16-xiaozhi-firmware-backend-task-checklist.md` 中把 OTA/MCP 条目改为“已拆出实现计划”，并添加本文件路径：

```markdown
- OTA / 激活配置接口：已拆出计划 `docs/superpowers/plans/2026-06-16-xiaozhi-ota-mcp-adaptation.md`。
- MCP 薄透传桥和 Hermes HTTP JSON-RPC 适配入口：已拆出计划 `docs/superpowers/plans/2026-06-16-xiaozhi-ota-mcp-adaptation.md`。
```

- [x] **步骤 4：运行文档占位符扫描**

运行：

```bash
rg -n 'T''ODO|待''定|后续''补充|place''holder|类似''任务' README.md docs/superpowers/plans/2026-06-16-xiaozhi-firmware-backend-task-checklist.md docs/superpowers/plans/2026-06-16-xiaozhi-ota-mcp-adaptation.md
```

预期：没有匹配。

## 任务 10：完整验证门禁

**文件：**
- 验证：全仓库源码和文档。

- [x] **步骤 1：运行 device-gateway OTA 测试**

```bash
"/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn" -pl chatbot-device-gateway -am test
```

预期：PASS。

- [x] **步骤 2：运行 voice-gateway MCP 测试**

```bash
"/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn" -pl chatbot-voice-gateway -am test
```

预期：PASS。

- [x] **步骤 3：运行全仓库测试**

```bash
"/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn" test
```

预期：7 个模块全部 SUCCESS。

- [x] **步骤 4：构建启动包**

```bash
"/Users/jiangzhibin/.local/opt/mvnd/mvn/bin/mvn" -pl chatbot-bootstrap -am -DskipTests package
```

预期：生成 `/Users/jiangzhibin/workspace/chatbot-service-java/chatbot-bootstrap/target/chatbot-bootstrap-0.0.1-SNAPSHOT.jar`。

- [x] **步骤 5：本地 OTA smoke**

启动本地服务后运行：

```bash
python3 scripts/xiaozhi_ota_smoke.py \
  --url http://127.0.0.1:8766/api/ota/check \
  --device-id smoke-device-1 \
  --client-id smoke-client-1
```

预期：输出包含 `websocket`、`server_time`、`firmware`。

- [x] **步骤 6：本地 MCP smoke**

在 WebSocket 调试客户端连上一个设备会话后运行：

```bash
python3 scripts/xiaozhi_mcp_smoke.py \
  --url http://127.0.0.1:8766 \
  --device-id smoke-device-1 \
  --token "$XIAOZHI_MCP_ADMIN_TOKEN"
```

预期：返回 `{"status":"sent"}`；WebSocket 设备收到 `type=mcp`。

- [x] **步骤 7：本地 Hermes HTTP JSON-RPC smoke**

启动本地服务后运行：

```bash
python3 scripts/hermes_xiaozhi_mcp_smoke.py \
  --url http://127.0.0.1:8766 \
  --token "$XIAOZHI_MCP_HERMES_TOKEN"
```

预期：返回 JSON-RPC `result.tools`，包含 `xiaozhi_list_online_devices`、`xiaozhi_list_device_tools`、`xiaozhi_call_device_tool`。

- [ ] **步骤 8：公网发布门禁**

影响协议、OTA、MCP、鉴权或部署配置时，发布顺序固定为：

1. 本地测试。
2. 构建 `chatbot-bootstrap`。
3. 部署到 `203.195.202.54:8766`。
4. 公网 OTA smoke。
5. 公网 WebSocket smoke。
6. 运维 MCP smoke。
7. Hermes HTTP JSON-RPC smoke。
8. 小智真机验证 OTA 配置、设备 MCP `tools/list` 和 Hermes HTTP JSON-RPC `tools/list`。
9. 将测试时间、Git commit、设备 ID、OTA URL、WebSocket URL、设备 MCP 调用结果、Hermes HTTP JSON-RPC 调用结果写入 `2026-06-16-xiaozhi-firmware-backend-task-checklist.md` 的真机测试记录。

## 关键风险与处理

- WebSocket token 泄露风险：OTA 返回 token 前必须限制设备 allowlist；生产环境不允许开放返回 token。
- 固件文件路径穿越风险：固件下载接口只允许读取 `firmwareDirectory` 下的单文件名。
- MCP 任意工具调用风险：公网或生产部署必须设置 `XIAOZHI_MCP_AUTH_REQUIRED=true`，并配置 `XIAOZHI_MCP_ADMIN_TOKEN`、`XIAOZHI_MCP_HERMES_TOKEN`；日志不输出完整 token。
- MCP 同步等待阻塞风险：`/mcp/rpc` 必须有固定超时，默认 10 秒。
- 设备重连导致 pending response 丢失：bridge 注销 session 时取消该设备所有 pending 请求。
- Java 侧边界膨胀风险：本计划只做 JSON-RPC payload 转发和 Hermes 稳定网关工具，不持久化设备工具 schema，不实现设备业务工具。

## 自检结果

- 规格覆盖度：OTA check、activation、firmware download、security allowlist、MCP send、MCP request/response、Hermes HTTP JSON-RPC 适配入口、配置、脚本、文档和发布门禁均有对应任务。
- 占位符扫描：本文档没有禁用占位标记的正文用法。
- 类型一致性：计划中使用的新增类型为 `XiaozhiOtaProperties`、`XiaozhiOtaBeans`、`OtaDeviceIdentity`、`XiaozhiOtaResponseFactory`、`XiaozhiOtaService`、`XiaozhiActivationStore`、`InMemoryXiaozhiActivationStore`、`XiaozhiMcpBridge`、`XiaozhiMcpAdminAuth`、`XiaozhiMcpPendingRequest`、`XiaozhiMcpController`、`XiaozhiMcpGatewayToolService`、`XiaozhiMcpJsonRpcService`、`XiaozhiMcpJsonRpcController`，均在对应任务中定义职责和关键方法。
- 范围一致性：不引入数据库、后台、设备业务工具实现、动态工具持久化或固件仓库改动。
