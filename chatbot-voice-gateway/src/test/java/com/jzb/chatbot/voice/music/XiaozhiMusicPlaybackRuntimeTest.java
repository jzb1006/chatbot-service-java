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
    void shouldResumeControlPausedMusicWhenManualResumeArrives() {
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
        runtime.pause("device-1", XiaozhiMusicPlaybackState.PauseSource.CONTROL);
        runtime.resume("device-1", XiaozhiMusicPlaybackState.PauseSource.MANUAL);

        assertThat(runtime.state("device-1").status()).isEqualTo(XiaozhiMusicPlaybackState.Status.PLAYING);
        assertThat(runtime.state("device-1").pauseSource()).isNull();
    }

    @Test
    void shouldExposePlaybackTraceInStateAndGenerateMissingRequestId() {
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
                "晴天",
                "周杰伦",
                "https://example.com/qingtian.mp3",
                null,
                "buguyy"
        ));

        assertThat(runtime.state("device-1").requestId()).startsWith("local-");
        assertThat(runtime.state("device-1").requestIdSource()).isEqualTo("generated");
        assertThat(runtime.state("device-1").source()).isEqualTo("buguyy");
    }

    @Test
    void shouldKeepLastTrackWhenPlaybackStops() {
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
                "晴天",
                "周杰伦",
                "https://example.com/qingtian.mp3",
                "music-20260621-001",
                "buguyy"
        ));
        runtime.stop("device-1");

        assertThat(runtime.state("device-1").status()).isEqualTo(XiaozhiMusicPlaybackState.Status.STOPPED);
        assertThat(runtime.state("device-1").title()).isEqualTo("晴天");
        assertThat(runtime.state("device-1").artist()).isEqualTo("周杰伦");
        assertThat(runtime.state("device-1").requestId()).isEqualTo("music-20260621-001");
        assertThat(runtime.state("device-1").source()).isEqualTo("buguyy");
    }

    @Test
    void shouldNotReplayLastTrackWhenTtsResumeHasNoActiveTask() {
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
                "晴天",
                "周杰伦",
                "https://example.com/qingtian.mp3",
                "music-20260621-001",
                "buguyy"
        ));
        runtime.stop("device-1");
        runtime.resume("device-1", XiaozhiMusicPlaybackState.PauseSource.TTS);

        assertThat(runtime.state("device-1").status()).isEqualTo(XiaozhiMusicPlaybackState.Status.STOPPED);
        assertThat(runtime.state("device-1").title()).isEqualTo("晴天");
        assertThat(runtime.state("device-1").requestId()).isEqualTo("music-20260621-001");
    }

    @Test
    void shouldReplayLastTrackOnCurrentSessionWhenManualResumeHasNoActiveTask() throws Exception {
        var properties = new XiaozhiMusicPlaybackProperties(
                true,
                "ffmpeg",
                Duration.ofSeconds(3),
                Duration.ofMinutes(5),
                Set.of("example.com")
        );
        var runtime = new XiaozhiMusicPlaybackRuntime(
                new TestMusicAudioSource(properties),
                new TestFfmpegMusicDecoder(new byte[16000 / 1000 * 60 * Short.BYTES]),
                new MusicFrameSender(new XiaozhiMessageCodec(new ObjectMapper())),
                properties,
                new XiaozhiServerEventFactory(new ObjectMapper())
        );
        var oldWebSocketSession = new MessageCountingSession("ws-session-old");
        var oldVoiceSession = new XiaozhiVoiceSession("session-old");
        oldVoiceSession.updateHandshake(null, "device-1", "client-1", 1);

        runtime.play(new XiaozhiMusicPlaybackRequest(
                oldWebSocketSession,
                oldVoiceSession,
                "晴天",
                "周杰伦",
                "https://example.com/qingtian.mp3",
                "music-20260621-001",
                "buguyy"
        ));
        assertThat(oldWebSocketSession.awaitMessages(3)).isTrue();
        oldWebSocketSession.close();
        var oldMessageCount = oldWebSocketSession.getSentMessages().size();
        var newWebSocketSession = new MessageCountingSession("ws-session-new");
        var newVoiceSession = new XiaozhiVoiceSession("session-new");
        newVoiceSession.updateHandshake(null, "device-1", "client-1", 1);

        runtime.resume(newWebSocketSession, newVoiceSession, XiaozhiMusicPlaybackState.PauseSource.MANUAL);

        assertThat(newWebSocketSession.awaitMessages(3)).isTrue();
        assertThat(oldWebSocketSession.getSentMessages()).hasSize(oldMessageCount);
        assertThat(newWebSocketSession.getSentMessages()).anySatisfy(message ->
                assertThat(message).isInstanceOf(org.springframework.web.socket.BinaryMessage.class));
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

    @Test
    void shouldWrapMusicBinaryFramesWithMediaLifecycleEvents() {
        var properties = new XiaozhiMusicPlaybackProperties(
                true,
                "ffmpeg",
                Duration.ofSeconds(3),
                Duration.ofMinutes(5),
                Set.of("example.com")
        );
        var runtime = new XiaozhiMusicPlaybackRuntime(
                new TestMusicAudioSource(properties),
                new TestFfmpegMusicDecoder(new byte[16000 / 1000 * 60 * Short.BYTES]),
                new MusicFrameSender(new XiaozhiMessageCodec(new ObjectMapper())),
                properties,
                new XiaozhiServerEventFactory(new ObjectMapper())
        );
        var webSocketSession = new MessageCountingSession("ws-session-1");
        var voiceSession = new XiaozhiVoiceSession("session-1");
        voiceSession.updateHandshake(null, "device-1", "client-1", 1);

        runtime.play(new XiaozhiMusicPlaybackRequest(
                webSocketSession,
                voiceSession,
                "晴天",
                "周杰伦",
                "https://example.com/qingtian.mp3",
                "music-20260621-001",
                "buguyy"
        ));

        assertThat(webSocketSession.awaitMessages(3)).isTrue();
        assertThat(runtime.state("device-1").status()).isEqualTo(XiaozhiMusicPlaybackState.Status.STOPPED);
        assertThat(webSocketSession.getSentMessages().get(0)).isInstanceOf(TextMessage.class);
        assertThat(((TextMessage) webSocketSession.getSentMessages().get(0)).getPayload())
                .contains("\"type\":\"media\"")
                .contains("\"state\":\"start\"")
                .contains("\"kind\":\"music\"")
                .contains("\"request_id\":\"music-20260621-001\"")
                .contains("\"source\":\"buguyy\"")
                .contains("\"title\":\"晴天\"")
                .contains("\"artist\":\"周杰伦\"");
        assertThat(webSocketSession.getSentMessages()).anySatisfy(message ->
                assertThat(message).isInstanceOf(org.springframework.web.socket.BinaryMessage.class));
        assertThat(webSocketSession.getSentMessages().get(webSocketSession.getSentMessages().size() - 1))
                .isInstanceOf(TextMessage.class);
        assertThat(((TextMessage) webSocketSession.getSentMessages().get(
                webSocketSession.getSentMessages().size() - 1)).getPayload())
                .contains("\"type\":\"media\"")
                .contains("\"state\":\"stop\"")
                .contains("\"kind\":\"music\"");
    }

    private List<String> textMessages(TestWebSocketSession session) {
        return session.getSentMessages().stream()
                .filter(TextMessage.class::isInstance)
                .map(TextMessage.class::cast)
                .map(TextMessage::getPayload)
                .toList();
    }

    private static final class TestMusicAudioSource extends MusicAudioSource {

        private TestMusicAudioSource(XiaozhiMusicPlaybackProperties properties) {
            super(properties, host -> List.of(InetAddress.getByName("93.184.216.34")));
        }

        @Override
        public OpenedMusic open(String mediaUrl) {
            return new OpenedMusic(java.net.URI.create(mediaUrl), InputStream.nullInputStream());
        }
    }

    private static final class TestFfmpegMusicDecoder extends FfmpegMusicDecoder {

        private final byte[] pcm;

        private TestFfmpegMusicDecoder() {
            this(new byte[0]);
        }

        private TestFfmpegMusicDecoder(byte[] pcm) {
            super("ffmpeg");
            this.pcm = pcm;
        }

        @Override
        public DecodedMusic decode(InputStream mediaStream) {
            return new DecodedMusic(new DummyProcess(), new ByteArrayInputStream(pcm));
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

    private static final class MessageCountingSession extends TestWebSocketSession {
        private final CountDownLatch messagesSent;

        private MessageCountingSession(String id) {
            super(id);
            this.messagesSent = new CountDownLatch(3);
        }

        @Override
        public synchronized void sendMessage(WebSocketMessage<?> message) throws IOException {
            super.sendMessage(message);
            messagesSent.countDown();
        }

        private boolean awaitMessages(int expectedCount) {
            try {
                while (getSentMessages().size() < expectedCount) {
                    if (!messagesSent.await(1L, TimeUnit.SECONDS)) {
                        return false;
                    }
                }
                return true;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
