package com.jzb.chatbot.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "chatbot.voice.tts.provider=fake")
class ChatbotApplicationTest {

    @Test
    void contextLoads() {
    }
}
