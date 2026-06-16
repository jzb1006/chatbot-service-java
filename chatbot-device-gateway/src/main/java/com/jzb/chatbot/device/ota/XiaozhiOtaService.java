package com.jzb.chatbot.device.ota;

import com.fasterxml.jackson.databind.JsonNode;
import com.jzb.chatbot.device.InvalidDeviceChatRequestException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 小智 OTA 服务。
 * <p>
 * 按设备身份生成 OTA 响应，并处理首版 challenge 激活流程。
 *
 * @author jiangzhibin
 * @since 2026-06-16 13:32:00
 */
@Service
@RequiredArgsConstructor
public class XiaozhiOtaService {

    /**
     * 设备激活状态。
     */
    public enum ActivationStatus {
        ACTIVATED,
        PENDING,
        REJECTED
    }

    private final XiaozhiOtaProperties properties;
    private final XiaozhiActivationStore activationStore;
    private final XiaozhiOtaResponseFactory responseFactory;

    /**
     * 检查 OTA 配置。
     *
     * @param identity 设备身份
     * @param body 请求体
     * @return OTA 响应
     */
    public JsonNode check(OtaDeviceIdentity identity, JsonNode body) {
        if (!properties.enabled()) {
            return responseFactory.disabledResponse();
        }
        var allowed = allowed(identity);
        var safeProperties = allowed
                ? properties
                : properties.withWebsocketToken("").withFirmwareUrl("").withFirmwareVersion("");
        var challenge = allowed ? activationChallenge(identity) : "";
        var upgradeAvailable = !safeProperties.firmwareVersion().isBlank() && !safeProperties.firmwareUrl().isBlank();
        return responseFactory.checkResponse(identity, safeProperties, upgradeAvailable, challenge);
    }

    /**
     * 激活设备。
     *
     * @param identity 设备身份
     * @param payload 激活 payload
     * @return 激活状态
     */
    public ActivationStatus activate(OtaDeviceIdentity identity, JsonNode payload) {
        if (!properties.activationRequired()) {
            return ActivationStatus.ACTIVATED;
        }
        if (!identity.hasStableIdentity()) {
            return ActivationStatus.REJECTED;
        }
        var challenge = payload.path("challenge").asText("");
        if (challenge.isBlank()) {
            return ActivationStatus.PENDING;
        }
        return activationStore.activate(identity, challenge)
                ? ActivationStatus.ACTIVATED
                : ActivationStatus.REJECTED;
    }

    /**
     * 读取固件文件资源。
     *
     * @param fileName 固件文件名
     * @return 固件资源
     */
    public Optional<Resource> firmware(String fileName) {
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            throw new InvalidDeviceChatRequestException(HttpStatus.BAD_REQUEST, "invalid firmware path");
        }
        var target = properties.firmwareDirectory().resolve(fileName).normalize();
        var root = properties.firmwareDirectory().normalize();
        if (!target.startsWith(root)) {
            throw new InvalidDeviceChatRequestException(HttpStatus.BAD_REQUEST, "invalid firmware path");
        }
        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            var realRoot = root.toRealPath();
            var realTarget = target.toRealPath();
            if (!realTarget.startsWith(realRoot)) {
                throw new InvalidDeviceChatRequestException(HttpStatus.BAD_REQUEST, "invalid firmware path");
            }
            return Optional.of(new FileSystemResource(realTarget));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private boolean allowed(OtaDeviceIdentity identity) {
        var deviceIds = properties.allowedDeviceIds();
        var serialNumbers = properties.allowedSerialNumbers();
        if (deviceIds.isEmpty() && serialNumbers.isEmpty()) {
            return true;
        }
        return deviceIds.contains(identity.deviceId()) || serialNumbers.contains(identity.serialNumber());
    }

    private String activationChallenge(OtaDeviceIdentity identity) {
        if (!properties.activationRequired()) {
            return "";
        }
        if (!identity.hasStableIdentity()) {
            return "";
        }
        if (!allowed(identity) || activationStore.isActivated(identity)) {
            return "";
        }
        return activationStore.createChallenge(identity, properties.activationTtl());
    }
}
