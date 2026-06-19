package com.jzb.chatbot.voice.bargein;

/**
 * 小智播放期打断检测器。
 * <p>
 * 只基于 ASR 文本、当前播报文本和播放耗时做保守过滤，不承担用户意图识别。
 *
 * @author jiangzhibin
 * @since 2026-06-19 14:46:00
 */
public class XiaozhiBargeInDetector {

    private final XiaozhiBargeInProperties properties;

    public XiaozhiBargeInDetector(XiaozhiBargeInProperties properties) {
        this.properties = properties;
    }

    public XiaozhiBargeInProperties properties() {
        return properties;
    }

    public XiaozhiBargeInDecision decide(String asrText, String speakingText, long elapsedPlaybackMs) {
        if (!properties.enabled()) {
            return XiaozhiBargeInDecision.ignore("disabled");
        }
        var text = normalize(asrText);
        if (text.isBlank()) {
            return XiaozhiBargeInDecision.ignore("blank_text");
        }
        if (text.length() < properties.minTextLength()) {
            return XiaozhiBargeInDecision.ignore("too_short");
        }
        if (elapsedPlaybackMs < properties.cooldownMs()) {
            return XiaozhiBargeInDecision.ignore("cooldown");
        }
        if (similarity(text, normalize(speakingText)) >= properties.similarityThreshold()) {
            return XiaozhiBargeInDecision.ignore("echo_like_text");
        }
        return XiaozhiBargeInDecision.interrupt("user_speech_detected");
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\p{Punct}\\p{P}\\s]+", "");
    }

    private double similarity(String left, String right) {
        if (left.isBlank() || right.isBlank()) {
            return 0.0;
        }
        var shorter = left.length() <= right.length() ? left : right;
        var longer = left.length() > right.length() ? left : right;
        if (longer.contains(shorter)) {
            return (double) shorter.length() / longer.length();
        }
        var same = 0;
        for (var index = 0; index < Math.min(left.length(), right.length()); index++) {
            if (left.charAt(index) == right.charAt(index)) {
                same++;
            }
        }
        return (double) same / Math.max(left.length(), right.length());
    }
}
