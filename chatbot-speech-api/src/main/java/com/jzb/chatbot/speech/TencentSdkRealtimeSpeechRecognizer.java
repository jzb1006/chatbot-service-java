package com.jzb.chatbot.speech;

import com.tencent.asrv2.SpeechRecognizer;

/**
 * 腾讯云 SDK 实时 ASR 单次识别器包装。
 *
 * @author jiangzhibin
 * @since 2026-06-17 00:00:00
 */
class TencentSdkRealtimeSpeechRecognizer implements TencentRealtimeSpeechRecognizer {

    private final SpeechRecognizer sdkRecognizer;

    TencentSdkRealtimeSpeechRecognizer(SpeechRecognizer sdkRecognizer) {
        this.sdkRecognizer = sdkRecognizer;
    }

    @Override
    public void start() {
        try {
            sdkRecognizer.start();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to start Tencent realtime ASR", exception);
        }
    }

    @Override
    public void write(byte[] pcm) {
        sdkRecognizer.write(pcm);
    }

    @Override
    public void stop() {
        try {
            sdkRecognizer.stop();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to stop Tencent realtime ASR", exception);
        }
    }

    @Override
    public void close() {
        sdkRecognizer.close();
    }
}
