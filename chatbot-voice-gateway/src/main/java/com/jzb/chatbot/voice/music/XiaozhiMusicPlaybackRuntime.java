package com.jzb.chatbot.voice.music;

import com.jzb.chatbot.voice.protocol.XiaozhiServerEventFactory;
import com.jzb.chatbot.voice.XiaozhiVoiceSession;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 小智音乐播放运行时。
 * <p>
 * 管理每台设备的音乐播放任务、暂停、恢复和停止。
 *
 * @author jiangzhibin
 * @since 2026-06-18 00:00:00
 */
@Slf4j
public class XiaozhiMusicPlaybackRuntime implements XiaozhiMusicPlaybackCoordinator {

    private static final long PAUSE_IDLE_TIMEOUT_MS = 90L;
    private static final long PAUSE_IDLE_POLL_MS = 10L;

    private final MusicAudioSource audioSource;
    private final FfmpegMusicDecoder decoder;
    private final MusicFrameSender frameSender;
    private final XiaozhiMusicPlaybackProperties properties;
    private final XiaozhiServerEventFactory eventFactory;
    private final Map<String, PlaybackTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, XiaozhiMusicPlaybackState> lastStates = new ConcurrentHashMap<>();
    private final Map<String, XiaozhiMusicPlaybackRequest> lastRequests = new ConcurrentHashMap<>();

    public XiaozhiMusicPlaybackRuntime(
            MusicAudioSource audioSource,
            FfmpegMusicDecoder decoder,
            MusicFrameSender frameSender,
            XiaozhiMusicPlaybackProperties properties
    ) {
        this(audioSource, decoder, frameSender, properties, null);
    }

    public XiaozhiMusicPlaybackRuntime(
            MusicAudioSource audioSource,
            FfmpegMusicDecoder decoder,
            MusicFrameSender frameSender,
            XiaozhiMusicPlaybackProperties properties,
            XiaozhiServerEventFactory eventFactory
    ) {
        this.audioSource = audioSource;
        this.decoder = decoder;
        this.frameSender = frameSender;
        this.properties = properties;
        this.eventFactory = eventFactory;
    }

    public void play(XiaozhiMusicPlaybackRequest request) {
        play(request, null);
    }

    public void playPausedForTts(XiaozhiMusicPlaybackRequest request) {
        play(request, XiaozhiMusicPlaybackState.PauseSource.TTS);
    }

    private void play(XiaozhiMusicPlaybackRequest request, XiaozhiMusicPlaybackState.PauseSource initialPauseSource) {
        if (!properties.enabled()) {
            return;
        }
        var deviceId = request.voiceSession().deviceId();
        stop(deviceId);
        var task = new PlaybackTask(request, initialPauseSource);
        tasks.put(deviceId, task);
        lastStates.put(deviceId, stoppedState(task));
        lastRequests.put(deviceId, request);
        log.info("xiaozhi music playback requested, deviceId={}, requestId={}, requestIdSource={}, source={}, title={}, artist={}, mediaHost={}, initialPauseSource={}",
                deviceId, request.requestId(), request.requestIdSource(), request.source(), request.title(),
                request.artist(), mediaHost(request.mediaUrl()), initialPauseSource);
        Thread.ofVirtual().name("xiaozhi-music-" + deviceId).start(() -> run(deviceId, task));
    }

    @Override
    public void pause(String deviceId, XiaozhiMusicPlaybackState.PauseSource source) {
        var task = tasks.get(deviceId);
        if (task != null) {
            task.pause(source);
            task.awaitIdle(PAUSE_IDLE_TIMEOUT_MS);
            log.info("xiaozhi music playback paused, deviceId={}, source={}", deviceId, source);
        }
    }

    @Override
    public void resume(String deviceId, XiaozhiMusicPlaybackState.PauseSource source) {
        var task = tasks.get(deviceId);
        if (task != null) {
            task.resume(source);
            log.info("xiaozhi music playback resumed, deviceId={}, source={}, status={}",
                    deviceId, source, task.state().status());
            return;
        }
    }

