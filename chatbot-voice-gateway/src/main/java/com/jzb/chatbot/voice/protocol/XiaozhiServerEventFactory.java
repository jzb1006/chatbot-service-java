package com.jzb.chatbot.voice.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 小智服务端 JSON 事件工厂。
 * <p>
 * 统一生成固件可识别的 STT、LLM 和 TTS 控制帧。
 *
 * @author jiangzhibin
 * @since 2026-06-14 20:54:00
 */
@Component
@RequiredArgsConstructor
public class XiaozhiServerEventFactory {

    private final ObjectMapper objectMapper;

    public String stt(String sessionId, String text) {
        return objectMapper.createObjectNode()
                .put("session_id", sessionId)
                .put("type", "stt")
                .put("text", text)
                .toString();
    }

    public String llmEmotion(String sessionId, String emotion) {
        return objectMapper.createObjectNode()
                .put("session_id", sessionId)
                .put("type", "llm")
                .put("emotion", emotion)
                .toString();
    }

    public String ttsStart(String sessionId) {
        return ttsState(sessionId, "start");
    }

    public String ttsSentenceStart(String sessionId, String text) {
        return objectMapper.createObjectNode()
                .put("session_id", sessionId)
                .put("type", "tts")
                .put("state", "sentence_start")
                .put("text", text)
                .toString();
    }

    public String ttsStop(String sessionId) {
        return ttsState(sessionId, "stop");
    }

    public String session(String sessionId, String conversationId) {
        return objectMapper.createObjectNode()
                .put("session_id", sessionId)
                .put("type", "session")
                .put("state", "ready")
                .put("conversation_id", conversationId)
                .toString();
    }

    public String error(String sessionId, String code, String message) {
        return objectMapper.createObjectNode()
                .put("session_id", sessionId)
                .put("type", "error")
                .put("code", code)
                .put("message", message)
                .toString();
    }

    public String mcp(String sessionId, JsonNode payload) {
        var root = objectMapper.createObjectNode()
                .put("session_id", sessionId)
                .put("type", "mcp");
        root.set("payload", payload == null ? objectMapper.createObjectNode() : payload);
        return root.toString();
    }

    private String ttsState(String sessionId, String state) {
        return objectMapper.createObjectNode()
                .put("session_id", sessionId)
                .put("type", "tts")
                .put("state", state)
                .toString();
    }
}
