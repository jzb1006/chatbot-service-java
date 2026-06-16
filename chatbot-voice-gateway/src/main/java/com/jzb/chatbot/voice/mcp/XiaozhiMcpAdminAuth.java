package com.jzb.chatbot.voice.mcp;

/**
 * 小智 MCP 管理入口鉴权配置。
 * <p>
 * 同时支持运维 REST token 和 Hermes HTTP JSON-RPC token。
 *
 * @author jiangzhibin
 * @since 2026-06-16 13:44:00
 */
public record XiaozhiMcpAdminAuth(String adminToken, String hermesToken, boolean authRequired) {

    /**
     * 判断是否要求管理 token。
     *
     * @return true 表示需要鉴权
     */
    public boolean required() {
        return authRequired || adminToken != null && !adminToken.isBlank();
    }

    /**
     * 校验运维管理 token。
     *
     * @param actualToken 请求 token
     * @return true 表示匹配
     */
    public boolean matches(String actualToken) {
        return matchesRequired(adminToken, actualToken);
    }

    /**
     * 校验 Hermes Bearer token。
     *
     * @param authorizationHeader Authorization 请求头
     * @return true 表示匹配
     */
    public boolean matchesHermes(String authorizationHeader) {
        var token = authorizationHeader == null ? "" : authorizationHeader.replaceFirst("(?i)^Bearer\\s+", "");
        return matchesRequired(hermesToken, token);
    }

    private boolean matchesRequired(String expected, String actual) {
        if (!authRequired && (expected == null || expected.isBlank())) {
            return true;
        }
        if (expected == null || expected.isBlank()) {
            return false;
        }
        return expected.equals(actual);
    }
}
