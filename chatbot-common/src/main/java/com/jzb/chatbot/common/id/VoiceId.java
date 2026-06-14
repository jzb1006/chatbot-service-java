package com.jzb.chatbot.common.id;

/**
 * TTS 音色标识。
 * <p>
 * 用于隔离设备请求中的音色选择。
 *
 * @author jiangzhibin
 * @since 2026-06-14 18:36:50
 */
public record VoiceId(String value) {

    public VoiceId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("voiceId must not be blank");
        }
    }
}
