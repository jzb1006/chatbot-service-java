package com.jzb.chatbot.speech;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class FakeStreamingSpeechToTextClientTest {

    @Test
    void shouldDrainCompletedAudioStreamAndReturnConfiguredText() throws Exception {
        var client = new FakeStreamingSpeechToTextClient("configured text");
        var audioStream = new SpeechToTextAudioStream();

        var resultFuture = CompletableFuture.supplyAsync(() -> client.transcribe(audioStream));
        audioStream.write(new byte[] {1, 2, 3});
        audioStream.complete();

        var result = resultFuture.get(500, TimeUnit.MILLISECONDS);

        assertThat(result.text()).isEqualTo("configured text");
        assertThat(result.provider()).isEqualTo("fake");
    }

    @Test
    void shouldReturnPromptlyWhenAudioStreamIsNotCompleted() throws Exception {
        var client = new FakeStreamingSpeechToTextClient();
        var audioStream = new SpeechToTextAudioStream();

        var result = CompletableFuture
                .supplyAsync(() -> client.transcribe(audioStream))
                .get(500, TimeUnit.MILLISECONDS);

        assertThat(result.text()).isEqualTo("ping");
        assertThat(result.provider()).isEqualTo("fake");
    }
}
