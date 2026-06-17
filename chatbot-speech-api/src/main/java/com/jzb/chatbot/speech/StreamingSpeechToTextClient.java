package com.jzb.chatbot.speech;

/**
 * 流式语音识别客户端边界。
 * <p>
 * Provider 消费 PCM 音频流并返回文本结果。
 *
 * @author jiangzhibin
 * @since 2026-06-17 00:00:00
 */
public interface StreamingSpeechToTextClient {

    SpeechToTextResult transcribe(SpeechToTextAudioStream audioStream);
}
