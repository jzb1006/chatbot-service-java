package com.jzb.chatbot.voice.music;

import com.jzb.chatbot.speech.StreamingPcmToOpusEncoder;
import com.jzb.chatbot.voice.XiaozhiVoiceSession;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 音乐音频帧发送器。
 * <p>
 * 将 PCM 流增量编码为 Opus，并通过小智 WebSocket binary 下发。
 *
 * @author jiangzhibin
 * @since 2026-06-18 00:00:00
 */
public class MusicFrameSender {

    private static final java.util.Set<Integer> SUPPORTED_OPUS_SAMPLE_RATES = java.util.Set.of(
            8000, 12000, 16000, 24000, 48000
    );

    private final XiaozhiMessageCodec codec;
    private final int sampleRate;
    private final int frameDurationMs;
    private final int readBufferBytes;
    private final StreamingPcmToOpusEncoder.Options opusOptions;

    public MusicFrameSender(XiaozhiMessageCodec codec) {
        this(codec, StreamingPcmToOpusEncoder.Options.music16k());
    }

    public MusicFrameSender(XiaozhiMessageCodec codec, StreamingPcmToOpusEncoder.Options opusOptions) {
        this(
                codec,
                XiaozhiMusicPlaybackProperties.DEFAULT_SAMPLE_RATE,
                XiaozhiMusicPlaybackProperties.DEFAULT_FRAME_DURATION_MS,
                opusOptions
        );
    }

    public MusicFrameSender(
            XiaozhiMessageCodec codec,
            int sampleRate,
            int frameDurationMs,
            StreamingPcmToOpusEncoder.Options opusOptions
    ) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        if (!SUPPORTED_OPUS_SAMPLE_RATES.contains(sampleRate)) {
            throw new IllegalArgumentException("sampleRate must be one of 8000, 12000, 16000, 24000, 48000");
        }
        if (frameDurationMs <= 0) {
            throw new IllegalArgumentException("frameDurationMs must be positive");
        }
        this.codec = codec;
        this.sampleRate = sampleRate;
        this.frameDurationMs = frameDurationMs;
        this.readBufferBytes = sampleRate / 1000 * frameDurationMs * Short.BYTES;
        this.opusOptions = opusOptions == null ? StreamingPcmToOpusEncoder.Options.music16k() : opusOptions;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int frameDurationMs() {
        return frameDurationMs;
    }

    public StreamingPcmToOpusEncoder.Options opusOptions() {
        return opusOptions;
    }

    public int send(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            InputStream pcmStream,
            BooleanSupplier paused,
            BooleanSupplier cancelled
    ) throws IOException {
        return send(webSocketSession, voiceSession, pcmStream, paused, cancelled, () -> {
        }, () -> {
        });
    }

    public int send(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            InputStream pcmStream,
            BooleanSupplier paused,
            BooleanSupplier cancelled,
            Runnable beforeFrameSend,
            Runnable afterFrameSend
    ) throws IOException {
        var encoder = new StreamingPcmToOpusEncoder(sampleRate, frameDurationMs, opusOptions);
        var buffer = new byte[readBufferBytes];
        var sentFrames = 0;
        var read = pcmStream.read(buffer);
        while (read >= 0 && !cancelled.getAsBoolean()) {
            waitIfPaused(paused, cancelled);
            for (var frame : encoder.accept(read == buffer.length ? buffer : Arrays.copyOf(buffer, read))) {
                if (cancelled.getAsBoolean()) {
                    return sentFrames;
                }
                beforeFrameSend.run();
                try {
                    webSocketSession.sendMessage(new BinaryMessage(
                            codec.encodeAudioFrame(voiceSession.protocolVersion(), 0, frame)
                    ));
                } finally {
                    afterFrameSend.run();
                }
                sentFrames++;
                sleepFrameInterval(cancelled);
            }
            read = pcmStream.read(buffer);
        }
        for (var frame : encoder.flush()) {
            if (cancelled.getAsBoolean()) {
                return sentFrames;
            }
            beforeFrameSend.run();
            try {
                webSocketSession.sendMessage(new BinaryMessage(
                        codec.encodeAudioFrame(voiceSession.protocolVersion(), 0, frame)
                ));
            } finally {
                afterFrameSend.run();
            }
            sentFrames++;
        }
        return sentFrames;
    }

    private void waitIfPaused(BooleanSupplier paused, BooleanSupplier cancelled) {
        while (paused.getAsBoolean() && !cancelled.getAsBoolean()) {
            sleepMillis(20L);
        }
    }

    private void sleepFrameInterval(BooleanSupplier cancelled) {
        if (!cancelled.getAsBoolean()) {
            sleepMillis(frameDurationMs);
        }
    }

    private void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
