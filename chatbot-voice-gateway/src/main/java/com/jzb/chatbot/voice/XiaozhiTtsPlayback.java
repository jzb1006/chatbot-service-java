package com.jzb.chatbot.voice;

import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import com.jzb.chatbot.voice.protocol.XiaozhiServerEventFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
    private final long sentenceGapNs;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean stopSent = new AtomicBoolean();
    private long startTimestamp;
    private long playPosition = BURST_PREBUFFER_NS;
    private String currentSentence;
    private int currentSentenceIndex;
    private long currentSentenceStartedAt;
    private long currentSentenceFirstBinaryAt;
    private long currentSentenceLastBinaryAt;
    private long currentSentenceMaxFrameGapNs;
    private int currentSentenceFrameCount;
    private volatile int startedSentences;
    private volatile int sentFrames;

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
        this(webSocketSession, voiceSession, codec, eventFactory, cancellationRequested, SENTENCE_GAP_NS);
    }

    /**
     * 创建流式 TTS 播放控制器。
     * <p>
     * 流式 provider 自身连续推送音频帧，不额外插入同步 TTS 的句间停顿。
     *
     * @param webSocketSession WebSocket 会话
     * @param voiceSession 小智语音会话
     * @param codec 小智消息编解码器
     * @param eventFactory 小智服务端事件工厂
     * @param cancellationRequested 取消状态提供器
     * @return 流式 TTS 播放控制器
     */
    public static XiaozhiTtsPlayback streaming(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            XiaozhiMessageCodec codec,
            XiaozhiServerEventFactory eventFactory,
            BooleanSupplier cancellationRequested
    ) {
        return new XiaozhiTtsPlayback(
                webSocketSession,
                voiceSession,
                codec,
                eventFactory,
                cancellationRequested,
                0L
        );
    }

    private XiaozhiTtsPlayback(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            XiaozhiMessageCodec codec,
            XiaozhiServerEventFactory eventFactory,
            BooleanSupplier cancellationRequested,
            long sentenceGapNs
    ) {
        this.webSocketSession = webSocketSession;
        this.voiceSession = voiceSession;
        this.codec = codec;
        this.eventFactory = eventFactory;
        this.cancellationRequested = cancellationRequested == null ? () -> false : cancellationRequested;
        this.sentenceGapNs = sentenceGapNs;
    }

    public boolean playSentence(String sentence, List<ByteBuffer> frames) throws IOException {
        if (cancelled()) {
            return false;
        }
        if (frames == null || frames.isEmpty()) {
            return true;
        }
        if (!startSentence(sentence)) {
            return false;
        }
        for (var frame : frames) {
            if (!playFrame(frame)) {
                return false;
            }
        }
        return true;
    }

    public boolean playSentence(String sentence, List<ByteBuffer> frames, BooleanSupplier activeSupplier) throws IOException {
        return playSentence(sentence, frames);
    }

    public boolean startSentence(String sentence) throws IOException {
        if (cancelled()) {
            return false;
        }
        finishSentenceDiagnostics();
        if (sentFrames > 0 && sentenceGapNs > 0) {
            playPosition += sentenceGapNs;
            waitForFrameTime();
        }
        if (cancelled()) {
            return false;
        }
        sendText(eventFactory.ttsSentenceStart(voiceSession.sessionId(), sentence));
        startedSentences++;
        currentSentence = sentence;
        currentSentenceIndex = startedSentences;
        currentSentenceStartedAt = System.nanoTime();
        currentSentenceFirstBinaryAt = 0L;
        currentSentenceLastBinaryAt = 0L;
        currentSentenceMaxFrameGapNs = 0L;
        currentSentenceFrameCount = 0;
        return true;
    }

    public boolean playFrame(ByteBuffer frame) throws IOException {
        if (cancelled() || frame == null || !frame.hasRemaining()) {
            return false;
        }
        waitForFrameTime();
        if (cancelled()) {
            return false;
        }
        webSocketSession.sendMessage(new BinaryMessage(
                codec.encodeAudioFrame(voiceSession.protocolVersion(), 0, frame)
        ));
        recordBinarySent();
        sentFrames++;
        playPosition += OPUS_FRAME_SEND_INTERVAL_NS;
        return true;
    }

    public void cancel() {
        finishSentenceDiagnostics();
        cancelled.set(true);
    }

    public boolean cancelled() {
        return cancelled.get() || cancellationRequested.getAsBoolean();
    }

    public boolean markStopSent() {
        finishSentenceDiagnostics();
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

    private void recordBinarySent() {
        if (currentSentence == null) {
            return;
        }
        var now = System.nanoTime();
        if (currentSentenceFirstBinaryAt == 0L) {
            currentSentenceFirstBinaryAt = now;
        }
        if (currentSentenceLastBinaryAt > 0L) {
            currentSentenceMaxFrameGapNs = Math.max(currentSentenceMaxFrameGapNs, now - currentSentenceLastBinaryAt);
        }
        currentSentenceLastBinaryAt = now;
        currentSentenceFrameCount++;
    }

    private void finishSentenceDiagnostics() {
        if (currentSentence == null) {
            return;
        }
        var firstBinaryMs = currentSentenceFirstBinaryAt == 0L
                ? -1L
                : elapsedMillis(currentSentenceStartedAt, currentSentenceFirstBinaryAt);
        var maxFrameGapMs = currentSentenceMaxFrameGapNs == 0L
                ? 0L
                : currentSentenceMaxFrameGapNs / 1_000_000L;
        var durationMs = currentSentenceLastBinaryAt == 0L
                ? elapsedMillis(currentSentenceStartedAt, System.nanoTime())
                : elapsedMillis(currentSentenceStartedAt, currentSentenceLastBinaryAt);
        log.info("xiaozhi tts sentence playback, sessionId={}, deviceId={}, sentenceIndex={}, frameCount={}, firstBinaryMs={}, maxFrameGapMs={}, durationMs={}, sentenceText={}",
                voiceSession.sessionId(),
                voiceSession.deviceId(),
                currentSentenceIndex,
                currentSentenceFrameCount,
                firstBinaryMs,
                maxFrameGapMs,
                durationMs,
                currentSentence);
        currentSentence = null;
        currentSentenceIndex = 0;
        currentSentenceStartedAt = 0L;
        currentSentenceFirstBinaryAt = 0L;
        currentSentenceLastBinaryAt = 0L;
        currentSentenceMaxFrameGapNs = 0L;
        currentSentenceFrameCount = 0;
    }

    private long elapsedMillis(long start, long end) {
        return Math.max(0L, (end - start) / 1_000_000L);
    }
}
