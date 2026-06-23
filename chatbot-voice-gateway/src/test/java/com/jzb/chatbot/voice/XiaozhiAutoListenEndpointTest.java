package com.jzb.chatbot.voice;

import static org.assertj.core.api.Assertions.assertThat;

import com.jzb.chatbot.speech.StreamingPcmToOpusEncoder;
import com.jzb.chatbot.voice.protocol.XiaozhiAudioFrame;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class XiaozhiAutoListenEndpointTest {

    @Test
    void shouldDetectEndAfterSpeechThenSilence() {
        var endpoint = new XiaozhiAutoListenEndpoint(
                16000,
                60,
                new XiaozhiAutoStopProperties(true, Duration.ofMillis(120), Duration.ofMillis(900), 0.01)
        );
        var results = speechThenSilenceOpusFrames().stream()
                .map(frame -> endpoint.accept(new XiaozhiAudioFrame(1, 0, toBytes(frame))))
                .toList();

        assertThat(results).contains(XiaozhiAutoListenEndpoint.Result.END_OF_UTTERANCE);
    }

    private List<ByteBuffer> speechThenSilenceOpusFrames() {
        var encoder = new StreamingPcmToOpusEncoder(16000, 60);
        var frames = new ArrayList<ByteBuffer>();
        for (var index = 0; index < 3; index++) {
            frames.addAll(encoder.accept(tonePcmFrame(index)));
        }
        for (var index = 0; index < 20; index++) {
            frames.addAll(encoder.accept(silencePcmFrame()));
        }
        frames.addAll(encoder.flush());
        return frames.stream().map(ByteBuffer::slice).toList();
    }

    private byte[] tonePcmFrame(int frameIndex) {
        var pcm = ByteBuffer.allocate(960 * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (var index = 0; index < 960; index++) {
            var sampleIndex = frameIndex * 960 + index;
            pcm.putShort((short) (12000 * Math.sin(2 * Math.PI * 440 * sampleIndex / 16000)));
        }
        return pcm.array();
    }

    private byte[] silencePcmFrame() {
        return new byte[960 * Short.BYTES];
    }

    private byte[] toBytes(ByteBuffer buffer) {
        var input = buffer.slice();
        var bytes = new byte[input.remaining()];
        input.get(bytes);
        return bytes;
    }
}
