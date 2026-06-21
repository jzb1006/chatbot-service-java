package com.jzb.chatbot.speech;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * ffmpeg Edge 音频解码器。
 * <p>
 * 将 Edge 返回的 MP3 解码为 16-bit little-endian PCM。
 *
 * @author jiangzhibin
 * @since 2026-06-21 09:34:00
 */
final class FfmpegEdgeAudioDecoder implements EdgeAudioDecoder {

    private final String ffmpegPath;

    FfmpegEdgeAudioDecoder(String ffmpegPath) {
        this.ffmpegPath = ffmpegPath == null || ffmpegPath.isBlank() ? "ffmpeg" : ffmpegPath;
    }

    @Override
    public byte[] decodeToPcm(byte[] audio, int sampleRate, Duration timeout) {
        if (audio == null || audio.length == 0) {
            return new byte[0];
        }
        var processBuilder = new ProcessBuilder(
                ffmpegPath,
                "-hide_banner",
                "-loglevel",
                "error",
                "-i",
                "pipe:0",
                "-f",
                "s16le",
                "-acodec",
                "pcm_s16le",
                "-ac",
                "1",
                "-ar",
                String.valueOf(sampleRate),
                "pipe:1"
        );
        try {
            var process = processBuilder.start();
            var stdoutFuture = CompletableFuture.supplyAsync(() -> readAllBytes(process.getInputStream()));
            var stderrFuture = CompletableFuture.supplyAsync(() -> readAllBytes(process.getErrorStream()));
            try (var stdin = process.getOutputStream()) {
                stdin.write(audio);
            }
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("ffmpeg Edge audio decode timed out");
            }
            var stdout = stdoutFuture.join();
            var stderr = stderrFuture.join();
            if (process.exitValue() != 0) {
                throw new IllegalStateException("ffmpeg Edge audio decode failed: "
                        + new String(stderr, StandardCharsets.UTF_8));
            }
            return stdout;
        } catch (IOException exception) {
            throw new IllegalStateException("failed to run ffmpeg for Edge audio decode", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ffmpeg Edge audio decode interrupted", exception);
        }
    }

    private byte[] readAllBytes(java.io.InputStream inputStream) {
        try {
            return inputStream.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to read ffmpeg output", exception);
        }
    }
}
