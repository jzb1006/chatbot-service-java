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
        Set<String> allowedHosts,
        int sampleRate,
        int frameDurationMs,
        int bitrateBps,
        int complexity
) {

    public static final int DEFAULT_SAMPLE_RATE = 16000;
    public static final int DEFAULT_FRAME_DURATION_MS = 60;
    public static final int DEFAULT_BITRATE_BPS = 64000;
    public static final int DEFAULT_COMPLEXITY = 10;

    public XiaozhiMusicPlaybackProperties(
            boolean enabled,
            String ffmpegPath,
            Duration connectTimeout,
            Duration maxDuration,
            Set<String> allowedHosts
    ) {
        this(
                enabled,
                ffmpegPath,
                connectTimeout,
                maxDuration,
                allowedHosts,
                DEFAULT_SAMPLE_RATE,
                DEFAULT_FRAME_DURATION_MS,
                DEFAULT_BITRATE_BPS,
                DEFAULT_COMPLEXITY
        );
    }

    public XiaozhiMusicPlaybackProperties {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        if (frameDurationMs <= 0) {
            throw new IllegalArgumentException("frameDurationMs must be positive");
        }
        if (bitrateBps <= 0) {
            throw new IllegalArgumentException("bitrateBps must be positive");
        }
        complexity = Math.max(0, Math.min(complexity, 10));
        allowedHosts = allowedHosts == null ? Set.of() : Set.copyOf(allowedHosts);
    }
}
