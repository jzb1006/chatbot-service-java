package com.jzb.chatbot.speech;

/**
 * ASR 识别结果。
 * <p>
 * 仅承载语音输入基础设施字段；意图、情绪和工具动作由 Hermes agent 返回。
 *
 * @author jiangzhibin
 * @since 2026-06-17 00:00:00
 */
public record SpeechToTextResult(String text, String provider, long audioMillis) {

    public SpeechToTextResult {
        text = text == null ? "" : text;
        provider = provider == null || provider.isBlank() ? "unknown" : provider;
        if (audioMillis < 0) {
            audioMillis = 0;
        }
    }

    public static SpeechToTextResult blank(String provider) {
        return new SpeechToTextResult("", provider, 0);
    }
}
