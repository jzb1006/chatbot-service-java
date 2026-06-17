package com.jzb.chatbot.voice.hermes;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HermesAgentEventExtractorTest {

    @Test
    void shouldExtractReminderEventFromHermesSse() {
        var extractor = new HermesAgentEventExtractor();

        assertThat(extractor.accept("event: xiaozhi.agent_event\n")).isEmpty();

        assertThat(extractor.accept("""
                data: {"action":"create_reminder","message":"喝水","delay_seconds":60,"confirmation_text":"1分钟后提醒你喝水"}

                """)).containsExactly(new HermesAgentEvent(
                "create_reminder",
                "喝水",
                60L,
                "1分钟后提醒你喝水"
        ));
    }

    @Test
    void shouldIgnoreEmptyInvalidAndUnrelatedChunks() {
        var extractor = new HermesAgentEventExtractor();

        assertThat(extractor.accept(null)).isEmpty();
        assertThat(extractor.accept("")).isEmpty();
        assertThat(extractor.accept("event: xiaozhi.agent_event\n\n")).isEmpty();
        assertThat(extractor.accept("event: response.output_text.delta\ndata: {\"delta\":\"你好\"}\n\n")).isEmpty();
        assertThat(extractor.accept("event: xiaozhi.agent_event\ndata: {broken-json}\n\n")).isEmpty();
    }

    @Test
    void shouldExtractEventWhenCrLfBoundaryIsSplitAcrossChunks() {
        var extractor = new HermesAgentEventExtractor();

        assertThat(extractor.accept("event: xiaozhi.agent_event\r\ndata: {\"action\":\"create_reminder\","
                + "\"message\":\"喝水\",\"delay_seconds\":60,\"confirmation_text\":\"1分钟后提醒你喝水\"}\r\n\r")).isEmpty();

        assertThat(extractor.accept("\n")).containsExactly(new HermesAgentEvent(
                "create_reminder",
                "喝水",
                60L,
                "1分钟后提醒你喝水"
        ));
    }
}
