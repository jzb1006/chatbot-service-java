package com.jzb.chatbot.voice.sessionend;

import com.jzb.chatbot.voice.hermes.HermesAgentEvent;

/**
 * 小智结束会话动作。
 * <p>
 * 将 Hermes {@code session_end} 结构化事件转换为服务端可执行的告别播报动作。
 *
 * @author jiangzhibin
 * @since 2026-06-19 23:38:00
 */
public record XiaozhiSessionEndAction(String confirmationText, String reason) {

    public static XiaozhiSessionEndAction from(
            HermesAgentEvent event,
            XiaozhiSessionEndProperties properties
    ) {
        if (event == null || properties == null || !properties.enabled()) {
            return null;
        }
        if (!"session_end".equals(event.action())) {
            return null;
        }
        var confirmationText = event.confirmationText();
        if (confirmationText == null || confirmationText.isBlank()) {
            confirmationText = properties.defaultConfirmationText();
        }
        var reason = event.reason();
        if (reason == null || reason.isBlank()) {
            reason = "session_end";
        }
        return new XiaozhiSessionEndAction(confirmationText, reason);
    }
}
