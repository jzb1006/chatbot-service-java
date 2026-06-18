package com.jzb.chatbot.voice.music;

import java.time.Duration;
import java.util.Set;

/**
 * 小智音乐播放配置。
 * <p>
 * 控制音乐播放开关、ffmpeg 路径、超时和允许访问的媒体域名。
 *
 * @author jiangzhibin
 * @since 2026-06-18 00:00:00
 */
public record XiaozhiMusicPlaybackProperties(
        boolean enabled,
        String ffmpegPath,
        Duration connectTimeout,
        Duration maxDuration,
        Set<String> allowedHosts
) {
}
