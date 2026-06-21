package com.jzb.chatbot.speech;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;
import io.github.jaredmdobson.concentus.OpusSignal;

/**
 * 流式 PCM 到 Opus 编码器。
 * <p>
 * 在同一流式 TTS 会话内缓存腾讯云返回的 PCM binary，按固定帧长增量输出小智协议需要的 Opus 帧。
 *
 * @author jiangzhibin
 * @since 2026-06-18 12:48:00
 */
public final class StreamingPcmToOpusEncoder {

    private static final int CHANNELS = 1;
    private static final int MAX_PACKET_BYTES = 4096;

    private final int frameSamples;
    private final int frameBytes;
    private final OpusEncoder encoder;
    private final Options options;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    public StreamingPcmToOpusEncoder(int sampleRate, int frameDurationMs) {
        this(sampleRate, frameDurationMs, Options.voice());
    }

    public StreamingPcmToOpusEncoder(int sampleRate, int frameDurationMs, Options options) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        if (frameDurationMs <= 0) {
            throw new IllegalArgumentException("frameDurationMs must be positive");
        }
        this.options = options == null ? Options.voice() : options;
        this.frameSamples = sampleRate * frameDurationMs / 1000;
        this.frameBytes = frameSamples * Short.BYTES;
        try {
            this.encoder = new OpusEncoder(sampleRate, CHANNELS, this.options.application());
            this.encoder.setSignalType(this.options.signal());
            this.encoder.setBitrate(this.options.bitrateBps());
            this.encoder.setComplexity(this.options.complexity());
            this.encoder.setUseVBR(this.options.vbr());
        } catch (OpusException exception) {
            throw new IllegalStateException("Failed to create Opus encoder", exception);
        }
    }

    public Options options() {
        return options;
    }

    /**
     * 接收一段 PCM 数据并编码其中的完整帧。
     *
     * @param pcm 16-bit little-endian PCM
     * @return 已编码 Opus 帧列表
     */
    public List<ByteBuffer> accept(byte[] pcm) {
        if (pcm == null || pcm.length == 0) {
            return List.of();
        }
        pending.writeBytes(pcm);
        return drainCompleteFrames();
    }

    /**
     * 刷出剩余 PCM，不足一帧时右侧补零。
     *
     * @return 已编码 Opus 帧列表
     */
    public List<ByteBuffer> flush() {
        if (pending.size() == 0) {
            return List.of();
        }
        var pcm = pending.toByteArray();
        pending.reset();
        return encodeFrame(Arrays.copyOf(pcm, frameBytes));
    }

    private List<ByteBuffer> drainCompleteFrames() {
        var pcm = pending.toByteArray();
        var completeBytes = pcm.length / frameBytes * frameBytes;
        if (completeBytes == 0) {
            return List.of();
        }
        var frames = new ArrayList<ByteBuffer>();
        for (var offset = 0; offset < completeBytes; offset += frameBytes) {
            frames.addAll(encodeFrame(Arrays.copyOfRange(pcm, offset, offset + frameBytes)));
        }
        pending.reset();
        if (completeBytes < pcm.length) {
            pending.writeBytes(Arrays.copyOfRange(pcm, completeBytes, pcm.length));
        }
        return frames;
    }

    private List<ByteBuffer> encodeFrame(byte[] pcm) {
        try {
            var input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
            var samples = new short[frameSamples];
            for (var index = 0; index < samples.length; index++) {
                samples[index] = input.getShort();
            }
            var output = new byte[MAX_PACKET_BYTES];
            var encodedBytes = encoder.encode(samples, 0, frameSamples, output, 0, output.length);
            return List.of(ByteBuffer.wrap(output, 0, encodedBytes));
        } catch (OpusException exception) {
            throw new IllegalStateException("Failed to encode streaming PCM to Opus", exception);
        }
    }

    public record Options(
            OpusApplication application,
            OpusSignal signal,
            int bitrateBps,
            int complexity,
            boolean vbr
    ) {

        public Options {
            application = application == null ? OpusApplication.OPUS_APPLICATION_VOIP : application;
            signal = signal == null ? OpusSignal.OPUS_SIGNAL_AUTO : signal;
            if (bitrateBps <= 0) {
                bitrateBps = 32000;
            }
            complexity = Math.max(0, Math.min(complexity, 10));
        }

        public static Options voice() {
            return new Options(
                    OpusApplication.OPUS_APPLICATION_VOIP,
                    OpusSignal.OPUS_SIGNAL_AUTO,
                    32000,
                    5,
                    true
            );
        }

        public static Options music16k() {
            return new Options(
                    OpusApplication.OPUS_APPLICATION_AUDIO,
                    OpusSignal.OPUS_SIGNAL_MUSIC,
                    64000,
                    10,
                    true
            );
        }
    }
}
