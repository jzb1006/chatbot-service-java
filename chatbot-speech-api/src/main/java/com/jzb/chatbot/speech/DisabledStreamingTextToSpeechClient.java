package com.jzb.chatbot.speech;

/**
 * 未启用的流式 TTS 客户端。
 * <p>
 * 作为 Spring 默认 Bean 使用，避免未启用流式 provider 时注入 null。
 *
 * @author jiangzhibin
 * @since 2026-06-18 13:20:00
 */
public class DisabledStreamingTextToSpeechClient implements StreamingTextToSpeechClient {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public StreamingTextToSpeechSession open(
            TextToSpeechOptions options,
            StreamingTextToSpeechListener listener
    ) {
        throw new IllegalStateException("Streaming TTS provider is disabled");
    }
}
