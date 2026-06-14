package com.jzb.chatbot.device;

import com.jzb.chatbot.common.id.ConversationId;
import com.jzb.chatbot.common.id.DeviceId;
import com.jzb.chatbot.device.dto.DeviceChatRequest;
import com.jzb.chatbot.device.dto.DeviceChatResponse;
import com.jzb.chatbot.hermes.HermesClient;
import com.jzb.chatbot.hermes.HermesRequest;
import lombok.RequiredArgsConstructor;
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
    public DeviceChatResponse chat(DeviceChatRequest request) {
        var hermesResponse = hermesClient.chat(new HermesRequest(
                new DeviceId(request.deviceId()),
                new ConversationId(request.conversationId()),
                request.message()
        ));
        return new DeviceChatResponse(hermesResponse.conversationId().value(), hermesResponse.text());
    }
}
