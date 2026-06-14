package com.jzb.chatbot.hermes;

import com.jzb.chatbot.common.id.ConversationId;

/**
 * Hermes 文本对话响应。
 * <p>
 * 第一阶段只返回对话标识和文本内容。
 *
 * @author jiangzhibin
 * @since 2026-06-14 18:36:50
 */
public record HermesResponse(ConversationId conversationId, String text) {

    public HermesResponse {
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId must not be null");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
    }
}
