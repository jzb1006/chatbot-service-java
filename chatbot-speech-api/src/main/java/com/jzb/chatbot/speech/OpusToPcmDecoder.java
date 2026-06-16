package com.jzb.chatbot.speech;

import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Opus 到 PCM 解码器。
 * <p>
 * 将小智上行的裸 Opus 帧解码为腾讯云一句话识别支持的 16-bit little-endian PCM。
 *
 * @author jiangzhibin
 * @since 2026-06-16 09:02:00
 */
public final class OpusToPcmDecoder {

    private static final int CHANNELS = 1;
    private static final int FRAME_DURATION_MS = 60;

    private OpusToPcmDecoder() {
    }

    /**
     * 解码 Opus 帧列表。
     *
     * @param opusFrames 小智上行 Opus 帧
     * @param sampleRate 采样率
     * @return 16-bit little-endian PCM
     */
    public static byte[] decode(List<ByteBuffer> opusFrames, int sampleRate) {
        if (opusFrames == null || opusFrames.isEmpty()) {
            return new byte[0];
        }
        try {
            var decoder = new OpusDecoder(sampleRate, CHANNELS);
            var frameSamples = sampleRate * FRAME_DURATION_MS / 1000;
            var output = new ByteArrayOutputStream(opusFrames.size() * frameSamples * Short.BYTES);
            for (var frame : opusFrames) {
                if (frame == null || !frame.hasRemaining()) {
                    continue;
                }
                decodeFrame(decoder, frame, frameSamples, output);
            }
            return output.toByteArray();
        } catch (OpusException exception) {
            throw new IllegalStateException("Failed to decode Opus to PCM", exception);
        }
    }

    private static void decodeFrame(
            OpusDecoder decoder,
            ByteBuffer frame,
            int frameSamples,
            ByteArrayOutputStream output
    ) throws OpusException {
        var input = frame.slice();
        var opus = new byte[input.remaining()];
        input.get(opus);
        var pcm = new short[frameSamples];
        var decodedSamples = decoder.decode(opus, 0, opus.length, pcm, 0, frameSamples, false);
        var bytes = ByteBuffer.allocate(decodedSamples * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (var index = 0; index < decodedSamples; index++) {
            bytes.putShort(pcm[index]);
        }
        output.writeBytes(bytes.array());
    }
}
