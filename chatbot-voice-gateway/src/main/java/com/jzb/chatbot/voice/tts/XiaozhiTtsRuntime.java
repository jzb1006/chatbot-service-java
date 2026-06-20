package com.jzb.chatbot.voice.tts;

import com.jzb.chatbot.speech.StreamingTextToSpeechClient;
import com.jzb.chatbot.speech.StreamingTextToSpeechListener;
import com.jzb.chatbot.speech.StreamingTextToSpeechSession;
import com.jzb.chatbot.speech.TextToSpeechClient;
import com.jzb.chatbot.voice.XiaozhiTtsPlayback;
import com.jzb.chatbot.voice.music.XiaozhiMusicPlaybackCoordinator;
import com.jzb.chatbot.voice.music.XiaozhiMusicPlaybackState;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import com.jzb.chatbot.voice.protocol.XiaozhiServerEventFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 小智轻量 TTS 播放运行时。
 * <p>
 * 负责串联情绪控制帧、TTS 生命周期控制帧、语音合成和逐句播放。
 *
 * @author jiangzhibin
 * @since 2026-06-17 22:12:00
 */
@Slf4j
public class XiaozhiTtsRuntime {

    private static final String DEFAULT_EMOTION = "neutral";
    private static final long STOP_DELAY_MS = 120L;
    private static final long STOP_DELAY_POLL_MS = 10L;
    private static final Duration STREAMING_FINAL_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STREAMING_FINAL_MAX_TIMEOUT = Duration.ofMinutes(2);

    private final TextToSpeechClient textToSpeechClient;
    private final StreamingTextToSpeechClient streamingTextToSpeechClient;
    private final XiaozhiMessageCodec codec;
    private final XiaozhiServerEventFactory eventFactory;
    private final XiaozhiMusicPlaybackCoordinator musicPlaybackCoordinator;
    private final Object activePlaybackLock = new Object();
    private final Map<String, XiaozhiTtsPlayback> activePlaybacks = new ConcurrentHashMap<>();
    private final Map<String, Long> activePlaybackGenerations = new ConcurrentHashMap<>();
    private final Map<String, StreamingTextToSpeechSession> activeStreamingSessions = new ConcurrentHashMap<>();

    public XiaozhiTtsRuntime(
            TextToSpeechClient textToSpeechClient,
            XiaozhiMessageCodec codec,
            XiaozhiServerEventFactory eventFactory
    ) {
        this(textToSpeechClient, null, codec, eventFactory, null);
    }

    public XiaozhiTtsRuntime(
            TextToSpeechClient textToSpeechClient,
            XiaozhiMessageCodec codec,
            XiaozhiServerEventFactory eventFactory,
            XiaozhiMusicPlaybackCoordinator musicPlaybackCoordinator
    ) {
        this(textToSpeechClient, null, codec, eventFactory, musicPlaybackCoordinator);
    }

    public XiaozhiTtsRuntime(
            TextToSpeechClient textToSpeechClient,
            StreamingTextToSpeechClient streamingTextToSpeechClient,
            XiaozhiMessageCodec codec,
            XiaozhiServerEventFactory eventFactory
    ) {
        this(textToSpeechClient, streamingTextToSpeechClient, codec, eventFactory, null);
    }

    public XiaozhiTtsRuntime(
            TextToSpeechClient textToSpeechClient,
            StreamingTextToSpeechClient streamingTextToSpeechClient,
            XiaozhiMessageCodec codec,
            XiaozhiServerEventFactory eventFactory,
            XiaozhiMusicPlaybackCoordinator musicPlaybackCoordinator
    ) {
        this.textToSpeechClient = textToSpeechClient;
        this.streamingTextToSpeechClient = streamingTextToSpeechClient;
        this.codec = codec;
        this.eventFactory = eventFactory;
        this.musicPlaybackCoordinator = musicPlaybackCoordinator;
    }

    /**
     * 播放一次 TTS 响应。
     *
     * @param request TTS 播放请求
     * @return 是否至少实际开始播放了一个有效句子
     */
    public boolean speak(XiaozhiTtsRequest request) {
        return play(request).played();
    }

