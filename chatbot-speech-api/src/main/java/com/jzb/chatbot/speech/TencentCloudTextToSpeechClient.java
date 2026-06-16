package com.jzb.chatbot.speech;

import com.jzb.chatbot.common.id.VoiceId;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 腾讯云语音合成客户端。
 * <p>
 * 调用 TextToVoice 获取 PCM 后编码为小智 WebSocket 使用的 Opus 帧。
 *
 * @author jiangzhibin
 * @since 2026-06-15 16:45:00
 */
public class TencentCloudTextToSpeechClient implements TextToSpeechClient {

    private static final int OPUS_FRAME_DURATION_MS = 60;
    private static final int MAX_TEXT_CODE_POINTS = 150;

    private final TencentCloudTextToSpeechConfig config;
    private final TencentTextToVoiceApi textToVoiceApi;

    public TencentCloudTextToSpeechClient(TencentCloudTextToSpeechConfig config) {
        this(config, new TencentCloudTextToVoiceApi(config));
    }

    TencentCloudTextToSpeechClient(TencentCloudTextToSpeechConfig config, TencentTextToVoiceApi textToVoiceApi) {
        this.config = config;
        this.textToVoiceApi = textToVoiceApi;
    }

    @Override
    public List<ByteBuffer> synthesize(String text, VoiceId voiceId) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        var voiceType = resolveVoiceType(voiceId);
        var frames = new ArrayList<ByteBuffer>();
        for (var chunk : splitText(text)) {
            var audio = textToVoiceApi.synthesize(new TencentTextToVoiceRequest(
                    chunk,
                    voiceType,
                    config.codec(),
                    config.sampleRate()
            ));
            var pcm = Base64.getDecoder().decode(audio);
            frames.addAll(PcmToOpusEncoder.encode(pcm, config.sampleRate(), OPUS_FRAME_DURATION_MS));
        }
        return List.copyOf(frames);
    }

    private String resolveVoiceType(VoiceId voiceId) {
        if (voiceId == null || voiceId.value().isBlank() || "default".equals(voiceId.value())) {
            return config.voiceType();
        }
        return voiceId.value();
    }

    private List<String> splitText(String text) {
        var chunks = new ArrayList<String>();
        var chunk = new StringBuilder();
        var codePoints = 0;
        for (var offset = 0; offset < text.length(); ) {
            var codePoint = text.codePointAt(offset);
            if (codePoints >= MAX_TEXT_CODE_POINTS) {
                chunks.add(chunk.toString());
                chunk.setLength(0);
                codePoints = 0;
            }
            chunk.appendCodePoint(codePoint);
            codePoints++;
            offset += Character.charCount(codePoint);
        }
        if (!chunk.isEmpty()) {
            chunks.add(chunk.toString());
        }
        return chunks;
    }
}
