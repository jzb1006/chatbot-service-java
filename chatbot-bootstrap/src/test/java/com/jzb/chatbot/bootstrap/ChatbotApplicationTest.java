package com.jzb.chatbot.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

@SpringBootTest(properties = "chatbot.voice.tts.provider=fake")
class ChatbotApplicationTest {

    @Autowired
    private Environment environment;

    @Test
    void contextLoads() {
    }

    @Test
    void shouldExposeEdgeTtsConfigAtVoiceTtsLevel() {
        assertThat(environment.getProperty("chatbot.voice.tts.provider")).isEqualTo("fake");
        assertThat(environment.getProperty("chatbot.voice.tts.edge.voice")).isEqualTo("zh-CN-XiaoxiaoNeural");
        assertThat(environment.getProperty("chatbot.voice.tts.edge.output-format"))
                .isEqualTo("audio-24khz-48kbitrate-mono-mp3");
        assertThat(environment.getProperty("chatbot.voice.asr.tencent.tts.provider")).isNull();
    }
}
