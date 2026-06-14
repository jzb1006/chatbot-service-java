package com.jzb.chatbot.device.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 设备文本聊天响应。
 * <p>
 * 字段命名保持与 ESP32 固件文本协议兼容。
 *
 * @author jiangzhibin
 * @since 2026-06-14 18:36:50
 */
public record DeviceChatResponse(
        @JsonProperty("conversation_id") String conversationId,
        String reply
) {
}
