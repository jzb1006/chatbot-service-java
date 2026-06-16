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
}
