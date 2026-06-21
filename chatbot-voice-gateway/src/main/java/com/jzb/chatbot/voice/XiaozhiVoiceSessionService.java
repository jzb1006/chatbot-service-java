package com.jzb.chatbot.voice;

import com.jzb.chatbot.common.id.ConversationId;
import com.jzb.chatbot.common.id.DeviceId;
import com.jzb.chatbot.hermes.HermesClient;
import com.jzb.chatbot.hermes.HermesClientConfig;
import com.jzb.chatbot.hermes.HermesRequest;
import com.jzb.chatbot.speech.SpeechToTextClient;
import com.jzb.chatbot.speech.SpeechToTextResult;
import com.jzb.chatbot.speech.StreamingSpeechToTextClient;
import com.jzb.chatbot.voice.bargein.XiaozhiBargeInDetector;
import com.jzb.chatbot.voice.bargein.XiaozhiBargeInProperties;
import com.jzb.chatbot.voice.bargein.XiaozhiBargeInTurn;
import com.jzb.chatbot.voice.hermes.HermesAgentEvent;
import com.jzb.chatbot.voice.hermes.HermesAgentEventExtractor;
import com.jzb.chatbot.voice.mcp.XiaozhiMcpBridge;
import com.jzb.chatbot.voice.music.XiaozhiMusicActionHandler;
import com.jzb.chatbot.voice.music.XiaozhiMusicPlaybackRuntime;
import com.jzb.chatbot.voice.music.XiaozhiMusicPlaybackState;
import com.jzb.chatbot.voice.protocol.XiaozhiAudioParams;
import com.jzb.chatbot.voice.protocol.XiaozhiClientHello;
import com.jzb.chatbot.voice.protocol.XiaozhiClientMessage;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import com.jzb.chatbot.voice.protocol.XiaozhiServerEventFactory;
import com.jzb.chatbot.voice.reminder.XiaozhiReminderRequestedEvent;
import com.jzb.chatbot.voice.sessionend.XiaozhiSessionEndAction;
import com.jzb.chatbot.voice.sessionend.XiaozhiSessionEndProperties;
import com.jzb.chatbot.voice.tts.XiaozhiStreamingTtsRequest;
import com.jzb.chatbot.voice.tts.XiaozhiTtsSentenceSink;
import com.jzb.chatbot.voice.tts.XiaozhiTtsRequest;
import com.jzb.chatbot.voice.tts.XiaozhiTtsResult;
import com.jzb.chatbot.voice.tts.XiaozhiTtsRuntime;
import com.jzb.chatbot.voice.tts.XiaozhiTtsRuntime.StreamingTtsWriteException;
import com.jzb.chatbot.voice.tts.XiaozhiVoiceProfileResolver;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
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
    private static final String SENTENCE_ASR_PROVIDER = "sentence";
    private static final String ASR_FAILED_PROMPT = "语音识别失败，请再试一次";
    private static final String HERMES_FAILED_PROMPT = "对话服务暂时不可用，请稍后再试";
    private static final String INTERNAL_ERROR_PROMPT = "服务暂时不可用，请稍后再试";

    private final XiaozhiMessageCodec codec;
    private final SpeechToTextClient speechToTextClient;
    private final HermesClient hermesClient;
    private final XiaozhiTtsRuntime ttsRuntime;
    private final XiaozhiServerEventFactory eventFactory;
    private final HermesClientConfig hermesClientConfig;
    private final XiaozhiVoiceTokenAuth tokenAuth;
    private final XiaozhiMcpBridge mcpBridge;
    private final XiaozhiAsrMode asrMode;
    private final StreamingSpeechToTextClient streamingSpeechToTextClient;
    private final XiaozhiAudioParams audioParams;
    private final XiaozhiVoiceProfileResolver voiceProfileResolver;
    private final XiaozhiBargeInDetector bargeInDetector;
    private final XiaozhiMusicActionHandler musicActionHandler;
    private final XiaozhiMusicPlaybackRuntime musicPlaybackRuntime;
    private final XiaozhiSessionEndProperties sessionEndProperties;
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
            XiaozhiAsrMode asrMode,
            StreamingSpeechToTextClient streamingSpeechToTextClient,
            XiaozhiAudioParams audioParams,
            XiaozhiVoiceProfileResolver voiceProfileResolver
    ) {
        this(codec,
                speechToTextClient,
                hermesClient,
                ttsRuntime,
                eventFactory,
                hermesClientConfig,
                tokenAuth,
                mcpBridge,
                asrMode,
                streamingSpeechToTextClient,
                audioParams,
                voiceProfileResolver,
                disabledBargeInDetector(),
                (XiaozhiMusicActionHandler) null,
                (XiaozhiMusicPlaybackRuntime) null,
                disabledSessionEndProperties());
    }

    @Autowired
    public XiaozhiVoiceSessionService(
            XiaozhiMessageCodec codec,
            SpeechToTextClient speechToTextClient,
            HermesClient hermesClient,
            XiaozhiTtsRuntime ttsRuntime,
            XiaozhiServerEventFactory eventFactory,
            HermesClientConfig hermesClientConfig,
            XiaozhiVoiceTokenAuth tokenAuth,
            XiaozhiMcpBridge mcpBridge,
            XiaozhiAsrMode asrMode,
            StreamingSpeechToTextClient streamingSpeechToTextClient,
            XiaozhiAudioParams audioParams,
            XiaozhiVoiceProfileResolver voiceProfileResolver,
            XiaozhiBargeInDetector bargeInDetector,
            ObjectProvider<XiaozhiMusicActionHandler> musicActionHandler,
            ObjectProvider<XiaozhiMusicPlaybackRuntime> musicPlaybackRuntime,
            XiaozhiSessionEndProperties sessionEndProperties
    ) {
        this(codec,
                speechToTextClient,
                hermesClient,
                ttsRuntime,
                eventFactory,
                hermesClientConfig,
                tokenAuth,
                mcpBridge,
                asrMode,
                streamingSpeechToTextClient,
                audioParams,
                voiceProfileResolver,
                bargeInDetector,
                musicActionHandler.getIfAvailable(),
                musicPlaybackRuntime.getIfAvailable(),
                sessionEndProperties);
    }

    public XiaozhiVoiceSessionService(
            XiaozhiMessageCodec codec,
            SpeechToTextClient speechToTextClient,
            HermesClient hermesClient,
            XiaozhiTtsRuntime ttsRuntime,
            XiaozhiServerEventFactory eventFactory,
            HermesClientConfig hermesClientConfig,
            XiaozhiVoiceTokenAuth tokenAuth,
            XiaozhiMcpBridge mcpBridge,
            XiaozhiAsrMode asrMode,
            StreamingSpeechToTextClient streamingSpeechToTextClient,
            XiaozhiAudioParams audioParams,
            XiaozhiVoiceProfileResolver voiceProfileResolver,
            XiaozhiMusicActionHandler musicActionHandler
    ) {
        this(codec,
                speechToTextClient,
                hermesClient,
                ttsRuntime,
                eventFactory,
                hermesClientConfig,
                tokenAuth,
                mcpBridge,
                asrMode,
                streamingSpeechToTextClient,
                audioParams,
                voiceProfileResolver,
                disabledBargeInDetector(),
                musicActionHandler,
                null,
                disabledSessionEndProperties());
    }

    public XiaozhiVoiceSessionService(
            XiaozhiMessageCodec codec,
            SpeechToTextClient speechToTextClient,
            HermesClient hermesClient,
            XiaozhiTtsRuntime ttsRuntime,
            XiaozhiServerEventFactory eventFactory,
            HermesClientConfig hermesClientConfig,
            XiaozhiVoiceTokenAuth tokenAuth,
            XiaozhiMcpBridge mcpBridge,
            XiaozhiAsrMode asrMode,
            StreamingSpeechToTextClient streamingSpeechToTextClient,
            XiaozhiAudioParams audioParams,
            XiaozhiVoiceProfileResolver voiceProfileResolver,
            XiaozhiBargeInDetector bargeInDetector,
            XiaozhiMusicActionHandler musicActionHandler,
            XiaozhiMusicPlaybackRuntime musicPlaybackRuntime
    ) {
        this(codec,
                speechToTextClient,
                hermesClient,
                ttsRuntime,
                eventFactory,
                hermesClientConfig,
                tokenAuth,
                mcpBridge,
                asrMode,
                streamingSpeechToTextClient,
                audioParams,
                voiceProfileResolver,
                bargeInDetector,
                musicActionHandler,
                musicPlaybackRuntime,
                disabledSessionEndProperties());
    }

    public XiaozhiVoiceSessionService(
            XiaozhiMessageCodec codec,
            SpeechToTextClient speechToTextClient,
            HermesClient hermesClient,
            XiaozhiTtsRuntime ttsRuntime,
            XiaozhiServerEventFactory eventFactory,
            HermesClientConfig hermesClientConfig,
            XiaozhiVoiceTokenAuth tokenAuth,
            XiaozhiMcpBridge mcpBridge,
            XiaozhiAsrMode asrMode,
            StreamingSpeechToTextClient streamingSpeechToTextClient,
            XiaozhiAudioParams audioParams,
            XiaozhiVoiceProfileResolver voiceProfileResolver,
            XiaozhiBargeInDetector bargeInDetector,
            XiaozhiMusicActionHandler musicActionHandler,
            XiaozhiMusicPlaybackRuntime musicPlaybackRuntime,
            XiaozhiSessionEndProperties sessionEndProperties
    ) {
        this.codec = codec;
        this.speechToTextClient = speechToTextClient;
        this.hermesClient = hermesClient;
        this.ttsRuntime = ttsRuntime;
        this.eventFactory = eventFactory;
        this.hermesClientConfig = hermesClientConfig;
        this.tokenAuth = tokenAuth;
        this.mcpBridge = mcpBridge;
        this.asrMode = asrMode;
        this.streamingSpeechToTextClient = streamingSpeechToTextClient;
        this.audioParams = audioParams;
        this.voiceProfileResolver = voiceProfileResolver;
        this.bargeInDetector = bargeInDetector;
        this.musicActionHandler = musicActionHandler;
        this.musicPlaybackRuntime = musicPlaybackRuntime;
        this.sessionEndProperties = sessionEndProperties == null ? disabledSessionEndProperties() : sessionEndProperties;
    }

    public XiaozhiVoiceSessionService(
            XiaozhiMessageCodec codec,
            SpeechToTextClient speechToTextClient,
            HermesClient hermesClient,
            XiaozhiTtsRuntime ttsRuntime,
            XiaozhiServerEventFactory eventFactory,
            HermesClientConfig hermesClientConfig,
            XiaozhiVoiceTokenAuth tokenAuth,
            XiaozhiMcpBridge mcpBridge,
            XiaozhiAsrMode asrMode,
            StreamingSpeechToTextClient streamingSpeechToTextClient,
            XiaozhiAudioParams audioParams,
            XiaozhiVoiceProfileResolver voiceProfileResolver,
            XiaozhiMusicActionHandler musicActionHandler,
            XiaozhiMusicPlaybackRuntime musicPlaybackRuntime
    ) {
        this(codec,
                speechToTextClient,
                hermesClient,
                ttsRuntime,
                eventFactory,
                hermesClientConfig,
                tokenAuth,
                mcpBridge,
                asrMode,
                streamingSpeechToTextClient,
                audioParams,
                voiceProfileResolver,
                disabledBargeInDetector(),
                musicActionHandler,
                musicPlaybackRuntime,
                disabledSessionEndProperties());
    }

    private static XiaozhiBargeInDetector disabledBargeInDetector() {
        return new XiaozhiBargeInDetector(new XiaozhiBargeInProperties(
                false,
                2,
                500,
                0.82,
                Duration.ofSeconds(2)
        ));
    }

    private static XiaozhiSessionEndProperties disabledSessionEndProperties() {
        return new XiaozhiSessionEndProperties(false, "回头再聊", 1000, "session ended");
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
            stopMusic(voiceSession);
            cancelCurrentTurnPlayback(voiceSession);
            voiceSession.terminateAsrStream();
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
        if (hello.features() != null && Boolean.TRUE.equals(hello.features().get("mcp"))) {
            mcpBridge.markMcpReady(voiceSession.deviceId(), webSocketSession.getId());
        }
        var conversationId = voiceSession.startNewConversation();
        log.info("xiaozhi hello received, sessionId={}, deviceId={}, clientId={}, protocolVersion={}, audioParams={}",
                webSocketSession.getId(),
                voiceSession.deviceId(),
                voiceSession.clientId(),
                version,
                hello.audioParams());
        log.info("xiaozhi conversation started, sessionId={}, deviceId={}, conversationId={}, source=hello",
                webSocketSession.getId(), voiceSession.deviceId(), conversationId);
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
            handleListenStop(webSocketSession, message, voiceSession);
            return;
        }
        if ("listen".equals(message.type()) && "detect".equals(message.state())) {
            log.info("xiaozhi wake word detected, sessionId={}, text={}", webSocketSession.getId(), message.text());
            return;
        }
        if ("session".equals(message.type()) && "new".equals(message.state())) {
            stopMusic(voiceSession);
            cancelCurrentTurnPlayback(voiceSession);
            var conversationId = voiceSession.startNewConversation();
            sendText(webSocketSession, eventFactory.session(voiceSession.sessionId(), conversationId));
            log.info("xiaozhi conversation started, sessionId={}, deviceId={}, conversationId={}",
                    webSocketSession.getId(), voiceSession.deviceId(), conversationId);
            return;
        }
        if ("session".equals(message.type()) && "clear".equals(message.state())) {
            stopMusic(voiceSession);
            cancelCurrentTurnPlayback(voiceSession);
            var conversationId = voiceSession.clearConversation();
            sendText(webSocketSession, eventFactory.session(voiceSession.sessionId(), conversationId));
            log.info("xiaozhi conversation cleared, sessionId={}, deviceId={}, conversationId={}",
                    webSocketSession.getId(), voiceSession.deviceId(), conversationId);
            return;
        }
        if ("abort".equals(message.type())) {
            stopMusic(voiceSession);
            cancelCurrentTurnPlayback(voiceSession);
            voiceSession.terminateAsrStream();
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
        if ("barge_in".equals(message.mode())) {
            handleBargeInStart(webSocketSession, voiceSession);
            return;
        }
        if ("auto".equals(message.mode()) && musicPlaying(voiceSession)) {
            log.info("ignore xiaozhi auto listen while music playing, sessionId={}, deviceId={}",
                    webSocketSession.getId(), voiceSession.deviceId());
            return;
        }
        if (musicPlaying(voiceSession)) {
            pauseMusicForControl(voiceSession);
        } else {
            stopMusic(voiceSession);
        }
        cancelCurrentTurnPlayback(voiceSession);
        if (asrMode.streaming()) {
            var asrTurn = voiceSession.startAsrStream(audioParams.sampleRate(), message.mode());
            Thread.startVirtualThread(() -> processStreamingTurn(webSocketSession, voiceSession, asrTurn));
        } else {
            voiceSession.markListening();
        }
        log.info("xiaozhi listen started, sessionId={}, deviceId={}, mode={}",
                webSocketSession.getId(), voiceSession.deviceId(), message.mode());
    }

    private void handleListenStop(
            WebSocketSession webSocketSession,
            XiaozhiClientMessage message,
            XiaozhiVoiceSession voiceSession
    ) {
        if ("barge_in".equals(message.mode())) {
            handleBargeInStop(webSocketSession, voiceSession);
            return;
        }
        if (asrMode.streaming()) {
            if (voiceSession.completeAsrStream() == null) {
                voiceSession.markIdle();
            } else {
                voiceSession.markProcessing();
            }
            return;
        }
        var processingAudio = voiceSession.tryDrainAudioFramesForProcessing();
        if (!processingAudio.accepted()) {
            log.debug("ignore xiaozhi listen stop outside listening, sessionId={}, deviceId={}, state={}",
                    webSocketSession.getId(), voiceSession.deviceId(), voiceSession.state());
            return;
        }
        processSentenceTurn(webSocketSession, voiceSession, processingAudio);
    }

    private void handleBargeInStart(WebSocketSession webSocketSession, XiaozhiVoiceSession voiceSession) {
        if (!bargeInDetector.properties().enabled()) {
            log.debug("ignore xiaozhi barge-in start because disabled, sessionId={}, deviceId={}",
                    webSocketSession.getId(), voiceSession.deviceId());
            return;
        }
        var turn = voiceSession.startBargeInTurn(audioParams.sampleRate());
        if (turn == null) {
            log.debug("ignore xiaozhi barge-in start outside speaking, sessionId={}, deviceId={}, state={}",
                    webSocketSession.getId(), voiceSession.deviceId(), voiceSession.state());
            return;
        }
        Thread.startVirtualThread(() -> processBargeInTurn(webSocketSession, voiceSession, turn));
        Thread.startVirtualThread(() -> completeBargeInOnTimeout(voiceSession, turn));
        log.info("xiaozhi barge-in started, sessionId={}, deviceId={}, playbackGeneration={}",
                webSocketSession.getId(), voiceSession.deviceId(), turn.playbackGeneration());
    }

    private void handleBargeInStop(WebSocketSession webSocketSession, XiaozhiVoiceSession voiceSession) {
        var turn = voiceSession.activeBargeInTurn();
        if (turn == null) {
            log.debug("ignore xiaozhi barge-in stop without active turn, sessionId={}, deviceId={}, state={}",
                    webSocketSession.getId(), voiceSession.deviceId(), voiceSession.state());
            return;
        }
        voiceSession.completeBargeInTurn(turn);
        log.info("xiaozhi barge-in stopped, sessionId={}, deviceId={}, playbackGeneration={}",
                webSocketSession.getId(), voiceSession.deviceId(), turn.playbackGeneration());
    }

    private void completeBargeInOnTimeout(XiaozhiVoiceSession voiceSession, XiaozhiBargeInTurn turn) {
        try {
            Thread.sleep(bargeInDetector.properties().asrTimeout());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return;
        }
        if (voiceSession.completeBargeInTurn(turn)) {
            log.info("xiaozhi barge-in timed out, sessionId={}, deviceId={}, playbackGeneration={}",
                    voiceSession.sessionId(), voiceSession.deviceId(), turn.playbackGeneration());
        }
    }

    private void processBargeInTurn(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            XiaozhiBargeInTurn turn
    ) {
        try {
            var result = streamingSpeechToTextClient.transcribe(turn.audioStream());
            if (!voiceSession.activeBargeInTurnMatches(turn)) {
                return;
            }
            var text = result == null ? "" : result.text();
            var decision = bargeInDetector.decide(
                    text,
                    voiceSession.currentSpeakingText(),
                    voiceSession.currentSpeakingElapsedMillis()
            );
            if (!decision.interrupt()) {
                log.info("xiaozhi barge-in ignored, sessionId={}, deviceId={}, reason={}, text={}",
                        webSocketSession.getId(), voiceSession.deviceId(), decision.reason(), text);
                return;
            }
            if (!voiceSession.activeBargeInTurnMatches(turn)) {
                return;
            }
            if (tryHandleBargeInControlIntent(webSocketSession, voiceSession, turn, text)) {
                return;
            }
            if (!voiceSession.cancelPlaybackAndListenIfBargeInTurnActive(turn)) {
                return;
            }
            log.info("xiaozhi barge-in detected, sessionId={}, deviceId={}, text={}",
                    webSocketSession.getId(), voiceSession.deviceId(), text);
            ttsRuntime.cancel(voiceSession.sessionId(), turn.playbackGeneration());
        } catch (RuntimeException exception) {
            log.warn("xiaozhi barge-in failed, sessionId={}, deviceId={}",
                    webSocketSession.getId(), voiceSession.deviceId(), exception);
        } finally {
            voiceSession.clearBargeInTurn(turn);
        }
    }

    private boolean tryHandleBargeInControlIntent(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            XiaozhiBargeInTurn turn,
            String text
    ) {
        if ((!sessionEndProperties.enabled() && musicActionHandler == null) || !voiceSession.activeBargeInTurnMatches(turn)) {
            return false;
        }
        var prompt = """
                用户在设备播放回答时插话，原始 ASR 文本如下。
                请只判断是否是结束会话意图、音乐控制意图或普通打断。
                如果是结束会话，只输出 xiaozhi.agent_event: session_end，不要输出自然语言正文。
                ASR: %s
                """.formatted(text);
        try (var chunks = hermesClient.streamChat(new HermesRequest(
                new DeviceId(voiceSession.deviceId()),
                new ConversationId(voiceSession.conversationId()),
                prompt
        ), hermesClientConfig)) {
            var extractor = new HermesAgentEventExtractor();
            for (var chunk : (Iterable<String>) chunks::iterator) {
                for (var event : extractor.accept(chunk)) {
                    var action = XiaozhiSessionEndAction.from(event, sessionEndProperties);
                    if (action != null && voiceSession.activeBargeInTurnMatches(turn)) {
                        ttsRuntime.cancel(voiceSession.sessionId(), turn.playbackGeneration());
                        executeSessionEnd(webSocketSession, voiceSession, action);
                        return true;
                    }
                    if (musicActionHandler != null
                            && voiceSession.activeBargeInTurnMatches(turn)
                            && musicActionHandler.handle(webSocketSession, voiceSession, event)) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException exception) {
            log.warn("xiaozhi barge-in control intent failed, sessionId={}, deviceId={}, message={}",
                    webSocketSession.getId(), voiceSession.deviceId(), exception.getMessage(), exception);
        }
        return false;
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
                voiceSession.addAudioFrameIfListening(frame);
            }
            return;
        }
        if (voiceSession.state() == XiaozhiVoiceSession.State.SPEAKING
                && voiceSession.activeBargeInTurn() != null) {
            voiceSession.writeAudioFrameToBargeIn(frame);
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
        var playbackGeneration = voiceSession.tryBeginNotificationPlayback();
        if (playbackGeneration < 0) {
            log.info("xiaozhi notification skipped because session is busy, sessionId={}, deviceId={}, state={}",
                    sessionId, deviceId, voiceSession.state());
            return false;
        }
        var ttsStartedAt = System.nanoTime();
        try {
            var profile = voiceProfileResolver.resolve(voiceSession.deviceId());
            if (ttsRuntime.streamingEnabled()) {
                return ttsRuntime.playStreaming(new XiaozhiStreamingTtsRequest(
                        webSocketSession,
                        voiceSession,
                        profile.toTtsOptions(),
                        playbackGeneration,
                        () -> notificationCancelled(webSocketSession, voiceSession, playbackGeneration)
                ), sentenceSink -> {
                    sentenceSink.accept(text);
                    sentenceSink.complete();
                }).played();
            }
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

    private void processSentenceTurn(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            XiaozhiVoiceSession.ProcessingAudio processingAudio
    ) {
        var turnGeneration = processingAudio.turnGeneration();
        try {
            if (!XiaozhiVoiceInputGate.shouldTranscribe(processingAudio.frames())) {
                log.info("xiaozhi no speech detected, sessionId={}, deviceId={}, audioFrames={}",
                        webSocketSession.getId(), voiceSession.deviceId(), processingAudio.frames().size());
                voiceSession.markIdleIfTurnActive(turnGeneration);
                return;
            }
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
                log.warn("xiaozhi asr returned blank text, sessionId={}, deviceId={}, asrProvider={}, audioFrames={}, asrMillis={}",
                        webSocketSession.getId(),
                        voiceSession.deviceId(),
                        transcription.provider(),
                        audioFrameCount,
                        asrMillis);
                sendRecoverableTurnError(
                        webSocketSession,
                        voiceSession,
                        turnGeneration,
                        "asr_empty",
                        "未识别到语音",
                        "我没听清，请再说一遍",
                        asrMillis,
                        0,
                        transcription.provider()
                );
                voiceSession.markIdleIfTurnActive(turnGeneration);
                return;
            }
            sendText(webSocketSession, eventFactory.stt(voiceSession.sessionId(), userText));
            if (turnCancelled(webSocketSession, voiceSession, turnGeneration)) {
                cancelTurnBeforeRuntime(webSocketSession, voiceSession, "", asrMillis);
                return;
            }
            var result = streamChatAndSpeak(
                    webSocketSession,
                    voiceSession,
                    TurnGuard.none(),
                    voiceSession.deviceId(),
                    voiceSession.conversationId(),
                    turnGeneration,
                    userText,
                    audioFrameCount,
                    asrMillis,
                    SENTENCE_ASR_PROVIDER
            );
            if (!result.completed()) {
                return;
            }
            log.info("xiaozhi conversation turn, sessionId={}, deviceId={}, conversationId={}, asrProvider={}, asrMillis={}, userText={}, assistantText={}",
                    webSocketSession.getId(),
                    voiceSession.deviceId(),
                    voiceSession.conversationId(),
                    transcription.provider(),
                    asrMillis,
                    userText,
                    result.reply());
        } catch (RuntimeException exception) {
            log.warn("xiaozhi turn failed, sessionId={}, deviceId={}, message={}",
                    webSocketSession.getId(), voiceSession.deviceId(), exception.getMessage(), exception);
            sendRecoverableTurnError(
                    webSocketSession,
                    voiceSession,
                    turnGeneration,
                    "internal_error",
                    "服务暂时不可用",
                    INTERNAL_ERROR_PROMPT,
                    0,
                    0,
                    SENTENCE_ASR_PROVIDER
            );
            voiceSession.markIdleIfTurnActive(turnGeneration);
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
            if (!voiceSession.beginAsrTurnProcessing(asrTurn)) {
                return;
            }
            var userText = result == null ? "" : result.text();
            if (userText == null || userText.isBlank()) {
                log.warn("xiaozhi streaming asr returned blank text, sessionId={}, deviceId={}, asrProvider={}, asrMillis={}",
                        webSocketSession.getId(),
                        voiceSession.deviceId(),
                        provider(result),
                        asrMillis);
                if (!trySendAsrTurnText(
                        webSocketSession,
                        voiceSession,
                        asrTurn,
                        eventFactory.error(voiceSession.sessionId(), "asr_empty", "未识别到语音")
                )) {
                    return;
                }
                if ("auto".equals(asrTurn.listenMode())) {
                    voiceSession.markIdleIfAsrTurn(asrTurn);
                    return;
                }
                sendRecoverableTurnTts(
                        webSocketSession,
                        voiceSession,
                        asrTurn.turnGeneration(),
                        "我没听清，请再说一遍",
                        asrMillis,
                        0,
                        provider(result)
                );
                voiceSession.markIdleIfAsrTurn(asrTurn);
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
            var turnResult = streamChatAndSpeak(
                    webSocketSession,
                    voiceSession,
                    new TurnGuard(() -> voiceSession.isCurrentAsrTurn(asrTurn)),
                    asrTurn.deviceId(),
                    asrTurn.conversationId(),
                    asrTurn.turnGeneration(),
                    userText,
                    0,
                    asrMillis,
                    provider(result)
            );
            if (!turnResult.completed()) {
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
            sendRecoverableTurnTts(
                    webSocketSession,
                    voiceSession,
                    asrTurn.turnGeneration(),
                    ASR_FAILED_PROMPT,
                    elapsedMillis(asrStartedAt),
                    0,
                    "unknown"
            );
            voiceSession.markIdleIfAsrTurn(asrTurn);
        } finally {
            voiceSession.clearAsrStream(asrTurn);
        }
    }

    private Transcription transcribe(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            long turnGeneration,
            List<ByteBuffer> audioFrames,
            long asrStartedAt
    ) {
        try {
            return new Transcription(speechToTextClient.transcribe(audioFrames), SENTENCE_ASR_PROVIDER, false);
        } catch (RuntimeException exception) {
            if (turnCancelled(webSocketSession, voiceSession, turnGeneration)) {
                return new Transcription(null, SENTENCE_ASR_PROVIDER, true);
            }
            log.warn("xiaozhi asr failed, sessionId={}, deviceId={}, asrProvider={}, asrMillis={}, message={}",
                    webSocketSession.getId(),
                    voiceSession.deviceId(),
                    SENTENCE_ASR_PROVIDER,
                    elapsedMillis(asrStartedAt),
                    exception.getMessage(),
                    exception);
            sendRecoverableTurnError(
                    webSocketSession,
                    voiceSession,
                    turnGeneration,
                    "asr_failed",
                    "语音识别失败",
                    ASR_FAILED_PROMPT,
                    elapsedMillis(asrStartedAt),
                    0,
                    SENTENCE_ASR_PROVIDER
            );
            voiceSession.markIdleIfTurnActive(turnGeneration);
            return new Transcription(null, SENTENCE_ASR_PROVIDER, true);
        }
    }

    private TurnResult streamChatAndSpeak(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            TurnGuard turnGuard,
            String deviceId,
            String conversationId,
            long turnGeneration,
            String userText,
            int audioFrameCount,
            long asrMillis,
            String asrProvider
    ) {
        if (!turnGuard.active()) {
            return TurnResult.cancelled("");
        }
        var extractor = new XiaozhiHermesStreamTextExtractor();
        var eventExtractor = new HermesAgentEventExtractor();
        var eventTextFilter = new XiaozhiHermesAgentEventTextFilter();
        var segmenter = new XiaozhiSentenceSegmenter();
        var reply = new StringBuilder();
        var sentences = new ArrayList<String>();
        var hermesStartedAt = System.nanoTime();
        try {
            if (ttsRuntime.streamingEnabled()) {
                return streamChatAndSpeakWithStreamingTts(
                        webSocketSession,
                        voiceSession,
                        turnGuard,
                        deviceId,
                        conversationId,
                        turnGeneration,
                        userText,
                        asrMillis,
                        asrProvider,
                        extractor,
                        eventExtractor,
                        eventTextFilter,
                        segmenter,
                        reply,
                        hermesStartedAt
                );
            }
            var reminderConfirmationText = new AtomicReference<String>();
            try (var chunks = hermesClient.streamChat(new HermesRequest(
                    new DeviceId(deviceId),
                    new ConversationId(conversationId),
                    userText
            ), hermesClientConfig)) {
                for (var chunk : (Iterable<String>) chunks::iterator) {
                    if (turnCancelled(webSocketSession, voiceSession, turnGeneration) || !turnGuard.active()) {
                        return cancelTurnBeforeRuntime(webSocketSession, voiceSession, reply.toString(), asrMillis, hermesStartedAt);
                    }
                    for (var event : eventExtractor.accept(chunk)) {
                        var confirmationText = handleHermesAgentEvent(webSocketSession, voiceSession, turnGuard, event);
                        if (!musicEvent(event) && reminderConfirmationText.get() == null && confirmationText != null && !confirmationText.isBlank()) {
                            reminderConfirmationText.set(confirmationText);
                        }
                    }
                    if (turnCancelled(webSocketSession, voiceSession, turnGeneration) || !turnGuard.active()) {
                        return cancelTurnBeforeRuntime(webSocketSession, voiceSession, reply.toString(), asrMillis, hermesStartedAt);
                    }
                    for (var text : filterHermesTexts(
                            webSocketSession,
                            voiceSession,
                            turnGuard,
                            eventTextFilter,
                            extractor.accept(chunk),
                            false,
                            confirmationText -> {
                                if (reminderConfirmationText.get() == null && confirmationText != null && !confirmationText.isBlank()) {
                                    reminderConfirmationText.set(confirmationText);
                                }
                            }
                    )) {
                        if (turnCancelled(webSocketSession, voiceSession, turnGeneration) || !turnGuard.active()) {
                            return cancelTurnBeforeRuntime(webSocketSession, voiceSession, reply.toString(), asrMillis, hermesStartedAt);
                        }
                        reply.append(text);
                        sentences.addAll(segmenter.accept(text));
                    }
                }
            }
            for (var text : filterHermesTexts(
                    webSocketSession,
                    voiceSession,
                    turnGuard,
                    eventTextFilter,
                    extractor.flush(),
                    true,
                    confirmationText -> {
                        if (reminderConfirmationText.get() == null && confirmationText != null && !confirmationText.isBlank()) {
                            reminderConfirmationText.set(confirmationText);
                        }
                    }
            )) {
                if (turnCancelled(webSocketSession, voiceSession, turnGeneration) || !turnGuard.active()) {
                    return cancelTurnBeforeRuntime(webSocketSession, voiceSession, reply.toString(), asrMillis, hermesStartedAt);
                }
                reply.append(text);
                sentences.addAll(segmenter.accept(text));
            }
            var finalSentence = segmenter.flush();
            if (!finalSentence.isBlank()) {
                sentences.add(finalSentence);
            }
            if (reply.toString().isBlank() && reminderConfirmationText.get() != null && !reminderConfirmationText.get().isBlank()) {
                reply.append(reminderConfirmationText.get());
                sentences.add(reminderConfirmationText.get());
            }
            if (turnCancelled(webSocketSession, voiceSession, turnGeneration) || !turnGuard.active()) {
                return cancelTurnBeforeRuntime(webSocketSession, voiceSession, reply.toString(), asrMillis, hermesStartedAt);
            }
            var hermesMillis = elapsedMillis(hermesStartedAt);
            var ttsOperationStartedAt = System.nanoTime();
            var playbackResult = speakWithRuntime(
                    webSocketSession,
                    voiceSession,
                    turnGeneration,
                    turnGuard,
                    sentences,
                    ttsOperationStartedAt,
                    "语音合成失败"
            );
            logTurnCompleted(
                    webSocketSession,
                    voiceSession,
                    playbackResult,
                    asrProvider,
                    asrMillis,
                    hermesMillis,
                    playbackResult.ttsMillis()
            );
            if (playbackResult.failed()) {
                return TurnResult.failure();
            }
            if (playbackResult.cancelled()) {
                return TurnResult.cancelled(reply.toString());
            }
            return TurnResult.completed(reply.toString());
        } catch (XiaozhiSessionEndRequestedException exception) {
            return TurnResult.cancelled(reply.toString());
        } catch (RuntimeException exception) {
            if (turnCancelled(webSocketSession, voiceSession, turnGeneration) || !turnGuard.active()) {
                return cancelTurnBeforeRuntime(webSocketSession, voiceSession, reply.toString(), asrMillis, hermesStartedAt);
            }
            log.warn("xiaozhi hermes failed, sessionId={}, deviceId={}, message={}",
                    webSocketSession.getId(),
                    deviceId,
                    exception.getMessage(),
                    exception);
            sendRecoverableTurnError(
                    webSocketSession,
                    voiceSession,
                    turnGeneration,
                    "hermes_failed",
                    "对话服务失败",
                    HERMES_FAILED_PROMPT,
                    asrMillis,
                    elapsedMillis(hermesStartedAt),
                    asrProvider
            );
            voiceSession.markIdleIfTurnActive(turnGeneration);
            return TurnResult.failure();
        }
    }

    private TurnResult streamChatAndSpeakWithStreamingTts(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            TurnGuard turnGuard,
            String deviceId,
            String conversationId,
            long turnGeneration,
            String userText,
            long asrMillis,
            String asrProvider,
            XiaozhiHermesStreamTextExtractor extractor,
            HermesAgentEventExtractor eventExtractor,
            XiaozhiHermesAgentEventTextFilter eventTextFilter,
            XiaozhiSentenceSegmenter segmenter,
            StringBuilder reply,
            long hermesStartedAt
    ) {
        var ttsOperationStartedAt = System.nanoTime();
        var playbackResult = speakStreamingWithRuntime(
                webSocketSession,
                voiceSession,
                turnGeneration,
                turnGuard,
                ttsOperationStartedAt,
                "语音合成失败",
                sentenceSink -> streamHermesIntoSentenceSink(
                        webSocketSession,
                        voiceSession,
                        turnGuard,
                        deviceId,
                        conversationId,
                        turnGeneration,
                        userText,
                        asrMillis,
                        extractor,
                        eventExtractor,
                        eventTextFilter,
                        segmenter,
                        reply,
                        hermesStartedAt,
                        asrProvider,
                        sentenceSink
                )
        );
        var hermesMillis = elapsedMillis(hermesStartedAt);
        logTurnCompleted(
                webSocketSession,
                voiceSession,
                playbackResult,
                asrProvider,
                asrMillis,
                hermesMillis,
                playbackResult.ttsMillis()
        );
        if (playbackResult.failed()) {
            return TurnResult.failure();
        }
        if (playbackResult.cancelled()) {
            return TurnResult.cancelled(reply.toString());
        }
        return TurnResult.completed(reply.toString());
    }

    private void streamHermesIntoSentenceSink(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            TurnGuard turnGuard,
            String deviceId,
            String conversationId,
            long turnGeneration,
            String userText,
            long asrMillis,
            XiaozhiHermesStreamTextExtractor extractor,
            HermesAgentEventExtractor eventExtractor,
            XiaozhiHermesAgentEventTextFilter eventTextFilter,
            XiaozhiSentenceSegmenter segmenter,
            StringBuilder reply,
            long hermesStartedAt,
            String asrProvider,
            XiaozhiTtsSentenceSink sentenceSink
    ) {
        String reminderConfirmationText = null;
        try (var chunks = hermesClient.streamChat(new HermesRequest(
                new DeviceId(deviceId),
                new ConversationId(conversationId),
                userText
        ), hermesClientConfig)) {
            for (var chunk : (Iterable<String>) chunks::iterator) {
                ensureTurnActive(webSocketSession, voiceSession, turnGeneration, turnGuard);
                reminderConfirmationText = acceptHermesChunk(
                        webSocketSession,
                        voiceSession,
                        turnGuard,
                        chunk,
                        extractor,
                        eventExtractor,
                        eventTextFilter,
                        segmenter,
                        reply,
                        sentenceSink,
                        reminderConfirmationText
                );
            }
        } catch (XiaozhiTtsTurnCancelledException exception) {
            throw exception;
        } catch (XiaozhiSessionEndRequestedException exception) {
            throw exception;
        } catch (StreamingTtsWriteException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (turnCancelled(webSocketSession, voiceSession, turnGeneration) || !turnGuard.active()) {
                throw new XiaozhiTtsTurnCancelledException();
            }
            log.warn("xiaozhi hermes failed, sessionId={}, deviceId={}, message={}",
                    webSocketSession.getId(), deviceId, exception.getMessage(), exception);
            throw new RecoverableTurnException(
                    "hermes_failed",
                    "对话服务失败",
                    HERMES_FAILED_PROMPT,
                    asrMillis,
                    elapsedMillis(hermesStartedAt),
                    asrProvider,
                    exception
            );
        }
        flushHermesSentences(
                webSocketSession,
                voiceSession,
                turnGeneration,
                turnGuard,
                extractor,
                eventTextFilter,
                segmenter,
                reply,
                sentenceSink,
                reminderConfirmationText,
                asrMillis,
                hermesStartedAt
        );
        sentenceSink.complete();
    }

    private String acceptHermesChunk(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            TurnGuard turnGuard,
            String chunk,
            XiaozhiHermesStreamTextExtractor extractor,
            HermesAgentEventExtractor eventExtractor,
            XiaozhiHermesAgentEventTextFilter eventTextFilter,
            XiaozhiSentenceSegmenter segmenter,
            StringBuilder reply,
            XiaozhiTtsSentenceSink sentenceSink,
            String reminderConfirmationText
    ) {
        var confirmationRef = new AtomicReference<>(reminderConfirmationText);
        for (var event : eventExtractor.accept(chunk)) {
            var confirmationText = handleHermesAgentEvent(webSocketSession, voiceSession, turnGuard, event);
            if (!musicEvent(event) && confirmationRef.get() == null && confirmationText != null && !confirmationText.isBlank()) {
                confirmationRef.set(confirmationText);
            }
        }
        for (var text : filterHermesTexts(
                webSocketSession,
                voiceSession,
                turnGuard,
                eventTextFilter,
                extractor.accept(chunk),
                false,
                confirmationText -> {
                    if (confirmationRef.get() == null && confirmationText != null && !confirmationText.isBlank()) {
                        confirmationRef.set(confirmationText);
                    }
                }
        )) {
            reply.append(text);
            for (var sentence : segmenter.accept(text)) {
                sentenceSink.accept(sentence);
            }
        }
        return confirmationRef.get();
    }

    private void flushHermesSentences(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            long turnGeneration,
            TurnGuard turnGuard,
            XiaozhiHermesStreamTextExtractor extractor,
            XiaozhiHermesAgentEventTextFilter eventTextFilter,
            XiaozhiSentenceSegmenter segmenter,
            StringBuilder reply,
            XiaozhiTtsSentenceSink sentenceSink,
            String reminderConfirmationText,
            long asrMillis,
            long hermesStartedAt
    ) {
        var confirmationRef = new AtomicReference<>(reminderConfirmationText);
        for (var text : filterHermesTexts(
                webSocketSession,
                voiceSession,
                turnGuard,
                eventTextFilter,
                extractor.flush(),
                true,
                confirmationText -> {
                    if (confirmationRef.get() == null && confirmationText != null && !confirmationText.isBlank()) {
                        confirmationRef.set(confirmationText);
                    }
                }
        )) {
            ensureTurnActive(webSocketSession, voiceSession, turnGeneration, turnGuard);
            reply.append(text);
            for (var sentence : segmenter.accept(text)) {
                sentenceSink.accept(sentence);
            }
        }
        ensureTurnActive(webSocketSession, voiceSession, turnGeneration, turnGuard);
        var finalSentence = segmenter.flush();
        if (!finalSentence.isBlank()) {
            sentenceSink.accept(finalSentence);
        }
        if (reply.toString().isBlank() && confirmationRef.get() != null && !confirmationRef.get().isBlank()) {
            reply.append(confirmationRef.get());
            sentenceSink.accept(confirmationRef.get());
        }
        if (turnCancelled(webSocketSession, voiceSession, turnGeneration) || !turnGuard.active()) {
            cancelTurnBeforeRuntime(webSocketSession, voiceSession, reply.toString(), asrMillis, hermesStartedAt);
            throw new XiaozhiTtsTurnCancelledException();
        }
    }

    private List<String> filterHermesTexts(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            TurnGuard turnGuard,
            XiaozhiHermesAgentEventTextFilter eventTextFilter,
            List<String> texts,
            boolean flush,
            java.util.function.Consumer<String> confirmationConsumer
    ) {
        var filteredTexts = new ArrayList<String>();
        for (var text : texts) {
            var result = eventTextFilter.accept(text);
            handleHermesAgentEvents(webSocketSession, voiceSession, turnGuard, result.events(), confirmationConsumer);
            if (!result.text().isEmpty()) {
                filteredTexts.add(result.text());
            }
        }
        if (flush) {
            var flushed = eventTextFilter.flush();
            handleHermesAgentEvents(webSocketSession, voiceSession, turnGuard, flushed.events(), confirmationConsumer);
            if (!flushed.text().isEmpty()) {
                filteredTexts.add(flushed.text());
            }
        }
        return List.copyOf(filteredTexts);
    }

    private void handleHermesAgentEvents(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            TurnGuard turnGuard,
            List<HermesAgentEvent> events,
            java.util.function.Consumer<String> confirmationConsumer
    ) {
        for (var event : events) {
            var confirmationText = handleHermesAgentEvent(webSocketSession, voiceSession, turnGuard, event);
            if (!musicEvent(event) && confirmationText != null && !confirmationText.isBlank()) {
                confirmationConsumer.accept(confirmationText);
            }
        }
    }

    private void ensureTurnActive(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            long turnGeneration,
            TurnGuard turnGuard
    ) {
        if (turnCancelled(webSocketSession, voiceSession, turnGeneration) || !turnGuard.active()) {
            throw new XiaozhiTtsTurnCancelledException();
        }
    }

    private String handleHermesAgentEvent(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            TurnGuard turnGuard,
            HermesAgentEvent event
    ) {
        if (!turnGuard.active()) {
            return null;
        }
        if (handleSessionEndAction(webSocketSession, voiceSession, turnGuard, event)) {
            throw new XiaozhiSessionEndRequestedException();
        }
        if (musicActionHandler != null && musicActionHandler.handle(webSocketSession, voiceSession, event)) {
            return event.confirmationText();
        }
        if (!"create_reminder".equals(event.action())) {
            return null;
        }
        publishReminderRequest(voiceSession, event.message(), event.dueText(), event.delaySeconds());
        var confirmationText = event.confirmationText() == null ? "" : event.confirmationText();
        return turnGuard.active() ? confirmationText : null;
    }

    private boolean handleSessionEndAction(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            TurnGuard turnGuard,
            HermesAgentEvent event
    ) {
        var action = XiaozhiSessionEndAction.from(event, sessionEndProperties);
        if (action == null || !turnGuard.active()) {
            return false;
        }
        executeSessionEnd(webSocketSession, voiceSession, action);
        return true;
    }

    private void executeSessionEnd(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            XiaozhiSessionEndAction action
    ) {
        stopMusic(voiceSession);
        ttsRuntime.cancel(voiceSession.sessionId());
        voiceSession.prepareSessionEndPlayback();
        var generation = voiceSession.markProcessing();
        speakWithRuntime(
                webSocketSession,
                voiceSession,
                generation,
                TurnGuard.none(),
                List.of(action.confirmationText()),
                System.nanoTime(),
                "语音合成失败"
        );
        closeWebSocket(webSocketSession);
        log.info("xiaozhi session ended, sessionId={}, deviceId={}, reason={}",
                webSocketSession.getId(), voiceSession.deviceId(), action.reason());
    }

    private void closeWebSocket(WebSocketSession webSocketSession) {
        try {
            if (webSocketSession.isOpen()) {
                webSocketSession.close(new CloseStatus(
                        sessionEndProperties.closeStatusCode(),
                        sessionEndProperties.closeReason()
                ));
            }
        } catch (IOException exception) {
            log.warn("xiaozhi websocket session-end close failed, sessionId={}, message={}",
                    webSocketSession.getId(), exception.getMessage(), exception);
        }
    }

    private boolean musicEvent(HermesAgentEvent event) {
        return event != null && event.action() != null && event.action().startsWith("music_");
    }

    private void publishReminderRequest(XiaozhiVoiceSession voiceSession, String message, String dueText, long delaySeconds) {
        if (dueText == null || dueText.isBlank()) {
            log.warn("xiaozhi reminder event ignored because dueText is blank, sessionId={}, deviceId={}, message={}",
                    voiceSession.sessionId(), voiceSession.deviceId(), message);
            return;
        }
        eventPublisher.publishEvent(new XiaozhiReminderRequestedEvent(
                voiceSession.deviceId(),
                message,
                dueText,
                delaySeconds
        ));
    }

    private void stopMusic(XiaozhiVoiceSession voiceSession) {
        if (musicPlaybackRuntime != null && voiceSession != null) {
            musicPlaybackRuntime.stop(voiceSession.deviceId());
        }
    }

    private void pauseMusicForControl(XiaozhiVoiceSession voiceSession) {
        if (musicPlaybackRuntime != null && voiceSession != null) {
            musicPlaybackRuntime.pause(voiceSession.deviceId(), XiaozhiMusicPlaybackState.PauseSource.CONTROL);
        }
    }

    private boolean musicPlaying(XiaozhiVoiceSession voiceSession) {
        if (musicPlaybackRuntime == null || voiceSession == null) {
            return false;
        }
        return musicPlaybackRuntime.state(voiceSession.deviceId()).status()
                == XiaozhiMusicPlaybackState.Status.PLAYING;
    }

    private PlaybackResult speakWithRuntime(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            long turnGeneration,
            TurnGuard turnGuard,
            List<String> sentences,
            long ttsStartedAt,
            String errorMessage
    ) {
        try {
            var profile = voiceProfileResolver.resolve(voiceSession.deviceId());
            if (cancelledBeforeRuntime(webSocketSession, voiceSession, turnGeneration) || !turnGuard.active()) {
                return PlaybackResult.cancelledBeforeRuntime();
            }
            var runtimeStartedAt = System.nanoTime();
            voiceSession.updateCurrentSpeakingText(String.join("", sentences));
            var result = ttsRuntime.play(new XiaozhiTtsRequest(
                    webSocketSession,
                    voiceSession,
                    sentences,
                    profile.toTtsOptions(),
                    () -> cancelledBeforeRuntime(webSocketSession, voiceSession, turnGeneration) || !turnGuard.active()
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

    private PlaybackResult speakStreamingWithRuntime(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            long turnGeneration,
            TurnGuard turnGuard,
            long ttsStartedAt,
            String errorMessage,
            java.util.function.Consumer<XiaozhiTtsSentenceSink> sentenceProducer
    ) {
        try {
            var profile = voiceProfileResolver.resolve(voiceSession.deviceId());
            if (cancelledBeforeRuntime(webSocketSession, voiceSession, turnGeneration) || !turnGuard.active()) {
                return PlaybackResult.cancelledBeforeRuntime();
            }
            var runtimeStartedAt = System.nanoTime();
            var result = ttsRuntime.playStreaming(new XiaozhiStreamingTtsRequest(
                    webSocketSession,
                    voiceSession,
                    profile.toTtsOptions(),
                    () -> cancelledBeforeRuntime(webSocketSession, voiceSession, turnGeneration) || !turnGuard.active()
            ), sentenceSink -> sentenceProducer.accept(recordingSentenceSink(voiceSession, sentenceSink)));
            var ttsMillis = elapsedMillis(runtimeStartedAt);
            if (result.cancelled()) {
                return PlaybackResult.cancelled(result, ttsMillis);
            }
            return result.played() ? PlaybackResult.completed(result, ttsMillis) : PlaybackResult.cancelled(result, ttsMillis);
        } catch (XiaozhiTtsTurnCancelledException exception) {
            return PlaybackResult.cancelledBeforeRuntime();
        } catch (RecoverableTurnException exception) {
            sendRecoverableTurnError(
                    webSocketSession,
                    voiceSession,
                    turnGeneration,
                    exception.code(),
                    exception.message(),
                    exception.spokenMessage(),
                    exception.asrMillis(),
                    exception.hermesMillis(),
                    exception.asrProvider()
            );
            return PlaybackResult.FAILED;
        } catch (XiaozhiHermesStreamingException exception) {
            return PlaybackResult.FAILED;
        } catch (XiaozhiSessionEndRequestedException exception) {
            return PlaybackResult.cancelledBeforeRuntime();
        } catch (RuntimeException exception) {
            handleTurnTtsFailure(webSocketSession, voiceSession, turnGeneration, ttsStartedAt, errorMessage, exception);
            return PlaybackResult.FAILED;
        }
    }

    private XiaozhiTtsSentenceSink recordingSentenceSink(
            XiaozhiVoiceSession voiceSession,
            XiaozhiTtsSentenceSink delegate
    ) {
        return new XiaozhiTtsSentenceSink() {
            @Override
            public void accept(String sentence) {
                voiceSession.appendCurrentSpeakingText(sentence);
                delegate.accept(sentence);
            }

            @Override
            public void complete() {
                delegate.complete();
            }
        };
    }

    private TurnResult cancelTurnBeforeRuntime(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            String reply,
            long asrMillis
    ) {
        logTurnCompleted(
                webSocketSession,
                voiceSession,
                PlaybackResult.cancelledBeforeRuntime(),
                "unknown",
                asrMillis,
                0,
                0
        );
        return TurnResult.cancelled(reply);
    }

    private TurnResult cancelTurnBeforeRuntime(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            String reply,
            long asrMillis,
            long hermesStartedAt
    ) {
        logTurnCompleted(
                webSocketSession,
                voiceSession,
                PlaybackResult.cancelledBeforeRuntime(),
                "unknown",
                asrMillis,
                elapsedMillis(hermesStartedAt),
                0
        );
        return TurnResult.cancelled(reply);
    }

    private void logTurnCompleted(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            PlaybackResult playbackResult,
            String asrProvider,
            long asrMillis,
            long hermesMillis,
            long ttsMillis
    ) {
        var ttsResult = playbackResult.ttsResult();
        if (ttsResult == null) {
            return;
        }
        log.info("xiaozhi turn completed, sessionId={}, deviceId={}, conversationId={}, asrProvider={}, sentenceCount={}, ttsFrames={}, asrMillis={}, hermesMillis={}, ttsMillis={}, cancelled={}",
                webSocketSession.getId(),
                voiceSession.deviceId(),
                voiceSession.conversationId(),
                asrProvider,
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

    private PlaybackResult sendRecoverableTurnError(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            long turnGeneration,
            String code,
            String message,
            String spokenMessage,
            long asrMillis,
            long hermesMillis,
            String asrProvider
    ) {
        if (!trySendTurnErrorIfActive(webSocketSession, voiceSession, turnGeneration, code, message)) {
            return PlaybackResult.cancelledBeforeRuntime();
        }
        return sendRecoverableTurnTts(
                webSocketSession,
                voiceSession,
                turnGeneration,
                spokenMessage,
                asrMillis,
                hermesMillis,
                asrProvider
        );
    }

    private PlaybackResult sendRecoverableTurnTts(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            long turnGeneration,
            String spokenMessage,
            long asrMillis,
            long hermesMillis,
            String asrProvider
    ) {
        if (spokenMessage == null || spokenMessage.isBlank()) {
            return PlaybackResult.cancelledBeforeRuntime();
        }
        var ttsStartedAt = System.nanoTime();
        var playbackResult = speakWithRuntime(
                webSocketSession,
                voiceSession,
                turnGeneration,
                TurnGuard.none(),
                List.of(spokenMessage),
                ttsStartedAt,
                "语音合成失败"
        );
        logTurnCompleted(webSocketSession, voiceSession, playbackResult, asrProvider, asrMillis, hermesMillis,
                playbackResult.ttsMillis());
        return playbackResult;
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
        voiceSession.cancelPlayback();
    }

    private boolean trySendTurnErrorIfActive(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            long turnGeneration,
            String code,
            String message
    ) {
        if (turnCancelled(webSocketSession, voiceSession, turnGeneration)) {
            return false;
        }
        return trySendText(webSocketSession, eventFactory.error(voiceSession.sessionId(), code, message));
    }

    private boolean notificationCancelled(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            long playbackGeneration
    ) {
        return sessions.get(webSocketSession.getId()) != voiceSession
                || !webSocketSession.getId().equals(deviceSessionIds.get(voiceSession.deviceId()))
                || !voiceSession.playbackActive(playbackGeneration);
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

    private static final class XiaozhiTtsTurnCancelledException extends RuntimeException {
    }

    private static final class XiaozhiSessionEndRequestedException extends RuntimeException {
    }

    private static final class XiaozhiHermesStreamingException extends RuntimeException {

        private XiaozhiHermesStreamingException(Throwable cause) {
            super(cause);
        }
    }

    private static final class RecoverableTurnException extends RuntimeException {

        private final String code;
        private final String message;
        private final String spokenMessage;
        private final long asrMillis;
        private final long hermesMillis;
        private final String asrProvider;

        private RecoverableTurnException(
                String code,
                String message,
                String spokenMessage,
                long asrMillis,
                long hermesMillis,
                String asrProvider,
                Throwable cause
        ) {
            super(cause);
            this.code = code;
            this.message = message;
            this.spokenMessage = spokenMessage;
            this.asrMillis = asrMillis;
            this.hermesMillis = hermesMillis;
            this.asrProvider = asrProvider;
        }

        private String code() {
            return code;
        }

        private String message() {
            return message;
        }

        private String spokenMessage() {
            return spokenMessage;
        }

        private long asrMillis() {
            return asrMillis;
        }

        private long hermesMillis() {
            return hermesMillis;
        }

        private String asrProvider() {
            return asrProvider;
        }
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

        private boolean failed() {
            return status == Status.FAILED;
        }
    }
}
