package com.jzb.chatbot.voice.bargein;

import com.jzb.chatbot.speech.SpeechToTextAudioStream;
import com.jzb.chatbot.speech.StreamingOpusToPcmDecoder;

/**
 * 播放期打断 ASR 回合。
 * <p>
 * 绑定创建时的播放代际，避免异步 ASR 返回后误取消新的播报。
 *
 * @author jiangzhibin
 * @since 2026-06-19 14:49:00
 */
public record XiaozhiBargeInTurn(
        long sequence,
        long playbackGeneration,
        String deviceId,
        SpeechToTextAudioStream audioStream,
        StreamingOpusToPcmDecoder opusDecoder,
        long startedAtEpochMillis
) {

    public boolean matches(XiaozhiBargeInTurn other) {
        return other != null && sequence == other.sequence && playbackGeneration == other.playbackGeneration;
    }
}
