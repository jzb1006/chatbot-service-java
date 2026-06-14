package com.jzb.chatbot.device;

import com.jzb.chatbot.common.id.ConversationId;
import com.jzb.chatbot.common.id.DeviceId;
import com.jzb.chatbot.device.config.DeviceGatewayConfig;
import com.jzb.chatbot.device.dto.DeviceChatRequest;
import com.jzb.chatbot.device.dto.DeviceChatResponse;
import com.jzb.chatbot.hermes.HermesClient;
import com.jzb.chatbot.hermes.HermesRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 设备文本聊天服务。
 * <p>
 * 负责将 HTTP 协议 DTO 转换为 Hermes 对话请求。
 *
 * @author jiangzhibin
 * @since 2026-06-14 18:36:50
 */
@Service
@RequiredArgsConstructor
public class DeviceChatService {

    private final HermesClient hermesClient;

    /**
     * 处理设备文本聊天请求。
     *
     * @param request 设备请求
     * @return 聊天响应
     */
    public DeviceChatResponse chat(DeviceChatRequest request, DeviceGatewayConfig config) {
        var deviceId = resolveDeviceId(request.deviceId());
        var conversationId = resolveConversationId(request.conversationId());
        var text = resolveText(request.text(), config);
        var hermesResponse = hermesClient.chat(new HermesRequest(
                new DeviceId(deviceId),
                new ConversationId(conversationId),
                text
        ), config.hermesClientConfig());
        return new DeviceChatResponse(deviceId, hermesResponse.conversationId().value(), hermesResponse.text());
    }

    /**
     * 生成新对话标识。
     *
     * @return 新对话标识
     */
    public String createConversationId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 解析设备标识。
     *
     * @param value 请求设备标识
     * @return 规范化设备标识
     */
    public String resolveDeviceId(String value) {
        if (value == null || value.isBlank()) {
            return "default-device";
        }
        return value.trim();
    }

    private String resolveConversationId(String value) {
        if (value == null || value.isBlank()) {
            return createConversationId();
        }
        return value.trim();
    }

    private String resolveText(String value, DeviceGatewayConfig config) {
        if (value == null || value.isBlank()) {
            throw new InvalidDeviceChatRequestException(HttpStatus.BAD_REQUEST, "prompt is required");
        }
        var text = value.trim();
        if (text.length() > config.maxPromptChars()) {
            throw new InvalidDeviceChatRequestException(HttpStatus.PAYLOAD_TOO_LARGE, "prompt too long");
        }
        return text;
    }
}
