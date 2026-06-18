package com.jzb.chatbot.speech;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * 腾讯云流式 TTS 传输层。
 * <p>
 * 隔离 Java WebSocket 细节，让流式 TTS client 可以在单元测试中使用 fake transport。
 *
 * @author jiangzhibin
 * @since 2026-06-18 12:54:00
 */
public interface TencentStreamingTtsTransport {

    /**
     * 建立 WebSocket 连接。
     *
     * @param uri 已签名腾讯云 WebSocket URI
     * @param listener 上游消息监听器
     * @return WebSocket 连接
     */
    Connection connect(URI uri, Listener listener);

    /**
     * 创建基于 JDK WebSocket 的传输层。
     *
     * @param connectTimeout 连接超时
     * @return 传输层实例
     */
    static TencentStreamingTtsTransport javaNetHttp(Duration connectTimeout) {
        return new JavaNetHttpTencentStreamingTtsTransport(connectTimeout);
    }

    /**
     * 腾讯云 WebSocket 连接。
     */
    interface Connection {

        /**
         * 发送 text frame。
         *
         * @param payload JSON payload
         */
        void sendText(String payload);

        /**
         * 关闭连接。
         */
        void close();
    }

    /**
     * 腾讯云 WebSocket 消息监听器。
     */
    interface Listener {

        /**
         * 收到 text frame。
         *
         * @param payload JSON payload
         */
        void onText(String payload);

        /**
         * 收到 binary frame。
         *
         * @param payload PCM binary payload
         */
        void onBinary(ByteBuffer payload);

        /**
         * 连接或传输失败。
         *
         * @param exception 失败异常
         */
        default void onError(RuntimeException exception) {
        }

        /**
         * 连接关闭。
         *
         * @param statusCode WebSocket close status
         * @param reason close reason
         */
        default void onClose(int statusCode, String reason) {
        }
    }
}

final class JavaNetHttpTencentStreamingTtsTransport implements TencentStreamingTtsTransport {

    private final HttpClient httpClient;

    JavaNetHttpTencentStreamingTtsTransport(Duration connectTimeout) {
        var timeout = connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                ? Duration.ofSeconds(30)
                : connectTimeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public Connection connect(URI uri, Listener listener) {
        var webSocket = httpClient.newWebSocketBuilder()
                .buildAsync(uri, new JavaNetHttpListener(listener))
                .join();
        return new JavaNetHttpConnection(webSocket);
    }

    private record JavaNetHttpConnection(WebSocket webSocket) implements Connection {

        @Override
        public void sendText(String payload) {
            webSocket.sendText(payload, true).join();
        }

        @Override
        public void close() {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "closed").join();
        }
    }

    private static final class JavaNetHttpListener implements WebSocket.Listener {

        private final Listener listener;
        private final StringBuilder text = new StringBuilder();
        private final ByteArrayOutputStream binary = new ByteArrayOutputStream();

        private JavaNetHttpListener(Listener listener) {
            this.listener = listener;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                listener.onText(text.toString());
                text.setLength(0);
            }
            webSocket.request(1);
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            var input = data.slice();
            var bytes = new byte[input.remaining()];
            input.get(bytes);
            binary.writeBytes(bytes);
            if (last) {
                listener.onBinary(ByteBuffer.wrap(binary.toByteArray()));
                binary.reset();
            }
            webSocket.request(1);
            return WebSocket.Listener.super.onBinary(webSocket, data, last);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            var exception = error instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new IllegalStateException("Tencent streaming TTS WebSocket failed", error);
            listener.onError(exception);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            listener.onClose(statusCode, reason == null ? "" : reason);
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }
    }
}