    /**
     * 播放一次 TTS 响应并返回播放指标。
     *
     * @param request TTS 播放请求
     * @return TTS 播放结果
     */
    public XiaozhiTtsResult play(XiaozhiTtsRequest request) {
        var voiceSession = request.voiceSession();
        var webSocketSession = request.webSocketSession();
        var sessionId = voiceSession.sessionId();
        var playback = new XiaozhiTtsPlayback(
                webSocketSession, voiceSession, codec, eventFactory, request.cancellationRequested()
        );
        var playbackGeneration = voiceSession.beginRuntimePlayback(request.expectedPlaybackGeneration());
        if (playbackGeneration < 0) {
            return new XiaozhiTtsResult(false, 0, 0, true);
        }
        var naturalPlaybackFinished = false;
        XiaozhiTtsResult result = null;
        registerActivePlayback(sessionId, playbackGeneration, playback, null);
        voiceSession.updatePlayback(playback);
        try {
            if (!cancelled(request, playback)) {
                sendText(request, eventFactory.llmEmotion(sessionId, DEFAULT_EMOTION));
            }
            if (!cancelled(request, playback)) {
                pauseMusicForTts(voiceSession.deviceId());
                sendText(request, eventFactory.ttsStart(sessionId));
                for (var sentence : request.sentences()) {
                    if (sentence == null || sentence.isBlank()) {
                        continue;
                    }
                    if (cancelled(request, playback)) {
                        break;
                    }
                    var frames = textToSpeechClient.synthesize(sentence, request.options());
                    if (!playback.playSentence(sentence, frames)) {
                        break;
                    }
                }
            }
            naturalPlaybackFinished = !cancelled(request, playback);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to play xiaozhi tts", exception);
        } finally {
            try {
                sendStopOnce(request, playback, naturalPlaybackFinished);
                resumeMusicAfterTts(voiceSession.deviceId());
                result = result(playback);
            } finally {
                unregisterActivePlayback(sessionId, playbackGeneration, playback, null);
                voiceSession.clearPlayback(playback);
                voiceSession.completePlayback(playbackGeneration);
            }
        }
        return result;
    }

    /**
     * 使用流式 TTS provider 播放一次响应。
     *
     * @param request 流式播放请求
     * @param sentenceProducer 句子生产器
     * @return TTS 播放结果
     */
    public XiaozhiTtsResult playStreaming(
            XiaozhiStreamingTtsRequest request,
            Consumer<XiaozhiTtsSentenceSink> sentenceProducer
    ) {
        if (!streamingEnabled()) {
            return play(collectSynchronousFallbackRequest(request, sentenceProducer));
        }
        var voiceSession = request.voiceSession();
        var webSocketSession = request.webSocketSession();
        var sessionId = voiceSession.sessionId();
        var playback = new XiaozhiTtsPlayback(
                webSocketSession, voiceSession, codec, eventFactory, request.cancellationRequested()
        );
        var playbackGeneration = voiceSession.beginRuntimePlayback(request.expectedPlaybackGeneration());
        if (playbackGeneration < 0) {
            return new XiaozhiTtsResult(false, 0, 0, true);
        }
        var naturalPlaybackFinished = false;
        XiaozhiTtsResult result = null;
        var sentences = new ArrayList<String>();
        var completedByProducer = new AtomicBoolean();
        var listener = new RuntimeStreamingListener(request, playback);
        StreamingTextToSpeechSession streamingSession = null;
        registerActivePlayback(sessionId, playbackGeneration, playback, null);
        voiceSession.updatePlayback(playback);
        try {
            streamingSession = streamingTextToSpeechClient.open(request.options(), listener);
            registerActivePlayback(sessionId, playbackGeneration, playback, streamingSession);
            if (!cancelled(request.cancellationRequested(), playback)) {
                sendText(webSocketSession, eventFactory.llmEmotion(sessionId, DEFAULT_EMOTION));
            }
            if (!cancelled(request.cancellationRequested(), playback)) {
                pauseMusicForTts(voiceSession.deviceId());
                sendText(webSocketSession, eventFactory.ttsStart(sessionId));
                var sink = new RuntimeSentenceSink(
                        request,
                        playback,
                        streamingSession,
                        sentences,
                        completedByProducer
                );
                sentenceProducer.accept(sink);
                if (!completedByProducer.get() && listener.failure() == null && !cancelled(request.cancellationRequested(), playback)) {
                    streamingSession.complete();
                    completedByProducer.set(true);
                }
                var failure = listener.failure();
                if (failure != null) {
                    handleStreamingFailure(request, playback, sentences, failure);
                } else if (!cancelled(request.cancellationRequested(), playback)
                        && !awaitStreamingFinal(request, playback, streamingSession)
                        && !cancelled(request.cancellationRequested(), playback)) {
                    handleStreamingFinalTimeout(request, playback, sentences);
                } else if (listener.failure() != null) {
                    handleStreamingFailure(request, playback, sentences, listener.failure());
                }
            }
            naturalPlaybackFinished = !cancelled(request.cancellationRequested(), playback);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to play xiaozhi streaming tts", exception);
        } finally {
            try {
                closeStreamingSession(sessionId, streamingSession);
                sendStopOnce(
                        request.webSocketSession(),
                        request.voiceSession(),
                        request.cancellationRequested(),
                        playback,
                        naturalPlaybackFinished
                );
                resumeMusicAfterTts(voiceSession.deviceId());
                result = result(playback);
            } finally {
                unregisterActivePlayback(sessionId, playbackGeneration, playback, streamingSession);
                voiceSession.clearPlayback(playback);
                voiceSession.completePlayback(playbackGeneration);
            }
        }
        return result;
    }

