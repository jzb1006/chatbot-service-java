package com.jzb.chatbot.speech;

import java.time.Duration;

/**
 * Edge TTS 音频解码边界。
 * <p>
 * Edge Read Aloud 当前稳定返回 MP3，该边界负责转成后续 Opus 编码需要的 PCM。
 *
 * @author jiangzhibin
 * @since 2026-06-21 09:34:00
 */
public interface EdgeAudioDecoder {

    /**
     * 将 Edge 返回音频解码为单声道 16-bit little-endian PCM。
     *
     * @param audio Edge 返回的音频字节
     * @param sampleRate 输出采样率
     * @param timeout 解码超时时间
     * @return PCM 字节
     */
    byte[] decodeToPcm(byte[] audio, int sampleRate, Duration timeout);

    /**
     * 创建 ffmpeg 解码实现。
     *
     * @param ffmpegPath ffmpeg 可执行文件路径
     * @return 音频解码器
     */
    static EdgeAudioDecoder ffmpeg(String ffmpegPath) {
        return new FfmpegEdgeAudioDecoder(ffmpegPath);
    }
}
