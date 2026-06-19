package com.jzb.chatbot.voice.bargein;

/**
 * 播放期打断判定结果。
 * <p>
 * 表达是否应取消当前播报，以及被忽略时的可观测原因。
 *
 * @author jiangzhibin
 * @since 2026-06-19 14:46:00
 */
public record XiaozhiBargeInDecision(boolean interrupt, String reason) {

    public static XiaozhiBargeInDecision interrupt(String reason) {
        return new XiaozhiBargeInDecision(true, reason);
    }

    public static XiaozhiBargeInDecision ignore(String reason) {
        return new XiaozhiBargeInDecision(false, reason);
    }
}
