package com.jzb.chatbot.voice;

import com.jzb.chatbot.common.id.ConversationId;
import com.jzb.chatbot.common.id.DeviceId;
import com.jzb.chatbot.hermes.HermesClient;
import com.jzb.chatbot.hermes.HermesClientConfig;
import com.jzb.chatbot.hermes.HermesRequest;
import com.jzb.chatbot.speech.SpeechToTextClient;
import com.jzb.chatbot.voice.mcp.XiaozhiMcpBridge;
import com.jzb.chatbot.voice.protocol.XiaozhiClientHello;
import com.jzb.chatbot.voice.protocol.XiaozhiClientMessage;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import com.jzb.chatbot.voice.protocol.XiaozhiServerEventFactory;
import com.jzb.chatbot.voice.reminder.XiaozhiReminderIntent;
import com.jzb.chatbot.voice.reminder.XiaozhiReminderRequestedEvent;
import com.jzb.chatbot.voice.tts.XiaozhiTtsRequest;
import com.jzb.chatbot.voice.tts.XiaozhiTtsResult;
import com.jzb.chatbot.voice.tts.XiaozhiTtsRuntime;
import com.jzb.chatbot.voice.tts.XiaozhiVoiceProfileResolver;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
public class XiaozhiVoiceSessionService implements ApplicationEventPublisherAware {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String PROTOCOL_VERSION_HEADER = "Protocol-Version";
    private static final String DEVICE_ID_HEADER = "Device-Id";
    private static final String CLIENT_ID_HEADER = "Client-Id";

    private final XiaozhiMessageCodec codec;
    private final SpeechToTextClient speechToTextClient;
    private final HermesClient hermesClient;
    private final XiaozhiTtsRuntime ttsRuntime;
    private final XiaozhiServerEventFactory eventFactory;
    private final HermesClientConfig hermesClientConfig;
    private final XiaozhiVoiceTokenAuth tokenAuth;
    private final XiaozhiMcpBridge mcpBridge;
    private final XiaozhiVoiceProfileResolver voiceProfileResolver;
    private final Map<String, XiaozhiVoiceSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> webSocketSessions = new ConcurrentHashMap<>();
    private final Map<String, String> deviceSessionIds = new ConcurrentHashMap<>();
    private ApplicationEventPublisher eventPublisher = event -> {
    };

