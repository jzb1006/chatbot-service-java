package com.jzb.chatbot.device.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 新建对话请求。
 * <p>
 * 保持旧版设备网关字段命名。
 *
 * @author jiangzhibin
 * @since 2026-06-14 19:20:00
 */
public record ConversationCreateRequest(@JsonProperty("device_id") String deviceId) {
}
