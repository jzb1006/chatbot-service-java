package com.jzb.chatbot.speech;

import static org.assertj.core.api.Assertions.assertThat;

import com.jzb.chatbot.common.id.VoiceId;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class EdgeTextToSpeechClientTest {

    @Test
    void shouldUseEdgeSupportedMp3FormatByDefault() {
        var config = EdgeTextToSpeechConfig.defaults();

        assertThat(config.outputFormat()).isEqualTo("audio-24khz-48kbitrate-mono-mp3");
        assertThat(config.ffmpegPath()).isEqualTo("ffmpeg");
        assertThat(config.sampleRate()).isEqualTo(16000);
    }

    @Test
    void shouldSendEdgeRequestAndEncodeRawPcmToOpusFrames() {
        var transport = new CapturingEdgeTtsTransport(new byte[] {1, 2, 3});
        var decoder = new CapturingEdgeAudioDecoder(silentPcm());
        var config = new EdgeTextToSpeechConfig(
                "zh-CN-XiaoxiaoNeural",
                "audio-24khz-48kbitrate-mono-mp3",
                16000,
                "ffmpeg",
                Duration.ofSeconds(3)
        );
        var client = new EdgeTextToSpeechClient(config, transport, decoder);

        var frames = client.synthesize("你好", TextToSpeechOptions.defaults());

        assertThat(frames).hasSize(1);
        assertThat(frames.getFirst().remaining()).isGreaterThan(0);
        assertThat(transport.request.text()).isEqualTo("你好");
        assertThat(transport.request.voice()).isEqualTo("zh-CN-XiaoxiaoNeural");
        assertThat(transport.request.outputFormat()).isEqualTo("audio-24khz-48kbitrate-mono-mp3");
        assertThat(transport.request.sampleRate()).isEqualTo(16000);
        assertThat(transport.request.rate()).isEqualTo("+0%");
        assertThat(transport.request.pitch()).isEqualTo("+0Hz");
        assertThat(decoder.audio).containsExactly(1, 2, 3);
        assertThat(decoder.sampleRate).isEqualTo(16000);
    }

    @Test
    void shouldSendConfiguredEdgeVoice() {
        var transport = new CapturingEdgeTtsTransport(silentPcm());
        var config = new EdgeTextToSpeechConfig(
                "zh-CN-YunxiNeural",
                "audio-24khz-48kbitrate-mono-mp3",
                16000,
                "ffmpeg",
                Duration.ofSeconds(3)
        );
        var client = new EdgeTextToSpeechClient(config, transport, new CapturingEdgeAudioDecoder(silentPcm()));

        client.synthesize("浣犲ソ", TextToSpeechOptions.defaults());

        assertThat(transport.request.voice()).isEqualTo("zh-CN-YunxiNeural");
    }

    @Test
    void shouldUseExplicitVoiceIdWhenPresent() {
        var transport = new CapturingEdgeTtsTransport(silentPcm());
        var config = new EdgeTextToSpeechConfig(
                "zh-CN-XiaoxiaoNeural",
                "audio-24khz-48kbitrate-mono-mp3",
                16000,
                "ffmpeg",
                Duration.ofSeconds(3)
        );
        var client = new EdgeTextToSpeechClient(config, transport, new CapturingEdgeAudioDecoder(silentPcm()));

        client.synthesize("你好", new VoiceId("zh-CN-YunxiNeural"));

        assertThat(transport.request.voice()).isEqualTo("zh-CN-YunxiNeural");
    }

    @Test
    void shouldSkipBlankText() {
        var transport = new CapturingEdgeTtsTransport(silentPcm());
        var client = new EdgeTextToSpeechClient(
                EdgeTextToSpeechConfig.defaults(),
                transport,
                new CapturingEdgeAudioDecoder(silentPcm())
        );

        var frames = client.synthesize("  ", TextToSpeechOptions.defaults());

        assertThat(frames).isEmpty();
        assertThat(transport.request).isNull();
    }

    private static byte[] silentPcm() {
        var pcm = ByteBuffer.allocate(960 * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (var index = 0; index < 960; index++) {
            pcm.putShort((short) 0);
        }
        return pcm.array();
    }

    private static class CapturingEdgeTtsTransport implements EdgeTtsTransport {

        private final byte[] audio;
        private EdgeTtsRequest request;

        private CapturingEdgeTtsTransport(byte[] audio) {
            this.audio = audio;
        }

        @Override
        public byte[] synthesize(EdgeTtsRequest request, Duration timeout) {
            this.request = request;
            return audio;
        }
    }

    private static class CapturingEdgeAudioDecoder implements EdgeAudioDecoder {

        private final byte[] pcm;
        private byte[] audio;
        private int sampleRate;

        private CapturingEdgeAudioDecoder(byte[] pcm) {
            this.pcm = pcm;
        }

        @Override
        public byte[] decodeToPcm(byte[] audio, int sampleRate, Duration timeout) {
            this.audio = audio;
            this.sampleRate = sampleRate;
            return pcm;
        }
    }
}
