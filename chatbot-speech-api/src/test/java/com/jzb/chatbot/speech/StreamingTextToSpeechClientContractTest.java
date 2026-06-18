package com.jzb.chatbot.speech;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StreamingTextToSpeechClientContractTest {

    @Test
    void shouldExposeMinimalStreamingSessionContractWithOpusFrames() {
        var listener = new RecordingListener();
        StreamingTextToSpeechClient client = (options, callback) -> new InMemorySession(callback);

        try (var session = client.open(TextToSpeechOptions.defaults(), listener)) {
            session.sendText("第一句。");
            session.complete();
            assertThat(session.awaitFinal(Duration.ofMillis(10))).isTrue();
        }

        assertThat(listener.ready).isTrue();
        assertThat(listener.frames).hasSize(1);
        assertThat(listener.frames.getFirst().remaining()).isGreaterThan(0);
        assertThat(listener.completed).isTrue();
    }

    private static final class InMemorySession implements StreamingTextToSpeechSession {

        private final StreamingTextToSpeechListener listener;
        private boolean completed;

        private InMemorySession(StreamingTextToSpeechListener listener) {
            this.listener = listener;
            this.listener.onReady();
        }

        @Override
        public void sendText(String text) {
            listener.onAudioFrame(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        }

        @Override
        public void complete() {
            completed = true;
            listener.onCompleted();
        }

        @Override
        public void cancel() {
            completed = true;
        }

        @Override
        public boolean awaitFinal(Duration timeout) {
            return completed;
        }

        @Override
        public void close() {
            cancel();
        }
    }

    private static final class RecordingListener implements StreamingTextToSpeechListener {

        private boolean ready;
        private boolean completed;
        private final List<ByteBuffer> frames = new ArrayList<>();

        @Override
        public void onReady() {
            ready = true;
        }

        @Override
        public void onAudioFrame(ByteBuffer frame) {
            frames.add(frame);
        }

        @Override
        public void onCompleted() {
            completed = true;
        }

        @Override
        public void onFailed(RuntimeException exception) {
            throw exception;
        }
    }
}
