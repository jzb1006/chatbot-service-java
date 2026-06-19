package com.jzb.chatbot.speech;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * 腾讯云实时 ASR 客户端。
 *
 * @author jiangzhibin
 * @since 2026-06-17 00:00:00
 */
@Slf4j
public class TencentRealtimeSpeechToTextClient implements StreamingSpeechToTextClient, AutoCloseable {

    private static final String PROVIDER = "tencent-realtime";

    private final TencentRealtimeSpeechToTextConfig config;
    private final TencentRealtimeSpeechRecognizerFactory recognizerFactory;

    public TencentRealtimeSpeechToTextClient(TencentRealtimeSpeechToTextConfig config) {
        this(config, new TencentSdkRealtimeSpeechRecognizerFactory());
    }

    TencentRealtimeSpeechToTextClient(
            TencentRealtimeSpeechToTextConfig config,
            TencentRealtimeSpeechRecognizerFactory recognizerFactory
    ) {
        this.config = config;
        this.recognizerFactory = recognizerFactory;
    }

    @Override
    public SpeechToTextResult transcribe(SpeechToTextAudioStream audioStream) {
        if (audioStream == null) {
            return SpeechToTextResult.blank(PROVIDER);
        }
        var startedAt = System.nanoTime();
        var finalResult = new AtomicReference<>("");
        var failure = new AtomicReference<String>();
        var recognitionFinished = new CompletableFuture<Void>();
        var listener = listener(finalResult, failure, recognitionFinished);
        RecognizerOperationGuard recognizer = null;
        WriterHandle writer = null;
        Throwable primaryFailure = null;
        try {
            recognizer = new RecognizerOperationGuard(recognizerFactory.create(config, listener));
            log.info("tencent realtime asr recognizer created, engineModelType={}, sampleRate={}, timeoutMillis={}",
                    config.engineModelType(), config.sampleRate(), config.recognitionTimeout().toMillis());
            recognizer.start();
            log.info("tencent realtime asr recognizer started, engineModelType={}, sampleRate={}",
                    config.engineModelType(), config.sampleRate());
            writer = writeAudioAsync(audioStream, recognizer);
            log.info("tencent realtime asr writer started");
            awaitRecognition(recognitionFinished, writer, failure);
            if (failure.get() != null) {
                var failureException = new IllegalStateException("Tencent realtime ASR failed: " + failure.get());
                throw failureException;
            }
            audioStream.close();
            awaitWriter(writer);
            log.info("tencent realtime asr completed, textBlank={}, audioMillis={}",
                    finalResult.get().isBlank(), elapsedMillis(startedAt));
            return new SpeechToTextResult(finalResult.get(), PROVIDER, elapsedMillis(startedAt));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            var failureException = new IllegalStateException("Tencent realtime ASR interrupted", exception);
            primaryFailure = failureException;
            cleanupAfterFailure(failureException, audioStream, writer);
            throw failureException;
        } catch (RuntimeException exception) {
            primaryFailure = exception;
            cleanupAfterFailure(exception, audioStream, writer);
            throw exception;
        } catch (Exception exception) {
            var failureException = new IllegalStateException("Tencent realtime ASR failed", exception);
            primaryFailure = failureException;
            cleanupAfterFailure(failureException, audioStream, writer);
            throw failureException;
        } finally {
            closeRecognizer(recognizer, primaryFailure, writer);
        }
    }

