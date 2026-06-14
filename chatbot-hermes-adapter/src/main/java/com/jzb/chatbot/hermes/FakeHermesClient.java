package com.jzb.chatbot.hermes;

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
        var text = "ping".equals(request.text()) ? "pong" : request.text();
        return new HermesResponse(request.conversationId(), text);
    }
}
