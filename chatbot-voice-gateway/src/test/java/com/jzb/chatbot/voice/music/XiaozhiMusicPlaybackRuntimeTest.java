package com.jzb.chatbot.voice.music;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jzb.chatbot.voice.TestWebSocketSession;
import com.jzb.chatbot.voice.XiaozhiVoiceSession;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import com.jzb.chatbot.voice.protocol.XiaozhiServerEventFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;

class XiaozhiMusicPlaybackRuntimeTest {

    @Test
    void shouldStopPreviousPlaybackWhenNewMusicStarts() {
        var properties = new XiaozhiMusicPlaybackProperties(
                true,
                "ffmpeg",
                Duration.ofSeconds(3),
                Duration.ofMinutes(5),
                Set.of("example.com")
        );
        var runtime = new XiaozhiMusicPlaybackRuntime(
                new MusicAudioSource(properties, host -> List.of(InetAddress.getByName("93.184.216.34"))),
                new TestFfmpegMusicDecoder(),
                new MusicFrameSender(new XiaozhiMessageCodec(new ObjectMapper())),
                properties
        );
        var webSocketSession = new TestWebSocketSession("ws-session-1");
        var voiceSession = new XiaozhiVoiceSession("session-1");
        voiceSession.updateHandshake(null, "device-1", "client-1", 1);

        runtime.play(new XiaozhiMusicPlaybackRequest(
                webSocketSession,
                voiceSession,
                "第一首",
                "歌手",
                "https://example.com/one.mp3"
        ));
        runtime.play(new XiaozhiMusicPlaybackRequest(
                webSocketSession,
                voiceSession,
                "第二首",
                "歌手",
                "https://example.com/two.mp3"
        ));

        assertThat(runtime.state("device-1").title()).isEqualTo("第二首");
        assertThat(runtime.state("device-1").status()).isEqualTo(XiaozhiMusicPlaybackState.Status.PLAYING);
    }

    @Test
    void shouldKeepManualPauseWhenTtsPauseIsResumed() {
        var properties = new XiaozhiMusicPlaybackProperties(
                true,
                "ffmpeg",
                Duration.ofSeconds(3),
                Duration.ofMinutes(5),
                Set.of("example.com")
        );
        var runtime = new XiaozhiMusicPlaybackRuntime(
                new MusicAudioSource(properties, host -> List.of(InetAddress.getByName("93.184.216.34"))),
                new TestFfmpegMusicDecoder(),
                new MusicFrameSender(new XiaozhiMessageCodec(new ObjectMapper())),
                properties
        );
        var webSocketSession = new TestWebSocketSession("ws-session-1");
        var voiceSession = new XiaozhiVoiceSession("session-1");
        voiceSession.updateHandshake(null, "device-1", "client-1", 1);

        runtime.play(new XiaozhiMusicPlaybackRequest(
                webSocketSession,
                voiceSession,
                "第一首",
                "歌手",
                "https://example.com/one.mp3"
        ));
        runtime.pause("device-1", XiaozhiMusicPlaybackState.PauseSource.TTS);
        runtime.pause("device-1", XiaozhiMusicPlaybackState.PauseSource.MANUAL);
        runtime.resume("device-1", XiaozhiMusicPlaybackState.PauseSource.TTS);

        assertThat(runtime.state("device-1").status()).isEqualTo(XiaozhiMusicPlaybackState.Status.PAUSED);
        assertThat(runtime.state("device-1").pauseSource()).isEqualTo(XiaozhiMusicPlaybackState.PauseSource.MANUAL);
    }

