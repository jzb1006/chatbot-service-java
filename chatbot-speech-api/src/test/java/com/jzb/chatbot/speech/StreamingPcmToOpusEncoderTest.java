package com.jzb.chatbot.speech;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusSignal;
import org.junit.jupiter.api.Test;

class StreamingPcmToOpusEncoderTest {

    @Test
    void shouldEmitOneOpusFrameWhenSixtyMillisecondsPcmArrives() {
        var encoder = new StreamingPcmToOpusEncoder(16000, 60);
        var pcm = ByteBuffer.allocate(960 * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (var index = 0; index < 960; index++) {
            pcm.putShort((short) 0);
        }

        var frames = encoder.accept(pcm.array());

        assertThat(frames).hasSize(1);
        assertThat(frames.getFirst().remaining()).isGreaterThan(0);
        assertThat(encoder.flush()).isEmpty();
    }

    @Test
    void shouldBufferPartialPcmUntilFrameIsComplete() {
        var encoder = new StreamingPcmToOpusEncoder(16000, 60);
        var halfFrame = new byte[480 * Short.BYTES];

        assertThat(encoder.accept(halfFrame)).isEmpty();
        assertThat(encoder.accept(halfFrame)).hasSize(1);
    }

    @Test
    void shouldPadRemainingPcmWhenFlushed() {
        var encoder = new StreamingPcmToOpusEncoder(16000, 60);
        var partial = new byte[120 * Short.BYTES];

        assertThat(encoder.accept(partial)).isEmpty();
        assertThat(encoder.flush()).hasSize(1);
        assertThat(encoder.flush()).isEmpty();
    }

    @Test
    void shouldApplyMusicOpusProfileOptions() {
        var options = new StreamingPcmToOpusEncoder.Options(
                OpusApplication.OPUS_APPLICATION_AUDIO,
                OpusSignal.OPUS_SIGNAL_MUSIC,
                64000,
                10,
                true
        );
        var encoder = new StreamingPcmToOpusEncoder(16000, 60, options);
        var pcm = ByteBuffer.allocate(960 * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);

        assertThat(encoder.options()).isEqualTo(options);
        assertThat(encoder.accept(pcm.array())).hasSize(1);
    }
}
