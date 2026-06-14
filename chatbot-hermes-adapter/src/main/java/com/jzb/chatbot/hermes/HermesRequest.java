package com.jzb.chatbot.hermes;

import com.jzb.chatbot.common.id.ConversationId;
import com.jzb.chatbot.common.id.DeviceId;

/**
 * Hermes 文本对话请求。
 * <p>
 * 保留设备标识和对话标识，避免协议层直接依赖具体 Hermes API。
 *
 * @author jiangzhibin
 * @since 2026-06-14 18:36:50
 */
public record HermesRequest(DeviceId deviceId, ConversationId conversationId, String text) {

    public HermesRequest {
        if (deviceId == null) {
            throw new IllegalArgumentException("deviceId must not be null");
        }
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId must not be null");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
    }
}
