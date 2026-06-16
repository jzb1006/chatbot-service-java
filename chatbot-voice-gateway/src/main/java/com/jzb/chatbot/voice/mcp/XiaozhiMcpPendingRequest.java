package com.jzb.chatbot.voice.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * 小智 MCP 等待中的 JSON-RPC 请求。
 * <p>
 * 用于把设备返回的 response 关联回服务端 pending future。
 *
 * @author jiangzhibin
 * @since 2026-06-16 13:39:00
 */
public record XiaozhiMcpPendingRequest(
        String deviceId,
        String requestId,
        Instant expiresAt,
        CompletableFuture<JsonNode> response
) {

    /**
     * 判断请求是否已过期。
     *
     * @param now 当前时间
     * @return true 表示已过期
     */
    public boolean expired(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
