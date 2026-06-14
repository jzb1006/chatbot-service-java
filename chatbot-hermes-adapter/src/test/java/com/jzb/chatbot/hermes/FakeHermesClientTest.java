package com.jzb.chatbot.hermes;

import static org.assertj.core.api.Assertions.assertThat;

import com.jzb.chatbot.common.id.ConversationId;
import com.jzb.chatbot.common.id.DeviceId;
import org.junit.jupiter.api.Test;

class FakeHermesClientTest {

    @Test
    void shouldEchoTextWithConversationId() {
        var client = new FakeHermesClient();
        var response = client.chat(new HermesRequest(
                new DeviceId("device-1"),
                new ConversationId("conv-1"),
                "ping"
        ));

        assertThat(response.conversationId()).isEqualTo(new ConversationId("conv-1"));
        assertThat(response.text()).isEqualTo("pong");
    }
}
