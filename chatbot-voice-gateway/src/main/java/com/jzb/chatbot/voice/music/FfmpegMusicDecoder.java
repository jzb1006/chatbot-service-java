package com.jzb.chatbot.voice.music;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * ffmpeg 音乐解码器。
 * <p>
 * 将远端媒体解码为小智播放链路需要的 16k mono signed 16-bit little-endian PCM。
 *
 * @author jiangzhibin
 * @since 2026-06-18 00:00:00
 */
public class FfmpegMusicDecoder {

    private final String ffmpegPath;

    public FfmpegMusicDecoder(String ffmpegPath) {
        this.ffmpegPath = ffmpegPath == null || ffmpegPath.isBlank() ? "ffmpeg" : ffmpegPath;
    }

    public List<String> command() {
        return List.of(
                ffmpegPath,
                "-hide_banner",
                "-loglevel",
                "error",
                "-i",
                "pipe:0",
                "-vn",
                "-ac",
                "1",
                "-ar",
                "16000",
                "-f",
                "s16le",
                "pipe:1"
        );
    }

    public DecodedMusic decode(InputStream mediaStream) throws IOException {
        var process = new ProcessBuilder(command())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        Thread.ofVirtual().name("xiaozhi-music-ffmpeg-stdin").start(() -> copyToProcess(mediaStream, process));
        return new DecodedMusic(process, process.getInputStream());
    }

    private void copyToProcess(InputStream mediaStream, Process process) {
        try (mediaStream; var stdin = process.getOutputStream()) {
            mediaStream.transferTo(stdin);
        } catch (IOException exception) {
            process.destroy();
        }
    }

    public record DecodedMusic(Process process, InputStream pcmStream) implements AutoCloseable {

        @Override
        public void close() throws IOException {
            pcmStream.close();
            if (process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(500L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
        }
    }
}
