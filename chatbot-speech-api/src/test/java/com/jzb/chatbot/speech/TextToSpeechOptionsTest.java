package com.jzb.chatbot.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jzb.chatbot.common.id.VoiceId;
import org.junit.jupiter.api.Test;

class TextToSpeechOptionsTest {

    @Test
    void shouldUseDefaultVoiceOptions() {
        var options = TextToSpeechOptions.defaults();

        assertThat(options.voiceId()).isEqualTo(new VoiceId("default"));
        assertThat(options.speed()).isEqualTo(1.0);
        assertThat(options.pitch()).isEqualTo(1.0);
    }

    @Test
    void shouldNormalizeNullVoiceIdToDefault() {
        var options = new TextToSpeechOptions(null, 1.0, 1.0);

        assertThat(options.voiceId()).isEqualTo(new VoiceId("default"));
    }

    @Test
    void shouldRejectNonPositiveSpeed() {
        assertThatThrownBy(() -> new TextToSpeechOptions(new VoiceId("default"), 0, 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("speed must be positive");
    }

    @Test
    void shouldRejectNonPositivePitch() {
        assertThatThrownBy(() -> new TextToSpeechOptions(new VoiceId("default"), 1.0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("pitch must be positive");
    }

    @Test
    void shouldRejectNaNSpeed() {
        assertThatThrownBy(() -> new TextToSpeechOptions(new VoiceId("default"), Double.NaN, 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("speed must be positive");
    }

    @Test
    void shouldRejectInfiniteSpeed() {
        assertThatThrownBy(() -> new TextToSpeechOptions(new VoiceId("default"), Double.POSITIVE_INFINITY, 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("speed must be positive");
    }

    @Test
    void shouldRejectNaNPitch() {
        assertThatThrownBy(() -> new TextToSpeechOptions(new VoiceId("default"), 1.0, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("pitch must be positive");
    }

    @Test
    void shouldRejectInfinitePitch() {
        assertThatThrownBy(() -> new TextToSpeechOptions(new VoiceId("default"), 1.0, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("pitch must be positive");
    }
}
