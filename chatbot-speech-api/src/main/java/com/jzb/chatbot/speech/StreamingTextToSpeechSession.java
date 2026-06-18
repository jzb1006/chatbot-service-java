package com.jzb.chatbot.speech;

import java.time.Duration;

/**
 * 流式文本转语音会话。
 * <p>
 * 抽象 provider 会话生命周期，调用方可以增量写入文本、标记输入完成或取消当前会话。
 *
 * @author jiangzhibin
 * @since 2026-06-18 12:47:00
 */
public interface StreamingTextToSpeechSession extends AutoCloseable {

    /**
     * 写入待合成文本。
     *
     * @param text 待合成文本
     */
    void sendText(String text);

    /**
     * 标记文本输入完成。
     */
    void complete();

    /**
     * 取消当前流式会话。
     */
    void cancel();

    /**
     * 等待 provider 返回最终状态。
     *
     * @param timeout 最大等待时长
     * @return 是否在超时前收到最终状态
     */
    boolean awaitFinal(Duration timeout);

    @Override
    void close();
}
