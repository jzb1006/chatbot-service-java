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
}
