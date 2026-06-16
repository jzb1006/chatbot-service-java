package com.jzb.chatbot.device.ota;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 小智 OTA Bean 配置。
 * <p>
 * 使用当前项目的 {@link Value} 风格绑定配置，避免引入新的配置模式。
 *
 * @author jiangzhibin
 * @since 2026-06-16 13:30:00
 */
@Configuration
public class XiaozhiOtaBeans {

    @Bean
    XiaozhiOtaProperties xiaozhiOtaProperties(
            @Value("${chatbot.ota.enabled:true}") boolean enabled,
            @Value("${chatbot.ota.websocket.url:}") String websocketUrl,
            @Value("${chatbot.ota.websocket.token:}") String websocketToken,
            @Value("${chatbot.ota.websocket.version:1}") int websocketVersion,
            @Value("${chatbot.ota.firmware.version:}") String firmwareVersion,
            @Value("${chatbot.ota.firmware.url:}") String firmwareUrl,
            @Value("${chatbot.ota.firmware.force:false}") boolean firmwareForce,
            @Value("${chatbot.ota.firmware.directory:/app/firmware}") Path firmwareDirectory,
            @Value("${chatbot.ota.activation.required:false}") boolean activationRequired,
            @Value("${chatbot.ota.activation.message:请在服务端完成设备激活}") String activationMessage,
            @Value("${chatbot.ota.activation.ttl-seconds:30}") long activationTtlSeconds,
            @Value("${chatbot.ota.security.allowed-device-ids:}") String allowedDeviceIds,
            @Value("${chatbot.ota.security.allowed-serial-numbers:}") String allowedSerialNumbers
    ) {
        var properties = new XiaozhiOtaProperties(
                enabled,
                websocketUrl,
                websocketToken,
                websocketVersion,
                firmwareVersion,
                firmwareUrl,
                firmwareForce,
                firmwareDirectory,
                activationRequired,
                activationMessage,
                Duration.ofSeconds(activationTtlSeconds),
                csv(allowedDeviceIds),
                csv(allowedSerialNumbers)
        );
        if (properties.exposesWebsocketTokenWithoutAllowlist()) {
            throw new IllegalStateException("Xiaozhi OTA websocket token requires device or serial allowlist");
        }
        return properties;
    }

    private List<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }
}
