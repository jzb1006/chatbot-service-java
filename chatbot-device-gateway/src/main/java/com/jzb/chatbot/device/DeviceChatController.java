package com.jzb.chatbot.device;

import com.jzb.chatbot.device.dto.DeviceChatRequest;
import com.jzb.chatbot.device.dto.DeviceChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

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

    /**
     * 处理设备文本聊天。
     *
     * @param request 设备聊天请求
     * @return 设备聊天响应
     */
    @PostMapping("/api/chat")
    public DeviceChatResponse chat(@RequestBody DeviceChatRequest request) {
        return chatService.chat(request);
    }
}
