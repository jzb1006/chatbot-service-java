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
        @JsonProperty("conversation_id") String conversationId,
        XiaozhiAudioParams audio
) {

    /**
     * 创建 WebSocket hello 消息。
     *
     * @param sessionId 会话标识
     * @param conversationId 对话标识
     * @return hello 消息
     */
    public static XiaozhiServerHello websocket(String sessionId, String conversationId) {
        return new XiaozhiServerHello(
                "hello",
                "websocket",
                sessionId,
                conversationId,
                XiaozhiAudioParams.defaults()
        );
    }
}
