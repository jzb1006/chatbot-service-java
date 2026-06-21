package com.jzb.chatbot.voice.hermes;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HermesAgentEventExtractorTest {

    @Test
    void shouldExtractReminderEventFromHermesSse() {
        var extractor = new HermesAgentEventExtractor();

        assertThat(extractor.accept("event: xiaozhi.agent_event\n")).isEmpty();

        assertThat(extractor.accept("""
                data: {"action":"create_reminder","message":"喝水","delay_seconds":60,"confirmation_text":"好的，一分钟后提醒你喝水。","due_text":"该喝水了，别忘了。"}

                """)).containsExactly(new HermesAgentEvent(
                "create_reminder",
                "喝水",
                60L,
                "好的，一分钟后提醒你喝水。",
                "该喝水了，别忘了。",
                null,
                null,
                null,
                0L,
                null
        ));
    }

    @Test
    void shouldExtractSessionEndEventFromHermesSse() {
        var extractor = new HermesAgentEventExtractor();

        var events = extractor.accept("""
                event: xiaozhi.agent_event
                data: {"action":"session_end","confirmation_text":"回头再聊","reason":"user_requested_exit"}

                """);

        assertThat(events).containsExactly(new HermesAgentEvent(
                "session_end",
                null,
                0L,
                "回头再聊",
                null,
                null,
                null,
                0L,
                "user_requested_exit"
        ));
    }

    @Test
    void shouldExtractMusicPlayEventFromHermesSse() {
        var extractor = new HermesAgentEventExtractor();

        var events = extractor.accept("""
                event: xiaozhi.agent_event
                data: {"action":"music_play","request_id":"music-20260621-001","title":"稻香","artist":"周杰伦","media_url":"https://example.com/daoxiang.mp3","source":"buguyy","confidence":0.93,"match_reason":"artist_title_exact","confirmation_text":"开始播放稻香"}

                """);

        assertThat(events).containsExactly(new HermesAgentEvent(
                "music_play",
                null,
                0L,
                "开始播放稻香",
                "https://example.com/daoxiang.mp3",
                "稻香",
                "周杰伦",
                0L,
                null,
                "music-20260621-001",
                "buguyy",
                0.93,
                "artist_title_exact"
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
                + "\"message\":\"喝水\",\"delay_seconds\":60,\"confirmation_text\":\"好的，一分钟后提醒你喝水。\","
                + "\"due_text\":\"该喝水了，别忘了。\"}\r\n\r")).isEmpty();

        assertThat(extractor.accept("\n")).containsExactly(new HermesAgentEvent(
                "create_reminder",
                "喝水",
                60L,
                "好的，一分钟后提醒你喝水。",
                "该喝水了，别忘了。",
                null,
                null,
                null,
                0L,
                null
        ));
    }
}
