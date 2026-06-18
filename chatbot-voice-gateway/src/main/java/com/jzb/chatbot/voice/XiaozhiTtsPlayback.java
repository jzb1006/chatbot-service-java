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
public class XiaozhiTtsPlayback {

    private static final long OPUS_FRAME_SEND_INTERVAL_NS = 60_000_000L;
    private static final long SENTENCE_GAP_NS = OPUS_FRAME_SEND_INTERVAL_NS * 5;
    private static final long WAIT_POLL_NS = 10_000_000L;
    private static final long BURST_PREBUFFER_NS = -OPUS_FRAME_SEND_INTERVAL_NS * 2;

    private final WebSocketSession webSocketSession;
    private final XiaozhiVoiceSession voiceSession;
    private final XiaozhiMessageCodec codec;
    private final XiaozhiServerEventFactory eventFactory;
    private final BooleanSupplier cancellationRequested;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean stopSent = new AtomicBoolean();
    private long startTimestamp;
    private long playPosition = BURST_PREBUFFER_NS;
    private int startedSentences;
    private int sentFrames;

    public XiaozhiTtsPlayback(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            XiaozhiMessageCodec codec,
            XiaozhiServerEventFactory eventFactory
    ) {
        this(webSocketSession, voiceSession, codec, eventFactory, () -> false);
    }

    public XiaozhiTtsPlayback(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            XiaozhiMessageCodec codec,
            XiaozhiServerEventFactory eventFactory,
            BooleanSupplier cancellationRequested
    ) {
        this.webSocketSession = webSocketSession;
        this.voiceSession = voiceSession;
        this.codec = codec;
        this.eventFactory = eventFactory;
        this.cancellationRequested = cancellationRequested == null ? () -> false : cancellationRequested;
    }

    public boolean playSentence(String sentence, List<ByteBuffer> frames) throws IOException {
        if (cancelled()) {
            return false;
        }
        if (frames == null || frames.isEmpty()) {
            return true;
        }
        if (sentFrames > 0) {
            playPosition += SENTENCE_GAP_NS;
            waitForFrameTime();
            if (cancelled()) {
                return false;
            }
        }
        if (cancelled()) {
            return false;
        }
        sendText(eventFactory.ttsSentenceStart(voiceSession.sessionId(), sentence));
        startedSentences++;
        for (var frame : frames) {
            if (cancelled()) {
                return false;
            }
            waitForFrameTime();
            if (cancelled()) {
                return false;
            }
            webSocketSession.sendMessage(new BinaryMessage(
                    codec.encodeAudioFrame(voiceSession.protocolVersion(), 0, frame)
            ));
            sentFrames++;
            playPosition += OPUS_FRAME_SEND_INTERVAL_NS;
        }
        return true;
    }

    public boolean playSentence(String sentence, List<ByteBuffer> frames, BooleanSupplier activeSupplier) throws IOException {
        return playSentence(sentence, frames);
    }

    public void cancel() {
        cancelled.set(true);
    }

    public boolean cancelled() {
        return cancelled.get() || cancellationRequested.getAsBoolean();
    }

    public boolean markStopSent() {
        return stopSent.compareAndSet(false, true);
    }

    /**
     * 获取已发送的 TTS 音频帧数量。
     *
     * @return 已发送音频帧数量
     */
    public int sentFrames() {
        return sentFrames;
    }

    /**
     * 获取已实际开始播放的句子数量。
     *
     * @return 已发送 sentence_start 的句子数量
     */
    public int startedSentences() {
        return startedSentences;
    }

    private void waitForFrameTime() {
        if (startTimestamp == 0) {
            startTimestamp = System.nanoTime();
        }
        while (!cancelled()) {
            var delay = startTimestamp + playPosition - System.nanoTime();
            if (delay <= 0) {
                return;
            }
            var sleepNanos = Math.min(WAIT_POLL_NS, delay);
            try {
                Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                cancel();
                return;
            }
        }
    }

    private void sendText(String payload) throws IOException {
        webSocketSession.sendMessage(new TextMessage(payload));
    }
}
