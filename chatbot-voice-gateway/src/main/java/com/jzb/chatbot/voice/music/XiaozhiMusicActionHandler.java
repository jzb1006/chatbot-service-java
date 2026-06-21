package com.jzb.chatbot.voice.music;

import com.jzb.chatbot.voice.XiaozhiVoiceSession;
import com.jzb.chatbot.voice.hermes.HermesAgentEvent;
import org.springframework.web.socket.WebSocketSession;

/**
 * 小智音乐动作处理器。
 * <p>
 * 只执行 Hermes agent 返回的结构化音乐动作，不从自然语言中推断音乐意图。
 *
 * @author jiangzhibin
 * @since 2026-06-18 00:00:00
 */
public class XiaozhiMusicActionHandler {

    private final XiaozhiMusicPlaybackRuntime musicPlaybackRuntime;

    public XiaozhiMusicActionHandler(XiaozhiMusicPlaybackRuntime musicPlaybackRuntime) {
        this.musicPlaybackRuntime = musicPlaybackRuntime;
    }

    public boolean handle(WebSocketSession webSocketSession, XiaozhiVoiceSession voiceSession, HermesAgentEvent event) {
        if (musicPlaybackRuntime == null || event == null || event.action() == null || !event.action().startsWith("music_")) {
            return false;
        }
        var deviceId = voiceSession.deviceId();
        switch (event.action()) {
            case "music_play" -> musicPlaybackRuntime.playPausedForTts(new XiaozhiMusicPlaybackRequest(
                    webSocketSession,
                    voiceSession,
                    event.title(),
                    event.artist(),
                    event.mediaUrl(),
                    event.requestId(),
                    event.source()
            ));
            case "music_pause" -> musicPlaybackRuntime.pause(deviceId, XiaozhiMusicPlaybackState.PauseSource.MANUAL);
            case "music_resume" -> musicPlaybackRuntime.resume(
                    webSocketSession,
                    voiceSession,
                    XiaozhiMusicPlaybackState.PauseSource.MANUAL
            );
            case "music_stop" -> musicPlaybackRuntime.stop(deviceId);
            default -> {
                return false;
            }
        }
        return true;
    }
}
