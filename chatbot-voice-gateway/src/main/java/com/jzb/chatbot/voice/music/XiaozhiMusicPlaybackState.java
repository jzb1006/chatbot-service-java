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
        PauseSource pauseSource
) {

    public enum Status {
        PLAYING,
        PAUSED,
        STOPPED
    }

    public enum PauseSource {
        MANUAL,
        TTS
    }
}
