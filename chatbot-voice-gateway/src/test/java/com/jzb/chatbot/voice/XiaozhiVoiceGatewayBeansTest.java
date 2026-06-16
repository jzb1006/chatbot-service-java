package com.jzb.chatbot.voice;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jzb.chatbot.hermes.HermesClientConfig;
import com.jzb.chatbot.speech.FakeSpeechToTextClient;
import com.jzb.chatbot.speech.FakeTextToSpeechClient;
import com.jzb.chatbot.speech.SpeechToTextClient;
import com.jzb.chatbot.speech.TencentCloudSpeechToTextClient;
import com.jzb.chatbot.speech.TencentCloudTextToSpeechClient;
import com.jzb.chatbot.speech.TextToSpeechClient;
import com.jzb.chatbot.voice.protocol.XiaozhiAudioParams;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class XiaozhiVoiceGatewayBeansTest {

    @TempDir
    private Path tempDir;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class)
            .withUserConfiguration(XiaozhiVoiceGatewayBeans.class);

    @Test
    void shouldReadHermesConfigFromLegacyConfigJson() throws Exception {
        var configPath = tempDir.resolve("llm_config.json");
        Files.writeString(configPath, """
                {
                  "base_url": "http://hermes:8642/v1",
                  "model": "hermes-agent",
                  "api_key": "real-hermes-key",
                  "session_key": "owner"
                }
                """);

        contextRunner
                .withPropertyValues(
                        "chatbot.device.config-path=" + configPath,
                        "chatbot.hermes.base-url=http://wrong-host:8642/v1",
                        "chatbot.hermes.model=wrong-model",
                        "chatbot.hermes.api-key=fake-key",
                        "chatbot.hermes.session-key=wrong-owner"
                )
                .run(context -> {
                    var config = context.getBean(HermesClientConfig.class);

                    assertThat(config.baseUrl()).isEqualTo("http://hermes:8642/v1");
                    assertThat(config.model()).isEqualTo("hermes-agent");
                    assertThat(config.apiKey()).isEqualTo("real-hermes-key");
                    assertThat(config.sessionKey()).isEqualTo("owner");
                });
    }

    @Test
    void shouldUseFakeTextToSpeechByDefault() {
        contextRunner.run(context -> {
            var client = context.getBean(TextToSpeechClient.class);

            assertThat(client).isInstanceOf(FakeTextToSpeechClient.class);
        });
    }

    @Test
    void shouldUseFakeSpeechToTextByDefault() {
        contextRunner.run(context -> {
            var client = context.getBean(SpeechToTextClient.class);

            assertThat(client).isInstanceOf(FakeSpeechToTextClient.class);
        });
    }

    @Test
    void shouldCreateConfiguredXiaozhiAudioParams() {
        contextRunner
                .withPropertyValues(
                        "chatbot.voice.audio.format=opus",
                        "chatbot.voice.audio.sample-rate=24000",
                        "chatbot.voice.audio.channels=1",
                        "chatbot.voice.audio.frame-duration=60"
                )
                .run(context -> {
                    var params = context.getBean(XiaozhiAudioParams.class);

                    assertThat(params.format()).isEqualTo("opus");
                    assertThat(params.sampleRate()).isEqualTo(24000);
                    assertThat(params.channels()).isEqualTo(1);
                    assertThat(params.frameDuration()).isEqualTo(60);
                });
    }

    @Test
    void shouldUseTencentTextToSpeechWhenConfigured() {
        contextRunner
                .withPropertyValues(
                        "chatbot.voice.tts.provider=tencent",
                        "chatbot.voice.tts.tencent.secret-id=secret-id",
                        "chatbot.voice.tts.tencent.secret-key=secret-key"
                )
                .run(context -> {
                    var client = context.getBean(TextToSpeechClient.class);

                    assertThat(client).isInstanceOf(TencentCloudTextToSpeechClient.class);
                });
    }

    @Test
    void shouldUseTencentSpeechToTextWhenConfigured() {
        contextRunner
                .withPropertyValues(
                        "chatbot.voice.asr.provider=tencent",
                        "chatbot.voice.asr.tencent.secret-id=secret-id",
                        "chatbot.voice.asr.tencent.secret-key=secret-key"
                )
                .run(context -> {
                    var client = context.getBean(SpeechToTextClient.class);

                    assertThat(client).isInstanceOf(TencentCloudSpeechToTextClient.class);
                });
    }

    @Test
    void shouldFailFastWhenTencentSpeechToTextSecretIsMissing() {
        contextRunner
                .withPropertyValues("chatbot.voice.asr.provider=tencent")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Tencent Cloud ASR requires secret-id and secret-key"));
    }
}
