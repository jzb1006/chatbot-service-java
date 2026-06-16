package com.jzb.chatbot.device.ota;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 小智 OTA 固件路径过滤器。
 * <p>
 * 在 Spring MVC 路由前拒绝固件下载路径穿越请求，避免全局异常处理影响其他资源 404。
 *
 * @author jiangzhibin
 * @since 2026-06-16 14:10:00
 */
@Component
public class XiaozhiOtaFirmwarePathFilter extends OncePerRequestFilter {

    private static final String FIRMWARE_PATH_PREFIX = "/api/ota/firmware/";
    private static final String INVALID_FIRMWARE_PATH_RESPONSE = "{\"error\":\"invalid firmware path\"}";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (isUnsafeFirmwarePath(request)) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(INVALID_FIRMWARE_PATH_RESPONSE);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isUnsafeFirmwarePath(HttpServletRequest request) {
        var path = pathWithoutContext(request);
        if (!path.startsWith(FIRMWARE_PATH_PREFIX)) {
            return false;
        }
        var fileName = decode(path.substring(FIRMWARE_PATH_PREFIX.length()));
        return fileName.contains("..") || fileName.contains("/") || fileName.contains("\\");
    }

    private String pathWithoutContext(HttpServletRequest request) {
        var contextPath = request.getContextPath();
        var requestUri = request.getRequestURI();
        if (contextPath == null || contextPath.isBlank()) {
            return requestUri;
        }
        return requestUri.startsWith(contextPath) ? requestUri.substring(contextPath.length()) : requestUri;
    }

    private String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return value;
        }
    }
}
