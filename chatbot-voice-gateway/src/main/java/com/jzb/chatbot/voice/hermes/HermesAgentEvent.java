package com.jzb.chatbot.voice.hermes;

/**
 * Hermes agent 结构化事件。
 * <p>
 * 承载 Hermes SSE 中的 agent 行为事件，Java 侧只识别 action 与基础字段。
 *
 * @author jiangzhibin
 * @since 2026-06-18 04:10:00
 */
public record HermesAgentEvent(
        String action,
        String message,
        long delaySeconds,
        String confirmationText
) {
}