    private boolean awaitStreamingFinal(
            XiaozhiStreamingTtsRequest request,
            XiaozhiTtsPlayback playback,
            StreamingTextToSpeechSession streamingSession
    ) {
        var deadline = System.nanoTime() + STREAMING_FINAL_MAX_TIMEOUT.toNanos();
        var observedFrames = playback.sentFrames();
        while (!cancelled(request.cancellationRequested(), playback)) {
            var remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            if (streamingSession.awaitFinal(minDuration(STREAMING_FINAL_TIMEOUT, Duration.ofNanos(remaining)))) {
                return true;
            }
            if (cancelled(request.cancellationRequested(), playback)) {
                return false;
            }
            var currentFrames = playback.sentFrames();
            if (currentFrames <= observedFrames) {
                return false;
            }
            observedFrames = currentFrames;
        }
        return false;
    }

    private Duration minDuration(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private void closeStreamingSession(String sessionId, StreamingTextToSpeechSession streamingSession) {
        if (streamingSession == null) {
            return;
        }
        try {
            streamingSession.close();
        } catch (RuntimeException exception) {
            log.warn("Failed to close xiaozhi streaming tts session, sessionId={}, message={}",
                    sessionId, exception.getMessage(), exception);
        }
    }

    /**
     * 判断是否启用了流式 TTS provider。
     *
     * @return true 表示可使用流式播放入口
     */
    public boolean streamingEnabled() {
        return streamingTextToSpeechClient != null && streamingTextToSpeechClient.available();
    }

    private XiaozhiTtsResult result(XiaozhiTtsPlayback playback) {
        var sentenceCount = playback.startedSentences();
        return new XiaozhiTtsResult(sentenceCount > 0, sentenceCount, playback.sentFrames(), cancelled(playback));
    }

    private boolean cancelled(XiaozhiTtsRequest request, XiaozhiTtsPlayback playback) {
        return playback.cancelled() || request.cancellationRequested().getAsBoolean();
    }

    private boolean cancelled(XiaozhiTtsPlayback playback) {
        return playback.cancelled();
    }

    /**
     * 取消指定会话的活跃播放。
     *
     * @param sessionId 会话标识
     */
    public void cancel(String sessionId) {
        cancel(activePlayback(sessionId));
    }

    /**
     * 仅取消指定播放代际的活跃播放。
     *
     * @param sessionId 会话标识
     * @param playbackGeneration 播放代际
     * @return true 表示命中并发起取消
     */
    public boolean cancel(String sessionId, long playbackGeneration) {
        var activePlayback = activePlayback(sessionId, playbackGeneration);
        if (activePlayback == null) {
            return false;
        }
        cancel(activePlayback);
        return true;
    }

    private void registerActivePlayback(
            String sessionId,
            long playbackGeneration,
            XiaozhiTtsPlayback playback,
            StreamingTextToSpeechSession streamingSession
    ) {
        synchronized (activePlaybackLock) {
            activePlaybacks.put(sessionId, playback);
            activePlaybackGenerations.put(sessionId, playbackGeneration);
            if (streamingSession != null) {
                activeStreamingSessions.put(sessionId, streamingSession);
            }
        }
    }

    private void unregisterActivePlayback(
            String sessionId,
            long playbackGeneration,
            XiaozhiTtsPlayback playback,
            StreamingTextToSpeechSession streamingSession
    ) {
        synchronized (activePlaybackLock) {
            activePlaybacks.remove(sessionId, playback);
            activePlaybackGenerations.remove(sessionId, playbackGeneration);
            if (streamingSession != null) {
                activeStreamingSessions.remove(sessionId, streamingSession);
            }
        }
    }

    private ActivePlayback activePlayback(String sessionId) {
        synchronized (activePlaybackLock) {
            return new ActivePlayback(activePlaybacks.get(sessionId), activeStreamingSessions.get(sessionId));
        }
    }

    private ActivePlayback activePlayback(String sessionId, long playbackGeneration) {
        synchronized (activePlaybackLock) {
            if (!Long.valueOf(playbackGeneration).equals(activePlaybackGenerations.get(sessionId))) {
                return null;
            }
            return new ActivePlayback(activePlaybacks.get(sessionId), activeStreamingSessions.get(sessionId));
        }
    }

    private void cancel(ActivePlayback activePlayback) {
        var playback = activePlayback.playback();
        if (playback != null) {
            playback.cancel();
        }
        var streamingSession = activePlayback.streamingSession();
        if (streamingSession != null) {
            streamingSession.cancel();
        }
    }

    private void sendStopOnce(XiaozhiTtsRequest request, XiaozhiTtsPlayback playback, boolean naturalPlaybackFinished) {
        sendStopOnce(
                request.webSocketSession(),
                request.voiceSession(),
                request.cancellationRequested(),
                playback,
                naturalPlaybackFinished
        );
    }

    private void sendStopOnce(
            WebSocketSession webSocketSession,
            com.jzb.chatbot.voice.XiaozhiVoiceSession voiceSession,
            BooleanSupplier cancellationRequested,
            XiaozhiTtsPlayback playback,
            boolean naturalPlaybackFinished
    ) {
        if (!playback.markStopSent()) {
            return;
        }
        if (naturalPlaybackFinished) {
            sleepStopDelay(cancellationRequested, playback);
        }
        if (!webSocketSession.isOpen()) {
            return;
        }
        try {
            sendText(webSocketSession, eventFactory.ttsStop(voiceSession.sessionId()));
        } catch (IOException exception) {
            log.warn("Failed to send xiaozhi tts stop for session {}: {}",
                    voiceSession.sessionId(), exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            if (!closedWebSocketMessage(exception)) {
                throw exception;
            }
        }
    }

    private boolean closedWebSocketMessage(IllegalStateException exception) {
        var message = exception.getMessage();
        return message != null && message.contains("WebSocket session has been closed");
    }

    private void sleepStopDelay(BooleanSupplier cancellationRequested, XiaozhiTtsPlayback playback) {
        var deadline = System.nanoTime() + STOP_DELAY_MS * 1_000_000L;
        while (!cancelled(cancellationRequested, playback)) {
            var remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            var sleepMillis = Math.min(STOP_DELAY_POLL_MS, remaining / 1_000_000L);
            try {
                Thread.sleep(Math.max(1L, sleepMillis));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                playback.cancel();
                return;
            }
        }
    }

    private void sendText(XiaozhiTtsRequest request, String payload) throws IOException {
        sendText(request.webSocketSession(), payload);
    }

    private void sendText(WebSocketSession webSocketSession, String payload) throws IOException {
        webSocketSession.sendMessage(new TextMessage(payload));
    }

    private void pauseMusicForTts(String deviceId) {
        if (musicPlaybackCoordinator != null) {
            musicPlaybackCoordinator.pause(deviceId, XiaozhiMusicPlaybackState.PauseSource.TTS);
        }
    }

    private void resumeMusicAfterTts(String deviceId) {
        if (musicPlaybackCoordinator != null) {
            musicPlaybackCoordinator.resume(deviceId, XiaozhiMusicPlaybackState.PauseSource.TTS);
        }
    }

    private XiaozhiTtsRequest collectSynchronousFallbackRequest(
            XiaozhiStreamingTtsRequest request,
            Consumer<XiaozhiTtsSentenceSink> sentenceProducer
    ) {
        var sentences = new ArrayList<String>();
        sentenceProducer.accept(new XiaozhiTtsSentenceSink() {
            @Override
            public void accept(String sentence) {
                if (sentence != null && !sentence.isBlank()) {
                    sentences.add(sentence);
                }
            }

            @Override
            public void complete() {
            }
        });
        return new XiaozhiTtsRequest(
                request.webSocketSession(),
                request.voiceSession(),
                sentences,
                request.options(),
                request.expectedPlaybackGeneration(),
                request.cancellationRequested()
        );
    }

    private void handleStreamingFailure(
            XiaozhiStreamingTtsRequest request,
            XiaozhiTtsPlayback playback,
            ArrayList<String> sentences,
            RuntimeException failure
    ) throws IOException {
        if (playback.sentFrames() > 0 || sentences.isEmpty()) {
            throw failure;
        }
        for (var sentence : sentences) {
            if (cancelled(request.cancellationRequested(), playback)) {
                return;
            }
            var frames = textToSpeechClient.synthesize(sentence, request.options());
            for (var frame : frames) {
                if (!playback.playFrame(frame)) {
                    return;
                }
            }
        }
    }

    private void handleStreamingFinalTimeout(
            XiaozhiStreamingTtsRequest request,
            XiaozhiTtsPlayback playback,
            ArrayList<String> sentences
    ) throws IOException {
        if (playback.sentFrames() > 0) {
            log.warn("xiaozhi streaming tts final timeout after audio, sessionId={}, deviceId={}, sentences={}, frames={}",
                    request.voiceSession().sessionId(),
                    request.voiceSession().deviceId(),
                    sentences.size(),
                    playback.sentFrames());
            return;
        }
        handleStreamingFailure(
                request,
                playback,
                sentences,
                new IllegalStateException("streaming tts final timeout")
        );
    }

    private boolean cancelled(BooleanSupplier cancellationRequested, XiaozhiTtsPlayback playback) {
        return playback.cancelled() || cancellationRequested.getAsBoolean();
    }

    private final class RuntimeSentenceSink implements XiaozhiTtsSentenceSink {

        private final XiaozhiStreamingTtsRequest request;
        private final XiaozhiTtsPlayback playback;
        private final StreamingTextToSpeechSession streamingSession;
        private final ArrayList<String> sentences;
        private final AtomicBoolean completed;

        private RuntimeSentenceSink(
                XiaozhiStreamingTtsRequest request,
                XiaozhiTtsPlayback playback,
                StreamingTextToSpeechSession streamingSession,
                ArrayList<String> sentences,
                AtomicBoolean completed
        ) {
            this.request = request;
            this.playback = playback;
            this.streamingSession = streamingSession;
            this.sentences = sentences;
            this.completed = completed;
        }

        @Override
        public void accept(String sentence) {
            if (sentence == null || sentence.isBlank() || completed.get()) {
                return;
            }
            if (cancelled(request.cancellationRequested(), playback)) {
                return;
            }
            try {
                var accepted = false;
                synchronized (playback) {
                    accepted = playback.startSentence(sentence);
                }
                if (accepted) {
                    sentences.add(sentence);
                    streamingSession.sendText(sentence);
                }
            } catch (IOException exception) {
                throw new StreamingTtsWriteException("failed to send xiaozhi streaming tts sentence", exception);
            } catch (RuntimeException exception) {
                throw new StreamingTtsWriteException("failed to send xiaozhi streaming tts sentence", exception);
            }
        }

        @Override
        public void complete() {
            if (completed.compareAndSet(false, true)) {
                streamingSession.complete();
            }
        }
    }

    private final class RuntimeStreamingListener implements StreamingTextToSpeechListener {

        private final XiaozhiStreamingTtsRequest request;
        private final XiaozhiTtsPlayback playback;
        private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
        private final CountDownLatch finalState = new CountDownLatch(1);

        private RuntimeStreamingListener(XiaozhiStreamingTtsRequest request, XiaozhiTtsPlayback playback) {
            this.request = request;
            this.playback = playback;
        }

        @Override
        public void onReady() {
        }

        @Override
        public void onAudioFrame(ByteBuffer frame) {
            if (failure.get() != null || cancelled(request.cancellationRequested(), playback)) {
                return;
            }
            try {
                synchronized (playback) {
                    playback.playFrame(frame);
                }
            } catch (IOException exception) {
                onFailed(new IllegalStateException("failed to send xiaozhi streaming tts audio frame", exception));
            }
        }

        @Override
        public void onCompleted() {
            finalState.countDown();
        }

        @Override
        public void onFailed(RuntimeException exception) {
            failure.compareAndSet(null, exception);
            finalState.countDown();
        }

        private RuntimeException failure() {
            return failure.get();
        }

        private boolean awaitFinal(Duration timeout) {
            try {
                return finalState.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                failure.compareAndSet(null, new IllegalStateException("interrupted while waiting streaming tts final", exception));
                return true;
            }
        }
    }

    /**
     * 流式 TTS 写入 WebSocket 或 provider 失败。
     * <p>
     * 调用方用该异常区分 TTS 写入失败和 Hermes 流读取失败。
     *
     * @author jiangzhibin
     * @since 2026-06-18 13:38:00
     */
    public static final class StreamingTtsWriteException extends RuntimeException {

        public StreamingTtsWriteException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record ActivePlayback(
            XiaozhiTtsPlayback playback,
            StreamingTextToSpeechSession streamingSession
    ) {
    }
}
