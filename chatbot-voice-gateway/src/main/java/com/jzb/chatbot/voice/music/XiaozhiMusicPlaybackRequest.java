package com.jzb.chatbot.voice.music;

import com.jzb.chatbot.voice.XiaozhiVoiceSession;
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
        String mediaUrl
) {
}
