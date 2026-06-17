package com.jzb.chatbot.voice;

import com.jzb.chatbot.common.id.ConversationId;
import com.jzb.chatbot.common.id.DeviceId;
import com.jzb.chatbot.common.id.VoiceId;
import com.jzb.chatbot.hermes.HermesClient;
import com.jzb.chatbot.hermes.HermesClientConfig;
import com.jzb.chatbot.hermes.HermesRequest;
import com.jzb.chatbot.speech.SpeechToTextAudioStream;
import com.jzb.chatbot.speech.SpeechToTextClient;
import com.jzb.chatbot.speech.SpeechToTextResult;
import com.jzb.chatbot.speech.StreamingSpeechToTextClient;
import com.jzb.chatbot.speech.TextToSpeechClient;
import com.jzb.chatbot.voice.hermes.HermesAgentEvent;
import com.jzb.chatbot.voice.hermes.HermesAgentEventExtractor;
import com.jzb.chatbot.voice.mcp.XiaozhiMcpBridge;
import com.jzb.chatbot.voice.protocol.XiaozhiAudioParams;
import com.jzb.chatbot.voice.protocol.XiaozhiClientHello;
import com.jzb.chatbot.voice.protocol.XiaozhiClientMessage;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import com.jzb.chatbot.voice.protocol.XiaozhiServerEventFactory;
import com.jzb.chatbot.voice.reminder.XiaozhiReminderIntent;
import com.jzb.chatbot.voice.reminder.XiaozhiReminderRequestedEvent;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
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
public class XiaozhiVoiceSessionService implements ApplicationEventPublisherAware {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String PROTOCOL_VERSION_HEADER = "Protocol-Version";
    private static final String DEVICE_ID_HEADER = "Device-Id";
    private static final String CLIENT_ID_HEADER = "Client-Id";
    private static final String SENTENCE_ASR_PROVIDER = "sentence";

