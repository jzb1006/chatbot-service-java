package com.jzb.chatbot.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * 小智 WebSocket 协议处理器。
 * <p>
 * 第一阶段只完成握手、listen.start ack 和音频帧接收。
 *
 * @author jiangzhibin
 * @since 2026-06-14 18:36:50
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class XiaozhiWebSocketHandler extends AbstractWebSocketHandler {

    private static final String LISTEN_TYPE = "listen";
    private static final String START_STATE = "start";

    private final XiaozhiMessageCodec codec;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        var conversationId = "conv-" + session.getId();
        var hello = codec.encodeServerHello(session.getId(), conversationId);
        session.sendMessage(new TextMessage(hello));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        var clientMessage = codec.decodeText(message.getPayload());
        if (LISTEN_TYPE.equals(clientMessage.type()) && START_STATE.equals(clientMessage.state())) {
            sendListenStartAck(session);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        log.debug("received xiaozhi binary audio frame, sessionId={}, bytes={}",
                session.getId(), message.getPayloadLength());
    }

    private void sendListenStartAck(WebSocketSession session) throws IOException {
        var ack = objectMapper.createObjectNode()
                .put("type", "ack")
                .put("ack_type", LISTEN_TYPE)
                .put("ack_state", START_STATE);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(ack)));
    }
}
