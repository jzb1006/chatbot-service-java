package com.jzb.chatbot.voice.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class XiaozhiMessageCodecTest {

    private final XiaozhiMessageCodec codec = new XiaozhiMessageCodec(new ObjectMapper());

    @Test
    void shouldBuildServerHello() throws Exception {
        var json = codec.encodeServerHello("audio-1", "conv-1");

        assertThat(json).contains("\"type\":\"hello\"");
        assertThat(json).contains("\"transport\":\"websocket\"");
        assertThat(json).contains("\"session_id\":\"audio-1\"");
        assertThat(json).contains("\"conversation_id\":\"conv-1\"");
        assertThat(json).contains("\"format\":\"opus\"");
    }

    @Test
    void shouldParseListenStart() throws Exception {
        var message = codec.decodeText("{\"type\":\"listen\",\"state\":\"start\",\"session_id\":\"s1\"}");

        assertThat(message.type()).isEqualTo("listen");
        assertThat(message.state()).isEqualTo("start");
        assertThat(message.sessionId()).isEqualTo("s1");
    }
}
