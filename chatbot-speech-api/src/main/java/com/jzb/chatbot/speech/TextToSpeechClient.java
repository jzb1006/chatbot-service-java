package com.jzb.chatbot.speech;

import com.jzb.chatbot.common.id.VoiceId;
import java.nio.ByteBuffer;

/**
 * 文本转语音客户端边界。
 * <p>
 * 第一阶段只定义接口，具体 TTS Provider 后续接入。
 *
 * @author jiangzhibin
 * @since 2026-06-14 18:36:50
 */
public interface TextToSpeechClient {

    /**
     * 将文本合成为音频数据。
     *
     * @param text 文本内容
     * @param voiceId 音色标识
     * @return 音频数据
     */
    ByteBuffer synthesize(String text, VoiceId voiceId);
}
