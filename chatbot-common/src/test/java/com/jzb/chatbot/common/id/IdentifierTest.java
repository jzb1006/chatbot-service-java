package com.jzb.chatbot.common.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IdentifierTest {

    @Test
    void shouldRejectBlankDeviceId() {
        assertThatThrownBy(() -> new DeviceId(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deviceId");
    }

    @Test
    void shouldKeepConversationIdValue() {
        var conversationId = new ConversationId("conv-1");

        assertThat(conversationId.value()).isEqualTo("conv-1");
    }
}
