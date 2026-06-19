package com.jzb.chatbot.voice.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class XiaozhiServerEventFactoryTest {

    private final XiaozhiServerEventFactory factory = new XiaozhiServerEventFactory(new ObjectMapper());

    @Test
    void shouldBuildTtsSentenceStartEvent() {
        var json = factory.ttsSentenceStart("s1", "pong");

        assertThat(json).contains("\"type\":\"tts\"");
        assertThat(json).contains("\"state\":\"sentence_start\"");
        assertThat(json).contains("\"text\":\"pong\"");
    }

    @Test
    void shouldBuildErrorEvent() {
        var json = factory.error("s1", "asr_empty", "未识别到语音");

        assertThat(json).contains("\"type\":\"error\"");
        assertThat(json).contains("\"code\":\"asr_empty\"");
        assertThat(json).contains("\"message\":\"未识别到语音\"");
    }

    @Test
    void shouldBuildMcpEvent() throws Exception {
        var payload = new ObjectMapper().readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"withUserTools":true}}
                """);

        var json = factory.mcp("s1", payload);

        assertThat(json).contains("\"session_id\":\"s1\"");
        assertThat(json).contains("\"type\":\"mcp\"");
        assertThat(json).contains("\"method\":\"tools/list\"");
    }

    @Test
    void shouldBuildMusicMediaStartEvent() {
        var json = factory.mediaStart("s1", "music", "晴天", "周杰伦");

        assertThat(json).contains("\"session_id\":\"s1\"");
        assertThat(json).contains("\"type\":\"media\"");
        assertThat(json).contains("\"state\":\"start\"");
        assertThat(json).contains("\"kind\":\"music\"");
        assertThat(json).contains("\"title\":\"晴天\"");
        assertThat(json).contains("\"artist\":\"周杰伦\"");
    }

    @Test
    void shouldBuildMusicMediaStopEvent() {
        var json = factory.mediaStop("s1", "music");

        assertThat(json).contains("\"session_id\":\"s1\"");
        assertThat(json).contains("\"type\":\"media\"");
        assertThat(json).contains("\"state\":\"stop\"");
        assertThat(json).contains("\"kind\":\"music\"");
    }
}
