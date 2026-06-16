package com.jzb.chatbot.voice.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小智 MCP HTTP JSON-RPC 控制器。
 * <p>
 * 给 Hermes Agent 提供 HTTP JSON-RPC 适配入口。
 *
 * @author jiangzhibin
 * @since 2026-06-16 13:49:00
 */
@RestController
@RequiredArgsConstructor
public class XiaozhiMcpJsonRpcController {

    private final XiaozhiMcpJsonRpcService jsonRpcService;
    private final XiaozhiMcpAdminAuth adminAuth;

    /**
     * 处理 Hermes JSON-RPC 请求。
     *
     * @param authorization Authorization 请求头
     * @param request JSON-RPC 请求
     * @return JSON-RPC 响应
     */
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
