package com.jzb.chatbot.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Hermes SSE 文本增量提取器。
 * <p>
 * 将 Responses API 或兼容网关返回的 SSE 片段转换为纯文本增量。
 *
 * @author jiangzhibin
 * @since 2026-06-17 16:50:00
 */
class XiaozhiHermesStreamTextExtractor {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final StringBuilder buffer = new StringBuilder();

    public List<String> accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return List.of();
        }
        buffer.append(chunk.replace("\r\n", "\n"));
        var texts = new ArrayList<String>();
        var boundary = buffer.indexOf("\n\n");
        while (boundary >= 0) {
            var event = buffer.substring(0, boundary);
            buffer.delete(0, boundary + 2);
            texts.addAll(extractEvent(event));
            boundary = buffer.indexOf("\n\n");
        }
        return List.copyOf(texts);
    }

    public List<String> flush() {
        if (buffer.isEmpty()) {
            return List.of();
        }
        var event = buffer.toString();
        buffer.setLength(0);
        return extractEvent(event);
    }

    private List<String> extractEvent(String event) {
        var data = new StringBuilder();
        for (var line : event.split("\n")) {
            if (line.startsWith("data:")) {
                data.append(line.substring("data:".length()).trim());
            }
        }
        if (data.isEmpty() || "[DONE]".contentEquals(data)) {
            return List.of();
        }
        try {
            var root = OBJECT_MAPPER.readTree(data.toString());
            if (!root.path("delta").isMissingNode()) {
                return text(root.path("delta").asText());
            }
            if (!root.path("answer").isMissingNode()) {
                return text(root.path("answer").asText());
            }
            if (!root.path("text").isMissingNode()) {
                return text(root.path("text").asText());
            }
            if (!root.path("output_text").isMissingNode()) {
                return text(root.path("output_text").asText());
            }
            return List.of();
        } catch (IOException exception) {
            return List.of();
        }
    }

    private List<String> text(String value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        return List.of(value);
    }
}
