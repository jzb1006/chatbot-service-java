package com.jzb.chatbot.voice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import com.jzb.chatbot.voice.protocol.XiaozhiProtocolException;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * 小智 WebSocket 协议处理器。
 * <p>
 * 负责 WebSocket 连接生命周期、控制帧分发和二进制音频帧入口处理。
 *
 * @author jiangzhibin
 * @since 2026-06-14 18:36:50
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class XiaozhiWebSocketHandler extends AbstractWebSocketHandler {

    private static final String HELLO_TYPE = "hello";

    private final XiaozhiMessageCodec codec;
    private final XiaozhiVoiceSessionService sessionService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (!sessionService.open(session)) {
            session.close(CloseStatus.POLICY_VIOLATION);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (sessionService.getSession(session.getId()) == null) {
            log.debug("ignore xiaozhi control frame for unauthenticated session, sessionId={}", session.getId());
            return;
        }
        try {
            var clientMessage = codec.decodeText(message.getPayload());
            if (HELLO_TYPE.equals(clientMessage.type())) {
                var clientHello = codec.decodeClientHello(message.getPayload());
                sessionService.handleHello(session, clientHello);
                session.sendMessage(new TextMessage(codec.encodeServerHello(session.getId())));
                return;
            }
            sessionService.handleText(session, clientMessage);
        } catch (JsonProcessingException exception) {
            log.warn("invalid xiaozhi control frame, sessionId={}, message={}", session.getId(), exception.getOriginalMessage());
            session.close(CloseStatus.BAD_DATA);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        if (sessionService.getSession(session.getId()) == null) {
            log.debug("ignore xiaozhi binary frame for unauthenticated session, sessionId={}", session.getId());
            return;
        }
        try {
            log.debug("received xiaozhi binary audio frame, sessionId={}, bytes={}",
                    session.getId(), message.getPayloadLength());
            sessionService.handleBinary(session, message.getPayload());
        } catch (XiaozhiProtocolException exception) {
            log.warn("invalid xiaozhi binary frame, sessionId={}, message={}", session.getId(), exception.getMessage());
            try {
                session.close(CloseStatus.BAD_DATA);
            } catch (IOException ioException) {
                throw new IllegalStateException("Failed to close invalid xiaozhi websocket session", ioException);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionService.close(session);
    }
}
