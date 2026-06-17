package com.jzb.chatbot.speech;

import java.time.Duration;

/**
 * 腾讯云实时 ASR 配置。
 *
 * @author jiangzhibin
 * @since 2026-06-17 00:00:00
 */
public record TencentRealtimeSpeechToTextConfig(
        String appId,
        String secretId,
        String secretKey,
        String engineModelType,
        int sampleRate,
        Duration chunkTimeout,
        Duration recognitionTimeout
) {

    public TencentRealtimeSpeechToTextConfig {
        if (appId == null || appId.isBlank() || secretId == null || secretId.isBlank()
                || secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("Tencent realtime ASR requires app-id, secret-id and secret-key");
        }
        if (engineModelType == null || engineModelType.isBlank()) {
            engineModelType = "16k_zh";
        }
        if (sampleRate <= 0) {
            sampleRate = 16000;
        }
        if (chunkTimeout == null || chunkTimeout.isNegative() || chunkTimeout.isZero()) {
            chunkTimeout = Duration.ofMillis(100);
        }
        if (recognitionTimeout == null || recognitionTimeout.isNegative() || recognitionTimeout.isZero()) {
            recognitionTimeout = Duration.ofSeconds(90);
        }
    }
}
