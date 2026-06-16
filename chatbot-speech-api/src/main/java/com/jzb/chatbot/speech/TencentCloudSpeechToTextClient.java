package com.jzb.chatbot.speech;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.List;

/**
 * 腾讯云语音识别客户端。
 * <p>
 * 聚合单轮小智 Opus 音频帧后调用一句话识别接口。
 *
 * @author jiangzhibin
 * @since 2026-06-16 01:38:00
 */
public class TencentCloudSpeechToTextClient implements SpeechToTextClient {

    private final TencentCloudSpeechToTextConfig config;
    private final TencentSentenceRecognitionApi recognitionApi;

    public TencentCloudSpeechToTextClient(TencentCloudSpeechToTextConfig config) {
        this(config, new TencentCloudSentenceRecognitionApi(config));
    }

    TencentCloudSpeechToTextClient(TencentCloudSpeechToTextConfig config, TencentSentenceRecognitionApi recognitionApi) {
        this.config = config;
        this.recognitionApi = recognitionApi;
    }

    @Override
    public String transcribe(List<ByteBuffer> audioFrames) {
        if (audioFrames == null || audioFrames.isEmpty()) {
            return "";
        }
        var audio = resolveAudio(audioFrames);
        if (audio.length == 0) {
            return "";
        }
        return recognitionApi.recognize(new TencentSentenceRecognitionRequest(
                Base64.getEncoder().encodeToString(audio),
                audio.length,
                config.engineModelType(),
                config.voiceFormat(),
                config.sampleRate()
        ));
    }

    private byte[] resolveAudio(List<ByteBuffer> audioFrames) {
        if ("pcm".equalsIgnoreCase(config.voiceFormat())) {
            return OpusToPcmDecoder.decode(audioFrames, config.sampleRate());
        }
        return combine(audioFrames);
    }

    private byte[] combine(List<ByteBuffer> audioFrames) {
        var totalSize = audioFrames.stream()
                .filter(buffer -> buffer != null)
                .map(ByteBuffer::slice)
                .mapToInt(ByteBuffer::remaining)
                .sum();
        var output = ByteBuffer.allocate(totalSize);
        audioFrames.stream()
                .filter(buffer -> buffer != null)
                .map(ByteBuffer::slice)
                .forEach(output::put);
        return output.array();
    }
}
