package com.jzb.chatbot.speech;

import com.jzb.chatbot.common.id.VoiceId;

/**
 * 文本转语音参数。
 * <p>
 * 封装音色、语速和音调等 TTS 调用参数，供新调用链逐步迁移使用。
 *
 * @author jiangzhibin
 * @since 2026-06-17 21:33:00
 */
public record TextToSpeechOptions(VoiceId voiceId, double speed, double pitch) {

    public TextToSpeechOptions {
        if (voiceId == null) {
            voiceId = new VoiceId("default");
        }
        if (!Double.isFinite(speed) || speed <= 0) {
            throw new IllegalArgumentException("speed must be positive");
        }
        if (!Double.isFinite(pitch) || pitch <= 0) {
            throw new IllegalArgumentException("pitch must be positive");
        }
    }

    /**
     * 创建默认文本转语音参数。
     *
     * @return 默认参数
     */
    public static TextToSpeechOptions defaults() {
        return new TextToSpeechOptions(new VoiceId("default"), 1.0, 1.0);
    }
}
