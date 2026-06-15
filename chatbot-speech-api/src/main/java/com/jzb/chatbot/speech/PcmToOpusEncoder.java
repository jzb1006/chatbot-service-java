package com.jzb.chatbot.speech;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;

/**
 * PCM 到 Opus 编码器。
 * <p>
 * 将腾讯云返回的 16-bit little-endian PCM 转换为小智 WebSocket 可发送的 Opus 帧。
 *
 * @author jiangzhibin
 * @since 2026-06-15 16:55:00
 */
public final class PcmToOpusEncoder {

    private static final int CHANNELS = 1;
    private static final int MAX_PACKET_BYTES = 4096;

    private PcmToOpusEncoder() {
    }

    /**
     * 编码 PCM 音频。
     *
     * @param pcm 16-bit little-endian PCM
     * @param sampleRate 采样率
     * @param frameDurationMs 帧时长，毫秒
     * @return Opus 帧列表
     */
    public static List<ByteBuffer> encode(byte[] pcm, int sampleRate, int frameDurationMs) {
        if (pcm.length == 0) {
            return List.of();
        }
        try {
            var frameSamples = sampleRate * frameDurationMs / 1000;
            var samples = toShortSamples(pcm);
            var encoder = new OpusEncoder(sampleRate, CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP);
            var frames = new ArrayList<ByteBuffer>();
            for (var offset = 0; offset < samples.length; offset += frameSamples) {
                var input = new short[frameSamples];
                var length = Math.min(frameSamples, samples.length - offset);
                System.arraycopy(samples, offset, input, 0, length);
                var output = new byte[MAX_PACKET_BYTES];
                var encodedBytes = encoder.encode(input, 0, frameSamples, output, 0, output.length);
                frames.add(ByteBuffer.wrap(output, 0, encodedBytes));
            }
            return frames;
        } catch (OpusException exception) {
            throw new IllegalStateException("Failed to encode PCM to Opus", exception);
        }
    }

    private static short[] toShortSamples(byte[] pcm) {
        var input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        var samples = new short[pcm.length / Short.BYTES];
        for (var index = 0; index < samples.length; index++) {
            samples[index] = input.getShort();
        }
        return samples;
    }
}
