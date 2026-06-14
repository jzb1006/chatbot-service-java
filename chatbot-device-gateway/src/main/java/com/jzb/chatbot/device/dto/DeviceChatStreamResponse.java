package com.jzb.chatbot.device.dto;

import java.util.stream.Stream;

/**
 * 设备文本聊天流式响应。
 * <p>
 * 保存会话元数据和 Hermes SSE 片段流。
 *
 * @author jiangzhibin
 * @since 2026-06-14 20:00:00
 */
public record DeviceChatStreamResponse(
        String deviceId,
        String conversationId,
        Stream<String> chunks
) {

    public DeviceChatStreamResponse {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId must not be blank");
        }
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        if (chunks == null) {
            throw new IllegalArgumentException("chunks must not be null");
        }
    }
}
