package com.jzb.chatbot.device.ota;

import com.fasterxml.jackson.databind.JsonNode;
import com.jzb.chatbot.device.InvalidDeviceChatRequestException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小智 OTA 控制器。
 * <p>
 * 暴露固件 OTA 检查和设备激活入口。
 *
 * @author jiangzhibin
 * @since 2026-06-16 13:32:00
 */
@RestController
@RequiredArgsConstructor
public class XiaozhiOtaController {

    private final XiaozhiOtaService otaService;

    /**
     * 检查 OTA 配置。
     *
     * @param deviceId 设备 ID
     * @param clientId 客户端 ID
     * @param serialNumber 设备序列号
     * @param activationVersion 激活协议版本
     * @param userAgent User-Agent
     * @param body 请求体
     * @return OTA 响应
     */
    @PostMapping("/api/ota/check")
    public JsonNode check(
            @RequestHeader(value = "Device-Id", defaultValue = "") String deviceId,
            @RequestHeader(value = "Client-Id", defaultValue = "") String clientId,
            @RequestHeader(value = "Serial-Number", defaultValue = "") String serialNumber,
            @RequestHeader(value = "Activation-Version", defaultValue = "1") String activationVersion,
            @RequestHeader(value = "User-Agent", defaultValue = "") String userAgent,
            @RequestBody(required = false) JsonNode body
    ) {
        var identity = new OtaDeviceIdentity(deviceId, clientId, serialNumber, activationVersion, userAgent);
        return otaService.check(identity, body);
    }

    /**
     * 激活 OTA 设备。
     *
     * @param deviceId 设备 ID
     * @param clientId 客户端 ID
     * @param serialNumber 设备序列号
     * @param payload 激活 payload
     * @return 激活结果
     */
    @PostMapping("/api/ota/check/activate")
    public ResponseEntity<?> activate(
            @RequestHeader(value = "Device-Id", defaultValue = "") String deviceId,
            @RequestHeader(value = "Client-Id", defaultValue = "") String clientId,
            @RequestHeader(value = "Serial-Number", defaultValue = "") String serialNumber,
            @RequestBody JsonNode payload
    ) {
        var identity = new OtaDeviceIdentity(deviceId, clientId, serialNumber, "", "");
        var status = otaService.activate(identity, payload);
        if (status == XiaozhiOtaService.ActivationStatus.PENDING) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "pending"));
        }
        if (status == XiaozhiOtaService.ActivationStatus.REJECTED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "activation rejected"));
        }
        return ResponseEntity.ok(Map.of("status", "activated"));
    }

    /**
     * 下载 OTA 固件文件。
     *
     * @param fileName 固件文件名
     * @return 固件资源
     */
    @GetMapping("/api/ota/firmware/{fileName:.+}")
    public ResponseEntity<?> firmware(@PathVariable String fileName) {
        requireSafeFileName(fileName);
        return otaService.firmware(fileName)
                .<ResponseEntity<?>>map(resource -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(resource))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "firmware not found")));
    }

    private void requireSafeFileName(String fileName) {
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            throw new InvalidDeviceChatRequestException(HttpStatus.BAD_REQUEST, "invalid firmware path");
        }
    }
}
