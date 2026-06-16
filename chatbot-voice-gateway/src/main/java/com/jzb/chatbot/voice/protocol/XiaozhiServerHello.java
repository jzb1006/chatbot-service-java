package com.jzb.chatbot.voice.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 小智服务端 hello 消息。
 * <p>
 * WebSocket 建立后由服务端主动下发。
 *
 * @author jiangzhibin
 * @since 2026-06-14 18:36:50
 */
public record XiaozhiServerHello(
        String type,
        String transport,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("audio_params") XiaozhiAudioParams audioParams
) {

    /**
     * 创建 WebSocket hello 消息。
     *
     * @param sessionId 会话标识
     * @return hello 消息
     */
    public static XiaozhiServerHello websocket(String sessionId) {
        return websocket(sessionId, XiaozhiAudioParams.defaults());
    }

    /**
     * 创建 WebSocket hello 消息。
     *
     * @param sessionId 会话标识
     * @param audioParams 音频参数
     * @return hello 消息
     */
    public static XiaozhiServerHello websocket(String sessionId, XiaozhiAudioParams audioParams) {
        return new XiaozhiServerHello(
                "hello",
                "websocket",
                sessionId,
                audioParams == null ? XiaozhiAudioParams.defaults() : audioParams
        );
    }
}
