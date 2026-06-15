package com.jzb.chatbot.speech;

/**
 * 腾讯云一句话识别请求。
 * <p>
 * 仅保留当前语音网关所需的最小参数。
 *
 * @author jiangzhibin
 * @since 2026-06-16 01:38:00
 */
record TencentSentenceRecognitionRequest(
        String audio,
        int audioBytes,
        String engineModelType,
        String voiceFormat,
        int sampleRate
) {
}
