package com.jzb.chatbot.voice;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 小智 WebSocket 配置。
 * <p>
 * 注册小智 ESP32 固件连接入口。
 *
 * @author jiangzhibin
 * @since 2026-06-14 18:36:50
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class XiaozhiWebSocketConfig implements WebSocketConfigurer {

    private final XiaozhiWebSocketHandler handler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/xiaozhi/v1").setAllowedOrigins("*");
    }
}
