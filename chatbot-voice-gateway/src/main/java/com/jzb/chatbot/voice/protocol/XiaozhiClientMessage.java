package com.jzb.chatbot.voice.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 小智客户端 JSON 控制帧。
 * <p>
 * 解析 hello 之外的通用控制帧字段，具体行为由语音会话服务决定。
 *
 * @author jiangzhibin
 * @since 2026-06-14 18:36:50
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record XiaozhiClientMessage(
        String type,
        String state,
        String mode,
        String reason,
        String text,
        @JsonProperty("session_id") String sessionId,
        JsonNode payload
) {
}
