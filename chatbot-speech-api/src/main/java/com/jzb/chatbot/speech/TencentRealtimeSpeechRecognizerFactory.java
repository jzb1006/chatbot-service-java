package com.jzb.chatbot.speech;

/**
 * 腾讯云实时 ASR 识别器工厂。
 *
 * @author jiangzhibin
 * @since 2026-06-17 00:00:00
 */
interface TencentRealtimeSpeechRecognizerFactory {

    TencentRealtimeSpeechRecognizer create(
            TencentRealtimeSpeechToTextConfig config,
            TencentRealtimeSpeechRecognitionListener listener
    );
}
