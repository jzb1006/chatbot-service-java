package com.jzb.chatbot.voice;

import java.time.Duration;

/**
 * Auto-stop settings for Xiaozhi auto listening mode.
 */
public record XiaozhiAutoStopProperties(
        boolean enabled,
        Duration minSpeechDuration,
        Duration silenceDuration,
        double speechRmsThreshold,
        Duration noSpeechTimeout,
        Duration maxDuration
) {

    public XiaozhiAutoStopProperties(
            boolean enabled,
            Duration minSpeechDuration,
            Duration silenceDuration,
            double speechRmsThreshold
    ) {
        this(
                enabled,
                minSpeechDuration,
                silenceDuration,
                speechRmsThreshold,
                Duration.ofSeconds(6),
                Duration.ofSeconds(15)
        );
    }

    public XiaozhiAutoStopProperties {
        minSpeechDuration = normalize(minSpeechDuration, Duration.ofMillis(180));
        silenceDuration = normalize(silenceDuration, Duration.ofMillis(900));
        noSpeechTimeout = normalize(noSpeechTimeout, Duration.ofSeconds(6));
        maxDuration = normalize(maxDuration, Duration.ofSeconds(15));
        if (maxDuration.compareTo(noSpeechTimeout) < 0) {
            maxDuration = noSpeechTimeout;
        }
        if (speechRmsThreshold <= 0 || Double.isNaN(speechRmsThreshold) || Double.isInfinite(speechRmsThreshold)) {
            speechRmsThreshold = 0.01;
        }
    }

    public static XiaozhiAutoStopProperties defaults() {
        return new XiaozhiAutoStopProperties(
                true,
                Duration.ofMillis(180),
                Duration.ofMillis(900),
                0.01,
                Duration.ofSeconds(6),
                Duration.ofSeconds(15)
        );
    }

    private static Duration normalize(Duration value, Duration fallback) {
        if (value == null || value.isNegative() || value.isZero()) {
            return fallback;
        }
        return value;
    }
}
