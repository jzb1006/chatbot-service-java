package com.jzb.chatbot.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SpeechToTextAudioStreamTest {

    @Test
    void shouldReadWrittenPcmChunksAndThenEnd() {
        var stream = new SpeechToTextAudioStream();

        stream.write(new byte[] {1, 2});
        stream.complete();

        var first = stream.take(Duration.ofMillis(100));
        var second = stream.take(Duration.ofMillis(100));

        assertThat(first).containsExactly(1, 2);
        assertThat(stream.isEnd(second)).isTrue();
    }

    @Test
    void shouldIgnoreWritesAfterComplete() {
        var stream = new SpeechToTextAudioStream();

        stream.complete();
        stream.write(new byte[] {9});

        assertThat(stream.isEnd(stream.take(Duration.ofMillis(100)))).isTrue();
        assertThat(stream.isEnd(stream.take(Duration.ofMillis(10)))).isTrue();
    }

    @Test
    void shouldReturnEmptyChunkOnTimeoutWithoutEndingStream() {
        var stream = new SpeechToTextAudioStream();

        var timeoutChunk = stream.take(Duration.ofMillis(1));
        stream.write(new byte[] {3, 4});
        stream.complete();

        assertThat(timeoutChunk).isEmpty();
        assertThat(stream.isEnd(timeoutChunk)).isFalse();
        assertThat(stream.take(Duration.ofMillis(100))).containsExactly(3, 4);
        assertThat(stream.isEnd(stream.take(Duration.ofMillis(100)))).isTrue();
    }

    @Test
    void shouldRejectNonPositiveTimeout() {
        var stream = new SpeechToTextAudioStream();

        assertThatThrownBy(() -> stream.take(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("timeout must be positive");
    }
}