    @Override
    public void close() {
        if (recognizerFactory instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to close Tencent realtime ASR", exception);
            }
        }
    }

    private void awaitRecognition(
            CompletableFuture<Void> recognitionFinished,
            WriterHandle writer,
            AtomicReference<String> failure
    ) throws InterruptedException {
        var writerFailure = new CompletableFuture<Void>();
        writer.completion().whenComplete((unused, throwable) -> {
            if (throwable != null) {
                writerFailure.completeExceptionally(throwable);
            }
        });
        try {
            CompletableFuture.anyOf(recognitionFinished, writerFailure)
                    .orTimeout(config.recognitionTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .join();
        } catch (CompletionException exception) {
            if (failure.get() != null) {
                return;
            }
            if (exception.getCause() instanceof TimeoutException) {
                throw new IllegalStateException("Tencent realtime ASR timed out", exception.getCause());
            }
            if (exception.getCause() instanceof InterruptedException interruptedException) {
                throw interruptedException;
            }
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Tencent realtime ASR writer failed", exception.getCause());
        }
    }

    private TencentRealtimeSpeechRecognitionListener listener(
            AtomicReference<String> finalResult,
            AtomicReference<String> failure,
            CompletableFuture<Void> recognitionFinished
    ) {
        return new TencentRealtimeSpeechRecognitionListener() {
            @Override
            public void onSentenceEnd(String text) {
                updateResult(finalResult, text);
                log.info("tencent realtime asr sentence ended, textBlank={}", text == null || text.isBlank());
                recognitionFinished.complete(null);
            }

            @Override
            public void onComplete(String text) {
                updateResult(finalResult, text);
                log.info("tencent realtime asr recognition completed, textBlank={}", text == null || text.isBlank());
                recognitionFinished.complete(null);
            }

            @Override
            public void onFail(String message) {
                failure.set(message == null || message.isBlank() ? "unknown" : message);
                log.warn("tencent realtime asr failed callback, message={}", failure.get());
                recognitionFinished.complete(null);
            }
        };
    }

    private void updateResult(AtomicReference<String> finalResult, String text) {
        if (text != null && !text.isBlank()) {
            finalResult.set(text);
        }
    }

    private WriterHandle writeAudioAsync(
            SpeechToTextAudioStream audioStream,
            RecognizerOperationGuard recognizer
    ) {
        var writer = new CompletableFuture<Void>();
        var cancelled = new AtomicBoolean(false);
        var thread = Thread.startVirtualThread(() -> {
            try {
                writeAudio(audioStream, recognizer, cancelled);
                writer.complete(null);
            } catch (Throwable throwable) {
                writer.completeExceptionally(throwable);
            }
        });
        return new WriterHandle(writer, thread, cancelled);
    }

    private void writeAudio(
            SpeechToTextAudioStream audioStream,
            RecognizerOperationGuard recognizer,
            AtomicBoolean cancelled
    ) {
        while (true) {
            if (isWriterCancelled(cancelled)) {
                return;
            }
            var chunk = audioStream.take(config.chunkTimeout());
            if (isWriterCancelled(cancelled)) {
                return;
            }
            if (audioStream.isEnd(chunk)) {
                log.info("tencent realtime asr audio stream ended, stopping recognizer");
                recognizer.stop(cancelled);
                log.info("tencent realtime asr recognizer stopped by audio stream end");
                return;
            }
            if (chunk.length > 0) {
                recognizer.write(chunk, cancelled);
            }
        }
    }

    private boolean isWriterCancelled(AtomicBoolean cancelled) {
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }

    private void awaitWriter(WriterHandle writer) {
        try {
            writer.completion().join();
        } catch (CompletionException exception) {
            var cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Tencent realtime ASR writer failed", cause);
        }
    }

    private void cleanupAfterFailure(Throwable primaryFailure, SpeechToTextAudioStream audioStream, WriterHandle writer) {
        if (writer != null) {
            writer.cancel();
        }
        try {
            audioStream.close();
        } catch (RuntimeException exception) {
            primaryFailure.addSuppressed(exception);
        }
        if (writer == null) {
            return;
        }
        try {
            awaitWriterCleanup(writer);
        } catch (RuntimeException exception) {
            addSuppressed(primaryFailure, exception);
        }
    }

    private void awaitWriterCleanup(WriterHandle writer) {
        try {
            writer.completion().get(writerCleanupTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            writer.thread().interrupt();
            throw new IllegalStateException("Tencent realtime ASR writer cleanup interrupted", exception);
        } catch (TimeoutException exception) {
            writer.thread().interrupt();
            writer.deferRecognizerClose();
            throw new IllegalStateException("Tencent realtime ASR writer cleanup timed out", exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            var cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Tencent realtime ASR writer failed", cause);
        }
    }

    private Duration writerCleanupTimeout() {
        var timeoutMillis = Math.min(200, Math.max(1, config.recognitionTimeout().toMillis()));
        return Duration.ofMillis(timeoutMillis);
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private void closeRecognizer(
            RecognizerOperationGuard recognizer,
            Throwable primaryFailure,
            WriterHandle writer
    ) {
        if (recognizer == null) {
            return;
        }
        if (writer != null && writer.isRecognizerCloseDeferred()) {
            Thread.startVirtualThread(() -> closeRecognizer(recognizer, primaryFailure));
            return;
        }
        closeRecognizer(recognizer, primaryFailure);
    }

    private void closeRecognizer(RecognizerOperationGuard recognizer, Throwable primaryFailure) {
        try {
            recognizer.close();
        } catch (RuntimeException exception) {
            if (primaryFailure == null) {
                throw exception;
            }
            addSuppressed(primaryFailure, exception);
        }
    }

    private void addSuppressed(Throwable primaryFailure, RuntimeException exception) {
        if (primaryFailure == exception) {
            return;
        }
        for (var suppressed : primaryFailure.getSuppressed()) {
            if (suppressed == exception) {
                return;
            }
        }
        primaryFailure.addSuppressed(exception);
    }

    private static final class RecognizerOperationGuard {

        private final TencentRealtimeSpeechRecognizer recognizer;
        private boolean closed;

        private RecognizerOperationGuard(TencentRealtimeSpeechRecognizer recognizer) {
            this.recognizer = recognizer;
        }

        private void start() {
            recognizer.start();
        }

        private synchronized void write(byte[] chunk, AtomicBoolean cancelled) {
            if (shouldSkip(cancelled)) {
                return;
            }
            recognizer.write(chunk);
        }

        private synchronized void stop(AtomicBoolean cancelled) {
            if (shouldSkip(cancelled)) {
                return;
            }
            recognizer.stop();
        }

        private synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            recognizer.close();
        }

        private boolean shouldSkip(AtomicBoolean cancelled) {
            return closed || cancelled.get() || Thread.currentThread().isInterrupted();
        }
    }

    private record WriterHandle(
            CompletableFuture<Void> completion,
            Thread thread,
            AtomicBoolean cancelled,
            AtomicBoolean recognizerCloseDeferred
    ) {

        private WriterHandle(CompletableFuture<Void> completion, Thread thread, AtomicBoolean cancelled) {
            this(completion, thread, cancelled, new AtomicBoolean(false));
        }

        private void cancel() {
            cancelled.set(true);
        }

        private void deferRecognizerClose() {
            recognizerCloseDeferred.set(true);
        }

        private boolean isRecognizerCloseDeferred() {
            return recognizerCloseDeferred.get();
        }
    }
}
