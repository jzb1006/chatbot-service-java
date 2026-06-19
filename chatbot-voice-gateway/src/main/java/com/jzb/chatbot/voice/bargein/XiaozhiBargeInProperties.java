package com.jzb.chatbot.voice.bargein;

import java.time.Duration;

/**
 * 小智播放期打断配置。
 * <p>
 * 第一版默认关闭，通过阈值控制误触发风险。
 *
 * @author jiangzhibin
 * @since 2026-06-19 14:46:00
 */
public record XiaozhiBargeInProperties(
        boolean enabled,
        int minTextLength,
        long cooldownMs,
        double similarityThreshold,
        Duration asrTimeout
) {
}