    private final XiaozhiMessageCodec codec;
    private final SpeechToTextClient speechToTextClient;
    private final HermesClient hermesClient;
    private final TextToSpeechClient textToSpeechClient;
    private final XiaozhiServerEventFactory eventFactory;
    private final HermesClientConfig hermesClientConfig;
    private final XiaozhiVoiceTokenAuth tokenAuth;
    private final XiaozhiMcpBridge mcpBridge;
    private final XiaozhiAsrMode asrMode;
    private final StreamingSpeechToTextClient streamingSpeechToTextClient;
    private final XiaozhiAudioParams audioParams;
    private final Map<String, XiaozhiVoiceSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> webSocketSessions = new ConcurrentHashMap<>();
    private final Map<String, String> deviceSessionIds = new ConcurrentHashMap<>();
    private ApplicationEventPublisher eventPublisher = event -> {
    };

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

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
            voiceSession.terminateAsrStream();
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
            handleListenStart(webSocketSession, message, voiceSession);
            return;
        }
        if ("listen".equals(message.type()) && "stop".equals(message.state())) {
            handleListenStop(webSocketSession, voiceSession);
            return;
        }
        if ("listen".equals(message.type()) && "detect".equals(message.state())) {
            log.info("xiaozhi wake word detected, sessionId={}, text={}", webSocketSession.getId(), message.text());
            return;
        }
        if ("session".equals(message.type()) && "new".equals(message.state())) {
            voiceSession.cancelPlayback();
            var conversationId = voiceSession.startNewConversation();
            sendText(webSocketSession, eventFactory.session(voiceSession.sessionId(), conversationId));
            log.info("xiaozhi conversation started, sessionId={}, deviceId={}, conversationId={}",
                    webSocketSession.getId(), voiceSession.deviceId(), conversationId);
            return;
        }
        if ("session".equals(message.type()) && "clear".equals(message.state())) {
            voiceSession.cancelPlayback();
            var conversationId = voiceSession.clearConversation();
            sendText(webSocketSession, eventFactory.session(voiceSession.sessionId(), conversationId));
            log.info("xiaozhi conversation cleared, sessionId={}, deviceId={}, conversationId={}",
                    webSocketSession.getId(), voiceSession.deviceId(), conversationId);
            return;
        }
        if ("abort".equals(message.type())) {
            voiceSession.terminateAsrStream();
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

    private void handleListenStart(
            WebSocketSession webSocketSession,
            XiaozhiClientMessage message,
            XiaozhiVoiceSession voiceSession
    ) {
        voiceSession.cancelPlayback();
        if (asrMode.streaming()) {
            var asrTurn = voiceSession.startAsrStream(audioParams.sampleRate());
            Thread.startVirtualThread(() -> processStreamingTurn(webSocketSession, voiceSession, asrTurn));
        } else {
            voiceSession.markListening();
        }
        log.info("xiaozhi listen started, sessionId={}, deviceId={}, mode={}",
                webSocketSession.getId(), voiceSession.deviceId(), message.mode());
    }

    private void handleListenStop(WebSocketSession webSocketSession, XiaozhiVoiceSession voiceSession) {
        if (asrMode.streaming()) {
            if (voiceSession.completeAsrStream() == null) {
                voiceSession.markIdle();
            } else {
                voiceSession.markProcessing();
            }
            return;
        }
        voiceSession.markProcessing();
        processSentenceTurn(webSocketSession, voiceSession);
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
            if (asrMode.streaming()) {
                voiceSession.writeAudioFrameToAsr(frame);
            } else {
                voiceSession.addAudioFrame(frame);
            }
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
        if (!voiceSession.startNotificationPlayback(playback)) {
            return false;
        }
        var turnGuard = new TurnGuard(() -> voiceSession.hasPlayback(playback));
        if (!sendPlaybackText(webSocketSession, voiceSession, turnGuard, playback,
                eventFactory.llmEmotion(voiceSession.sessionId(), "neutral"))) {
            return false;
        }
        if (!sendPlaybackText(webSocketSession, voiceSession, turnGuard, playback,
                eventFactory.ttsStart(voiceSession.sessionId()))) {
            return false;
        }
        var sent = false;
        try {
            sent = speakSentences(
                    webSocketSession,
                    voiceSession,
                    turnGuard,
                    playback,
                    List.of(text),
                    System.nanoTime()
            );
        } finally {
            finishNotification(webSocketSession, voiceSession, playback);
        }
        return sent;
    }

    private void processSentenceTurn(WebSocketSession webSocketSession, XiaozhiVoiceSession voiceSession) {
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
                log.warn("xiaozhi asr returned blank text, sessionId={}, deviceId={}, asrProvider={}, audioFrames={}, asrMillis={}",
                        webSocketSession.getId(),
                        voiceSession.deviceId(),
                        transcription.provider(),
                        audioFrameCount,
                        asrMillis);
                trySendText(webSocketSession, eventFactory.error(voiceSession.sessionId(), "asr_empty", "未识别到语音"));
                voiceSession.markIdle();
                return;
            }
            sendText(webSocketSession, eventFactory.stt(voiceSession.sessionId(), userText));

            var turnStartedAt = System.nanoTime();
            var conversationId = voiceSession.conversationId();
            var result = streamChatAndSpeak(
                    webSocketSession,
                    voiceSession,
                    TurnGuard.none(),
                    voiceSession.deviceId(),
                    conversationId,
                    userText,
                    audioFrameCount,
                    asrMillis,
                    turnStartedAt
            );
            if (result.failed()) {
                return;
            }
            log.info("xiaozhi conversation turn, sessionId={}, deviceId={}, conversationId={}, asrProvider={}, asrMillis={}, userText={}, assistantText={}",
                    webSocketSession.getId(),
                    voiceSession.deviceId(),
                    conversationId,
                    transcription.provider(),
                    asrMillis,
                    userText,
                    result.reply());
        } catch (RuntimeException exception) {
            log.warn("xiaozhi turn failed, sessionId={}, deviceId={}, message={}",
                    webSocketSession.getId(), voiceSession.deviceId(), exception.getMessage(), exception);
            voiceSession.markIdle();
        }
    }

    private void processStreamingTurn(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            XiaozhiVoiceSession.AsrTurn asrTurn
    ) {
        var asrStartedAt = System.nanoTime();
        try {
            var result = streamingSpeechToTextClient.transcribe(asrTurn.audioStream());
            var asrMillis = elapsedMillis(asrStartedAt);
            if (!voiceSession.isActiveAsrTurn(asrTurn)) {
                return;
            }
            if (voiceSession.clearAsrStreamIfListening(asrTurn)) {
                return;
            }
            var userText = result == null ? "" : result.text();
            if (userText == null || userText.isBlank()) {
                log.warn("xiaozhi streaming asr returned blank text, sessionId={}, deviceId={}, asrProvider={}, asrMillis={}",
                        webSocketSession.getId(),
                        voiceSession.deviceId(),
                        provider(result),
                        asrMillis);
                trySendText(webSocketSession, eventFactory.error(voiceSession.sessionId(), "asr_empty", "未识别到语音"));
                voiceSession.markIdle();
                return;
            }
            if (!sendAsrTurnText(
                    webSocketSession,
                    voiceSession,
                    asrTurn,
                    () -> eventFactory.stt(voiceSession.sessionId(), userText)
            )) {
                return;
            }

            var turnStartedAt = System.nanoTime();
            var turnResult = streamChatAndSpeak(
                    webSocketSession,
                    voiceSession,
                    new TurnGuard(() -> voiceSession.isCurrentAsrTurn(asrTurn)),
                    asrTurn.deviceId(),
                    asrTurn.conversationId(),
                    userText,
                    0,
                    asrMillis,
                    turnStartedAt
            );
            if (turnResult.failed()) {
                return;
            }
            log.info("xiaozhi streaming conversation turn, sessionId={}, deviceId={}, conversationId={}, asrProvider={}, asrMillis={}, userText={}, assistantText={}",
                    webSocketSession.getId(),
                    voiceSession.deviceId(),
                    asrTurn.conversationId(),
                    provider(result),
                    asrMillis,
                    userText,
                    turnResult.reply());
        } catch (RuntimeException exception) {
            if (!voiceSession.isActiveAsrTurn(asrTurn)) {
                return;
            }
            log.warn("xiaozhi streaming asr failed, sessionId={}, deviceId={}, asrProvider={}, asrMillis={}, message={}",
                    webSocketSession.getId(),
                    voiceSession.deviceId(),
                    "unknown",
                    elapsedMillis(asrStartedAt),
                    exception.getMessage(),
                    exception);
            trySendAsrTurnText(
                    webSocketSession,
                    voiceSession,
                    asrTurn,
                    eventFactory.error(voiceSession.sessionId(), "asr_failed", "语音识别失败")
            );
            voiceSession.markIdleIfAsrTurn(asrTurn);
        } finally {
            voiceSession.clearAsrStream(asrTurn);
        }
    }

    private void scheduleReminder(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            XiaozhiReminderIntent reminderIntent,
            int audioFrameCount,
            long asrMillis
    ) {
        publishReminderRequest(voiceSession, reminderIntent);
        var turnStartedAt = System.nanoTime();
        var ttsStartedAt = System.nanoTime();
        var playback = new XiaozhiTtsPlayback(webSocketSession, voiceSession, codec, eventFactory);
        if (!voiceSession.startPlaybackIfActive(
                playback,
                () -> voiceSession.state() == XiaozhiVoiceSession.State.PROCESSING
        )) {
            return;
        }
        var turnGuard = new TurnGuard(() -> voiceSession.hasPlayback(playback));
        try {
            if (!sendPlaybackText(webSocketSession, voiceSession, turnGuard, playback,
                    eventFactory.llmEmotion(voiceSession.sessionId(), "neutral"))) {
                return;
            }
            if (!sendPlaybackText(webSocketSession, voiceSession, turnGuard, playback,
                    eventFactory.ttsStart(voiceSession.sessionId()))) {
                return;
            }
            speakSentences(
                    webSocketSession,
                    voiceSession,
                    turnGuard,
                    playback,
                    List.of(reminderIntent.confirmationText()),
                    ttsStartedAt
            );
            finishPlayback(
                    webSocketSession,
                    voiceSession,
                    turnGuard,
                    playback,
                    audioFrameCount,
                    asrMillis,
                    turnStartedAt,
                    ttsStartedAt
            );
            log.info("xiaozhi reminder scheduled, sessionId={}, deviceId={}, delaySeconds={}, message={}",
                    webSocketSession.getId(), voiceSession.deviceId(), reminderIntent.delaySeconds(), reminderIntent.message());
        } finally {
            voiceSession.markIdleIfPlayback(playback);
        }
    }

    private void publishReminderRequest(
            XiaozhiVoiceSession voiceSession,
            XiaozhiReminderIntent reminderIntent
    ) {
        eventPublisher.publishEvent(new XiaozhiReminderRequestedEvent(
                voiceSession.deviceId(),
                reminderIntent.message(),
                reminderIntent.delaySeconds()
        ));
    }

    private Transcription transcribe(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            List<ByteBuffer> audioFrames,
            long asrStartedAt
    ) {
        try {
            return new Transcription(speechToTextClient.transcribe(audioFrames), SENTENCE_ASR_PROVIDER, false);
        } catch (RuntimeException exception) {
            log.warn("xiaozhi asr failed, sessionId={}, deviceId={}, asrProvider={}, asrMillis={}, message={}",
                    webSocketSession.getId(),
                    voiceSession.deviceId(),
                    SENTENCE_ASR_PROVIDER,
                    elapsedMillis(asrStartedAt),
                    exception.getMessage(),
                    exception);
            trySendText(webSocketSession, eventFactory.error(voiceSession.sessionId(), "asr_failed", "语音识别失败"));
            voiceSession.markIdle();
            return new Transcription(null, SENTENCE_ASR_PROVIDER, true);
        }
    }

    private TurnResult streamChatAndSpeak(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            TurnGuard turnGuard,
            String deviceId,
            String conversationId,
            String userText,
            int audioFrameCount,
            long asrMillis,
            long turnStartedAt
    ) {
        if (!turnGuard.active()) {
            return TurnResult.cancelled("");
        }
        var playback = new XiaozhiTtsPlayback(webSocketSession, voiceSession, codec, eventFactory);
        try {
            if (!voiceSession.startPlaybackIfActive(playback, turnGuard::active)) {
                return TurnResult.cancelled("");
            }
            if (playback.cancelled() || !turnGuard.active()) {
                return TurnResult.cancelled("");
            }
            if (!sendPlaybackText(webSocketSession, voiceSession, turnGuard, playback,
                    eventFactory.llmEmotion(voiceSession.sessionId(), "neutral"))) {
                return TurnResult.cancelled("");
            }
            if (!sendPlaybackText(webSocketSession, voiceSession, turnGuard, playback,
                    eventFactory.ttsStart(voiceSession.sessionId()))) {
                return TurnResult.cancelled("");
            }
            var extractor = new XiaozhiHermesStreamTextExtractor();
            var eventExtractor = new HermesAgentEventExtractor();
            var segmenter = new XiaozhiSentenceSegmenter();
            var reply = new StringBuilder();
            String reminderConfirmationText = null;
            var ttsStartedAt = System.nanoTime();
            try (var chunks = hermesClient.streamChat(new HermesRequest(
                    new DeviceId(deviceId),
                    new ConversationId(conversationId),
                    userText
            ), hermesClientConfig)) {
                for (var chunk : (Iterable<String>) chunks::iterator) {
                    if (playback.cancelled() || !turnGuard.active()) {
                        break;
                    }
                    for (var event : eventExtractor.accept(chunk)) {
                        var confirmationText = handleHermesAgentEvent(
                                webSocketSession,
                                voiceSession,
                                turnGuard,
                                event,
                                audioFrameCount,
                                asrMillis
                        );
                        if (reminderConfirmationText == null && confirmationText != null && !confirmationText.isBlank()) {
                            reminderConfirmationText = confirmationText;
                        }
                    }
                    if (playback.cancelled() || !turnGuard.active()) {
                        break;
                    }
                    for (var text : extractor.accept(chunk)) {
                        reply.append(text);
                        if (!speakSentences(webSocketSession, voiceSession, turnGuard, playback, segmenter.accept(text), ttsStartedAt)) {
                            return TurnResult.cancelled(reply.toString());
                        }
                    }
                }
            }
            for (var text : extractor.flush()) {
                reply.append(text);
                if (!speakSentences(webSocketSession, voiceSession, turnGuard, playback, segmenter.accept(text), ttsStartedAt)) {
                    return TurnResult.cancelled(reply.toString());
                }
            }
            var finalSentence = segmenter.flush();
            if (!finalSentence.isBlank()
                    && !speakSentences(webSocketSession, voiceSession, turnGuard, playback, List.of(finalSentence), ttsStartedAt)) {
                return TurnResult.cancelled(reply.toString());
            }
            if (reply.toString().isBlank() && reminderConfirmationText != null && !reminderConfirmationText.isBlank()) {
                reply.append(reminderConfirmationText);
                if (!speakSentences(webSocketSession, voiceSession, turnGuard, playback, List.of(reminderConfirmationText), ttsStartedAt)) {
                    return TurnResult.cancelled(reply.toString());
                }
            }
            finishPlayback(webSocketSession, voiceSession, turnGuard, playback, audioFrameCount, asrMillis, turnStartedAt, ttsStartedAt);
            return TurnResult.completed(reply.toString());
        } catch (RuntimeException exception) {
            if (!turnGuard.active()) {
                return TurnResult.cancelled("");
            }
            log.warn("xiaozhi hermes failed, sessionId={}, deviceId={}, message={}",
                    webSocketSession.getId(),
                    deviceId,
                    exception.getMessage(),
                    exception);
            trySendPlaybackFailure(
                    webSocketSession,
                    voiceSession,
                    turnGuard,
                    playback,
                    "hermes_failed",
                    "对话服务失败"
            );
            return TurnResult.failure();
        } finally {
            voiceSession.clearPlayback(playback);
        }
    }

    private String handleHermesAgentEvent(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            TurnGuard turnGuard,
            HermesAgentEvent event,
            int audioFrameCount,
            long asrMillis
    ) {
        if (!turnGuard.active() || !"create_reminder".equals(event.action())) {
            return null;
        }
        var reminderIntent = new XiaozhiReminderIntent(
                event.message(),
                event.delaySeconds(),
                event.confirmationText() == null ? "" : event.confirmationText()
        );
        publishReminderRequest(voiceSession, reminderIntent);
        return turnGuard.active() ? reminderIntent.confirmationText() : null;
    }

    private boolean speakSentences(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            TurnGuard turnGuard,
            XiaozhiTtsPlayback playback,
            List<String> sentences,
            long ttsStartedAt
    ) {
        for (var sentence : sentences) {
            if (sentence == null || sentence.isBlank()) {
                continue;
            }
            if (!turnGuard.active()) {
                return false;
            }
            try {
                var synthesizedFrames = textToSpeechClient.synthesize(sentence, new VoiceId("default"));
                if (!turnGuard.active()) {
                    return false;
                }
                if (!playback.playSentence(sentence, synthesizedFrames, turnGuard::active)) {
                    return false;
                }
            } catch (RuntimeException exception) {
                if (!turnGuard.active()) {
                    return false;
                }
                log.warn("xiaozhi tts failed, sessionId={}, deviceId={}, ttsMillis={}, message={}",
                        webSocketSession.getId(),
                        voiceSession.deviceId(),
                        elapsedMillis(ttsStartedAt),
                        exception.getMessage(),
                        exception);
                trySendPlaybackFailure(
                        webSocketSession,
                        voiceSession,
                        turnGuard,
                        playback,
                        "tts_failed",
                        "语音合成失败"
                );
                return false;
            } catch (IOException exception) {
                if (!turnGuard.active()) {
                    return false;
                }
                log.warn("xiaozhi tts audio send failed, sessionId={}, deviceId={}, message={}",
                        webSocketSession.getId(), voiceSession.deviceId(), exception.getMessage(), exception);
                trySendPlaybackFailure(
                        webSocketSession,
                        voiceSession,
                        turnGuard,
                        playback,
                        "tts_failed",
                        "语音下发失败"
                );
                return false;
            }
        }
        return true;
    }

    private void finishPlayback(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            TurnGuard turnGuard,
            XiaozhiTtsPlayback playback,
            int audioFrameCount,
            long asrMillis,
            long turnStartedAt,
            long ttsStartedAt
    ) {
        if (playback.cancelled() || !turnGuard.active() || !voiceSession.hasPlayback(playback)) {
            return;
        }
        if (!sendPlaybackTtsStop(webSocketSession, voiceSession, turnGuard, playback)) {
            return;
        }
        if (playback.cancelled() || !turnGuard.active() || !voiceSession.hasPlayback(playback)) {
            return;
        }
        log.info("xiaozhi turn completed, sessionId={}, deviceId={}, audioFrames={}, ttsFrames={}, asrMillis={}, hermesMillis={}, ttsMillis={}",
                webSocketSession.getId(),
                voiceSession.deviceId(),
                audioFrameCount,
                playback.sentFrames(),
                asrMillis,
                elapsedMillis(turnStartedAt),
                elapsedMillis(ttsStartedAt));
        voiceSession.markIdleIfPlayback(playback);
    }

    private void finishNotification(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            XiaozhiTtsPlayback playback
    ) {
        var turnGuard = new TurnGuard(() -> voiceSession.hasPlayback(playback));
        sendPlaybackTtsStop(webSocketSession, voiceSession, turnGuard, playback);
        voiceSession.markIdleIfPlayback(playback);
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

    private boolean sendPlaybackText(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            TurnGuard turnGuard,
            XiaozhiTtsPlayback playback,
            String payload
    ) {
        try {
            if (!turnGuard.active() || !voiceSession.hasPlayback(playback)) {
                playback.cancel();
                return false;
            }
            webSocketSession.sendMessage(new TextMessage(payload));
            return turnGuard.active() && voiceSession.hasPlayback(playback);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to send xiaozhi playback websocket message", exception);
        }
    }

    private boolean sendPlaybackTtsStop(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            TurnGuard turnGuard,
            XiaozhiTtsPlayback playback
    ) {
        if (!playback.markStopSent()) {
            return true;
        }
        return sendPlaybackText(webSocketSession, voiceSession, turnGuard, playback, eventFactory.ttsStop(voiceSession.sessionId()));
    }

    private void trySendPlaybackFailure(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            TurnGuard turnGuard,
            XiaozhiTtsPlayback playback,
            String code,
            String message
    ) {
        sendPlaybackTtsStop(webSocketSession, voiceSession, turnGuard, playback);
        sendPlaybackText(
                webSocketSession,
                voiceSession,
                turnGuard,
                playback,
                eventFactory.error(voiceSession.sessionId(), code, message)
        );
        voiceSession.markIdleIfPlayback(playback);
    }

    private boolean sendAsrTurnText(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            XiaozhiVoiceSession.AsrTurn asrTurn,
            java.util.function.Supplier<String> payloadSupplier
    ) {
        if (!voiceSession.isCurrentAsrTurn(asrTurn)) {
            return false;
        }
        var payload = payloadSupplier.get();
        if (!voiceSession.isCurrentAsrTurn(asrTurn)) {
            return false;
        }
        try {
            webSocketSession.sendMessage(new TextMessage(payload));
            return voiceSession.isCurrentAsrTurn(asrTurn);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to send xiaozhi asr-turn websocket message", exception);
        }
    }

    private boolean trySendAsrTurnText(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            XiaozhiVoiceSession.AsrTurn asrTurn,
            String payload
    ) {
        if (!voiceSession.isCurrentAsrTurn(asrTurn)) {
            return false;
        }
        try {
            webSocketSession.sendMessage(new TextMessage(payload));
            return voiceSession.isCurrentAsrTurn(asrTurn);
        } catch (IOException exception) {
            log.warn("failed to send xiaozhi asr-turn websocket failure event, sessionId={}, message={}",
                    webSocketSession.getId(), exception.getMessage(), exception);
            return false;
        }
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

    private String provider(SpeechToTextResult result) {
        return result == null ? "unknown" : result.provider();
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

    private record Transcription(String text, String provider, boolean failed) {
    }

    private record TurnGuard(BooleanSupplier activeSupplier) {

        private static TurnGuard none() {
            return new TurnGuard(() -> true);
        }

        private boolean active() {
            return activeSupplier.getAsBoolean();
        }
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
