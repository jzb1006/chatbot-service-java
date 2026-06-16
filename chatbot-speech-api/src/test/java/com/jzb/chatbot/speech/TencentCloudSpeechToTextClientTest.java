package com.jzb.chatbot.speech;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class TencentCloudSpeechToTextClientTest {

    @Test
    void shouldCallTencentApiWithDecodedPcmAudioFrames() throws Exception {
        var api = new CapturingTencentSentenceRecognitionApi("你好");
        var config = new TencentCloudSpeechToTextConfig(
                "secret-id",
                "secret-key",
                "ap-guangzhou",
                "asr.tencentcloudapi.com",
                "16k_zh",
                "pcm",
                16000,
                Duration.ofSeconds(3)
        );
        var client = new TencentCloudSpeechToTextClient(config, api);
        var firstFrame = encodeOpusFrame();
        var secondFrame = encodeOpusFrame();
        var thirdFrame = encodeOpusFrame();

        var text = client.transcribe(List.of(
                ByteBuffer.wrap(firstFrame),
                ByteBuffer.wrap(secondFrame),
                ByteBuffer.wrap(thirdFrame)
        ));

        assertThat(text).isEqualTo("你好");
        assertThat(api.request.engineModelType()).isEqualTo("16k_zh");
        assertThat(api.request.voiceFormat()).isEqualTo("pcm");
        assertThat(api.request.sampleRate()).isEqualTo(16000);
        assertThat(api.request.audioBytes()).isEqualTo(3 * 960 * Short.BYTES);
        assertThat(Base64.getDecoder().decode(api.request.audio()))
                .hasSize(3 * 960 * Short.BYTES);
    }

    @Test
    void shouldDecodeXiaozhiOpusFramesToPcmForTencentSentenceRecognition() throws Exception {
        var api = new CapturingTencentSentenceRecognitionApi("你好小智");
        var config = new TencentCloudSpeechToTextConfig(
                "secret-id",
                "secret-key",
                "ap-guangzhou",
                "asr.tencentcloudapi.com",
                "16k_zh",
                "pcm",
                16000,
                Duration.ofSeconds(3)
        );
        var client = new TencentCloudSpeechToTextClient(config, api);
        var opusFrame = encodeOpusFrame();

        var text = client.transcribe(List.of(ByteBuffer.wrap(opusFrame)));

        assertThat(text).isEqualTo("你好小智");
        assertThat(api.request.voiceFormat()).isEqualTo("pcm");
        assertThat(api.request.audioBytes()).isEqualTo(960 * Short.BYTES);
        var pcm = Base64.getDecoder().decode(api.request.audio());
        assertThat(pcm).hasSize(960 * Short.BYTES);
        assertThat(Arrays.equals(pcm, new byte[pcm.length])).isFalse();
    }

    @Test
    void shouldReturnBlankTextWhenAudioFramesAreEmpty() {
        var api = new CapturingTencentSentenceRecognitionApi("should-not-call");
        var config = new TencentCloudSpeechToTextConfig(
                "secret-id",
                "secret-key",
                "ap-guangzhou",
                "asr.tencentcloudapi.com",
                "16k_zh",
                "pcm",
                16000,
                Duration.ofSeconds(3)
        );
        var client = new TencentCloudSpeechToTextClient(config, api);

        var text = client.transcribe(List.of());

        assertThat(text).isBlank();
        assertThat(api.request()).isNull();
    }

    @Test
    void shouldUsePcmAsDefaultVoiceFormat() {
        var config = new TencentCloudSpeechToTextConfig(
                "secret-id",
                "secret-key",
                "ap-guangzhou",
                "asr.tencentcloudapi.com",
                "16k_zh",
                "",
                16000,
                Duration.ofSeconds(3)
        );

        assertThat(config.voiceFormat()).isEqualTo("pcm");
    }

    @Test
    void shouldNotSetInputSampleRateFor16kPcmSentenceRecognition() {
        var sdkRequest = TencentCloudSentenceRecognitionApi.toSdkRequest(new TencentSentenceRecognitionRequest(
                "base64-audio",
                10,
                "16k_zh",
                "pcm",
                16000
        ));

        assertThat(sdkRequest.getInputSampleRate()).isNull();
    }

    @Test
    void shouldSetInputSampleRateFor8kPcmSentenceRecognition() {
        var sdkRequest = TencentCloudSentenceRecognitionApi.toSdkRequest(new TencentSentenceRecognitionRequest(
                "base64-audio",
                10,
                "8k_zh",
                "pcm",
                8000
        ));

        assertThat(sdkRequest.getInputSampleRate()).isEqualTo(8000L);
    }

    private byte[] encodeOpusFrame() throws Exception {
        var samples = new short[960];
        for (var index = 0; index < samples.length; index++) {
            samples[index] = (short) (Math.sin(index / 8.0) * 6_000);
        }
        var encoder = new OpusEncoder(16000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        var output = new byte[4096];
        var encodedBytes = encoder.encode(samples, 0, samples.length, output, 0, output.length);
        return Arrays.copyOf(output, encodedBytes);
    }

    private static class CapturingTencentSentenceRecognitionApi implements TencentSentenceRecognitionApi {

        private final String text;
        private TencentSentenceRecognitionRequest request;

        private CapturingTencentSentenceRecognitionApi(String text) {
            this.text = text;
        }

        @Override
        public String recognize(TencentSentenceRecognitionRequest request) {
            this.request = request;
            return text;
        }

        private TencentSentenceRecognitionRequest request() {
            return request;
        }
    }
}
