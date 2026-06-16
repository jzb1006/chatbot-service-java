package com.jzb.chatbot.device.ota;

import java.time.Duration;

/**
 * 小智 OTA 激活状态存储。
 * <p>
 * 首版用于 challenge 生成、校验和设备激活状态读取。
 *
 * @author jiangzhibin
 * @since 2026-06-16 13:32:00
 */
public interface XiaozhiActivationStore {

    /**
     * 创建设备激活 challenge。
     *
     * @param identity 设备身份
     * @param ttl 有效期
     * @return challenge
     */
    String createChallenge(OtaDeviceIdentity identity, Duration ttl);

    /**
     * 激活设备。
     *
     * @param identity 设备身份
     * @param challenge 激活 challenge
     * @return true 表示激活成功
     */
    boolean activate(OtaDeviceIdentity identity, String challenge);

    /**
     * 判断设备是否已激活。
     *
     * @param identity 设备身份
     * @return true 表示已激活
     */
    boolean isActivated(OtaDeviceIdentity identity);

    /**
     * 移除设备激活记录。
     *
     * @param identity 设备身份
     */
    void remove(OtaDeviceIdentity identity);
}
