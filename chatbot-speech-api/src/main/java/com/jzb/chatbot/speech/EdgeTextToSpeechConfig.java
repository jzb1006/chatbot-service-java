package com.jzb.chatbot.speech;

import java.time.Duration;

/**
 * Edge 在线朗读语音合成配置。
 * <p>
 * 该配置只用于个人项目接入 Edge Read Aloud 的非官方 WebSocket 能力。
 *
 * @author jiangzhibin
 * @since 2026-06-21 09:28:00
 */
public record EdgeTextToSpeechConfig(
        String voice,
        String outputFormat,
        int sampleRate,
        String ffmpegPath,
        Duration timeout
) {

    private static final String DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural";
    private static final String DEFAULT_OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3";
    private static final int DEFAULT_SAMPLE_RATE = 16000;
    private static final String DEFAULT_FFMPEG_PATH = "ffmpeg";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    public EdgeTextToSpeechConfig {
        if (voice == null || voice.isBlank()) {
            voice = DEFAULT_VOICE;
        }
        if (outputFormat == null || outputFormat.isBlank()) {
            outputFormat = DEFAULT_OUTPUT_FORMAT;
        }
        if (sampleRate <= 0) {
            sampleRate = DEFAULT_SAMPLE_RATE;
        }
        if (ffmpegPath == null || ffmpegPath.isBlank()) {
            ffmpegPath = DEFAULT_FFMPEG_PATH;
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            timeout = DEFAULT_TIMEOUT;
        }
    }

    /**
     * 创建默认 Edge TTS 配置。
     *
     * @return 默认配置
     */
    public static EdgeTextToSpeechConfig defaults() {
        return new EdgeTextToSpeechConfig(
                DEFAULT_VOICE,
                DEFAULT_OUTPUT_FORMAT,
                DEFAULT_SAMPLE_RATE,
                DEFAULT_FFMPEG_PATH,
                DEFAULT_TIMEOUT
        );
    }
}
