package com.jzb.chatbot.voice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class XiaozhiHermesAgentEventTextFilterTest {

    @Test
    void shouldExtractEmbeddedAgentEventAndKeepPlainText() {
        var filter = new XiaozhiHermesAgentEventTextFilter();

        var result = filter.accept("""
                好的。
                event: xiaozhi.agent_event
                data: {"action":"music_play","title":"晴天","artist":"周杰伦","media_url":"https://example.com/qingtian.mp3","confirmation_text":"开始播放晴天"}

                """);

        assertThat(result.text()).isEqualTo("好的。\n");
        assertThat(result.events()).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo("music_play");
            assertThat(event.title()).isEqualTo("晴天");
            assertThat(event.artist()).isEqualTo("周杰伦");
            assertThat(event.mediaUrl()).isEqualTo("https://example.com/qingtian.mp3");
        });
    }

    @Test
    void shouldHoldPartialMarkerAcrossTextDeltas() {
        var filter = new XiaozhiHermesAgentEventTextFilter();

        assertThat(filter.accept("event: xiaozhi.").text()).isEmpty();
        assertThat(filter.accept("""
                agent_event
                data: {"action":"music_play","title":"晴天","artist":"周杰伦","media_url":"https://example.com/qingtian.mp3"}

                """).events()).singleElement().satisfies(event ->
                assertThat(event.title()).isEqualTo("晴天"));
        assertThat(filter.flush().text()).isEmpty();
    }

    @Test
    void shouldHoldPartialAgentEventBlockAcrossTextDeltas() {
        var filter = new XiaozhiHermesAgentEventTextFilter();

        assertThat(filter.accept("\n\nevent").text()).isEqualTo("\n\n");
        assertThat(filter.accept(": xiaozhi.agent_event\ndata: {\"").text()).isEmpty();
        assertThat(filter.accept("action\":\"music_play\",\"title\":\"晴天\",\"artist\":\"").text()).isEmpty();
        assertThat(filter.accept("周杰伦\",\"media_url\":\"https://example.com/qingtian.mp3\"").text()).isEmpty();
        var result = filter.accept(",\"confirmation_text\":\"开始播放晴天\"}\n\n");

        assertThat(result.text()).isEmpty();
        assertThat(result.events()).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo("music_play");
            assertThat(event.title()).isEqualTo("晴天");
            assertThat(event.artist()).isEqualTo("周杰伦");
            assertThat(event.mediaUrl()).isEqualTo("https://example.com/qingtian.mp3");
            assertThat(event.confirmationText()).isEqualTo("开始播放晴天");
        });
        assertThat(filter.flush().text()).isEmpty();
    }

    @Test
    void shouldExtractAgentEventWithoutTrailingBlankLineOnFlush() {
        var filter = new XiaozhiHermesAgentEventTextFilter();

        assertThat(filter.accept("\n\nevent").text()).isEqualTo("\n\n");
        assertThat(filter.accept(": xiaozhi.agent_event\ndata: {\"action\":\"music_play\",\"title\":\"晴天\",\"artist\":\"").text()).isEmpty();
        assertThat(filter.accept("周杰伦\",\"media_url\":\"https://example.com/qingtian.mp3\",\"confirmation_text\":\"找到周杰伦的晴天，现在播放。\"}").text()).isEmpty();
        var result = filter.flush();

        assertThat(result.text()).isEmpty();
        assertThat(result.events()).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo("music_play");
            assertThat(event.title()).isEqualTo("晴天");
            assertThat(event.artist()).isEqualTo("周杰伦");
            assertThat(event.mediaUrl()).isEqualTo("https://example.com/qingtian.mp3");
            assertThat(event.confirmationText()).isEqualTo("找到周杰伦的晴天，现在播放。");
        });
    }
}
