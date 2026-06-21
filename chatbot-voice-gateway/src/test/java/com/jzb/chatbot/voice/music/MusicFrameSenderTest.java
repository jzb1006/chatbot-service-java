package com.jzb.chatbot.voice.music;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jzb.chatbot.speech.StreamingPcmToOpusEncoder;
import com.jzb.chatbot.voice.TestWebSocketSession;
import com.jzb.chatbot.voice.XiaozhiVoiceSession;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import java.io.ByteArrayInputStream;
import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusSignal;
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

    @Test
    void shouldUseMusicOpusProfile() {
        var options = StreamingPcmToOpusEncoder.Options.music16k();
        var sender = new MusicFrameSender(new XiaozhiMessageCodec(new ObjectMapper()), 16000, 60, options);

        assertThat(sender.sampleRate()).isEqualTo(16000);
        assertThat(sender.frameDurationMs()).isEqualTo(60);
        assertThat(sender.opusOptions().application()).isEqualTo(OpusApplication.OPUS_APPLICATION_AUDIO);
        assertThat(sender.opusOptions().signal()).isEqualTo(OpusSignal.OPUS_SIGNAL_MUSIC);
        assertThat(sender.opusOptions().bitrateBps()).isEqualTo(64000);
    }

    @Test
    void shouldRejectUnsupportedOpusSampleRate() {
        assertThatThrownBy(() -> new MusicFrameSender(
                new XiaozhiMessageCodec(new ObjectMapper()),
                44100,
                20,
                StreamingPcmToOpusEncoder.Options.music16k()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleRate must be one of");
    }
}
