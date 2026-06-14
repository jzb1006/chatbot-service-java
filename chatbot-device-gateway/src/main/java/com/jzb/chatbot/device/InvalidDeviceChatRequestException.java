package com.jzb.chatbot.device;

import org.springframework.http.HttpStatus;

/**
 * 设备聊天请求异常。
 * <p>
 * 表示请求字段不满足旧版设备网关协议约束。
 *
 * @author jiangzhibin
 * @since 2026-06-14 19:25:00
 */
public class InvalidDeviceChatRequestException extends RuntimeException {

    private final HttpStatus status;

    /**
     * 创建设备聊天请求异常。
     *
     * @param status HTTP 状态
     * @param message 错误消息
     */
    public InvalidDeviceChatRequestException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    /**
     * 返回 HTTP 状态。
     *
     * @return HTTP 状态
     */
    public HttpStatus status() {
        return status;
    }
}
