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
import com.jzb.chatbot.speech.FakeStreamingSpeechToTextClient;
import com.jzb.chatbot.speech.FakeTextToSpeechClient;
import com.jzb.chatbot.speech.SpeechToTextAudioStream;
import com.jzb.chatbot.speech.SpeechToTextClient;
import com.jzb.chatbot.speech.SpeechToTextResult;
import com.jzb.chatbot.speech.StreamingSpeechToTextClient;
import com.jzb.chatbot.speech.TextToSpeechClient;
import com.jzb.chatbot.voice.mcp.XiaozhiMcpBridge;
import com.jzb.chatbot.voice.protocol.XiaozhiAudioParams;
import com.jzb.chatbot.voice.protocol.XiaozhiClientHello;
import com.jzb.chatbot.voice.protocol.XiaozhiClientMessage;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import com.jzb.chatbot.voice.protocol.XiaozhiServerEventFactory;
import com.jzb.chatbot.voice.reminder.XiaozhiReminderRequestedEvent;
import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.TextMessage;

class XiaozhiVoiceSessionServiceTest {

    private final XiaozhiMessageCodec codec = new XiaozhiMessageCodec(new ObjectMapper());
    private final XiaozhiVoiceSessionService service = newService(new FakeTextToSpeechClient());

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
    void shouldWritePcmChunksToStreamingAsrBeforeListenStop() {
        var streamingSpeech = new CapturingStreamingSpeechToTextClient();
        var serviceWithStreamingAsr = newService(
                new RecordingSpeechToTextClient(),
                new FakeHermesClient(),
                new FakeTextToSpeechClient(),
                new XiaozhiAsrMode("streaming"),
                streamingSpeech
        );
        var session = openSession(serviceWithStreamingAsr);

        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        serviceWithStreamingAsr.handleBinary(session, ByteBuffer.wrap(encodeOpusFrame()));

        assertThat(streamingSpeech.awaitChunkCountAtLeast(1)).isTrue();

        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", null, null, null, "ws-session-1", null
        ));

        assertThat(awaitIdle(serviceWithStreamingAsr, session)).isTrue();
        assertThat(streamingSpeech.chunkCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void shouldKeepSentencePathWhenAsrModeIsSentence() {
        var sentenceSpeech = new RecordingSpeechToTextClient();
        var streamingSpeech = new CapturingStreamingSpeechToTextClient();
        var serviceWithSentenceAsr = newService(
                sentenceSpeech,
                new FakeHermesClient(),
                new FakeTextToSpeechClient(),
                new XiaozhiAsrMode("sentence"),
                streamingSpeech
        );
        var session = openSession(serviceWithSentenceAsr);

        serviceWithSentenceAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        serviceWithSentenceAsr.handleBinary(session, ByteBuffer.wrap(new byte[] {1, 2, 3}));
        serviceWithSentenceAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", null, null, null, "ws-session-1", null
        ));

        assertThat(sentenceSpeech.callCount()).isEqualTo(1);
        assertThat(streamingSpeech.chunkCount()).isZero();
        assertThat(serviceWithSentenceAsr.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
    }

    @Test
    void shouldUseStreamingPathWhenAsrModeIsStreaming() {
        var sentenceSpeech = new RecordingSpeechToTextClient();
        var streamingSpeech = new CapturingStreamingSpeechToTextClient();
        var serviceWithStreamingAsr = newService(
                sentenceSpeech,
                new FakeHermesClient(),
                new FakeTextToSpeechClient(),
                new XiaozhiAsrMode("streaming"),
                streamingSpeech
        );
        var session = openSession(serviceWithStreamingAsr);

        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        serviceWithStreamingAsr.handleBinary(session, ByteBuffer.wrap(encodeOpusFrame()));
        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", null, null, null, "ws-session-1", null
        ));

        assertThat(awaitIdle(serviceWithStreamingAsr, session)).isTrue();
        assertThat(sentenceSpeech.callCount()).isZero();
        assertThat(streamingSpeech.chunkCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void shouldReturnIdleWhenStreamingWorkerFinishesBeforeListenStop() throws Exception {
        var streamingSpeech = new TimeoutStreamingSpeechToTextClient();
        var serviceWithStreamingAsr = newService(
                new RecordingSpeechToTextClient(),
                new FakeHermesClient(),
                new FakeTextToSpeechClient(),
                new XiaozhiAsrMode("streaming"),
                streamingSpeech
        );
        var session = openSession(serviceWithStreamingAsr);

        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        assertThat(streamingSpeech.awaitFinished()).isTrue();
        assertThat(awaitIdle(serviceWithStreamingAsr, session)).isTrue();
        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", null, null, null, "ws-session-1", null
        ));

        assertThat(serviceWithStreamingAsr.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
    }

    @Test
    void shouldCompleteStreamingAsrWhenSessionCloses() throws Exception {
        var streamingSpeech = new EndAwareStreamingSpeechToTextClient();
        var serviceWithStreamingAsr = newService(
                new RecordingSpeechToTextClient(),
                new FakeHermesClient(),
                new FakeTextToSpeechClient(),
                new XiaozhiAsrMode("streaming"),
                streamingSpeech
        );
        var session = openSession(serviceWithStreamingAsr);

        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        assertThat(streamingSpeech.awaitStarted()).isTrue();
        serviceWithStreamingAsr.close(session);

        assertThat(streamingSpeech.awaitEnd()).isTrue();
    }

    @Test
    void shouldNotLetPreviousStreamingTurnClearCurrentStream() {
        var streamingSpeech = new TimeoutStreamingSpeechToTextClient();
        var serviceWithStreamingAsr = newService(
                new RecordingSpeechToTextClient(),
                new FakeHermesClient(),
                new FakeTextToSpeechClient(),
                new XiaozhiAsrMode("streaming"),
                streamingSpeech
        );
        var session = openSession(serviceWithStreamingAsr);

        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        serviceWithStreamingAsr.handleBinary(session, ByteBuffer.wrap(encodeOpusFrame()));
        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", null, null, null, "ws-session-1", null
        ));

        assertThat(awaitIdle(serviceWithStreamingAsr, session)).isTrue();
        assertThat(streamingSpeech.chunkCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void shouldIgnorePreviousStreamingTurnFailureAfterReplacement() throws Exception {
        var streamingSpeech = new ReplacedTurnFailingStreamingSpeechToTextClient();
        var serviceWithStreamingAsr = newService(
                new RecordingSpeechToTextClient(),
                new FakeHermesClient(),
                new FakeTextToSpeechClient(),
                new XiaozhiAsrMode("streaming"),
                streamingSpeech
        );
        var session = openSession(serviceWithStreamingAsr);

        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        assertThat(streamingSpeech.awaitFirstCallStarted()).isTrue();
        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        assertThat(streamingSpeech.awaitSecondCallStarted()).isTrue();
        assertThat(streamingSpeech.awaitOldTurnReadyToFail()).isTrue();
        streamingSpeech.releaseOldFailure();

        awaitCondition(
                () -> serviceWithStreamingAsr.getSession(session.getId()).state() != XiaozhiVoiceSession.State.LISTENING
                        || hasTextMessageContaining(session, "\"code\":\"asr_failed\""),
                Duration.ofMillis(200)
        );

        assertThat(hasTextMessageContaining(session, "\"code\":\"asr_failed\"")).isFalse();
        assertThat(serviceWithStreamingAsr.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.LISTENING);

        serviceWithStreamingAsr.handleBinary(session, ByteBuffer.wrap(encodeOpusFrame()));
        assertThat(streamingSpeech.awaitSecondChunkCountAtLeast(1)).isTrue();
        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", null, null, null, "ws-session-1", null
        ));

        assertThat(awaitIdle(serviceWithStreamingAsr, session)).isTrue();
    }

    @Test
    void shouldNotLetPreviousStreamingTurnFinishPlaybackAfterNewListenStart() throws Exception {
        var ttsClient = new BlockingTextToSpeechClient();
        var serviceWithStreamingAsr = newService(
                new RecordingSpeechToTextClient(),
                new StreamingHermesClient("旧回合回答。"),
                ttsClient,
                new XiaozhiAsrMode("streaming"),
                new EndAwareStreamingSpeechToTextClient()
        );
        var session = openSession(serviceWithStreamingAsr);

        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", null, null, null, "ws-session-1", null
        ));
        assertThat(ttsClient.awaitFirstCall()).isTrue();

        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        ttsClient.releaseFirstCall();

        assertThat(awaitCondition(
                () -> serviceWithStreamingAsr.getSession(session.getId()).state() == XiaozhiVoiceSession.State.IDLE,
                Duration.ofMillis(200)
        )).isFalse();
        assertThat(serviceWithStreamingAsr.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.LISTENING);
    }

    @Test
    void shouldCancelPreviousStreamingPlaybackWhenNewListenStartArrives() throws Exception {
        var serviceWithStreamingAsr = newService(
                new RecordingSpeechToTextClient(),
                new StreamingHermesClient("旧回合回答。"),
                new MultiFrameTextToSpeechClient(),
                new XiaozhiAsrMode("streaming"),
                new EndAwareStreamingSpeechToTextClient()
        );
        var session = new BinarySendBlockingSession("ws-session-1");
        serviceWithStreamingAsr.open(session);
        serviceWithStreamingAsr.handleHello(session, new XiaozhiClientHello(
                "hello",
                1,
                Map.of("mcp", true),
                "websocket",
                XiaozhiAudioParams.defaults()
        ));

        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", null, null, null, "ws-session-1", null
        ));
        assertThat(session.awaitFirstBinarySend()).isTrue();

        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        session.releaseFirstBinarySend();

        assertThat(awaitCondition(
                () -> session.binaryMessageCount() >= 2,
                Duration.ofMillis(200)
        )).isFalse();
        assertThat(serviceWithStreamingAsr.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.LISTENING);
    }

    @Test
    void shouldNotLetPreviousStreamingTurnContinuePlaybackAfterReplacement() throws Exception {
        var serviceWithStreamingAsr = newService(
                new RecordingSpeechToTextClient(),
                new StreamingHermesClient("旧回合回答。"),
                new FakeTextToSpeechClient(),
                new XiaozhiAsrMode("streaming"),
                new EndAwareStreamingSpeechToTextClient()
        );
        var session = new TextSendBlockingSession("ws-session-1", "\"type\":\"llm\"");
        serviceWithStreamingAsr.open(session);
        serviceWithStreamingAsr.handleHello(session, new XiaozhiClientHello(
                "hello",
                1,
                Map.of("mcp", true),
                "websocket",
                XiaozhiAudioParams.defaults()
        ));

        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", null, null, null, "ws-session-1", null
        ));
        assertThat(session.awaitBlockedTextSend()).isTrue();

        var replacementThread = Thread.startVirtualThread(() -> serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        )));
        session.releaseTextSend();
        join(replacementThread);

        assertThat(awaitCondition(
                () -> hasTextMessageContaining(session, "\"type\":\"tts\""),
                Duration.ofMillis(200)
        )).isFalse();
        assertThat(serviceWithStreamingAsr.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.LISTENING);
    }

    @Test
    void shouldNotSendPreviousStreamingSttWhenTurnIsReplacedBeforeSend() throws Exception {
        var eventFactory = new SttBlockingEventFactory();
        var serviceWithStreamingAsr = newService(
                new RecordingSpeechToTextClient(),
                new FakeHermesClient(),
                new FakeTextToSpeechClient(),
                new XiaozhiAsrMode("streaming"),
                new EndAwareStreamingSpeechToTextClient(),
                eventFactory
        );
        var session = new SttSendCountingSession("ws-session-1");
        serviceWithStreamingAsr.open(session);
        serviceWithStreamingAsr.handleHello(session, new XiaozhiClientHello(
                "hello",
                1,
                Map.of("mcp", true),
                "websocket",
                XiaozhiAudioParams.defaults()
        ));

        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", null, null, null, "ws-session-1", null
        ));
        assertThat(eventFactory.awaitSttPayloadRequested()).isTrue();

        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        eventFactory.releaseSttPayload();

        awaitCondition(() -> session.sttSendCount() > 0, Duration.ofMillis(200));

        assertThat(session.sttSendCount()).isZero();
        assertThat(serviceWithStreamingAsr.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.LISTENING);
    }

    @Test
    void shouldKeepStreamingTurnConversationWhenSessionNewArrivesBeforeHermesCall() throws Exception {
        var hermesClient = new RecordingHermesClient();
        var streamingSpeech = new ReleaseAfterEndStreamingSpeechToTextClient();
        var serviceWithStreamingAsr = newService(
                new RecordingSpeechToTextClient(),
                hermesClient,
                new FakeTextToSpeechClient(),
                new XiaozhiAsrMode("streaming"),
                streamingSpeech
        );
        var session = new SttSendBlockingSession("ws-session-1");
        serviceWithStreamingAsr.open(session);
        serviceWithStreamingAsr.handleHello(session, new XiaozhiClientHello(
                "hello",
                1,
                Map.of("mcp", true),
                "websocket",
                XiaozhiAudioParams.defaults()
        ));
        var originalConversationId = serviceWithStreamingAsr.getSession(session.getId()).conversationId();

        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", null, null, null, "ws-session-1", null
        ));
        assertThat(streamingSpeech.awaitEnd()).isTrue();
        streamingSpeech.releaseResult();
        assertThat(session.awaitSttSend()).isTrue();

        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "session", "new", null, null, null, "ws-session-1", null
        ));
        var newConversationId = serviceWithStreamingAsr.getSession(session.getId()).conversationId();
        session.releaseSttSend();

        awaitCondition(() -> serviceWithStreamingAsr.getSession(session.getId()).state() == XiaozhiVoiceSession.State.IDLE,
                Duration.ofMillis(200));
        assertThat(hermesClient.conversationIds()).doesNotContain(newConversationId);
        if (!hermesClient.conversationIds().isEmpty()) {
            assertThat(hermesClient.conversationIds()).containsExactly(originalConversationId);
        }
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
                                    "asrProvider=sentence",
                                    "asrMillis=",
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
        var serviceWithStreamingHermes = newService(
                new FakeSpeechToTextClient(),
                new StreamingHermesClient("第一句内容很完整。", "第二句内容也完整。"),
                ttsClient,
                new XiaozhiAsrMode("sentence"),
                new FakeStreamingSpeechToTextClient()
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
        var serviceWithStreamingHermes = newService(
                new FakeSpeechToTextClient(),
                new StreamingHermesClient("第一句内容很完整。", "第二句内容也完整。"),
                ttsClient,
                new XiaozhiAsrMode("sentence"),
                new FakeStreamingSpeechToTextClient()
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
        var serviceWithCapturingHermes = newService(
                new FakeSpeechToTextClient(),
                hermesClient,
                new FakeTextToSpeechClient(),
                new XiaozhiAsrMode("sentence"),
                new FakeStreamingSpeechToTextClient()
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
        var serviceWithRecordingHermes = newService(
                new FakeSpeechToTextClient(),
                hermesClient,
                new FakeTextToSpeechClient(),
                new XiaozhiAsrMode("sentence"),
                new FakeStreamingSpeechToTextClient()
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
        var serviceWithBlankAsr = newService(
                audioFrames -> " ",
                hermesClient,
                new FakeTextToSpeechClient(),
                new XiaozhiAsrMode("sentence"),
                new FakeStreamingSpeechToTextClient()
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
        var serviceWithFailingHermes = newService(
                new FakeSpeechToTextClient(),
                new FailingHermesClient(),
                new FakeTextToSpeechClient(),
                new XiaozhiAsrMode("sentence"),
                new FakeStreamingSpeechToTextClient()
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
    void shouldNotLetPreviousHermesFailureOverwriteNewListenStart() throws Exception {
        var serviceWithFailingHermes = newService(
                new FakeSpeechToTextClient(),
                new FailingHermesClient(),
                new FakeTextToSpeechClient(),
                new XiaozhiAsrMode("sentence"),
                new FakeStreamingSpeechToTextClient()
        );
        var session = new TtsStopBlockingSession("ws-session-1");
        serviceWithFailingHermes.open(session);
        serviceWithFailingHermes.handleHello(session, new XiaozhiClientHello(
                "hello",
                1,
                Map.of("mcp", true),
                "websocket",
                XiaozhiAudioParams.defaults()
        ));
        var turnThread = Thread.startVirtualThread(() -> runSingleTurn(serviceWithFailingHermes, session));
        assertThat(session.awaitTtsStopSend()).isTrue();

        serviceWithFailingHermes.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        session.releaseTtsStopSend();
        join(turnThread);

        assertThat(hasTextMessageContaining(session, "\"code\":\"hermes_failed\"")).isFalse();
        assertThat(serviceWithFailingHermes.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.LISTENING);
    }

    @Test
    void shouldSendTtsStopWhenSynthesizedAudioIsEmpty() {
        var serviceWithEmptyTts = newService(
                new FakeSpeechToTextClient(),
                new FakeHermesClient(),
                new EmptyTextToSpeechClient(),
                new XiaozhiAsrMode("sentence"),
                new FakeStreamingSpeechToTextClient()
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
        var serviceWithFailingTts = newService(
                new FakeSpeechToTextClient(),
                new FakeHermesClient(),
                new FailingTextToSpeechClient(),
                new XiaozhiAsrMode("sentence"),
                new FakeStreamingSpeechToTextClient()
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
        var serviceWithRecordingSpeech = newService(
                recordingSpeech,
                new FakeHermesClient(),
                new FakeTextToSpeechClient(),
                new XiaozhiAsrMode("sentence"),
                new FakeStreamingSpeechToTextClient()
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
        var serviceWithBridge = newService(
                new FakeSpeechToTextClient(),
                new FakeHermesClient(),
                new FakeTextToSpeechClient(),
                new XiaozhiAsrMode("sentence"),
                new FakeStreamingSpeechToTextClient(),
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

    @Test
    void shouldNotifyOnlineDeviceWithReminderSpeech() {
        var headers = new HttpHeaders();
        headers.set("Device-Id", "device-1");
        var session = new TestWebSocketSession("ws-session-1", URI.create("ws://127.0.0.1/xiaozhi/v1"), headers);
        service.open(session);
        service.handleHello(session, new XiaozhiClientHello(
                "hello",
                1,
                Map.of("mcp", true),
                "websocket",
                XiaozhiAudioParams.defaults()
        ));

        var notified = service.notifyDevice("device-1", "提醒时间到了");

        assertThat(notified).isTrue();
        assertThat(service.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"llm\"", "\"emotion\":\"neutral\""))
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"tts\"", "\"state\":\"start\""))
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"tts\"", "\"state\":\"sentence_start\"", "\"text\":\"提醒时间到了\""))
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"tts\"", "\"state\":\"stop\""));
        assertThat(session.getSentMessages())
                .anySatisfy(message -> assertThat(message).isInstanceOf(BinaryMessage.class));
    }

    @Test
    void shouldReturnFalseWhenReminderDeviceIsOffline() {
        var notified = service.notifyDevice("missing-device", "提醒时间到了");

        assertThat(notified).isFalse();
    }

    @Test
    void shouldReturnFalseWhenReminderDeviceIsBusy() {
        var session = openSession();
        service.handleText(session, new XiaozhiClientMessage("listen", "start", "manual", null, null, null, null));

        var notified = service.notifyDevice("ws-session-1", "提醒时间到了");

        assertThat(notified).isFalse();
        assertThat(service.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.LISTENING);
    }

    @Test
    void shouldReturnFalseWhenReminderSpeechFails() {
        var serviceWithFailingTts = newService(new FailingTextToSpeechClient());
        var session = openSession(serviceWithFailingTts);

        var notified = serviceWithFailingTts.notifyDevice("ws-session-1", "提醒时间到了");

        assertThat(notified).isFalse();
        assertThat(serviceWithFailingTts.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
    }

    @Test
    void shouldNotLetPreviousNotificationTtsFailureOverwriteNewListenStart() throws Exception {
        var serviceWithFailingTts = newService(new FailingTextToSpeechClient());
        var session = new TtsStopBlockingSession("ws-session-1");
        serviceWithFailingTts.open(session);
        serviceWithFailingTts.handleHello(session, new XiaozhiClientHello(
                "hello",
                1,
                Map.of("mcp", true),
                "websocket",
                XiaozhiAudioParams.defaults()
        ));
        var notifyThread = Thread.startVirtualThread(() -> serviceWithFailingTts.notifyDevice("ws-session-1", "提醒时间到了"));
        assertThat(session.awaitTtsStopSend()).isTrue();

        serviceWithFailingTts.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        session.releaseTtsStopSend();
        join(notifyThread);

        assertThat(hasTextMessageContaining(session, "\"code\":\"tts_failed\"")).isFalse();
        assertThat(serviceWithFailingTts.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.LISTENING);
    }

    @Test
    void shouldNotCreateReminderFromLocalTextParsingWhenHermesDoesNotReturnAction() {
        var eventPublisher = new RecordingApplicationEventPublisher();
        var serviceWithReminderIntent = newService(
                new FixedSpeechToTextClient("一分钟后提醒我喝水"),
                new StreamingHermesClient("好的，我记下了。"),
                new FakeTextToSpeechClient(),
                new XiaozhiAsrMode("sentence"),
                new FakeStreamingSpeechToTextClient()
        );
        serviceWithReminderIntent.setApplicationEventPublisher(eventPublisher);
        var session = openSession(serviceWithReminderIntent);

        runSingleTurn(serviceWithReminderIntent, session);

        assertThat(eventPublisher.events()).isEmpty();
        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .anySatisfy(payload -> assertThat(payload)
                        .contains("\"type\":\"tts\"", "\"state\":\"sentence_start\"", "好的，我记下了。"));
    }

    @Test
    void shouldCreateReminderOnlyFromHermesAgentEvent() {
        var eventPublisher = new RecordingApplicationEventPublisher();
        var serviceWithReminderEvent = newService(
                new FixedSpeechToTextClient("帮我记一下喝水"),
                new RawStreamingHermesClient(
                        """
                                event: xiaozhi.agent_event
                                data: {"action":"create_reminder","message":"喝水","delay_seconds":60,"confirmation_text":"1分钟后提醒你喝水"}

                                """,
                        """
                                event: response.output_text.delta
                                data: {"delta":"1分钟后提醒你喝水。"}

                                """
                ),
                new FakeTextToSpeechClient(),
                new XiaozhiAsrMode("sentence"),
                new FakeStreamingSpeechToTextClient()
        );
        serviceWithReminderEvent.setApplicationEventPublisher(eventPublisher);
        var session = openSession(serviceWithReminderEvent);

        runSingleTurn(serviceWithReminderEvent, session);

        assertThat(eventPublisher.events())
                .singleElement()
                .isInstanceOfSatisfying(XiaozhiReminderRequestedEvent.class, event -> {
                    assertThat(event.deviceId()).isEqualTo("ws-session-1");
                    assertThat(event.message()).isEqualTo("喝水");
                    assertThat(event.delaySeconds()).isEqualTo(60L);
                });
        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .anySatisfy(payload -> assertThat(payload)
                        .contains("\"type\":\"tts\"", "\"state\":\"sentence_start\"", "1分钟后提醒你喝水。"));
    }

    @Test
    void shouldSpeakReminderConfirmationFromCurrentHermesPlaybackWhenEventHasNoTextDelta() {
        var eventPublisher = new RecordingApplicationEventPublisher();
        var serviceWithReminderEvent = newService(
                new FixedSpeechToTextClient("帮我记一下喝水"),
                new RawStreamingHermesClient("""
                        event: xiaozhi.agent_event
                        data: {"action":"create_reminder","message":"喝水","delay_seconds":60,"confirmation_text":"1分钟后提醒你喝水"}

                        """),
                new FakeTextToSpeechClient(),
                new XiaozhiAsrMode("sentence"),
                new FakeStreamingSpeechToTextClient()
        );
        serviceWithReminderEvent.setApplicationEventPublisher(eventPublisher);
        var session = openSession(serviceWithReminderEvent);

        runSingleTurn(serviceWithReminderEvent, session);

        assertThat(eventPublisher.events())
                .singleElement()
                .isInstanceOf(XiaozhiReminderRequestedEvent.class);
        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .filteredOn(payload -> payload.contains("\"type\":\"tts\""))
                .anySatisfy(payload -> assertThat(payload)
                        .contains("\"state\":\"sentence_start\"", "1分钟后提醒你喝水"))
                .anySatisfy(payload -> assertThat(payload).contains("\"state\":\"stop\""));
        assertThat(serviceWithReminderEvent.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
    }

    private TestWebSocketSession openSession() {
        return openSession(service);
    }

    private XiaozhiVoiceSessionService newService(TextToSpeechClient textToSpeechClient) {
        return newService(
                new FakeSpeechToTextClient(),
                new FakeHermesClient(),
                textToSpeechClient,
                new XiaozhiAsrMode("sentence"),
                new FakeStreamingSpeechToTextClient()
        );
    }

    private XiaozhiVoiceSessionService newService(
            SpeechToTextClient speechToTextClient,
            HermesClient hermesClient,
            TextToSpeechClient textToSpeechClient,
            XiaozhiAsrMode asrMode,
            StreamingSpeechToTextClient streamingSpeechToTextClient
    ) {
        return newService(speechToTextClient, hermesClient, textToSpeechClient, asrMode, streamingSpeechToTextClient, newMcpBridge());
    }

    private XiaozhiVoiceSessionService newService(
            SpeechToTextClient speechToTextClient,
            HermesClient hermesClient,
            TextToSpeechClient textToSpeechClient,
            XiaozhiAsrMode asrMode,
            StreamingSpeechToTextClient streamingSpeechToTextClient,
            XiaozhiMcpBridge mcpBridge
    ) {
        return new XiaozhiVoiceSessionService(
                codec,
                speechToTextClient,
                hermesClient,
                textToSpeechClient,
                new XiaozhiServerEventFactory(new ObjectMapper()),
                new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner"),
                new XiaozhiVoiceTokenAuth(""),
                mcpBridge,
                asrMode,
                streamingSpeechToTextClient,
                XiaozhiAudioParams.defaults()
        );
    }

    private XiaozhiVoiceSessionService newService(
            SpeechToTextClient speechToTextClient,
            HermesClient hermesClient,
            TextToSpeechClient textToSpeechClient,
            XiaozhiAsrMode asrMode,
            StreamingSpeechToTextClient streamingSpeechToTextClient,
            XiaozhiServerEventFactory eventFactory
    ) {
        return new XiaozhiVoiceSessionService(
                codec,
                speechToTextClient,
                hermesClient,
                textToSpeechClient,
                eventFactory,
                new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner"),
                new XiaozhiVoiceTokenAuth(""),
                new XiaozhiMcpBridge(eventFactory),
                asrMode,
                streamingSpeechToTextClient,
                XiaozhiAudioParams.defaults()
        );
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

        private final java.util.List<String> conversationIds = new java.util.concurrent.CopyOnWriteArrayList<>();

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

    private static class RawStreamingHermesClient implements HermesClient {

        private final List<String> chunks;

        private RawStreamingHermesClient(String... chunks) {
            this.chunks = List.of(chunks);
        }

        @Override
        public HermesResponse chat(HermesRequest request, HermesClientConfig config) {
            return new HermesResponse(request.conversationId(), String.join("", chunks));
        }

        @Override
        public Stream<String> streamChat(HermesRequest request, HermesClientConfig config) {
            return chunks.stream();
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

    private static class MultiFrameTextToSpeechClient implements TextToSpeechClient {

        @Override
        public List<ByteBuffer> synthesize(String text, VoiceId voiceId) {
            return List.of(
                    ByteBuffer.wrap(new byte[] {1}),
                    ByteBuffer.wrap(new byte[] {2})
            );
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

    private record FixedSpeechToTextClient(String text) implements SpeechToTextClient {

        @Override
        public String transcribe(List<ByteBuffer> audioFrames) {
            return text;
        }
    }

    private static class RecordingApplicationEventPublisher implements ApplicationEventPublisher {

        private final ArrayList<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }

        private List<Object> events() {
            return List.copyOf(events);
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

    private static class BinarySendBlockingSession extends TestWebSocketSession {

        private final CountDownLatch firstBinarySendStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirstBinarySend = new CountDownLatch(1);
        private final AtomicInteger binaryMessageCount = new AtomicInteger();

        BinarySendBlockingSession(String id) {
            super(id);
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            if (message instanceof BinaryMessage) {
                var currentCount = binaryMessageCount.incrementAndGet();
                if (currentCount == 1) {
                    firstBinarySendStarted.countDown();
                    await(releaseFirstBinarySend);
                }
            }
            super.sendMessage(message);
        }

        private boolean awaitFirstBinarySend() throws InterruptedException {
            return firstBinarySendStarted.await(1, TimeUnit.SECONDS);
        }

        private void releaseFirstBinarySend() {
            releaseFirstBinarySend.countDown();
        }

        private int binaryMessageCount() {
            return binaryMessageCount.get();
        }
    }

    private static class TextSendBlockingSession extends TestWebSocketSession {

        private final String blockedPayloadPart;
        private final CountDownLatch blockedTextSendStarted = new CountDownLatch(1);
        private final CountDownLatch releaseTextSend = new CountDownLatch(1);

        TextSendBlockingSession(String id, String blockedPayloadPart) {
            super(id);
            this.blockedPayloadPart = blockedPayloadPart;
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            if (message instanceof TextMessage textMessage
                    && textMessage.getPayload().contains(blockedPayloadPart)) {
                blockedTextSendStarted.countDown();
                await(releaseTextSend);
            }
            super.sendMessage(message);
        }

        private boolean awaitBlockedTextSend() throws InterruptedException {
            return blockedTextSendStarted.await(1, TimeUnit.SECONDS);
        }

        private void releaseTextSend() {
            releaseTextSend.countDown();
        }
    }

    private static class SttSendBlockingSession extends TestWebSocketSession {

        private final CountDownLatch sttSendStarted = new CountDownLatch(1);
        private final CountDownLatch releaseSttSend = new CountDownLatch(1);

        SttSendBlockingSession(String id) {
            super(id);
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            if (message instanceof TextMessage textMessage
                    && textMessage.getPayload().contains("\"type\":\"stt\"")) {
                sttSendStarted.countDown();
                await(releaseSttSend);
            }
            super.sendMessage(message);
        }

        private boolean awaitSttSend() throws InterruptedException {
            return sttSendStarted.await(1, TimeUnit.SECONDS);
        }

        private void releaseSttSend() {
            releaseSttSend.countDown();
        }
    }

    private static class SttSendCountingSession extends TestWebSocketSession {

        private final AtomicInteger sttSendCount = new AtomicInteger();

        SttSendCountingSession(String id) {
            super(id);
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            if (message instanceof TextMessage textMessage
                    && textMessage.getPayload().contains("\"type\":\"stt\"")) {
                sttSendCount.incrementAndGet();
            }
            super.sendMessage(message);
        }

        private int sttSendCount() {
            return sttSendCount.get();
        }
    }

    private static class TtsStopBlockingSession extends TestWebSocketSession {

        private final CountDownLatch ttsStopSendStarted = new CountDownLatch(1);
        private final CountDownLatch releaseTtsStopSend = new CountDownLatch(1);

        TtsStopBlockingSession(String id) {
            super(id);
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            if (message instanceof TextMessage textMessage
                    && textMessage.getPayload().contains("\"type\":\"tts\"")
                    && textMessage.getPayload().contains("\"state\":\"stop\"")) {
                ttsStopSendStarted.countDown();
                await(releaseTtsStopSend);
            }
            super.sendMessage(message);
        }

        private boolean awaitTtsStopSend() throws InterruptedException {
            return ttsStopSendStarted.await(1, TimeUnit.SECONDS);
        }

        private void releaseTtsStopSend() {
            releaseTtsStopSend.countDown();
        }
    }

    private static class RecordingSpeechToTextClient implements SpeechToTextClient {

        private List<List<Integer>> audioFramePayloads = List.of();
        private int callCount;

        @Override
        public String transcribe(List<ByteBuffer> audioFrames) {
            callCount++;
            audioFramePayloads = audioFrames.stream()
                    .map(RecordingSpeechToTextClient::toUnsignedBytes)
                    .toList();
            return "ping";
        }

        private int callCount() {
            return callCount;
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

    private static class CapturingStreamingSpeechToTextClient implements StreamingSpeechToTextClient {

        private final AtomicInteger chunkCount = new AtomicInteger();

        @Override
        public SpeechToTextResult transcribe(SpeechToTextAudioStream audioStream) {
            while (true) {
                var chunk = audioStream.take(Duration.ofMillis(100));
                if (audioStream.isEnd(chunk)) {
                    return new SpeechToTextResult("ping", "test", 0);
                }
                if (chunk.length > 0) {
                    chunkCount.incrementAndGet();
                }
            }
        }

        private int chunkCount() {
            return chunkCount.get();
        }

        private boolean awaitChunkCountAtLeast(int expected) {
            var deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            while (System.nanoTime() < deadline) {
                if (chunkCount.get() >= expected) {
                    return true;
                }
                Thread.onSpinWait();
            }
            return chunkCount.get() >= expected;
        }
    }

    private static class TimeoutStreamingSpeechToTextClient implements StreamingSpeechToTextClient {

        private final AtomicInteger chunkCount = new AtomicInteger();
        private final CountDownLatch firstCallFinished = new CountDownLatch(1);
        private final AtomicInteger callCount = new AtomicInteger();

        @Override
        public SpeechToTextResult transcribe(SpeechToTextAudioStream audioStream) {
            var currentCall = callCount.incrementAndGet();
            var consecutiveTimeouts = 0;
            while (true) {
                var chunk = audioStream.take(Duration.ofMillis(100));
                if (audioStream.isEnd(chunk)) {
                    finishFirstCall(currentCall);
                    return new SpeechToTextResult("ping", "test", 0);
                }
                if (chunk.length == 0) {
                    consecutiveTimeouts++;
                    if (consecutiveTimeouts >= 2) {
                        finishFirstCall(currentCall);
                        return new SpeechToTextResult("ping", "test", 0);
                    }
                    continue;
                }
                chunkCount.incrementAndGet();
                consecutiveTimeouts = 0;
            }
        }

        private boolean awaitFinished() throws InterruptedException {
            return firstCallFinished.await(1, TimeUnit.SECONDS);
        }

        private int chunkCount() {
            return chunkCount.get();
        }

        private void finishFirstCall(int currentCall) {
            if (currentCall == 1) {
                firstCallFinished.countDown();
            }
        }
    }

    private static class EndAwareStreamingSpeechToTextClient implements StreamingSpeechToTextClient {

        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch endReceived = new CountDownLatch(1);

        @Override
        public SpeechToTextResult transcribe(SpeechToTextAudioStream audioStream) {
            started.countDown();
            while (true) {
                var chunk = audioStream.take(Duration.ofMillis(100));
                if (audioStream.isEnd(chunk)) {
                    endReceived.countDown();
                    return new SpeechToTextResult("ping", "test", 0);
                }
            }
        }

        private boolean awaitStarted() throws InterruptedException {
            return started.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitEnd() throws InterruptedException {
            return endReceived.await(1, TimeUnit.SECONDS);
        }
    }

    private static class ReplacedTurnFailingStreamingSpeechToTextClient implements StreamingSpeechToTextClient {

        private final AtomicInteger callCount = new AtomicInteger();
        private final AtomicInteger secondChunkCount = new AtomicInteger();
        private final CountDownLatch firstCallStarted = new CountDownLatch(1);
        private final CountDownLatch secondCallStarted = new CountDownLatch(1);
        private final CountDownLatch oldTurnReadyToFail = new CountDownLatch(1);
        private final CountDownLatch allowOldFailure = new CountDownLatch(1);

        @Override
        public SpeechToTextResult transcribe(SpeechToTextAudioStream audioStream) {
            var currentCall = callCount.incrementAndGet();
            if (currentCall == 1) {
                return failAfterReplacement(audioStream);
            }
            return captureSecondTurn(audioStream);
        }

        private SpeechToTextResult failAfterReplacement(SpeechToTextAudioStream audioStream) {
            firstCallStarted.countDown();
            while (true) {
                var chunk = audioStream.take(Duration.ofMillis(100));
                if (audioStream.isEnd(chunk)) {
                    oldTurnReadyToFail.countDown();
                    await(allowOldFailure);
                    throw new IllegalStateException("old streaming turn failed after replacement");
                }
            }
        }

        private SpeechToTextResult captureSecondTurn(SpeechToTextAudioStream audioStream) {
            secondCallStarted.countDown();
            while (true) {
                var chunk = audioStream.take(Duration.ofMillis(100));
                if (audioStream.isEnd(chunk)) {
                    return new SpeechToTextResult("ping", "test", 0);
                }
                if (chunk.length > 0) {
                    secondChunkCount.incrementAndGet();
                }
            }
        }

        private boolean awaitFirstCallStarted() throws InterruptedException {
            return firstCallStarted.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitSecondCallStarted() throws InterruptedException {
            return secondCallStarted.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitOldTurnReadyToFail() throws InterruptedException {
            return oldTurnReadyToFail.await(1, TimeUnit.SECONDS);
        }

        private void releaseOldFailure() {
            allowOldFailure.countDown();
        }

        private boolean awaitSecondChunkCountAtLeast(int expected) {
            var deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            while (System.nanoTime() < deadline) {
                if (secondChunkCount.get() >= expected) {
                    return true;
                }
                Thread.onSpinWait();
            }
            return secondChunkCount.get() >= expected;
        }
    }

    private static class ReleaseAfterEndStreamingSpeechToTextClient implements StreamingSpeechToTextClient {

        private final CountDownLatch endReceived = new CountDownLatch(1);
        private final CountDownLatch releaseResult = new CountDownLatch(1);

        @Override
        public SpeechToTextResult transcribe(SpeechToTextAudioStream audioStream) {
            while (true) {
                var chunk = audioStream.take(Duration.ofMillis(100));
                if (audioStream.isEnd(chunk)) {
                    endReceived.countDown();
                    await(releaseResult);
                    return new SpeechToTextResult("ping", "test", 0);
                }
            }
        }

        private boolean awaitEnd() throws InterruptedException {
            return endReceived.await(1, TimeUnit.SECONDS);
        }

        private void releaseResult() {
            releaseResult.countDown();
        }
    }

    private static class ImmediateStreamingSpeechToTextClient implements StreamingSpeechToTextClient {

        @Override
        public SpeechToTextResult transcribe(SpeechToTextAudioStream audioStream) {
            return new SpeechToTextResult("ping", "test", 0);
        }
    }

    private static class SttBlockingEventFactory extends XiaozhiServerEventFactory {

        private final CountDownLatch sttPayloadRequested = new CountDownLatch(1);
        private final CountDownLatch releaseSttPayload = new CountDownLatch(1);

        SttBlockingEventFactory() {
            super(new ObjectMapper());
        }

        @Override
        public String stt(String sessionId, String text) {
            sttPayloadRequested.countDown();
            await(releaseSttPayload);
            return super.stt(sessionId, text);
        }

        private boolean awaitSttPayloadRequested() throws InterruptedException {
            return sttPayloadRequested.await(1, TimeUnit.SECONDS);
        }

        private void releaseSttPayload() {
            releaseSttPayload.countDown();
        }
    }

    private static byte[] encodeOpusFrame() {
        try {
            var sampleRate = 16_000;
            var pcm = new short[sampleRate * 60 / 1000];
            Arrays.fill(pcm, (short) 1000);
            var output = new byte[1024];
            var encoder = new OpusEncoder(sampleRate, 1, OpusApplication.OPUS_APPLICATION_VOIP);
            var encodedBytes = encoder.encode(pcm, 0, pcm.length, output, 0, output.length);
            return Arrays.copyOf(output, encodedBytes);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to encode Opus test frame", exception);
        }
    }

    private static boolean awaitIdle(XiaozhiVoiceSessionService service, TestWebSocketSession session) {
        var deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (System.nanoTime() < deadline) {
            if (service.getSession(session.getId()).state() == XiaozhiVoiceSession.State.IDLE) {
                return true;
            }
            Thread.onSpinWait();
        }
        return service.getSession(session.getId()).state() == XiaozhiVoiceSession.State.IDLE;
    }

    private static boolean awaitCondition(BooleanSupplier condition, Duration timeout) {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.onSpinWait();
        }
        return condition.getAsBoolean();
    }

    private static boolean hasTextMessageContaining(TestWebSocketSession session, String expected) {
        return session.getSentMessages().stream()
                .filter(TextMessage.class::isInstance)
                .map(message -> ((TextMessage) message).getPayload())
                .anyMatch(payload -> payload.contains(expected));
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
