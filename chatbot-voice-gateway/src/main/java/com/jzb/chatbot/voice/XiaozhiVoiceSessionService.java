package com.jzb.chatbot.voice;

import com.jzb.chatbot.common.id.ConversationId;
import com.jzb.chatbot.common.id.DeviceId;
import com.jzb.chatbot.common.id.VoiceId;
import com.jzb.chatbot.hermes.HermesClient;
import com.jzb.chatbot.hermes.HermesClientConfig;
import com.jzb.chatbot.hermes.HermesRequest;
import com.jzb.chatbot.speech.SpeechToTextClient;
import com.jzb.chatbot.speech.TextToSpeechClient;
import com.jzb.chatbot.voice.mcp.XiaozhiMcpBridge;
import com.jzb.chatbot.voice.protocol.XiaozhiClientHello;
import com.jzb.chatbot.voice.protocol.XiaozhiClientMessage;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import com.jzb.chatbot.voice.protocol.XiaozhiServerEventFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.TextMessage;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

/**
 * 小智语音会话服务。
 * <p>
 * 管理 WebSocket 音频会话的最小状态，避免 Handler 承担业务流程。
 *
 * @author jiangzhibin
 * @since 2026-06-14 20:45:00
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class XiaozhiVoiceSessionService {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String PROTOCOL_VERSION_HEADER = "Protocol-Version";
    private static final String DEVICE_ID_HEADER = "Device-Id";
    private static final String CLIENT_ID_HEADER = "Client-Id";

    private final XiaozhiMessageCodec codec;
    private final SpeechToTextClient speechToTextClient;
    private final HermesClient hermesClient;
    private final TextToSpeechClient textToSpeechClient;
    private final XiaozhiServerEventFactory eventFactory;
    private final HermesClientConfig hermesClientConfig;
    private final XiaozhiVoiceTokenAuth tokenAuth;
    private final XiaozhiMcpBridge mcpBridge;
    private final Map<String, XiaozhiVoiceSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> webSocketSessions = new ConcurrentHashMap<>();
    private final Map<String, String> deviceSessionIds = new ConcurrentHashMap<>();

    /**
     * 打开新的语音会话。
     *
     * @param session WebSocket 会话
     * @return true 表示会话已通过鉴权并打开
     */
    public boolean open(WebSocketSession session) {
        var voiceSession = new XiaozhiVoiceSession(session.getId());
        var headers = session.getHandshakeHeaders();
        voiceSession.updateHandshake(
                headers.getFirst(AUTHORIZATION_HEADER),
                headers.getFirst(DEVICE_ID_HEADER),
                headers.getFirst(CLIENT_ID_HEADER),
                parseProtocolVersion(headers.getFirst(PROTOCOL_VERSION_HEADER))
        );
        if (!tokenAuth.matches(voiceSession.authorization())) {
            log.warn("xiaozhi websocket auth failed, sessionId={}, deviceId={}, clientId={}",
                    session.getId(), voiceSession.deviceId(), voiceSession.clientId());
            return false;
        }
        sessions.put(session.getId(), voiceSession);
        webSocketSessions.put(session.getId(), session);
        deviceSessionIds.put(voiceSession.deviceId(), session.getId());
        mcpBridge.register(voiceSession.deviceId(), session.getId(), session);
        log.info("xiaozhi websocket connected, sessionId={}, deviceId={}, clientId={}, protocolVersion={}, authRequired={}, authResult=success",
                session.getId(),
                voiceSession.deviceId(),
                voiceSession.clientId(),
                voiceSession.protocolVersion(),
                tokenAuth.required());
        return true;
    }

    /**
     * 获取语音会话。
     *
     * @param sessionId WebSocket 会话标识
     * @return 语音会话
     */
    public XiaozhiVoiceSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * 关闭并移除语音会话。
     *
     * @param session WebSocket 会话
     */
    public void close(WebSocketSession session) {
        var voiceSession = sessions.remove(session.getId());
        webSocketSessions.remove(session.getId());
        if (voiceSession != null) {
            voiceSession.cancelPlayback();
            deviceSessionIds.computeIfPresent(voiceSession.deviceId(), (key, currentSessionId) ->
                    session.getId().equals(currentSessionId) ? null : currentSessionId);
            mcpBridge.unregister(voiceSession.deviceId(), session.getId());
        }
        log.info("xiaozhi websocket closed, sessionId={}", session.getId());
    }

    /**
     * 处理设备 hello。
     *
     * @param webSocketSession WebSocket 会话
     * @param hello 设备 hello
     */
    public void handleHello(WebSocketSession webSocketSession, XiaozhiClientHello hello) {
        var version = hello.version() <= 0 ? 1 : hello.version();
        var voiceSession = getSession(webSocketSession.getId());
        voiceSession.updateProtocolVersion(version);
        log.info("xiaozhi hello received, sessionId={}, deviceId={}, clientId={}, protocolVersion={}, audioParams={}",
                webSocketSession.getId(),
                voiceSession.deviceId(),
                voiceSession.clientId(),
                version,
                hello.audioParams());
    }

    /**
     * 处理普通文本控制帧。
     *
     * @param webSocketSession WebSocket 会话
     * @param message 客户端消息
     */
    public void handleText(WebSocketSession webSocketSession, XiaozhiClientMessage message) {
        var voiceSession = getSession(webSocketSession.getId());
        if ("listen".equals(message.type()) && "start".equals(message.state())) {
            voiceSession.markListening();
            log.info("xiaozhi listen started, sessionId={}, deviceId={}, mode={}",
                    webSocketSession.getId(), voiceSession.deviceId(), message.mode());
            return;
        }
        if ("listen".equals(message.type()) && "stop".equals(message.state())) {
            voiceSession.markProcessing();
            processTurn(webSocketSession, voiceSession);
            return;
        }
        if ("listen".equals(message.type()) && "detect".equals(message.state())) {
            log.info("xiaozhi wake word detected, sessionId={}, text={}", webSocketSession.getId(), message.text());
            return;
        }
        if ("session".equals(message.type()) && "new".equals(message.state())) {
            var conversationId = voiceSession.startNewConversation();
            sendText(webSocketSession, eventFactory.session(voiceSession.sessionId(), conversationId));
            log.info("xiaozhi conversation started, sessionId={}, deviceId={}, conversationId={}",
                    webSocketSession.getId(), voiceSession.deviceId(), conversationId);
            return;
        }
        if ("session".equals(message.type()) && "clear".equals(message.state())) {
            var conversationId = voiceSession.clearConversation();
            sendText(webSocketSession, eventFactory.session(voiceSession.sessionId(), conversationId));
            log.info("xiaozhi conversation cleared, sessionId={}, deviceId={}, conversationId={}",
                    webSocketSession.getId(), voiceSession.deviceId(), conversationId);
            return;
        }
        if ("abort".equals(message.type())) {
            var playback = voiceSession.cancelPlayback();
            trySendTtsStop(webSocketSession, voiceSession, playback);
            voiceSession.markIdle();
            log.info("xiaozhi turn aborted, sessionId={}, deviceId={}, reason={}",
                    webSocketSession.getId(), voiceSession.deviceId(), message.reason());
            return;
        }
        if ("mcp".equals(message.type())) {
            mcpBridge.handleInbound(voiceSession.deviceId(), message.payload());
            log.debug("xiaozhi mcp message bridged, sessionId={}, deviceId={}",
                    webSocketSession.getId(), voiceSession.deviceId());
        }
    }

    /**
     * 处理二进制音频帧。
     *
     * @param webSocketSession WebSocket 会话
     * @param payload 二进制 payload
     */
    public void handleBinary(WebSocketSession webSocketSession, ByteBuffer payload) {
        var voiceSession = getSession(webSocketSession.getId());
        var frame = codec.decodeAudioFrame(voiceSession.protocolVersion(), payload);
        if (voiceSession.state() == XiaozhiVoiceSession.State.LISTENING) {
            voiceSession.addAudioFrame(frame);
            return;
        }
        log.debug("ignore xiaozhi binary frame outside listening, sessionId={}, state={}, bytes={}",
                webSocketSession.getId(), voiceSession.state(), frame.payload().length);
    }

    /**
     * 向在线设备主动播报文本。
     *
     * @param deviceId 设备 ID
     * @param text 播报文本
     * @return true 表示已下发播报
     */
    public boolean notifyDevice(String deviceId, String text) {
        if (deviceId == null || deviceId.isBlank() || text == null || text.isBlank()) {
            return false;
        }
        var sessionId = deviceSessionIds.get(deviceId);
        if (sessionId == null) {
            return false;
        }
        var webSocketSession = webSocketSessions.get(sessionId);
        var voiceSession = sessions.get(sessionId);
        if (webSocketSession == null || voiceSession == null || !webSocketSession.isOpen()) {
            deviceSessionIds.remove(deviceId, sessionId);
            return false;
        }
        if (voiceSession.state() != XiaozhiVoiceSession.State.IDLE) {
            log.info("xiaozhi notification skipped because session is busy, sessionId={}, deviceId={}, state={}",
                    sessionId, deviceId, voiceSession.state());
            return false;
        }
        var playback = new XiaozhiTtsPlayback(webSocketSession, voiceSession, codec, eventFactory);
        voiceSession.updatePlayback(playback);
        voiceSession.markSpeaking();
        sendText(webSocketSession, eventFactory.llmEmotion(voiceSession.sessionId(), "neutral"));
        sendText(webSocketSession, eventFactory.ttsStart(voiceSession.sessionId()));
        var sent = false;
        try {
            sent = speakSentences(webSocketSession, voiceSession, playback, List.of(text), System.nanoTime());
        } finally {
            finishNotification(webSocketSession, voiceSession, playback);
        }
        return sent;
    }

    private void processTurn(WebSocketSession webSocketSession, XiaozhiVoiceSession voiceSession) {
        try {
            var audioFrames = voiceSession.drainAudioFrames().stream()
                    .map(frame -> ByteBuffer.wrap(frame.payload()))
                    .toList();
            var audioFrameCount = audioFrames.size();
            var asrStartedAt = System.nanoTime();
            var transcription = transcribe(webSocketSession, voiceSession, audioFrames, asrStartedAt);
            var asrMillis = elapsedMillis(asrStartedAt);
            if (transcription.failed()) {
                return;
            }
            var userText = transcription.text();
            if (userText == null || userText.isBlank()) {
                log.warn("xiaozhi asr returned blank text, sessionId={}, deviceId={}, audioFrames={}, asrMillis={}",
                        webSocketSession.getId(), voiceSession.deviceId(), audioFrameCount, asrMillis);
                trySendText(webSocketSession, eventFactory.error(voiceSession.sessionId(), "asr_empty", "未识别到语音"));
                voiceSession.markIdle();
                return;
            }
            sendText(webSocketSession, eventFactory.stt(voiceSession.sessionId(), userText));

            var turnStartedAt = System.nanoTime();
            var result = streamChatAndSpeak(webSocketSession, voiceSession, userText, audioFrameCount, asrMillis, turnStartedAt);
            if (result.failed()) {
                return;
            }
            log.info("xiaozhi conversation turn, sessionId={}, deviceId={}, conversationId={}, userText={}, assistantText={}",
                    webSocketSession.getId(),
                    voiceSession.deviceId(),
                    voiceSession.conversationId(),
                    userText,
                    result.reply());
        } catch (RuntimeException exception) {
            log.warn("xiaozhi turn failed, sessionId={}, deviceId={}, message={}",
                    webSocketSession.getId(), voiceSession.deviceId(), exception.getMessage(), exception);
            voiceSession.markIdle();
        }
    }

    private Transcription transcribe(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            List<ByteBuffer> audioFrames,
            long asrStartedAt
    ) {
        try {
            return new Transcription(speechToTextClient.transcribe(audioFrames), false);
        } catch (RuntimeException exception) {
            log.warn("xiaozhi asr failed, sessionId={}, deviceId={}, asrMillis={}, message={}",
                    webSocketSession.getId(),
                    voiceSession.deviceId(),
                    elapsedMillis(asrStartedAt),
                    exception.getMessage(),
                    exception);
            trySendText(webSocketSession, eventFactory.error(voiceSession.sessionId(), "asr_failed", "语音识别失败"));
            voiceSession.markIdle();
            return new Transcription(null, true);
        }
    }

    private TurnResult streamChatAndSpeak(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            String userText,
            int audioFrameCount,
            long asrMillis,
            long turnStartedAt
    ) {
        var playback = new XiaozhiTtsPlayback(webSocketSession, voiceSession, codec, eventFactory);
        voiceSession.updatePlayback(playback);
        try {
            voiceSession.markSpeaking();
            sendText(webSocketSession, eventFactory.llmEmotion(voiceSession.sessionId(), "neutral"));
            sendText(webSocketSession, eventFactory.ttsStart(voiceSession.sessionId()));
            var extractor = new XiaozhiHermesStreamTextExtractor();
            var segmenter = new XiaozhiSentenceSegmenter();
            var reply = new StringBuilder();
            var ttsStartedAt = System.nanoTime();
            try (var chunks = hermesClient.streamChat(new HermesRequest(
                    new DeviceId(voiceSession.deviceId()),
                    new ConversationId(voiceSession.conversationId()),
                    userText
            ), hermesClientConfig)) {
                for (var chunk : (Iterable<String>) chunks::iterator) {
                    if (playback.cancelled()) {
                        break;
                    }
                    for (var text : extractor.accept(chunk)) {
                        reply.append(text);
                        if (!speakSentences(webSocketSession, voiceSession, playback, segmenter.accept(text), ttsStartedAt)) {
                            return TurnResult.cancelled(reply.toString());
                        }
                    }
                }
            }
            for (var text : extractor.flush()) {
                reply.append(text);
                if (!speakSentences(webSocketSession, voiceSession, playback, segmenter.accept(text), ttsStartedAt)) {
                    return TurnResult.cancelled(reply.toString());
                }
            }
            var finalSentence = segmenter.flush();
            if (!finalSentence.isBlank()
                    && !speakSentences(webSocketSession, voiceSession, playback, List.of(finalSentence), ttsStartedAt)) {
                return TurnResult.cancelled(reply.toString());
            }
            finishPlayback(webSocketSession, voiceSession, playback, audioFrameCount, asrMillis, turnStartedAt, ttsStartedAt);
            return TurnResult.completed(reply.toString());
        } catch (RuntimeException exception) {
            log.warn("xiaozhi hermes failed, sessionId={}, deviceId={}, message={}",
                    webSocketSession.getId(),
                    voiceSession.deviceId(),
                    exception.getMessage(),
                    exception);
            trySendTtsStop(webSocketSession, voiceSession, playback);
            trySendText(webSocketSession, eventFactory.error(voiceSession.sessionId(), "hermes_failed", "对话服务失败"));
            voiceSession.markIdle();
            return TurnResult.failure();
        } finally {
            voiceSession.updatePlayback(null);
        }
    }

    private boolean speakSentences(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            XiaozhiTtsPlayback playback,
            List<String> sentences,
            long ttsStartedAt
    ) {
        for (var sentence : sentences) {
            if (sentence == null || sentence.isBlank()) {
                continue;
            }
            try {
                var synthesizedFrames = textToSpeechClient.synthesize(sentence, new VoiceId("default"));
                if (!playback.playSentence(sentence, synthesizedFrames)) {
                    return false;
                }
            } catch (RuntimeException exception) {
                log.warn("xiaozhi tts failed, sessionId={}, deviceId={}, ttsMillis={}, message={}",
                        webSocketSession.getId(),
                        voiceSession.deviceId(),
                        elapsedMillis(ttsStartedAt),
                        exception.getMessage(),
                        exception);
                trySendTtsStop(webSocketSession, voiceSession, playback);
                trySendText(webSocketSession, eventFactory.error(voiceSession.sessionId(), "tts_failed", "语音合成失败"));
                voiceSession.markIdle();
                return false;
            } catch (IOException exception) {
                log.warn("xiaozhi tts audio send failed, sessionId={}, deviceId={}, message={}",
                        webSocketSession.getId(), voiceSession.deviceId(), exception.getMessage(), exception);
                trySendTtsStop(webSocketSession, voiceSession, playback);
                trySendText(webSocketSession, eventFactory.error(voiceSession.sessionId(), "tts_failed", "语音下发失败"));
                voiceSession.markIdle();
                return false;
            }
        }
        return true;
    }

    private void finishPlayback(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            XiaozhiTtsPlayback playback,
            int audioFrameCount,
            long asrMillis,
            long turnStartedAt,
            long ttsStartedAt
    ) {
        trySendTtsStop(webSocketSession, voiceSession, playback);
        log.info("xiaozhi turn completed, sessionId={}, deviceId={}, audioFrames={}, ttsFrames={}, asrMillis={}, hermesMillis={}, ttsMillis={}",
                webSocketSession.getId(),
                voiceSession.deviceId(),
                audioFrameCount,
                playback.sentFrames(),
                asrMillis,
                elapsedMillis(turnStartedAt),
                elapsedMillis(ttsStartedAt));
        if (!playback.cancelled()) {
            voiceSession.markIdle();
        }
    }

    private void finishNotification(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            XiaozhiTtsPlayback playback
    ) {
        trySendTtsStop(webSocketSession, voiceSession, playback);
        voiceSession.updatePlayback(null);
        if (!playback.cancelled()) {
            voiceSession.markIdle();
        }
    }

    private void trySendTtsStop(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            XiaozhiTtsPlayback playback
    ) {
        if (playback != null && !playback.markStopSent()) {
            return;
        }
        trySendText(webSocketSession, eventFactory.ttsStop(voiceSession.sessionId()));
    }

    private void sendText(WebSocketSession webSocketSession, String payload) {
        try {
            webSocketSession.sendMessage(new TextMessage(payload));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to send xiaozhi websocket message", exception);
        }
    }

    private boolean trySendText(WebSocketSession webSocketSession, String payload) {
        try {
            webSocketSession.sendMessage(new TextMessage(payload));
            return true;
        } catch (IOException exception) {
            log.warn("failed to send xiaozhi websocket failure event, sessionId={}, message={}",
                    webSocketSession.getId(), exception.getMessage(), exception);
            return false;
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private int parseProtocolVersion(String value) {
        if (value == null || value.isBlank()) {
            return 1;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return 1;
        }
    }

    private record Transcription(String text, boolean failed) {
    }

    private record TurnResult(String reply, boolean failed) {

        private static TurnResult completed(String reply) {
            return new TurnResult(reply, false);
        }

        private static TurnResult cancelled(String reply) {
            return new TurnResult(reply, false);
        }

        private static TurnResult failure() {
            return new TurnResult("", true);
        }
    }
}
