package com.jzb.chatbot.voice.sessionend;

import static org.assertj.core.api.Assertions.assertThat;

import com.jzb.chatbot.voice.hermes.HermesAgentEvent;
import org.junit.jupiter.api.Test;

class XiaozhiSessionEndActionTest {

    private final XiaozhiSessionEndProperties properties =
            new XiaozhiSessionEndProperties(true, "回头再聊", 1000, "session ended");

    @Test
    void shouldCreateActionFromSessionEndEvent() {
        var event = new HermesAgentEvent(
                "session_end", null, 0, "下次再聊", null, null, null, 0, "user_requested_exit"
        );

        var action = XiaozhiSessionEndAction.from(event, properties);

        assertThat(action).isNotNull();
        assertThat(action.confirmationText()).isEqualTo("下次再聊");
        assertThat(action.reason()).isEqualTo("user_requested_exit");
    }

    @Test
    void shouldUseDefaultConfirmationWhenEventTextIsBlank() {
        var event = new HermesAgentEvent(
                "session_end", null, 0, " ", null, null, null, 0, null
        );

        var action = XiaozhiSessionEndAction.from(event, properties);

        assertThat(action).isNotNull();
        assertThat(action.confirmationText()).isEqualTo("回头再聊");
        assertThat(action.reason()).isEqualTo("session_end");
    }

    @Test
    void shouldIgnoreWhenDisabledOrNotSessionEnd() {
        assertThat(XiaozhiSessionEndAction.from(
                new HermesAgentEvent("music_stop", null, 0, null, null, null, null, 0, null),
                properties
        )).isNull();

        assertThat(XiaozhiSessionEndAction.from(
                new HermesAgentEvent("session_end", null, 0, "回头再聊", null, null, null, 0, null),
                new XiaozhiSessionEndProperties(false, "回头再聊", 1000, "session ended")
        )).isNull();
    }
}
