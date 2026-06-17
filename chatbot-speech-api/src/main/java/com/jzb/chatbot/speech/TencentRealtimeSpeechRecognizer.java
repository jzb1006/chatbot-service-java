package com.jzb.chatbot.speech;

/**
 * 腾讯云实时 ASR 单次识别器。
 * <p>
 * 每次语音回合创建一个实例，客户端负责 start/write/stop/close 生命周期。
 *
 * @author jiangzhibin
 * @since 2026-06-17 00:00:00
 */
interface TencentRealtimeSpeechRecognizer extends AutoCloseable {

    void start();

    void write(byte[] pcm);

    void stop();

    @Override
    void close();
}
