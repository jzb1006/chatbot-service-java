package com.jzb.chatbot.voice.music;

/**
 * 小智音乐播放协作接口。
 * <p>
 * 暴露给 TTS runtime 的最小互斥能力，避免 TTS 依赖音乐播放实现细节。
 *
 * @author jiangzhibin
 * @since 2026-06-18 00:00:00
 */
public interface XiaozhiMusicPlaybackCoordinator {

    void pause(String deviceId, XiaozhiMusicPlaybackState.PauseSource source);

    void resume(String deviceId, XiaozhiMusicPlaybackState.PauseSource source);
}
