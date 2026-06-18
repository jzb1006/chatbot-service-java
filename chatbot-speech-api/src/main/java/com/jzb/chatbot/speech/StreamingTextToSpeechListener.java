package com.jzb.chatbot.speech;

import java.nio.ByteBuffer;

/**
 * 流式文本转语音回调监听器。
 * <p>
 * 接收 provider ready、已编码 Opus 音频帧、完成和失败事件。
 *
 * @author jiangzhibin
 * @since 2026-06-18 12:47:00
 */
public interface StreamingTextToSpeechListener {

    /**
     * provider 已准备好接收文本。
     */
    void onReady();

    /**
     * 接收已编码的小智 Opus 音频帧。
     *
     * @param frame Opus 16k mono 60ms frame
     */
    void onAudioFrame(ByteBuffer frame);

    /**
     * provider 已返回最终完成状态。
     */
    void onCompleted();

    /**
     * provider 处理失败。
     *
     * @param exception 失败异常
     */
    void onFailed(RuntimeException exception);
}
