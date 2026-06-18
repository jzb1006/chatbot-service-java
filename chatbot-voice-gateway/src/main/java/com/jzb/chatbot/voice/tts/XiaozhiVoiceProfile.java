package com.jzb.chatbot.voice.tts;

import com.jzb.chatbot.common.id.VoiceId;
import com.jzb.chatbot.speech.TextToSpeechOptions;

/**
 * 小智设备语音配置。
 * <p>
 * 封装设备侧默认音色、语速和音调，作为语音网关调用 TTS 前的稳定参数边界。
 *
 * @author jiangzhibin
 * @since 2026-06-17 21:49:00
 */
public record XiaozhiVoiceProfile(VoiceId voiceId, double speed, double pitch) {

    public XiaozhiVoiceProfile {
        var options = new TextToSpeechOptions(voiceId, speed, pitch);
        voiceId = options.voiceId();
        speed = options.speed();
        pitch = options.pitch();
    }

    /**
     * 转换为文本转语音调用参数。
     *
     * @return 文本转语音调用参数
     */
    public TextToSpeechOptions toTtsOptions() {
        return new TextToSpeechOptions(voiceId, speed, pitch);
    }
}
