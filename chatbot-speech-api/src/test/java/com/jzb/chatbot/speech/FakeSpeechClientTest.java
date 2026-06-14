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
}
