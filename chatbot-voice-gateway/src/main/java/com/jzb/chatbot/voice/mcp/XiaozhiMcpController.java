package com.jzb.chatbot.voice.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小智 MCP 运维控制器。
 * <p>
 * 提供在线设备列表、fire-and-forget 下发和 JSON-RPC request/response 调用入口。
 *
 * @author jiangzhibin
 * @since 2026-06-16 13:44:00
 */
@RestController
@RequiredArgsConstructor
public class XiaozhiMcpController {

    private final XiaozhiMcpBridge bridge;
    private final XiaozhiMcpAdminAuth adminAuth;

    /**
     * 列出在线小智设备。
     *
     * @param token 管理 token
     * @return 在线设备列表
     */
    @GetMapping("/api/xiaozhi/devices")
    public ResponseEntity<?> devices(@RequestHeader(value = "X-MCP-Admin-Token", defaultValue = "") String token) {
        if (!adminAuth.matches(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "mcp admin token required"));
        }
        return ResponseEntity.ok(Map.of("devices", bridge.onlineDeviceIds()));
    }

    /**
     * 下发 MCP payload。
     *
     * @param deviceId 设备 ID
     * @param token 管理 token
     * @param payload MCP payload
     * @return 下发结果
     */
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

    /**
     * 发起 MCP JSON-RPC 调用并等待设备 response。
     *
     * @param deviceId 设备 ID
     * @param token 管理 token
     * @param payload MCP payload
     * @return 设备 response
     * @throws Exception 等待异常
     */
    @PostMapping("/api/xiaozhi/devices/{deviceId}/mcp/rpc")
    public ResponseEntity<?> call(
            @PathVariable String deviceId,
            @RequestHeader(value = "X-MCP-Admin-Token", defaultValue = "") String token,
            @RequestBody JsonNode payload
    ) throws Exception {
        if (!adminAuth.matches(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "mcp admin token required"));
        }
        var timeout = Duration.ofSeconds(10);
        try {
            return ResponseEntity.ok(bridge.call(deviceId, payload, timeout).get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", exception.getMessage()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "mcp request interrupted"));
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof TimeoutException) {
                return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Map.of("error", "mcp request timed out"));
            }
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", exception.getCause().getMessage()));
        } catch (TimeoutException exception) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Map.of("error", "mcp request timed out"));
        }
    }
}
