package com.jzb.chatbot.device.ota;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 小智 OTA 配置。
 * <p>
 * 承载固件 OTA、WebSocket 和激活安全策略的不可变配置。
 *
 * @author jiangzhibin
 * @since 2026-06-16 13:30:00
 */
public record XiaozhiOtaProperties(
        boolean enabled,
        String websocketUrl,
        String websocketToken,
        int websocketVersion,
        String firmwareVersion,
        String firmwareUrl,
        boolean firmwareForce,
        Path firmwareDirectory,
        boolean activationRequired,
        String activationMessage,
        Duration activationTtl,
        List<String> allowedDeviceIds,
        List<String> allowedSerialNumbers
) {

    public XiaozhiOtaProperties {
        websocketUrl = blankToEmpty(websocketUrl);
        websocketToken = blankToEmpty(websocketToken);
        websocketVersion = websocketVersion <= 0 ? 1 : websocketVersion;
        firmwareVersion = blankToEmpty(firmwareVersion);
        firmwareUrl = blankToEmpty(firmwareUrl);
        firmwareDirectory = firmwareDirectory == null ? Path.of("/app/firmware") : firmwareDirectory;
        activationMessage = activationMessage == null || activationMessage.isBlank()
                ? "请在服务端完成设备激活"
                : activationMessage;
        activationTtl = activationTtl == null || activationTtl.isNegative() || activationTtl.isZero()
                ? Duration.ofSeconds(30)
                : activationTtl;
        allowedDeviceIds = allowedDeviceIds == null ? List.of() : List.copyOf(allowedDeviceIds);
        allowedSerialNumbers = allowedSerialNumbers == null ? List.of() : List.copyOf(allowedSerialNumbers);
    }

    /**
     * 创建默认 OTA 配置。
     *
     * @return 默认配置
     */
    public static XiaozhiOtaProperties defaults() {
        return new XiaozhiOtaProperties(
                true,
                "",
                "",
                1,
                "",
                "",
                false,
                Path.of("/app/firmware"),
                false,
                "请在服务端完成设备激活",
                Duration.ofSeconds(30),
                List.of(),
                List.of()
        );
    }

    /**
     * 返回替换 WebSocket URL 后的新配置。
     *
     * @param value WebSocket URL
     * @return 新配置
     */
    public XiaozhiOtaProperties withWebsocketUrl(String value) {
        return new XiaozhiOtaProperties(
                enabled, value, websocketToken, websocketVersion,
                firmwareVersion, firmwareUrl, firmwareForce, firmwareDirectory,
                activationRequired, activationMessage, activationTtl,
                allowedDeviceIds, allowedSerialNumbers
        );
    }

    /**
     * 返回替换 WebSocket token 后的新配置。
     *
     * @param value WebSocket token
     * @return 新配置
     */
    public XiaozhiOtaProperties withWebsocketToken(String value) {
        return new XiaozhiOtaProperties(
                enabled, websocketUrl, value, websocketVersion,
                firmwareVersion, firmwareUrl, firmwareForce, firmwareDirectory,
                activationRequired, activationMessage, activationTtl,
                allowedDeviceIds, allowedSerialNumbers
        );
    }

    /**
     * 返回替换 WebSocket 协议版本后的新配置。
     *
     * @param value 协议版本
     * @return 新配置
     */
    public XiaozhiOtaProperties withWebsocketVersion(int value) {
        return new XiaozhiOtaProperties(
                enabled, websocketUrl, websocketToken, value,
                firmwareVersion, firmwareUrl, firmwareForce, firmwareDirectory,
                activationRequired, activationMessage, activationTtl,
                allowedDeviceIds, allowedSerialNumbers
        );
    }

    /**
     * 返回替换固件版本后的新配置。
     *
     * @param value 固件版本
     * @return 新配置
     */
    public XiaozhiOtaProperties withFirmwareVersion(String value) {
        return new XiaozhiOtaProperties(
                enabled, websocketUrl, websocketToken, websocketVersion,
                value, firmwareUrl, firmwareForce, firmwareDirectory,
                activationRequired, activationMessage, activationTtl,
                allowedDeviceIds, allowedSerialNumbers
        );
    }

    /**
     * 返回替换固件 URL 后的新配置。
     *
     * @param value 固件 URL
     * @return 新配置
     */
    public XiaozhiOtaProperties withFirmwareUrl(String value) {
        return new XiaozhiOtaProperties(
                enabled, websocketUrl, websocketToken, websocketVersion,
                firmwareVersion, value, firmwareForce, firmwareDirectory,
                activationRequired, activationMessage, activationTtl,
                allowedDeviceIds, allowedSerialNumbers
        );
    }

    /**
     * 返回替换设备白名单后的新配置。
     *
     * @param values 设备 ID 白名单
     * @return 新配置
     */
    public XiaozhiOtaProperties withAllowedDeviceIds(List<String> values) {
        return new XiaozhiOtaProperties(
                enabled, websocketUrl, websocketToken, websocketVersion,
                firmwareVersion, firmwareUrl, firmwareForce, firmwareDirectory,
                activationRequired, activationMessage, activationTtl,
                values, allowedSerialNumbers
        );
    }

    /**
     * 返回替换序列号白名单后的新配置。
     *
     * @param values 序列号白名单
     * @return 新配置
     */
    public XiaozhiOtaProperties withAllowedSerialNumbers(List<String> values) {
        return new XiaozhiOtaProperties(
                enabled, websocketUrl, websocketToken, websocketVersion,
                firmwareVersion, firmwareUrl, firmwareForce, firmwareDirectory,
                activationRequired, activationMessage, activationTtl,
                allowedDeviceIds, values
        );
    }

    /**
     * 判断当前配置是否会对未限制设备暴露 WebSocket token。
     *
     * @return true 表示存在暴露风险
     */
    public boolean exposesWebsocketTokenWithoutAllowlist() {
        return !websocketToken.isBlank() && allowedDeviceIds.isEmpty() && allowedSerialNumbers.isEmpty();
    }

    private static String blankToEmpty(String value) {
        return value == null || value.isBlank() ? "" : value;
    }
}
