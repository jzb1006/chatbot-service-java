package com.jzb.chatbot.speech;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 腾讯云流式文本转语音客户端。
 * <p>
 * 连接 stream_wsv2 WebSocket，接收腾讯云 PCM binary 并增量编码为小智协议的 Opus 帧后回调。
 *
 * @author jiangzhibin
 * @since 2026-06-18 12:54:00
 */
public class TencentCloudStreamingTextToSpeechClient implements StreamingTextToSpeechClient {

    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_DURATION_MS = 60;

    private final Function<String, URI> signer;
    private final TencentStreamingTtsTransport transport;
    private final TencentStreamingTtsTextGuard textGuard;

    public TencentCloudStreamingTextToSpeechClient(TencentStreamingTextToSpeechConfig config) {
        this(
                new TencentStreamingTtsSigner(config, Clock.systemUTC())::sign,
                TencentStreamingTtsTransport.javaNetHttp(config.timeout())
        );
    }

    public TencentCloudStreamingTextToSpeechClient(
            Function<String, URI> signer,
            TencentStreamingTtsTransport transport
    ) {
        this(signer, transport, new TencentStreamingTtsTextGuard());
    }

    public TencentCloudStreamingTextToSpeechClient(
            Function<String, URI> signer,
            TencentStreamingTtsTransport transport,
            TencentStreamingTtsTextGuard textGuard
    ) {
        if (signer == null || transport == null || textGuard == null) {
            throw new IllegalArgumentException("signer, transport and textGuard must not be null");
        }
        this.signer = signer;
        this.transport = transport;
        this.textGuard = textGuard;
    }

    @Override
    public StreamingTextToSpeechSession open(TextToSpeechOptions options, StreamingTextToSpeechListener listener) {
        var effectiveOptions = options == null ? TextToSpeechOptions.defaults() : options;
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        var sessionId = UUID.randomUUID().toString();
        var session = new TencentStreamingTextToSpeechSession(sessionId, effectiveOptions, listener, textGuard);
        session.connect(transport, signer.apply(sessionId));
        return session;
    }

    private static final class TencentStreamingTextToSpeechSession
            implements StreamingTextToSpeechSession, TencentStreamingTtsTransport.Listener {

        private final String sessionId;
        private final TextToSpeechOptions options;
        private final StreamingTextToSpeechListener listener;
        private final TencentStreamingTtsTextGuard textGuard;
        private final StreamingPcmToOpusEncoder encoder = new StreamingPcmToOpusEncoder(SAMPLE_RATE, FRAME_DURATION_MS);
        private final CompletableFuture<Boolean> finalResult = new CompletableFuture<>();
        private final AtomicBoolean terminalSignalled = new AtomicBoolean(false);
        private final AtomicBoolean connectionClosed = new AtomicBoolean(false);
        private final ArrayList<String> queuedText = new ArrayList<>();
        private final Object sendLock = new Object();

        private TencentStreamingTtsTransport.Connection connection;
        private boolean ready;
        private boolean completeRequested;
        private boolean completeCommandSent;
        private int messageIndex;

        private TencentStreamingTextToSpeechSession(
                String sessionId,
                TextToSpeechOptions options,
                StreamingTextToSpeechListener listener,
                TencentStreamingTtsTextGuard textGuard
        ) {
            this.sessionId = sessionId;
            this.options = options;
            this.listener = listener;
            this.textGuard = textGuard;
        }

        private void connect(TencentStreamingTtsTransport transport, URI uri) {
            this.connection = transport.connect(uri, this);
        }

        @Override
        public void sendText(String text) {
            try {
                textGuard.validate(text);
            } catch (IllegalArgumentException exception) {
                fail(exception);
                return;
            }
            var sendNow = false;
            synchronized (this) {
                if (terminalSignalled.get()) {
                    return;
                }
                if (ready) {
                    sendNow = true;
                } else {
                    queuedText.add(text);
                }
            }
            if (sendNow) {
                sendSynthesis(text);
            }
        }

        @Override
        public void complete() {
            var sendNow = false;
            synchronized (this) {
                if (terminalSignalled.get()) {
                    return;
                }
                if (ready) {
                    sendNow = true;
                } else {
                    completeRequested = true;
                }
            }
            if (sendNow) {
                sendComplete();
            }
        }

        @Override
        public void cancel() {
            if (!terminalSignalled.compareAndSet(false, true)) {
                return;
            }
            try {
                if (connection != null) {
                    connection.sendText(command("ACTION_RESET", null));
                }
            } finally {
                finalResult.complete(false);
                closeConnection();
            }
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
                return false;
            } catch (Exception exception) {
                throw new IllegalStateException("Tencent streaming TTS final wait failed", exception);
            }
        }

        @Override
        public void close() {
            if (terminalSignalled.get()) {
                closeConnection();
                return;
            }
            cancel();
        }