    @Test
    void shouldStartPlaybackPausedForTtsAndResumeAfterTtsFinishes() {
        var properties = new XiaozhiMusicPlaybackProperties(
                true,
                "ffmpeg",
                Duration.ofSeconds(3),
                Duration.ofMinutes(5),
                Set.of("example.com")
        );
        var runtime = new XiaozhiMusicPlaybackRuntime(
                new MusicAudioSource(properties, host -> List.of(InetAddress.getByName("93.184.216.34"))),
                new TestFfmpegMusicDecoder(),
                new MusicFrameSender(new XiaozhiMessageCodec(new ObjectMapper())),
                properties
        );
        var webSocketSession = new TestWebSocketSession("ws-session-1");
        var voiceSession = new XiaozhiVoiceSession("session-1");
        voiceSession.updateHandshake(null, "device-1", "client-1", 1);

        runtime.playPausedForTts(new XiaozhiMusicPlaybackRequest(
                webSocketSession,
                voiceSession,
                "晴天",
                "周杰伦",
                "https://example.com/qingtian.mp3"
        ));

        assertThat(runtime.state("device-1").status()).isEqualTo(XiaozhiMusicPlaybackState.Status.PAUSED);
        assertThat(runtime.state("device-1").pauseSource()).isEqualTo(XiaozhiMusicPlaybackState.PauseSource.TTS);

        runtime.resume("device-1", XiaozhiMusicPlaybackState.PauseSource.TTS);

        assertThat(runtime.state("device-1").status()).isEqualTo(XiaozhiMusicPlaybackState.Status.PLAYING);
        assertThat(runtime.state("device-1").pauseSource()).isNull();
    }

    @Test
    void shouldSendErrorMessageWhenMusicSourceIsRejected() {
        var properties = new XiaozhiMusicPlaybackProperties(
                true,
                "ffmpeg",
                Duration.ofSeconds(3),
                Duration.ofMinutes(5),
                Set.of("example.com")
        );
        var runtime = new XiaozhiMusicPlaybackRuntime(
                new MusicAudioSource(properties, host -> List.of(InetAddress.getByName("93.184.216.34"))),
                new TestFfmpegMusicDecoder(),
                new MusicFrameSender(new XiaozhiMessageCodec(new ObjectMapper())),
                properties,
                new XiaozhiServerEventFactory(new ObjectMapper())
        );
        var webSocketSession = new TextCapturingSession("ws-session-1");
        var voiceSession = new XiaozhiVoiceSession("session-1");
        voiceSession.updateHandshake(null, "device-1", "client-1", 1);

        runtime.play(new XiaozhiMusicPlaybackRequest(
                webSocketSession,
                voiceSession,
                "素颜",
                "许嵩&何曼婷",
                "https://car-lv.kuwo.cn/song.mp3"
        ));

        assertThat(webSocketSession.awaitTextMessage()).isTrue();
        assertThat(textMessages(webSocketSession))
                .anySatisfy(payload -> assertThat(payload)
                        .contains("\"type\":\"error\"")
                        .contains("\"code\":\"music_playback_failed\"")
                        .contains("音乐播放失败：音频来源未授权"));
    }

    private List<String> textMessages(TestWebSocketSession session) {
        return session.getSentMessages().stream()
                .filter(TextMessage.class::isInstance)
                .map(TextMessage.class::cast)
                .map(TextMessage::getPayload)
                .toList();
    }

    private static final class TestFfmpegMusicDecoder extends FfmpegMusicDecoder {

        private TestFfmpegMusicDecoder() {
            super("ffmpeg");
        }

        @Override
        public DecodedMusic decode(InputStream mediaStream) {
            return new DecodedMusic(new DummyProcess(), new ByteArrayInputStream(new byte[0]));
        }
    }

    private static final class DummyProcess extends Process {

        @Override
        public java.io.OutputStream getOutputStream() {
            return java.io.OutputStream.nullOutputStream();
        }

        @Override
        public java.io.InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public java.io.InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
        }

        @Override
        public boolean isAlive() {
            return false;
        }
    }

    private static final class TextCapturingSession extends TestWebSocketSession {
        private final CountDownLatch textMessageSent = new CountDownLatch(1);

        private TextCapturingSession(String id) {
            super(id);
        }

        @Override
        public synchronized void sendMessage(WebSocketMessage<?> message) throws IOException {
            super.sendMessage(message);
            if (message instanceof TextMessage) {
                textMessageSent.countDown();
            }
        }

        private boolean awaitTextMessage() {
            try {
                return textMessageSent.await(1L, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
