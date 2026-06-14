package com.jzb.chatbot.voice;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jzb.chatbot.hermes.FakeHermesClient;
import com.jzb.chatbot.hermes.HermesClientConfig;
import com.jzb.chatbot.speech.FakeSpeechToTextClient;
import com.jzb.chatbot.speech.FakeTextToSpeechClient;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import com.jzb.chatbot.voice.protocol.XiaozhiServerEventFactory;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;

class XiaozhiWebSocketHandlerTest {

    @Test
    void shouldNotSendHelloBeforeClientHello() throws Exception {
        var codec = new XiaozhiMessageCodec(new ObjectMapper());
        var sessionService = newSessionService(codec);
        var handler = new XiaozhiWebSocketHandler(codec, sessionService);
        var session = new TestWebSocketSession("ws-session-1");

        handler.afterConnectionEstablished(session);

        assertThat(session.getSentMessages()).isEmpty();
    }

    @Test
    void shouldReplyServerHelloWhenClientHelloReceived() throws Exception {
        var codec = new XiaozhiMessageCodec(new ObjectMapper());
        var sessionService = newSessionService(codec);
        var handler = new XiaozhiWebSocketHandler(codec, sessionService);
        var session = new TestWebSocketSession("ws-session-1");
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new org.springframework.web.socket.TextMessage("""
                {
                  "type": "hello",
                  "version": 2,
                  "features": {"mcp": true},
                  "transport": "websocket",
                  "audio_params": {
                    "format": "opus",
                    "sample_rate": 16000,
                    "channels": 1,
                    "frame_duration": 60
                  }
                }
                """));

        assertThat(session.getSentMessages()).hasSize(1);
        assertThat(session.getSentMessages().getFirst().getPayload().toString())
                .contains("\"type\":\"hello\"")
                .contains("\"audio_params\"")
                .doesNotContain("\"audio\"");
    }

    @Test
    void shouldNotSendAckWhenListenStartReceived() throws Exception {
        var codec = new XiaozhiMessageCodec(new ObjectMapper());
        var sessionService = newSessionService(codec);
        var handler = new XiaozhiWebSocketHandler(codec, sessionService);
        var session = new TestWebSocketSession("ws-session-1");
        handler.afterConnectionEstablished(session);
        handler.handleMessage(session, new org.springframework.web.socket.TextMessage("""
                {
                  "type": "hello",
                  "version": 1,
                  "transport": "websocket",
                  "audio_params": {
                    "format": "opus",
                    "sample_rate": 16000,
                    "channels": 1,
                    "frame_duration": 60
                  }
                }
                """));
        session.getSentMessages().clear();

        handler.handleMessage(session, new TextMessage("""
                {
                  "session_id": "ws-session-1",
                  "type": "listen",
                  "state": "start",
                  "mode": "manual"
                }
                """));

        assertThat(session.getSentMessages()).isEmpty();
        assertThat(sessionService.getSession(session.getId()).state())
                .isEqualTo(XiaozhiVoiceSession.State.LISTENING);
    }

    @Test
    void shouldCloseSessionWhenInvalidJsonReceived() throws Exception {
        var codec = new XiaozhiMessageCodec(new ObjectMapper());
        var sessionService = newSessionService(codec);
        var handler = new XiaozhiWebSocketHandler(codec, sessionService);
        var session = new TestWebSocketSession("ws-session-1");
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new TextMessage("{bad-json"));

        assertThat(session.isOpen()).isFalse();
        assertThat(session.getCloseStatus()).isEqualTo(CloseStatus.BAD_DATA);
    }

    @Test
    void shouldCloseSessionWhenInvalidBinaryFrameReceived() throws Exception {
        var codec = new XiaozhiMessageCodec(new ObjectMapper());
        var sessionService = newSessionService(codec);
        var handler = new XiaozhiWebSocketHandler(codec, sessionService);
        var session = new TestWebSocketSession("ws-session-1");
        handler.afterConnectionEstablished(session);
        handler.handleMessage(session, new TextMessage("""
                {
                  "type": "hello",
                  "version": 2,
                  "transport": "websocket",
                  "audio_params": {
                    "format": "opus",
                    "sample_rate": 16000,
                    "channels": 1,
                    "frame_duration": 60
                  }
                }
                """));
        session.getSentMessages().clear();

        handler.handleMessage(session, new BinaryMessage(new byte[] {1, 2, 3}));

        assertThat(session.isOpen()).isFalse();
        assertThat(session.getCloseStatus()).isEqualTo(CloseStatus.BAD_DATA);
    }

    @Test
    void shouldRemoveSessionAfterConnectionClosed() throws Exception {
        var codec = new XiaozhiMessageCodec(new ObjectMapper());
        var sessionService = newSessionService(codec);
        var handler = new XiaozhiWebSocketHandler(codec, sessionService);
        var session = new TestWebSocketSession("ws-session-1");
        handler.afterConnectionEstablished(session);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(sessionService.getSession(session.getId())).isNull();
    }

    private XiaozhiVoiceSessionService newSessionService(XiaozhiMessageCodec codec) {
        return new XiaozhiVoiceSessionService(
                codec,
                new FakeSpeechToTextClient(),
                new FakeHermesClient(),
                new FakeTextToSpeechClient(),
                new XiaozhiServerEventFactory(new ObjectMapper()),
                new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner")
        );
    }
}
