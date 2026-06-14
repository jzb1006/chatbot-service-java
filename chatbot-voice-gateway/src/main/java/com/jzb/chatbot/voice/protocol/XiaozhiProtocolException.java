package com.jzb.chatbot.voice.protocol;

/**
 * 小智协议异常。
 * <p>
 * 用于把非法控制帧和二进制帧统一转换为 WebSocket 协议错误。
 *
 * @author jiangzhibin
 * @since 2026-06-14 21:05:00
 */
public class XiaozhiProtocolException extends RuntimeException {

    public XiaozhiProtocolException(String message) {
        super(message);
    }
}
