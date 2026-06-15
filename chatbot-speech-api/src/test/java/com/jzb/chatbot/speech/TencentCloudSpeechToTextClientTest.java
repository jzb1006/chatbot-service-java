package com.jzb.chatbot.speech;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class TencentCloudSpeechToTextClientTest {

    @Test
    void shouldCallTencentApiWithCombinedAudioFrames() {
        var api = new CapturingTencentSentenceRecognitionApi("你好");
        var config = new TencentCloudSpeechToTextConfig(
                "secret-id",
                "secret-key",
                "ap-guangzhou",
                "asr.tencentcloudapi.com",
                "16k_zh",
                "opus",
                16000,
                Duration.ofSeconds(3)
        );
        var client = new TencentCloudSpeechToTextClient(config, api);

        var text = client.transcribe(List.of(
                ByteBuffer.wrap(new byte[] {1, 2}),
                ByteBuffer.wrap(new byte[] {3})
        ));

        assertThat(text).isEqualTo("你好");
        assertThat(api.request.engineModelType()).isEqualTo("16k_zh");
        assertThat(api.request.voiceFormat()).isEqualTo("opus");
        assertThat(api.request.sampleRate()).isEqualTo(16000);
        assertThat(api.request.audioBytes()).isEqualTo(3);
        assertThat(api.request.audio()).isEqualTo(Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}));
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
                "opus",
                16000,
                Duration.ofSeconds(3)
        );
        var client = new TencentCloudSpeechToTextClient(config, api);

        var text = client.transcribe(List.of());

        assertThat(text).isBlank();
        assertThat(api.request()).isNull();
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
