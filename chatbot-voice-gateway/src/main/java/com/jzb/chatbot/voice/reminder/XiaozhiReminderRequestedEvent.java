package com.jzb.chatbot.voice.reminder;

/**
 * 小智提醒请求事件。
 * <p>
 * 解耦语音会话服务与提醒调度服务，避免会话服务直接依赖提醒服务形成循环依赖。
 *
 * @author jiangzhibin
 * @since 2026-06-17 18:34:00
 */
public record XiaozhiReminderRequestedEvent(String deviceId, String message, long delaySeconds) {
}
