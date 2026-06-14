package com.jzb.chatbot.device.config;

import com.jzb.chatbot.hermes.HermesClientConfig;
import java.time.Duration;

/**
 * 设备网关运行配置。
 * <p>
 * 与旧版 device_gateway 的 llm_config.json 保持字段兼容。
 *
 * @author jiangzhibin
 * @since 2026-06-14 19:20:00
 */
public record DeviceGatewayConfig(
        String baseUrl,
        String model,
        String apiKey,
        String deviceToken,
        int maxPromptChars,
        Duration requestTimeout,
        String sessionKey
) {

    private static final String DEFAULT_BASE_URL = "http://hermes:8642/v1";
    private static final String DEFAULT_MODEL = "hermes-agent";
    private static final int DEFAULT_MAX_PROMPT_CHARS = 2000;
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(120);

    public DeviceGatewayConfig {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        if (model == null || model.isBlank()) {
            model = DEFAULT_MODEL;
        }
        if (deviceToken == null) {
            deviceToken = "";
        }
        if (maxPromptChars <= 0) {
            maxPromptChars = DEFAULT_MAX_PROMPT_CHARS;
        }
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
            requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        }
        if (sessionKey == null) {
            sessionKey = "";
        }
    }

    /**
     * 创建默认配置。
     *
     * @return 默认设备网关配置
     */
    public static DeviceGatewayConfig defaults() {
        return new DeviceGatewayConfig(
                DEFAULT_BASE_URL,
                DEFAULT_MODEL,
                "",
                "",
                DEFAULT_MAX_PROMPT_CHARS,
                DEFAULT_REQUEST_TIMEOUT,
                "owner"
        );
    }

    /**
     * 返回替换设备 Token 后的新配置。
     *
     * @param value 设备 Token
     * @return 新配置
     */
    public DeviceGatewayConfig withDeviceToken(String value) {
        return new DeviceGatewayConfig(baseUrl, model, apiKey, value, maxPromptChars, requestTimeout, sessionKey);
    }

    /**
     * 转换为 Hermes 客户端配置。
     *
     * @return Hermes 客户端配置
     */
    public HermesClientConfig hermesClientConfig() {
        return new HermesClientConfig(baseUrl, model, apiKey, requestTimeout, sessionKey);
    }
}
