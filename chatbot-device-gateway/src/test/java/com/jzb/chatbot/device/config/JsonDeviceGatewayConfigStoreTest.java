package com.jzb.chatbot.device.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class JsonDeviceGatewayConfigStoreTest {

    @TempDir
    private Path tempDir;

    @Test
    void shouldReadLegacyConfigJson() throws Exception {
        var configPath = tempDir.resolve("llm_config.json");
        Files.writeString(configPath, """
                {
                  "base_url": "http://hermes:8642/v1",
                  "model": "hermes-agent",
                  "api_key": "secret-key",
                  "device_token": "device-token",
                  "max_prompt_chars": "1234",
                  "request_timeout": "45",
                  "session_key": "owner"
                }
                """);
        var store = new JsonDeviceGatewayConfigStore(new ObjectMapper());
        setConfigPath(store, configPath);

        var config = store.get();

        assertThat(config.baseUrl()).isEqualTo("http://hermes:8642/v1");
        assertThat(config.model()).isEqualTo("hermes-agent");
        assertThat(config.apiKey()).isEqualTo("secret-key");
        assertThat(config.deviceToken()).isEqualTo("device-token");
        assertThat(config.maxPromptChars()).isEqualTo(1234);
        assertThat(config.requestTimeout()).hasSeconds(45);
        assertThat(config.sessionKey()).isEqualTo("owner");
    }

    private void setConfigPath(JsonDeviceGatewayConfigStore store, Path configPath) throws Exception {
        Field field = JsonDeviceGatewayConfigStore.class.getDeclaredField("configPath");
        field.setAccessible(true);
        field.set(store, configPath);
    }
}
