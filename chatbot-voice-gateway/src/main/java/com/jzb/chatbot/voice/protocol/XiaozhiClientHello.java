package com.jzb.chatbot.voice.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * 小智设备 hello 消息。
 * <p>
 * 设备建立 WebSocket 后先上报协议版本、传输方式和音频参数。
 *
 * @author jiangzhibin
 * @since 2026-06-14 20:45:00
 */
public record XiaozhiClientHello(
        String type,
        int version,
        Map<String, Boolean> features,
        String transport,
        @JsonProperty("audio_params") XiaozhiAudioParams audioParams
) {
}
