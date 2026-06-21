package com.jzb.chatbot.speech;

/**
 * Edge 在线朗读语音合成请求。
 * <p>
 * 传输层使用该对象生成 Edge Read Aloud WebSocket 消息。
 *
 * @author jiangzhibin
 * @since 2026-06-21 09:28:00
 */
public record EdgeTtsRequest(
        String text,
        String voice,
        String outputFormat,
        int sampleRate,
        String rate,
        String pitch
) {
}
