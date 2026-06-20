package com.jzb.chatbot.hermes;

import static org.assertj.core.api.Assertions.assertThat;

import com.jzb.chatbot.common.id.ConversationId;
import com.jzb.chatbot.common.id.DeviceId;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class FakeHermesClientTest {

    @Test
    void shouldEchoTextWithConversationId() {
        var client = new FakeHermesClient();
        var response = client.chat(
                new HermesRequest(
                        new DeviceId("device-1"),
                        new ConversationId("conv-1"),
                        "ping"
                ),
                new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", java.time.Duration.ofSeconds(1), "owner")
        );

        assertThat(response.conversationId()).isEqualTo(new ConversationId("conv-1"));
        assertThat(response.text()).isEqualTo("pong");
    }

    @Test
    void shouldReturnSseEventsForStreamChat() {
        var client = new FakeHermesClient();

        var events = client.streamChat(
                new HermesRequest(
                        new DeviceId("device-1"),
                        new ConversationId("conv-1"),
                        "ping"
                ),
                new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner")
        ).toList();

        assertThat(String.join("", events)).isEqualTo("""
                event: message
                data: {"answer":"pong"}

                event: done
                data: {}

                """);
    }

    @Test
    void shouldUseAsrTextWhenRequestContainsXiaozhiSkillInstructions() {
        var client = new FakeHermesClient();

        var events = client.streamChat(
                new HermesRequest(
                        new DeviceId("device-1"),
                        new ConversationId("conv-1"),
                        """
                                你是小智设备的 Hermes agent。

                                ASR: ping
                                """
                ),
                new HermesClientConfig("http://127.0.0.1:8642/v1", "hermes-agent", "key", Duration.ofSeconds(1), "owner")
        ).toList();

        assertThat(String.join("", events)).contains("{\"answer\":\"pong\"}");
    }
}
