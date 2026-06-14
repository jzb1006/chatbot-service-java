package com.jzb.chatbot.common.id;

/**
 * 单次连接或音频会话标识。
 * <p>
 * 用于 WebSocket 音频连接的生命周期跟踪。
 *
 * @author jiangzhibin
 * @since 2026-06-14 18:36:50
 */
public record SessionId(String value) {

    public SessionId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
    }
}
