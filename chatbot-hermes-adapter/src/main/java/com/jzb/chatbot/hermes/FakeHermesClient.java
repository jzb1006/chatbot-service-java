package com.jzb.chatbot.hermes;

import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Hermes Fake 客户端。
 * <p>
 * 用于第一阶段协议联调和自动化测试，不访问真实 Hermes 服务。
 *
 * @author jiangzhibin
 * @since 2026-06-14 18:36:50
 */
@Component
public class FakeHermesClient implements HermesClient {

    @Override
    public HermesResponse chat(HermesRequest request, HermesClientConfig config) {
        var userText = userText(request.text());
        var text = "ping".equals(userText) ? "pong" : userText;
        return new HermesResponse(request.conversationId(), text);
    }

    @Override
    public Stream<String> streamChat(HermesRequest request, HermesClientConfig config) {
        var userText = userText(request.text());
        var text = "ping".equals(userText) ? "pong" : userText;
        return Stream.of(
                "event: message\ndata: {\"answer\":\"" + escapeJson(text) + "\"}\n\n",
                "event: done\ndata: {}\n\n"
        );
    }

    private String userText(String value) {
        var marker = "\nASR: ";
        var index = value.lastIndexOf(marker);
        if (index < 0) {
            return value;
        }
        return value.substring(index + marker.length()).strip();
    }

    private String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
