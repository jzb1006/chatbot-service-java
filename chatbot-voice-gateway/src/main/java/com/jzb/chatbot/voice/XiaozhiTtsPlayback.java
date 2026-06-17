package com.jzb.chatbot.voice;

import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import com.jzb.chatbot.voice.protocol.XiaozhiServerEventFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 小智 TTS 播放控制器。
 * <p>
 * 负责逐句发送 TTS 控制帧，并按 60ms 节奏下发 Opus 音频帧。
 *
 * @author jiangzhibin
 * @since 2026-06-17 16:50:00
 */
class XiaozhiTtsPlayback {

    private static final long OPUS_FRAME_SEND_INTERVAL_NS = 60_000_000L;
    private static final long BURST_PREBUFFER_NS = -OPUS_FRAME_SEND_INTERVAL_NS * 2;

    private final WebSocketSession webSocketSession;
    private final XiaozhiVoiceSession voiceSession;
    private final XiaozhiMessageCodec codec;
    private final XiaozhiServerEventFactory eventFactory;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean stopSent = new AtomicBoolean();
    private long startTimestamp;
    private long playPosition = BURST_PREBUFFER_NS;
    private int sentFrames;

    XiaozhiTtsPlayback(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            XiaozhiMessageCodec codec,
            XiaozhiServerEventFactory eventFactory
    ) {
        this.webSocketSession = webSocketSession;
        this.voiceSession = voiceSession;
        this.codec = codec;
        this.eventFactory = eventFactory;
    }

    public boolean playSentence(String sentence, List<ByteBuffer> frames, BooleanSupplier activeSupplier) throws IOException {
        if (cancelled.get()) {
            return false;
        }
        if (frames == null || frames.isEmpty()) {
            return true;
        }
        if (!sendText(eventFactory.ttsSentenceStart(voiceSession.sessionId(), sentence), activeSupplier)) {
            return false;
        }
        for (var frame : frames) {
            if (cancelled.get()) {
                return false;
            }
            waitForFrameTime();
            if (cancelled.get()) {
                return false;
            }
            if (!sendBinary(frame, activeSupplier)) {
                return false;
            }
            sentFrames++;
            playPosition += OPUS_FRAME_SEND_INTERVAL_NS;
        }
        return true;
    }

    public void cancel() {
        cancelled.set(true);
    }

    public boolean cancelled() {
        return cancelled.get();
    }

    public boolean markStopSent() {
        return stopSent.compareAndSet(false, true);
    }

    public int sentFrames() {
        return sentFrames;
    }

    private void waitForFrameTime() {
        if (startTimestamp == 0) {
            startTimestamp = System.nanoTime();
        }
        var delay = startTimestamp + playPosition - System.nanoTime();
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay / 1_000_000L, (int) (delay % 1_000_000L));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cancel();
        }
    }

    private boolean sendText(String payload, BooleanSupplier activeSupplier) throws IOException {
        if (!activeSupplier.getAsBoolean() || !voiceSession.hasPlayback(this)) {
            cancel();
            return false;
        }
        webSocketSession.sendMessage(new TextMessage(payload));
        return activeSupplier.getAsBoolean() && voiceSession.hasPlayback(this);
    }

    private boolean sendBinary(ByteBuffer frame, BooleanSupplier activeSupplier) throws IOException {
        if (!activeSupplier.getAsBoolean() || !voiceSession.hasPlayback(this)) {
            cancel();
            return false;
        }
        webSocketSession.sendMessage(new BinaryMessage(
                codec.encodeAudioFrame(voiceSession.protocolVersion(), 0, frame)
        ));
        return activeSupplier.getAsBoolean() && voiceSession.hasPlayback(this);
    }
}
