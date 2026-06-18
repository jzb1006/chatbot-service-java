package com.jzb.chatbot.voice.music;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FfmpegMusicDecoderTest {

    @Test
    void shouldBuildFfmpegCommandWithoutShell() {
        var decoder = new FfmpegMusicDecoder("ffmpeg");

        assertThat(decoder.command())
                .containsExactly(
                        "ffmpeg",
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
}
