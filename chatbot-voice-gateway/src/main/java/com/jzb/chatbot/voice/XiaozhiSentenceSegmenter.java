package com.jzb.chatbot.voice;

import java.util.ArrayList;
import java.util.List;

/**
 * 小智 TTS 分句器。
 * <p>
 * 按标点从 LLM 文本流中切出适合逐句合成的文本。
 *
 * @author jiangzhibin
 * @since 2026-06-17 16:50:00
 */
class XiaozhiSentenceSegmenter {

    private static final int MIN_SENTENCE_LENGTH = 8;
    private static final int CONTEXT_BUFFER_MAX_LENGTH = 20;

    private final StringBuilder currentSentence = new StringBuilder();
    private final StringBuilder contextBuffer = new StringBuilder();

    public List<String> accept(String token) {
        if (token == null || token.isEmpty()) {
            return List.of();
        }
        var sentences = new ArrayList<String>();
        for (var offset = 0; offset < token.length(); ) {
            var codePoint = token.codePointAt(offset);
            var value = new String(Character.toChars(codePoint));
            contextBuffer.append(value);
            if (contextBuffer.length() > CONTEXT_BUFFER_MAX_LENGTH) {
                contextBuffer.delete(0, contextBuffer.length() - CONTEXT_BUFFER_MAX_LENGTH);
            }
            currentSentence.append(value);
            if (shouldEmit(value) && currentSentence.length() >= MIN_SENTENCE_LENGTH) {
                var sentence = currentSentence.toString().trim();
                if (containsSubstantialContent(sentence)) {
                    sentences.add(sentence);
                    currentSentence.setLength(0);
                }
            }
            offset += Character.charCount(codePoint);
        }
        return List.copyOf(sentences);
    }

    public String flush() {
        var sentence = currentSentence.toString().trim();
        currentSentence.setLength(0);
        return sentence;
    }

    private boolean shouldEmit(String value) {
        if (isEndMark(value)) {
            if (".".equals(value) && isDecimalPoint()) {
                return false;
            }
            return true;
        }
        return isPauseMark(value) || isSpecialMark(value) || "\n".equals(value) || "\r".equals(value);
    }

    private boolean isEndMark(String value) {
        return "。".equals(value) || "！".equals(value) || "？".equals(value)
                || "!".equals(value) || "?".equals(value) || ".".equals(value);
    }

    private boolean isPauseMark(String value) {
        return "，".equals(value) || "、".equals(value) || "；".equals(value)
                || ",".equals(value) || ";".equals(value);
    }

    private boolean isSpecialMark(String value) {
        return "：".equals(value) || ":".equals(value) || "\"".equals(value);
    }

    private boolean isDecimalPoint() {
        var context = contextBuffer.toString();
        return context.matches(".*\\d+\\.\\d*$");
    }

    private boolean containsSubstantialContent(String text) {
        if (text == null || text.trim().length() < MIN_SENTENCE_LENGTH) {
            return false;
        }
        return text.replaceAll("[\\p{P}\\s]", "").length() >= 2;
    }
}
