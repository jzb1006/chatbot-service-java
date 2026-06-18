package com.jzb.chatbot.voice.tts;

import com.jzb.chatbot.speech.TextToSpeechOptions;
import com.jzb.chatbot.voice.XiaozhiVoiceSession;
import java.util.function.BooleanSupplier;
import org.springframework.web.socket.WebSocketSession;

/**
 * 小智流式 TTS 播放请求。
 * <p>
 * 封装一次流式播放所需的 WebSocket 会话、语音会话、合成参数和取消条件。
 *
 * @author jiangzhibin
 * @since 2026-06-18 12:58:00
 */
public record XiaozhiStreamingTtsRequest(
        WebSocketSession webSocketSession,
        XiaozhiVoiceSession voiceSession,
        TextToSpeechOptions options,
        Long expectedPlaybackGeneration,
        BooleanSupplier cancellationRequested
) {

    public XiaozhiStreamingTtsRequest(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            TextToSpeechOptions options,
            BooleanSupplier cancellationRequested
    ) {
        this(webSocketSession, voiceSession, options, null, cancellationRequested);
    }

    public XiaozhiStreamingTtsRequest {
        if (webSocketSession == null) {
            throw new IllegalArgumentException("webSocketSession must not be null");
        }
        if (voiceSession == null) {
            throw new IllegalArgumentException("voiceSession must not be null");
        }
        options = options == null ? TextToSpeechOptions.defaults() : options;
        cancellationRequested = cancellationRequested == null ? () -> false : cancellationRequested;
    }
}
