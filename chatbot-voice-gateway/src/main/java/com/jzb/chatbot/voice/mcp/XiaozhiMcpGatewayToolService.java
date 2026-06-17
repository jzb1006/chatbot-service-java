package com.jzb.chatbot.voice.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jzb.chatbot.voice.reminder.XiaozhiReminderService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 小智 MCP 网关工具服务。
 * <p>
 * 向 Hermes 暴露稳定网关工具，并把调用映射为在线设备 MCP JSON-RPC。
 *
 * @author jiangzhibin
 * @since 2026-06-16 13:47:00
 */
@Service
@RequiredArgsConstructor
public class XiaozhiMcpGatewayToolService {

    private final ObjectMapper objectMapper;
    private final XiaozhiMcpBridge bridge;
    private final XiaozhiReminderService reminderService;
    private final AtomicLong requestIds = new AtomicLong(10000L);

    /**
     * 返回 Hermes 可见的稳定网关工具列表。
     *
     * @return 工具列表
     */
    public ArrayNode gatewayTools() {
        var tools = objectMapper.createArrayNode();
        var listDeviceProperties = objectMapper.createObjectNode();
        listDeviceProperties.set("deviceId", schema("string", "在线小智设备 ID"));
        var callDeviceProperties = objectMapper.createObjectNode();
        callDeviceProperties.set("deviceId", schema("string", "在线小智设备 ID"));
        callDeviceProperties.set("name", schema("string", "设备 MCP 工具原始名称，例如 self.get_device_status"));
        callDeviceProperties.set("arguments", schema("object", "设备 MCP 工具参数对象"));
        var reminderProperties = objectMapper.createObjectNode();
        reminderProperties.set("deviceId", schema("string", "在线小智设备 ID"));
        reminderProperties.set("message", schema("string", "到点后需要播报给用户的提醒内容"));
        reminderProperties.set("remindAt", schema("string", "ISO-8601 到期时间，例如 2026-06-17T18:00:00+08:00"));
        reminderProperties.set("delaySeconds", schema("integer", "从当前时间开始延迟的秒数，例如一分钟后传 60"));
        tools.add(tool(
                "xiaozhi_list_online_devices",
                "列出当前在线的小智设备 ID。返回 devices 数组。",
                objectMapper.createObjectNode()
        ));
        tools.add(tool(
                "xiaozhi_list_device_tools",
                "读取指定小智设备当前暴露的 MCP 工具列表。参数 deviceId 必填。",
                listDeviceProperties
        ));
        tools.add(tool(
                "xiaozhi_call_device_tool",
                "调用指定小智设备上的 MCP 工具。参数 deviceId、name 必填，arguments 为设备工具参数对象。",
                callDeviceProperties
        ));
        tools.add(tool(
                "xiaozhi_create_reminder",
                "创建一次性提醒，到期后小智设备会主动播报 message。message 必填，remindAt 或 delaySeconds 至少填一个。",
                reminderProperties,
                List.of("message")
        ));
        return tools;
    }

    /**
     * 调用稳定网关工具。
     *
     * @param toolName 工具名
     * @param arguments 工具参数
     * @param timeout 超时时间
     * @return 工具结果
     */
    public JsonNode call(String toolName, JsonNode arguments, Duration timeout) {
        return switch (toolName) {
            case "xiaozhi_list_online_devices" -> listOnlineDevices();
            case "xiaozhi_list_device_tools" -> listDeviceTools(arguments, timeout);
            case "xiaozhi_call_device_tool" -> callDeviceTool(arguments, timeout);
            case "xiaozhi_create_reminder" -> createReminder(arguments);
            default -> throw new IllegalArgumentException("unknown xiaozhi gateway tool: " + toolName);
        };
    }

    private ObjectNode tool(String name, String description, ObjectNode properties) {
        return tool(name, description, properties, null);
    }

    private ObjectNode tool(String name, String description, ObjectNode properties, List<String> requiredFields) {
        var schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", requiredFields == null ? required(properties) : required(requiredFields));

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

    private ArrayNode required(List<String> fields) {
        var required = objectMapper.createArrayNode();
        fields.forEach(required::add);
        return required;
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

    private ObjectNode createReminder(JsonNode arguments) {
        var deviceId = resolveReminderDeviceId(arguments);
        var message = requiredText(arguments, "message");
        var reminder = arguments.hasNonNull("delaySeconds")
                ? reminderService.createAfter(deviceId, message, arguments.path("delaySeconds").asLong())
                : reminderService.create(deviceId, message, requiredText(arguments, "remindAt"));
        return objectMapper.createObjectNode()
                .put("id", reminder.id())
                .put("deviceId", reminder.deviceId())
                .put("message", reminder.message())
                .put("remindAt", reminder.remindAt().toString());
    }

    private String resolveReminderDeviceId(JsonNode arguments) {
        var deviceId = arguments.path("deviceId").asText("");
        if (!deviceId.isBlank()) {
            return deviceId;
        }
        var onlineDeviceIds = bridge.onlineDeviceIds();
        if (onlineDeviceIds.size() == 1) {
            return onlineDeviceIds.getFirst();
        }
        throw new IllegalArgumentException("deviceId is required when online device count is not 1");
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
}
