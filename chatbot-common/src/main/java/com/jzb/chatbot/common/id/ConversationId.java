package com.jzb.chatbot.common.id;

/**
 * 连续对话标识。
 * <p>
 * 用于向 Hermes 传递多轮对话上下文边界。
 *
 * @author jiangzhibin
 * @since 2026-06-14 18:36:50
 */
public record ConversationId(String value) {

    public ConversationId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
    }
}
