package com.jzb.chatbot.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.jzb.chatbot.speech.StreamingTextToSpeechClient;
import com.jzb.chatbot.speech.StreamingTextToSpeechListener;
import com.jzb.chatbot.speech.StreamingTextToSpeechSession;
import com.jzb.chatbot.speech.TextToSpeechClient;
import com.jzb.chatbot.speech.TextToSpeechOptions;
import com.jzb.chatbot.voice.bargein.XiaozhiBargeInDetector;
import com.jzb.chatbot.voice.bargein.XiaozhiBargeInProperties;
import com.jzb.chatbot.voice.mcp.XiaozhiMcpBridge;
import com.jzb.chatbot.voice.music.XiaozhiMusicActionHandler;
import com.jzb.chatbot.voice.music.XiaozhiMusicPlaybackRequest;
import com.jzb.chatbot.voice.music.XiaozhiMusicPlaybackRuntime;
import com.jzb.chatbot.voice.protocol.XiaozhiAudioParams;
import com.jzb.chatbot.voice.protocol.XiaozhiClientHello;
import com.jzb.chatbot.voice.protocol.XiaozhiClientMessage;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import com.jzb.chatbot.voice.protocol.XiaozhiServerEventFactory;
import com.jzb.chatbot.voice.reminder.XiaozhiReminderRequestedEvent;
import com.jzb.chatbot.voice.tts.XiaozhiTtsRequest;
import com.jzb.chatbot.voice.tts.XiaozhiTtsResult;
import com.jzb.chatbot.voice.tts.XiaozhiTtsRuntime;
import com.jzb.chatbot.voice.tts.XiaozhiVoiceProfileResolver;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final XiaozhiVoiceSessionService service = newService();

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
        service.handleBinary(session, ByteBuffer.wrap(new byte[] {1, 2, 3}));

        service.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", null, null, null, "ws-session-1", null
        ));

        assertThat(service.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
        assertThat(session.getSentMessages()).isNotEmpty();
    }

    @Test
    void shouldEndSilentlyWhenListenStopHasNoAudioFrames() {
        var speechToTextClient = new CountingSpeechToTextClient("ping");
        var serviceWithCountingAsr = newService(speechToTextClient, new FakeHermesClient(), new FakeTextToSpeechClient());
        var session = openSession(serviceWithCountingAsr);
        serviceWithCountingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "auto", null, null, "ws-session-1", null
        ));

        serviceWithCountingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", null, null, null, "ws-session-1", null
        ));

        assertThat(speechToTextClient.calls()).isZero();
        assertThat(serviceWithCountingAsr.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .noneSatisfy(payload -> assertThat(payload)
                        .contains("\"type\":\"tts\"", "\"state\":\"sentence_start\""));
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
    void shouldStartBargeInTurnWhenListenStartBargeInDuringSpeaking() {
        var serviceWithBargeIn = newService(
                new FakeSpeechToTextClient(),
                new FakeHermesClient(),
                new FakeTextToSpeechClient(),
                new XiaozhiAsrMode("streaming"),
                new ImmediateStreamingSpeechToTextClient("", "streaming-provider"),
                new XiaozhiBargeInDetector(new XiaozhiBargeInProperties(true, 2, 500, 0.82, Duration.ofMillis(200)))
        );
        var session = openSession(serviceWithBargeIn);
        serviceWithBargeIn.getSession(session.getId()).markSpeaking();

        serviceWithBargeIn.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "barge_in", null, null, "ws-session-1", null
        ));

        assertThat(serviceWithBargeIn.getSession(session.getId()).activeBargeInTurn()).isNotNull();
    }

    @Test
    void shouldIgnoreBargeInStartWhenDisabled() {
        var serviceWithDisabledBargeIn = newService(
                new FakeSpeechToTextClient(),
                new FakeHermesClient(),
                new FakeTextToSpeechClient(),
                new XiaozhiAsrMode("streaming"),
                new ImmediateStreamingSpeechToTextClient("", "streaming-provider"),
                new XiaozhiBargeInDetector(new XiaozhiBargeInProperties(false, 2, 500, 0.82, Duration.ofMillis(200)))
        );
        var session = openSession(serviceWithDisabledBargeIn);
        serviceWithDisabledBargeIn.getSession(session.getId()).markSpeaking();

        serviceWithDisabledBargeIn.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "barge_in", null, null, "ws-session-1", null
        ));

        assertThat(serviceWithDisabledBargeIn.getSession(session.getId()).activeBargeInTurn()).isNull();
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
    void shouldAcceptBinaryForActiveBargeInTurnWhileSpeaking() {
        var streamingSpeech = new RecordingBargeInStreamingSpeechToTextClient();
        var serviceWithStreamingAsr = newService(
                new FakeSpeechToTextClient(),
                new FakeHermesClient(),
                new FakeTextToSpeechClient(),
                new XiaozhiAsrMode("streaming"),
                streamingSpeech,
                new XiaozhiBargeInDetector(new XiaozhiBargeInProperties(true, 2, 0, 0.82, Duration.ofMillis(200)))
        );
        var session = openSession(serviceWithStreamingAsr);

        serviceWithStreamingAsr.getSession(session.getId()).markSpeaking();
        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "barge_in", null, null, "ws-session-1", null
        ));
        serviceWithStreamingAsr.handleBinary(session, ByteBuffer.wrap(new byte[] {(byte) 0xf8, (byte) 0xff, (byte) 0xfe}));
        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", "barge_in", null, null, "ws-session-1", null
        ));

        assertThat(streamingSpeech.callCount()).isEqualTo(1);
        assertThat(streamingSpeech.awaitAudioChunk()).isTrue();
        assertThat(streamingSpeech.pcmBytes()).isPositive();
    }

    @Test
    void shouldCancelTtsAndEnterListeningWhenBargeInDetected() {
        var eventFactory = new XiaozhiServerEventFactory(new ObjectMapper());
        var ttsRuntime = new RecordingCancelTtsRuntime(new FakeTextToSpeechClient(), codec, eventFactory);
        var streamingSpeech = new ImmediateStreamingSpeechToTextClient("等一下", "streaming-provider");
        var serviceWithBargeIn = newService(
                new FakeSpeechToTextClient(),
                new FakeHermesClient(),
                ttsRuntime,
                eventFactory,
                new XiaozhiAsrMode("streaming"),
                streamingSpeech,
                new XiaozhiBargeInDetector(new XiaozhiBargeInProperties(true, 2, 0, 0.82, Duration.ofMillis(200)))
        );
        var session = openSession(serviceWithBargeIn);
        var voiceSession = serviceWithBargeIn.getSession(session.getId());
        voiceSession.markSpeaking();
        voiceSession.updateCurrentSpeakingText("这是一段很长的回答。");

        serviceWithBargeIn.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "barge_in", null, null, "ws-session-1", null
        ));
        serviceWithBargeIn.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", "barge_in", null, null, "ws-session-1", null
        ));

        assertThat(ttsRuntime.awaitCancelled()).isTrue();
        assertThat(serviceWithBargeIn.getSession(session.getId()).state())
                .isEqualTo(XiaozhiVoiceSession.State.LISTENING);
    }

    @Test
    void shouldKeepListeningWhenBargeInCancelRacesWithPlaybackCompletion() {
        var eventFactory = new XiaozhiServerEventFactory(new ObjectMapper());
        var ttsRuntime = new InterleavingCancelTtsRuntime(new FakeTextToSpeechClient(), codec, eventFactory);
        var streamingSpeech = new ImmediateStreamingSpeechToTextClient("等一下", "streaming-provider");
        var serviceWithBargeIn = newService(
                new FakeSpeechToTextClient(),
                new FakeHermesClient(),
                ttsRuntime,
                eventFactory,
                new XiaozhiAsrMode("streaming"),
                streamingSpeech,
                new XiaozhiBargeInDetector(new XiaozhiBargeInProperties(true, 2, 0, 0.82, Duration.ofMillis(200)))
        );
        var session = openSession(serviceWithBargeIn);
        var voiceSession = serviceWithBargeIn.getSession(session.getId());
        var playbackGeneration = voiceSession.markSpeaking();
        voiceSession.updateCurrentSpeakingText("这是一段很长的回答。");
        ttsRuntime.completePlaybackOnCancel(voiceSession, playbackGeneration);

        serviceWithBargeIn.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "barge_in", null, null, "ws-session-1", null
        ));
        serviceWithBargeIn.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", "barge_in", null, null, "ws-session-1", null
        ));

        assertThat(ttsRuntime.awaitCancelled()).isTrue();
        assertThat(serviceWithBargeIn.getSession(session.getId()).state())
                .isEqualTo(XiaozhiVoiceSession.State.LISTENING);
    }

    @Test
    void shouldKeepSentencePathWhenAsrModeIsSentence() {
        var sentenceSpeech = new RecordingSpeechToTextClient();
        var streamingSpeech = new RecordingStreamingSpeechToTextClient("streaming text", "streaming-provider");
        var serviceWithSentenceAsr = newService(
                sentenceSpeech,
                new FakeHermesClient(),
                new FakeTextToSpeechClient(),
                new XiaozhiAsrMode("sentence"),
                streamingSpeech
        );
        var session = openSession(serviceWithSentenceAsr);

        runSingleTurn(serviceWithSentenceAsr, session);

        assertThat(sentenceSpeech.audioFramePayloads()).containsExactly(List.of(1, 2, 3));
        assertThat(streamingSpeech.callCount()).isZero();
        assertThat(serviceWithSentenceAsr.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
    }

    @Test
    void shouldUseStreamingPathWhenAsrModeIsStreaming() {
        var sentenceSpeech = new RecordingSpeechToTextClient();
        var streamingSpeech = new RecordingStreamingSpeechToTextClient("streaming ping", "streaming-provider");
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
        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", null, null, null, "ws-session-1", null
        ));

        assertThat(awaitIdle(serviceWithStreamingAsr, session)).isTrue();
        assertThat(sentenceSpeech.audioFramePayloads()).isEmpty();
        assertThat(streamingSpeech.callCount()).isEqualTo(1);
        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"stt\"", "streaming ping"));
    }

    @Test
    void shouldSkipTtsWhenStreamingAsrTextIsBlank() {
        var streamingSpeech = new ImmediateStreamingSpeechToTextClient(" ", "streaming-provider");
        var serviceWithStreamingAsr = newService(
                new RecordingSpeechToTextClient(),
                new FakeHermesClient(),
                new FakeTextToSpeechClient(),
                new XiaozhiAsrMode("streaming"),
                streamingSpeech
        );
        var session = openSession(serviceWithStreamingAsr);

        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "auto", null, null, "ws-session-1", null
        ));

        assertThat(awaitIdle(serviceWithStreamingAsr, session)).isTrue();
        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"error\"", "\"code\":\"asr_empty\""))
                .noneSatisfy(payload -> assertThat(payload).contains("\"type\":\"tts\"", "\"state\":\"sentence_start\"",
                        "\"text\":\"我没听清，请再说一遍\""));
        assertThat(session.getSentMessages())
                .noneSatisfy(message -> assertThat(message).isInstanceOf(BinaryMessage.class));
    }

    @Test
    void shouldContinueStreamingTurnWhenAsrCompletesBeforeListenStop() {
        var sentenceSpeech = new RecordingSpeechToTextClient();
        var streamingSpeech = new ImmediateStreamingSpeechToTextClient("ping", "streaming-provider");
        var serviceWithStreamingAsr = newService(
                sentenceSpeech,
                new FakeHermesClient(),
                new FakeTextToSpeechClient(),
                new XiaozhiAsrMode("streaming"),
                streamingSpeech
        );
        var session = openSession(serviceWithStreamingAsr);

        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "auto", null, null, "ws-session-1", null
        ));

        assertThat(awaitIdle(serviceWithStreamingAsr, session)).isTrue();
        assertThat(sentenceSpeech.audioFramePayloads()).isEmpty();
        assertThat(streamingSpeech.callCount()).isEqualTo(1);
        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"stt\"", "\"text\":\"ping\""))
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"tts\"", "\"state\":\"sentence_start\"", "\"text\":\"pong\""));
        assertThat(session.getSentMessages())
                .anySatisfy(message -> assertThat(message).isInstanceOf(BinaryMessage.class));
    }

    @Test
    void shouldSpeakErrorPromptWhenHermesFailsAfterStreamingAsr() {
        var sentenceSpeech = new RecordingSpeechToTextClient();
        var streamingSpeech = new ImmediateStreamingSpeechToTextClient("ping", "streaming-provider");
        var serviceWithStreamingAsr = newService(
                sentenceSpeech,
                new FailingHermesClient(),
                new FakeTextToSpeechClient(),
                new XiaozhiAsrMode("streaming"),
                streamingSpeech
        );
        var session = openSession(serviceWithStreamingAsr);

        serviceWithStreamingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "auto", null, null, "ws-session-1", null
        ));

        assertThat(awaitIdle(serviceWithStreamingAsr, session)).isTrue();
        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"error\"", "\"code\":\"hermes_failed\""))
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"tts\"", "\"state\":\"sentence_start\"",
                        "\"text\":\"对话服务暂时不可用，请稍后再试\""));
        assertThat(session.getSentMessages())
                .anySatisfy(message -> assertThat(message).isInstanceOf(BinaryMessage.class));
    }

    @Test
    void shouldCompleteStreamingAsrWhenSessionCloses() {
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
    void shouldLogUnifiedPlaybackMetricsWhenConversationTurnCompletes() {
        var logger = (Logger) LoggerFactory.getLogger(XiaozhiVoiceSessionService.class);
        var appender = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var session = openSession();

            runSingleTurn(service, session);

            assertThat(appender.list)
                    .extracting(event -> event.getFormattedMessage())
                    .anySatisfy(message -> assertThat(message)
                            .contains(
                                    "xiaozhi turn completed",
                                    "sessionId=ws-session-1",
                                    "deviceId=ws-session-1",
                                    "conversationId=conv-ws-session-1",
                                    "sentenceCount=1",
                                    "ttsFrames=1",
                                    "asrMillis=",
                                    "hermesMillis=",
                                    "ttsMillis=",
                                    "cancelled=false"
                            )
                            .doesNotContain("audioFrames="));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void shouldMeasureHermesAndTtsDurationsFromSeparateBoundaries() {
        var logger = (Logger) LoggerFactory.getLogger(XiaozhiVoiceSessionService.class);
        var appender = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var eventFactory = new XiaozhiServerEventFactory(new ObjectMapper());
            var serviceWithSlowHermes = newService(
                    new FakeSpeechToTextClient(),
                    new DelayingHermesClient(Duration.ofMillis(220), "pong"),
                    new ImmediateTtsRuntime(codec, eventFactory),
                    eventFactory
            );
            var session = openSession(serviceWithSlowHermes);

            runSingleTurn(serviceWithSlowHermes, session);

            var completedLog = completedLogMessages(appender).getFirst();
            var hermesMillis = loggedMetric(completedLog, "hermesMillis");
            var ttsMillis = loggedMetric(completedLog, "ttsMillis");
            assertThat(hermesMillis).isGreaterThanOrEqualTo(180L);
            assertThat(ttsMillis).isLessThan(hermesMillis);
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
                ttsClient
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
    void shouldStartMusicPlayPausedForTtsFromHermesAgentEventWithoutTtsSynthesis() {
        var hermesClient = new StaticSseHermesClient("""
                event: xiaozhi.agent_event
                data: {"action":"music_play","title":"稻香","artist":"周杰伦","media_url":"https://example.com/daoxiang.mp3","confirmation_text":"开始播放稻香"}

                """);
        var ttsClient = new RecordingTextToSpeechClient();
        var musicRuntime = new CapturingMusicPlaybackRuntime();
        var serviceWithMusic = newServiceWithMusic(hermesClient, ttsClient, musicRuntime);
        var session = openSession(serviceWithMusic);

        runSingleTurn(serviceWithMusic, session);

        assertThat(ttsClient.texts()).isEmpty();
        assertThat(musicRuntime.playedTitles()).isEmpty();
        assertThat(musicRuntime.playedPausedForTtsTitles()).containsExactly("稻香");
    }

    @Test
    void shouldStartMusicPlayPausedForTtsFromResponsesDeltaEmbeddedAgentEventWithoutTtsSynthesis() {
        var hermesClient = new StaticSseHermesClient("""
                event: response.output_text.delta
                data: {"delta":"event: xiaozhi.agent_event\\ndata: {\\"action\\":\\"music_play\\",\\"title\\":\\"晴天\\",\\"artist\\":\\"周杰伦\\",\\"media_url\\":\\"https://example.com/qingtian.mp3\\",\\"confirmation_text\\":\\"开始播放晴天\\"}\\n\\n"}
                
                """);
        var ttsClient = new RecordingTextToSpeechClient();
        var musicRuntime = new CapturingMusicPlaybackRuntime();
        var serviceWithMusic = newServiceWithMusic(hermesClient, ttsClient, musicRuntime);
        var session = openSession(serviceWithMusic);

        runSingleTurn(serviceWithMusic, session);

        assertThat(ttsClient.texts()).isEmpty();
        assertThat(musicRuntime.playedTitles()).isEmpty();
        assertThat(musicRuntime.playedPausedForTtsTitles()).containsExactly("晴天");
    }

    @Test
    void shouldUseConfirmationTextFromResponsesDeltaEmbeddedReminderEvent() {
        var hermesClient = new StaticSseHermesClient("""
                event: response.output_text.delta
                data: {"delta":"event: xiaozhi.agent_event\\ndata: {\\"action\\":\\"create_reminder\\",\\"message\\":\\"喝水\\",\\"delay_seconds\\":60,\\"confirmation_text\\":\\"1分钟后提醒你喝水\\"}\\n\\n"}
                
                """);
        var ttsClient = new RecordingTextToSpeechClient();
        var serviceWithReminder = newService(new FakeSpeechToTextClient(), hermesClient, ttsClient);
        var session = openSession(serviceWithReminder);

        runSingleTurn(serviceWithReminder, session);

        assertThat(ttsClient.texts()).containsExactly("1分钟后提醒你喝水");
    }

    @Test
    void shouldStopMusicWhenUserStartsListening() {
        var musicRuntime = new CapturingMusicPlaybackRuntime();
        var serviceWithMusic = newServiceWithMusic(new FakeHermesClient(), new FakeTextToSpeechClient(), musicRuntime);
        var session = openSession(serviceWithMusic);

        serviceWithMusic.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));

        assertThat(musicRuntime.events()).contains("stop:ws-session-1");
    }

    @Test
    void shouldPassDeviceVoiceProfileOptionsToTtsRuntime() {
        var ttsClient = new CapturingOptionsTextToSpeechClient();
        var objectMapper = new ObjectMapper();
        var eventFactory = new XiaozhiServerEventFactory(objectMapper);
        var serviceWithCustomVoiceProfile = new XiaozhiVoiceSessionService(
                codec,
                new FakeSpeechToTextClient(),
                new FakeHermesClient(),
                new XiaozhiTtsRuntime(ttsClient, codec, eventFactory),
                eventFactory,
                new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner"),
                new XiaozhiVoiceTokenAuth(""),
                newMcpBridge(),
                new XiaozhiAsrMode("sentence"),
                new FakeStreamingSpeechToTextClient(),
                XiaozhiAudioParams.defaults(),
                new XiaozhiVoiceProfileResolver(new VoiceId("voice-custom"), 1.25, 0.85)
        );
        var session = openSession(serviceWithCustomVoiceProfile);

        runSingleTurn(serviceWithCustomVoiceProfile, session);

        assertThat(ttsClient.options())
                .singleElement()
                .satisfies(options -> {
                    assertThat(options.voiceId().value()).isEqualTo("voice-custom");
                    assertThat(options.speed()).isEqualTo(1.25);
                    assertThat(options.pitch()).isEqualTo(0.85);
                });
    }

    @Test
    void shouldCancelQueuedStreamingTtsWhenAbortReceived() {
        var ttsClient = new BlockingTextToSpeechClient();
        var serviceWithStreamingHermes = newService(
                new FakeSpeechToTextClient(),
                new StreamingHermesClient("第一句内容很完整。", "第二句内容也完整。"),
                ttsClient
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
    void shouldCancelStreamingTtsWhenListenStartReceived() {
        assertStreamingPlaybackCancelledBy(
                new XiaozhiClientMessage("listen", "start", "manual", null, null, "ws-session-1", null),
                XiaozhiVoiceSession.State.LISTENING
        );
    }

    @Test
    void shouldCancelStreamingTtsWhenSessionNewReceived() {
        assertStreamingPlaybackCancelledBy(
                new XiaozhiClientMessage("session", "new", null, null, null, "ws-session-1", null),
                XiaozhiVoiceSession.State.IDLE
        );
    }

    @Test
    void shouldCancelStreamingTtsWhenSessionClearReceived() {
        assertStreamingPlaybackCancelledBy(
                new XiaozhiClientMessage("session", "clear", null, null, null, "ws-session-1", null),
                XiaozhiVoiceSession.State.IDLE
        );
    }

    @Test
    void shouldIgnoreListenStopWhenIdle() {
        var hermesClient = new CapturingHermesClient();
        var ttsClient = new RecordingTextToSpeechClient();
        var serviceWithCapturingClients = newService(new FakeSpeechToTextClient(), hermesClient, ttsClient);
        var session = openSession(serviceWithCapturingClients);

        serviceWithCapturingClients.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", null, null, null, "ws-session-1", null
        ));

        assertThat(hermesClient.request()).isNull();
        assertThat(ttsClient.texts()).isEmpty();
        assertThat(session.getSentMessages()).isEmpty();
        assertThat(serviceWithCapturingClients.getSession(session.getId()).state())
                .isEqualTo(XiaozhiVoiceSession.State.IDLE);
    }

    @Test
    void shouldIgnoreListenStopWhenSpeakingAndKeepPlaybackOwner() {
        var hermesClient = new CapturingHermesClient();
        var ttsClient = new RecordingTextToSpeechClient();
        var serviceWithCapturingClients = newService(new FakeSpeechToTextClient(), hermesClient, ttsClient);
        var session = openSession(serviceWithCapturingClients);
        var voiceSession = serviceWithCapturingClients.getSession(session.getId());
        var playbackGeneration = voiceSession.markSpeaking();

        serviceWithCapturingClients.handleText(session, new XiaozhiClientMessage(
                "listen", "stop", null, null, null, "ws-session-1", null
        ));

        assertThat(hermesClient.request()).isNull();
        assertThat(ttsClient.texts()).isEmpty();
        assertThat(voiceSession.state()).isEqualTo(XiaozhiVoiceSession.State.SPEAKING);
        assertThat(voiceSession.playbackActive(playbackGeneration)).isTrue();
    }

    @Test
    void shouldCancelBeforeSttWhenAbortReceivedDuringAsr() {
        var logger = (Logger) LoggerFactory.getLogger(XiaozhiVoiceSessionService.class);
        var appender = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var speechToTextClient = new BlockingSpeechToTextClient("ping");
            var hermesClient = new CapturingHermesClient();
            var ttsClient = new RecordingTextToSpeechClient();
            var serviceWithBlockingAsr = newService(speechToTextClient, hermesClient, ttsClient);
            var session = openSession(serviceWithBlockingAsr);
            var turnThread = Thread.startVirtualThread(() -> runSingleTurn(serviceWithBlockingAsr, session));
            assertThat(speechToTextClient.awaitTranscription()).isTrue();

            serviceWithBlockingAsr.handleText(session, new XiaozhiClientMessage(
                    "abort", null, null, "wake_word_detected", null, "ws-session-1", null
            ));
            speechToTextClient.releaseTranscription();
            join(turnThread);

            assertThat(hermesClient.request()).isNull();
            assertThat(ttsClient.texts()).isEmpty();
            assertThat(session.getSentMessages())
                    .filteredOn(BinaryMessage.class::isInstance)
                    .isEmpty();
            assertThat(session.getSentMessages())
                    .filteredOn(TextMessage.class::isInstance)
                    .extracting(message -> message.getPayload().toString())
                    .noneSatisfy(payload -> assertThat(payload)
                            .contains("\"type\":\"stt\""))
                    .noneSatisfy(payload -> assertThat(payload)
                            .contains("\"type\":\"tts\"", "\"state\":\"start\""));
            assertThat(completedLogMessages(appender))
                    .singleElement()
                    .satisfies(message -> assertThat(message)
                            .contains(
                                    "xiaozhi turn completed",
                                    "sessionId=ws-session-1",
                                    "deviceId=ws-session-1",
                                    "sentenceCount=0",
                                    "ttsFrames=0",
                                    "cancelled=true"
                            ));
            assertThat(serviceWithBlockingAsr.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void shouldDiscardOldAsrTextWhenNewListenStartsBeforeTranscriptionReturns() {
        var speechToTextClient = new BlockingSpeechToTextClient("ping");
        var hermesClient = new CapturingHermesClient();
        var ttsClient = new RecordingTextToSpeechClient();
        var serviceWithBlockingAsr = newService(speechToTextClient, hermesClient, ttsClient);
        var session = openSession(serviceWithBlockingAsr);
        serviceWithBlockingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        serviceWithBlockingAsr.handleBinary(session, ByteBuffer.wrap(new byte[] {1, 2, 3}));
        var turnThread = Thread.startVirtualThread(() ->
                serviceWithBlockingAsr.handleText(session, new XiaozhiClientMessage(
                        "listen", "stop", null, null, null, "ws-session-1", null
                )));
        assertThat(speechToTextClient.awaitTranscription()).isTrue();

        serviceWithBlockingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        speechToTextClient.releaseTranscription();
        join(turnThread);

        assertThat(hermesClient.request()).isNull();
        assertThat(ttsClient.texts()).isEmpty();
        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .noneSatisfy(payload -> assertThat(payload).contains("\"type\":\"stt\""));
        assertThat(serviceWithBlockingAsr.getSession(session.getId()).state())
                .isEqualTo(XiaozhiVoiceSession.State.LISTENING);
    }

    @Test
    void shouldKeepNewListeningWhenOldAsrReturnsBlank() {
        var speechToTextClient = new BlockingSpeechToTextClient(" ");
        var hermesClient = new CapturingHermesClient();
        var serviceWithBlockingAsr = newService(speechToTextClient, hermesClient, new RecordingTextToSpeechClient());
        var session = openSession(serviceWithBlockingAsr);
        serviceWithBlockingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        serviceWithBlockingAsr.handleBinary(session, ByteBuffer.wrap(new byte[] {1, 2, 3}));
        var turnThread = Thread.startVirtualThread(() ->
                serviceWithBlockingAsr.handleText(session, new XiaozhiClientMessage(
                        "listen", "stop", null, null, null, "ws-session-1", null
                )));
        assertThat(speechToTextClient.awaitTranscription()).isTrue();

        serviceWithBlockingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        speechToTextClient.releaseTranscription();
        join(turnThread);

        assertThat(hermesClient.request()).isNull();
        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .noneSatisfy(payload -> assertThat(payload).contains("\"type\":\"error\"", "\"code\":\"asr_empty\""));
        assertThat(serviceWithBlockingAsr.getSession(session.getId()).state())
                .isEqualTo(XiaozhiVoiceSession.State.LISTENING);
    }

    @Test
    void shouldKeepNewListeningWhenOldAsrFails() {
        var speechToTextClient = new BlockingFailingSpeechToTextClient();
        var hermesClient = new CapturingHermesClient();
        var serviceWithBlockingAsr = newService(speechToTextClient, hermesClient, new RecordingTextToSpeechClient());
        var session = openSession(serviceWithBlockingAsr);
        serviceWithBlockingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        serviceWithBlockingAsr.handleBinary(session, ByteBuffer.wrap(new byte[] {1, 2, 3}));
        var turnThread = Thread.startVirtualThread(() ->
                serviceWithBlockingAsr.handleText(session, new XiaozhiClientMessage(
                        "listen", "stop", null, null, null, "ws-session-1", null
                )));
        assertThat(speechToTextClient.awaitTranscription()).isTrue();

        serviceWithBlockingAsr.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        speechToTextClient.releaseFailure();
        join(turnThread);

        assertThat(hermesClient.request()).isNull();
        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .noneSatisfy(payload -> assertThat(payload).contains("\"type\":\"error\"", "\"code\":\"asr_failed\""));
        assertThat(serviceWithBlockingAsr.getSession(session.getId()).state())
                .isEqualTo(XiaozhiVoiceSession.State.LISTENING);
    }

    @Test
    void shouldNotPublishReminderWhenAbortArrivesAfterStt() {
        var logger = (Logger) LoggerFactory.getLogger(XiaozhiVoiceSessionService.class);
        var appender = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var eventPublisher = new RecordingApplicationEventPublisher();
            var serviceWithReminderIntent = newService(
                    new FixedSpeechToTextClient("一分钟后提醒我喝水"),
                    new FakeHermesClient(),
                    new RecordingTextToSpeechClient()
            );
            serviceWithReminderIntent.setApplicationEventPublisher(eventPublisher);
            var session = new SttCallbackSession("ws-session-1");
            serviceWithReminderIntent.open(session);
            serviceWithReminderIntent.handleHello(session, new XiaozhiClientHello(
                    "hello",
                    1,
                    Map.of("mcp", true),
                    "websocket",
                    XiaozhiAudioParams.defaults()
            ));
            session.onSttSent(() -> serviceWithReminderIntent.handleText(session, new XiaozhiClientMessage(
                    "abort", null, null, "wake_word_detected", null, "ws-session-1", null
            )));

            runSingleTurn(serviceWithReminderIntent, session);

            assertThat(eventPublisher.events()).isEmpty();
            assertThat(session.getSentMessages())
                    .filteredOn(TextMessage.class::isInstance)
                    .extracting(message -> message.getPayload().toString())
                    .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"stt\""))
                    .noneSatisfy(payload -> assertThat(payload)
                            .contains("\"type\":\"tts\"", "\"state\":\"start\""));
            assertThat(completedLogMessages(appender))
                    .singleElement()
                    .satisfies(message -> assertThat(message)
                            .contains(
                                    "xiaozhi turn completed",
                                    "sessionId=ws-session-1",
                                    "deviceId=ws-session-1",
                                    "sentenceCount=0",
                                    "ttsFrames=0",
                                    "cancelled=true"
                            ));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void shouldNotStartTtsWhenAbortReceivedDuringHermesStreaming() {
        var logger = (Logger) LoggerFactory.getLogger(XiaozhiVoiceSessionService.class);
        var appender = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var hermesClient = new BlockingHermesClient("延迟回复。");
            var ttsClient = new RecordingTextToSpeechClient();
            var serviceWithBlockingHermes = newService(
                    new FakeSpeechToTextClient(),
                    hermesClient,
                    ttsClient
            );
            var session = openSession(serviceWithBlockingHermes);
            var turnThread = Thread.startVirtualThread(() -> runSingleTurn(serviceWithBlockingHermes, session));
            assertThat(hermesClient.awaitStreaming()).isTrue();

            serviceWithBlockingHermes.handleText(session, new XiaozhiClientMessage(
                    "abort", null, null, "wake_word_detected", null, "ws-session-1", null
            ));
            hermesClient.releaseStreaming();
            join(turnThread);

            assertThat(ttsClient.texts()).isEmpty();
            assertThat(session.getSentMessages())
                    .filteredOn(BinaryMessage.class::isInstance)
                    .isEmpty();
            assertThat(session.getSentMessages())
                    .filteredOn(TextMessage.class::isInstance)
                    .extracting(message -> message.getPayload().toString())
                    .noneSatisfy(payload -> assertThat(payload)
                            .contains("\"type\":\"tts\"", "\"state\":\"start\""));
            assertThat(appender.list)
                    .extracting(event -> event.getFormattedMessage())
                    .noneSatisfy(message -> assertThat(message).contains("xiaozhi conversation turn"));
            assertThat(completedLogMessages(appender))
                    .singleElement()
                    .satisfies(message -> assertThat(message)
                            .contains(
                                    "xiaozhi turn completed",
                                    "sessionId=ws-session-1",
                                    "deviceId=ws-session-1",
                                    "sentenceCount=0",
                                    "ttsFrames=0",
                                    "cancelled=true"
                            ));
            assertThat(serviceWithBlockingHermes.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void shouldKeepAbortedTurnCancelledWhenNextListenStartsBeforeHermesReturns() {
        var hermesClient = new BlockingHermesClient("上一轮延迟回复。");
        var ttsClient = new RecordingTextToSpeechClient();
        var serviceWithBlockingHermes = newService(
                new FakeSpeechToTextClient(),
                hermesClient,
                ttsClient
        );
        var session = openSession(serviceWithBlockingHermes);
        var turnThread = Thread.startVirtualThread(() -> runSingleTurn(serviceWithBlockingHermes, session));
        assertThat(hermesClient.awaitStreaming()).isTrue();

        serviceWithBlockingHermes.handleText(session, new XiaozhiClientMessage(
                "abort", null, null, "wake_word_detected", null, "ws-session-1", null
        ));
        serviceWithBlockingHermes.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        hermesClient.releaseStreaming();
        join(turnThread);

        assertThat(ttsClient.texts()).isEmpty();
        assertThat(session.getSentMessages())
                .filteredOn(BinaryMessage.class::isInstance)
                .isEmpty();
        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .noneSatisfy(payload -> assertThat(payload)
                        .contains("\"type\":\"tts\"", "\"state\":\"start\""));
        assertThat(serviceWithBlockingHermes.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.LISTENING);
    }

    @Test
    void shouldTreatHermesStreamExceptionAfterAbortAsCancelledTurn() {
        var logger = (Logger) LoggerFactory.getLogger(XiaozhiVoiceSessionService.class);
        var appender = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var hermesClient = new BlockingFailingHermesClient();
            var ttsClient = new RecordingTextToSpeechClient();
            var serviceWithFailingHermes = newService(
                    new FakeSpeechToTextClient(),
                    hermesClient,
                    ttsClient
            );
            var session = openSession(serviceWithFailingHermes);
            var turnThread = Thread.startVirtualThread(() -> runSingleTurn(serviceWithFailingHermes, session));
            assertThat(hermesClient.awaitStreaming()).isTrue();

            serviceWithFailingHermes.handleText(session, new XiaozhiClientMessage(
                    "abort", null, null, "wake_word_detected", null, "ws-session-1", null
            ));
            hermesClient.releaseFailure();
            join(turnThread);

            assertThat(ttsClient.texts()).isEmpty();
            assertThat(session.getSentMessages())
                    .filteredOn(BinaryMessage.class::isInstance)
                    .isEmpty();
            assertThat(session.getSentMessages())
                    .filteredOn(TextMessage.class::isInstance)
                    .extracting(message -> message.getPayload().toString())
                    .noneSatisfy(payload -> assertThat(payload)
                            .contains("\"type\":\"error\"", "\"code\":\"hermes_failed\""))
                    .noneSatisfy(payload -> assertThat(payload)
                            .contains("\"type\":\"tts\"", "\"state\":\"start\""));
            assertThat(completedLogMessages(appender))
                    .singleElement()
                    .satisfies(message -> assertThat(message)
                            .contains(
                                    "xiaozhi turn completed",
                                    "sessionId=ws-session-1",
                                    "deviceId=ws-session-1",
                                    "sentenceCount=0",
                                    "ttsFrames=0",
                                    "cancelled=true"
                            ));
            assertThat(serviceWithFailingHermes.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void shouldNotStartTtsWhenSessionClosesDuringHermesStreaming() {
        var logger = (Logger) LoggerFactory.getLogger(XiaozhiVoiceSessionService.class);
        var appender = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var hermesClient = new BlockingHermesClient("关闭后延迟回复。");
            var ttsClient = new RecordingTextToSpeechClient();
            var serviceWithBlockingHermes = newService(
                    new FakeSpeechToTextClient(),
                    hermesClient,
                    ttsClient
            );
            var session = openSession(serviceWithBlockingHermes);
            var turnThread = Thread.startVirtualThread(() -> runSingleTurn(serviceWithBlockingHermes, session));
            assertThat(hermesClient.awaitStreaming()).isTrue();

            serviceWithBlockingHermes.close(session);
            hermesClient.releaseStreaming();
            join(turnThread);

            assertThat(ttsClient.texts()).isEmpty();
            assertThat(session.getSentMessages())
                    .filteredOn(BinaryMessage.class::isInstance)
                    .isEmpty();
            assertThat(session.getSentMessages())
                    .filteredOn(TextMessage.class::isInstance)
                    .extracting(message -> message.getPayload().toString())
                    .noneSatisfy(payload -> assertThat(payload)
                            .contains("\"type\":\"tts\"", "\"state\":\"start\""));
            assertThat(appender.list)
                    .extracting(event -> event.getFormattedMessage())
                    .noneSatisfy(message -> assertThat(message).contains("xiaozhi conversation turn"));
            assertThat(completedLogMessages(appender))
                    .singleElement()
                    .satisfies(message -> assertThat(message)
                            .contains(
                                    "xiaozhi turn completed",
                                    "sessionId=ws-session-1",
                                    "deviceId=ws-session-1",
                                    "sentenceCount=0",
                                    "ttsFrames=0",
                                    "cancelled=true"
                            ));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void shouldNotStartTtsWhenAbortArrivesAtRuntimeBoundary() {
        var logger = (Logger) LoggerFactory.getLogger(XiaozhiVoiceSessionService.class);
        var appender = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var profileResolver = new BlockingVoiceProfileResolver();
            var ttsClient = new RecordingTextToSpeechClient();
            var serviceWithBlockingProfile = newService(
                    new FakeSpeechToTextClient(),
                    new FakeHermesClient(),
                    ttsClient,
                    newMcpBridge(),
                    profileResolver
            );
            var session = openSession(serviceWithBlockingProfile);
            var turnThread = Thread.startVirtualThread(() -> runSingleTurn(serviceWithBlockingProfile, session));
            assertThat(profileResolver.awaitResolving()).isTrue();

            serviceWithBlockingProfile.handleText(session, new XiaozhiClientMessage(
                    "abort", null, null, "wake_word_detected", null, "ws-session-1", null
            ));
            profileResolver.releaseResolving();
            join(turnThread);

            assertThat(ttsClient.texts()).isEmpty();
            assertThat(session.getSentMessages())
                    .filteredOn(BinaryMessage.class::isInstance)
                    .isEmpty();
            assertThat(session.getSentMessages())
                    .filteredOn(TextMessage.class::isInstance)
                    .extracting(message -> message.getPayload().toString())
                    .noneSatisfy(payload -> assertThat(payload)
                            .contains("\"type\":\"tts\"", "\"state\":\"start\""));
            assertThat(completedLogMessages(appender))
                    .singleElement()
                    .satisfies(message -> assertThat(message)
                            .contains(
                                    "xiaozhi turn completed",
                                    "sessionId=ws-session-1",
                                    "deviceId=ws-session-1",
                                    "sentenceCount=0",
                                    "ttsFrames=0",
                                    "cancelled=true"
                            ));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void shouldNotStartTtsWhenAbortArrivesAfterRuntimeBoundaryCheck() {
        var logger = (Logger) LoggerFactory.getLogger(XiaozhiVoiceSessionService.class);
        var appender = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var eventFactory = new XiaozhiServerEventFactory(new ObjectMapper());
            var ttsClient = new RecordingTextToSpeechClient();
            var ttsRuntime = new BoundaryBlockingTtsRuntime(ttsClient, codec, eventFactory);
            var serviceWithBlockingRuntime = newService(
                    new FakeSpeechToTextClient(),
                    new FakeHermesClient(),
                    ttsRuntime,
                    eventFactory
            );
            var session = openSession(serviceWithBlockingRuntime);
            var turnThread = Thread.startVirtualThread(() -> runSingleTurn(serviceWithBlockingRuntime, session));
            assertThat(ttsRuntime.awaitSpeaking()).isTrue();

            serviceWithBlockingRuntime.handleText(session, new XiaozhiClientMessage(
                    "abort", null, null, "wake_word_detected", null, "ws-session-1", null
            ));
            ttsRuntime.releaseSpeaking();
            join(turnThread);

            assertThat(ttsClient.texts()).isEmpty();
            assertThat(session.getSentMessages())
                    .filteredOn(BinaryMessage.class::isInstance)
                    .isEmpty();
            assertThat(session.getSentMessages())
                    .filteredOn(TextMessage.class::isInstance)
                    .extracting(message -> message.getPayload().toString())
                    .noneSatisfy(payload -> assertThat(payload)
                            .contains("\"type\":\"tts\"", "\"state\":\"start\""));
            assertThat(appender.list)
                    .extracting(event -> event.getFormattedMessage())
                    .noneSatisfy(message -> assertThat(message).contains("xiaozhi conversation turn"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void joinShouldFailWhenThreadDoesNotFinish() throws InterruptedException {
        var releaseThread = new java.util.concurrent.CountDownLatch(1);
        var thread = Thread.startVirtualThread(() -> await(releaseThread));
        try {
            assertThatThrownBy(() -> join(thread))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("expected to finish");
        } finally {
            releaseThread.countDown();
            thread.join(Duration.ofSeconds(1));
        }
    }

    @Test
    void shouldUseFirmwareDeviceIdWhenCallingHermes() {
        var hermesClient = new CapturingHermesClient();
        var serviceWithCapturingHermes = newService(new FakeSpeechToTextClient(), hermesClient, new FakeTextToSpeechClient());
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
        var serviceWithRecordingHermes = newService(new FakeSpeechToTextClient(), hermesClient, new FakeTextToSpeechClient());
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
        var serviceWithBlankAsr = newService(audioFrames -> " ", hermesClient, new FakeTextToSpeechClient());
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
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"error\"", "\"code\":\"asr_empty\""))
                .noneSatisfy(payload -> assertThat(payload).contains("\"type\":\"tts\"", "\"state\":\"sentence_start\"",
                        "\"text\":\"我没听清，请再说一遍\""));
        assertThat(session.getSentMessages())
                .noneSatisfy(message -> assertThat(message).isInstanceOf(BinaryMessage.class));
    }

    @Test
    void shouldReturnIdleWhenHermesFails() {
        var serviceWithFailingHermes = newService(
                new FakeSpeechToTextClient(),
                new FailingHermesClient(),
                new FakeTextToSpeechClient()
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
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"error\"", "\"code\":\"hermes_failed\""))
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"tts\"", "\"state\":\"sentence_start\"",
                        "\"text\":\"对话服务暂时不可用，请稍后再试\""));
        assertThat(session.getSentMessages())
                .anySatisfy(message -> assertThat(message).isInstanceOf(BinaryMessage.class));
    }

    @Test
    void shouldSendTtsStopWhenSynthesizedAudioIsEmpty() {
        var serviceWithEmptyTts = newService(new EmptyTextToSpeechClient());
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
        var serviceWithFailingTts = newService(new FailingTextToSpeechClient());
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
        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .noneSatisfy(payload -> assertThat(payload).contains("\"type\":\"tts\"", "\"state\":\"sentence_start\"",
                        "\"text\":\"语音合成失败\""));
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
        var serviceWithRecordingSpeech = newService(recordingSpeech, new FakeHermesClient(), new FakeTextToSpeechClient());
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
    void shouldStartStreamingTtsBeforeHermesStreamCompletes() {
        var hermes = new FirstChunkThenBlockingHermesClient("第一句内容已经完整。", "第二句稍后也完整。");
        var streamingTts = new RecordingStreamingTextToSpeechClient();
        var eventFactory = new XiaozhiServerEventFactory(new ObjectMapper());
        var runtime = new XiaozhiTtsRuntime(
                new FakeTextToSpeechClient(),
                streamingTts,
                codec,
                eventFactory
        );
        var serviceWithStreamingTts = newService(new FakeSpeechToTextClient(), hermes, runtime, eventFactory);
        var session = openSession(serviceWithStreamingTts);

        var turnThread = Thread.startVirtualThread(() -> runSingleTurn(serviceWithStreamingTts, session));

        assertThat(hermes.awaitSecondChunkRequested()).isTrue();
        assertThat(streamingTts.texts()).containsExactly("第一句内容已经完整。");
        hermes.release();
        join(turnThread);

        assertThat(streamingTts.texts()).containsExactly("第一句内容已经完整。", "第二句稍后也完整。");
        assertThat(serviceWithStreamingTts.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
    }

    @Test
    void shouldUseStreamingRuntimeForNotificationWhenStreamingTtsIsEnabled() {
        var eventFactory = new XiaozhiServerEventFactory(new ObjectMapper());
        var streamingTts = new RecordingStreamingTextToSpeechClient();
        var runtime = new XiaozhiTtsRuntime(
                new FakeTextToSpeechClient(),
                streamingTts,
                codec,
                eventFactory
        );
        var serviceWithStreamingRuntime = newService(
                new FakeSpeechToTextClient(),
                new FakeHermesClient(),
                runtime,
                eventFactory
        );
        var headers = new HttpHeaders();
        headers.set("Device-Id", "device-1");
        var session = new TestWebSocketSession("ws-session-1", URI.create("ws://127.0.0.1/xiaozhi/v1"), headers);
        serviceWithStreamingRuntime.open(session);
        serviceWithStreamingRuntime.handleHello(session, new XiaozhiClientHello(
                "hello",
                1,
                Map.of("mcp", true),
                "websocket",
                XiaozhiAudioParams.defaults()
        ));

        var notified = serviceWithStreamingRuntime.notifyDevice("device-1", "提醒时间到了。");

        assertThat(notified).isTrue();
        assertThat(streamingTts.texts()).containsExactly("提醒时间到了。");
        assertThat(textPayloads(session))
                .filteredOn(payload -> payload.contains("\"type\":\"tts\"") && payload.contains("\"state\":\"stop\""))
                .hasSize(1);
    }

    @Test
    void shouldSpeakErrorPromptWhenHermesFailsWithStreamingTtsEnabled() {
        var eventFactory = new XiaozhiServerEventFactory(new ObjectMapper());
        var streamingTts = new RecordingStreamingTextToSpeechClient();
        var runtime = new XiaozhiTtsRuntime(
                new FakeTextToSpeechClient(),
                streamingTts,
                codec,
                eventFactory
        );
        var serviceWithStreamingTts = newService(
                new FakeSpeechToTextClient(),
                new FailingHermesClient(),
                runtime,
                eventFactory
        );
        var session = openSession(serviceWithStreamingTts);

        runSingleTurn(serviceWithStreamingTts, session);

        assertThat(streamingTts.texts()).isEmpty();
        assertThat(textPayloads(session))
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"error\"", "\"code\":\"hermes_failed\""))
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"tts\"", "\"state\":\"sentence_start\"",
                        "\"text\":\"对话服务暂时不可用，请稍后再试\""));
        assertThat(session.getSentMessages())
                .anySatisfy(message -> assertThat(message).isInstanceOf(BinaryMessage.class));
        assertThat(serviceWithStreamingTts.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
    }

    @Test
    void shouldReportTtsFailedWhenStreamingTtsWriteFailsDuringHermesStream() {
        var eventFactory = new XiaozhiServerEventFactory(new ObjectMapper());
        var runtime = new XiaozhiTtsRuntime(
                new FakeTextToSpeechClient(),
                new FailingSendStreamingTextToSpeechClient(),
                codec,
                eventFactory
        );
        var serviceWithFailingStreamingTts = newService(
                new FakeSpeechToTextClient(),
                new StreamingHermesClient("第一句内容已经完整。"),
                runtime,
                eventFactory
        );
        var session = openSession(serviceWithFailingStreamingTts);

        runSingleTurn(serviceWithFailingStreamingTts, session);

        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"error\"", "\"code\":\"tts_failed\""))
                .noneSatisfy(payload -> assertThat(payload).contains("\"type\":\"error\"", "\"code\":\"hermes_failed\""));
        assertThat(serviceWithFailingStreamingTts.getSession(session.getId()).state())
                .isEqualTo(XiaozhiVoiceSession.State.IDLE);
    }

    @Test
    void shouldDelegateNotificationDirectlyToTtsRuntimeRequest() {
        var eventFactory = new XiaozhiServerEventFactory(new ObjectMapper());
        var ttsRuntime = new CapturingTtsRuntime(codec, eventFactory);
        var serviceWithCapturingRuntime = newService(
                new FakeSpeechToTextClient(),
                new FakeHermesClient(),
                ttsRuntime,
                eventFactory,
                new XiaozhiVoiceProfileResolver(new VoiceId("voice-notify"), 1.2, 0.9)
        );
        var headers = new HttpHeaders();
        headers.set("Device-Id", "device-1");
        var session = new TestWebSocketSession("ws-session-1", URI.create("ws://127.0.0.1/xiaozhi/v1"), headers);
        serviceWithCapturingRuntime.open(session);
        serviceWithCapturingRuntime.handleHello(session, new XiaozhiClientHello(
                "hello",
                1,
                Map.of("mcp", true),
                "websocket",
                XiaozhiAudioParams.defaults()
        ));

        var notified = serviceWithCapturingRuntime.notifyDevice("device-1", "提醒时间到了");

        assertThat(notified).isTrue();
        assertThat(ttsRuntime.request())
                .satisfies(request -> {
                    assertThat(request.webSocketSession()).isSameAs(session);
                    assertThat(request.voiceSession()).isSameAs(serviceWithCapturingRuntime.getSession(session.getId()));
                    assertThat(request.sentences()).containsExactly("提醒时间到了");
                    assertThat(request.options().voiceId().value()).isEqualTo("voice-notify");
                    assertThat(request.options().speed()).isEqualTo(1.2);
                    assertThat(request.options().pitch()).isEqualTo(0.9);
                });
        // 锁定通知路径直接委托 runtime 构造 request，不再复用普通 turn 播放 helper。
        assertThat(ttsRuntime.calledThroughSpeakWithRuntime()).isFalse();
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
    void shouldSkipNotificationWhenSessionIsSpeaking() {
        var eventFactory = new XiaozhiServerEventFactory(new ObjectMapper());
        var ttsRuntime = new CapturingTtsRuntime(codec, eventFactory);
        var serviceWithCapturingRuntime = newService(
                new FakeSpeechToTextClient(),
                new FakeHermesClient(),
                ttsRuntime,
                eventFactory
        );
        var session = openSession(serviceWithCapturingRuntime);
        serviceWithCapturingRuntime.getSession(session.getId()).markSpeaking();

        var notified = serviceWithCapturingRuntime.notifyDevice("ws-session-1", "提醒时间到了");

        assertThat(notified).isFalse();
        assertThat(ttsRuntime.request()).isNull();
        assertThat(serviceWithCapturingRuntime.getSession(session.getId()).state())
                .isEqualTo(XiaozhiVoiceSession.State.SPEAKING);
    }

    @Test
    void shouldAllowOnlyOneConcurrentNotificationToCallRuntime() {
        var eventFactory = new XiaozhiServerEventFactory(new ObjectMapper());
        var ttsRuntime = new BlockingCountingTtsRuntime(codec, eventFactory);
        var serviceWithCountingRuntime = newService(
                new FakeSpeechToTextClient(),
                new FakeHermesClient(),
                ttsRuntime,
                eventFactory
        );
        var headers = new HttpHeaders();
        headers.set("Device-Id", "device-1");
        var session = new TestWebSocketSession("ws-session-1", URI.create("ws://127.0.0.1/xiaozhi/v1"), headers);
        serviceWithCountingRuntime.open(session);
        serviceWithCountingRuntime.handleHello(session, new XiaozhiClientHello(
                "hello",
                1,
                Map.of("mcp", true),
                "websocket",
                XiaozhiAudioParams.defaults()
        ));
        var firstResult = new java.util.concurrent.atomic.AtomicReference<Boolean>();
        var secondResult = new java.util.concurrent.atomic.AtomicReference<Boolean>();
        var firstThread = Thread.startVirtualThread(() ->
                firstResult.set(serviceWithCountingRuntime.notifyDevice("device-1", "提醒时间到了")));
        assertThat(ttsRuntime.awaitFirstSpeak()).isTrue();

        var secondThread = Thread.startVirtualThread(() ->
                secondResult.set(serviceWithCountingRuntime.notifyDevice("device-1", "提醒时间到了")));
        join(secondThread);
        ttsRuntime.releaseFirstSpeak();
        join(firstThread);

        assertThat(List.of(firstResult.get(), secondResult.get())).containsExactlyInAnyOrder(true, false);
        assertThat(ttsRuntime.speakCalls()).isEqualTo(1);
    }

    @Test
    void shouldNotStartNotificationPlaybackWhenAbortClearsOwnerBeforeRuntimeStarts() {
        var eventFactory = new XiaozhiServerEventFactory(new ObjectMapper());
        var voiceProfileResolver = new BlockingVoiceProfileResolver();
        var ttsRuntime = new XiaozhiTtsRuntime(new FakeTextToSpeechClient(), codec, eventFactory);
        var serviceWithBlockingResolver = newService(
                new FakeSpeechToTextClient(),
                new FakeHermesClient(),
                ttsRuntime,
                eventFactory,
                voiceProfileResolver
        );
        var headers = new HttpHeaders();
        headers.set("Device-Id", "device-1");
        var session = new TestWebSocketSession("ws-session-1", URI.create("ws://127.0.0.1/xiaozhi/v1"), headers);
        serviceWithBlockingResolver.open(session);
        serviceWithBlockingResolver.handleHello(session, new XiaozhiClientHello(
                "hello",
                1,
                Map.of("mcp", true),
                "websocket",
                XiaozhiAudioParams.defaults()
        ));
        var notified = new java.util.concurrent.atomic.AtomicReference<Boolean>();
        var notifyThread = Thread.startVirtualThread(() ->
                notified.set(serviceWithBlockingResolver.notifyDevice("device-1", "提醒时间到了")));
        assertThat(voiceProfileResolver.awaitResolving()).isTrue();

        serviceWithBlockingResolver.handleText(session, new XiaozhiClientMessage(
                "abort", null, null, "wake_word_detected", null, "ws-session-1", null
        ));
        voiceProfileResolver.releaseResolving();
        join(notifyThread);

        assertThat(notified.get()).isFalse();
        assertThat(serviceWithBlockingResolver.getSession(session.getId()).state())
                .isEqualTo(XiaozhiVoiceSession.State.IDLE);
        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .noneSatisfy(payload -> assertThat(payload)
                        .containsAnyOf("\"state\":\"start\"", "\"state\":\"sentence_start\""));
        assertThat(session.getSentMessages())
                .noneSatisfy(message -> assertThat(message).isInstanceOf(BinaryMessage.class));
    }

    @Test
    void shouldCancelNotificationWhenDeviceReconnectsBeforeRuntimeStarts() {
        var eventFactory = new XiaozhiServerEventFactory(new ObjectMapper());
        var voiceProfileResolver = new BlockingVoiceProfileResolver();
        var ttsRuntime = new XiaozhiTtsRuntime(new FakeTextToSpeechClient(), codec, eventFactory);
        var serviceWithBlockingResolver = newService(
                new FakeSpeechToTextClient(),
                new FakeHermesClient(),
                ttsRuntime,
                eventFactory,
                voiceProfileResolver
        );
        var oldHeaders = new HttpHeaders();
        oldHeaders.set("Device-Id", "device-1");
        var oldSession = new TestWebSocketSession(
                "ws-session-old",
                URI.create("ws://127.0.0.1/xiaozhi/v1"),
                oldHeaders
        );
        serviceWithBlockingResolver.open(oldSession);
        serviceWithBlockingResolver.handleHello(oldSession, new XiaozhiClientHello(
                "hello",
                1,
                Map.of("mcp", true),
                "websocket",
                XiaozhiAudioParams.defaults()
        ));
        var notified = new java.util.concurrent.atomic.AtomicReference<Boolean>();
        var notifyThread = Thread.startVirtualThread(() ->
                notified.set(serviceWithBlockingResolver.notifyDevice("device-1", "提醒时间到了")));
        assertThat(voiceProfileResolver.awaitResolving()).isTrue();

        var newHeaders = new HttpHeaders();
        newHeaders.set("Device-Id", "device-1");
        var newSession = new TestWebSocketSession(
                "ws-session-new",
                URI.create("ws://127.0.0.1/xiaozhi/v1"),
                newHeaders
        );
        serviceWithBlockingResolver.open(newSession);
        serviceWithBlockingResolver.handleHello(newSession, new XiaozhiClientHello(
                "hello",
                1,
                Map.of("mcp", true),
                "websocket",
                XiaozhiAudioParams.defaults()
        ));
        voiceProfileResolver.releaseResolving();
        join(notifyThread);

        assertThat(notified.get()).isFalse();
        assertThat(serviceWithBlockingResolver.getSession(oldSession.getId()).state())
                .isEqualTo(XiaozhiVoiceSession.State.IDLE);
        assertThat(oldSession.isOpen()).isTrue();
        assertThat(oldSession.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .noneSatisfy(payload -> assertThat(payload)
                        .containsAnyOf("\"state\":\"start\"", "\"state\":\"sentence_start\""));
        assertThat(oldSession.getSentMessages())
                .noneSatisfy(message -> assertThat(message).isInstanceOf(BinaryMessage.class));
        assertThat(serviceWithBlockingResolver.getSession(newSession.getId()).state())
                .isEqualTo(XiaozhiVoiceSession.State.IDLE);
    }

    @Test
    void shouldReleaseNotificationOwnerWhenRuntimeReturnsFalseWithoutCleanup() {
        var eventFactory = new XiaozhiServerEventFactory(new ObjectMapper());
        var ttsRuntime = new FalseReturningTtsRuntime(codec, eventFactory);
        var serviceWithFalseRuntime = newService(
                new FakeSpeechToTextClient(),
                new FakeHermesClient(),
                ttsRuntime,
                eventFactory
        );
        var session = openSession(serviceWithFalseRuntime);

        var notified = serviceWithFalseRuntime.notifyDevice("ws-session-1", "提醒时间到了");

        assertThat(notified).isFalse();
        assertThat(serviceWithFalseRuntime.getSession(session.getId()).state())
                .isEqualTo(XiaozhiVoiceSession.State.IDLE);
    }

    @Test
    void shouldReturnFalseWhenReminderSpeechFails() {
        var serviceWithFailingTts = newService(new FailingTextToSpeechClient());
        var session = openSession(serviceWithFailingTts);

        var notified = serviceWithFailingTts.notifyDevice("ws-session-1", "提醒时间到了");

        assertThat(notified).isFalse();
        assertThat(serviceWithFailingTts.getSession(session.getId()).state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"error\"", "\"code\":\"tts_failed\""));
    }

    @Test
    void shouldKeepListeningWhenNotificationFailsAfterListenStarts() {
        var eventFactory = new XiaozhiServerEventFactory(new ObjectMapper());
        var ttsRuntime = new FailingAfterListenStartTtsRuntime(codec, eventFactory);
        var serviceWithFailingRuntime = newService(
                new FakeSpeechToTextClient(),
                new FakeHermesClient(),
                ttsRuntime,
                eventFactory
        );
        var session = openSession(serviceWithFailingRuntime);
        var notified = new java.util.concurrent.atomic.AtomicReference<Boolean>();
        var notifyThread = Thread.startVirtualThread(() ->
                notified.set(serviceWithFailingRuntime.notifyDevice("ws-session-1", "提醒时间到了")));
        assertThat(ttsRuntime.awaitSpeaking()).isTrue();

        serviceWithFailingRuntime.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        ttsRuntime.releaseFailure();
        join(notifyThread);

        assertThat(notified.get()).isFalse();
        assertThat(serviceWithFailingRuntime.getSession(session.getId()).state())
                .isEqualTo(XiaozhiVoiceSession.State.LISTENING);
        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"error\"", "\"code\":\"tts_failed\""));
    }

    @Test
    void shouldSuppressOldTurnTtsFailureAfterNewListenStarts() {
        var ttsClient = new BlockingFailingTextToSpeechClient();
        var serviceWithBlockingTts = newService(new FakeSpeechToTextClient(), new FakeHermesClient(), ttsClient);
        var session = openSession(serviceWithBlockingTts);
        serviceWithBlockingTts.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        serviceWithBlockingTts.handleBinary(session, ByteBuffer.wrap(new byte[] {1, 2, 3}));
        var turnThread = Thread.startVirtualThread(() ->
                serviceWithBlockingTts.handleText(session, new XiaozhiClientMessage(
                        "listen", "stop", null, null, null, "ws-session-1", null
                )));
        try {
            assertThat(ttsClient.awaitSynthesis()).isTrue();

            serviceWithBlockingTts.handleText(session, new XiaozhiClientMessage(
                    "listen", "start", "manual", null, null, "ws-session-1", null
            ));
            ttsClient.releaseFailure();
            join(turnThread);
        } finally {
            ttsClient.releaseFailure();
            join(turnThread);
        }

        assertThat(serviceWithBlockingTts.getSession(session.getId()).state())
                .isEqualTo(XiaozhiVoiceSession.State.LISTENING);
        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .noneSatisfy(payload -> assertThat(payload).contains("\"type\":\"error\"", "\"code\":\"tts_failed\""));
    }

    @Test
    void shouldSerializeTurnTtsFailureSendWithNewListenStart() {
        var ttsClient = new BlockingFailingTextToSpeechClient();
        var serviceWithBlockingTts = newService(new FakeSpeechToTextClient(), new FakeHermesClient(), ttsClient);
        var session = new BlockingTtsFailureSession("ws-session-1");
        openSession(serviceWithBlockingTts, session);
        serviceWithBlockingTts.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        serviceWithBlockingTts.handleBinary(session, ByteBuffer.wrap(new byte[] {1, 2, 3}));
        var turnThread = Thread.startVirtualThread(() ->
                serviceWithBlockingTts.handleText(session, new XiaozhiClientMessage(
                        "listen", "stop", null, null, null, "ws-session-1", null
                )));
        var listenStarted = new java.util.concurrent.CountDownLatch(1);
        var listenThread = Thread.ofVirtual().unstarted(() -> {
            serviceWithBlockingTts.handleText(session, new XiaozhiClientMessage(
                    "listen", "start", "manual", null, null, "ws-session-1", null
            ));
            listenStarted.countDown();
        });
        try {
            assertThat(ttsClient.awaitSynthesis()).isTrue();
            ttsClient.releaseFailure();
            assertThat(session.awaitTtsFailureSend()).isTrue();

            listenThread.start();

            assertThat(await(listenStarted, Duration.ofMillis(200))).isFalse();
        } finally {
            ttsClient.releaseFailure();
            session.releaseTtsFailureSend();
            join(turnThread);
            if (listenThread.getState() != Thread.State.NEW) {
                join(listenThread);
            }
        }
        assertThat(serviceWithBlockingTts.getSession(session.getId()).state())
                .isEqualTo(XiaozhiVoiceSession.State.LISTENING);
    }

    @Test
    void shouldNotClearNewNotificationOwnerWhenOldTurnTtsFailureIsHandled() {
        var eventFactory = new XiaozhiServerEventFactory(new ObjectMapper());
        var ttsRuntime = new TurnFailureInterleavingTtsRuntime(codec, eventFactory);
        var serviceWithInterleavingRuntime = newService(
                new FakeSpeechToTextClient(),
                new FakeHermesClient(),
                ttsRuntime,
                eventFactory
        );
        var session = openSession(serviceWithInterleavingRuntime);
        serviceWithInterleavingRuntime.handleText(session, new XiaozhiClientMessage(
                "listen", "start", "manual", null, null, "ws-session-1", null
        ));
        serviceWithInterleavingRuntime.handleBinary(session, ByteBuffer.wrap(new byte[] {1, 2, 3}));
        var turnThread = Thread.startVirtualThread(() ->
                serviceWithInterleavingRuntime.handleText(session, new XiaozhiClientMessage(
                        "listen", "stop", null, null, null, "ws-session-1", null
                )));
        assertThat(ttsRuntime.awaitTurnPlaybackCleanedUp()).isTrue();

        var notified = new java.util.concurrent.atomic.AtomicReference<Boolean>();
        var notifyThread = Thread.startVirtualThread(() ->
                notified.set(serviceWithInterleavingRuntime.notifyDevice("ws-session-1", "提醒时间到了")));
        try {
            assertThat(ttsRuntime.awaitNotificationStarted()).isTrue();
            assertThat(serviceWithInterleavingRuntime.getSession(session.getId()).state())
                    .isEqualTo(XiaozhiVoiceSession.State.SPEAKING);

            ttsRuntime.releaseTurnFailure();
            join(turnThread);

            assertThat(serviceWithInterleavingRuntime.getSession(session.getId()).state())
                    .isEqualTo(XiaozhiVoiceSession.State.SPEAKING);

            ttsRuntime.releaseNotification();
            join(notifyThread);
        } finally {
            ttsRuntime.releaseTurnFailure();
            ttsRuntime.releaseNotification();
            join(turnThread);
            join(notifyThread);
        }

        assertThat(notified.get()).isTrue();
        assertThat(serviceWithInterleavingRuntime.getSession(session.getId()).state())
                .isEqualTo(XiaozhiVoiceSession.State.IDLE);
        assertThat(session.getSentMessages())
                .filteredOn(TextMessage.class::isInstance)
                .extracting(message -> message.getPayload().toString())
                .anySatisfy(payload -> assertThat(payload).contains("\"type\":\"error\"", "\"code\":\"tts_failed\""));
    }

    @Test
    void shouldScheduleRelativeReminderBeforeCallingHermes() {
        var eventPublisher = new RecordingApplicationEventPublisher();
        var serviceWithReminderIntent = newService(
                new FixedSpeechToTextClient("一分钟后提醒我喝水"),
                new FailingHermesClient(),
                new FakeTextToSpeechClient()
        );
        serviceWithReminderIntent.setApplicationEventPublisher(eventPublisher);
        var session = openSession(serviceWithReminderIntent);

        runSingleTurn(serviceWithReminderIntent, session);

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
                        .contains("\"type\":\"tts\"", "\"state\":\"sentence_start\"", "一分钟后提醒你喝水"));
    }

    @Test
    void shouldLogUnifiedPlaybackMetricsWhenReminderTurnCompletes() {
        var logger = (Logger) LoggerFactory.getLogger(XiaozhiVoiceSessionService.class);
        var appender = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var serviceWithReminderIntent = newService(
                    new FixedSpeechToTextClient("一分钟后提醒我喝水"),
                    new FailingHermesClient(),
                    new FakeTextToSpeechClient()
            );
            var session = openSession(serviceWithReminderIntent);

            runSingleTurn(serviceWithReminderIntent, session);

            assertThat(appender.list)
                    .extracting(event -> event.getFormattedMessage())
                    .anySatisfy(message -> assertThat(message)
                            .contains(
                                    "xiaozhi turn completed",
                                    "sessionId=ws-session-1",
                                    "deviceId=ws-session-1",
                                    "conversationId=conv-ws-session-1",
                                    "sentenceCount=1",
                                    "ttsFrames=1",
                                    "asrMillis=",
                                    "hermesMillis=",
                                    "ttsMillis=",
                                    "cancelled=false"
                            )
                            .doesNotContain("audioFrames="));
        } finally {
            logger.detachAppender(appender);
        }
    }

    private TestWebSocketSession openSession() {
        return openSession(service);
    }

    private XiaozhiVoiceSessionService newService() {
        return newService(new FakeTextToSpeechClient());
    }

    private XiaozhiVoiceSessionService newService(TextToSpeechClient textToSpeechClient) {
        return newService(new FakeSpeechToTextClient(), new FakeHermesClient(), textToSpeechClient);
    }

    private XiaozhiVoiceSessionService newService(
            SpeechToTextClient speechToTextClient,
            HermesClient hermesClient,
            TextToSpeechClient textToSpeechClient
    ) {
        return newService(speechToTextClient, hermesClient, textToSpeechClient, newMcpBridge());
    }

    private XiaozhiVoiceSessionService newService(
            SpeechToTextClient speechToTextClient,
            HermesClient hermesClient,
            TextToSpeechClient textToSpeechClient,
            XiaozhiMcpBridge mcpBridge
    ) {
        return newService(speechToTextClient,
                hermesClient,
                textToSpeechClient,
                mcpBridge,
                new XiaozhiVoiceProfileResolver(new VoiceId("default"), 1.0, 1.0));
    }

    private XiaozhiVoiceSessionService newService(
            SpeechToTextClient speechToTextClient,
            HermesClient hermesClient,
            TextToSpeechClient textToSpeechClient,
            XiaozhiMcpBridge mcpBridge,
            XiaozhiVoiceProfileResolver voiceProfileResolver
    ) {
        var eventFactory = new XiaozhiServerEventFactory(new ObjectMapper());
        return new XiaozhiVoiceSessionService(
                codec,
                speechToTextClient,
                hermesClient,
                new XiaozhiTtsRuntime(textToSpeechClient, codec, eventFactory),
                eventFactory,
                new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner"),
                new XiaozhiVoiceTokenAuth(""),
                mcpBridge,
                new XiaozhiAsrMode("sentence"),
                new FakeStreamingSpeechToTextClient(),
                XiaozhiAudioParams.defaults(),
                voiceProfileResolver
        );
    }

    private XiaozhiVoiceSessionService newService(
            SpeechToTextClient speechToTextClient,
            HermesClient hermesClient,
            XiaozhiTtsRuntime ttsRuntime,
            XiaozhiServerEventFactory eventFactory
    ) {
        return newService(speechToTextClient,
                hermesClient,
                ttsRuntime,
                eventFactory,
                new XiaozhiVoiceProfileResolver(new VoiceId("default"), 1.0, 1.0));
    }

    private XiaozhiVoiceSessionService newService(
            SpeechToTextClient speechToTextClient,
            HermesClient hermesClient,
            XiaozhiTtsRuntime ttsRuntime,
            XiaozhiServerEventFactory eventFactory,
            XiaozhiVoiceProfileResolver voiceProfileResolver
    ) {
        return new XiaozhiVoiceSessionService(
                codec,
                speechToTextClient,
                hermesClient,
                ttsRuntime,
                eventFactory,
                new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner"),
                new XiaozhiVoiceTokenAuth(""),
                newMcpBridge(),
                new XiaozhiAsrMode("sentence"),
                new FakeStreamingSpeechToTextClient(),
                XiaozhiAudioParams.defaults(),
                voiceProfileResolver
        );
    }

    private XiaozhiVoiceSessionService newService(
            SpeechToTextClient speechToTextClient,
            HermesClient hermesClient,
            XiaozhiTtsRuntime ttsRuntime,
            XiaozhiServerEventFactory eventFactory,
            XiaozhiAsrMode asrMode,
            StreamingSpeechToTextClient streamingSpeechToTextClient,
            XiaozhiBargeInDetector bargeInDetector
    ) {
        return new XiaozhiVoiceSessionService(
                codec,
                speechToTextClient,
                hermesClient,
                ttsRuntime,
                eventFactory,
                new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner"),
                new XiaozhiVoiceTokenAuth(""),
                newMcpBridge(),
                asrMode,
                streamingSpeechToTextClient,
                XiaozhiAudioParams.defaults(),
                new XiaozhiVoiceProfileResolver(new VoiceId("default"), 1.0, 1.0),
                bargeInDetector,
                (XiaozhiMusicActionHandler) null,
                (XiaozhiMusicPlaybackRuntime) null
        );
    }

    private XiaozhiVoiceSessionService newServiceWithMusic(
            HermesClient hermesClient,
            TextToSpeechClient textToSpeechClient,
            XiaozhiMusicPlaybackRuntime musicRuntime
    ) {
        var eventFactory = new XiaozhiServerEventFactory(new ObjectMapper());
        return new XiaozhiVoiceSessionService(
                codec,
                new FakeSpeechToTextClient(),
                hermesClient,
                new XiaozhiTtsRuntime(textToSpeechClient, codec, eventFactory),
                eventFactory,
                new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner"),
                new XiaozhiVoiceTokenAuth(""),
                newMcpBridge(),
                new XiaozhiAsrMode("sentence"),
                new FakeStreamingSpeechToTextClient(),
                XiaozhiAudioParams.defaults(),
                new XiaozhiVoiceProfileResolver(new VoiceId("default"), 1.0, 1.0),
                new XiaozhiMusicActionHandler(musicRuntime),
                musicRuntime
        );
    }

    private XiaozhiVoiceSessionService newService(
            SpeechToTextClient speechToTextClient,
            HermesClient hermesClient,
            TextToSpeechClient textToSpeechClient,
            XiaozhiAsrMode asrMode,
            StreamingSpeechToTextClient streamingSpeechToTextClient
    ) {
        return newService(
                speechToTextClient,
                hermesClient,
                textToSpeechClient,
                asrMode,
                streamingSpeechToTextClient,
                new XiaozhiBargeInDetector(new XiaozhiBargeInProperties(false, 2, 500, 0.82, Duration.ofSeconds(2)))
        );
    }

    private XiaozhiVoiceSessionService newService(
            SpeechToTextClient speechToTextClient,
            HermesClient hermesClient,
            TextToSpeechClient textToSpeechClient,
            XiaozhiAsrMode asrMode,
            StreamingSpeechToTextClient streamingSpeechToTextClient,
            XiaozhiBargeInDetector bargeInDetector
    ) {
        var eventFactory = new XiaozhiServerEventFactory(new ObjectMapper());
        return new XiaozhiVoiceSessionService(
                codec,
                speechToTextClient,
                hermesClient,
                new XiaozhiTtsRuntime(textToSpeechClient, codec, eventFactory),
                eventFactory,
                new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner"),
                new XiaozhiVoiceTokenAuth(""),
                newMcpBridge(),
                asrMode,
                streamingSpeechToTextClient,
                XiaozhiAudioParams.defaults(),
                new XiaozhiVoiceProfileResolver(new VoiceId("default"), 1.0, 1.0),
                bargeInDetector,
                (XiaozhiMusicActionHandler) null,
                (XiaozhiMusicPlaybackRuntime) null
        );
    }

    private TestWebSocketSession openSession(XiaozhiVoiceSessionService service) {
        return openSession(service, new TestWebSocketSession("ws-session-1"));
    }

    private TestWebSocketSession openSession(
            XiaozhiVoiceSessionService service,
            TestWebSocketSession session
    ) {
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

    private boolean awaitIdle(XiaozhiVoiceSessionService service, TestWebSocketSession session) {
        var deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (System.nanoTime() < deadline) {
            if (service.getSession(session.getId()).state() == XiaozhiVoiceSession.State.IDLE) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return service.getSession(session.getId()).state() == XiaozhiVoiceSession.State.IDLE;
    }

    private List<String> textPayloads(TestWebSocketSession session) {
        return session.getSentMessages().stream()
                .filter(TextMessage.class::isInstance)
                .map(TextMessage.class::cast)
                .map(TextMessage::getPayload)
                .toList();
    }

    private void assertStreamingPlaybackCancelledBy(
            XiaozhiClientMessage interruptingMessage,
            XiaozhiVoiceSession.State expectedState
    ) {
        var ttsClient = new BlockingTextToSpeechClient();
        var serviceWithStreamingHermes = newService(
                new FakeSpeechToTextClient(),
                new StreamingHermesClient("第一句内容很完整。", "第二句内容也完整。"),
                ttsClient
        );
        var session = openSession(serviceWithStreamingHermes);
        var turnThread = Thread.startVirtualThread(() -> runSingleTurn(serviceWithStreamingHermes, session));
        assertThat(ttsClient.awaitFirstCall()).isTrue();

        serviceWithStreamingHermes.handleText(session, interruptingMessage);
        ttsClient.releaseFirstCall();
        join(turnThread);

        assertThat(ttsClient.texts()).containsExactly("第一句内容很完整。");
        assertThat(session.getSentMessages())
                .filteredOn(BinaryMessage.class::isInstance)
                .isEmpty();
        assertThat(serviceWithStreamingHermes.getSession(session.getId()).state()).isEqualTo(expectedState);
    }

    private List<String> completedLogMessages(ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender) {
        return appender.list.stream()
                .map(event -> event.getFormattedMessage())
                .filter(message -> message.contains("xiaozhi turn completed"))
                .toList();
    }

    private long loggedMetric(String message, String metricName) {
        var prefix = metricName + "=";
        var start = message.indexOf(prefix);
        assertThat(start).as("metric %s in log %s", metricName, message).isNotNegative();
        var valueStart = start + prefix.length();
        var valueEnd = message.indexOf(",", valueStart);
        if (valueEnd < 0) {
            valueEnd = message.length();
        }
        return Long.parseLong(message.substring(valueStart, valueEnd));
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

    private static class StaticSseHermesClient implements HermesClient {

        private final String chunk;

        private StaticSseHermesClient(String chunk) {
            this.chunk = chunk;
        }

        @Override
        public HermesResponse chat(HermesRequest request, HermesClientConfig config) {
            return new HermesResponse(request.conversationId(), "");
        }

        @Override
        public Stream<String> streamChat(HermesRequest request, HermesClientConfig config) {
            return Stream.of(chunk);
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

    private static class BlockingHermesClient implements HermesClient {

        private final String chunk;
        private final java.util.concurrent.CountDownLatch streamingStarted = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch releaseStreaming = new java.util.concurrent.CountDownLatch(1);

        private BlockingHermesClient(String chunk) {
            this.chunk = chunk;
        }

        @Override
        public HermesResponse chat(HermesRequest request, HermesClientConfig config) {
            return new HermesResponse(request.conversationId(), chunk);
        }

        @Override
        public Stream<String> streamChat(HermesRequest request, HermesClientConfig config) {
            return Stream.of("event: response.output_text.delta\ndata: {\"delta\":\"" + chunk + "\"}\n\n")
                    .peek(ignored -> {
                        streamingStarted.countDown();
                        await(releaseStreaming);
                    });
        }

        private boolean awaitStreaming() {
            try {
                return streamingStarted.await(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void releaseStreaming() {
            releaseStreaming.countDown();
        }
    }

    private static class FirstChunkThenBlockingHermesClient implements HermesClient {

        private final String firstChunk;
        private final String secondChunk;
        private final java.util.concurrent.CountDownLatch secondChunkRequested = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

        private FirstChunkThenBlockingHermesClient(String firstChunk, String secondChunk) {
            this.firstChunk = firstChunk;
            this.secondChunk = secondChunk;
        }

        @Override
        public HermesResponse chat(HermesRequest request, HermesClientConfig config) {
            return new HermesResponse(request.conversationId(), firstChunk + secondChunk);
        }

        @Override
        public Stream<String> streamChat(HermesRequest request, HermesClientConfig config) {
            return Stream.of(firstChunkEvent(), secondChunkEvent())
                    .peek(chunk -> {
                        if (chunk.contains(secondChunk)) {
                            secondChunkRequested.countDown();
                            await(release);
                        }
                    });
        }

        private boolean awaitSecondChunkRequested() {
            return await(secondChunkRequested, Duration.ofSeconds(1));
        }

        private void release() {
            release.countDown();
        }

        private String firstChunkEvent() {
            return "event: response.output_text.delta\ndata: {\"delta\":\"" + firstChunk + "\"}\n\n";
        }

        private String secondChunkEvent() {
            return "event: response.output_text.delta\ndata: {\"delta\":\"" + secondChunk + "\"}\n\n";
        }
    }

    private static class BlockingFailingHermesClient implements HermesClient {

        private final java.util.concurrent.CountDownLatch streamingStarted = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch releaseFailure = new java.util.concurrent.CountDownLatch(1);

        @Override
        public HermesResponse chat(HermesRequest request, HermesClientConfig config) {
            throw new IllegalStateException("hermes unavailable");
        }

        @Override
        public Stream<String> streamChat(HermesRequest request, HermesClientConfig config) {
            return Stream.<String>of("ignored")
                    .peek(ignored -> {
                        streamingStarted.countDown();
                        await(releaseFailure);
                        throw new IllegalStateException("hermes unavailable after abort");
                    });
        }

        private boolean awaitStreaming() {
            try {
                return streamingStarted.await(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void releaseFailure() {
            releaseFailure.countDown();
        }
    }

    private static class DelayingHermesClient implements HermesClient {

        private final Duration delay;
        private final String chunk;

        private DelayingHermesClient(Duration delay, String chunk) {
            this.delay = delay;
            this.chunk = chunk;
        }

        @Override
        public HermesResponse chat(HermesRequest request, HermesClientConfig config) {
            sleep();
            return new HermesResponse(request.conversationId(), chunk);
        }

        @Override
        public Stream<String> streamChat(HermesRequest request, HermesClientConfig config) {
            return Stream.of("event: response.output_text.delta\ndata: {\"delta\":\"" + chunk + "\"}\n\n")
                    .peek(ignored -> sleep());
        }

        private void sleep() {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static class BlockingVoiceProfileResolver extends XiaozhiVoiceProfileResolver {

        private final java.util.concurrent.CountDownLatch resolvingStarted = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch releaseResolving = new java.util.concurrent.CountDownLatch(1);

        private BlockingVoiceProfileResolver() {
            super(new VoiceId("default"), 1.0, 1.0);
        }

        @Override
        public com.jzb.chatbot.voice.tts.XiaozhiVoiceProfile resolve(String deviceId) {
            resolvingStarted.countDown();
            await(releaseResolving);
            return super.resolve(deviceId);
        }

        private boolean awaitResolving() {
            try {
                return resolvingStarted.await(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void releaseResolving() {
            releaseResolving.countDown();
        }
    }

    private static class BoundaryBlockingTtsRuntime extends XiaozhiTtsRuntime {

        private final java.util.concurrent.CountDownLatch speakingStarted = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch releaseSpeaking = new java.util.concurrent.CountDownLatch(1);

        private BoundaryBlockingTtsRuntime(
                TextToSpeechClient textToSpeechClient,
                XiaozhiMessageCodec codec,
                XiaozhiServerEventFactory eventFactory
        ) {
            super(textToSpeechClient, codec, eventFactory);
        }

        @Override
        public XiaozhiTtsResult play(XiaozhiTtsRequest request) {
            speakingStarted.countDown();
            await(releaseSpeaking);
            return super.play(request);
        }

        private boolean awaitSpeaking() {
            try {
                return speakingStarted.await(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void releaseSpeaking() {
            releaseSpeaking.countDown();
        }
    }

    private static class CapturingTtsRuntime extends XiaozhiTtsRuntime {

        private XiaozhiTtsRequest request;
        private boolean calledThroughSpeakWithRuntime;

        private CapturingTtsRuntime(
                XiaozhiMessageCodec codec,
                XiaozhiServerEventFactory eventFactory
        ) {
            super(new FakeTextToSpeechClient(), codec, eventFactory);
        }

        @Override
        public XiaozhiTtsResult play(XiaozhiTtsRequest request) {
            this.request = request;
            calledThroughSpeakWithRuntime = StackWalker.getInstance()
                    .walk(frames -> frames.anyMatch(frame -> "speakWithRuntime".equals(frame.getMethodName())));
            return new XiaozhiTtsResult(true, 1, 1, false);
        }

        private XiaozhiTtsRequest request() {
            return request;
        }

        private boolean calledThroughSpeakWithRuntime() {
            return calledThroughSpeakWithRuntime;
        }
    }

    private static class ImmediateTtsRuntime extends XiaozhiTtsRuntime {

        private ImmediateTtsRuntime(
                XiaozhiMessageCodec codec,
                XiaozhiServerEventFactory eventFactory
        ) {
            super(new FakeTextToSpeechClient(), codec, eventFactory);
        }

        @Override
        public XiaozhiTtsResult play(XiaozhiTtsRequest request) {
            var playbackGeneration = request.voiceSession()
                    .beginRuntimePlayback(request.expectedPlaybackGeneration());
            if (playbackGeneration < 0) {
                return new XiaozhiTtsResult(false, 0, 0, true);
            }
            request.voiceSession().completePlayback(playbackGeneration);
            return new XiaozhiTtsResult(true, 1, 1, false);
        }
    }

    private static class RecordingCancelTtsRuntime extends XiaozhiTtsRuntime {

        private final java.util.concurrent.CountDownLatch cancelled = new java.util.concurrent.CountDownLatch(1);

        private RecordingCancelTtsRuntime(
                TextToSpeechClient textToSpeechClient,
                XiaozhiMessageCodec codec,
                XiaozhiServerEventFactory eventFactory
        ) {
            super(textToSpeechClient, codec, eventFactory);
        }

        @Override
        public void cancel(String sessionId) {
            cancelled.countDown();
            super.cancel(sessionId);
        }

        @Override
        public boolean cancel(String sessionId, long playbackGeneration) {
            cancelled.countDown();
            super.cancel(sessionId);
            return true;
        }

        private boolean awaitCancelled() {
            return await(cancelled, Duration.ofSeconds(1));
        }
    }

    private static class InterleavingCancelTtsRuntime extends XiaozhiTtsRuntime {

        private final java.util.concurrent.CountDownLatch cancelled = new java.util.concurrent.CountDownLatch(1);
        private XiaozhiVoiceSession voiceSession;
        private long playbackGeneration;

        private InterleavingCancelTtsRuntime(
                TextToSpeechClient textToSpeechClient,
                XiaozhiMessageCodec codec,
                XiaozhiServerEventFactory eventFactory
        ) {
            super(textToSpeechClient, codec, eventFactory);
        }

        private void completePlaybackOnCancel(XiaozhiVoiceSession voiceSession, long playbackGeneration) {
            this.voiceSession = voiceSession;
            this.playbackGeneration = playbackGeneration;
        }

        @Override
        public void cancel(String sessionId) {
            if (voiceSession != null) {
                voiceSession.completePlayback(playbackGeneration);
            }
            super.cancel(sessionId);
            cancelled.countDown();
        }

        @Override
        public boolean cancel(String sessionId, long playbackGeneration) {
            if (voiceSession != null) {
                voiceSession.completePlayback(this.playbackGeneration);
            }
            super.cancel(sessionId);
            cancelled.countDown();
            return true;
        }

        private boolean awaitCancelled() {
            return await(cancelled, Duration.ofSeconds(1));
        }
    }

    private static class BlockingCountingTtsRuntime extends XiaozhiTtsRuntime {

        private final java.util.concurrent.CountDownLatch firstSpeakStarted = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch releaseFirstSpeak = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.atomic.AtomicInteger speakCalls = new java.util.concurrent.atomic.AtomicInteger();

        private BlockingCountingTtsRuntime(
                XiaozhiMessageCodec codec,
                XiaozhiServerEventFactory eventFactory
        ) {
            super(new FakeTextToSpeechClient(), codec, eventFactory);
        }

        @Override
        public XiaozhiTtsResult play(XiaozhiTtsRequest request) {
            speakCalls.incrementAndGet();
            firstSpeakStarted.countDown();
            await(releaseFirstSpeak);
            return super.play(request);
        }

        private boolean awaitFirstSpeak() {
            try {
                return firstSpeakStarted.await(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void releaseFirstSpeak() {
            releaseFirstSpeak.countDown();
        }

        private int speakCalls() {
            return speakCalls.get();
        }
    }

    private static class FailingAfterListenStartTtsRuntime extends XiaozhiTtsRuntime {

        private final java.util.concurrent.CountDownLatch speakingStarted = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch releaseFailure = new java.util.concurrent.CountDownLatch(1);

        private FailingAfterListenStartTtsRuntime(
                XiaozhiMessageCodec codec,
                XiaozhiServerEventFactory eventFactory
        ) {
            super(new FakeTextToSpeechClient(), codec, eventFactory);
        }

        @Override
        public XiaozhiTtsResult play(XiaozhiTtsRequest request) {
            speakingStarted.countDown();
            await(releaseFailure);
            throw new IllegalStateException("tts unavailable");
        }

        private boolean awaitSpeaking() {
            try {
                return speakingStarted.await(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void releaseFailure() {
            releaseFailure.countDown();
        }
    }

    private static class FalseReturningTtsRuntime extends XiaozhiTtsRuntime {

        private FalseReturningTtsRuntime(
                XiaozhiMessageCodec codec,
                XiaozhiServerEventFactory eventFactory
        ) {
            super(new FakeTextToSpeechClient(), codec, eventFactory);
        }

        @Override
        public XiaozhiTtsResult play(XiaozhiTtsRequest request) {
            return new XiaozhiTtsResult(false, 0, 0, true);
        }
    }

    private static class TurnFailureInterleavingTtsRuntime extends XiaozhiTtsRuntime {

        private final java.util.concurrent.CountDownLatch turnPlaybackCleanedUp = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch releaseTurnFailure = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch notificationStarted = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch releaseNotification = new java.util.concurrent.CountDownLatch(1);

        private TurnFailureInterleavingTtsRuntime(
                XiaozhiMessageCodec codec,
                XiaozhiServerEventFactory eventFactory
        ) {
            super(new FakeTextToSpeechClient(), codec, eventFactory);
        }

        @Override
        public XiaozhiTtsResult play(XiaozhiTtsRequest request) {
            if (request.expectedPlaybackGeneration() != null) {
                return speakNotification(request);
            }
            var playbackGeneration = request.voiceSession().beginRuntimePlayback();
            request.voiceSession().completePlayback(playbackGeneration);
            turnPlaybackCleanedUp.countDown();
            await(releaseTurnFailure);
            throw new IllegalStateException("tts unavailable");
        }

        private XiaozhiTtsResult speakNotification(XiaozhiTtsRequest request) {
            var playbackGeneration = request.voiceSession()
                    .beginRuntimePlayback(request.expectedPlaybackGeneration());
            if (playbackGeneration < 0) {
                return new XiaozhiTtsResult(false, 0, 0, true);
            }
            notificationStarted.countDown();
            await(releaseNotification);
            request.voiceSession().completePlayback(playbackGeneration);
            return new XiaozhiTtsResult(true, 1, 1, false);
        }

        private boolean awaitTurnPlaybackCleanedUp() {
            try {
                return turnPlaybackCleanedUp.await(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void releaseTurnFailure() {
            releaseTurnFailure.countDown();
        }

        private boolean awaitNotificationStarted() {
            try {
                return notificationStarted.await(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void releaseNotification() {
            releaseNotification.countDown();
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

    private static class CapturingMusicPlaybackRuntime extends XiaozhiMusicPlaybackRuntime {

        private final java.util.ArrayList<String> playedTitles = new java.util.ArrayList<>();
        private final java.util.ArrayList<String> playedPausedForTtsTitles = new java.util.ArrayList<>();
        private final java.util.ArrayList<String> events = new java.util.ArrayList<>();

        private CapturingMusicPlaybackRuntime() {
            super(null, null, null, new com.jzb.chatbot.voice.music.XiaozhiMusicPlaybackProperties(
                    true,
                    "ffmpeg",
                    Duration.ofSeconds(3),
                    Duration.ofMinutes(5),
                    java.util.Set.of("example.com")
            ));
        }

        @Override
        public void play(XiaozhiMusicPlaybackRequest request) {
            playedTitles.add(request.title());
        }

        @Override
        public void playPausedForTts(XiaozhiMusicPlaybackRequest request) {
            playedPausedForTtsTitles.add(request.title());
        }

        @Override
        public void stop(String deviceId) {
            events.add("stop:" + deviceId);
        }

        private List<String> playedTitles() {
            return List.copyOf(playedTitles);
        }

        private List<String> playedPausedForTtsTitles() {
            return List.copyOf(playedPausedForTtsTitles);
        }

        private List<String> events() {
            return List.copyOf(events);
        }
    }

    private static class CapturingOptionsTextToSpeechClient implements TextToSpeechClient {

        private final java.util.ArrayList<TextToSpeechOptions> options = new java.util.ArrayList<>();

        @Override
        public List<ByteBuffer> synthesize(String text, VoiceId voiceId) {
            options.add(new TextToSpeechOptions(voiceId, 1.0, 1.0));
            return List.of(ByteBuffer.wrap(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        @Override
        public List<ByteBuffer> synthesize(String text, TextToSpeechOptions options) {
            this.options.add(options);
            return List.of(ByteBuffer.wrap(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        private List<TextToSpeechOptions> options() {
            return List.copyOf(options);
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

    private static class BlockingFailingTextToSpeechClient implements TextToSpeechClient {

        private final java.util.concurrent.CountDownLatch synthesisStarted = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch releaseFailure = new java.util.concurrent.CountDownLatch(1);

        @Override
        public List<ByteBuffer> synthesize(String text, VoiceId voiceId) {
            synthesisStarted.countDown();
            await(releaseFailure);
            throw new IllegalStateException("tts unavailable");
        }

        private boolean awaitSynthesis() {
            try {
                return synthesisStarted.await(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void releaseFailure() {
            releaseFailure.countDown();
        }
    }

    private record FixedSpeechToTextClient(String text) implements SpeechToTextClient {

        @Override
        public String transcribe(List<ByteBuffer> audioFrames) {
            return text;
        }
    }

    private static class CountingSpeechToTextClient implements SpeechToTextClient {

        private final String text;
        private int calls;

        private CountingSpeechToTextClient(String text) {
            this.text = text;
        }

        @Override
        public String transcribe(List<ByteBuffer> audioFrames) {
            calls++;
            return text;
        }

        private int calls() {
            return calls;
        }
    }

    private static class BlockingSpeechToTextClient implements SpeechToTextClient {

        private final String text;
        private final java.util.concurrent.CountDownLatch transcriptionStarted = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch releaseTranscription = new java.util.concurrent.CountDownLatch(1);

        private BlockingSpeechToTextClient(String text) {
            this.text = text;
        }

        @Override
        public String transcribe(List<ByteBuffer> audioFrames) {
            transcriptionStarted.countDown();
            await(releaseTranscription);
            return text;
        }

        private boolean awaitTranscription() {
            try {
                return transcriptionStarted.await(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void releaseTranscription() {
            releaseTranscription.countDown();
        }
    }

    private static class BlockingFailingSpeechToTextClient implements SpeechToTextClient {

        private final java.util.concurrent.CountDownLatch transcriptionStarted = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch releaseFailure = new java.util.concurrent.CountDownLatch(1);

        @Override
        public String transcribe(List<ByteBuffer> audioFrames) {
            transcriptionStarted.countDown();
            await(releaseFailure);
            throw new IllegalStateException("asr unavailable");
        }

        private boolean awaitTranscription() {
            try {
                return transcriptionStarted.await(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void releaseFailure() {
            releaseFailure.countDown();
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

    private static class SttCallbackSession extends TestWebSocketSession {

        private Runnable onSttSent = () -> {
        };

        private SttCallbackSession(String id) {
            super(id);
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            super.sendMessage(message);
            if (message instanceof TextMessage textMessage
                    && textMessage.getPayload().contains("\"type\":\"stt\"")) {
                onSttSent.run();
            }
        }

        private void onSttSent(Runnable onSttSent) {
            this.onSttSent = onSttSent;
        }
    }

    private static class BlockingTtsFailureSession extends TestWebSocketSession {

        private final java.util.concurrent.CountDownLatch ttsFailureSendStarted =
                new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch releaseTtsFailureSend =
                new java.util.concurrent.CountDownLatch(1);

        private BlockingTtsFailureSession(String id) {
            super(id);
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            if (message instanceof TextMessage textMessage
                    && textMessage.getPayload().contains("\"type\":\"error\"")
                    && textMessage.getPayload().contains("\"code\":\"tts_failed\"")) {
                ttsFailureSendStarted.countDown();
                await(releaseTtsFailureSend);
            }
            super.sendMessage(message);
        }

        private boolean awaitTtsFailureSend() {
            return await(ttsFailureSendStarted, Duration.ofSeconds(1));
        }

        private void releaseTtsFailureSend() {
            releaseTtsFailureSend.countDown();
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

    private static class RecordingStreamingSpeechToTextClient implements StreamingSpeechToTextClient {

        private final String text;
        private final String provider;
        private final java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();

        private RecordingStreamingSpeechToTextClient(String text, String provider) {
            this.text = text;
            this.provider = provider;
        }

        @Override
        public SpeechToTextResult transcribe(SpeechToTextAudioStream audioStream) {
            callCount.incrementAndGet();
            var chunk = audioStream.take(Duration.ofMillis(100));
            while (!audioStream.isEnd(chunk)) {
                chunk = audioStream.take(Duration.ofMillis(100));
            }
            return new SpeechToTextResult(text, provider, 0);
        }

        private int callCount() {
            return callCount.get();
        }
    }

    private static class ImmediateStreamingSpeechToTextClient implements StreamingSpeechToTextClient {

        private final String text;
        private final String provider;
        private final java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();

        private ImmediateStreamingSpeechToTextClient(String text, String provider) {
            this.text = text;
            this.provider = provider;
        }

        @Override
        public SpeechToTextResult transcribe(SpeechToTextAudioStream audioStream) {
            callCount.incrementAndGet();
            return new SpeechToTextResult(text, provider, 0);
        }

        private int callCount() {
            return callCount.get();
        }
    }

    private static class RecordingBargeInStreamingSpeechToTextClient implements StreamingSpeechToTextClient {

        private final java.util.concurrent.CountDownLatch audioChunkReceived = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger pcmBytes = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public SpeechToTextResult transcribe(SpeechToTextAudioStream audioStream) {
            callCount.incrementAndGet();
            var chunk = audioStream.take(Duration.ofSeconds(1));
            if (!audioStream.isEnd(chunk) && chunk.length > 0) {
                pcmBytes.addAndGet(chunk.length);
                audioChunkReceived.countDown();
            }
            while (!audioStream.isEnd(chunk)) {
                chunk = audioStream.take(Duration.ofMillis(100));
            }
            return new SpeechToTextResult("等一下", "streaming-provider", 0);
        }

        private int callCount() {
            return callCount.get();
        }

        private boolean awaitAudioChunk() {
            return await(audioChunkReceived, Duration.ofSeconds(1));
        }

        private int pcmBytes() {
            return pcmBytes.get();
        }
    }

    private static class RecordingStreamingTextToSpeechClient implements StreamingTextToSpeechClient {

        private final List<String> texts = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public StreamingTextToSpeechSession open(
                TextToSpeechOptions options,
                StreamingTextToSpeechListener listener
        ) {
            listener.onReady();
            return new RecordingStreamingTextToSpeechSession(listener);
        }

        private List<String> texts() {
            return List.copyOf(texts);
        }

        private final class RecordingStreamingTextToSpeechSession implements StreamingTextToSpeechSession {

            private final StreamingTextToSpeechListener listener;
            private volatile boolean completed;

            private RecordingStreamingTextToSpeechSession(StreamingTextToSpeechListener listener) {
                this.listener = listener;
            }

            @Override
            public void sendText(String text) {
                texts.add(text);
                listener.onAudioFrame(ByteBuffer.wrap(new byte[] {1, 2, 3}));
            }

            @Override
            public void complete() {
                completed = true;
                listener.onCompleted();
            }

            @Override
            public void cancel() {
                completed = true;
                listener.onCompleted();
            }

            @Override
            public boolean awaitFinal(Duration timeout) {
                return completed;
            }

            @Override
            public void close() {
                cancel();
            }
        }
    }

    private static class FailingSendStreamingTextToSpeechClient implements StreamingTextToSpeechClient {

        @Override
        public StreamingTextToSpeechSession open(
                TextToSpeechOptions options,
                StreamingTextToSpeechListener listener
        ) {
            listener.onReady();
            return new FailingSendStreamingTextToSpeechSession();
        }
    }

    private static class FailingSendStreamingTextToSpeechSession implements StreamingTextToSpeechSession {

        @Override
        public void sendText(String text) {
            throw new IllegalStateException("streaming send unavailable");
        }

        @Override
        public void complete() {
        }

        @Override
        public void cancel() {
        }

        @Override
        public boolean awaitFinal(Duration timeout) {
            return false;
        }

        @Override
        public void close() {
            cancel();
        }
    }

    private static class EndAwareStreamingSpeechToTextClient implements StreamingSpeechToTextClient {

        private final java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch ended = new java.util.concurrent.CountDownLatch(1);

        @Override
        public SpeechToTextResult transcribe(SpeechToTextAudioStream audioStream) {
            started.countDown();
            var chunk = audioStream.take(Duration.ofMillis(100));
            while (!audioStream.isEnd(chunk)) {
                chunk = audioStream.take(Duration.ofMillis(100));
            }
            ended.countDown();
            return SpeechToTextResult.blank("streaming-provider");
        }

        private boolean awaitStarted() {
            return await(started, Duration.ofSeconds(1));
        }

        private boolean awaitEnd() {
            return await(ended, Duration.ofSeconds(1));
        }
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean await(java.util.concurrent.CountDownLatch latch, Duration timeout) {
        try {
            return latch.await(timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(Duration.ofSeconds(1));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        assertThat(thread.isAlive()).as("thread %s expected to finish", thread.getName()).isFalse();
    }
}
