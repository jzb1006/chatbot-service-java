package com.jzb.chatbot.speech;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EdgeStreamingTextToSpeechClientTest {

    @Test
    void shouldStreamRawPcmToOpusFrames() throws Exception {
        var transport = new CapturingStreamingEdgeTtsTransport();
        var client = new EdgeStreamingTextToSpeechClient(
                new EdgeTextToSpeechConfig(
                        "zh-CN-YunxiNeural",
                        "audio-24khz-48kbitrate-mono-mp3",
                        16000,
                        "ffmpeg",
                        Duration.ofSeconds(3)
                ),
                transport
        );
        var listener = new CapturingStreamingTextToSpeechListener();

        try (var session = client.open(TextToSpeechOptions.defaults(), listener)) {
            session.sendText("你好，志彬。");
            session.complete();

            assertThat(session.awaitFinal(Duration.ofSeconds(3))).isTrue();
        }

        assertThat(listener.awaitCompleted()).isTrue();
        assertThat(listener.frames).hasSize(2);
        assertThat(transport.request.text()).isEqualTo("你好，志彬。");
        assertThat(transport.request.voice()).isEqualTo("zh-CN-YunxiNeural");
        assertThat(transport.request.outputFormat()).isEqualTo("raw-16khz-16bit-mono-pcm");
    }

    private static byte[] silentPcmFrames(int frames) {
        var samples = 960 * frames;
        var pcm = ByteBuffer.allocate(samples * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (var index = 0; index < samples; index++) {
            pcm.putShort((short) 0);
        }
        return pcm.array();
    }

    private static class CapturingStreamingEdgeTtsTransport implements EdgeTtsTransport {

        private EdgeTtsRequest request;

        @Override
        public byte[] synthesize(EdgeTtsRequest request, Duration timeout) {
            throw new UnsupportedOperationException("streaming test should not call synthesize");
        }

        @Override
        public void stream(EdgeTtsRequest request, Duration timeout, StreamingListener listener) {
            this.request = request;
            var pcm = silentPcmFrames(2);
            listener.onAudio(pcm);
            listener.onCompleted();
        }
    }

    private static class CapturingStreamingTextToSpeechListener implements StreamingTextToSpeechListener {

        private final List<ByteBuffer> frames = new ArrayList<>();
        private final CountDownLatch completed = new CountDownLatch(1);

        @Override
        public void onReady() {
        }

        @Override
        public void onAudioFrame(ByteBuffer frame) {
            frames.add(frame);
        }

        @Override
        public void onCompleted() {
            completed.countDown();
        }

        @Override
        public void onFailed(RuntimeException exception) {
            completed.countDown();
        }

        private boolean awaitCompleted() throws InterruptedException {
            return completed.await(3, TimeUnit.SECONDS);
        }
    }
}
