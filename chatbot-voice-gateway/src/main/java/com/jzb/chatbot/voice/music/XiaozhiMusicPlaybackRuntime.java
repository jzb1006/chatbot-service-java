package com.jzb.chatbot.voice.music;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

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
    private final Map<String, PlaybackTask> tasks = new ConcurrentHashMap<>();

    public XiaozhiMusicPlaybackRuntime(
            MusicAudioSource audioSource,
            FfmpegMusicDecoder decoder,
            MusicFrameSender frameSender,
            XiaozhiMusicPlaybackProperties properties
    ) {
        this.audioSource = audioSource;
        this.decoder = decoder;
        this.frameSender = frameSender;
        this.properties = properties;
    }

    public void play(XiaozhiMusicPlaybackRequest request) {
        if (!properties.enabled()) {
            return;
        }
        var deviceId = request.voiceSession().deviceId();
        stop(deviceId);
        var task = new PlaybackTask(request);
        tasks.put(deviceId, task);
        Thread.ofVirtual().name("xiaozhi-music-" + deviceId).start(() -> run(deviceId, task));
    }

    @Override
    public void pause(String deviceId, XiaozhiMusicPlaybackState.PauseSource source) {
        var task = tasks.get(deviceId);
        if (task != null) {
            task.pause(source);
            task.awaitIdle(PAUSE_IDLE_TIMEOUT_MS);
        }
    }

    @Override
    public void resume(String deviceId, XiaozhiMusicPlaybackState.PauseSource source) {
        var task = tasks.get(deviceId);
        if (task != null) {
            task.resume(source);
        }
    }

    public void stop(String deviceId) {
        var task = tasks.remove(deviceId);
        if (task != null) {
            task.cancel();
        }
    }

    public XiaozhiMusicPlaybackState state(String deviceId) {
        var task = tasks.get(deviceId);
        return task == null ? new XiaozhiMusicPlaybackState(
                deviceId, null, null, XiaozhiMusicPlaybackState.Status.STOPPED, null
        ) : task.state();
    }

    private void run(String deviceId, PlaybackTask task) {
        try {
            var deadline = System.nanoTime() + properties.maxDuration().toNanos();
            try (var opened = audioSource.open(task.request.mediaUrl());
                    var decoded = decoder.decode(opened.inputStream())) {
                frameSender.send(
                        task.request.webSocketSession(),
                        task.request.voiceSession(),
                        decoded.pcmStream(),
                        task::paused,
                        () -> task.cancelled() || expired(deadline),
                        task::markSending,
                        task::markIdle
                );
            }
        } catch (IOException | RuntimeException exception) {
            log.warn("xiaozhi music playback failed, deviceId={}, title={}, message={}",
                    deviceId, task.request.title(), exception.getMessage(), exception);
        } finally {
            tasks.remove(deviceId, task);
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

        private PlaybackTask(XiaozhiMusicPlaybackRequest request) {
            this.request = request;
        }

        private void pause(XiaozhiMusicPlaybackState.PauseSource source) {
            if (source == XiaozhiMusicPlaybackState.PauseSource.MANUAL) {
                manualPaused.set(true);
            } else if (source == XiaozhiMusicPlaybackState.PauseSource.TTS) {
                ttsPaused.set(true);
            }
        }

        private void resume(XiaozhiMusicPlaybackState.PauseSource source) {
            if (source == XiaozhiMusicPlaybackState.PauseSource.MANUAL) {
                manualPaused.set(false);
            } else if (source == XiaozhiMusicPlaybackState.PauseSource.TTS) {
                ttsPaused.set(false);
            }
        }

        private void cancel() {
            cancelled.set(true);
        }

        private boolean cancelled() {
            return cancelled.get();
        }

        private boolean paused() {
            return manualPaused.get() || ttsPaused.get();
        }

        private void markSending() {
            sending.set(true);
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
                    pauseSource()
            );
        }

        private XiaozhiMusicPlaybackState.PauseSource pauseSource() {
            if (manualPaused.get()) {
                return XiaozhiMusicPlaybackState.PauseSource.MANUAL;
            }
            return ttsPaused.get() ? XiaozhiMusicPlaybackState.PauseSource.TTS : null;
        }
    }
}
