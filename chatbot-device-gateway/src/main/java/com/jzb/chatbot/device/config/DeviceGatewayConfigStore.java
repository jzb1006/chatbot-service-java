package com.jzb.chatbot.device.config;

/**
 * 设备网关配置存储。
 * <p>
 * 屏蔽配置来源，便于生产读取 JSON 文件、测试替换实现。
 *
 * @author jiangzhibin
 * @since 2026-06-14 19:20:00
 */
public interface DeviceGatewayConfigStore {

    /**
     * 获取当前运行配置。
     *
     * @return 当前运行配置
     */
    DeviceGatewayConfig get();
}
