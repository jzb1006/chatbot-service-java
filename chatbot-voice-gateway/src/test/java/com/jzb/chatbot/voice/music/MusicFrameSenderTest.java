package com.jzb.chatbot.voice.music;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jzb.chatbot.voice.TestWebSocketSession;
import com.jzb.chatbot.voice.XiaozhiVoiceSession;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;

class MusicFrameSenderTest {

    @Test
    void shouldSendBinaryOpusFramesFromPcm() throws Exception {
        var webSocketSession = new TestWebSocketSession("ws-session-1");
        var voiceSession = new XiaozhiVoiceSession("session-1");
        voiceSession.updateProtocolVersion(1);
        var sender = new MusicFrameSender(new XiaozhiMessageCodec(new ObjectMapper()));
        var pcm = new byte[16000 / 1000 * 60 * Short.BYTES];

        var sentFrames = sender.send(
                webSocketSession,
                voiceSession,
                new ByteArrayInputStream(pcm),
                () -> false,
                () -> false
        );

        assertThat(sentFrames).isGreaterThan(0);
        assertThat(webSocketSession.getSentMessages()).filteredOn(BinaryMessage.class::isInstance).isNotEmpty();
    }
}
