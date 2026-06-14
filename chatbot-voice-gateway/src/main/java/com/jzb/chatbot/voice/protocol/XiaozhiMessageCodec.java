package com.jzb.chatbot.voice.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 小智协议 JSON 编解码器。
 * <p>
 * 只处理文本控制帧，二进制音频帧由 Handler 直接接收。
 *
 * @author jiangzhibin
 * @since 2026-06-14 18:36:50
 */
@Component
@RequiredArgsConstructor
public class XiaozhiMessageCodec {

    private final ObjectMapper objectMapper;

    /**
     * 编码服务端 hello 消息。
     *
     * @param sessionId 会话标识
     * @param conversationId 对话标识
     * @return JSON 文本
     * @throws JsonProcessingException JSON 序列化失败
     */
    public String encodeServerHello(String sessionId, String conversationId) throws JsonProcessingException {
        return objectMapper.writeValueAsString(XiaozhiServerHello.websocket(sessionId, conversationId));
    }

    /**
     * 解码客户端文本控制帧。
     *
     * @param text JSON 文本
     * @return 客户端消息
     * @throws JsonProcessingException JSON 解析失败
     */
    public XiaozhiClientMessage decodeText(String text) throws JsonProcessingException {
        return objectMapper.readValue(text, XiaozhiClientMessage.class);
    }
}
