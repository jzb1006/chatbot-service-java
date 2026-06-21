package com.jzb.chatbot.speech;

import java.time.Duration;
import java.util.List;

/**
 * Sherpa-ONNX WebSocket 传输边界。
 * <p>
 * 调用方发送 little-endian float32 音频块，传输实现负责协议收尾并返回服务端 JSON 文本消息。
 *
 * @author jiangzhibin
 * @since 2026-06-21 11:54:00
 */
interface SherpaOnnxStreamingTransport {

    List<String> transcribe(String uri, Iterable<byte[]> audioChunks, Duration timeout);
}
