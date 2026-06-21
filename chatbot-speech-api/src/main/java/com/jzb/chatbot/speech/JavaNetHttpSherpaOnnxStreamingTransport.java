package com.jzb.chatbot.speech;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于 JDK WebSocket 的 Sherpa-ONNX 流式 ASR 传输实现。
 * <p>
 * 协议与 sherpa-onnx 官方 websocket 示例保持一致：发送 float32 音频块，文本 "Done" 表示结束。
 *
 * @author jiangzhibin
 * @since 2026-06-21 11:54:00
 */
final class JavaNetHttpSherpaOnnxStreamingTransport implements SherpaOnnxStreamingTransport {

    private static final String DONE = "Done";

    private final HttpClient httpClient;

    JavaNetHttpSherpaOnnxStreamingTransport(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public List<String> transcribe(String uri, Iterable<byte[]> audioChunks, Duration timeout) {
        var listener = new SherpaWebSocketListener();
        var webSocket = httpClient.newWebSocketBuilder()
                .connectTimeout(timeout)
                .buildAsync(URI.create(uri), listener)
                .join();
        try {
            for (var chunk : audioChunks) {
                if (chunk.length > 0) {
                    webSocket.sendBinary(java.nio.ByteBuffer.wrap(chunk), true).join();
                }
            }
            webSocket.sendText(DONE, true).join();
            if (!listener.await(timeout)) {
                webSocket.abort();
                throw new IllegalStateException("Sherpa-ONNX ASR timed out");
            }
            var failure = listener.failure();
            if (failure != null) {
                throw new IllegalStateException("Sherpa-ONNX ASR failed", failure);
            }
            return listener.messages();
        } finally {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }

    private static final class SherpaWebSocketListener implements WebSocket.Listener {

        private final CountDownLatch done = new CountDownLatch(1);
        private final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private StringBuilder textBuffer = new StringBuilder();

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
                if ("Done!".equals(message)) {
                    done.countDown();
                } else {
                    messages.add(message);
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
            if (statusCode != WebSocket.NORMAL_CLOSURE && done.getCount() > 0) {
                failure.compareAndSet(null, new IllegalStateException(
                        "Sherpa-ONNX ASR websocket closed, statusCode=" + statusCode + ", reason=" + reason
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

        private List<String> messages() {
            synchronized (messages) {
                return List.copyOf(messages);
            }
        }
    }
}
