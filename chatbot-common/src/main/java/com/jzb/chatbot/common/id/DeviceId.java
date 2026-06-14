package com.jzb.chatbot.common.id;

/**
 * 设备长期标识。
 * <p>
 * 用于设备文本协议和后续设备认证边界。
 *
 * @author jiangzhibin
 * @since 2026-06-14 18:36:50
 */
public record DeviceId(String value) {

    public DeviceId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("deviceId must not be blank");
        }
    }
}
