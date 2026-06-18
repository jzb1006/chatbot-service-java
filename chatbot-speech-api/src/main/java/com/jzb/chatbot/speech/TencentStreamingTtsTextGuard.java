package com.jzb.chatbot.speech;

import java.util.Locale;

/**
 * 腾讯云流式 TTS 文本保护器。
 * <p>
 * 在发送 ACTION_SYNTHESIS 前拒绝 SSML 和单次超长文本，避免把可本地判定的错误推给腾讯云。
 *
 * @author jiangzhibin
 * @since 2026-06-18 12:54:00
 */
public final class TencentStreamingTtsTextGuard {

    private static final int DEFAULT_MAX_TEXT_LENGTH = 150;

    private final int maxTextLength;

    public TencentStreamingTtsTextGuard() {
        this(DEFAULT_MAX_TEXT_LENGTH);
    }

    public TencentStreamingTtsTextGuard(int maxTextLength) {
        if (maxTextLength <= 0) {
            throw new IllegalArgumentException("maxTextLength must be positive");
        }
        this.maxTextLength = maxTextLength;
    }

    /**
     * 校验待发送文本。
     *
     * @param text 待发送文本
     */
    public void validate(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        var lowerText = text.toLowerCase(Locale.ROOT);
        if (lowerText.contains("<speak") || lowerText.contains("</speak>")) {
            throw new IllegalArgumentException("Tencent streaming TTS does not support SSML");
        }
        if (text.length() > maxTextLength) {
            throw new IllegalArgumentException("Tencent streaming TTS text is too long: " + text.length());
        }
    }
}