        @Override
        public void onText(String payload) {
            var code = intField(payload, "code", 0);
            if (code != 0) {
                fail(tencentFailure(payload, code));
                return;
            }
            if (intField(payload, "heartbeat", 0) == 1) {
                return;
            }
            if (intField(payload, "ready", 0) == 1) {
                markReady();
            }
            if (intField(payload, "final", 0) == 1) {
                completeFinal();
            }
        }

        @Override
        public void onBinary(ByteBuffer payload) {
            if (terminalSignalled.get() || payload == null || !payload.hasRemaining()) {
                return;
            }
            var input = payload.slice();
            var pcm = new byte[input.remaining()];
            input.get(pcm);
            for (var frame : encoder.accept(pcm)) {
                listener.onAudioFrame(frame);
            }
        }

        @Override
        public void onError(RuntimeException exception) {
            fail(exception);
        }

        @Override
        public void onClose(int statusCode, String reason) {
            if (!terminalSignalled.get()) {
                fail(new IllegalStateException("Tencent streaming TTS closed before final: statusCode="
                        + statusCode + ", reason=" + reason));
            }
        }

        private void markReady() {
            ArrayList<String> texts;
            boolean sendComplete;
            synchronized (this) {
                if (ready || terminalSignalled.get()) {
                    return;
                }
                ready = true;
                texts = new ArrayList<>(queuedText);
                queuedText.clear();
                sendComplete = completeRequested;
                completeRequested = false;
            }
            synchronized (sendLock) {
                texts.forEach(this::sendSynthesisLocked);
                listener.onReady();
                if (sendComplete) {
                    sendCompleteLocked();
                }
            }
        }

        private void sendSynthesis(String text) {
            synchronized (sendLock) {
                sendSynthesisLocked(text);
            }
        }

        private void sendComplete() {
            synchronized (sendLock) {
                sendCompleteLocked();
            }
        }

        private void sendSynthesisLocked(String text) {
            if (terminalSignalled.get()) {
                return;
            }
            connection.sendText(commandLocked("ACTION_SYNTHESIS", text));
        }

        private void sendCompleteLocked() {
            synchronized (this) {
                if (completeCommandSent || terminalSignalled.get()) {
                    return;
                }
                completeCommandSent = true;
            }
            connection.sendText(commandLocked("ACTION_COMPLETE", null));
        }

        private void completeFinal() {
            if (!terminalSignalled.compareAndSet(false, true)) {
                return;
            }
            for (var frame : encoder.flush()) {
                listener.onAudioFrame(frame);
            }
            listener.onCompleted();
            finalResult.complete(true);
            closeConnection();
        }

        private void fail(RuntimeException exception) {
            if (!terminalSignalled.compareAndSet(false, true)) {
                return;
            }
            listener.onFailed(exception);
            finalResult.complete(false);
            closeConnection();
        }

        private void closeConnection() {
            if (connection != null && connectionClosed.compareAndSet(false, true)) {
                connection.close();
            }
        }

        private String command(String action, String data) {
            synchronized (sendLock) {
                return commandLocked(action, data);
            }
        }

        private String commandLocked(String action, String data) {
            var payload = new StringBuilder();
            payload.append("{\"session_id\":\"").append(escapeJson(sessionId)).append('"');
            payload.append(",\"message_id\":\"").append(escapeJson(sessionId)).append('-').append(++messageIndex).append('"');
            payload.append(",\"action\":\"").append(action).append('"');
            if (data != null) {
                payload.append(",\"data\":\"").append(escapeJson(data)).append('"');
            }
            payload.append('}');
            return payload.toString();
        }

        private RuntimeException tencentFailure(String payload, int code) {
            var message = stringField(payload, "message", "");
            var requestId = stringField(payload, "request_id", "");
            return new IllegalStateException("Tencent streaming TTS failed: code=" + code
                    + ", message=" + message + ", request_id=" + requestId);
        }

        private static int intField(String payload, String field, int defaultValue) {
            var matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+)").matcher(payload);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : defaultValue;
        }

        private static String stringField(String payload, String field, String defaultValue) {
            var matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                    .matcher(payload);
            return matcher.find() ? matcher.group(1) : defaultValue;
        }

        private static String escapeJson(String value) {
            var escaped = new StringBuilder();
            for (var index = 0; index < value.length(); index++) {
                var character = value.charAt(index);
                switch (character) {
                    case '"' -> escaped.append("\\\"");
                    case '\\' -> escaped.append("\\\\");
                    case '\b' -> escaped.append("\\b");
                    case '\f' -> escaped.append("\\f");
                    case '\n' -> escaped.append("\\n");
                    case '\r' -> escaped.append("\\r");
                    case '\t' -> escaped.append("\\t");
                    default -> {
                        if (character < 0x20) {
                            escaped.append(String.format("\\u%04x", (int) character));
                        } else {
                            escaped.append(character);
                        }
                    }
                }
            }
            return escaped.toString();
        }
    }
}
