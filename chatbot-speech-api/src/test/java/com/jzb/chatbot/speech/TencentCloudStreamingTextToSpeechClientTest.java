package com.jzb.chatbot.speech;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TencentCloudStreamingTextToSpeechClientTest {

    @Test
    void shouldWaitReadyThenSendSynthesisAndCompleteCommands() {
        var transport = new FakeTencentStreamingTtsTransport();
        var client = new TencentCloudStreamingTextToSpeechClient(
                sessionId -> URI.create("wss://tts.cloud.tencent.com/stream_wsv2?SessionId=" + sessionId),
                transport
        );
        var listener = new RecordingListener();

        try (var session = client.open(TextToSpeechOptions.defaults(), listener)) {
            transport.emitText("{\"code\":0,\"ready\":1,\"final\":0}");
            session.sendText("第一句。");
            session.complete();
            transport.emitText("{\"code\":0,\"ready\":0,\"final\":1}");

            assertThat(session.awaitFinal(Duration.ofMillis(100))).isTrue();
        }

        assertThat(listener.ready).isTrue();
        assertThat(listener.completed).isTrue();
        assertThat(transport.sentText).anySatisfy(payload -> assertThat(payload)
                .contains("\"action\":\"ACTION_SYNTHESIS\"", "\"data\":\"第一句。\""));
        assertThat(transport.sentText).anySatisfy(payload -> assertThat(payload)
                .contains("\"action\":\"ACTION_COMPLETE\""));
    }

    @Test
    void shouldConvertBinaryPcmToOpusFrames() {
        var transport = new FakeTencentStreamingTtsTransport();
        var client = new TencentCloudStreamingTextToSpeechClient(
                sessionId -> URI.create("wss://tts.cloud.tencent.com/stream_wsv2?SessionId=" + sessionId),
                transport
        );
        var listener = new RecordingListener();

        try (var ignored = client.open(TextToSpeechOptions.defaults(), listener)) {
            transport.emitText("{\"code\":0,\"ready\":1,\"final\":0}");
            transport.emitBinary(ByteBuffer.allocate(960 * Short.BYTES));
        }

        assertThat(listener.frames).hasSize(1);
        assertThat(listener.frames.getFirst().remaining()).isNotEqualTo(960 * Short.BYTES);
    }

    @Test
    void shouldFailOnNonZeroTencentCode() {
        var transport = new FakeTencentStreamingTtsTransport();
        var client = new TencentCloudStreamingTextToSpeechClient(
                sessionId -> URI.create("wss://tts.cloud.tencent.com/stream_wsv2?SessionId=" + sessionId),
                transport
        );
        var listener = new RecordingListener();

        try (var ignored = client.open(TextToSpeechOptions.defaults(), listener)) {
            transport.emitText("{\"code\":10001,\"message\":\"参数不合法\"}");
        }

        assertThat(listener.failure).hasMessageContaining("10001");
    }

    @Test
    void shouldRejectSsmlBeforeSendingTextToTencent() {
        var transport = new FakeTencentStreamingTtsTransport();
        var client = new TencentCloudStreamingTextToSpeechClient(
                sessionId -> URI.create("wss://tts.cloud.tencent.com/stream_wsv2?SessionId=" + sessionId),
                transport
        );
        var listener = new RecordingListener();

        try (var session = client.open(TextToSpeechOptions.defaults(), listener)) {
            transport.emitText("{\"code\":0,\"ready\":1,\"final\":0}");
            session.sendText("<speak>第一句。</speak>");
        }

        assertThat(listener.failure).hasMessageContaining("SSML");
        assertThat(transport.sentText).noneSatisfy(payload -> assertThat(payload)
                .contains("\"action\":\"ACTION_SYNTHESIS\""));
    }

    @Test
    void shouldRejectOversizedTextBeforeSendingTextToTencent() {
        var transport = new FakeTencentStreamingTtsTransport();
        var client = new TencentCloudStreamingTextToSpeechClient(
                sessionId -> URI.create("wss://tts.cloud.tencent.com/stream_wsv2?SessionId=" + sessionId),
                transport,
                new TencentStreamingTtsTextGuard(8)
        );
        var listener = new RecordingListener();

        try (var session = client.open(TextToSpeechOptions.defaults(), listener)) {
            transport.emitText("{\"code\":0,\"ready\":1,\"final\":0}");
            session.sendText("这句话超过八个字符。");
        }

        assertThat(listener.failure).hasMessageContaining("too long");
        assertThat(transport.sentText).noneSatisfy(payload -> assertThat(payload)
                .contains("\"action\":\"ACTION_SYNTHESIS\""));
    }

    @Test
    void shouldSendCompleteCommandOnlyOnce() {
        var transport = new FakeTencentStreamingTtsTransport();
        var client = new TencentCloudStreamingTextToSpeechClient(
                sessionId -> URI.create("wss://tts.cloud.tencent.com/stream_wsv2?SessionId=" + sessionId),
                transport
        );
        var listener = new RecordingListener();

        try (var session = client.open(TextToSpeechOptions.defaults(), listener)) {
            transport.emitText("{\"code\":0,\"ready\":1,\"final\":0}");
            session.complete();
            session.complete();
        }

        assertThat(transport.sentText)
                .filteredOn(payload -> payload.contains("\"action\":\"ACTION_COMPLETE\""))
                .hasSize(1);
    }

    @Test
    void shouldDrainQueuedTextBeforeReadyCallbackReentrantTextAndComplete() {
        var transport = new FakeTencentStreamingTtsTransport();
        var client = new TencentCloudStreamingTextToSpeechClient(
                sessionId -> URI.create("wss://tts.cloud.tencent.com/stream_wsv2?SessionId=" + sessionId),
                transport
        );
        var listener = new ReadyCallbackListener();

        try (var session = client.open(TextToSpeechOptions.defaults(), listener)) {
            listener.session = session;
            session.sendText("第一句。");

            transport.emitText("{\"code\":0,\"ready\":1,\"final\":0}");
        }

        assertThat(transport.sentText)
                .filteredOn(payload -> payload.contains("\"action\":\"ACTION_SYNTHESIS\"")
                        || payload.contains("\"action\":\"ACTION_COMPLETE\""))
                .extracting(TencentCloudStreamingTextToSpeechClientTest::commandActionAndData)
                .containsExactly(
                        "ACTION_SYNTHESIS:第一句。",
                        "ACTION_SYNTHESIS:第二句。",
                        "ACTION_COMPLETE:"
                );
    }

    @Test
    void shouldFailWhenConnectionClosesBeforeFinalState() {
        var transport = new FakeTencentStreamingTtsTransport();
        var client = new TencentCloudStreamingTextToSpeechClient(
                sessionId -> URI.create("wss://tts.cloud.tencent.com/stream_wsv2?SessionId=" + sessionId),
                transport
        );
        var listener = new RecordingListener();

        try (var session = client.open(TextToSpeechOptions.defaults(), listener)) {
            transport.emitText("{\"code\":0,\"ready\":1,\"final\":0}");
            transport.emitClose(1006, "abnormal close");

            assertThat(session.awaitFinal(Duration.ofMillis(100))).isFalse();
        }

        assertThat(listener.failure).hasMessageContaining("closed before final");
    }

    private static String commandActionAndData(String payload) {
        var action = stringField(payload, "action");
        var data = stringField(payload, "data");
        return action + ":" + data;
    }

    private static String stringField(String payload, String field) {
        var prefix = "\"" + field + "\":\"";
        var start = payload.indexOf(prefix);
        if (start < 0) {
            return "";
        }
        var valueStart = start + prefix.length();
        var valueEnd = payload.indexOf('"', valueStart);
        return payload.substring(valueStart, valueEnd);
    }

    private static final class FakeTencentStreamingTtsTransport implements TencentStreamingTtsTransport {

        private final List<String> sentText = new ArrayList<>();
        private Listener listener;
        private int closeCount;

        @Override
        public Connection connect(URI uri, Listener listener) {
            this.listener = listener;
            return new Connection() {
                @Override
                public void sendText(String payload) {
                    sentText.add(payload);
                }

                @Override
                public void close() {
                    closeCount++;
                }
            };
        }

        private void emitText(String payload) {
            listener.onText(payload);
        }

        private void emitBinary(ByteBuffer payload) {
            listener.onBinary(payload);
        }

        private void emitClose(int statusCode, String reason) {
            listener.onClose(statusCode, reason);
        }
    }

    private static final class ReadyCallbackListener extends RecordingListener {

        private StreamingTextToSpeechSession session;

        @Override
        public void onReady() {
            super.onReady();
            session.sendText("第二句。");
            session.complete();
        }
    }

    private static class RecordingListener implements StreamingTextToSpeechListener {

        private boolean ready;
        private boolean completed;
        private RuntimeException failure;
        private final List<ByteBuffer> frames = new ArrayList<>();

        @Override
        public void onReady() {
            ready = true;
        }

        @Override
        public void onAudioFrame(ByteBuffer frame) {
            frames.add(frame);
        }

        @Override
        public void onCompleted() {
            completed = true;
        }

        @Override
        public void onFailed(RuntimeException exception) {
            failure = exception;
        }
    }
}
