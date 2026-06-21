package com.jzb.chatbot.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jzb.chatbot.common.id.VoiceId;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class TencentCloudTextToSpeechClientTest {

    @Test
    void shouldUsePremiumVoiceByDefault() {
        var config = new TencentCloudTextToSpeechConfig(
                "secret-id",
                "secret-key",
                null,
                null,
                null,
                null,
                0,
                null
        );

        assertThat(config.voiceType()).isEqualTo("101001");
    }

    @Test
    void shouldCallTencentApiAndEncodePcmToOpusFrames() {
        var pcm = ByteBuffer.allocate(960 * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (var index = 0; index < 960; index++) {
            pcm.putShort((short) 0);
        }
        var api = new CapturingTencentTextToVoiceApi(Base64.getEncoder().encodeToString(pcm.array()));
        var config = new TencentCloudTextToSpeechConfig(
                "secret-id",
                "secret-key",
                "ap-guangzhou",
                "tts.tencentcloudapi.com",
                "101001",
                "pcm",
                16000,
                Duration.ofSeconds(3)
        );
        var client = new TencentCloudTextToSpeechClient(config, api);

        var frames = client.synthesize("你好", new VoiceId("default"));

        assertThat(frames).isNotEmpty();
        assertThat(frames.getFirst().remaining()).isGreaterThan(0);
        assertThat(api.request.text()).isEqualTo("你好");
        assertThat(api.request.voiceType()).isEqualTo("101001");
        assertThat(api.request.codec()).isEqualTo("pcm");
        assertThat(api.request.sampleRate()).isEqualTo(16000);
    }

    @Test
    void shouldSplitLongChineseTextBeforeCallingTencentApi() {
        var pcm = ByteBuffer.allocate(960 * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (var index = 0; index < 960; index++) {
            pcm.putShort((short) 0);
        }
        var api = new CapturingTencentTextToVoiceApi(Base64.getEncoder().encodeToString(pcm.array()));
        var config = new TencentCloudTextToSpeechConfig(
                "secret-id",
                "secret-key",
                "ap-guangzhou",
                "tts.tencentcloudapi.com",
                "101001",
                "pcm",
                16000,
                Duration.ofSeconds(3)
        );
        var client = new TencentCloudTextToSpeechClient(config, api);
        var text = "测试".repeat(90);

        var frames = client.synthesize(text, new VoiceId("default"));

        assertThat(frames).hasSize(2);
        assertThat(api.requests).hasSize(2);
        assertThat(api.requests)
                .extracting(request -> request.text().length())
                .containsExactly(150, 30);
    }

    @Test
    void shouldEncodeLittleEndianPcmToOpusFrames() {
        var pcm = ByteBuffer.allocate(960 * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (var index = 0; index < 960; index++) {
            pcm.putShort((short) 0);
        }

        var frames = PcmToOpusEncoder.encode(pcm.array(), 16000, 60);

        assertThat(frames).hasSize(1);
        assertThat(frames.getFirst().remaining()).isGreaterThan(0);
    }

    @Test
    void shouldRejectNonNumericVoiceType() {
        var config = new TencentCloudTextToSpeechConfig(
                "secret-id",
                "secret-key",
                "ap-guangzhou",
                "tts.tencentcloudapi.com",
                "101001",
                "pcm",
                16000,
                Duration.ofSeconds(3)
        );
        var client = new TencentCloudTextToSpeechClient(config, request -> "");

        assertThatThrownBy(() -> client.synthesize("你好", new VoiceId("default-voice")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tencent Cloud TTS voice-type must be numeric");
    }

    private static class CapturingTencentTextToVoiceApi implements TencentTextToVoiceApi {

        private final String audio;
        private TencentTextToVoiceRequest request;
        private final List<TencentTextToVoiceRequest> requests = new ArrayList<>();

        private CapturingTencentTextToVoiceApi(String audio) {
            this.audio = audio;
        }

        @Override
        public String synthesize(TencentTextToVoiceRequest request) {
            this.request = request;
            this.requests.add(request);
            return audio;
        }
    }
}
