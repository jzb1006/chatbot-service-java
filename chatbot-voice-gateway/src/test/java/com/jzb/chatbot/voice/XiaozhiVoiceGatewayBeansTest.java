package com.jzb.chatbot.voice;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.jzb.chatbot.speech.TencentCloudStreamingTextToSpeechClient;
import com.jzb.chatbot.speech.TencentCloudTextToSpeechClient;
import com.jzb.chatbot.speech.TencentRealtimeSpeechToTextClient;
import com.jzb.chatbot.speech.TextToSpeechClient;
import com.jzb.chatbot.voice.mcp.XiaozhiMcpAdminAuth;
import com.jzb.chatbot.voice.protocol.XiaozhiAudioParams;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import com.jzb.chatbot.voice.protocol.XiaozhiServerEventFactory;
import com.jzb.chatbot.voice.tts.XiaozhiTtsRuntime;
import com.jzb.chatbot.voice.tts.XiaozhiVoiceProfileResolver;
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
            .withBean(XiaozhiMessageCodec.class)
            .withBean(XiaozhiServerEventFactory.class)
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
    void shouldUseDisabledStreamingTextToSpeechByDefault() {
        contextRunner.run(context -> {
            var client = context.getBean(StreamingTextToSpeechClient.class);
            var runtime = context.getBean(XiaozhiTtsRuntime.class);

            assertThat(client).isInstanceOf(DisabledStreamingTextToSpeechClient.class);
            assertThat(runtime.streamingEnabled()).isFalse();
        });
    }

    @Test
    void shouldCreateXiaozhiTtsRuntimeWithLightweightContext() {
        contextRunner.run(context -> assertThat(context.getBean(XiaozhiTtsRuntime.class)).isNotNull());
    }

    @Test
    void shouldUseFakeSpeechToTextByDefault() {
        contextRunner.run(context -> {
            var client = context.getBean(SpeechToTextClient.class);

            assertThat(client).isInstanceOf(FakeSpeechToTextClient.class);
        });
    }

    @Test
    void shouldUseFakeStreamingSpeechToTextByDefault() {
        contextRunner.run(context -> {
            var clients = context.getBeansOfType(StreamingSpeechToTextClient.class);

            assertThat(clients).hasSize(1);
            assertThat(clients.values()).singleElement().isInstanceOf(FakeStreamingSpeechToTextClient.class);
        });
    }

    @Test
    void shouldCreateStreamingXiaozhiAsrMode() {
        contextRunner
                .withPropertyValues("chatbot.voice.asr.mode=streaming")
                .run(context -> {
                    var mode = context.getBean(XiaozhiAsrMode.class);

                    assertThat(mode.streaming()).isTrue();
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
    void shouldCreateConfiguredXiaozhiVoiceProfileResolver() {
        contextRunner
                .withPropertyValues(
                        "chatbot.voice.default-voice-id=xiaozhi",
                        "chatbot.voice.tts.default-speed=1.2",
                        "chatbot.voice.tts.default-pitch=0.8"
                )
                .run(context -> {
                    var resolver = context.getBean(XiaozhiVoiceProfileResolver.class);

                    var profile = resolver.resolve("device-1");

                    assertThat(profile.voiceId()).isEqualTo(new VoiceId("xiaozhi"));
                    assertThat(profile.speed()).isEqualTo(1.2);
                    assertThat(profile.pitch()).isEqualTo(0.8);
                });
    }

    @Test
    void shouldFailFastWhenDefaultVoiceSpeedIsInvalid() {
        contextRunner
                .withPropertyValues("chatbot.voice.tts.default-speed=0")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("speed must be positive"));
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
    void shouldCreateStreamingTencentTtsClientWhenProviderIsTencentStreaming() {
        contextRunner
                .withPropertyValues(
                        "chatbot.voice.tts.provider=tencent-streaming",
                        "chatbot.voice.tts.tencent.app-id=1300000000",
                        "chatbot.voice.tts.tencent.secret-id=secret-id",
                        "chatbot.voice.tts.tencent.secret-key=secret-key",
                        "chatbot.voice.tts.tencent.voice-type=101001",
                        "chatbot.voice.default-voice-id=101001"
                )
                .run(context -> assertThat(context.getBean(StreamingTextToSpeechClient.class))
                        .isInstanceOf(TencentCloudStreamingTextToSpeechClient.class));
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
    void shouldUseTencentRealtimeSpeechToTextWhenStreamingTencentConfigured() {
        contextRunner
                .withPropertyValues(
                        "chatbot.voice.asr.mode=streaming",
                        "chatbot.voice.asr.provider=tencent",
                        "chatbot.voice.asr.tencent.app-id=app-id",
                        "chatbot.voice.asr.tencent.secret-id=secret-id",
                        "chatbot.voice.asr.tencent.secret-key=secret-key"
                )
                .run(context -> {
                    var client = context.getBean(StreamingSpeechToTextClient.class);

                    assertThat(client).isInstanceOf(TencentRealtimeSpeechToTextClient.class);
                });
    }

    @Test
    void shouldFailFastWhenTencentSpeechToTextSecretIsMissing() {
        contextRunner
                .withPropertyValues("chatbot.voice.asr.provider=tencent")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Tencent Cloud ASR requires secret-id and secret-key"));
    }

    @Test
    void shouldFailFastWhenTencentRealtimeSpeechToTextCredentialIsMissing() {
        contextRunner
                .withPropertyValues(
                        "chatbot.voice.asr.mode=streaming",
                        "chatbot.voice.asr.provider=tencent"
                )
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Tencent realtime ASR requires app-id, secret-id and secret-key"));
    }

    @Test
    void shouldFailFastWhenXiaozhiAsrModeIsUnsupported() {
        contextRunner
                .withPropertyValues("chatbot.voice.asr.mode=unsupported")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Unsupported Xiaozhi ASR mode: unsupported"));
    }

    @Test
    void shouldRequireMcpAdminTokenWhenAuthRequiredIsTrue() {
        var auth = new XiaozhiMcpAdminAuth("", "", true);

        assertThat(auth.required()).isTrue();
        assertThat(auth.matches("")).isFalse();
    }

    @Test
    void shouldMatchHermesBearerToken() {
        var auth = new XiaozhiMcpAdminAuth("admin-token", "hermes-token", true);

        assertThat(auth.matchesHermes("Bearer hermes-token")).isTrue();
        assertThat(auth.matchesHermes("Bearer bad")).isFalse();
    }
}
