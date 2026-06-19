package com.jzb.chatbot.voice;

import com.jzb.chatbot.voice.protocol.XiaozhiAudioFrame;
import java.util.List;

/**
 * 小智上行语音输入门禁。
 * <p>
 * 在调用 ASR 前过滤空的监听窗口，避免把无语音当成可恢复错误并触发 TTS 循环。
 *
 * @author jiangzhibin
 * @since 2026-06-19 22:49:00
 */
final class XiaozhiVoiceInputGate {

    private static final int MIN_AUDIO_FRAMES = 1;
    private static final int MIN_AUDIO_BYTES = 1;

    private XiaozhiVoiceInputGate() {
    }

    /**
     * 判断普通语音回合是否有足够输入进入 ASR。
     *
     * @param frames 上行 Opus 音频帧
     * @return true 表示可以进入 ASR
     */
    static boolean shouldTranscribe(List<XiaozhiAudioFrame> frames) {
        if (frames == null || frames.size() < MIN_AUDIO_FRAMES) {
            return false;
        }
        var audioBytes = frames.stream()
                .map(XiaozhiAudioFrame::payload)
                .filter(payload -> payload != null)
                .mapToInt(payload -> payload.length)
                .sum();
        return audioBytes >= MIN_AUDIO_BYTES;
    }
}
