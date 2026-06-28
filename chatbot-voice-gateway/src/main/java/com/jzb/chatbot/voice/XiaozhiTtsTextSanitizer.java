package com.jzb.chatbot.voice;

import java.util.regex.Pattern;

/**
 * 小智 TTS 文本清洗器。
 * <p>
 * 将 Hermes 返回的富文本风格回复转换为适合语音合成的纯文本。
 *
 * @author jiangzhibin
 * @since 2026-06-28 09:20:00
 */
class XiaozhiTtsTextSanitizer {

    private static final Pattern CODE_FENCE = Pattern.compile("(?s)```.*?```");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^]]*)]\\([^)]+\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^]]+)]\\([^)]+\\)");
    private static final Pattern LINE_PREFIX = Pattern.compile("(?m)^\\s*(?:#{1,6}\\s*|[-*+]\\s+|\\d+[.)]\\s+|>\\s*)");
    private static final Pattern EMPHASIS_MARK = Pattern.compile("(?:\\*\\*|__|\\*|_)");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("(?m)^\\s*[-*_]{3,}\\s*$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        var value = text.replace("\r\n", "\n").replace('\r', '\n');
        value = CODE_FENCE.matcher(value).replaceAll(" ");
        value = IMAGE.matcher(value).replaceAll("$1");
        value = LINK.matcher(value).replaceAll("$1");
        value = INLINE_CODE.matcher(value).replaceAll("$1");
        value = HORIZONTAL_RULE.matcher(value).replaceAll(" ");
        value = LINE_PREFIX.matcher(value).replaceAll("");
        value = EMPHASIS_MARK.matcher(value).replaceAll("");
        value = HTML_TAG.matcher(value).replaceAll(" ");
        value = WHITESPACE.matcher(value).replaceAll(" ");
        return value.trim();
    }
}
