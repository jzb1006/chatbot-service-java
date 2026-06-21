package com.jzb.chatbot.voice.music;

import com.jzb.chatbot.voice.XiaozhiVoiceSession;
import java.util.UUID;
import org.springframework.web.socket.WebSocketSession;

/**
 * 小智音乐播放请求。
 * <p>
 * 封装一次由 Hermes 结构化事件触发的音乐播放动作。
 *
 * @author jiangzhibin
 * @since 2026-06-18 00:00:00
 */
public record XiaozhiMusicPlaybackRequest(
        WebSocketSession webSocketSession,
        XiaozhiVoiceSession voiceSession,
        String title,
        String artist,
        String mediaUrl,
        String requestId,
        String source
) {

    public XiaozhiMusicPlaybackRequest {
        if (requestId == null || requestId.isBlank()) {
            requestId = "local-" + UUID.randomUUID();
        }
    }

    public XiaozhiMusicPlaybackRequest(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            String title,
            String artist,
            String mediaUrl
    ) {
        this(webSocketSession, voiceSession, title, artist, mediaUrl, null, null);
    }

    public String requestIdSource() {
        return requestId.startsWith("local-") ? "generated" : "hermes";
    }
}
