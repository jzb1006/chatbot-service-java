package com.jzb.chatbot.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jzb.chatbot.common.id.VoiceId;
import com.jzb.chatbot.speech.StreamingTextToSpeechClient;
import com.jzb.chatbot.speech.StreamingTextToSpeechListener;
import com.jzb.chatbot.speech.StreamingTextToSpeechSession;
import com.jzb.chatbot.speech.TextToSpeechClient;
import com.jzb.chatbot.speech.TextToSpeechOptions;
import com.jzb.chatbot.voice.music.XiaozhiMusicPlaybackCoordinator;
import com.jzb.chatbot.voice.music.XiaozhiMusicPlaybackState;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import com.jzb.chatbot.voice.protocol.XiaozhiServerEventFactory;
import com.jzb.chatbot.voice.tts.XiaozhiStreamingTtsRequest;
import com.jzb.chatbot.voice.tts.XiaozhiTtsSentenceSink;
import com.jzb.chatbot.voice.tts.XiaozhiTtsRequest;
import com.jzb.chatbot.voice.tts.XiaozhiTtsRuntime;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;

class XiaozhiTtsRuntimeTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldSendTtsStopOnlyOnceWhenPlaybackFinishes() {
        var runtime = newRuntimeWithFakeTts();
        var session = openSession();
        var voiceSession = new XiaozhiVoiceSession(session.getId());

        assertThat(runtime.speak(new XiaozhiTtsRequest(
                session, voiceSession, List.of("你好"), TextToSpeechOptions.defaults()
        ))).isTrue();

        assertThat(textPayloads(session))
                .filteredOn(payload -> payload.contains("\"type\":\"tts\"") && payload.contains("\"state\":\"stop\""))
                .hasSize(1);
        assertThat(voiceSession.state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
        assertThat(voiceSession.cancelPlayback()).isNull();
    }

    @Test
    void shouldReturnPlaybackMetricsWhenRuntimePlaysSentences() {
        var runtime = newRuntime(new SequencedFrameTextToSpeechClient(2, 3));
        var session = openSession();
        var voiceSession = new XiaozhiVoiceSession(session.getId());

        var result = runtime.play(new XiaozhiTtsRequest(
                session,
                voiceSession,
                List.of("第一句内容。", "第二句内容。"),
                TextToSpeechOptions.defaults()
        ));

        assertThat(result.played()).isTrue();
        assertThat(result.sentenceCount()).isEqualTo(2);
        assertThat(result.ttsFrames()).isEqualTo(5);
        assertThat(result.cancelled()).isFalse();
    }

    @Test
    void shouldCancelQueuedSentencesAfterAbort() throws Exception {
        var ttsClient = new BlockingTextToSpeechClient();
        var runtime = newRuntime(ttsClient);
        var session = new TimingWebSocketSession("ws-session-1");
        var voiceSession = new XiaozhiVoiceSession(session.getId());

        var thread = Thread.startVirtualThread(() -> runtime.speak(new XiaozhiTtsRequest(
                session,
                voiceSession,
                List.of("第一句内容很完整。", "第二句内容也完整。"),
                TextToSpeechOptions.defaults()
        )));

        assertThat(ttsClient.awaitFirstCall()).isTrue();
        runtime.cancel(session.getId());
        var releasedAt = System.nanoTime();
        ttsClient.releaseFirstCall();
        thread.join(2_000L);

        assertThat(thread.isAlive()).isFalse();
        assertThat(ttsClient.texts()).containsExactly("第一句内容很完整。");
        assertThat(session.stopSentAt()).isPositive();
        assertThat(TimeUnit.NANOSECONDS.toMillis(session.stopSentAt() - releasedAt)).isLessThan(100L);
    }

    @Test
    void shouldIgnoreCancelWhenPlaybackGenerationDoesNotMatch() throws Exception {
        var ttsClient = new BlockingTextToSpeechClient();
        var runtime = newRuntime(ttsClient);
        var session = new TimingWebSocketSession("ws-session-1");
        var voiceSession = new XiaozhiVoiceSession(session.getId());
        var played = new AtomicBoolean();

        var thread = Thread.startVirtualThread(() -> played.set(runtime.speak(new XiaozhiTtsRequest(
                session,
                voiceSession,
                List.of("第一句内容很完整。", "第二句内容也完整。"),
                TextToSpeechOptions.defaults()
        ))));

        assertThat(ttsClient.awaitFirstCall()).isTrue();
        assertThat(runtime.cancel(session.getId(), 2L)).isFalse();
        ttsClient.releaseFirstCall();
        thread.join(2_000L);

        assertThat(thread.isAlive()).isFalse();
        assertThat(played.get()).isTrue();
        assertThat(ttsClient.texts()).containsExactly("第一句内容很完整。", "第二句内容也完整。");
    }

