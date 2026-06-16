package com.jzb.chatbot.device.ota;

/**
 * 小智 OTA 设备身份。
 * <p>
 * 封装固件 OTA 请求中可用于识别设备的请求头。
 *
 * @author jiangzhibin
 * @since 2026-06-16 13:30:00
 */
public record OtaDeviceIdentity(
        String deviceId,
        String clientId,
        String serialNumber,
        String activationVersion,
        String userAgent
) {

    public OtaDeviceIdentity {
        deviceId = blankToEmpty(deviceId);
        clientId = blankToEmpty(clientId);
        serialNumber = blankToEmpty(serialNumber);
        activationVersion = blankToEmpty(activationVersion);
        userAgent = blankToEmpty(userAgent);
    }

    /**
     * 判断设备是否携带稳定身份。
     *
     * @return true 表示存在设备 ID 或序列号
     */
    public boolean hasStableIdentity() {
        return !deviceId.isBlank() || !serialNumber.isBlank();
    }

    private static String blankToEmpty(String value) {
        return value == null || value.isBlank() ? "" : value;
    }
}
