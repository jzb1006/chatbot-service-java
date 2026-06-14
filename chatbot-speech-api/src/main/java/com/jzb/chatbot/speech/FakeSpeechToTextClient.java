package com.jzb.chatbot.speech;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Fake ASR 客户端。
 * <p>
 * 用于协议联调阶段提供稳定的语音转文本结果。
 *
 * @author jiangzhibin
 * @since 2026-06-14 20:51:00
 */
public class FakeSpeechToTextClient implements SpeechToTextClient {

    @Override
    public String transcribe(List<ByteBuffer> audioFrames) {
        return "ping";
    }
}
