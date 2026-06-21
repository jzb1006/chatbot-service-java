package com.jzb.chatbot.speech;

import com.jzb.chatbot.common.id.VoiceId;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Edge 在线朗读语音合成客户端。
 * <p>
 * 通过 Edge Read Aloud 的非官方 WebSocket 能力获取 16k PCM，再编码为小智 WebSocket 使用的 Opus 帧。
 *
 * @author jiangzhibin
 * @since 2026-06-21 09:28:00
 */
public class EdgeTextToSpeechClient implements TextToSpeechClient {

    private static final int OPUS_FRAME_DURATION_MS = 60;

    private final EdgeTextToSpeechConfig config;
    private final EdgeTtsTransport transport;
    private final EdgeAudioDecoder audioDecoder;

    public EdgeTextToSpeechClient(EdgeTextToSpeechConfig config) {
        this(
                config,
                EdgeTtsTransport.javaNetHttp(),
                EdgeAudioDecoder.ffmpeg((config == null ? EdgeTextToSpeechConfig.defaults() : config).ffmpegPath())
        );
    }

    EdgeTextToSpeechClient(EdgeTextToSpeechConfig config, EdgeTtsTransport transport, EdgeAudioDecoder audioDecoder) {
        this.config = config == null ? EdgeTextToSpeechConfig.defaults() : config;
        this.transport = transport;
        this.audioDecoder = audioDecoder;
    }

    @Override
    public List<ByteBuffer> synthesize(String text, VoiceId voiceId) {
        return synthesize(text, new TextToSpeechOptions(voiceId, 1.0, 1.0));
    }

    @Override
    public List<ByteBuffer> synthesize(String text, TextToSpeechOptions options) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        var effectiveOptions = options == null ? TextToSpeechOptions.defaults() : options;
        var voice = resolveVoice(effectiveOptions.voiceId());
        var request = new EdgeTtsRequest(
                text,
                voice,
                config.outputFormat(),
                config.sampleRate(),
                edgeRate(effectiveOptions.speed()),
                edgePitch(effectiveOptions.pitch())
        );
        var audio = transport.synthesize(request, config.timeout());
        if (audio.length == 0) {
            return List.of();
        }
        var pcm = audioDecoder.decodeToPcm(audio, config.sampleRate(), config.timeout());
        if (pcm.length == 0) {
            return List.of();
        }
        return PcmToOpusEncoder.encode(pcm, config.sampleRate(), OPUS_FRAME_DURATION_MS);
    }

    private String resolveVoice(VoiceId voiceId) {
        if (voiceId == null || voiceId.value().isBlank() || "default".equals(voiceId.value())) {
            return config.voice();
        }
        return voiceId.value();
    }

    private String edgeRate(double speed) {
        var percent = Math.round((speed - 1.0) * 100.0);
        return signed(percent, "%");
    }

    private String edgePitch(double pitch) {
        var hertz = Math.round((pitch - 1.0) * 100.0);
        return signed(hertz, "Hz");
    }

    private String signed(long value, String unit) {
        return value >= 0 ? "+" + value + unit : value + unit;
    }
}
