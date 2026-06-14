package com.jzb.chatbot.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jzb.chatbot.device.config.DeviceGatewayConfig;
import com.jzb.chatbot.device.config.DeviceGatewayConfigStore;
import com.jzb.chatbot.device.dto.ConversationCreateRequest;
import com.jzb.chatbot.device.dto.ConversationCreateResponse;
import com.jzb.chatbot.device.dto.DeviceChatRequest;
import com.jzb.chatbot.device.dto.DeviceChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备文本聊天接口。
 * <p>
 * 提供与现有设备文本协议兼容的 REST 入口。
 *
 * @author jiangzhibin
 * @since 2026-06-14 18:36:50
 */
@RestController
@RequiredArgsConstructor
public class DeviceChatController {

    private final DeviceChatService chatService;
    private final DeviceGatewayConfigStore configStore;
    private final ObjectMapper objectMapper;

    /**
     * 处理设备文本聊天。
     *
     * @param request 设备聊天请求
     * @return 设备聊天响应
     */
    @PostMapping("/api/chat")
    public ResponseEntity<?> chat(
            @RequestBody DeviceChatRequest request,
            @RequestHeader(value = "X-Device-Token", defaultValue = "") String deviceToken
    ) {
        var config = configStore.get();
        var tokenStatus = tokenStatus(config, deviceToken);
        if (tokenStatus != HttpStatus.OK) {
            return ResponseEntity.status(tokenStatus).body(Map.of("error", "device token required"));
        }
        return ResponseEntity.ok(chatService.chat(request, config));
    }

    /**
     * 处理设备文本流式聊天。
     *
     * @param request 设备聊天请求
     * @param deviceToken 设备 Token
     * @return SSE 流式响应
     */
    @PostMapping("/api/chat/stream")
    public ResponseEntity<StreamingResponseBody> streamChat(
            @RequestBody DeviceChatRequest request,
            @RequestHeader(value = "X-Device-Token", defaultValue = "") String deviceToken
    ) {
        var config = configStore.get();
        var tokenStatus = tokenStatus(config, deviceToken);
        if (tokenStatus != HttpStatus.OK) {
            throw new InvalidDeviceChatRequestException(tokenStatus, "device token required");
        }
        var response = chatService.streamChat(request, config);
        StreamingResponseBody body = output -> {
            var conversation = new LinkedHashMap<String, String>();
            conversation.put("device_id", response.deviceId());
            conversation.put("conversation_id", response.conversationId());
            writeSse(output, "conversation", objectMapper.writeValueAsString(conversation));
            try (var chunks = response.chunks()) {
                chunks.forEach(chunk -> {
                    try {
                        output.write(chunk.getBytes(StandardCharsets.UTF_8));
                        output.flush();
                    } catch (IOException exception) {
                        throw new IllegalStateException("write SSE chunk failed", exception);
                    }
                });
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(body);
    }

    /**
     * 新建设备对话。
     *
     * @param request 新建对话请求
     * @param deviceToken 设备 Token
     * @return 新建对话响应
     */
    @PostMapping("/api/conversations/new")
    public ResponseEntity<?> createConversation(
            @RequestBody ConversationCreateRequest request,
            @RequestHeader(value = "X-Device-Token", defaultValue = "") String deviceToken
    ) {
        var config = configStore.get();
        var tokenStatus = tokenStatus(config, deviceToken);
        if (tokenStatus != HttpStatus.OK) {
            return ResponseEntity.status(tokenStatus).body(Map.of("error", "device token required"));
        }
        return ResponseEntity.ok(new ConversationCreateResponse(
                chatService.resolveDeviceId(request.deviceId()),
                chatService.createConversationId()
        ));
    }

    private HttpStatus tokenStatus(DeviceGatewayConfig config, String actualToken) {
        if (config.deviceToken().isBlank()) {
            return HttpStatus.OK;
        }
        if (actualToken == null || actualToken.isBlank()) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (!config.deviceToken().equals(actualToken)) {
            return HttpStatus.FORBIDDEN;
        }
        return HttpStatus.OK;
    }

    private void writeSse(java.io.OutputStream output, String event, String data) throws IOException {
        output.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
        output.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }
}