    public void resume(
            WebSocketSession webSocketSession,
            XiaozhiVoiceSession voiceSession,
            XiaozhiMusicPlaybackState.PauseSource source
    ) {
        var deviceId = voiceSession.deviceId();
        var task = tasks.get(deviceId);
        if (task != null) {
            task.resume(source);
            log.info("xiaozhi music playback resumed, deviceId={}, source={}, status={}",
                    deviceId, source, task.state().status());
            return;
        }
        var lastRequest = lastRequests.get(deviceId);
        if (lastRequest != null) {
            log.info("xiaozhi music playback replaying last track, deviceId={}, requestId={}, source={}",
                    deviceId, lastRequest.requestId(), source);
            play(new XiaozhiMusicPlaybackRequest(
                    webSocketSession,
                    voiceSession,
                    lastRequest.title(),
                    lastRequest.artist(),
                    lastRequest.mediaUrl(),
                    lastRequest.requestId(),
                    lastRequest.source()
            ));
        }
    }

    public void stop(String deviceId) {
        var task = tasks.remove(deviceId);
        if (task != null) {
            task.cancel();
            lastStates.put(deviceId, stoppedState(task));
        }
    }

    public XiaozhiMusicPlaybackState state(String deviceId) {
        var task = tasks.get(deviceId);
        if (task != null) {
            return task.state();
        }
        var lastState = lastStates.get(deviceId);
        return lastState == null ? new XiaozhiMusicPlaybackState(
                deviceId, null, null, XiaozhiMusicPlaybackState.Status.STOPPED, null
        ) : lastState;
    }

    private void run(String deviceId, PlaybackTask task) {
        try {
            var deadline = System.nanoTime() + properties.maxDuration().toNanos();
            try (var opened = audioSource.open(task.request.mediaUrl());
                    var decoded = decoder.decode(opened.inputStream())) {
                var sentFrames = frameSender.send(
                        task.request.webSocketSession(),
                        task.request.voiceSession(),
                        decoded.pcmStream(),
                        task::paused,
                        () -> task.cancelled() || expired(deadline),
                        () -> {
                            sendMediaStartOnce(task);
                            task.markSending();
                        },
                        task::markIdle
                );
                log.info("xiaozhi music playback finished, deviceId={}, requestId={}, title={}, sentFrames={}",
                        deviceId, task.request.requestId(), task.request.title(), sentFrames);
            }
        } catch (IOException | RuntimeException exception) {
            log.warn("xiaozhi music playback failed, deviceId={}, requestId={}, title={}, message={}",
                    deviceId, task.request.requestId(), task.request.title(), exception.getMessage(), exception);
            sendPlaybackFailure(task, exception);
        } finally {
            sendMediaStop(task);
            lastStates.put(deviceId, stoppedState(task));
            tasks.remove(deviceId, task);
        }
    }

    private XiaozhiMusicPlaybackState stoppedState(PlaybackTask task) {
        return new XiaozhiMusicPlaybackState(
                task.request.voiceSession().deviceId(),
                task.request.title(),
                task.request.artist(),
                XiaozhiMusicPlaybackState.Status.STOPPED,
                null,
                task.request.requestId(),
                task.request.requestIdSource(),
                task.request.source()
        );
    }

    private void sendMediaStartOnce(PlaybackTask task) {
        if (task.mediaStarted() || eventFactory == null) {
            return;
        }
        try {
            task.request.webSocketSession().sendMessage(new TextMessage(eventFactory.mediaStart(
                    task.request.voiceSession().sessionId(),
                    "music",
                    task.request.title(),
                    task.request.artist(),
                    task.request.requestId(),
                    task.request.source()
            )));
            task.markMediaStarted();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to send xiaozhi music start event", exception);
        }
    }

    private void sendMediaStop(PlaybackTask task) {
        if (eventFactory == null || !task.mediaStarted() || !task.request.webSocketSession().isOpen()) {
            return;
        }
        try {
            task.request.webSocketSession().sendMessage(new TextMessage(eventFactory.mediaStop(
                    task.request.voiceSession().sessionId(),
                    "music"
            )));
        } catch (IOException exception) {
            log.warn("xiaozhi music playback stop event send failed, deviceId={}, title={}, message={}",
                    task.request.voiceSession().deviceId(), task.request.title(), exception.getMessage(),
                    exception);
        }
    }

