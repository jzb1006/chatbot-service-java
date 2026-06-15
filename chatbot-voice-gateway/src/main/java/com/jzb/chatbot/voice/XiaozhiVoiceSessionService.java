package com.jzb.chatbot.voice;

import com.jzb.chatbot.common.id.ConversationId;
import com.jzb.chatbot.common.id.DeviceId;
import com.jzb.chatbot.common.id.VoiceId;
import com.jzb.chatbot.hermes.HermesClient;
import com.jzb.chatbot.hermes.HermesClientConfig;
import com.jzb.chatbot.hermes.HermesRequest;
import com.jzb.chatbot.speech.SpeechToTextClient;
import com.jzb.chatbot.speech.TextToSpeechClient;
import com.jzb.chatbot.voice.protocol.XiaozhiClientHello;
import com.jzb.chatbot.voice.protocol.XiaozhiClientMessage;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import com.jzb.chatbot.voice.protocol.XiaozhiServerEventFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
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
    private final Map<String, XiaozhiVoiceSession> sessions = new ConcurrentHashMap<>();

    /**
     * 打开新的语音会话。
     *
     * @param session WebSocket 会话
     */
    public void open(WebSocketSession session) {
        var voiceSession = new XiaozhiVoiceSession(session.getId());
        var headers = session.getHandshakeHeaders();
        voiceSession.updateHandshake(
                headers.getFirst(AUTHORIZATION_HEADER),
                headers.getFirst(DEVICE_ID_HEADER),
                headers.getFirst(CLIENT_ID_HEADER),
                parseProtocolVersion(headers.getFirst(PROTOCOL_VERSION_HEADER))
        );
        sessions.put(session.getId(), voiceSession);
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
        sessions.remove(session.getId());
    }

    /**
     * 处理设备 hello。
     *
     * @param webSocketSession WebSocket 会话
     * @param hello 设备 hello
     */
    public void handleHello(WebSocketSession webSocketSession, XiaozhiClientHello hello) {
        var version = hello.version() <= 0 ? 1 : hello.version();
        getSession(webSocketSession.getId()).updateProtocolVersion(version);
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
        if ("abort".equals(message.type())) {
            voiceSession.markIdle();
            return;
        }
        if ("mcp".equals(message.type())) {
            log.debug("ignore xiaozhi mcp message in java gateway, sessionId={}", webSocketSession.getId());
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
        }
    }

    private void processTurn(WebSocketSession webSocketSession, XiaozhiVoiceSession voiceSession) {
        try {
            var audioFrames = voiceSession.drainAudioFrames().stream()
                    .map(frame -> ByteBuffer.wrap(frame.payload()))
                    .toList();
            var userText = speechToTextClient.transcribe(audioFrames);
            webSocketSession.sendMessage(new TextMessage(eventFactory.stt(voiceSession.sessionId(), userText)));

            var response = hermesClient.chat(new HermesRequest(
                    new DeviceId(voiceSession.deviceId()),
                    new ConversationId("conv-" + voiceSession.deviceId()),
                    userText
            ), hermesClientConfig);
            var reply = response.text();

            voiceSession.markSpeaking();
            webSocketSession.sendMessage(new TextMessage(eventFactory.llmEmotion(voiceSession.sessionId(), "neutral")));
            webSocketSession.sendMessage(new TextMessage(eventFactory.ttsStart(voiceSession.sessionId())));
            webSocketSession.sendMessage(new TextMessage(eventFactory.ttsSentenceStart(voiceSession.sessionId(), reply)));
            for (var frame : textToSpeechClient.synthesize(reply, new VoiceId("default"))) {
                webSocketSession.sendMessage(new BinaryMessage(frame));
            }
            webSocketSession.sendMessage(new TextMessage(eventFactory.ttsStop(voiceSession.sessionId())));
            voiceSession.markIdle();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to send xiaozhi websocket message", exception);
        }
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
}
