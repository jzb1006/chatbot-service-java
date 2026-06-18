package com.jzb.chatbot.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jzb.chatbot.common.id.VoiceId;
import com.jzb.chatbot.speech.TextToSpeechClient;
import com.jzb.chatbot.speech.TextToSpeechOptions;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import com.jzb.chatbot.voice.protocol.XiaozhiServerEventFactory;
import com.jzb.chatbot.voice.tts.XiaozhiTtsRequest;
import com.jzb.chatbot.voice.tts.XiaozhiTtsRuntime;
import java.io.IOException;
import java.nio.ByteBuffer;
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

    private XiaozhiTtsRuntime newRuntimeWithFakeTts() {
        return newRuntime(new FakeTextToSpeechClient());
    }

    private XiaozhiTtsRuntime newRuntime(TextToSpeechClient textToSpeechClient) {
        return new XiaozhiTtsRuntime(
                textToSpeechClient,
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
}