    @Test
    void shouldPlaySentencesInOrder() {
        var ttsClient = new RecordingTextToSpeechClient();
        var runtime = newRuntime(ttsClient);
        var session = openSession();
        var voiceSession = new XiaozhiVoiceSession(session.getId());

        assertThat(runtime.speak(new XiaozhiTtsRequest(
                session,
                voiceSession,
                List.of("第一句内容。", "第二句内容。"),
                TextToSpeechOptions.defaults()
        ))).isTrue();

        assertThat(ttsClient.texts()).containsExactly("第一句内容。", "第二句内容。");
        assertThat(sentenceStartTexts(session)).containsExactly("第一句内容。", "第二句内容。");
    }

    @Test
    void shouldPauseMusicDuringTtsAndResumeAfterTts() {
        var musicCoordinator = new CapturingMusicPlaybackCoordinator();
        var runtime = new XiaozhiTtsRuntime(
                new FakeTextToSpeechClient(),
                new XiaozhiMessageCodec(OBJECT_MAPPER),
                new XiaozhiServerEventFactory(OBJECT_MAPPER),
                musicCoordinator
        );
        var session = openSession();
        var voiceSession = new XiaozhiVoiceSession(session.getId());

        runtime.speak(new XiaozhiTtsRequest(
                session,
                voiceSession,
                List.of("你好。"),
                TextToSpeechOptions.defaults()
        ));

        assertThat(musicCoordinator.events()).containsExactly(
                "pause:ws-session-1:TTS",
                "resume:ws-session-1:TTS"
        );
    }

