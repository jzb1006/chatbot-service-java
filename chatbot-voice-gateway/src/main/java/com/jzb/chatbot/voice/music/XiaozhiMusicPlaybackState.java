package com.jzb.chatbot.voice.music;

/**
 * 小智音乐播放状态。
 * <p>
 * 记录单设备当前播放内容和暂停来源。
 *
 * @author jiangzhibin
 * @since 2026-06-18 00:00:00
 */
public record XiaozhiMusicPlaybackState(
        String deviceId,
        String title,
        String artist,
        Status status,
        PauseSource pauseSource,
        String requestId,
        String requestIdSource,
        String source
) {

    public XiaozhiMusicPlaybackState(
            String deviceId,
            String title,
            String artist,
            Status status,
            PauseSource pauseSource
    ) {
        this(deviceId, title, artist, status, pauseSource, null, null, null);
    }

    public enum Status {
        PLAYING,
        PAUSED,
        STOPPED
    }

    public enum PauseSource {
        MANUAL,
        TTS,
        CONTROL
    }
}
