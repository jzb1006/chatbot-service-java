package com.jzb.chatbot.hermes;

import java.time.Duration;

/**
 * Hermes 客户端运行配置。
 * <p>
 * 聚合调用 Hermes Responses API 所需的最小配置。
 *
 * @author jiangzhibin
 * @since 2026-06-14 19:18:00
 */
public record HermesClientConfig(
        String baseUrl,
        String model,
        String apiKey,
        Duration requestTimeout,
        String sessionKey
) {

    public HermesClientConfig {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API Key is required");
        }
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
    }

    /**
     * 返回去除末尾斜杠后的基础地址。
     *
     * @return 规范化基础地址
     */
    public String normalizedBaseUrl() {
        var trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * 返回可用于 HTTP Header 的会话 Key。
     *
     * @return 清理后的会话 Key
     */
    public String sanitizedSessionKey() {
        if (sessionKey == null || sessionKey.isBlank()) {
            return "";
        }
        var builder = new StringBuilder();
        sessionKey.trim().chars()
                .filter(value -> value >= 0x20 && value != 0x7F)
                .limit(256)
                .forEach(value -> builder.append((char) value));
        return builder.toString();
    }
}
