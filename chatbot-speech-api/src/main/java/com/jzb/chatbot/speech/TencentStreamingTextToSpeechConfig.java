package com.jzb.chatbot.speech;

import java.time.Duration;

/**
 * 腾讯云流式文本转语音配置。
 * <p>
 * 聚合 stream_wsv2 WebSocket 签名和会话创建所需的最小配置。
 *
 * @author jiangzhibin
 * @since 2026-06-18 12:50:00
 */
public record TencentStreamingTextToSpeechConfig(
        int appId,
        String secretId,
        String secretKey,
        String voiceType,
        String codec,
        int sampleRate,
        double speed,
        double volume,
        Duration timeout
) {

    private static final String DEFAULT_VOICE_TYPE = "603004";
    private static final String DEFAULT_CODEC = "pcm";
    private static final int DEFAULT_SAMPLE_RATE = 16000;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    public TencentStreamingTextToSpeechConfig {
        if (appId <= 0 || secretId == null || secretId.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("Tencent streaming TTS requires app-id, secret-id and secret-key");
        }
        if (voiceType == null || voiceType.isBlank()) {
            voiceType = DEFAULT_VOICE_TYPE;
        }
        if (codec == null || codec.isBlank()) {
            codec = DEFAULT_CODEC;
        }
        if (!DEFAULT_CODEC.equalsIgnoreCase(codec)) {
            throw new IllegalArgumentException("Tencent streaming TTS codec must be pcm before Opus encoding");
        }
        if (sampleRate <= 0) {
            sampleRate = DEFAULT_SAMPLE_RATE;
        }
        if (sampleRate != DEFAULT_SAMPLE_RATE) {
            throw new IllegalArgumentException("Tencent streaming TTS sample-rate must be 16000");
        }
        if (!Double.isFinite(speed)) {
            speed = 0.0;
        }
        if (!Double.isFinite(volume)) {
            volume = 0.0;
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = DEFAULT_TIMEOUT;
        }
    }
}
