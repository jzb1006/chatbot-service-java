package com.jzb.chatbot.voice;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jzb.chatbot.hermes.FakeHermesClient;
import com.jzb.chatbot.hermes.HermesClientConfig;
import com.jzb.chatbot.speech.FakeSpeechToTextClient;
import com.jzb.chatbot.speech.FakeTextToSpeechClient;
import com.jzb.chatbot.voice.protocol.XiaozhiAudioParams;
import com.jzb.chatbot.voice.protocol.XiaozhiClientHello;
import com.jzb.chatbot.voice.protocol.XiaozhiClientMessage;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import com.jzb.chatbot.voice.protocol.XiaozhiServerEventFactory;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;

class XiaozhiVoiceSessionServiceTest {

    private final XiaozhiMessageCodec codec = new XiaozhiMessageCodec(new ObjectMapper());
    private final XiaozhiVoiceSessionService service = new XiaozhiVoiceSessionService(
            codec,
            new FakeSpeechToTextClient(),
            new FakeHermesClient(),
            new FakeTextToSpeechClient(),
            new XiaozhiServerEventFactory(new ObjectMapper()),
            new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner")
    );

    @Test
    void shouldEnterListeningWhenListenStartReceived() {
        var session = openSession();

        service.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));

        assertThat(service.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.LISTENING);
    }

    @Test
    void shouldProcessAndReturnIdleWhenListenStopReceived() {
        var session = openSession();
        service.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));

        service.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", null, null, null, "ws-session-1", null
        ));

        assertThat(service.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
        assertThat(session.getSentMessages()).isNotEmpty();
    }

    @Test
    void shouldClearSpeakingStateWhenAbortReceived() {
        var session = openSession();
        service.getSession(session.getId()).markSpeaking();

        service.handleText(session, new XiaozhiClientMessage(
                "abort", null, null, "wake_word_detected", null, "ws-session-1", null
        ));

        assertThat(service.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
    }

    @Test
    void shouldStoreBinaryAudioFrameWhenListening() {
        var session = openSession();
        service.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));

        service.handleBinary(session, ByteBuffer.wrap(new byte[] {1, 2, 3}));

        assertThat(service.getSession(session.getId()).drainAudioFrames())
                .singleElement()
                .satisfies(frame -> assertThat(frame.payload()).containsExactly(1, 2, 3));
    }

    @Test
    void shouldSendSttHermesAndTtsEventsWhenListenStops() {
        var session = openSession();
        service.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        service.handleBinary(session, ByteBuffer.wrap(new byte[] {1, 2, 3}));

        service.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", null, null, null, "ws-session-1", null
        ));

        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"stt\"", "\"text\":\"ping\""))
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"llm\"", "\"emotion\":\"neutral\""))
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"tts\"", "\"state\":\"start\""))
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"tts\"", "\"state\":\"sentence_start\"", "\"text\":\"pong\""))
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"tts\"", "\"state\":\"stop\""));
        assertThat(session.getSentMessages())
                .anySatisfy(message -> assertThat(message).isInstanceOf(BinaryMessage.class));
    }

    private TestWebSocketSession openSession() {
        var session = new TestWebSocketSession("ws-session-1");
        service.open(session);
        service.handleHello(session, new XiaozhiClientHello(
                "hello",
                1,
                Map.of("mcp", true),
                "websocket",
                XiaozhiAudioParams.defaults()
        ));
        return session;
    }
}
