package com.jzb.chatbot.speech;

import java.time.Duration;

/**
 * 腾讯云一句话识别配置。
 * <p>
 * 聚合调用 SentenceRecognition 接口所需的最小配置。
 *
 * @author jiangzhibin
 * @since 2026-06-16 01:38:00
 */
public record TencentCloudSpeechToTextConfig(
        String secretId,
        String secretKey,
        String region,
        String endpoint,
        String engineModelType,
        String voiceFormat,
        int sampleRate,
        Duration timeout
) {

    private static final String DEFAULT_ENDPOINT = "asr.tencentcloudapi.com";
    private static final String DEFAULT_REGION = "ap-guangzhou";
    private static final String DEFAULT_ENGINE_MODEL_TYPE = "16k_zh";
    private static final String DEFAULT_VOICE_FORMAT = "pcm";
    private static final int DEFAULT_SAMPLE_RATE = 16000;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    public TencentCloudSpeechToTextConfig {
        if (region == null || region.isBlank()) {
            region = DEFAULT_REGION;
        }
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = DEFAULT_ENDPOINT;
        }
        if (engineModelType == null || engineModelType.isBlank()) {
            engineModelType = DEFAULT_ENGINE_MODEL_TYPE;
        }
        if (voiceFormat == null || voiceFormat.isBlank()) {
            voiceFormat = DEFAULT_VOICE_FORMAT;
        }
        if (sampleRate <= 0) {
            sampleRate = DEFAULT_SAMPLE_RATE;
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = DEFAULT_TIMEOUT;
        }
    }
}
