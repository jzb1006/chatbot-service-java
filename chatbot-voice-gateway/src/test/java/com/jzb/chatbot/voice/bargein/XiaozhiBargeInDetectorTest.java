package com.jzb.chatbot.voice.bargein;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class XiaozhiBargeInDetectorTest {

    private final XiaozhiBargeInDetector detector = new XiaozhiBargeInDetector(
            new XiaozhiBargeInProperties(true, 2, 500, 0.82, Duration.ofSeconds(2))
    );

    @Test
    void shouldInterruptForNonEmptyUserSpeechAfterCooldown() {
        var decision = detector.decide("等一下", "今天天气晴朗，适合出门。", 800);

        assertThat(decision.interrupt()).isTrue();
        assertThat(decision.reason()).isEqualTo("user_speech_detected");
    }

    @Test
    void shouldIgnoreBlankText() {
        var decision = detector.decide("   ", "今天天气晴朗。", 800);

        assertThat(decision.interrupt()).isFalse();
        assertThat(decision.reason()).isEqualTo("blank_text");
    }

    @Test
    void shouldIgnoreTextDuringCooldown() {
        var decision = detector.decide("等一下", "今天天气晴朗。", 200);

        assertThat(decision.interrupt()).isFalse();
        assertThat(decision.reason()).isEqualTo("cooldown");
    }

    @Test
    void shouldIgnoreEchoLikeText() {
        var decision = detector.decide("今天天气晴朗适合出门", "今天天气晴朗，适合出门。", 800);

        assertThat(decision.interrupt()).isFalse();
        assertThat(decision.reason()).isEqualTo("echo_like_text");
    }
}
