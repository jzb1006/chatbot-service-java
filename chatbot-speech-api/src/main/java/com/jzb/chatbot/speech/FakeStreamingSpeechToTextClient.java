package com.jzb.chatbot.speech;

import java.time.Duration;

/**
 * Fake 流式语音识别客户端。
 * <p>
 * 用于本地和默认配置下的实时 ASR 占位实现，消费完输入音频后返回配置文本。
 *
 * @author jiangzhibin
 * @since 2026-06-18 00:00:00
 */
public class FakeStreamingSpeechToTextClient implements StreamingSpeechToTextClient {

    private static final Duration DRAIN_TIMEOUT = Duration.ofMillis(100);
    private static final int MAX_CONSECUTIVE_TIMEOUTS = 2;

    private final String text;

    public FakeStreamingSpeechToTextClient() {
        this("ping");
    }

    public FakeStreamingSpeechToTextClient(String text) {
        this.text = text == null || text.isBlank() ? "ping" : text;
    }

    @Override
    public SpeechToTextResult transcribe(SpeechToTextAudioStream audioStream) {
        if (audioStream != null) {
            drain(audioStream);
        }
        return new SpeechToTextResult(text, "fake", 0);
    }

    private void drain(SpeechToTextAudioStream audioStream) {
        var consecutiveTimeouts = 0;
        while (true) {
            var chunk = audioStream.take(DRAIN_TIMEOUT);
            if (audioStream.isEnd(chunk)) {
                return;
            }
            if (chunk.length == 0 && ++consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
                return;
            }
            if (chunk.length > 0) {
                consecutiveTimeouts = 0;
            }
        }
    }
}
