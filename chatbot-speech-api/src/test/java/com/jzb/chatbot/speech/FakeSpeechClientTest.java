package com.jzb.chatbot.speech;

import static org.assertj.core.api.Assertions.assertThat;

import com.jzb.chatbot.common.id.VoiceId;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

class FakeSpeechClientTest {

    @Test
    void shouldReturnFixedTextForAnyAudioFrames() {
        var client = new FakeSpeechToTextClient();

        var text = client.transcribe(List.of(
                ByteBuffer.wrap(new byte[] {1, 2, 3})
        ));

        assertThat(text).isEqualTo("ping");
    }

    @Test
    void shouldReturnDeterministicAudioFrames() {
        var client = new FakeTextToSpeechClient();

        var frames = client.synthesize("pong", new VoiceId("default"));

        assertThat(frames).hasSize(1);
        assertThat(frames.getFirst().remaining()).isGreaterThan(0);
    }

    @Test
    void shouldKeepVoiceIdSynthesizeCallCompatible() {
        var client = new FakeTextToSpeechClient();

        var frames = client.synthesize("pong", new VoiceId("default"));

        assertThat(frames).isNotEmpty();
    }

    @Test
    void shouldSynthesizeWithDefaultOptionsForOldOnlyClient() {
        var client = new RecordingTextToSpeechClient();

        var frames = client.synthesize("pong", TextToSpeechOptions.defaults());

        assertThat(frames).isNotEmpty();
        assertThat(client.voiceId).isEqualTo(new VoiceId("default"));
    }

    @Test
    void shouldUseDefaultsForTypedNullOptions() {
        var client = new RecordingTextToSpeechClient();

        var frames = client.synthesize("pong", (TextToSpeechOptions) null);

        assertThat(frames).isNotEmpty();
        assertThat(client.voiceId).isEqualTo(new VoiceId("default"));
    }

    private static class RecordingTextToSpeechClient implements TextToSpeechClient {

        private VoiceId voiceId;

        @Override
        public List<ByteBuffer> synthesize(String text, VoiceId voiceId) {
            this.voiceId = voiceId;
            return List.of(ByteBuffer.wrap(text.getBytes()));
        }
    }
}
