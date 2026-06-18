package com.jzb.chatbot.voice.music;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MusicAudioSourceTest {

    @Test
    void shouldRejectLocalFileUrl() {
        var source = new MusicAudioSource(new XiaozhiMusicPlaybackProperties(
                true,
                "ffmpeg",
                Duration.ofSeconds(3),
                Duration.ofMinutes(5),
                Set.of("example.com")
        ), host -> List.of(InetAddress.getByName("93.184.216.34")));

        assertThatThrownBy(() -> source.validate("file:///tmp/song.mp3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("music media_url must use http or https");
    }

    @Test
    void shouldRejectHostOutsideAllowList() {
        var source = new MusicAudioSource(new XiaozhiMusicPlaybackProperties(
                true,
                "ffmpeg",
                Duration.ofSeconds(3),
                Duration.ofMinutes(5),
                Set.of("example.com")
        ), host -> List.of(InetAddress.getByName("93.184.216.34")));

        assertThatThrownBy(() -> source.validate("https://evil.example.org/song.mp3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("music media_url host is not allowed");
    }

    @Test
    void shouldRejectAllowedHostResolvedToPrivateAddress() {
        var source = new MusicAudioSource(new XiaozhiMusicPlaybackProperties(
                true,
                "ffmpeg",
                Duration.ofSeconds(3),
                Duration.ofMinutes(5),
                Set.of("example.com")
        ), host -> List.of(InetAddress.getByName("127.0.0.1")));

        assertThatThrownBy(() -> source.validate("https://example.com/song.mp3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("music media_url resolved to non-public address");
    }

    @Test
    void shouldRejectWhenAllowListIsEmpty() {
        var source = new MusicAudioSource(new XiaozhiMusicPlaybackProperties(
                true,
                "ffmpeg",
                Duration.ofSeconds(3),
                Duration.ofMinutes(5),
                Set.of()
        ), host -> List.of(InetAddress.getByName("93.184.216.34")));

        assertThatThrownBy(() -> source.validate("https://example.com/song.mp3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("music media_url host is not allowed");
    }

    @Test
    void shouldCreateHttpClientWithoutRedirectFollowing() {
        var source = new MusicAudioSource(new XiaozhiMusicPlaybackProperties(
                true,
                "ffmpeg",
                Duration.ofSeconds(3),
                Duration.ofMinutes(5),
                Set.of("example.com")
        ), host -> List.of(InetAddress.getByName("93.184.216.34")));

        assertThat(source.followRedirects()).isEqualTo(java.net.http.HttpClient.Redirect.NEVER);
    }
}
