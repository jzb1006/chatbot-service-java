package com.jzb.chatbot.speech;

/**
 * 流式文本转语音客户端。
 * <p>
 * 为每轮 TTS 打开一个可写入文本、可回调音频帧的流式会话。
 *
 * @author jiangzhibin
 * @since 2026-06-18 12:47:00
 */
public interface StreamingTextToSpeechClient {

    /**
     * 判断当前 provider 是否可用。
     *
     * @return true 表示可打开流式 TTS 会话
     */
    default boolean available() {
        return true;
    }

    /**
     * 打开新的流式 TTS 会话。
     *
     * @param options 文本转语音参数
     * @param listener 流式回调监听器
     * @return 流式 TTS 会话
     */
    StreamingTextToSpeechSession open(TextToSpeechOptions options, StreamingTextToSpeechListener listener);
}
