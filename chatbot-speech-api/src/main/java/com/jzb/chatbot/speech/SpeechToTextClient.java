package com.jzb.chatbot.speech;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * 语音转文本客户端边界。
 * <p>
 * 第一阶段只定义接口，具体 ASR Provider 后续接入。
 *
 * @author jiangzhibin
 * @since 2026-06-14 18:36:50
 */
public interface SpeechToTextClient {

    /**
     * 将音频帧转成文本。
     *
     * @param audioFrames 音频帧
     * @return 识别文本
     */
    String transcribe(List<ByteBuffer> audioFrames);
}
