package com.jzb.chatbot.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tencent.asrv2.SpeechRecognizer;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TencentRealtimeSpeechToTextClientTest {

    @Test
    void shouldUse16kChinesePcmDefaults() {
        var config = new TencentRealtimeSpeechToTextConfig(
                "app-id",
                "secret-id",
                "secret-key",
                "",
                0,
                null,
                null
        );

        assertThat(config.engineModelType()).isEqualTo("16k_zh");
        assertThat(config.sampleRate()).isEqualTo(16000);
        assertThat(config.chunkTimeout()).isEqualTo(Duration.ofMillis(100));
        assertThat(config.recognitionTimeout()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void shouldWriteAudioStopAndCloseRecognizer() {
        var factory = new CapturingRecognizerFactory("测试文本");
        factory.recognizer.completionDelay = Duration.ofMillis(1);
        var config = new TencentRealtimeSpeechToTextConfig(
                "app-id",
                "secret-id",
                "secret-key",
                "16k_zh",
                16000,
                Duration.ofMillis(10),
                Duration.ofSeconds(3)
        );
        var client = new TencentRealtimeSpeechToTextClient(config, factory);
        var audioStream = new SpeechToTextAudioStream();

        audioStream.write(new byte[] {1, 2, 3});
        audioStream.complete();
        var result = client.transcribe(audioStream);

        assertThat(result.text()).isEqualTo("测试文本");
        assertThat(result.provider()).isEqualTo("tencent-realtime");
        assertThat(result.audioMillis()).isGreaterThan(0);
        assertThat(factory.recognizer.started).isTrue();
        assertThat(factory.recognizer.writes).containsExactly(new byte[] {1, 2, 3});
        assertThat(factory.recognizer.stopped).isTrue();
        assertThat(factory.recognizer.closed).isTrue();
    }

    @Test
    void shouldRejectBlankCredentials() {
        assertThatThrownBy(() -> new TencentRealtimeSpeechToTextConfig(
                "",
                "secret-id",
                "secret-key",
                "16k_zh",
                16000,
                Duration.ofMillis(10),
                Duration.ofSeconds(3)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tencent realtime ASR requires app-id, secret-id and secret-key");
    }

    @Test
    void shouldPropagateRecognizerWriteFailure() {
        var factory = new CapturingRecognizerFactory("测试文本");
        factory.recognizer.writeFailure = new IllegalStateException("write failed");
        var client = new TencentRealtimeSpeechToTextClient(validConfig(), factory);
        var audioStream = new SpeechToTextAudioStream();

        audioStream.write(new byte[] {1, 2, 3});

        assertThatThrownBy(() -> client.transcribe(audioStream))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("write failed");
        assertThat(factory.recognizer.closed).isTrue();
    }

    @Test
    void shouldCloseAudioStreamWhenRecognizerCreationFails() {
        var factory = new CapturingRecognizerFactory("测试文本");
        factory.createFailure = new IllegalStateException("create failed");
        var client = new TencentRealtimeSpeechToTextClient(validConfig(), factory);
        var audioStream = new SpeechToTextAudioStream();

        assertThatThrownBy(() -> client.transcribe(audioStream))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("create failed");
        assertThat(audioStream.isEnd(audioStream.take(Duration.ofMillis(10)))).isTrue();
    }

    @Test
    void shouldPropagateRecognizerFailureCallback() {
        var factory = new CapturingRecognizerFactory("测试文本");
        factory.recognizer.failureMessage = "sdk failed";
        var client = new TencentRealtimeSpeechToTextClient(validConfig(), factory);
        var audioStream = new SpeechToTextAudioStream();

        audioStream.write(new byte[] {1, 2, 3});
        audioStream.complete();

        assertThatThrownBy(() -> client.transcribe(audioStream))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Tencent realtime ASR failed: sdk failed");
        assertThat(factory.recognizer.closed).isTrue();
    }

    @Test
    void shouldCompleteWhenRealtimeVadEndsSentence() {
        var factory = new CapturingRecognizerFactory("测试文本");
        factory.recognizer.completeOnFirstWrite = true;
        var client = new TencentRealtimeSpeechToTextClient(validConfig(), factory);
        var audioStream = new SpeechToTextAudioStream();

        audioStream.write(new byte[] {1, 2, 3});
        var result = client.transcribe(audioStream);

        assertThat(result.text()).isEqualTo("测试文本");
        assertThat(factory.recognizer.started).isTrue();
        assertThat(factory.recognizer.closed).isTrue();
    }

    @Test
    void shouldStopWriterWhenRecognitionTimesOut() {
        var factory = new CapturingRecognizerFactory("测试文本");
        factory.recognizer.suppressCompletion = true;
        var client = new TencentRealtimeSpeechToTextClient(
                new TencentRealtimeSpeechToTextConfig(
                        "app-id",
                        "secret-id",
                        "secret-key",
                        "16k_zh",
                        16000,
                        Duration.ofMillis(10),
                        Duration.ofMillis(50)
                ),
                factory
        );
        var audioStream = new SpeechToTextAudioStream();

        audioStream.write(new byte[] {1, 2, 3});

        assertThatThrownBy(() -> client.transcribe(audioStream))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Tencent realtime ASR timed out");
        assertThat(factory.recognizer.stopped).isFalse();
        assertThat(factory.recognizer.closed).isTrue();
    }

    @Test
    void shouldKeepTimeoutPrimaryWhenWriterDoesNotFinishDuringCleanup() throws Exception {
        var factory = new CapturingRecognizerFactory("测试文本");
        factory.recognizer.suppressCompletion = true;
        factory.recognizer.blockFirstWrite = true;
        var client = new TencentRealtimeSpeechToTextClient(
                new TencentRealtimeSpeechToTextConfig(
                        "app-id",
                        "secret-id",
                        "secret-key",
                        "16k_zh",
                        16000,
                        Duration.ofMillis(10),
                        Duration.ofMillis(50)
                ),
                factory
        );
        var audioStream = new SpeechToTextAudioStream();
        var transcription = new CompletableFuture<Throwable>();

        audioStream.write(new byte[] {1, 2, 3});
        var startedAt = System.nanoTime();
        var transcribeThread = Thread.startVirtualThread(() -> {
            try {
                client.transcribe(audioStream);
                transcription.complete(null);
            } catch (Throwable throwable) {
                transcription.complete(throwable);
            }
        });

        try {
            assertThat(factory.recognizer.firstWriteStarted.await(500, TimeUnit.MILLISECONDS)).isTrue();
            var error = transcription.get(500, TimeUnit.MILLISECONDS);
            var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(error)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Tencent realtime ASR timed out");
            assertThat(error.getSuppressed())
                    .singleElement()
                    .satisfies(suppressed -> assertThat(suppressed)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage("Tencent realtime ASR writer cleanup timed out"));
            assertThat(elapsed).isLessThan(Duration.ofMillis(500));
        } finally {
            factory.recognizer.releaseFirstWrite();
            transcribeThread.join(Duration.ofMillis(200));
        }
    }

    @Test
    void shouldNotWriteQueuedAudioAfterRecognizerClosesWhenCleanupTimesOut() throws Exception {
        var factory = new CapturingRecognizerFactory("测试文本");
        factory.recognizer.suppressCompletion = true;
        factory.recognizer.blockFirstWrite = true;
        var client = new TencentRealtimeSpeechToTextClient(
                new TencentRealtimeSpeechToTextConfig(
                        "app-id",
                        "secret-id",
                        "secret-key",
                        "16k_zh",
                        16000,
                        Duration.ofMillis(10),
                        Duration.ofMillis(50)
                ),
                factory
        );
        var audioStream = new SpeechToTextAudioStream();
        var transcription = new CompletableFuture<Throwable>();

        audioStream.write(new byte[] {1});
        audioStream.write(new byte[] {2});
        var transcribeThread = Thread.startVirtualThread(() -> {
            try {
                client.transcribe(audioStream);
                transcription.complete(null);
            } catch (Throwable throwable) {
                transcription.complete(throwable);
            }
        });

        try {
            assertThat(factory.recognizer.firstWriteStarted.await(500, TimeUnit.MILLISECONDS)).isTrue();

            var error = transcription.get(500, TimeUnit.MILLISECONDS);
            assertThat(error)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Tencent realtime ASR timed out");
            assertThat(error.getSuppressed())
                    .singleElement()
                    .satisfies(suppressed -> assertThat(suppressed)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage("Tencent realtime ASR writer cleanup timed out"));
            assertThat(factory.recognizer.closed).isTrue();

            factory.recognizer.releaseFirstWrite();
            assertThat(factory.recognizer.secondWriteAttempted.await(500, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(factory.recognizer.writeAfterCloseCount).isZero();
        } finally {
            factory.recognizer.releaseFirstWrite();
            transcribeThread.join(Duration.ofMillis(200));
        }
    }

    @Test
    void shouldNotWriteAfterCloseWhenTimeoutCleanupRacesBeforeRecognizerWrite() throws Exception {
        var factory = new CapturingRecognizerFactory("测试文本");
        factory.recognizer.suppressCompletion = true;
        factory.recognizer.blockBeforeFirstWriteBody = true;
        var client = new TencentRealtimeSpeechToTextClient(
                new TencentRealtimeSpeechToTextConfig(
                        "app-id",
                        "secret-id",
                        "secret-key",
                        "16k_zh",
                        16000,
                        Duration.ofMillis(10),
                        Duration.ofMillis(50)
                ),
                factory
        );
        var audioStream = new SpeechToTextAudioStream();
        var transcription = new CompletableFuture<Throwable>();

        audioStream.write(new byte[] {1});
        var transcribeThread = Thread.startVirtualThread(() -> {
            try {
                client.transcribe(audioStream);
                transcription.complete(null);
            } catch (Throwable throwable) {
                transcription.complete(throwable);
            }
        });

        try {
            assertThat(factory.recognizer.beforeFirstWriteBodyStarted.await(500, TimeUnit.MILLISECONDS)).isTrue();

            var error = transcription.get(500, TimeUnit.MILLISECONDS);
            assertThat(error)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Tencent realtime ASR timed out");
            assertThat(error.getSuppressed())
                    .singleElement()
                    .satisfies(suppressed -> assertThat(suppressed)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage("Tencent realtime ASR writer cleanup timed out"));

            factory.recognizer.releaseBeforeFirstWriteBody();
            assertThat(factory.recognizer.closeCompleted.await(500, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(factory.recognizer.writeAfterCloseCount).isZero();
        } finally {
            factory.recognizer.releaseBeforeFirstWriteBody();
            transcribeThread.join(Duration.ofMillis(200));
        }
    }

    @Test
    void shouldWaitForRecognitionCompletionWhenWriterStopsNormally() {
        var factory = new CapturingRecognizerFactory("测试文本");
        factory.recognizer.suppressCompletion = true;
        var client = new TencentRealtimeSpeechToTextClient(
                new TencentRealtimeSpeechToTextConfig(
                        "app-id",
                        "secret-id",
                        "secret-key",
                        "16k_zh",
                        16000,
                        Duration.ofMillis(10),
                        Duration.ofMillis(50)
                ),
                factory
        );
        var audioStream = new SpeechToTextAudioStream();

        audioStream.write(new byte[] {1, 2, 3});
        audioStream.complete();

        assertThatThrownBy(() -> client.transcribe(audioStream))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Tencent realtime ASR timed out");
        assertThat(factory.recognizer.stopped).isTrue();
        assertThat(factory.recognizer.closed).isTrue();
    }

    @Test
    void shouldKeepTimeoutAsPrimaryWhenWriterStopFailsDuringCleanup() {
        var factory = new CapturingRecognizerFactory("测试文本");
        factory.recognizer.suppressCompletion = true;
        factory.recognizer.stopFailure = new IllegalStateException("stop failed");
        var client = new TencentRealtimeSpeechToTextClient(
                new TencentRealtimeSpeechToTextConfig(
                        "app-id",
                        "secret-id",
                        "secret-key",
                        "16k_zh",
                        16000,
                        Duration.ofMillis(10),
                        Duration.ofMillis(50)
                ),
                factory
        );
        var audioStream = new SpeechToTextAudioStream();

        audioStream.write(new byte[] {1, 2, 3});

        assertThatThrownBy(() -> client.transcribe(audioStream))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Tencent realtime ASR timed out")
                .satisfies(error -> assertThat(error.getSuppressed()).isEmpty());
        assertThat(factory.recognizer.stopped).isFalse();
        assertThat(factory.recognizer.closed).isTrue();
    }

    @Test
    void shouldKeepRecognitionFailurePrimaryWhenWriterFailsAfterOnFailCallback() {
        for (var attempt = 0; attempt < 100; attempt++) {
            var factory = new CapturingRecognizerFactory("测试文本");
            factory.recognizer.writeFailureMessage = "sdk failed";
            factory.recognizer.writeFailureAfterCallback = new IllegalStateException("write failed");
            var client = new TencentRealtimeSpeechToTextClient(validConfig(), factory);
            var audioStream = new SpeechToTextAudioStream();

            audioStream.write(new byte[] {1, 2, 3});

            assertThatThrownBy(() -> client.transcribe(audioStream))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Tencent realtime ASR failed: sdk failed")
                    .satisfies(error -> assertThat(error.getSuppressed())
                            .anySatisfy(suppressed -> assertThat(suppressed)
                                    .isInstanceOf(IllegalStateException.class)
                                    .hasMessage("write failed")));
            assertThat(factory.recognizer.closed).isTrue();
        }
    }

    @Test
    void shouldCancelWriterWithoutStoppingWhenFailureCallbackWins() {
        var factory = new CapturingRecognizerFactory("测试文本");
        factory.recognizer.writeFailureMessage = "sdk failed";
        factory.recognizer.stopFailure = new IllegalStateException("stop failed");
        var client = new TencentRealtimeSpeechToTextClient(validConfig(), factory);
        var audioStream = new SpeechToTextAudioStream();

        audioStream.write(new byte[] {1, 2, 3});

        assertThatThrownBy(() -> client.transcribe(audioStream))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Tencent realtime ASR failed: sdk failed")
                .satisfies(error -> assertThat(error.getSuppressed()).isEmpty());
        assertThat(factory.recognizer.stopped).isFalse();
        assertThat(factory.recognizer.closed).isTrue();
    }

    @Test
    void shouldCleanUpWriterOnlyOnceWhenFailureCallbackLeavesWriterBlocked() throws Exception {
        var factory = new CapturingRecognizerFactory("测试文本");
        factory.recognizer.writeFailureMessage = "sdk failed";
        factory.recognizer.blockAfterFailureCallback = true;
        var client = new TencentRealtimeSpeechToTextClient(
                new TencentRealtimeSpeechToTextConfig(
                        "app-id",
                        "secret-id",
                        "secret-key",
                        "16k_zh",
                        16000,
                        Duration.ofMillis(10),
                        Duration.ofMillis(50)
                ),
                factory
        );
        var audioStream = new SpeechToTextAudioStream();
        var transcription = new CompletableFuture<Throwable>();

        audioStream.write(new byte[] {1, 2, 3});
        var transcribeThread = Thread.startVirtualThread(() -> {
            try {
                client.transcribe(audioStream);
                transcription.complete(null);
            } catch (Throwable throwable) {
                transcription.complete(throwable);
            }
        });

        try {
            assertThat(factory.recognizer.afterFailureCallbackStarted.await(500, TimeUnit.MILLISECONDS)).isTrue();

            var error = transcription.get(500, TimeUnit.MILLISECONDS);
            assertThat(error)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Tencent realtime ASR failed: sdk failed");
            assertThat(List.of(error.getSuppressed()))
                    .filteredOn(suppressed -> "Tencent realtime ASR writer cleanup timed out"
                            .equals(suppressed.getMessage()))
                    .hasSize(1);
        } finally {
            factory.recognizer.releaseAfterFailureCallback();
            transcribeThread.join(Duration.ofMillis(200));
        }
    }

    @Test
    void shouldCloseCloseableRecognizerFactory() {
        var factory = new CapturingRecognizerFactory("测试文本");
        var client = new TencentRealtimeSpeechToTextClient(validConfig(), factory);

        client.close();

        assertThat(factory.closed).isTrue();
    }


    @Test
    void shouldOmitInputSampleRateFor16kRealtimePcmRequest() {
        var factory = new TencentSdkRealtimeSpeechRecognizerFactory();
        var recognizer = factory.create(validConfig(), new NoopRecognitionListener());

        try {
            var request = sdkRecognizer(recognizer).getRequest();

            assertThat(request.getEngineModelType()).isEqualTo("16k_zh");
            assertThat(request.getVoiceFormat()).isEqualTo(1);
            assertThat(request.getNeedVad()).isEqualTo(1);
            assertThat(request.getInputSampleRate()).isNull();
        } finally {
            recognizer.close();
            factory.close();
        }
    }

    private SpeechRecognizer sdkRecognizer(TencentRealtimeSpeechRecognizer recognizer) {
        try {
            Field field = TencentSdkRealtimeSpeechRecognizer.class.getDeclaredField("sdkRecognizer");
            field.setAccessible(true);
            return (SpeechRecognizer) field.get(recognizer);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Failed to read Tencent SDK recognizer", exception);
        }
    }

    private TencentRealtimeSpeechToTextConfig validConfig() {
        return new TencentRealtimeSpeechToTextConfig(
                "app-id",
                "secret-id",
                "secret-key",
                "16k_zh",
                16000,
                Duration.ofMillis(10),
                Duration.ofSeconds(3)
        );
    }


    private static final class NoopRecognitionListener implements TencentRealtimeSpeechRecognitionListener {

        @Override
        public void onSentenceEnd(String text) {
        }

        @Override
        public void onComplete(String text) {
        }

        @Override
        public void onFail(String message) {
        }
    }

    private static final class CapturingRecognizerFactory implements TencentRealtimeSpeechRecognizerFactory, AutoCloseable {

        private final CapturingRecognizer recognizer;
        private boolean closed;
        private RuntimeException createFailure;

        private CapturingRecognizerFactory(String text) {
            recognizer = new CapturingRecognizer(text);
        }

        @Override
        public TencentRealtimeSpeechRecognizer create(
                TencentRealtimeSpeechToTextConfig config,
                TencentRealtimeSpeechRecognitionListener listener
        ) {
            if (createFailure != null) {
                throw createFailure;
            }
            recognizer.listener = listener;
            return recognizer;
        }

        public void close() {
            closed = true;
        }
    }

    private static final class CapturingRecognizer implements TencentRealtimeSpeechRecognizer {

        private final String text;
        private final List<byte[]> writes = new ArrayList<>();
        private TencentRealtimeSpeechRecognitionListener listener;
        private RuntimeException writeFailure;
        private RuntimeException writeFailureAfterCallback;
        private RuntimeException stopFailure;
        private String writeFailureMessage;
        private String failureMessage;
        private Duration completionDelay = Duration.ZERO;
        private boolean completeOnFirstWrite;
        private boolean blockFirstWrite;
        private boolean blockBeforeFirstWriteBody;
        private boolean blockAfterFailureCallback;
        private boolean suppressCompletion;
        private boolean started;
        private boolean stopped;
        private boolean closed;
        private int writeAfterCloseCount;
        private final CountDownLatch firstWriteStarted = new CountDownLatch(1);
        private final CountDownLatch beforeFirstWriteBodyStarted = new CountDownLatch(1);
        private final CountDownLatch afterFailureCallbackStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        private final CountDownLatch releaseBeforeFirstWriteBody = new CountDownLatch(1);
        private final CountDownLatch releaseAfterFailureCallback = new CountDownLatch(1);
        private final CountDownLatch secondWriteAttempted = new CountDownLatch(1);
        private final CountDownLatch closeCompleted = new CountDownLatch(1);

        private CapturingRecognizer(String text) {
            this.text = text;
        }

        @Override
        public void start() {
            started = true;
        }

        @Override
        public void write(byte[] pcm) {
            if (writeFailure != null) {
                throw writeFailure;
            }
            if (writes.isEmpty()) {
                firstWriteStarted.countDown();
                if (blockBeforeFirstWriteBody) {
                    beforeFirstWriteBodyStarted.countDown();
                    awaitReleaseBeforeFirstWriteBody();
                }
                if (blockFirstWrite) {
                    awaitReleaseFirstWrite();
                }
            } else {
                secondWriteAttempted.countDown();
            }
            if (closed) {
                writeAfterCloseCount++;
            }
            writes.add(pcm.clone());
            if (writeFailureMessage != null) {
                listener.onFail(writeFailureMessage);
                if (blockAfterFailureCallback) {
                    afterFailureCallbackStarted.countDown();
                    awaitReleaseAfterFailureCallback();
                }
            }
            if (writeFailureAfterCallback != null) {
                throw writeFailureAfterCallback;
            }
            if (completeOnFirstWrite) {
                listener.onSentenceEnd(text);
            }
        }

        @Override
        public void stop() {
            stopped = true;
            if (stopFailure != null) {
                throw stopFailure;
            }
            if (failureMessage != null) {
                listener.onFail(failureMessage);
                return;
            }
            if (!suppressCompletion) {
                sleepCompletionDelay();
                listener.onSentenceEnd(text);
                listener.onComplete(text);
            }
        }

        @Override
        public void close() {
            closed = true;
            closeCompleted.countDown();
        }

        private void releaseFirstWrite() {
            releaseFirstWrite.countDown();
        }

        private void releaseBeforeFirstWriteBody() {
            releaseBeforeFirstWriteBody.countDown();
        }

        private void releaseAfterFailureCallback() {
            releaseAfterFailureCallback.countDown();
        }

        private void awaitReleaseFirstWrite() {
            try {
                releaseFirstWrite.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        private void awaitReleaseBeforeFirstWriteBody() {
            try {
                releaseBeforeFirstWriteBody.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        private void awaitReleaseAfterFailureCallback() {
            var interrupted = false;
            while (true) {
                try {
                    releaseAfterFailureCallback.await();
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        }

        private void sleepCompletionDelay() {
            try {
                Thread.sleep(completionDelay);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
