package com.jzb.chatbot.speech;

/**
 * 腾讯云实时 ASR 识别事件监听器。
 * <p>
 * 隔离 SDK listener，方便测试音频写入、停止和关闭生命周期。
 *
 * @author jiangzhibin
 * @since 2026-06-17 00:00:00
 */
interface TencentRealtimeSpeechRecognitionListener {

    void onSentenceEnd(String text);

    void onComplete(String text);

    void onFail(String message);
}
