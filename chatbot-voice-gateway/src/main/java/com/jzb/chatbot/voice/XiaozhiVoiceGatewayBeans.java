package com.jzb.chatbot.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jzb.chatbot.common.id.VoiceId;
import com.jzb.chatbot.hermes.HermesClientConfig;
import com.jzb.chatbot.speech.DisabledStreamingTextToSpeechClient;
import com.jzb.chatbot.speech.FakeSpeechToTextClient;
import com.jzb.chatbot.speech.FakeStreamingSpeechToTextClient;
import com.jzb.chatbot.speech.FakeTextToSpeechClient;
import com.jzb.chatbot.speech.SpeechToTextClient;
import com.jzb.chatbot.speech.StreamingSpeechToTextClient;
import com.jzb.chatbot.speech.StreamingTextToSpeechClient;
import com.jzb.chatbot.speech.TencentCloudSpeechToTextClient;
import com.jzb.chatbot.speech.TencentCloudSpeechToTextConfig;
import com.jzb.chatbot.speech.TencentCloudStreamingTextToSpeechClient;
import com.jzb.chatbot.speech.TencentCloudTextToSpeechClient;
import com.jzb.chatbot.speech.TencentCloudTextToSpeechConfig;
import com.jzb.chatbot.speech.TencentRealtimeSpeechToTextClient;
import com.jzb.chatbot.speech.TencentRealtimeSpeechToTextConfig;
import com.jzb.chatbot.speech.TencentStreamingTextToSpeechConfig;
import com.jzb.chatbot.speech.TencentStreamingTtsSigner;
import com.jzb.chatbot.speech.TextToSpeechClient;
import com.jzb.chatbot.voice.mcp.XiaozhiMcpAdminAuth;
import com.jzb.chatbot.voice.protocol.XiaozhiAudioParams;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import com.jzb.chatbot.voice.protocol.XiaozhiServerEventFactory;
import com.jzb.chatbot.voice.tts.XiaozhiTtsRuntime;
import com.jzb.chatbot.voice.tts.XiaozhiVoiceProfileResolver;
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
    XiaozhiVoiceTokenAuth xiaozhiVoiceTokenAuth(
            @Value("${chatbot.voice.websocket.token:}") String expectedToken
    ) {
        return new XiaozhiVoiceTokenAuth(expectedToken);
    }

    @Bean
    XiaozhiMcpAdminAuth xiaozhiMcpAdminAuth(
            @Value("${chatbot.voice.mcp.admin-token:}") String adminToken,
            @Value("${chatbot.voice.mcp.hermes-token:}") String hermesToken,
            @Value("${chatbot.voice.mcp.auth-required:false}") boolean authRequired
    ) {
        return new XiaozhiMcpAdminAuth(adminToken, hermesToken, authRequired);
    }

    @Bean
    XiaozhiAudioParams xiaozhiAudioParams(
            @Value("${chatbot.voice.audio.format:opus}") String format,
            @Value("${chatbot.voice.audio.sample-rate:16000}") int sampleRate,
            @Value("${chatbot.voice.audio.channels:1}") int channels,
            @Value("${chatbot.voice.audio.frame-duration:60}") int frameDuration
    ) {
        return new XiaozhiAudioParams(format, sampleRate, channels, frameDuration);
    }

    @Bean
    XiaozhiAsrMode xiaozhiAsrMode(@Value("${chatbot.voice.asr.mode:sentence}") String mode) {
        return new XiaozhiAsrMode(mode);
    }

    @Bean
    XiaozhiVoiceProfileResolver xiaozhiVoiceProfileResolver(
            @Value("${chatbot.voice.default-voice-id:default}") String defaultVoiceId,
            @Value("${chatbot.voice.tts.default-speed:1.0}") double defaultSpeed,
            @Value("${chatbot.voice.tts.default-pitch:1.0}") double defaultPitch
    ) {
        return new XiaozhiVoiceProfileResolver(new VoiceId(defaultVoiceId), defaultSpeed, defaultPitch);
    }

    @Bean
    SpeechToTextClient speechToTextClient(
            XiaozhiAsrMode mode,
            @Value("${chatbot.voice.asr.provider:fake}") String provider,
            @Value("${chatbot.voice.asr.tencent.secret-id:}") String secretId,
            @Value("${chatbot.voice.asr.tencent.secret-key:}") String secretKey,
            @Value("${chatbot.voice.asr.tencent.region:ap-guangzhou}") String region,
            @Value("${chatbot.voice.asr.tencent.endpoint:asr.tencentcloudapi.com}") String endpoint,
            @Value("${chatbot.voice.asr.tencent.engine-model-type:16k_zh}") String engineModelType,
            @Value("${chatbot.voice.asr.tencent.voice-format:pcm}") String voiceFormat,
            @Value("${chatbot.voice.asr.tencent.sample-rate:16000}") int sampleRate,
            @Value("${chatbot.voice.asr.tencent.timeout-seconds:15}") int timeoutSeconds
    ) {
        if (mode.streaming() || !"tencent".equalsIgnoreCase(provider)) {
            return new FakeSpeechToTextClient();
        }
        if (secretId.isBlank() || secretKey.isBlank()) {
            throw new IllegalStateException("Tencent Cloud ASR requires secret-id and secret-key");
        }
        return new TencentCloudSpeechToTextClient(new TencentCloudSpeechToTextConfig(
                secretId,
                secretKey,
                region,
                endpoint,
                engineModelType,
                voiceFormat,
                sampleRate,
                Duration.ofSeconds(timeoutSeconds)
        ));
    }

    @Bean
    StreamingSpeechToTextClient streamingSpeechToTextClient(
            XiaozhiAsrMode mode,
            @Value("${chatbot.voice.asr.provider:fake}") String provider,
            @Value("${chatbot.voice.asr.tencent.app-id:}") String appId,
            @Value("${chatbot.voice.asr.tencent.secret-id:}") String secretId,
            @Value("${chatbot.voice.asr.tencent.secret-key:}") String secretKey,
            @Value("${chatbot.voice.asr.tencent.engine-model-type:16k_zh}") String engineModelType,
            @Value("${chatbot.voice.asr.tencent.sample-rate:16000}") int sampleRate,
            @Value("${chatbot.voice.asr.tencent.chunk-timeout-millis:100}") long chunkTimeoutMillis,
            @Value("${chatbot.voice.asr.tencent.recognition-timeout-seconds:90}") long recognitionTimeoutSeconds
    ) {
        if (!mode.streaming() || !"tencent".equalsIgnoreCase(provider)) {
            return new FakeStreamingSpeechToTextClient();
        }
        if (appId.isBlank() || secretId.isBlank() || secretKey.isBlank()) {
            throw new IllegalStateException("Tencent realtime ASR requires app-id, secret-id and secret-key");
        }
        return new TencentRealtimeSpeechToTextClient(new TencentRealtimeSpeechToTextConfig(
                appId,
                secretId,
                secretKey,
                engineModelType,
                sampleRate,
                Duration.ofMillis(chunkTimeoutMillis),
                Duration.ofSeconds(recognitionTimeoutSeconds)
        ));
    }

    @Bean
    TextToSpeechClient textToSpeechClient(
            @Value("${chatbot.voice.tts.provider:fake}") String provider,
            @Value("${chatbot.voice.tts.tencent.secret-id:}") String secretId,
            @Value("${chatbot.voice.tts.tencent.secret-key:}") String secretKey,
            @Value("${chatbot.voice.tts.tencent.region:ap-guangzhou}") String region,
            @Value("${chatbot.voice.tts.tencent.endpoint:tts.tencentcloudapi.com}") String endpoint,
            @Value("${chatbot.voice.tts.tencent.voice-type:603004}") String voiceType,
            @Value("${chatbot.voice.tts.tencent.codec:pcm}") String codec,
            @Value("${chatbot.voice.tts.tencent.sample-rate:16000}") int sampleRate,
            @Value("${chatbot.voice.tts.tencent.timeout-seconds:15}") int timeoutSeconds
    ) {
        if (!"tencent".equalsIgnoreCase(provider) && !"tencent-streaming".equalsIgnoreCase(provider)) {
            return new FakeTextToSpeechClient();
        }
        if (secretId.isBlank() || secretKey.isBlank()) {
            throw new IllegalStateException("Tencent Cloud TTS requires secret-id and secret-key");
        }
        return new TencentCloudTextToSpeechClient(new TencentCloudTextToSpeechConfig(
                secretId,
                secretKey,
                region,
                endpoint,
                voiceType,
                codec,
                sampleRate,
                Duration.ofSeconds(timeoutSeconds)
        ));
    }

    @Bean
    StreamingTextToSpeechClient streamingTextToSpeechClient(
            @Value("${chatbot.voice.tts.provider:fake}") String provider,
            @Value("${chatbot.voice.tts.tencent.app-id:}") String appId,
            @Value("${chatbot.voice.tts.tencent.secret-id:}") String secretId,
            @Value("${chatbot.voice.tts.tencent.secret-key:}") String secretKey,
            @Value("${chatbot.voice.tts.tencent.voice-type:603004}") String voiceType,
            @Value("${chatbot.voice.tts.tencent.codec:pcm}") String codec,
            @Value("${chatbot.voice.tts.tencent.sample-rate:16000}") int sampleRate,
            @Value("${chatbot.voice.tts.default-speed:1.0}") double defaultSpeed,
            @Value("${chatbot.voice.tts.tencent.volume:0.0}") double volume,
            @Value("${chatbot.voice.tts.tencent.stream-timeout-seconds:30}") int timeoutSeconds
    ) {
        if (!"tencent-streaming".equalsIgnoreCase(provider)) {
            return new DisabledStreamingTextToSpeechClient();
        }
        if (appId.isBlank() || secretId.isBlank() || secretKey.isBlank()) {
            throw new IllegalStateException("Tencent streaming TTS requires app-id, secret-id and secret-key");
        }
        return new TencentCloudStreamingTextToSpeechClient(new TencentStreamingTextToSpeechConfig(
                Integer.parseInt(appId),
                secretId,
                secretKey,
                voiceType,
                codec,
                sampleRate,
                TencentStreamingTtsSigner.toTencentSpeed(defaultSpeed),
                volume,
                Duration.ofSeconds(timeoutSeconds)
        ));
    }

    @Bean
    XiaozhiTtsRuntime xiaozhiTtsRuntime(
            TextToSpeechClient textToSpeechClient,
            StreamingTextToSpeechClient streamingTextToSpeechClient,
            XiaozhiMessageCodec codec,
            XiaozhiServerEventFactory eventFactory
    ) {
        return new XiaozhiTtsRuntime(textToSpeechClient, streamingTextToSpeechClient, codec, eventFactory);
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