    private void sendPlaybackFailure(PlaybackTask task, Exception exception) {
        if (eventFactory == null || !task.request.webSocketSession().isOpen()) {
            return;
        }
        var sessionId = task.request.voiceSession().sessionId();
        var message = userFriendlyFailureMessage(exception);
        try {
            task.request.webSocketSession().sendMessage(new TextMessage(
                    eventFactory.error(sessionId, "music_playback_failed", message)
            ));
        } catch (IOException sendException) {
            log.warn("xiaozhi music playback failure event send failed, deviceId={}, title={}, message={}",
                    task.request.voiceSession().deviceId(), task.request.title(), sendException.getMessage(),
                    sendException);
        }
    }

    private String userFriendlyFailureMessage(Exception exception) {
        if (exception instanceof IllegalArgumentException
                && "music media_url host is not allowed".equals(exception.getMessage())) {
            return "音乐播放失败：音频来源未授权";
        }
        return "音乐播放失败：音频暂时无法播放";
    }

    private String mediaHost(String mediaUrl) {
        if (mediaUrl == null || mediaUrl.isBlank()) {
            return "";
        }
        try {
            var host = URI.create(mediaUrl).getHost();
            return host == null ? "" : host;
        } catch (IllegalArgumentException exception) {
            return "invalid";
        }
    }

    private boolean expired(long deadline) {
        return System.nanoTime() >= deadline;
    }

    private static final class PlaybackTask {
        private final XiaozhiMusicPlaybackRequest request;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean sending = new AtomicBoolean();
        private final AtomicBoolean manualPaused = new AtomicBoolean();
        private final AtomicBoolean ttsPaused = new AtomicBoolean();
        private final AtomicBoolean controlPaused = new AtomicBoolean();
        private final AtomicBoolean mediaStarted = new AtomicBoolean();

        private PlaybackTask(
                XiaozhiMusicPlaybackRequest request,
                XiaozhiMusicPlaybackState.PauseSource initialPauseSource
        ) {
            this.request = request;
            pause(initialPauseSource);
        }

        private void pause(XiaozhiMusicPlaybackState.PauseSource source) {
            if (source == XiaozhiMusicPlaybackState.PauseSource.MANUAL) {
                manualPaused.set(true);
            } else if (source == XiaozhiMusicPlaybackState.PauseSource.TTS) {
                ttsPaused.set(true);
            } else if (source == XiaozhiMusicPlaybackState.PauseSource.CONTROL) {
                controlPaused.set(true);
            }
        }

        private void resume(XiaozhiMusicPlaybackState.PauseSource source) {
            if (source == XiaozhiMusicPlaybackState.PauseSource.MANUAL) {
                manualPaused.set(false);
            } else if (source == XiaozhiMusicPlaybackState.PauseSource.TTS) {
                ttsPaused.set(false);
            } else if (source == XiaozhiMusicPlaybackState.PauseSource.CONTROL) {
                controlPaused.set(false);
            }
        }

        private void cancel() {
            cancelled.set(true);
        }

        private boolean cancelled() {
            return cancelled.get();
        }

        private boolean paused() {
            return manualPaused.get() || ttsPaused.get() || controlPaused.get();
        }

        private void markSending() {
            sending.set(true);
        }

        private void markMediaStarted() {
            mediaStarted.set(true);
        }

        private boolean mediaStarted() {
            return mediaStarted.get();
        }

        private void markIdle() {
            sending.set(false);
        }

        private void awaitIdle(long timeoutMillis) {
            var deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
            while (sending.get() && System.nanoTime() < deadline) {
                sleep(PAUSE_IDLE_POLL_MS);
            }
        }

        private void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                cancel();
            }
        }

        private XiaozhiMusicPlaybackState state() {
            return new XiaozhiMusicPlaybackState(
                    request.voiceSession().deviceId(),
                    request.title(),
                    request.artist(),
                    paused() ? XiaozhiMusicPlaybackState.Status.PAUSED : XiaozhiMusicPlaybackState.Status.PLAYING,
                    pauseSource(),
                    request.requestId(),
                    request.requestIdSource(),
                    request.source()
            );
        }

        private XiaozhiMusicPlaybackState.PauseSource pauseSource() {
            if (manualPaused.get()) {
                return XiaozhiMusicPlaybackState.PauseSource.MANUAL;
            }
            if (controlPaused.get()) {
                return XiaozhiMusicPlaybackState.PauseSource.CONTROL;
            }
            return ttsPaused.get() ? XiaozhiMusicPlaybackState.PauseSource.TTS : null;
        }
    }
}
