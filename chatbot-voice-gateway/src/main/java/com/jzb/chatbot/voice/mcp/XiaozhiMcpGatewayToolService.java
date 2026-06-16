package com.jzb.chatbot.voice.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
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
            default -> throw new IllegalArgumentException("unknown xiaozhi gateway tool: " + toolName);
        };
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
}
