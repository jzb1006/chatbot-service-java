package com.jzb.chatbot.voice.hermes;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Hermes agent 事件提取器。
 * <p>
 * 从 Hermes SSE chunk 中提取 {@code xiaozhi.agent_event} 结构化事件。
 *
 * @author jiangzhibin
 * @since 2026-06-18 04:10:00
 */
public class HermesAgentEventExtractor {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String AGENT_EVENT = "xiaozhi.agent_event";

    private final StringBuilder buffer = new StringBuilder();

    /**
     * 接收 Hermes SSE chunk 并提取完整 agent 事件。
     *
     * @param chunk Hermes SSE chunk
     * @return 已完整解析出的 agent 事件列表
     */
    public List<HermesAgentEvent> accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return List.of();
        }
        buffer.append(chunk.replace("\r\n", "\n"));
        var events = new ArrayList<HermesAgentEvent>();
        var boundary = nextBoundary();
        while (boundary.start() >= 0) {
            var event = buffer.substring(0, boundary.start());
            buffer.delete(0, boundary.end());
            extractEvent(event).ifPresent(events::add);
            boundary = nextBoundary();
        }
        return List.copyOf(events);
    }

    private Boundary nextBoundary() {
        var lf = buffer.indexOf("\n\n");
        var splitCrLf = buffer.indexOf("\n\r\n");
        if (lf < 0) {
            return splitCrLf < 0 ? Boundary.missing() : new Boundary(splitCrLf, splitCrLf + 3);
        }
        if (splitCrLf < 0 || lf < splitCrLf) {
            return new Boundary(lf, lf + 2);
        }
        return new Boundary(splitCrLf, splitCrLf + 3);
    }

    private java.util.Optional<HermesAgentEvent> extractEvent(String event) {
        if (!AGENT_EVENT.equals(eventType(event))) {
            return java.util.Optional.empty();
        }
        var data = new StringBuilder();
        for (var line : event.split("\n")) {
            if (line.startsWith("data:")) {
                data.append(line.substring("data:".length()).trim());
            }
        }
        if (data.isEmpty()) {
            return java.util.Optional.empty();
        }
        try {
            var root = OBJECT_MAPPER.readTree(data.toString());
            return java.util.Optional.of(new HermesAgentEvent(
                    root.path("action").asText(null),
                    root.path("message").asText(null),
                    root.path("delay_seconds").asLong(0L),
                    root.path("confirmation_text").asText(null)
            ));
        } catch (IOException exception) {
            return java.util.Optional.empty();
        }
    }

    private String eventType(String event) {
        for (var line : event.split("\n")) {
            if (line.startsWith("event:")) {
                return line.substring("event:".length()).trim();
            }
        }
        return null;
    }

    private record Boundary(int start, int end) {

        private static Boundary missing() {
            return new Boundary(-1, -1);
        }
    }
}
