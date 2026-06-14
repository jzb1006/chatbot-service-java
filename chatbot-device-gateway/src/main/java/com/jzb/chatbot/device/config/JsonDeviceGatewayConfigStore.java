package com.jzb.chatbot.device.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * JSON 文件设备网关配置存储。
 * <p>
 * 读取旧版 Python device_gateway 使用的 /app/data/llm_config.json。
 *
 * @author jiangzhibin
 * @since 2026-06-14 19:20:00
 */
@Component
@RequiredArgsConstructor
public class JsonDeviceGatewayConfigStore implements DeviceGatewayConfigStore {

    private final ObjectMapper objectMapper;

    @Value("${chatbot.device.config-path:/app/data/llm_config.json}")
    private Path configPath;

    @Override
    public DeviceGatewayConfig get() {
        if (!Files.exists(configPath)) {
            return DeviceGatewayConfig.defaults();
        }
        try {
            var root = objectMapper.readTree(configPath.toFile());
            var defaults = DeviceGatewayConfig.defaults();
            return new DeviceGatewayConfig(
                    text(root, "base_url", defaults.baseUrl()),
                    text(root, "model", defaults.model()),
                    text(root, "api_key", defaults.apiKey()),
                    text(root, "device_token", defaults.deviceToken()),
                    positiveInt(root, "max_prompt_chars", defaults.maxPromptChars()),
                    Duration.ofSeconds(positiveInt(root, "request_timeout", (int) defaults.requestTimeout().toSeconds())),
                    text(root, "session_key", defaults.sessionKey())
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read device gateway config: " + configPath, exception);
        }
    }

    private String text(JsonNode root, String fieldName, String defaultValue) {
        var value = root.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return defaultValue;
        }
        return value.asText(defaultValue);
    }

    private int positiveInt(JsonNode root, String fieldName, int defaultValue) {
        var value = root.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return defaultValue;
        }
        try {
            var parsed = Integer.parseInt(value.asText());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }
}
