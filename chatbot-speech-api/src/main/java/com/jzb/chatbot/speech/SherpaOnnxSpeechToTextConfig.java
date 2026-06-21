package com.jzb.chatbot.speech;

import java.time.Duration;

/**
 * Sherpa-ONNX 流式 ASR 配置。
 * <p>
 * 当前仅承载本地 WebSocket 服务地址和超时，保持 provider 接入边界轻量。
 *
 * @author jiangzhibin
 * @since 2026-06-21 11:54:00
 */
public record SherpaOnnxSpeechToTextConfig(
        String url,
        Duration chunkTimeout,
        Duration recognitionTimeout
) {

    public SherpaOnnxSpeechToTextConfig {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Sherpa-ONNX ASR requires url");
        }
        if (chunkTimeout == null || chunkTimeout.isNegative() || chunkTimeout.isZero()) {
            chunkTimeout = Duration.ofMillis(100);
        }
        if (recognitionTimeout == null || recognitionTimeout.isNegative() || recognitionTimeout.isZero()) {
            recognitionTimeout = Duration.ofSeconds(90);
        }
    }
}
