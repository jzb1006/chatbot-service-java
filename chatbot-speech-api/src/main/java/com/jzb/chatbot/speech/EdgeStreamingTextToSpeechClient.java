package com.jzb.chatbot.speech;

import com.jzb.chatbot.common.id.VoiceId;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Edge 在线朗读流式 TTS 客户端。
 * <p>
 * Edge Read Aloud 每次请求只合成一段 SSML，本实现按句子顺序发起请求，并在收到 raw PCM payload
 * 时立即编码为小智协议需要的 Opus 帧。
 */
public class EdgeStreamingTextToSpeechClient implements StreamingTextToSpeechClient {

    private static final int OPUS_FRAME_DURATION_MS = 60;
    private static final String RAW_16K_PCM_OUTPUT_FORMAT = "raw-16khz-16bit-mono-pcm";
    private static final String END_MARKER = new String(new char[] {0});

    private final EdgeTextToSpeechConfig config;
    private final EdgeTtsTransport transport;

    public EdgeStreamingTextToSpeechClient(EdgeTextToSpeechConfig config) {
        this(config, EdgeTtsTransport.javaNetHttp());
    }

    EdgeStreamingTextToSpeechClient(EdgeTextToSpeechConfig config, EdgeTtsTransport transport) {
        this.config = config == null ? EdgeTextToSpeechConfig.defaults() : config;
        this.transport = transport;
    }

    @Override
    public StreamingTextToSpeechSession open(TextToSpeechOptions options, StreamingTextToSpeechListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        var effectiveOptions = options == null ? TextToSpeechOptions.defaults() : options;
        var session = new EdgeStreamingTextToSpeechSession(effectiveOptions, listener);
        session.start();
        return session;
    }

    private EdgeTtsRequest request(String text, TextToSpeechOptions options) {
        return new EdgeTtsRequest(
                text,
                resolveVoice(options.voiceId()),
                streamingOutputFormat(),
                config.sampleRate(),
                edgeRate(options.speed()),
                edgePitch(options.pitch())
        );
    }

    private String streamingOutputFormat() {
        if (config.outputFormat() != null && config.outputFormat().startsWith("raw-")) {
            return config.outputFormat();
        }
        return RAW_16K_PCM_OUTPUT_FORMAT;
    }

    private String resolveVoice(VoiceId voiceId) {
        if (voiceId == null || voiceId.value().isBlank() || "default".equals(voiceId.value())) {
            return config.voice();
        }
        return voiceId.value();
    }

    private String edgeRate(double speed) {
        var percent = Math.round((speed - 1.0) * 100.0);
        return signed(percent, "%");
    }

    private String edgePitch(double pitch) {
        var hertz = Math.round((pitch - 1.0) * 100.0);
        return signed(hertz, "Hz");
    }

    private String signed(long value, String unit) {
        return value >= 0 ? "+" + value + unit : value + unit;
    }

    private final class EdgeStreamingTextToSpeechSession implements StreamingTextToSpeechSession {

        private final TextToSpeechOptions options;
        private final StreamingTextToSpeechListener listener;
        private final LinkedBlockingQueue<String> texts = new LinkedBlockingQueue<>();
        private final CompletableFuture<Boolean> finalResult = new CompletableFuture<>();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

        private EdgeStreamingTextToSpeechSession(
                TextToSpeechOptions options,
                StreamingTextToSpeechListener listener
        ) {
            this.options = options;
            this.listener = listener;
        }

        private void start() {
            Thread.startVirtualThread(this::run);
            listener.onReady();
        }

        @Override
        public void sendText(String text) {
            if (text == null || text.isBlank() || completed.get() || cancelled.get() || finalResult.isDone()) {
                return;
            }
            texts.offer(text);
        }

        @Override
        public void complete() {
            if (completed.compareAndSet(false, true)) {
                texts.offer(END_MARKER);
            }
        }

        @Override
        public void cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            texts.offer(END_MARKER);
            finalResult.complete(false);
        }

        @Override
        public boolean awaitFinal(Duration timeout) {
            var effectiveTimeout = timeout == null || timeout.isZero() || timeout.isNegative()
                    ? Duration.ofSeconds(30)
                    : timeout;
            try {
                return finalResult.get(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException exception) {
                return false;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                fail(new IllegalStateException("interrupted while waiting Edge streaming TTS final", exception));
                return true;
            } catch (Exception exception) {
                throw new IllegalStateException("Edge streaming TTS final wait failed", exception);
            }
        }

        @Override
        public void close() {
            if (!finalResult.isDone()) {
                cancel();
            }
        }

        private void run() {
            try {
                while (!cancelled.get()) {
                    var text = texts.take();
                    if (END_MARKER.equals(text)) {
                        break;
                    }
                    synthesizeText(text);
                }
                if (!cancelled.get() && failure.get() == null) {
                    listener.onCompleted();
                    finalResult.complete(true);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                fail(new IllegalStateException("Edge streaming TTS worker interrupted", exception));
            } catch (RuntimeException exception) {
                fail(exception);
            }
        }

        private void synthesizeText(String text) {
            var encoder = new StreamingPcmToOpusEncoder(config.sampleRate(), OPUS_FRAME_DURATION_MS);
            transport.stream(request(text, options), config.timeout(), new EdgeTtsTransport.StreamingListener() {
                @Override
                public void onAudio(byte[] audio) {
                    if (cancelled.get() || failure.get() != null) {
                        return;
                    }
                    emitFrames(encoder.accept(audio));
                }

                @Override
                public void onCompleted() {
                    if (cancelled.get() || failure.get() != null) {
                        return;
                    }
                    emitFrames(encoder.flush());
                }

                @Override
                public void onFailed(RuntimeException exception) {
                    fail(exception);
                }
            });
            var currentFailure = failure.get();
            if (currentFailure != null) {
                throw currentFailure;
            }
        }

        private void emitFrames(Iterable<ByteBuffer> frames) {
            for (var frame : frames) {
                if (cancelled.get() || failure.get() != null) {
                    return;
                }
                listener.onAudioFrame(frame);
            }
        }

        private void fail(RuntimeException exception) {
            if (!failure.compareAndSet(null, exception)) {
                return;
            }
            cancelled.set(true);
            listener.onFailed(exception);
            finalResult.complete(false);
        }
    }
}
