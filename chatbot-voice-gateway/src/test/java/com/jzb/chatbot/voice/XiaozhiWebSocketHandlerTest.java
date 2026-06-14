package com.jzb.chatbot.voice;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

class XiaozhiWebSocketHandlerTest {

    @Test
    void shouldSendHelloAfterConnectionEstablished() throws Exception {
        var codec = new XiaozhiMessageCodec(new ObjectMapper());
        var handler = new XiaozhiWebSocketHandler(codec);
        var session = new TestWebSocketSession("ws-session-1");

        handler.afterConnectionEstablished(session);

        assertThat(session.getSentMessages()).hasSize(1);
        assertThat(session.getSentMessages().getFirst().getPayload().toString())
                .contains("\"type\":\"hello\"");
    }

    private static class TestWebSocketSession implements WebSocketSession {

        private final String id;
        private final Map<String, Object> attributes = new HashMap<>();
        private final List<WebSocketMessage<?>> sentMessages = new ArrayList<>();
        private boolean open = true;

        TestWebSocketSession(String id) {
            this.id = id;
        }

        List<WebSocketMessage<?>> getSentMessages() {
            return sentMessages;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public URI getUri() {
            return URI.create("ws://127.0.0.1/xiaozhi/v1");
        }

        @Override
        public HttpHeaders getHandshakeHeaders() {
            return HttpHeaders.EMPTY;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getTextMessageSizeLimit() {
            return 0;
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return 0;
        }

        @Override
        public List<WebSocketExtension> getExtensions() {
            return List.of();
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) {
            sentMessages.add(message);
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() throws IOException {
            open = false;
        }

        @Override
        public void close(CloseStatus status) throws IOException {
            open = false;
        }
    }
}