    public XiaozhiVoiceSessionService(
            XiaozhiMessageCodec codec,
            SpeechToTextClient speechToTextClient,
            HermesClient hermesClient,
            XiaozhiTtsRuntime ttsRuntime,
            XiaozhiServerEventFactory eventFactory,
            HermesClientConfig hermesClientConfig,
            XiaozhiVoiceTokenAuth tokenAuth,
            XiaozhiMcpBridge mcpBridge,
            XiaozhiVoiceProfileResolver voiceProfileResolver
    ) {
        this.codec = codec;
        this.speechToTextClient = speechToTextClient;
        this.hermesClient = hermesClient;
        this.ttsRuntime = ttsRuntime;
        this.eventFactory = eventFactory;
        this.hermesClientConfig = hermesClientConfig;
        this.tokenAuth = tokenAuth;
        this.mcpBridge = mcpBridge;
        this.voiceProfileResolver = voiceProfileResolver;
    }

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
            voiceSession.requestAbort();
            ttsRuntime.cancel(voiceSession.sessionId());
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
            cancelCurrentTurnPlayback(voiceSession);
            voiceSession.markListening();
            log.info("xiaozhi listen started, sessionId={}, deviceId={}, mode={}",
                    webSocketSession.getId(), voiceSession.deviceId(), message.mode());
            return;
        }
        if ("listen".equals(message.type()) && "stop".equals(message.state())) {
            var processingAudio = voiceSession.tryDrainAudioFramesForProcessing();
            if (!processingAudio.accepted()) {
                log.debug("ignore xiaozhi listen stop outside listening, sessionId={}, deviceId={}, state={}",
                        webSocketSession.getId(), voiceSession.deviceId(), voiceSession.state());
                return;
            }
            processTurn(webSocketSession, voiceSession, processingAudio);
            return;
        }
        if ("listen".equals(message.type()) && "detect".equals(message.state())) {
            log.info("xiaozhi wake word detected, sessionId={}, text={}", webSocketSession.getId(), message.text());
            return;
        }
        if ("session".equals(message.type()) && "new".equals(message.state())) {
            cancelCurrentTurnPlayback(voiceSession);
            var conversationId = voiceSession.startNewConversation();
            sendText(webSocketSession, eventFactory.session(voiceSession.sessionId(), conversationId));
            log.info("xiaozhi conversation started, sessionId={}, deviceId={}, conversationId={}",
                    webSocketSession.getId(), voiceSession.deviceId(), conversationId);
            return;
        }
        if ("session".equals(message.type()) && "clear".equals(message.state())) {
            cancelCurrentTurnPlayback(voiceSession);
            var conversationId = voiceSession.clearConversation();
            sendText(webSocketSession, eventFactory.session(voiceSession.sessionId(), conversationId));
            log.info("xiaozhi conversation cleared, sessionId={}, deviceId={}, conversationId={}",
                    webSocketSession.getId(), voiceSession.deviceId(), conversationId);
            return;
        }
        if ("abort".equals(message.type())) {
            voiceSession.requestAbort();
            ttsRuntime.cancel(voiceSession.sessionId());
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
        if (voiceSession.addAudioFrameIfListening(frame)) {
            return;
        }
        var state = voiceSession.state();
        log.debug("ignore xiaozhi binary frame outside listening, sessionId={}, state={}, bytes={}",
                webSocketSession.getId(), state, frame.payload().length);
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
        var playbackGeneration = voiceSession.tryBeginNotificationPlayback();
        if (playbackGeneration < 0) {
            log.info("xiaozhi notification skipped because session is busy, sessionId={}, deviceId={}, state={}",
                    sessionId, deviceId, voiceSession.state());
            return false;
        }
        var ttsStartedAt = System.nanoTime();
        try {
            var profile = voiceProfileResolver.resolve(voiceSession.deviceId());
            return ttsRuntime.speak(new XiaozhiTtsRequest(
                    webSocketSession,
                    voiceSession,
                    List.of(text),
                    profile.toTtsOptions(),
                    playbackGeneration,
                    () -> notificationCancelled(webSocketSession, voiceSession, playbackGeneration)
            ));
        } catch (RuntimeException exception) {
            handleNotificationTtsFailure(
                    webSocketSession, voiceSession, playbackGeneration, ttsStartedAt, "语音合成失败", exception
            );
            return false;
        } finally {
            voiceSession.completePlayback(playbackGeneration);
        }
    }

    private void processTurn(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            XiaozhiVoiceSession.ProcessingAudio processingAudio
    ) {
        var turnGeneration = processingAudio.turnGeneration();
        try {
            var audioFrames = processingAudio.frames().stream()
                    .map(frame -> ByteBuffer.wrap(frame.payload()))
                    .toList();
            var audioFrameCount = audioFrames.size();
            var asrStartedAt = System.nanoTime();
            var transcription = transcribe(webSocketSession, voiceSession, turnGeneration, audioFrames, asrStartedAt);
            var asrMillis = elapsedMillis(asrStartedAt);
            if (transcription.failed()) {
                return;
            }
            var userText = transcription.text();
            if (turnCancelled(webSocketSession, voiceSession, turnGeneration)) {
                cancelTurnBeforeRuntime(webSocketSession, voiceSession, "", asrMillis);
                return;
            }
            if (userText == null || userText.isBlank()) {
                log.warn("xiaozhi asr returned blank text, sessionId={}, deviceId={}, audioFrames={}, asrMillis={}",
                        webSocketSession.getId(), voiceSession.deviceId(), audioFrameCount, asrMillis);
                trySendTurnErrorIfActive(webSocketSession, voiceSession, turnGeneration, "asr_empty", "未识别到语音");
                voiceSession.markIdleIfTurnActive(turnGeneration);
                return;
            }
            sendText(webSocketSession, eventFactory.stt(voiceSession.sessionId(), userText));
            if (turnCancelled(webSocketSession, voiceSession, turnGeneration)) {
                cancelTurnBeforeRuntime(webSocketSession, voiceSession, "", asrMillis);
                return;
            }

            var reminderIntent = XiaozhiReminderIntent.parse(userText);
            if (reminderIntent != null) {
                scheduleReminder(webSocketSession, voiceSession, turnGeneration, reminderIntent, asrMillis);
                return;
            }

            var result = streamChatAndSpeak(webSocketSession,
                    voiceSession,
                    turnGeneration,
                    userText,
                    asrMillis);
            if (!result.completed()) {
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
            voiceSession.markIdleIfTurnActive(turnGeneration);
        }
    }

    private void scheduleReminder(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            long turnGeneration,
            XiaozhiReminderIntent reminderIntent,
            long asrMillis
    ) {
        if (turnCancelled(webSocketSession, voiceSession, turnGeneration)) {
            cancelTurnBeforeRuntime(webSocketSession, voiceSession, "", asrMillis);
            return;
        }
        eventPublisher.publishEvent(new XiaozhiReminderRequestedEvent(
                voiceSession.deviceId(),
                reminderIntent.message(),
                reminderIntent.delaySeconds()
        ));
        var ttsOperationStartedAt = System.nanoTime();
        var playbackResult = speakWithRuntime(webSocketSession,
                voiceSession,
                turnGeneration,
                List.of(reminderIntent.confirmationText()),
                ttsOperationStartedAt,
                "语音合成失败");
        logTurnCompleted(webSocketSession,
                voiceSession,
                playbackResult,
                asrMillis,
                0,
                playbackResult.ttsMillis());
        log.info("xiaozhi reminder scheduled, sessionId={}, deviceId={}, delaySeconds={}, message={}",
                webSocketSession.getId(), voiceSession.deviceId(), reminderIntent.delaySeconds(), reminderIntent.message());
    }

    private Transcription transcribe(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            long turnGeneration,
            List<ByteBuffer> audioFrames,
            long asrStartedAt
    ) {
        try {
            return new Transcription(speechToTextClient.transcribe(audioFrames), false);
        } catch (RuntimeException exception) {
            if (turnCancelled(webSocketSession, voiceSession, turnGeneration)) {
                return new Transcription(null, true);
            }
            log.warn("xiaozhi asr failed, sessionId={}, deviceId={}, asrMillis={}, message={}",
                    webSocketSession.getId(),
                    voiceSession.deviceId(),
                    elapsedMillis(asrStartedAt),
                    exception.getMessage(),
                    exception);
            trySendTurnErrorIfActive(webSocketSession, voiceSession, turnGeneration, "asr_failed", "语音识别失败");
            voiceSession.markIdleIfTurnActive(turnGeneration);
            return new Transcription(null, true);
        }
    }

    private TurnResult streamChatAndSpeak(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            long turnGeneration,
            String userText,
            long asrMillis
    ) {
        var extractor = new XiaozhiHermesStreamTextExtractor();
        var segmenter = new XiaozhiSentenceSegmenter();
        var reply = new StringBuilder();
        var sentences = new ArrayList<String>();
        var hermesStartedAt = System.nanoTime();
        try {
            try (var chunks = hermesClient.streamChat(new HermesRequest(
                    new DeviceId(voiceSession.deviceId()),
                    new ConversationId(voiceSession.conversationId()),
                    userText
            ), hermesClientConfig)) {
                for (var chunk : (Iterable<String>) chunks::iterator) {
                    if (voiceSession.abortRequested(turnGeneration)) {
                        return cancelTurnBeforeRuntime(webSocketSession, voiceSession, reply.toString(), asrMillis, hermesStartedAt);
                    }
                    for (var text : extractor.accept(chunk)) {
                        if (voiceSession.abortRequested(turnGeneration)) {
                            return cancelTurnBeforeRuntime(webSocketSession, voiceSession, reply.toString(), asrMillis, hermesStartedAt);
                        }
                        reply.append(text);
                        sentences.addAll(segmenter.accept(text));
                    }
                }
            }
            if (voiceSession.abortRequested(turnGeneration)) {
                return cancelTurnBeforeRuntime(webSocketSession, voiceSession, reply.toString(), asrMillis, hermesStartedAt);
            }
            for (var text : extractor.flush()) {
                if (voiceSession.abortRequested(turnGeneration)) {
                    return cancelTurnBeforeRuntime(webSocketSession, voiceSession, reply.toString(), asrMillis, hermesStartedAt);
                }
                reply.append(text);
                sentences.addAll(segmenter.accept(text));
            }
            var finalSentence = segmenter.flush();
            if (!finalSentence.isBlank()) {
                sentences.add(finalSentence);
            }
            if (voiceSession.abortRequested(turnGeneration)) {
                return cancelTurnBeforeRuntime(webSocketSession, voiceSession, reply.toString(), asrMillis, hermesStartedAt);
            }
            var hermesMillis = elapsedMillis(hermesStartedAt);
            var ttsOperationStartedAt = System.nanoTime();
            var playbackResult = speakWithRuntime(webSocketSession,
                    voiceSession,
                    turnGeneration,
                    sentences,
                    ttsOperationStartedAt,
                    "语音合成失败");
            if (playbackResult.cancelled()) {
                logTurnCompleted(webSocketSession,
                        voiceSession,
                        playbackResult,
                        asrMillis,
                        hermesMillis,
                        playbackResult.ttsMillis());
                return TurnResult.cancelled(reply.toString());
            }
            if (playbackResult.failed()) {
                return TurnResult.failure();
            }
            logTurnCompleted(webSocketSession,
                    voiceSession,
                    playbackResult,
                    asrMillis,
                    hermesMillis,
                    playbackResult.ttsMillis());
            return TurnResult.completed(reply.toString());
        } catch (RuntimeException exception) {
            if (turnCancelled(webSocketSession, voiceSession, turnGeneration)) {
                return cancelTurnBeforeRuntime(webSocketSession, voiceSession, reply.toString(), asrMillis, hermesStartedAt);
            }
            log.warn("xiaozhi hermes failed, sessionId={}, deviceId={}, message={}",
                    webSocketSession.getId(),
                    voiceSession.deviceId(),
                    exception.getMessage(),
                    exception);
            trySendText(webSocketSession, eventFactory.error(voiceSession.sessionId(), "hermes_failed", "对话服务失败"));
            voiceSession.markIdleIfTurnActive(turnGeneration);
            return TurnResult.failure();
        }
    }

    private PlaybackResult speakWithRuntime(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            long turnGeneration,
            List<String> sentences,
            long ttsStartedAt,
            String errorMessage
    ) {
        try {
            var profile = voiceProfileResolver.resolve(voiceSession.deviceId());
            if (cancelledBeforeRuntime(webSocketSession, voiceSession, turnGeneration)) {
                return PlaybackResult.cancelledBeforeRuntime();
            }
            var runtimeStartedAt = System.nanoTime();
            var result = ttsRuntime.play(new XiaozhiTtsRequest(
                    webSocketSession,
                    voiceSession,
                    sentences,
                    profile.toTtsOptions(),
                    () -> cancelledBeforeRuntime(webSocketSession, voiceSession, turnGeneration)
            ));
            var ttsMillis = elapsedMillis(runtimeStartedAt);
            if (result.cancelled()) {
                return PlaybackResult.cancelled(result, ttsMillis);
            }
            return result.played() ? PlaybackResult.completed(result, ttsMillis) : PlaybackResult.cancelled(result, ttsMillis);
        } catch (RuntimeException exception) {
            handleTurnTtsFailure(webSocketSession, voiceSession, turnGeneration, ttsStartedAt, errorMessage, exception);
            return PlaybackResult.FAILED;
        }
    }

    private TurnResult cancelTurnBeforeRuntime(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            String reply,
            long asrMillis
    ) {
        logTurnCompleted(webSocketSession,
                voiceSession,
                PlaybackResult.cancelledBeforeRuntime(),
                asrMillis,
                0,
                0);
        return TurnResult.cancelled(reply);
    }

    private TurnResult cancelTurnBeforeRuntime(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            String reply,
            long asrMillis,
            long hermesStartedAt
    ) {
        logTurnCompleted(webSocketSession,
                voiceSession,
                PlaybackResult.cancelledBeforeRuntime(),
                asrMillis,
                elapsedMillis(hermesStartedAt),
                0);
        return TurnResult.cancelled(reply);
    }

    private void logTurnCompleted(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            PlaybackResult playbackResult,
            long asrMillis,
            long hermesMillis,
            long ttsMillis
    ) {
        var ttsResult = playbackResult.ttsResult();
        if (ttsResult == null) {
            return;
        }
        log.info("xiaozhi turn completed, sessionId={}, deviceId={}, conversationId={}, sentenceCount={}, ttsFrames={}, asrMillis={}, hermesMillis={}, ttsMillis={}, cancelled={}",
                webSocketSession.getId(),
                voiceSession.deviceId(),
                voiceSession.conversationId(),
                ttsResult.sentenceCount(),
                ttsResult.ttsFrames(),
                asrMillis,
                hermesMillis,
                ttsMillis,
                ttsResult.cancelled());
    }

    private boolean turnCancelled(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            long turnGeneration
    ) {
        return cancelledBeforeRuntime(webSocketSession, voiceSession, turnGeneration);
    }

    private void handleNotificationTtsFailure(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            long playbackGeneration,
            long ttsStartedAt,
            String errorMessage,
            RuntimeException exception
    ) {
        sendTtsFailure(webSocketSession, voiceSession, ttsStartedAt, errorMessage, exception);
        voiceSession.completePlayback(playbackGeneration);
    }

    private void handleTurnTtsFailure(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            long turnGeneration,
            long ttsStartedAt,
            String errorMessage,
            RuntimeException exception
    ) {
        logTtsFailure(webSocketSession, voiceSession, ttsStartedAt, exception);
        if (sessions.get(webSocketSession.getId()) == voiceSession) {
            voiceSession.runIfRegularTurnNotCancelled(turnGeneration, () ->
                    trySendText(webSocketSession, eventFactory.error(voiceSession.sessionId(), "tts_failed", errorMessage)));
        }
        voiceSession.markIdleIfTurnActive(turnGeneration);
    }

    private void sendTtsFailure(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            long ttsStartedAt,
            String errorMessage,
            RuntimeException exception
    ) {
        logTtsFailure(webSocketSession, voiceSession, ttsStartedAt, exception);
        trySendText(webSocketSession, eventFactory.error(voiceSession.sessionId(), "tts_failed", errorMessage));
    }

    private void logTtsFailure(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            long ttsStartedAt,
            RuntimeException exception
    ) {
        log.warn("xiaozhi tts failed, sessionId={}, deviceId={}, ttsMillis={}, message={}",
                webSocketSession.getId(),
                voiceSession.deviceId(),
                elapsedMillis(ttsStartedAt),
                exception.getMessage(),
                exception);
    }

    private boolean cancelledBeforeRuntime(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            long turnGeneration
    ) {
        return voiceSession.regularTurnCancelled(turnGeneration) || sessions.get(webSocketSession.getId()) != voiceSession;
    }

    private void cancelCurrentTurnPlayback(XiaozhiVoiceSession voiceSession) {
        voiceSession.requestAbort();
        ttsRuntime.cancel(voiceSession.sessionId());
    }

    private void trySendTurnErrorIfActive(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            long turnGeneration,
            String code,
            String message
    ) {
        if (!turnCancelled(webSocketSession, voiceSession, turnGeneration)) {
            trySendText(webSocketSession, eventFactory.error(voiceSession.sessionId(), code, message));
        }
    }

    private boolean notificationCancelled(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            long playbackGeneration
    ) {
        return !webSocketSession.isOpen()
                || sessions.get(webSocketSession.getId()) != voiceSession
                || !webSocketSession.getId().equals(deviceSessionIds.get(voiceSession.deviceId()))
                || !voiceSession.playbackActive(playbackGeneration);
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

    private record PlaybackResult(Status status, XiaozhiTtsResult ttsResult, long ttsMillis) {

        private enum Status {
            COMPLETED,
            CANCELLED,
            FAILED
        }

        private static final PlaybackResult FAILED = new PlaybackResult(Status.FAILED, null, 0);

        private static PlaybackResult completed(XiaozhiTtsResult ttsResult, long ttsMillis) {
            return new PlaybackResult(Status.COMPLETED, ttsResult, ttsMillis);
        }

        private static PlaybackResult cancelled(XiaozhiTtsResult ttsResult, long ttsMillis) {
            return new PlaybackResult(Status.CANCELLED, ttsResult, ttsMillis);
        }

        private static PlaybackResult cancelledBeforeRuntime() {
            return new PlaybackResult(Status.CANCELLED, new XiaozhiTtsResult(false, 0, 0, true), 0);
        }

        private boolean completed() {
            return status == Status.COMPLETED;
        }

        private boolean cancelled() {
            return status == Status.CANCELLED;
        }

        private boolean failed() {
            return status == Status.FAILED;
        }
    }

    private record TurnResult(String reply, Status status) {

        private enum Status {
            COMPLETED,
            CANCELLED,
            FAILED
        }

        private static TurnResult completed(String reply) {
            return new TurnResult(reply, Status.COMPLETED);
        }

        private static TurnResult cancelled(String reply) {
            return new TurnResult(reply, Status.CANCELLED);
        }

        private static TurnResult failure() {
            return new TurnResult("", Status.FAILED);
        }

        private boolean completed() {
            return status == Status.COMPLETED;
        }
    }
}
