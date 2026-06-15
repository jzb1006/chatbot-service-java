package com.jzb.chatbot.speech;

/**
 * 腾讯云 TextToVoice 请求。
 * <p>
 * 仅保留当前语音网关所需的最小参数。
 *
 * @author jiangzhibin
 * @since 2026-06-15 17:25:00
 */
record TencentTextToVoiceRequest(
        String text,
        String voiceType,
        String codec,
        int sampleRate
) {

    TencentTextToVoiceRequest {
        if (voiceType == null || !voiceType.matches("\\d+")) {
            throw new IllegalArgumentException("Tencent Cloud TTS voice-type must be numeric");
        }
    }
}
