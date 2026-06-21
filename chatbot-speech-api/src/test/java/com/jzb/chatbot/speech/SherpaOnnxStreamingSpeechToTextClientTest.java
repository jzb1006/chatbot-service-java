package com.jzb.chatbot.speech;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SherpaOnnxStreamingSpeechToTextClientTest {

    @Test
    void shouldSendFloat32PcmChunksAndReturnFinalText() {
        var transport = new CapturingTransport(List.of(
                "{\"text\":\"你\",\"segment\":0}",
                "{\"text\":\"你好\",\"segment\":0}"
        ));
        var client = new SherpaOnnxStreamingSpeechToTextClient(
                new SherpaOnnxSpeechToTextConfig(
                        "ws://sherpa-asr:6006",
                        Duration.ofMillis(10),
                        Duration.ofSeconds(3)
                ),
                transport
        );
        var audioStream = new SpeechToTextAudioStream();

        audioStream.write(int16Pcm((short) -32768, (short) 0, (short) 32767));
        audioStream.complete();
        var result = client.transcribe(audioStream);

        assertThat(result.text()).isEqualTo("你好");
        assertThat(result.provider()).isEqualTo("sherpa-onnx");
        assertThat(transport.uri).isEqualTo("ws://sherpa-asr:6006");
        assertThat(transport.chunks).singleElement().satisfies(chunk ->
                assertThat(float32Samples(chunk)).containsExactly(-1.0f, 0.0f, 32767.0f / 32768.0f)
        );
    }

    @Test
    void shouldIgnoreBlankPartialResults() {
        var transport = new CapturingTransport(List.of(
                "{\"text\":\"\",\"segment\":0}",
                "{\"text\":\"测试文本\",\"segment\":0}"
        ));
        var client = new SherpaOnnxStreamingSpeechToTextClient(
                new SherpaOnnxSpeechToTextConfig(
                        "ws://sherpa-asr:6006",
                        Duration.ofMillis(10),
                        Duration.ofSeconds(3)
                ),
                transport
        );
        var audioStream = new SpeechToTextAudioStream();

        audioStream.write(int16Pcm((short) 100));
        audioStream.complete();

        assertThat(client.transcribe(audioStream).text()).isEqualTo("测试文本");
    }

    private byte[] int16Pcm(short... samples) {
        var bytes = ByteBuffer.allocate(samples.length * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (var sample : samples) {
            bytes.putShort(sample);
        }
        return bytes.array();
    }

    private List<Float> float32Samples(byte[] chunk) {
        var input = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN);
        var samples = new ArrayList<Float>();
        while (input.remaining() >= Float.BYTES) {
            samples.add(input.getFloat());
        }
        return samples;
    }

    private static final class CapturingTransport implements SherpaOnnxStreamingTransport {

        private final List<String> responses;
        private final List<byte[]> chunks = new ArrayList<>();
        private String uri;

        private CapturingTransport(List<String> responses) {
            this.responses = responses;
        }

        @Override
        public List<String> transcribe(String uri, Iterable<byte[]> audioChunks, Duration timeout) {
            this.uri = uri;
            for (var chunk : audioChunks) {
                chunks.add(chunk.clone());
            }
            return responses;
        }
    }
}
