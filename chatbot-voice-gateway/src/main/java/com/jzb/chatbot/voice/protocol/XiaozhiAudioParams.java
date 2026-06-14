package com.jzb.chatbot.voice.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 小智音频参数。
 * <p>
 * 第一阶段固定为 Opus 16k 单声道 60ms 帧。
 *
 * @author jiangzhibin
 * @since 2026-06-14 18:36:50
 */
public record XiaozhiAudioParams(
        String format,
        @JsonProperty("sample_rate") int sampleRate,
        int channels,
        @JsonProperty("frame_duration") int frameDuration
) {

    /**
     * 创建默认音频参数。
     *
     * @return 默认小智音频参数
     */
    public static XiaozhiAudioParams defaults() {
        return new XiaozhiAudioParams("opus", 16000, 1, 60);
    }
}
