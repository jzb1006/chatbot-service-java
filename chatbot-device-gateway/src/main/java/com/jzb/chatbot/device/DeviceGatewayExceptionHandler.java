package com.jzb.chatbot.device;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 设备网关异常处理器。
 * <p>
 * 将请求校验和 Hermes 调用异常转换为旧版设备网关兼容的 JSON 错误。
 *
 * @author jiangzhibin
 * @since 2026-06-14 19:25:00
 */
@RestControllerAdvice(assignableTypes = DeviceChatController.class)
public class DeviceGatewayExceptionHandler {

    /**
     * 处理设备请求异常。
     *
     * @param exception 设备请求异常
     * @return JSON 错误响应
     */
    @ExceptionHandler(InvalidDeviceChatRequestException.class)
    public ResponseEntity<Map<String, String>> handleInvalidDeviceChatRequest(InvalidDeviceChatRequestException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of("error", exception.getMessage()));
    }

    /**
     * 处理运行时异常。
     *
     * @param exception 运行时异常
     * @return JSON 错误响应
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntimeException(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", exception.getMessage()));
    }
}
