package com.jzb.chatbot.voice.tts;

import com.jzb.chatbot.speech.TextToSpeechOptions;
import com.jzb.chatbot.voice.XiaozhiVoiceSession;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.springframework.web.socket.WebSocketSession;

/**
 * 小智 TTS 播放请求。
 * <p>
 * 封装一次轻量 TTS 播放所需的 WebSocket 会话、语音会话、待播句子和合成参数。
 *
 * @author jiangzhibin
 * @since 2026-06-17 22:12:00
 */
public record XiaozhiTtsRequest(
        WebSocketSession webSocketSession,
        XiaozhiVoiceSession voiceSession,
        List<String> sentences,
        TextToSpeechOptions options,
        Long expectedPlaybackGeneration,
        BooleanSupplier cancellationRequested
) {

    public XiaozhiTtsRequest(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            List<String> sentences,
            TextToSpeechOptions options
    ) {
        this(webSocketSession, voiceSession, sentences, options, null, () -> false);
    }

    public XiaozhiTtsRequest(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            List<String> sentences,
            TextToSpeechOptions options,
            BooleanSupplier cancellationRequested
    ) {
        this(webSocketSession, voiceSession, sentences, options, null, cancellationRequested);
    }

    public XiaozhiTtsRequest {
        if (webSocketSession == null) {
            throw new IllegalArgumentException("webSocketSession must not be null");
        }
        if (voiceSession == null) {
            throw new IllegalArgumentException("voiceSession must not be null");
        }
        sentences = sentences == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(sentences));
        options = options == null ? TextToSpeechOptions.defaults() : options;
        cancellationRequested = cancellationRequested == null ? () -> false : cancellationRequested;
    }
}
