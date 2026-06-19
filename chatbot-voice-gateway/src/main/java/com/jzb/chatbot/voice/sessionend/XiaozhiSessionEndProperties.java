package com.jzb.chatbot.voice.sessionend;

/**
 * 小智结束会话配置。
 * <p>
 * 控制 Hermes session_end 事件是否生效，以及服务端主动关闭 WebSocket 时使用的默认文案和状态码。
 *
 * @author jiangzhibin
 * @since 2026-06-19 23:38:00
 */
public record XiaozhiSessionEndProperties(
        boolean enabled,
        String defaultConfirmationText,
        int closeStatusCode,
        String closeReason
) {

    public XiaozhiSessionEndProperties {
        if (defaultConfirmationText == null || defaultConfirmationText.isBlank()) {
            defaultConfirmationText = "回头再聊";
        }
        if (closeStatusCode <= 0) {
            closeStatusCode = 1000;
        }
        if (closeReason == null || closeReason.isBlank()) {
            closeReason = "session ended";
        }
    }
}
