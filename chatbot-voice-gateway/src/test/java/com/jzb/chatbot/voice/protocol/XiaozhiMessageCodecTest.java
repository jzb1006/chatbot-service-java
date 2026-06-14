package com.jzb.chatbot.voice.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class XiaozhiMessageCodecTest {

    private final XiaozhiMessageCodec codec = new XiaozhiMessageCodec(new ObjectMapper());

    @Test
    void shouldBuildServerHello() throws Exception {
        var json = codec.encodeServerHello("audio-1");

        assertThat(json).contains("\"type\":\"hello\"");
        assertThat(json).contains("\"transport\":\"websocket\"");
        assertThat(json).contains("\"session_id\":\"audio-1\"");
        assertThat(json).contains("\"audio_params\"");
        assertThat(json).doesNotContain("\"audio\"");
        assertThat(json).contains("\"format\":\"opus\"");
    }

    @Test
    void shouldParseClientHello() throws Exception {
        var message = """
                {
                  "type": "hello",
                  "version": 2,
                  "features": {"mcp": true},
                  "transport": "websocket",
                  "audio_params": {
                    "format": "opus",
                    "sample_rate": 16000,
                    "channels": 1,
                    "frame_duration": 60
                  }
                }
                """;

        var hello = codec.decodeClientHello(message);

        assertThat(hello.type()).isEqualTo("hello");
        assertThat(hello.version()).isEqualTo(2);
        assertThat(hello.transport()).isEqualTo("websocket");
        assertThat(hello.audioParams().format()).isEqualTo("opus");
        assertThat(hello.audioParams().sampleRate()).isEqualTo(16000);
    }

    @Test
    void shouldParseListenStart() throws Exception {
        var message = codec.decodeText("""
                {
                  "type": "listen",
                  "state": "start",
                  "mode": "manual",
                  "session_id": "s1"
                }
                """);

        assertThat(message.type()).isEqualTo("listen");
        assertThat(message.state()).isEqualTo("start");
        assertThat(message.mode()).isEqualTo("manual");
        assertThat(message.sessionId()).isEqualTo("s1");
    }

    @Test
    void shouldDecodeBinaryV1AsRawOpusPayload() {
        var payload = ByteBuffer.wrap(new byte[] {1, 2, 3});

        var frame = codec.decodeAudioFrame(1, payload);

        assertThat(frame.version()).isEqualTo(1);
        assertThat(frame.timestamp()).isZero();
        assertThat(frame.payload()).containsExactly(1, 2, 3);
    }

    @Test
    void shouldDecodeBinaryV2Header() {
        var payload = ByteBuffer.allocate(16 + 3);
        payload.putShort((short) 2);
        payload.putShort((short) 0);
        payload.putInt(0);
        payload.putInt(1234);
        payload.putInt(3);
        payload.put(new byte[] {4, 5, 6});
        payload.flip();

        var frame = codec.decodeAudioFrame(2, payload);

        assertThat(frame.version()).isEqualTo(2);
        assertThat(frame.timestamp()).isEqualTo(1234);
        assertThat(frame.payload()).containsExactly(4, 5, 6);
    }

    @Test
    void shouldDecodeBinaryV3Header() {
        var payload = ByteBuffer.allocate(4 + 2);
        payload.put((byte) 0);
        payload.put((byte) 0);
        payload.putShort((short) 2);
        payload.put(new byte[] {7, 8});
        payload.flip();

        var frame = codec.decodeAudioFrame(3, payload);

        assertThat(frame.version()).isEqualTo(3);
        assertThat(frame.timestamp()).isZero();
        assertThat(frame.payload()).containsExactly(7, 8);
    }
}
