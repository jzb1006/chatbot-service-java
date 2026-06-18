package com.jzb.chatbot.speech;

import java.time.Duration;

/**
 * 腾讯云实时语音合成配置。
 * <p>
 * 聚合调用 TextToVoice 接口所需的最小配置。
 *
 * @author jiangzhibin
 * @since 2026-06-15 16:45:00
 */
public record TencentCloudTextToSpeechConfig(
        String secretId,
        String secretKey,
        String region,
        String endpoint,
        String voiceType,
        String codec,
        int sampleRate,
        Duration timeout
) {

    private static final String DEFAULT_ENDPOINT = "tts.tencentcloudapi.com";
    private static final String DEFAULT_REGION = "ap-guangzhou";
    private static final String DEFAULT_VOICE_TYPE = "603004";
    private static final String DEFAULT_CODEC = "pcm";
    private static final int DEFAULT_SAMPLE_RATE = 16000;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    public TencentCloudTextToSpeechConfig {
        if (region == null || region.isBlank()) {
            region = DEFAULT_REGION;
        }
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = DEFAULT_ENDPOINT;
        }
        if (voiceType == null || voiceType.isBlank()) {
            voiceType = DEFAULT_VOICE_TYPE;
        }
        if (codec == null || codec.isBlank()) {
            codec = DEFAULT_CODEC;
        }
        if (!DEFAULT_CODEC.equalsIgnoreCase(codec)) {
            throw new IllegalArgumentException("Tencent Cloud TTS codec must be pcm before Opus encoding");
        }
        if (sampleRate <= 0) {
            sampleRate = DEFAULT_SAMPLE_RATE;
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = DEFAULT_TIMEOUT;
        }
    }
}
