package com.jzb.chatbot.voice;

import java.util.Locale;

/**
 * 小智 ASR 工作模式。
 * <p>
 * 用于在句子级识别和实时流式识别之间选择网关 Bean。
 *
 * @author jiangzhibin
 * @since 2026-06-18 00:00:00
 */
public record XiaozhiAsrMode(String value) {

    private static final String SENTENCE = "sentence";
    private static final String STREAMING = "streaming";

    public XiaozhiAsrMode {
        if (value == null || value.isBlank()) {
            value = SENTENCE;
        } else {
            value = value.trim().toLowerCase(Locale.ROOT);
        }
        if (!SENTENCE.equals(value) && !STREAMING.equals(value)) {
            throw new IllegalArgumentException("Unsupported Xiaozhi ASR mode: " + value);
        }
    }

    /**
     * 判断是否启用实时流式 ASR。
     *
     * @return 启用实时流式 ASR 时返回 true
     */
    public boolean streaming() {
        return STREAMING.equals(value);
    }
}
