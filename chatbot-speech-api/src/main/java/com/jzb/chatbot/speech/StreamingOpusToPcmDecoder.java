package com.jzb.chatbot.speech;

import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 会话级 Opus 到 PCM 解码器。
 * <p>
 * 每个语音会话持有一个实例，保持 Opus decoder 状态连续。
 *
 * @author jiangzhibin
 * @since 2026-06-17 00:00:00
 */
public final class StreamingOpusToPcmDecoder {

    private static final int CHANNELS = 1;
    private static final int FRAME_DURATION_MS = 60;

    private final OpusDecoder decoder;
    private final int frameSamples;

    public StreamingOpusToPcmDecoder(int sampleRate) {
        try {
            this.decoder = new OpusDecoder(sampleRate, CHANNELS);
            this.frameSamples = sampleRate * FRAME_DURATION_MS / 1000;
        } catch (OpusException exception) {
            throw new IllegalStateException("Failed to create Opus decoder", exception);
        }
    }

    public byte[] decode(ByteBuffer frame) {
        if (frame == null || !frame.hasRemaining()) {
            return new byte[0];
        }
        try {
            var input = frame.slice();
            var opus = new byte[input.remaining()];
            input.get(opus);
            var pcm = new short[frameSamples];
            var decodedSamples = decoder.decode(opus, 0, opus.length, pcm, 0, frameSamples, false);
            var bytes = ByteBuffer.allocate(decodedSamples * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            for (var index = 0; index < decodedSamples; index++) {
                bytes.putShort(pcm[index]);
            }
            return bytes.array();
        } catch (OpusException exception) {
            throw new IllegalStateException("Failed to decode Opus frame", exception);
        }
    }
}
