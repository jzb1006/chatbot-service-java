package com.jzb.chatbot.voice;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jzb.chatbot.common.id.ConversationId;
import com.jzb.chatbot.common.id.VoiceId;
import com.jzb.chatbot.hermes.FakeHermesClient;
import com.jzb.chatbot.hermes.HermesClient;
import com.jzb.chatbot.hermes.HermesClientConfig;
import com.jzb.chatbot.hermes.HermesRequest;
import com.jzb.chatbot.hermes.HermesResponse;
import com.jzb.chatbot.speech.FakeSpeechToTextClient;
import com.jzb.chatbot.speech.FakeTextToSpeechClient;
import com.jzb.chatbot.speech.SpeechToTextClient;
import com.jzb.chatbot.speech.TextToSpeechClient;
import com.jzb.chatbot.voice.mcp.XiaozhiMcpBridge;
import com.jzb.chatbot.voice.protocol.XiaozhiAudioParams;
import com.jzb.chatbot.voice.protocol.XiaozhiClientHello;
import com.jzb.chatbot.voice.protocol.XiaozhiClientMessage;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import com.jzb.chatbot.voice.protocol.XiaozhiServerEventFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.TextMessage;

class XiaozhiVoiceSessionServiceTest {

    private final XiaozhiMessageCodec codec = new XiaozhiMessageCodec(new ObjectMapper());
    private final XiaozhiVoiceSessionService service = new XiaozhiVoiceSessionService(
            codec,
            new FakeSpeechToTextClient(),
            new FakeHermesClient(),
            new FakeTextToSpeechClient(),
            new XiaozhiServerEventFactory(new ObjectMapper()),
            new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner"),
            new XiaozhiVoiceTokenAuth(""),
            newMcpBridge()
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
    void shouldLogConversationTextWhenTurnCompletes() {
        var logger = (Logger) LoggerFactory.getLogger(XiaozhiVoiceSessionService.class);
        var appender = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var session = openSession();
            service.handleText(session, new XiaozhiClientMessage(
                    "listen", "start", "manual", null, null, "ws-session-1", null
            ));
            service.handleBinary(session, ByteBuffer.wrap(new byte[] {1, 2, 3}));

            service.handleText(session, new XiaozhiClientMessage(
                    "listen", "stop", null, null, null, "ws-session-1", null
            ));

            assertThat(appender.list)
                    .extracting(event -> event.getFormattedMessage())
                    .anySatisfy(message -> assertThat(message)
                            .contains(
                                    "xiaozhi conversation turn",
                                    "sessionId=ws-session-1",
                                    "deviceId=ws-session-1",
                                    "conversationId=conv-ws-session-1",
                                    "userText=ping",
                                    "assistantText=pong"
                            ));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void shouldEncodeTtsBinaryWithCurrentProtocolVersion() {
        var session = openSessionWithProtocolVersion(3);
        service.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        service.handleBinary(session, codec.encodeAudioFrame(3, 0, ByteBuffer.wrap(new byte[] {1, 2, 3})));

        service.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", null, null, null, "ws-session-1", null
        ));

        assertThat(session.getSentMessages())
                .filteredOn(BinaryMessage.class::isInstance)
                .singleElement()
                .satisfies(message -> {
                    var payload = ((BinaryMessage) message).getPayload().slice();
                    assertThat(Byte.toUnsignedInt(payload.get())).isZero();
                    assertThat(Byte.toUnsignedInt(payload.get())).isZero();
                    assertThat(Short.toUnsignedInt(payload.getShort())).isGreaterThan(0);
                });
    }

