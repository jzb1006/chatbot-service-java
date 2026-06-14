package com.jzb.chatbot.speech;

import com.jzb.chatbot.common.id.VoiceId;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Fake TTS 客户端。
 * <p>
 * 用于协议联调阶段提供稳定的二进制音频帧。
 *
 * @author jiangzhibin
 * @since 2026-06-14 20:51:00
 */
public class FakeTextToSpeechClient implements TextToSpeechClient {

    @Override
    public List<ByteBuffer> synthesize(String text, VoiceId voiceId) {
        return List.of(ByteBuffer.wrap(("fake-opus:" + text).getBytes(StandardCharsets.UTF_8)));
    }
}
