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

    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_DURATION_MS = 60;
    private static final int READ_BUFFER_BYTES = SAMPLE_RATE / 1000 * FRAME_DURATION_MS * Short.BYTES;

    private final XiaozhiMessageCodec codec;

    public MusicFrameSender(XiaozhiMessageCodec codec) {
        this.codec = codec;
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
        var encoder = new StreamingPcmToOpusEncoder(SAMPLE_RATE, FRAME_DURATION_MS);
        var buffer = new byte[READ_BUFFER_BYTES];
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
            sleepMillis(FRAME_DURATION_MS);
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
