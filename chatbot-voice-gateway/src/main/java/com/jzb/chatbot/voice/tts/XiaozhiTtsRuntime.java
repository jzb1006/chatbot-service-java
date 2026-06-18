package com.jzb.chatbot.voice.tts;

import com.jzb.chatbot.speech.TextToSpeechClient;
import com.jzb.chatbot.voice.XiaozhiTtsPlayback;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import com.jzb.chatbot.voice.protocol.XiaozhiServerEventFactory;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.TextMessage;

/**
 * 小智轻量 TTS 播放运行时。
 * <p>
 * 负责串联情绪控制帧、TTS 生命周期控制帧、语音合成和逐句播放。
 *
 * @author jiangzhibin
 * @since 2026-06-17 22:12:00
 */
@RequiredArgsConstructor
@Slf4j
public class XiaozhiTtsRuntime {

    private static final String DEFAULT_EMOTION = "neutral";
    private static final long STOP_DELAY_MS = 120L;
    private static final long STOP_DELAY_POLL_MS = 10L;

    private final TextToSpeechClient textToSpeechClient;
    private final XiaozhiMessageCodec codec;
    private final XiaozhiServerEventFactory eventFactory;
    private final Map<String, XiaozhiTtsPlayback> activePlaybacks = new ConcurrentHashMap<>();

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
        activePlaybacks.put(sessionId, playback);
        voiceSession.updatePlayback(playback);
        try {
            if (!cancelled(request, playback)) {
                sendText(request, eventFactory.llmEmotion(sessionId, DEFAULT_EMOTION));
            }
            if (!cancelled(request, playback)) {
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
                result = result(playback);
            } finally {
                activePlaybacks.remove(sessionId, playback);
                voiceSession.clearPlayback(playback);
                voiceSession.completePlayback(playbackGeneration);
            }
        }
        return result;
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
        var playback = activePlaybacks.get(sessionId);
        if (playback != null) {
            playback.cancel();
        }
    }

    private void sendStopOnce(XiaozhiTtsRequest request, XiaozhiTtsPlayback playback, boolean naturalPlaybackFinished) {
        if (!playback.markStopSent()) {
            return;
        }
        if (naturalPlaybackFinished) {
            sleepStopDelay(request, playback);
        }
        try {
            sendText(request, eventFactory.ttsStop(request.voiceSession().sessionId()));
        } catch (IOException exception) {
            log.warn("Failed to send xiaozhi tts stop for session {}: {}",
                    request.voiceSession().sessionId(), exception.getMessage(), exception);
        }
    }

    private void sleepStopDelay(XiaozhiTtsRequest request, XiaozhiTtsPlayback playback) {
        var deadline = System.nanoTime() + STOP_DELAY_MS * 1_000_000L;
        while (!cancelled(request, playback)) {
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
        request.webSocketSession().sendMessage(new TextMessage(payload));
    }
}