    @Test
    void shouldSynthesizeEachStreamedSentenceSeparately() {
        var ttsClient = new RecordingTextToSpeechClient();
        var serviceWithStreamingHermes = new XiaozhiVoiceSessionService(
                codec,
                new FakeSpeechToTextClient(),
                new StreamingHermesClient("第一句内容很完整。", "第二句内容也完整。"),
                ttsClient,
                new XiaozhiServerEventFactory(new ObjectMapper()),
                new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner"),
                new XiaozhiVoiceTokenAuth(""),
                newMcpBridge()
        );
        var session = openSession(serviceWithStreamingHermes);

        runSingleTurn(serviceWithStreamingHermes, session);

        assertThat(ttsClient.texts()).containsExactly("第一句内容很完整。", "第二句内容也完整。");
        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"tts\"", "\"state\":\"sentence_start\"", "\"text\":\"第一句内容很完整。\""))
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"tts\"", "\"state\":\"sentence_start\"", "\"text\":\"第二句内容也完整。\""));
    }

    @Test
    void shouldCancelQueuedStreamingTtsWhenAbortReceived() {
        var ttsClient = new BlockingTextToSpeechClient();
        var serviceWithStreamingHermes = new XiaozhiVoiceSessionService(
                codec,
                new FakeSpeechToTextClient(),
                new StreamingHermesClient("第一句内容很完整。", "第二句内容也完整。"),
                ttsClient,
                new XiaozhiServerEventFactory(new ObjectMapper()),
                new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner"),
                new XiaozhiVoiceTokenAuth(""),
                newMcpBridge()
        );
        var session = openSession(serviceWithStreamingHermes);
        var turnThread = Thread.startVirtualThread(() -> runSingleTurn(serviceWithStreamingHermes, session));
        assertThat(ttsClient.awaitFirstCall()).isTrue();

        serviceWithStreamingHermes.handleText(session, new XiaozhiClientMessage(
                "abort", null, null, "wake_word_detected", null, "ws-session-1", null
        ));
        ttsClient.releaseFirstCall();
        join(turnThread);

        assertThat(ttsClient.texts()).containsExactly("第一句内容很完整。");
        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .filteredOn(payload -> payload.contains("\"type\":\"tts\"")
                        && payload.contains("\"state\":\"stop\""))
                .hasSize(1);
        assertThat(serviceWithStreamingHermes.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
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
                new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner"),
                new XiaozhiVoiceTokenAuth(""),
                newMcpBridge()
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

    @Test
    void shouldKeepSameConversationUntilSessionNewRequested() {
        var hermesClient = new RecordingHermesClient();
        var serviceWithRecordingHermes = new XiaozhiVoiceSessionService(
                codec,
                new FakeSpeechToTextClient(),
                hermesClient,
                new FakeTextToSpeechClient(),
                new XiaozhiServerEventFactory(new ObjectMapper()),
                new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner"),
                new XiaozhiVoiceTokenAuth(""),
                newMcpBridge()
        );
        var session = openSession(serviceWithRecordingHermes);

        runSingleTurn(serviceWithRecordingHermes, session);
        runSingleTurn(serviceWithRecordingHermes, session);
        serviceWithRecordingHermes.handleText(session, new XiaozhiClientMessage(
                "session", "new", null, null, null, "ws-session-1", null
        ));
        runSingleTurn(serviceWithRecordingHermes, session);

        assertThat(hermesClient.conversationIds()).hasSize(3);
        assertThat(hermesClient.conversationIds().get(0)).isEqualTo(hermesClient.conversationIds().get(1));
        assertThat(hermesClient.conversationIds().get(2)).isNotEqualTo(hermesClient.conversationIds().get(0));
        assertThat(hermesClient.conversationIds().get(2)).startsWith("conv-ws-session-1-ws-session-1-");
        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .anySatisfy(payload -> assertThat(payload)
                        .contains("\"type\":\"session\"", "\"state\":\"ready\"", "\"conversation_id\""));
    }

    @Test
    void shouldSkipHermesAndReturnIdleWhenAsrTextIsBlank() {
        var hermesClient = new CapturingHermesClient();
        var serviceWithBlankAsr = new XiaozhiVoiceSessionService(
                codec,
                audioFrames -> " ",
                hermesClient,
                new FakeTextToSpeechClient(),
                new XiaozhiServerEventFactory(new ObjectMapper()),
                new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner"),
                new XiaozhiVoiceTokenAuth(""),
                newMcpBridge()
        );
        var session = openSession(serviceWithBlankAsr);

        serviceWithBlankAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        serviceWithBlankAsr.handleBinary(session, ByteBuffer.wrap(new byte[] {1, 2, 3}));
        serviceWithBlankAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", null, null, null, "ws-session-1", null
        ));

        assertThat(hermesClient.request()).isNull();
        assertThat(serviceWithBlankAsr.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"error\"", "\"code\":\"asr_empty\""));
    }

    @Test
    void shouldReturnIdleWhenHermesFails() {
        var serviceWithFailingHermes = new XiaozhiVoiceSessionService(
                codec,
                new FakeSpeechToTextClient(),
                new FailingHermesClient(),
                new FakeTextToSpeechClient(),
                new XiaozhiServerEventFactory(new ObjectMapper()),
                new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner"),
                new XiaozhiVoiceTokenAuth(""),
                newMcpBridge()
        );
        var session = openSession(serviceWithFailingHermes);
        serviceWithFailingHermes.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        serviceWithFailingHermes.handleBinary(session, ByteBuffer.wrap(new byte[] {1, 2, 3}));

        serviceWithFailingHermes.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", null, null, null, "ws-session-1", null
        ));

        assertThat(serviceWithFailingHermes.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"stt\"", "\"text\":\"ping\""))
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"error\"", "\"code\":\"hermes_failed\""));
    }

    @Test
    void shouldSendTtsStopWhenSynthesizedAudioIsEmpty() {
        var serviceWithEmptyTts = new XiaozhiVoiceSessionService(
                codec,
                new FakeSpeechToTextClient(),
                new FakeHermesClient(),
                new EmptyTextToSpeechClient(),
                new XiaozhiServerEventFactory(new ObjectMapper()),
                new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner"),
                new XiaozhiVoiceTokenAuth(""),
                newMcpBridge()
        );
        var session = openSession(serviceWithEmptyTts);
        serviceWithEmptyTts.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        serviceWithEmptyTts.handleBinary(session, ByteBuffer.wrap(new byte[] {1, 2, 3}));

        serviceWithEmptyTts.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", null, null, null, "ws-session-1", null
        ));

        assertThat(session.getSentMessages()).noneSatisfy(message -> assertThat(message).isInstanceOf(BinaryMessage.class));
        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"tts\"", "\"state\":\"stop\""));
        assertThat(serviceWithEmptyTts.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
    }

    @Test
    void shouldSendTtsStopWhenTextToSpeechFailsAfterTtsStart() {
        var serviceWithFailingTts = new XiaozhiVoiceSessionService(
                codec,
                new FakeSpeechToTextClient(),
                new FakeHermesClient(),
                new FailingTextToSpeechClient(),
                new XiaozhiServerEventFactory(new ObjectMapper()),
                new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner"),
                new XiaozhiVoiceTokenAuth(""),
                newMcpBridge()
        );
        var session = openSession(serviceWithFailingTts);
        serviceWithFailingTts.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        serviceWithFailingTts.handleBinary(session, ByteBuffer.wrap(new byte[] {1, 2, 3}));

        serviceWithFailingTts.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", null, null, null, "ws-session-1", null
        ));

        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"tts\"", "\"state\":\"start\""))
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"tts\"", "\"state\":\"stop\""))
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"error\"", "\"code\":\"tts_failed\""));
        assertThat(serviceWithFailingTts.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
    }

    @Test
    void shouldSendErrorEventWhenTtsBinarySendFails() {
        var session = new BinarySendFailingSession("ws-session-1");
        service.open(session);
        service.handleHello(session, new XiaozhiClientHello(
                "hello",
                1,
                Map.of("mcp", true),
                "websocket",
                XiaozhiAudioParams.defaults()
        ));
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
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"tts\"", "\"state\":\"stop\""))
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"error\"", "\"code\":\"tts_failed\""));
        assertThat(service.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
    }

    @Test
    void shouldIgnoreBinaryFrameOutsideListeningState() {
        var recordingSpeech = new RecordingSpeechToTextClient();
        var serviceWithRecordingSpeech = new XiaozhiVoiceSessionService(
                codec,
                recordingSpeech,
                new FakeHermesClient(),
                new FakeTextToSpeechClient(),
                new XiaozhiServerEventFactory(new ObjectMapper()),
                new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner"),
                new XiaozhiVoiceTokenAuth(""),
                newMcpBridge()
        );
        var session = openSession(serviceWithRecordingSpeech);

        serviceWithRecordingSpeech.handleBinary(session, ByteBuffer.wrap(new byte[] {9, 9, 9}));
        serviceWithRecordingSpeech.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        serviceWithRecordingSpeech.handleBinary(session, ByteBuffer.wrap(new byte[] {1, 2, 3}));
        serviceWithRecordingSpeech.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", null, null, null, "ws-session-1", null
        ));

        assertThat(recordingSpeech.audioFramePayloads())
                .singleElement()
                .isEqualTo(List.of(1, 2, 3));
    }

    @Test
    void shouldBridgeMcpInboundResponse() throws Exception {
        var mcpBridge = newMcpBridge();
        var serviceWithBridge = new XiaozhiVoiceSessionService(
                codec,
                new FakeSpeechToTextClient(),
                new FakeHermesClient(),
                new FakeTextToSpeechClient(),
                new XiaozhiServerEventFactory(new ObjectMapper()),
                new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner"),
                new XiaozhiVoiceTokenAuth(""),
                mcpBridge
        );
        var headers = new HttpHeaders();
        headers.set("Device-Id", "device-1");
        var session = new TestWebSocketSession("ws-session-1", URI.create("ws://127.0.0.1/xiaozhi/v1"), headers);
        serviceWithBridge.open(session);
        var future = mcpBridge.call("device-1", new ObjectMapper().readTree("""
                {"jsonrpc":"2.0","id":9,"method":"tools/list"}
                """), Duration.ofSeconds(1));

        serviceWithBridge.handleText(session, new XiaozhiClientMessage(
                "mcp", null, null, null, null, "ws-session-1", new ObjectMapper().readTree("""
                        {"jsonrpc":"2.0","id":9,"result":{"tools":[]}}
                        """)
        ));

        assertThat(future).succeedsWithin(Duration.ofMillis(100))
                .satisfies(json -> assertThat(json.path("result").path("tools").isArray()).isTrue());
    }

    private TestWebSocketSession openSession() {
        return openSession(service);
    }

    private TestWebSocketSession openSession(XiaozhiVoiceSessionService service) {
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

    private TestWebSocketSession openSessionWithProtocolVersion(int protocolVersion) {
        var session = new TestWebSocketSession("ws-session-1");
        service.open(session);
        service.handleHello(session, new XiaozhiClientHello(
                "hello",
                protocolVersion,
                Map.of("mcp", true),
                "websocket",
                XiaozhiAudioParams.defaults()
        ));
        return session;
    }

    private void runSingleTurn(XiaozhiVoiceSessionService service, TestWebSocketSession session) {
        service.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        service.handleBinary(session, ByteBuffer.wrap(new byte[] {1, 2, 3}));
        service.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", null, null, null, "ws-session-1", null
        ));
    }

    private XiaozhiMcpBridge newMcpBridge() {
        return new XiaozhiMcpBridge(new XiaozhiServerEventFactory(new ObjectMapper()));
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
            this.request = request;
            return Stream.of("event: message\ndata: {\"answer\":\"pong\"}\n\n");
        }

        private HermesRequest request() {
            return request;
        }
    }

    private static class RecordingHermesClient implements HermesClient {

        private final java.util.ArrayList<String> conversationIds = new java.util.ArrayList<>();

        @Override
        public HermesResponse chat(HermesRequest request, HermesClientConfig config) {
            conversationIds.add(request.conversationId().value());
            return new HermesResponse(request.conversationId(), "pong");
        }

        @Override
        public Stream<String> streamChat(HermesRequest request, HermesClientConfig config) {
            conversationIds.add(request.conversationId().value());
            return Stream.of("event: message\ndata: {\"answer\":\"pong\"}\n\n");
        }

        private List<String> conversationIds() {
            return List.copyOf(conversationIds);
        }
    }

    private static class FailingHermesClient implements HermesClient {

        @Override
        public HermesResponse chat(HermesRequest request, HermesClientConfig config) {
            throw new IllegalStateException("hermes unavailable");
        }

        @Override
        public Stream<String> streamChat(HermesRequest request, HermesClientConfig config) {
            throw new IllegalStateException("hermes unavailable");
        }
    }

    private static class StreamingHermesClient implements HermesClient {

        private final List<String> chunks;

        private StreamingHermesClient(String... chunks) {
            this.chunks = List.of(chunks);
        }

        @Override
        public HermesResponse chat(HermesRequest request, HermesClientConfig config) {
            return new HermesResponse(request.conversationId(), String.join("", chunks));
        }

        @Override
        public Stream<String> streamChat(HermesRequest request, HermesClientConfig config) {
            return chunks.stream()
                    .map(chunk -> "event: response.output_text.delta\ndata: {\"delta\":\"" + chunk + "\"}\n\n");
        }
    }

    private static class RecordingTextToSpeechClient implements TextToSpeechClient {

        private final java.util.ArrayList<String> texts = new java.util.ArrayList<>();

        @Override
        public List<ByteBuffer> synthesize(String text, VoiceId voiceId) {
            texts.add(text);
            return List.of(ByteBuffer.wrap(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        private List<String> texts() {
            return List.copyOf(texts);
        }
    }

    private static class BlockingTextToSpeechClient implements TextToSpeechClient {

        private final java.util.ArrayList<String> texts = new java.util.ArrayList<>();
        private final java.util.concurrent.CountDownLatch firstCallStarted = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch releaseFirstCall = new java.util.concurrent.CountDownLatch(1);

        @Override
        public List<ByteBuffer> synthesize(String text, VoiceId voiceId) {
            texts.add(text);
            if (texts.size() == 1) {
                firstCallStarted.countDown();
                await(releaseFirstCall);
            }
            return List.of(ByteBuffer.wrap(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        private boolean awaitFirstCall() {
            try {
                return firstCallStarted.await(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void releaseFirstCall() {
            releaseFirstCall.countDown();
        }

        private List<String> texts() {
            return List.copyOf(texts);
        }
    }

    private static class EmptyTextToSpeechClient implements TextToSpeechClient {

        @Override
        public List<ByteBuffer> synthesize(String text, VoiceId voiceId) {
            return List.of();
        }
    }

    private static class FailingTextToSpeechClient implements TextToSpeechClient {

        @Override
        public List<ByteBuffer> synthesize(String text, VoiceId voiceId) {
            throw new IllegalStateException("tts unavailable");
        }
    }

    private static class BinarySendFailingSession extends TestWebSocketSession {

        BinarySendFailingSession(String id) {
            super(id);
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            if (message instanceof BinaryMessage) {
                throw new IOException("binary send unavailable");
            }
            super.sendMessage(message);
        }
    }

    private static class RecordingSpeechToTextClient implements SpeechToTextClient {

        private List<List<Integer>> audioFramePayloads = List.of();

        @Override
        public String transcribe(List<ByteBuffer> audioFrames) {
            audioFramePayloads = audioFrames.stream()
                    .map(RecordingSpeechToTextClient::toUnsignedBytes)
                    .toList();
            return "ping";
        }

        private List<List<Integer>> audioFramePayloads() {
            return audioFramePayloads;
        }

        private static List<Integer> toUnsignedBytes(ByteBuffer buffer) {
            var input = buffer.slice();
            var values = new java.util.ArrayList<Integer>();
            while (input.hasRemaining()) {
                values.add(Byte.toUnsignedInt(input.get()));
            }
            return List.copyOf(values);
        }
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(Duration.ofSeconds(1));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
