package com.jzb.chatbot.voice.protocol;

/**
 * 小智二进制音频帧。
 * <p>
 * 屏蔽 WebSocket binary v1/v2/v3 包头差异，向会话层暴露统一 Opus payload。
 *
 * @author jiangzhibin
 * @since 2026-06-14 20:49:00
 */
public record XiaozhiAudioFrame(
        int version,
        long timestamp,
        byte[] payload
) {
}
