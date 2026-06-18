package com.jzb.chatbot.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jzb.chatbot.common.id.VoiceId;
import com.jzb.chatbot.voice.tts.XiaozhiVoiceProfile;
import com.jzb.chatbot.voice.tts.XiaozhiVoiceProfileResolver;
import org.junit.jupiter.api.Test;

class XiaozhiVoiceProfileResolverTest {

    @Test
    void shouldResolveDefaultVoiceProfile() {
        var resolver = new XiaozhiVoiceProfileResolver(new VoiceId("default"), 1.0, 1.0);

        var profile = resolver.resolve("device-1");

        assertThat(profile.voiceId()).isEqualTo(new VoiceId("default"));
        assertThat(profile.speed()).isEqualTo(1.0);
        assertThat(profile.pitch()).isEqualTo(1.0);
    }

    @Test
    void shouldConvertProfileToTextToSpeechOptions() {
        var resolver = new XiaozhiVoiceProfileResolver(new VoiceId("xiaozhi"), 1.1, 0.9);

        var options = resolver.resolve("device-1").toTtsOptions();

        assertThat(options.voiceId()).isEqualTo(new VoiceId("xiaozhi"));
        assertThat(options.speed()).isEqualTo(1.1);
        assertThat(options.pitch()).isEqualTo(0.9);
    }

    @Test
    void shouldNormalizeNullVoiceIdToDefault() {
        var profile = new XiaozhiVoiceProfile(null, 1.0, 1.0);

        assertThat(profile.voiceId()).isEqualTo(new VoiceId("default"));
    }

    @Test
    void shouldRejectInvalidSpeedOnCreate() {
        assertThatThrownBy(() -> new XiaozhiVoiceProfile(new VoiceId("default"), 0, 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("speed must be positive");
    }

    @Test
    void shouldRejectInvalidPitchOnCreate() {
        assertThatThrownBy(() -> new XiaozhiVoiceProfile(new VoiceId("default"), 1.0, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("pitch must be positive");
    }
}
