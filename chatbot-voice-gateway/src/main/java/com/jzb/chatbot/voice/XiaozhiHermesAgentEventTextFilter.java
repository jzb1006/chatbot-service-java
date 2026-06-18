package com.jzb.chatbot.voice;

import com.jzb.chatbot.voice.hermes.HermesAgentEvent;
import com.jzb.chatbot.voice.hermes.HermesAgentEventExtractor;
import java.util.ArrayList;
import java.util.List;

/**
 * Hermes 文本增量中的小智 agent 事件过滤器。
 * <p>
 * 兼容 Responses API 把 {@code event: xiaozhi.agent_event} 当作文本增量包裹返回的情况。
 *
 * @author jiangzhibin
 * @since 2026-06-18 18:20:00
 */
class XiaozhiHermesAgentEventTextFilter {

    private static final String EVENT_MARKER = "event: xiaozhi.agent_event";

    private final HermesAgentEventExtractor eventExtractor = new HermesAgentEventExtractor();
    private final StringBuilder buffer = new StringBuilder();

    public Result accept(String text) {
        if (text == null || text.isEmpty()) {
            return Result.empty();
        }
        buffer.append(text.replace("\r\n", "\n"));
        return drain(false);
    }

    public Result flush() {
        return drain(true);
    }

    private Result drain(boolean flush) {
        var events = new ArrayList<HermesAgentEvent>();
        var text = new StringBuilder();
        var marker = buffer.indexOf(EVENT_MARKER);
        while (marker >= 0) {
            if (marker > 0) {
                text.append(buffer.substring(0, marker));
                buffer.delete(0, marker);
            }
            var eventEnd = buffer.indexOf("\n\n");
            if (eventEnd < 0) {
                break;
            }
            var eventBlock = buffer.substring(0, eventEnd + 2);
            events.addAll(eventExtractor.accept(eventBlock));
            buffer.delete(0, eventEnd + 2);
            marker = buffer.indexOf(EVENT_MARKER);
        }
        if (flush) {
            marker = buffer.indexOf(EVENT_MARKER);
            if (marker >= 0) {
                if (marker > 0) {
                    text.append(buffer.substring(0, marker));
                }
                events.addAll(eventExtractor.accept(buffer.substring(marker) + "\n\n"));
                buffer.setLength(0);
            } else {
                text.append(buffer);
                buffer.setLength(0);
            }
        } else if (buffer.indexOf(EVENT_MARKER) < 0) {
            var keepLength = markerTailLength();
            var emitLength = buffer.length() - keepLength;
            if (emitLength > 0) {
                text.append(buffer.substring(0, emitLength));
                buffer.delete(0, emitLength);
            }
        }
        return new Result(List.copyOf(events), text.toString());
    }

    private int markerTailLength() {
        var maxLength = Math.min(buffer.length(), EVENT_MARKER.length() - 1);
        for (var length = maxLength; length > 0; length--) {
            if (EVENT_MARKER.startsWith(buffer.substring(buffer.length() - length))) {
                return length;
            }
        }
        return 0;
    }

    record Result(List<HermesAgentEvent> events, String text) {

        private static Result empty() {
            return new Result(List.of(), "");
        }
    }
}
