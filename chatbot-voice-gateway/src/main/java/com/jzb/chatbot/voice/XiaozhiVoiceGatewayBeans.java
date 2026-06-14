package com.jzb.chatbot.voice;

import com.jzb.chatbot.hermes.HermesClientConfig;
import com.jzb.chatbot.speech.FakeSpeechToTextClient;
import com.jzb.chatbot.speech.FakeTextToSpeechClient;
import com.jzb.chatbot.speech.SpeechToTextClient;
import com.jzb.chatbot.speech.TextToSpeechClient;
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
            @Value("${chatbot.hermes.session-key:owner}") String sessionKey
    ) {
        return new HermesClientConfig(baseUrl, model, apiKey, Duration.ofSeconds(120), sessionKey);
    }
}