    @Test
    void shouldWaitBetweenSentenceAudioWithoutSendingEmptyFrames() {
        var ttsClient = new SequencedFrameTextToSpeechClient(4, 1);
        var runtime = newRuntime(ttsClient);
        var session = new TimingWebSocketSession("ws-session-1");
        var voiceSession = new XiaozhiVoiceSession(session.getId());

        assertThat(runtime.speak(new XiaozhiTtsRequest(
                session,
                voiceSession,
                List.of("第一句内容。", "第二句内容。"),
                TextToSpeechOptions.defaults()
        ))).isTrue();

        assertThat(binaryMessages(session))
                .hasSize(5)
                .allSatisfy(message -> assertThat(message.getPayloadLength()).isGreaterThan(0));
        assertThat(session.binarySentAt()).hasSize(5);
        var gapBetweenSentences = session.binarySentAt().get(4) - session.binarySentAt().get(3);
        assertThat(gapBetweenSentences)
                .isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(330L))
                .isLessThan(TimeUnit.MILLISECONDS.toNanos(405L));
    }

    @Test
    void shouldDelayStopAfterNaturalPlaybackFinishes() {
        var runtime = newRuntimeWithFakeTts();
        var session = new TimingWebSocketSession("ws-session-1");
        var voiceSession = new XiaozhiVoiceSession(session.getId());

        assertThat(runtime.speak(new XiaozhiTtsRequest(
                session,
                voiceSession,
                List.of("你好"),
                TextToSpeechOptions.defaults()
        ))).isTrue();

        assertThat(session.binarySentAt()).isNotEmpty();
        assertThat(session.stopSentAt()).isPositive();
        assertThat(session.stopSentAt() - session.lastBinarySentAt())
                .isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(100L));
    }

    @Test
    void shouldStopPromptlyWhenCancelledDuringStopDelay() throws Exception {
        var runtime = newRuntimeWithFakeTts();
        var session = new TimingWebSocketSession("ws-session-1");
        var voiceSession = new XiaozhiVoiceSession(session.getId());

        var thread = Thread.startVirtualThread(() -> runtime.speak(new XiaozhiTtsRequest(
                session,
                voiceSession,
                List.of("你好"),
                TextToSpeechOptions.defaults()
        )));

        assertThat(session.awaitFirstBinary()).isTrue();
        runtime.cancel(session.getId());
        var cancelledAt = System.nanoTime();
        thread.join(2_000L);

        assertThat(thread.isAlive()).isFalse();
        assertThat(session.stopSentAt()).isPositive();
        assertThat(TimeUnit.NANOSECONDS.toMillis(session.stopSentAt() - cancelledAt)).isLessThan(110L);
        assertThat(binaryMessages(session))
                .hasSize(1)
                .allSatisfy(message -> assertThat(message.getPayloadLength()).isGreaterThan(0));
    }

    @Test
    void shouldNotSendNextSentenceStartWhenCancelledDuringSentenceGap() throws Exception {
        var ttsClient = new SequencedFrameTextToSpeechClient(4, 1);
        var runtime = newRuntime(ttsClient);
        var session = new TimingWebSocketSession("ws-session-1");
        var voiceSession = new XiaozhiVoiceSession(session.getId());

        var thread = Thread.startVirtualThread(() -> runtime.speak(new XiaozhiTtsRequest(
                session,
                voiceSession,
                List.of("第一句内容。", "第二句内容。"),
                TextToSpeechOptions.defaults()
        )));

        assertThat(session.awaitBinaryCount(4)).isTrue();
        Thread.sleep(40L);
        runtime.cancel(session.getId());
        var cancelledAt = System.nanoTime();
        thread.join(2_000L);

        assertThat(thread.isAlive()).isFalse();
        assertThat(sentenceStartTexts(session)).containsExactly("第一句内容。");
        assertThat(binaryMessages(session)).hasSize(4);
        assertThat(session.stopSentAt()).isPositive();
        assertThat(TimeUnit.NANOSECONDS.toMillis(session.stopSentAt() - cancelledAt)).isLessThan(180L);
    }

    @Test
    void shouldNotSendNextSentenceStartWhenCancellationGuardTripsDuringSentenceGap() throws Exception {
        var cancellationRequested = new AtomicBoolean();
        var ttsClient = new SequencedFrameTextToSpeechClient(4, 1);
        var runtime = newRuntime(ttsClient);
        var session = new TimingWebSocketSession("ws-session-1");
        var voiceSession = new XiaozhiVoiceSession(session.getId());

        var thread = Thread.startVirtualThread(() -> runtime.speak(new XiaozhiTtsRequest(
                session,
                voiceSession,
                List.of("第一句内容。", "第二句内容。"),
                TextToSpeechOptions.defaults(),
                cancellationRequested::get
        )));

        assertThat(session.awaitBinaryCount(4)).isTrue();
        Thread.sleep(40L);
        cancellationRequested.set(true);
        var cancelledAt = System.nanoTime();
        thread.join(2_000L);

        assertThat(thread.isAlive()).isFalse();
        assertThat(sentenceStartTexts(session)).containsExactly("第一句内容。");
        assertThat(binaryMessages(session)).hasSize(4);
        assertThat(session.stopSentAt()).isPositive();
        assertThat(TimeUnit.NANOSECONDS.toMillis(session.stopSentAt() - cancelledAt)).isLessThan(180L);
    }

    @Test
    void shouldNotSendSentenceStartOrAudioWhenCancellationGuardTripsAfterSynthesis() {
        var cancellationRequested = new AtomicBoolean();
        var ttsClient = new CancellingTextToSpeechClient(cancellationRequested);
        var runtime = newRuntime(ttsClient);
        var session = openSession();
        var voiceSession = new XiaozhiVoiceSession(session.getId());

        var result = runtime.play(new XiaozhiTtsRequest(
                session,
                voiceSession,
                List.of("不会播放"),
                TextToSpeechOptions.defaults(),
                cancellationRequested::get
        ));

        assertThat(result.played()).isFalse();
        assertThat(result.sentenceCount()).isZero();
        assertThat(result.ttsFrames()).isZero();
        assertThat(result.cancelled()).isTrue();
        assertThat(ttsClient.texts()).containsExactly("不会播放");
        assertThat(sentenceStartTexts(session)).isEmpty();
        assertThat(binaryMessages(session)).isEmpty();
        assertThat(textPayloads(session))
                .filteredOn(payload -> payload.contains("\"type\":\"tts\"") && payload.contains("\"state\":\"stop\""))
                .hasSize(1);
    }

    @Test
    void shouldStopPromptlyWhenCancellationGuardTripsDuringStopDelay() throws Exception {
        var cancellationRequested = new AtomicBoolean();
        var runtime = newRuntimeWithFakeTts();
        var session = new TimingWebSocketSession("ws-session-1");
        var voiceSession = new XiaozhiVoiceSession(session.getId());

        var thread = Thread.startVirtualThread(() -> runtime.speak(new XiaozhiTtsRequest(
                session,
                voiceSession,
                List.of("你好"),
                TextToSpeechOptions.defaults(),
                cancellationRequested::get
        )));

        assertThat(session.awaitFirstBinary()).isTrue();
        cancellationRequested.set(true);
        var cancelledAt = System.nanoTime();
        thread.join(2_000L);

        assertThat(thread.isAlive()).isFalse();
        assertThat(session.stopSentAt()).isPositive();
        assertThat(TimeUnit.NANOSECONDS.toMillis(session.stopSentAt() - cancelledAt)).isLessThan(80L);
        assertThat(binaryMessages(session))
                .hasSize(1)
                .allSatisfy(message -> assertThat(message.getPayloadLength()).isGreaterThan(0));
    }

    @Test
    void shouldKeepCleanupAndOriginalExceptionWhenTtsAndStopFail() {
        var runtime = newRuntime(new FailingTextToSpeechClient());
        var session = new StopFailingSession("ws-session-1");
        var voiceSession = new XiaozhiVoiceSession(session.getId());

        assertThatThrownBy(() -> runtime.speak(new XiaozhiTtsRequest(
                session, voiceSession, List.of("会失败"), TextToSpeechOptions.defaults()
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("tts boom");

        assertThat(textPayloads(session))
                .filteredOn(payload -> payload.contains("\"type\":\"tts\"") && payload.contains("\"state\":\"stop\""))
                .isEmpty();
        assertThat(voiceSession.state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
        assertThat(voiceSession.cancelPlayback()).isNull();
    }

    @Test
    void shouldIgnoreStopFailureWhenPlaybackFinishes() {
        var runtime = newRuntimeWithFakeTts();
        var session = new StopFailingSession("ws-session-1");
        var voiceSession = new XiaozhiVoiceSession(session.getId());

        assertThat(runtime.speak(new XiaozhiTtsRequest(
                session, voiceSession, List.of("你好"), TextToSpeechOptions.defaults()
        ))).isTrue();

        assertThat(voiceSession.state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
        assertThat(voiceSession.cancelPlayback()).isNull();
    }

    @Test
    void shouldIgnoreClosedWebSocketRuntimeFailureWhenSendingStop() {
        var runtime = newRuntimeWithFakeTts();
        var session = new ClosedStopFailingSession("ws-session-1");
        var voiceSession = new XiaozhiVoiceSession(session.getId());

        assertThat(runtime.speak(new XiaozhiTtsRequest(
                session, voiceSession, List.of("回头再聊"), TextToSpeechOptions.defaults()
        ))).isTrue();

        assertThat(session.isOpen()).isFalse();
        assertThat(voiceSession.state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
        assertThat(voiceSession.cancelPlayback()).isNull();
    }

    @Test
    void shouldSkipNullAndBlankSentences() {
        var ttsClient = new RecordingTextToSpeechClient();
        var runtime = newRuntime(ttsClient);
        var session = openSession();
        var voiceSession = new XiaozhiVoiceSession(session.getId());

        assertThat(runtime.speak(new XiaozhiTtsRequest(
                session,
                voiceSession,
                Arrays.asList(null, "", "  ", "\n\t", "有效句子"),
                TextToSpeechOptions.defaults()
        ))).isTrue();

        assertThat(ttsClient.texts()).containsExactly("有效句子");
    }

    @Test
    void shouldReturnFalseWhenNoEffectiveSentenceIsPlayed() {
        var ttsClient = new RecordingTextToSpeechClient();
        var runtime = newRuntime(ttsClient);
        var session = openSession();
        var voiceSession = new XiaozhiVoiceSession(session.getId());

        assertThat(runtime.speak(new XiaozhiTtsRequest(
                session, voiceSession, Arrays.asList(null, "", "  "), TextToSpeechOptions.defaults()
        ))).isFalse();

        assertThat(ttsClient.texts()).isEmpty();
        assertThat(voiceSession.state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
        assertThat(voiceSession.cancelPlayback()).isNull();
    }

    @Test
    void shouldNotSendStartOrSynthesizeWhenCancellationGuardIsAlreadyRequested() {
        var ttsClient = new RecordingTextToSpeechClient();
        var runtime = newRuntime(ttsClient);
        var session = openSession();
        var voiceSession = new XiaozhiVoiceSession(session.getId());

        assertThat(runtime.speak(new XiaozhiTtsRequest(
                session,
                voiceSession,
                List.of("不会播放"),
                TextToSpeechOptions.defaults(),
                () -> true
        ))).isFalse();

        assertThat(ttsClient.texts()).isEmpty();
        assertThat(textPayloads(session))
                .noneSatisfy(payload -> assertThat(payload)
                        .containsAnyOf("\"type\":\"llm\"", "\"state\":\"start\""));
        assertThat(voiceSession.state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
        assertThat(voiceSession.cancelPlayback()).isNull();
    }

    @Test
    void shouldRejectUnownedRuntimePlaybackWhenSessionIsAlreadySpeaking() {
        var ttsClient = new RecordingTextToSpeechClient();
        var runtime = newRuntime(ttsClient);
        var session = openSession();
        var voiceSession = new XiaozhiVoiceSession(session.getId());
        voiceSession.markSpeaking();

        assertThat(runtime.speak(new XiaozhiTtsRequest(
                session,
                voiceSession,
                List.of("不能复用旧 owner"),
                TextToSpeechOptions.defaults()
        ))).isFalse();

        assertThat(ttsClient.texts()).isEmpty();
        assertThat(textPayloads(session))
                .noneSatisfy(payload -> assertThat(payload)
                        .containsAnyOf("\"type\":\"llm\"", "\"state\":\"start\"", "\"state\":\"sentence_start\""));
        assertThat(binaryMessages(session)).isEmpty();
        assertThat(voiceSession.state()).isEqualTo(XiaozhiVoiceSession.State.SPEAKING);
    }

    @Test
    void shouldSendStreamingFramesAfterSentenceStart() throws Exception {
        var runtime = newRuntimeWithStreamingTts(new PushStreamingTextToSpeechClient());
        var session = openSession();
        var voiceSession = new XiaozhiVoiceSession(session.getId());

        var thread = Thread.startVirtualThread(() -> runtime.playStreaming(new XiaozhiStreamingTtsRequest(
                session,
                voiceSession,
                TextToSpeechOptions.defaults(),
                () -> false
        ), sentenceSink -> {
            sentenceSink.accept("第一句内容。");
            sentenceSink.complete();
        }));
        thread.join(2_000L);

        assertThat(thread.isAlive()).isFalse();
        assertThat(sentenceStartTexts(session)).containsExactly("第一句内容。");
        assertThat(binaryMessages(session)).isNotEmpty();
        assertThat(textPayloads(session))
                .filteredOn(payload -> payload.contains("\"type\":\"tts\"") && payload.contains("\"state\":\"stop\""))
                .hasSize(1);
    }

    @Test
    void shouldSkipStreamingStopWhenWebSocketAlreadyClosed() {
        var runtime = newRuntimeWithStreamingTts(new PushStreamingTextToSpeechClient());
        TimingWebSocketSession session = new ClosingOnBinaryWebSocketSession("ws-session-1");
        var voiceSession = new XiaozhiVoiceSession(session.getId());

        var result = runtime.playStreaming(new XiaozhiStreamingTtsRequest(
                session,
                voiceSession,
                TextToSpeechOptions.defaults(),
                () -> false
        ), sentenceSink -> {
            sentenceSink.accept("回头再聊");
            sentenceSink.complete();
        });

        assertThat(session.isOpen()).isFalse();
        assertThat(result.played()).isTrue();
        assertThat(session.stopSentAt()).isZero();
        assertThat(textPayloads(session))
                .filteredOn(payload -> payload.contains("\"type\":\"tts\"") && payload.contains("\"state\":\"stop\""))
                .isEmpty();
        assertThat(voiceSession.state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
        assertThat(voiceSession.cancelPlayback()).isNull();
    }

    @Test
    void shouldCancelStreamingTtsSessionWhenRuntimeIsCancelled() throws Exception {
        var streamingClient = new BlockingStreamingTextToSpeechClient();
        var runtime = newRuntimeWithStreamingTts(streamingClient);
        var session = new TimingWebSocketSession("ws-session-1");
        var voiceSession = new XiaozhiVoiceSession(session.getId());

        var thread = Thread.startVirtualThread(() -> runtime.playStreaming(new XiaozhiStreamingTtsRequest(
                session,
                voiceSession,
                TextToSpeechOptions.defaults(),
                () -> false
        ), sentenceSink -> {
            sentenceSink.accept("第一句内容。");
            streamingClient.awaitFirstText();
        }));

        assertThat(streamingClient.awaitFirstText()).isTrue();
        runtime.cancel(session.getId());
        thread.join(2_000L);

        assertThat(thread.isAlive()).isFalse();
        assertThat(streamingClient.cancelled()).isTrue();
        assertThat(session.stopSentAt()).isPositive();
    }

    @Test
    void shouldNotLetOldStreamingCleanupRemoveNewActiveStreamingSession() throws Exception {
        var streamingClient = new BlockingStreamingTextToSpeechClient();
        var runtime = newRuntimeWithStreamingTts(streamingClient);
        var session = new TimingWebSocketSession("ws-session-1");
        var oldVoiceSession = new XiaozhiVoiceSession(session.getId());

        var oldThread = Thread.startVirtualThread(() -> runtime.playStreaming(new XiaozhiStreamingTtsRequest(
                session,
                oldVoiceSession,
                TextToSpeechOptions.defaults(),
                () -> false
        ), sentenceSink -> {
            sentenceSink.accept("上一轮。");
            streamingClient.awaitFirstTextCount(1);
        }));
        assertThat(streamingClient.awaitFirstTextCount(1)).isTrue();

        var newVoiceSession = new XiaozhiVoiceSession(session.getId());
        var newThread = Thread.startVirtualThread(() -> runtime.playStreaming(new XiaozhiStreamingTtsRequest(
                session,
                newVoiceSession,
                TextToSpeechOptions.defaults(),
                () -> false
        ), sentenceSink -> {
            sentenceSink.accept("新一轮。");
            streamingClient.awaitCancelCount(1);
        }));
        assertThat(streamingClient.awaitFirstTextCount(2)).isTrue();
        streamingClient.cancelFirstSession();
        oldThread.join(2_000L);

        runtime.cancel(session.getId());
        newThread.join(2_000L);

        assertThat(oldThread.isAlive()).isFalse();
        assertThat(newThread.isAlive()).isFalse();
        assertThat(streamingClient.cancelCount()).isEqualTo(2);
    }

    @Test
    void shouldFallbackToSynchronousTtsWhenStreamingProviderFailsBeforeAudio() {
        var streamingClient = new FailingStreamingTextToSpeechClient();
        var fallbackClient = new RecordingTextToSpeechClient();
        var runtime = newRuntimeWithStreamingTts(streamingClient, fallbackClient);
        var session = openSession();
        var voiceSession = new XiaozhiVoiceSession(session.getId());

        var result = runtime.playStreaming(new XiaozhiStreamingTtsRequest(
                session,
                voiceSession,
                TextToSpeechOptions.defaults(),
                () -> false
        ), sentenceSink -> {
            sentenceSink.accept("第一句内容。");
            sentenceSink.complete();
        });

        assertThat(result.played()).isTrue();
        assertThat(fallbackClient.texts()).containsExactly("第一句内容。");
        assertThat(sentenceStartTexts(session)).containsExactly("第一句内容。");
        assertThat(binaryMessages(session)).isNotEmpty();
    }

    @Test
    void shouldCompleteStreamingPlaybackWhenFinalTimesOutAfterAudio() {
        var runtime = newRuntimeWithStreamingTts(new FinalTimeoutAfterAudioStreamingTextToSpeechClient());
        var session = openSession();
        var voiceSession = new XiaozhiVoiceSession(session.getId());

        var result = runtime.playStreaming(new XiaozhiStreamingTtsRequest(
                session,
                voiceSession,
                TextToSpeechOptions.defaults(),
                () -> false
        ), sentenceSink -> {
            sentenceSink.accept("第一句内容。");
            sentenceSink.complete();
        });

        assertThat(result.played()).isTrue();
        assertThat(result.ttsFrames()).isEqualTo(1);
        assertThat(result.cancelled()).isFalse();
        assertThat(binaryMessages(session)).isNotEmpty();
        assertThat(textPayloads(session))
                .filteredOn(payload -> payload.contains("\"type\":\"tts\"") && payload.contains("\"state\":\"stop\""))
                .hasSize(1);
        assertThat(voiceSession.state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
    }

    @Test
    void shouldStillSendStopWhenStreamingSessionCloseFails() {
        var runtime = newRuntimeWithStreamingTts(new CloseFailingStreamingTextToSpeechClient());
        var session = openSession();
        var voiceSession = new XiaozhiVoiceSession(session.getId());

        var result = runtime.playStreaming(new XiaozhiStreamingTtsRequest(
                session,
                voiceSession,
                TextToSpeechOptions.defaults(),
                () -> false
        ), sentenceSink -> {
            sentenceSink.accept("第一句内容。");
            sentenceSink.complete();
        });

        assertThat(result.played()).isTrue();
        assertThat(textPayloads(session))
                .filteredOn(payload -> payload.contains("\"type\":\"tts\"") && payload.contains("\"state\":\"stop\""))
                .hasSize(1);
        assertThat(voiceSession.state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
    }

    private XiaozhiTtsRuntime newRuntimeWithFakeTts() {
        return newRuntime(new FakeTextToSpeechClient());
    }

    private XiaozhiTtsRuntime newRuntime(TextToSpeechClient textToSpeechClient) {
        return newRuntimeWithStreamingTts(null, textToSpeechClient);
    }

    private XiaozhiTtsRuntime newRuntimeWithStreamingTts(StreamingTextToSpeechClient streamingClient) {
        return newRuntimeWithStreamingTts(streamingClient, new FakeTextToSpeechClient());
    }

    private XiaozhiTtsRuntime newRuntimeWithStreamingTts(
            StreamingTextToSpeechClient streamingClient,
            TextToSpeechClient fallbackClient
    ) {
        return new XiaozhiTtsRuntime(
                fallbackClient,
                streamingClient,
                new XiaozhiMessageCodec(OBJECT_MAPPER),
                new XiaozhiServerEventFactory(OBJECT_MAPPER)
        );
    }

    private TestWebSocketSession openSession() {
        return new TestWebSocketSession("ws-session-1");
    }

    private List<String> textPayloads(TestWebSocketSession session) {
        return session.getSentMessages().stream()
                .filter(TextMessage.class::isInstance)
                .map(TextMessage.class::cast)
                .map(TextMessage::getPayload)
                .toList();
    }

    private List<String> sentenceStartTexts(TestWebSocketSession session) {
        return textPayloads(session).stream()
                .map(this::readJson)
                .filter(node -> "tts".equals(node.path("type").asText()))
                .filter(node -> "sentence_start".equals(node.path("state").asText()))
                .map(node -> node.path("text").asText())
                .toList();
    }

    private List<BinaryMessage> binaryMessages(TestWebSocketSession session) {
        return session.getSentMessages().stream()
                .filter(BinaryMessage.class::isInstance)
                .map(BinaryMessage.class::cast)
                .toList();
    }

    private JsonNode readJson(String payload) {
        try {
            return OBJECT_MAPPER.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid json payload", exception);
        }
    }

    private static class FakeTextToSpeechClient implements TextToSpeechClient {

        @Override
        public List<ByteBuffer> synthesize(String text, VoiceId voiceId) {
            return List.of(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        }
    }

    private static class CapturingMusicPlaybackCoordinator implements XiaozhiMusicPlaybackCoordinator {

        private final List<String> events = new ArrayList<>();

        @Override
        public void pause(String deviceId, XiaozhiMusicPlaybackState.PauseSource source) {
            events.add("pause:" + deviceId + ":" + source);
        }

        @Override
        public void resume(String deviceId, XiaozhiMusicPlaybackState.PauseSource source) {
            events.add("resume:" + deviceId + ":" + source);
        }

        private List<String> events() {
            return List.copyOf(events);
        }
    }

    private static class BlockingTextToSpeechClient implements TextToSpeechClient {

        private final CountDownLatch firstCallStarted = new CountDownLatch(1);
        private final CountDownLatch firstCallReleased = new CountDownLatch(1);
        private final List<String> texts = new ArrayList<>();

        @Override
        public List<ByteBuffer> synthesize(String text, VoiceId voiceId) {
            texts.add(text);
            if (texts.size() == 1) {
                firstCallStarted.countDown();
                awaitRelease();
            }
            return List.of(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        }

        private boolean awaitFirstCall() throws InterruptedException {
            return firstCallStarted.await(2, TimeUnit.SECONDS);
        }

        private void releaseFirstCall() {
            firstCallReleased.countDown();
        }

        private List<String> texts() {
            return List.copyOf(texts);
        }

        private void awaitRelease() {
            try {
                firstCallReleased.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static class RecordingTextToSpeechClient implements TextToSpeechClient {

        private final List<String> texts = new ArrayList<>();

        @Override
        public List<ByteBuffer> synthesize(String text, VoiceId voiceId) {
            texts.add(text);
            return List.of(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        }

        private List<String> texts() {
            return List.copyOf(texts);
        }
    }

    private static class SequencedFrameTextToSpeechClient implements TextToSpeechClient {

        private final int[] frameCounts;
        private final List<String> texts = new ArrayList<>();

        private SequencedFrameTextToSpeechClient(int... frameCounts) {
            this.frameCounts = frameCounts;
        }

        @Override
        public List<ByteBuffer> synthesize(String text, VoiceId voiceId) {
            texts.add(text);
            var frameCount = frameCounts[texts.size() - 1];
            var frames = new ArrayList<ByteBuffer>();
            for (var index = 0; index < frameCount; index++) {
                frames.add(ByteBuffer.wrap(new byte[] {(byte) (index + 1)}));
            }
            return frames;
        }
    }

    private static class CancellingTextToSpeechClient implements TextToSpeechClient {

        private final AtomicBoolean cancellationRequested;
        private final List<String> texts = new ArrayList<>();

        private CancellingTextToSpeechClient(AtomicBoolean cancellationRequested) {
            this.cancellationRequested = cancellationRequested;
        }

        @Override
        public List<ByteBuffer> synthesize(String text, VoiceId voiceId) {
            texts.add(text);
            cancellationRequested.set(true);
            return List.of(ByteBuffer.wrap(new byte[] {1, 2, 3}));
        }

        private List<String> texts() {
            return List.copyOf(texts);
        }
    }

    private static class FailingTextToSpeechClient implements TextToSpeechClient {

        @Override
        public List<ByteBuffer> synthesize(String text, VoiceId voiceId) {
            throw new IllegalStateException("tts boom");
        }
    }

    private static class StopFailingSession extends TestWebSocketSession {

        private StopFailingSession(String id) {
            super(id);
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            if (message instanceof TextMessage textMessage && textMessage.getPayload().contains("\"state\":\"stop\"")) {
                throw new IOException("stop failed");
            }
            super.sendMessage(message);
        }
    }

    private static class ClosedStopFailingSession extends TestWebSocketSession {

        private ClosedStopFailingSession(String id) {
            super(id);
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            if (message instanceof TextMessage textMessage && textMessage.getPayload().contains("\"state\":\"stop\"")) {
                close();
                throw new IllegalStateException("Message will not be sent because the WebSocket session has been closed");
            }
            super.sendMessage(message);
        }
    }

    private static class PushStreamingTextToSpeechClient implements StreamingTextToSpeechClient {

        @Override
        public StreamingTextToSpeechSession open(
                TextToSpeechOptions options,
                StreamingTextToSpeechListener listener
        ) {
            listener.onReady();
            return new PushStreamingTextToSpeechSession(listener);
        }
    }

    private static class PushStreamingTextToSpeechSession implements StreamingTextToSpeechSession {

        private final StreamingTextToSpeechListener listener;
        private boolean completed;

        private PushStreamingTextToSpeechSession(StreamingTextToSpeechListener listener) {
            this.listener = listener;
        }

        @Override
        public void sendText(String text) {
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

    private static class BlockingStreamingTextToSpeechClient implements StreamingTextToSpeechClient {

        private final List<BlockingStreamingTextToSpeechSession> sessions = new java.util.concurrent.CopyOnWriteArrayList<>();
        private int textCount;
        private int cancelCount;

        @Override
        public StreamingTextToSpeechSession open(
                TextToSpeechOptions options,
                StreamingTextToSpeechListener listener
        ) {
            listener.onReady();
            var session = new BlockingStreamingTextToSpeechSession(listener);
            sessions.add(session);
            return session;
        }

        private boolean awaitFirstText() {
            return awaitFirstTextCount(1);
        }

        private boolean cancelled() {
            return cancelCount() > 0;
        }

        private synchronized boolean awaitFirstTextCount(int expectedCount) {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
            while (textCount < expectedCount) {
                var remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }

        private synchronized boolean awaitCancelCount(int expectedCount) {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
            while (cancelCount < expectedCount) {
                var remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }

        private void cancelFirstSession() {
            sessions.getFirst().cancel();
        }

        private synchronized int cancelCount() {
            return cancelCount;
        }

        private synchronized void markTextReceived() {
            textCount++;
            notifyAll();
        }

        private synchronized void markCancelled() {
            cancelCount++;
            notifyAll();
        }

        private final class BlockingStreamingTextToSpeechSession implements StreamingTextToSpeechSession {

            private final StreamingTextToSpeechListener listener;
            private final CountDownLatch sessionCancelled = new CountDownLatch(1);
            private final AtomicBoolean cancelled = new AtomicBoolean();

            private BlockingStreamingTextToSpeechSession(StreamingTextToSpeechListener listener) {
                this.listener = listener;
            }

            @Override
            public void sendText(String text) {
                markTextReceived();
            }

            @Override
            public void complete() {
            }

            @Override
            public void cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    markCancelled();
                    sessionCancelled.countDown();
                    listener.onCompleted();
                }
            }

            @Override
            public boolean awaitFinal(Duration timeout) {
                try {
                    return sessionCancelled.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            @Override
            public void close() {
                cancel();
            }
        }
    }

    private static class FailingStreamingTextToSpeechClient implements StreamingTextToSpeechClient {

        @Override
        public StreamingTextToSpeechSession open(
                TextToSpeechOptions options,
                StreamingTextToSpeechListener listener
        ) {
            listener.onReady();
            return new FailingStreamingTextToSpeechSession(listener);
        }
    }

    private static class FailingStreamingTextToSpeechSession implements StreamingTextToSpeechSession {

        private final StreamingTextToSpeechListener listener;

        private FailingStreamingTextToSpeechSession(StreamingTextToSpeechListener listener) {
            this.listener = listener;
        }

        @Override
        public void sendText(String text) {
            listener.onFailed(new IllegalStateException("streaming tts boom"));
        }

        @Override
        public void complete() {
        }

        @Override
        public void cancel() {
        }

        @Override
        public boolean awaitFinal(Duration timeout) {
            return true;
        }

        @Override
        public void close() {
            cancel();
        }
    }

    private static class FinalTimeoutAfterAudioStreamingTextToSpeechClient implements StreamingTextToSpeechClient {

        @Override
        public StreamingTextToSpeechSession open(
                TextToSpeechOptions options,
                StreamingTextToSpeechListener listener
        ) {
            listener.onReady();
            return new FinalTimeoutAfterAudioStreamingTextToSpeechSession(listener);
        }
    }

    private static class FinalTimeoutAfterAudioStreamingTextToSpeechSession implements StreamingTextToSpeechSession {

        private final StreamingTextToSpeechListener listener;

        private FinalTimeoutAfterAudioStreamingTextToSpeechSession(StreamingTextToSpeechListener listener) {
            this.listener = listener;
        }

        @Override
        public void sendText(String text) {
            listener.onAudioFrame(ByteBuffer.wrap(new byte[] {1, 2, 3}));
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

    private static class CloseFailingStreamingTextToSpeechClient implements StreamingTextToSpeechClient {

        @Override
        public StreamingTextToSpeechSession open(
                TextToSpeechOptions options,
                StreamingTextToSpeechListener listener
        ) {
            listener.onReady();
            return new CloseFailingStreamingTextToSpeechSession(listener);
        }
    }

    private static class CloseFailingStreamingTextToSpeechSession implements StreamingTextToSpeechSession {

        private final StreamingTextToSpeechListener listener;
        private boolean completed;

        private CloseFailingStreamingTextToSpeechSession(StreamingTextToSpeechListener listener) {
            this.listener = listener;
        }

        @Override
        public void sendText(String text) {
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
        }

        @Override
        public boolean awaitFinal(Duration timeout) {
            return completed;
        }

        @Override
        public void close() {
            throw new IllegalStateException("close unavailable");
        }
    }

    private static class TimingWebSocketSession extends TestWebSocketSession {

        private final List<Long> binarySentAt = new ArrayList<>();
        private long stopSentAt;

        private TimingWebSocketSession(String id) {
            super(id);
        }

        @Override
        public synchronized void sendMessage(WebSocketMessage<?> message) throws IOException {
            var sentAt = System.nanoTime();
            if (message instanceof BinaryMessage) {
                binarySentAt.add(sentAt);
            }
            if (message instanceof TextMessage textMessage && isTtsStop(textMessage.getPayload())) {
                stopSentAt = sentAt;
            }
            super.sendMessage(message);
            notifyAll();
        }

        private boolean awaitFirstBinary() throws InterruptedException {
            return awaitBinaryCount(1);
        }

        private synchronized boolean awaitBinaryCount(int count) throws InterruptedException {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
            while (binarySentAt.size() < count) {
                var remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            return true;
        }

        private synchronized List<Long> binarySentAt() {
            return List.copyOf(binarySentAt);
        }

        private synchronized long lastBinarySentAt() {
            return binarySentAt.getLast();
        }

        private synchronized long stopSentAt() {
            return stopSentAt;
        }

        private boolean isTtsStop(String payload) {
            return payload.contains("\"type\":\"tts\"") && payload.contains("\"state\":\"stop\"");
        }
    }

    private static class ClosingOnBinaryWebSocketSession extends TimingWebSocketSession {

        private ClosingOnBinaryWebSocketSession(String id) {
            super(id);
        }

        @Override
        public synchronized void sendMessage(WebSocketMessage<?> message) throws IOException {
            super.sendMessage(message);
            if (message instanceof BinaryMessage) {
                close();
            }
        }
    }
}
