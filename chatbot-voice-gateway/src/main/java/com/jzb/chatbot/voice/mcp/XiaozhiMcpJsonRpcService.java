package com.jzb.chatbot.voice.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 小智 MCP HTTP JSON-RPC 服务。
 * <p>
 * 面向 Hermes 暴露稳定的小智设备 MCP 网关入口。
 *
 * @author jiangzhibin
 * @since 2026-06-16 13:49:00
 */
@Service
@RequiredArgsConstructor
public class XiaozhiMcpJsonRpcService {

    private final ObjectMapper objectMapper;
    private final XiaozhiMcpGatewayToolService toolService;

    /**
     * 处理 JSON-RPC 请求。
     *
     * @param request JSON-RPC 请求
     * @return JSON-RPC 响应
     */
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
