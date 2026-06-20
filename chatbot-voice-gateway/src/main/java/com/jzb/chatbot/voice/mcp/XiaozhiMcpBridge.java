package com.jzb.chatbot.voice.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.jzb.chatbot.voice.protocol.XiaozhiServerEventFactory;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 小智 MCP WebSocket 薄桥。
 * <p>
 * 维护在线设备会话，并把 JSON-RPC payload 透传给设备 MCP 服务。
 *
 * @author jiangzhibin
 * @since 2026-06-16 13:39:00
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class XiaozhiMcpBridge {

    private final XiaozhiServerEventFactory eventFactory;
    private final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, XiaozhiMcpPendingRequest> pendingRequests = new ConcurrentHashMap<>();

    /**
     * 注册在线设备会话。
     *
     * @param deviceId 设备 ID
     * @param sessionId WebSocket 会话 ID
     * @param session WebSocket 会话
     */
    public void register(String deviceId, String sessionId, WebSocketSession session) {
        sessions.put(deviceId, new DeviceSession(deviceId, sessionId, session, false));
    }

    /**
     * 标记设备当前会话已声明 MCP 能力。
     *
     * @param deviceId 设备 ID
     * @param sessionId WebSocket 会话 ID
     */
    public void markMcpReady(String deviceId, String sessionId) {
        sessions.computeIfPresent(deviceId, (key, current) -> current.sessionId().equals(sessionId)
                ? new DeviceSession(current.deviceId(), current.sessionId(), current.session(), true)
                : current);
    }

    /**
     * 注销在线设备会话。
     *
     * @param deviceId 设备 ID
     * @param sessionId WebSocket 会话 ID
     */
    public void unregister(String deviceId, String sessionId) {
        var removed = sessions.computeIfPresent(deviceId, (key, current) ->
                current.sessionId().equals(sessionId) ? null : current) == null;
        if (removed) {
            cancelPending(deviceId, new IllegalStateException("device disconnected"));
        }
    }

    /**
     * 下发 MCP payload。
     *
     * @param deviceId 设备 ID
     * @param payload JSON-RPC payload
     * @return true 表示已发送
     */
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

    /**
     * 发起需要等待 response 的 MCP JSON-RPC 请求。
     *
     * @param deviceId 设备 ID
     * @param payload JSON-RPC payload
     * @param timeout 超时时间
     * @return response future
     */
    public CompletableFuture<JsonNode> call(String deviceId, JsonNode payload, Duration timeout) {
        if (!payload.path("id").isNumber()) {
            throw new IllegalArgumentException("mcp request id must be numeric");
        }
        var deviceSession = sessions.get(deviceId);
        if (deviceSession == null || !deviceSession.session().isOpen()) {
            var future = new CompletableFuture<JsonNode>();
            future.completeExceptionally(new IllegalStateException("device is offline"));
            return future;
        }
        if (!deviceSession.mcpReady()) {
            var future = new CompletableFuture<JsonNode>();
            future.completeExceptionally(new IllegalStateException("device mcp is not ready"));
            return future;
        }
        var requestId = payload.path("id").asText("");
        var future = new CompletableFuture<JsonNode>();
        var key = pendingKey(deviceId, requestId);
        pendingRequests.put(key, new XiaozhiMcpPendingRequest(
                deviceId,
                requestId,
                Instant.now().plus(timeout),
                future
        ));
        if (!send(deviceId, payload)) {
            pendingRequests.remove(key);
            future.completeExceptionally(new IllegalStateException("device is offline"));
        }
        future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((response, error) -> pendingRequests.remove(key));
        return future;
    }

    /**
     * 处理设备入站 MCP payload。
     *
     * @param deviceId 设备 ID
     * @param payload MCP payload
     */
    public void handleInbound(String deviceId, JsonNode payload) {
        cleanupExpired();
        var id = payload.path("id").asText("");
        if (id.isBlank()) {
            log.debug("xiaozhi mcp notification received, deviceId={}", deviceId);
            return;
        }
        var pending = pendingRequests.remove(pendingKey(deviceId, id));
        if (pending != null) {
            pending.response().complete(payload);
        }
    }

    /**
     * 列出在线设备 ID。
     *
     * @return 在线设备 ID 列表
     */
    public List<String> onlineDeviceIds() {
        return sessions.values().stream()
                .filter(deviceSession -> deviceSession.session().isOpen())
                .map(DeviceSession::deviceId)
                .sorted()
                .toList();
    }

    /**
     * 列出在线设备 MCP 会话状态。
     *
     * @return 在线设备 MCP 会话状态列表
     */
    public List<DeviceMcpSession> onlineDevices() {
        return sessions.values().stream()
                .filter(deviceSession -> deviceSession.session().isOpen())
                .map(deviceSession -> new DeviceMcpSession(deviceSession.deviceId(), deviceSession.mcpReady()))
                .sorted((left, right) -> left.deviceId().compareTo(right.deviceId()))
                .toList();
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

    /**
     * 在线设备 MCP 会话状态。
     *
     * @param deviceId 设备 ID
     * @param mcpReady true 表示当前 WebSocket hello 已声明 MCP 能力
     */
    public record DeviceMcpSession(String deviceId, boolean mcpReady) {
    }

    private record DeviceSession(String deviceId, String sessionId, WebSocketSession session, boolean mcpReady) {
    }
}
