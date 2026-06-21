package com.jzb.chatbot.speech;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Edge TTS 传输边界。
 * <p>
 * 独立封装非官方 WebSocket 协议，便于在单元测试中替换网络调用。
 *
 * @author jiangzhibin
 * @since 2026-06-21 09:28:00
 */
public interface EdgeTtsTransport {

    /**
     * 调用 Edge 在线朗读服务合成音频。
     *
     * @param request 合成请求
     * @param timeout 请求超时时间
     * @return PCM 音频字节
     */
    byte[] synthesize(EdgeTtsRequest request, Duration timeout);

    /**
     * 创建 JDK HTTP 客户端实现。
     *
     * @return Edge TTS 传输实现
     */
    static EdgeTtsTransport javaNetHttp() {
        return new JavaNetHttpEdgeTtsTransport(HttpClient.newHttpClient());
    }
}
