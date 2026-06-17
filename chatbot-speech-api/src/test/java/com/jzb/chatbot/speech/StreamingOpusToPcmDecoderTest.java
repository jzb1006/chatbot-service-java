package com.jzb.chatbot.speech;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class StreamingOpusToPcmDecoderTest {

    @Test
    void shouldDecodeSingleXiaozhiOpusFrameToPcm() throws Exception {
        var decoder = new StreamingOpusToPcmDecoder(16000);

        var pcm = decoder.decode(ByteBuffer.wrap(encodeOpusFrame()));

        assertThat(pcm).hasSize(960 * Short.BYTES);
        assertThat(Arrays.equals(pcm, new byte[pcm.length])).isFalse();
    }

    private byte[] encodeOpusFrame() throws Exception {
        var samples = new short[960];
        for (var index = 0; index < samples.length; index++) {
            samples[index] = (short) (Math.sin(index / 8.0) * 6_000);
        }
        var encoder = new OpusEncoder(16000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        var output = new byte[4096];
        var encodedBytes = encoder.encode(samples, 0, samples.length, output, 0, output.length);
        return Arrays.copyOf(output, encodedBytes);
    }
}
