package com.jzb.chatbot.voice.reminder;

import java.util.regex.Pattern;

/**
 * 小智提醒意图。
 * <p>
 * 解析常见口语相对时间提醒，作为 Hermes 工具调用未触发时的服务端兜底。
 * 仅覆盖生产日志中出现的相对时间提醒句式，复杂自然语言仍交给 Hermes Agent。
 *
 * @author jiangzhibin
 * @since 2026-06-17 18:34:00
 */
public record XiaozhiReminderIntent(String message, long delaySeconds, String confirmationText) {

    private static final Pattern RELATIVE_REMINDER = Pattern.compile(
            "(?:一个|1个)?\\s*(\\d+|一|两|二|三|四|五|六|七|八|九|十)\\s*"
                    + "(秒|分钟钟|分钟|分|小时|钟头)\\s*(?:后|后的)?\\s*"
                    + "(?:定时任务|提醒|闹钟|任务)?\\s*"
                    + "(?:提醒我|叫我|给我(?:推送|通知|发送|播报|提醒|告诉)|推送给我|通知我|发送给我|播报给我|告诉我)(.+)"
    );

    /**
     * 尝试解析提醒意图。
     *
     * @param text 用户文本
     * @return 提醒意图；无法解析时返回 null
     */
    public static XiaozhiReminderIntent parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        var normalized = text.trim()
                .replace("，", "")
                .replace(",", "")
                .replace("。", "")
                .replace("！", "")
                .replace("!", "");
        var matcher = RELATIVE_REMINDER.matcher(normalized);
        if (!matcher.find()) {
            return null;
        }
        var amount = parseAmount(matcher.group(1));
        var unit = matcher.group(2);
        var message = matcher.group(3).trim();
        if (amount <= 0 || message.isBlank()) {
            return null;
        }
        var delaySeconds = amount * unitSeconds(unit);
        return new XiaozhiReminderIntent(message, delaySeconds, confirmation(matcher.group(1), unit, message));
    }

    private static long parseAmount(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return switch (value) {
                case "一" -> 1L;
                case "两", "二" -> 2L;
                case "三" -> 3L;
                case "四" -> 4L;
                case "五" -> 5L;
                case "六" -> 6L;
                case "七" -> 7L;
                case "八" -> 8L;
                case "九" -> 9L;
                case "十" -> 10L;
                default -> 0L;
            };
        }
    }

    private static long unitSeconds(String unit) {
        return switch (unit) {
            case "秒" -> 1L;
            case "分钟钟", "分钟", "分" -> 60L;
            case "小时", "钟头" -> 3600L;
            default -> 1L;
        };
    }

    private static String confirmation(String amountText, String unit, String message) {
        var spokenUnit = "分钟钟".equals(unit) ? "分钟" : unit;
        return amountText + spokenUnit + "后提醒你" + message;
    }
}
