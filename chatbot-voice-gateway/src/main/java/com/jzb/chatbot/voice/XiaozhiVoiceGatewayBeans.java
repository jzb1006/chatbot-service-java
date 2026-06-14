package com.jzb.chatbot.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jzb.chatbot.hermes.HermesClientConfig;
import com.jzb.chatbot.speech.FakeSpeechToTextClient;
import com.jzb.chatbot.speech.FakeTextToSpeechClient;
import com.jzb.chatbot.speech.SpeechToTextClient;
import com.jzb.chatbot.speech.TextToSpeechClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 小智语音网关 Bean 配置。
 * <p>
 * 在真实 ASR/TTS 接入前，使用 Fake Provider 保持协议闭环可运行。
 *
 * @author jiangzhibin
 * @since 2026-06-14 20:54:00
 */
@Configuration
public class XiaozhiVoiceGatewayBeans {

    @Bean
    SpeechToTextClient speechToTextClient() {
        return new FakeSpeechToTextClient();
    }

    @Bean
    TextToSpeechClient textToSpeechClient() {
        return new FakeTextToSpeechClient();
    }

    @Bean
    HermesClientConfig voiceHermesClientConfig(
            @Value("${chatbot.hermes.base-url:http://hermes:8642/v1}") String baseUrl,
            @Value("${chatbot.hermes.model:hermes-agent}") String model,
            @Value("${chatbot.hermes.api-key:fake-key}") String apiKey,
            @Value("${chatbot.hermes.session-key:owner}") String sessionKey,
            @Value("${chatbot.device.config-path:/app/data/llm_config.json}") Path configPath,
            ObjectMapper objectMapper
    ) {
        if (!Files.exists(configPath)) {
            return new HermesClientConfig(baseUrl, model, apiKey, Duration.ofSeconds(120), sessionKey);
        }
        try {
            var root = objectMapper.readTree(configPath.toFile());
            return new HermesClientConfig(
                    text(root, "base_url", baseUrl),
                    text(root, "model", model),
                    text(root, "api_key", apiKey),
                    Duration.ofSeconds(120),
                    text(root, "session_key", sessionKey)
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read voice Hermes config: " + configPath, exception);
        }
    }

    private String text(JsonNode root, String fieldName, String defaultValue) {
        var value = root.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return defaultValue;
        }
        return value.asText(defaultValue);
    }
}
