package com.jzb.chatbot.speech;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;

/**
 * Sherpa-ONNX 流式语音识别客户端。
 * <p>
 * 输入为服务端已解码的 16-bit little-endian PCM，输出为 sherpa-onnx websocket 服务返回的最终文本。
 *
 * @author jiangzhibin
 * @since 2026-06-21 11:54:00
 */
public class SherpaOnnxStreamingSpeechToTextClient implements StreamingSpeechToTextClient {

    private static final String PROVIDER = "sherpa-onnx";

    private final SherpaOnnxSpeechToTextConfig config;
    private final SherpaOnnxStreamingTransport transport;
    private final ObjectMapper objectMapper;

    public SherpaOnnxStreamingSpeechToTextClient(SherpaOnnxSpeechToTextConfig config) {
        this(config, new JavaNetHttpSherpaOnnxStreamingTransport(java.net.http.HttpClient.newHttpClient()));
    }

    SherpaOnnxStreamingSpeechToTextClient(
            SherpaOnnxSpeechToTextConfig config,
            SherpaOnnxStreamingTransport transport
    ) {
        this.config = config;
        this.transport = transport;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public SpeechToTextResult transcribe(SpeechToTextAudioStream audioStream) {
        if (audioStream == null) {
            return SpeechToTextResult.blank(PROVIDER);
        }
        var startedAt = System.nanoTime();
        var messages = transport.transcribe(
                config.url(),
                () -> new PcmFloat32ChunkIterator(audioStream, config.chunkTimeout()),
                config.recognitionTimeout()
        );
        return new SpeechToTextResult(finalText(messages), PROVIDER, elapsedMillis(startedAt));
    }

    private String finalText(List<String> messages) {
        var result = "";
        for (var message : messages) {
            var text = text(message);
            if (!text.isBlank()) {
                result = text;
            }
        }
        return result;
    }

    private String text(String message) {
        try {
            var node = objectMapper.readTree(message);
            var text = node.path("text").asText("");
            return text == null ? "" : text;
        } catch (Exception exception) {
            throw new IllegalStateException("Sherpa-ONNX ASR returned invalid JSON", exception);
        }
    }

    private long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private static final class PcmFloat32ChunkIterator implements Iterator<byte[]> {

        private final SpeechToTextAudioStream audioStream;
        private final Duration chunkTimeout;
        private byte[] next;
        private boolean loaded;
        private boolean finished;

        private PcmFloat32ChunkIterator(SpeechToTextAudioStream audioStream, Duration chunkTimeout) {
            this.audioStream = audioStream;
            this.chunkTimeout = chunkTimeout;
        }

        @Override
        public boolean hasNext() {
            load();
            return !finished;
        }

        @Override
        public byte[] next() {
            load();
            if (finished) {
                throw new java.util.NoSuchElementException();
            }
            loaded = false;
            return next;
        }

        private void load() {
            while (!loaded && !finished) {
                var pcm = audioStream.take(chunkTimeout);
                if (audioStream.isEnd(pcm)) {
                    finished = true;
                    return;
                }
                if (pcm.length == 0) {
                    continue;
                }
                next = toFloat32(pcm);
                loaded = true;
            }
        }

        private byte[] toFloat32(byte[] pcm) {
            var input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
            var output = ByteBuffer.allocate((pcm.length / Short.BYTES) * Float.BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN);
            while (input.remaining() >= Short.BYTES) {
                output.putFloat(input.getShort() / 32768.0f);
            }
            return output.array();
        }
    }
}
