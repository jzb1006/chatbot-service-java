package com.jzb.chatbot.speech;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于 JDK WebSocket 的 Edge TTS 传输实现。
 * <p>
 * Edge Read Aloud 不是公开后端 API，协议细节集中在该类内，避免污染通用 TTS 边界。
 *
 * @author jiangzhibin
 * @since 2026-06-21 09:28:00
 */
final class JavaNetHttpEdgeTtsTransport implements EdgeTtsTransport {

    private static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String CHROMIUM_FULL_VERSION = "143.0.3650.75";
    private static final String CHROMIUM_MAJOR_VERSION = "143";
    private static final String SEC_MS_GEC_VERSION = "1-" + CHROMIUM_FULL_VERSION;
    private static final long WINDOWS_EPOCH_SECONDS = 11_644_473_600L;
    private static final String WSS_URL = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter
            .ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT'Z '(Coordinated Universal Time)'")
            .withLocale(Locale.US)
            .withZone(ZoneOffset.UTC);

    private final HttpClient httpClient;

    JavaNetHttpEdgeTtsTransport(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public byte[] synthesize(EdgeTtsRequest request, Duration timeout) {
        var listener = new CollectingEdgeStreamingListener();
        stream(request, timeout, listener);
        var failure = listener.failure();
        if (failure != null) {
            throw new IllegalStateException("Edge TTS failed", failure);
        }
        var audio = listener.audio();
        if (audio.length == 0) {
            throw new IllegalStateException("Edge TTS returned no audio");
        }
        return audio;
    }

    @Override
    public void stream(EdgeTtsRequest request, Duration timeout, StreamingListener listener) {
        var failure = streamOnce(request, timeout, listener);
        if (failure != null) {
            listener.onFailed(new IllegalStateException("Edge TTS failed", failure));
            return;
        }
        listener.onCompleted();
    }

    private Throwable streamOnce(EdgeTtsRequest request, Duration timeout, StreamingListener listener) {
        var webSocketListener = new EdgeWebSocketListener(listener);
        try {
            var webSocket = httpClient.newWebSocketBuilder()
                    .header("Pragma", "no-cache")
                    .header("Cache-Control", "no-cache")
                    .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                    .header("User-Agent", userAgent())
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Cookie", "muid=" + UUID.randomUUID().toString().replace("-", "").toUpperCase() + ";")
                    .connectTimeout(timeout)
                    .buildAsync(edgeUri(), webSocketListener)
                    .join();
            webSocket.sendText(speechConfig(request.outputFormat()), true).join();
            webSocket.sendText(ssmlMessage(request), true).join();
            if (!webSocketListener.await(timeout)) {
                webSocket.abort();
                return new IllegalStateException("Edge TTS timed out");
            }
            return webSocketListener.failure();
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    private URI edgeUri() {
        return URI.create(WSS_URL
                + "?TrustedClientToken=" + TRUSTED_CLIENT_TOKEN
                + "&ConnectionId=" + connectId()
                + "&Sec-MS-GEC=" + secMsGec()
                + "&Sec-MS-GEC-Version=" + SEC_MS_GEC_VERSION);
    }

    private String speechConfig(String outputFormat) {
        return "X-Timestamp:" + timestamp() + "\r\n"
                + "Content-Type:application/json; charset=utf-8\r\n"
                + "Path:speech.config\r\n\r\n"
                + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
                + "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\""
                + "},\"outputFormat\":\"" + jsonEscape(outputFormat) + "\"}}}}\r\n";
    }

    private String ssmlMessage(EdgeTtsRequest request) {
        return "X-RequestId:" + connectId() + "\r\n"
                + "Content-Type:application/ssml+xml\r\n"
                + "X-Timestamp:" + timestamp() + "Z\r\n"
                + "Path:ssml\r\n\r\n"
                + ssml(request);
    }

    private String ssml(EdgeTtsRequest request) {
        var voice = xmlEscape(request.voice());
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>"
                + "<voice name='" + voice + "'>"
                + "<prosody pitch='" + xmlEscape(request.pitch()) + "' rate='" + xmlEscape(request.rate()) + "'>"
                + xmlEscape(cleanText(request.text()))
                + "</prosody></voice></speak>";
    }

    private String timestamp() {
        return TIMESTAMP_FORMATTER.format(Instant.now());
    }

    private String secMsGec() {
        var seconds = Instant.now().getEpochSecond() + WINDOWS_EPOCH_SECONDS;
        seconds -= seconds % 300;
        var ticks = seconds * 10_000_000L;
        return sha256Hex(ticks + TRUSTED_CLIENT_TOKEN);
    }

    private String sha256Hex(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest).toUpperCase();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String connectId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String userAgent() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/" + CHROMIUM_MAJOR_VERSION + ".0.0.0 Safari/537.36 "
                + "Edg/" + CHROMIUM_MAJOR_VERSION + ".0.0.0";
    }

    private String cleanText(String text) {
        var builder = new StringBuilder(text.length());
        for (var index = 0; index < text.length(); ) {
            var codePoint = text.codePointAt(index);
            if ((codePoint >= 0 && codePoint <= 8) || (codePoint >= 11 && codePoint <= 12)
                    || (codePoint >= 14 && codePoint <= 31)) {
                builder.append(' ');
            } else {
                builder.appendCodePoint(codePoint);
            }
            index += Character.charCount(codePoint);
        }
        return builder.toString();
    }

    private String xmlEscape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class CollectingEdgeStreamingListener implements StreamingListener {

        private final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

        @Override
        public void onAudio(byte[] audio) {
            this.audio.writeBytes(audio);
        }

        @Override
        public void onCompleted() {
        }

        @Override
        public void onFailed(RuntimeException exception) {
            failure.compareAndSet(null, exception);
        }

        private RuntimeException failure() {
            return failure.get();
        }

        private byte[] audio() {
            return audio.toByteArray();
        }
    }

    private static final class EdgeWebSocketListener implements WebSocket.Listener {

        private final CountDownLatch done = new CountDownLatch(1);
        private final StreamingListener listener;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();
        private StringBuilder textBuffer = new StringBuilder();

        private EdgeWebSocketListener(StreamingListener listener) {
            this.listener = listener;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener.super.onOpen(webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                var message = textBuffer.toString();
                textBuffer = new StringBuilder();
                if (message.contains("Path:turn.end")) {
                    done.countDown();
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            while (data.hasRemaining()) {
                binaryBuffer.write(data.get());
            }
            if (last) {
                var message = binaryBuffer.toByteArray();
                binaryBuffer = new ByteArrayOutputStream();
                var payload = audioPayload(message);
                if (payload.length > 0) {
                    listener.onAudio(payload);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            failure.compareAndSet(null, error);
            done.countDown();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (statusCode != WebSocket.NORMAL_CLOSURE) {
                failure.compareAndSet(null, new IllegalStateException(
                        "Edge TTS websocket closed, statusCode=" + statusCode + ", reason=" + reason
                ));
            }
            done.countDown();
            return null;
        }

        private boolean await(Duration timeout) {
            try {
                return done.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                failure.compareAndSet(null, exception);
                return true;
            }
        }

        private Throwable failure() {
            return failure.get();
        }

        private byte[] audioPayload(byte[] message) {
            if (message.length < Short.BYTES) {
                return new byte[0];
            }
            var headerLength = ((message[0] & 0xff) << 8) | (message[1] & 0xff);
            var payloadStart = Short.BYTES + headerLength;
            if (payloadStart > message.length) {
                return new byte[0];
            }
            var headers = new String(message, Short.BYTES, headerLength, StandardCharsets.UTF_8);
            if (!headers.contains("Path:audio")) {
                return new byte[0];
            }
            var payload = new byte[message.length - payloadStart];
            System.arraycopy(message, payloadStart, payload, 0, payload.length);
            return payload;
        }
    }
}
