package com.jzb.chatbot.device.ota;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 小智 OTA 响应工厂。
 * <p>
 * 统一构造固件可识别的 OTA JSON 字段。
 *
 * @author jiangzhibin
 * @since 2026-06-16 13:30:00
 */
@Component
@RequiredArgsConstructor
public class XiaozhiOtaResponseFactory {

    private final ObjectMapper objectMapper;

    /**
     * 构造 OTA 检查响应。
     *
     * @param identity 设备身份
     * @param properties OTA 配置
     * @param upgradeAvailable 是否存在可用升级
     * @param activationChallenge 激活 challenge
     * @return OTA JSON 响应
     */
    public ObjectNode checkResponse(
            OtaDeviceIdentity identity,
            XiaozhiOtaProperties properties,
            boolean upgradeAvailable,
            String activationChallenge
    ) {
        var root = objectMapper.createObjectNode();
        root.set("server_time", serverTime());
        root.set("websocket", websocket(properties));
        root.set("firmware", firmware(properties, upgradeAvailable));
        if (activationChallenge != null && !activationChallenge.isBlank()) {
            root.set("activation", activation(properties, activationChallenge));
        }
        return root;
    }

    /**
     * 构造 OTA 关闭时的安全响应。
     *
     * @return 关闭响应
     */
    public ObjectNode disabledResponse() {
        var root = objectMapper.createObjectNode();
        root.set("server_time", serverTime());
        root.set("websocket", objectMapper.createObjectNode());
        root.set("firmware", objectMapper.createObjectNode()
                .put("version", "")
                .put("url", ""));
        return root;
    }

    private ObjectNode websocket(XiaozhiOtaProperties properties) {
        var websocket = objectMapper.createObjectNode();
        websocket.put("url", properties.websocketUrl());
        websocket.put("token", properties.websocketToken());
        websocket.put("version", properties.websocketVersion());
        return websocket;
    }

    private ObjectNode firmware(XiaozhiOtaProperties properties, boolean upgradeAvailable) {
        var firmware = objectMapper.createObjectNode();
        firmware.put("version", upgradeAvailable ? properties.firmwareVersion() : "");
        firmware.put("url", upgradeAvailable ? properties.firmwareUrl() : "");
        if (properties.firmwareForce()) {
            firmware.put("force", 1);
        }
        return firmware;
    }

    private ObjectNode activation(XiaozhiOtaProperties properties, String challenge) {
        var activation = objectMapper.createObjectNode();
        activation.put("message", properties.activationMessage());
        activation.put("challenge", challenge);
        activation.put("timeout_ms", properties.activationTtl().toMillis());
        return activation;
    }

    private ObjectNode serverTime() {
        var serverTime = objectMapper.createObjectNode();
        serverTime.put("timestamp", Instant.now().toEpochMilli());
        serverTime.put("timezone_offset", 480);
        return serverTime;
    }
}
