package com.jzb.chatbot.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Chatbot 服务启动入口。
 * <p>
 * 聚合设备文本 REST 网关和小智 WebSocket 网关。
 *
 * @author jiangzhibin
 * @since 2026-06-14 18:36:50
 */
@SpringBootApplication(scanBasePackages = "com.jzb.chatbot")
public class ChatbotApplication {

    /**
     * 启动 Chatbot 服务。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(ChatbotApplication.class, args);
    }
}
