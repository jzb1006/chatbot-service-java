package com.jzb.chatbot.voice.tts;

/**
 * 小智流式 TTS 句子写入器。
 * <p>
 * 上游对话流解析出完整句子后，通过该边界增量写入流式 TTS runtime。
 *
 * @author jiangzhibin
 * @since 2026-06-18 12:58:00
 */
public interface XiaozhiTtsSentenceSink {

    /**
     * 写入一个完整句子。
     *
     * @param sentence 完整句子
     */
    void accept(String sentence);

    /**
     * 标记文本输入完成。
     */
    void complete();
}
