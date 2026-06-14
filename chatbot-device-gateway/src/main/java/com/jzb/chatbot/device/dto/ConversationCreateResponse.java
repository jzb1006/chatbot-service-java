package com.jzb.chatbot.device.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 新建对话响应。
 * <p>
 * 返回设备标识和新生成的对话标识。
 *
 * @author jiangzhibin
 * @since 2026-06-14 19:20:00
 */
public record ConversationCreateResponse(
        @JsonProperty("device_id") String deviceId,
        @JsonProperty("conversation_id") String conversationId
) {
}
