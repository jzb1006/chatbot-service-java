package com.jzb.chatbot.voice.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 小智客户端 JSON 控制帧。
 * <p>
 * 第一阶段只解析通用字段，具体行为由 WebSocket Handler 决定。
 *
 * @author jiangzhibin
 * @since 2026-06-14 18:36:50
 */
public record XiaozhiClientMessage(
        String type,
        String state,
        @JsonProperty("session_id") String sessionId
) {
}
