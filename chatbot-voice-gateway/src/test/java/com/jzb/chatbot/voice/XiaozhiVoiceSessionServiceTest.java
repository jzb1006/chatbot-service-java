package com.jzb.chatbot.voice;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jzb.chatbot.common.id.ConversationId;
import com.jzb.chatbot.hermes.FakeHermesClient;
import com.jzb.chatbot.hermes.HermesClient;
import com.jzb.chatbot.hermes.HermesClientConfig;
import com.jzb.chatbot.hermes.HermesRequest;
import com.jzb.chatbot.hermes.HermesResponse;
import com.jzb.chatbot.speech.FakeSpeechToTextClient;
import com.jzb.chatbot.speech.FakeTextToSpeechClient;
import com.jzb.chatbot.voice.protocol.XiaozhiAudioParams;
import com.jzb.chatbot.voice.protocol.XiaozhiClientHello;
import com.jzb.chatbot.voice.protocol.XiaozhiClientMessage;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import com.jzb.chatbot.voice.protocol.XiaozhiServerEventFactory;
import java.nio.ByteBuffer;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
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
    void shouldStoreFirmwareHandshakeParametersWhenSessionOpened() {
        var headers = new HttpHeaders();
        headers.setBearerAuth("firmware-token");
        headers.set("Protocol-Version", "3");
        headers.set("Device-Id", "aa:bb:cc:dd:ee:ff");
        headers.set("Client-Id", "client-uuid-1");
        var session = new TestWebSocketSession(
                "ws-session-1",
                URI.create("ws://127.0.0.1/ws/xiaozhi/v1/"),
                headers
        );

        service.open(session);

        assertThat(service.getSession(session.getId()))
                .satisfies(voiceSession -> {
                    assertThat(voiceSession.protocolVersion()).isEqualTo(3);
                    assertThat(voiceSession.deviceId()).isEqualTo("aa:bb:cc:dd:ee:ff");
                    assertThat(voiceSession.clientId()).isEqualTo("client-uuid-1");
                    assertThat(voiceSession.authorization()).isEqualTo("Bearer firmware-token");
                });
    }

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

    @Test
    void shouldUseFirmwareDeviceIdWhenCallingHermes() {
        var hermesClient = new CapturingHermesClient();
        var serviceWithCapturingHermes = new XiaozhiVoiceSessionService(
                codec,
                new FakeSpeechToTextClient(),
                hermesClient,
                new FakeTextToSpeechClient(),
                new XiaozhiServerEventFactory(new ObjectMapper()),
                new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner")
        );
        var headers = new HttpHeaders();
        headers.set("Device-Id", "aa:bb:cc:dd:ee:ff");
        var session = new TestWebSocketSession(
                "ws-session-1",
                URI.create("ws://127.0.0.1/ws/xiaozhi/v1/"),
                headers
        );
        serviceWithCapturingHermes.open(session);
        serviceWithCapturingHermes.handleHello(session, new XiaozhiClientHello(
                "hello",
                1,
                Map.of("mcp", true),
                "websocket",
                XiaozhiAudioParams.defaults()
        ));
        serviceWithCapturingHermes.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        serviceWithCapturingHermes.handleBinary(session, ByteBuffer.wrap(new byte[] {1, 2, 3}));

        serviceWithCapturingHermes.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", null, null, null, "ws-session-1", null
        ));

        assertThat(hermesClient.request().deviceId().value()).isEqualTo("aa:bb:cc:dd:ee:ff");
        assertThat(hermesClient.request().conversationId().value()).isEqualTo("conv-aa:bb:cc:dd:ee:ff");
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

    private static class CapturingHermesClient implements HermesClient {

        private HermesRequest request;

        @Override
        public HermesResponse chat(HermesRequest request, HermesClientConfig config) {
            this.request = request;
            return new HermesResponse(new ConversationId("conv-response"), "pong");
        }

        @Override
        public Stream<String> streamChat(HermesRequest request, HermesClientConfig config) {
            return Stream.of();
        }

        private HermesRequest request() {
            return request;
        }
    }
}
