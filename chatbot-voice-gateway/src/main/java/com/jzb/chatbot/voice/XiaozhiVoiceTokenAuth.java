package com.jzb.chatbot.voice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 小智 WebSocket token 鉴权器。
 * <p>
 * 负责归一化固件上传的 Authorization 头，避免业务日志和会话流程直接处理 token 原文。
 *
 * @author jiangzhibin
 * @since 2026-06-16 01:24:00
 */
public record XiaozhiVoiceTokenAuth(String expectedToken) {

    private static final String BEARER_PREFIX = "Bearer ";

    public XiaozhiVoiceTokenAuth {
        expectedToken = expectedToken == null ? "" : expectedToken.trim();
    }

    /**
     * 判断当前请求是否通过 token 鉴权。
     *
     * @param authorization Authorization 头
     * @return true 表示鉴权通过
     */
    public boolean matches(String authorization) {
        if (expectedToken == null || expectedToken.isBlank()) {
            return true;
        }
        var actualToken = normalize(authorization);
        return MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                actualToken.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * 判断是否启用强制 token 鉴权。
     *
     * @return true 表示配置了期望 token
     */
    public boolean required() {
        return !expectedToken.isBlank();
    }

    private String normalize(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return "";
        }
        var value = authorization.trim();
        if (value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return value.substring(BEARER_PREFIX.length()).trim();
        }
        return value;
    }
}
