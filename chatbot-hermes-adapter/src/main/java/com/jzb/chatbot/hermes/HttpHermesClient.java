package com.jzb.chatbot.hermes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Hermes Responses API HTTP 客户端。
 * <p>
 * 负责把设备文本请求转发到 Hermes Agent，并提取最终助手文本。
 *
 * @author jiangzhibin
 * @since 2026-06-14 19:18:00
 */
@Primary
@Component
@RequiredArgsConstructor
public class HttpHermesClient implements HermesClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public HermesResponse chat(HermesRequest request, HermesClientConfig config) {
        try {
            var payload = buildPayload(request, config);
            var httpRequest = buildRequest(config, payload);
            var response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Hermes HTTP " + response.statusCode() + ": " + response.body());
            }
            return new HermesResponse(request.conversationId(), extractOutputText(response.body()));
        } catch (IOException exception) {
            throw new IllegalStateException("Hermes request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Hermes request interrupted", exception);
        }
    }

    private String buildPayload(HermesRequest request, HermesClientConfig config) throws IOException {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("model", config.model());
        payload.put("input", request.text());
        payload.put("store", true);
        payload.put("stream", false);
        payload.put("conversation", request.conversationId().value());
        return objectMapper.writeValueAsString(payload);
    }

    private HttpRequest buildRequest(HermesClientConfig config, String payload) {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(config.normalizedBaseUrl() + "/responses"))
                .timeout(config.requestTimeout())
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        var sessionKey = config.sanitizedSessionKey();
        if (!sessionKey.isBlank()) {
            builder.header("X-Hermes-Session-Key", sessionKey);
        }
        return builder.build();
    }

    private String extractOutputText(String body) throws IOException {
        var root = objectMapper.readTree(body);
        var output = root.path("output");
        if (!output.isArray()) {
            throw new IllegalStateException("Hermes responses missing 'output' array");
        }
        var builder = new StringBuilder();
        for (JsonNode item : output) {
            if (!"message".equals(item.path("type").asText()) || !"assistant".equals(item.path("role").asText())) {
                continue;
            }
            for (JsonNode chunk : item.path("content")) {
                if ("output_text".equals(chunk.path("type").asText()) && !chunk.path("text").isMissingNode()) {
                    builder.append(chunk.path("text").asText());
                }
            }
        }
        if (builder.isEmpty()) {
            throw new IllegalStateException("Hermes responses missing assistant output_text");
        }
        return builder.toString();
    }
}
