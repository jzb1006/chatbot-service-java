package com.jzb.chatbot.hermes;

/**
 * Hermes 对话客户端边界。
 * <p>
 * 屏蔽真实 Hermes 服务或 Fake 实现的差异。
 *
 * @author jiangzhibin
 * @since 2026-06-14 18:36:50
 */
public interface HermesClient {

    /**
     * 发送文本对话请求。
     *
     * @param request Hermes 请求
     * @param config Hermes 客户端配置
     * @return Hermes 响应
     */
    HermesResponse chat(HermesRequest request, HermesClientConfig config);
}
