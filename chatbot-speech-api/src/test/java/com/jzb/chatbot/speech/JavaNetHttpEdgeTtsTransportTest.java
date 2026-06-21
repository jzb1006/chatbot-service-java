package com.jzb.chatbot.speech;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;

class JavaNetHttpEdgeTtsTransportTest {

    @Test
    void shouldUseEnglishTimestampInSpeechConfigMessage() throws Exception {
        var transport = new JavaNetHttpEdgeTtsTransport(HttpClient.newHttpClient());
        var speechConfig = speechConfig(transport);

        assertThat(speechConfig).containsPattern("X-Timestamp:(Mon|Tue|Wed|Thu|Fri|Sat|Sun) ");
        assertThat(speechConfig).doesNotContain("周");
    }

    private String speechConfig(JavaNetHttpEdgeTtsTransport transport) throws Exception {
        Method method = JavaNetHttpEdgeTtsTransport.class.getDeclaredMethod("speechConfig", String.class);
        method.setAccessible(true);
        return (String) method.invoke(transport, "raw-16khz-16bit-mono-pcm");
    }
}
