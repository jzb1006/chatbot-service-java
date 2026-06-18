package com.jzb.chatbot.voice.music;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * 音乐音频源。
 * <p>
 * 负责校验 Hermes 返回的媒体地址，避免 voice-gateway 访问本地文件或非授权地址。
 *
 * @author jiangzhibin
 * @since 2026-06-18 00:00:00
 */
public class MusicAudioSource {

    private final XiaozhiMusicPlaybackProperties properties;
    private final MusicHostResolver hostResolver;
    private final HttpClient httpClient;

    public MusicAudioSource(XiaozhiMusicPlaybackProperties properties, MusicHostResolver hostResolver) {
        this.properties = properties;
        this.hostResolver = hostResolver == null ? MusicHostResolver.system() : hostResolver;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public URI validate(String mediaUrl) {
        if (mediaUrl == null || mediaUrl.isBlank()) {
            throw new IllegalArgumentException("music media_url is required");
        }
        var uri = URI.create(mediaUrl);
        var scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("music media_url must use http or https");
        }
        var host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("music media_url host is required");
        }
        var allowedHosts = properties.allowedHosts();
        if (allowedHosts == null || allowedHosts.isEmpty() || !allowedHosts.contains(host)) {
            throw new IllegalArgumentException("music media_url host is not allowed");
        }
        validateResolvedAddresses(host);
        return uri;
    }

    public HttpClient.Redirect followRedirects() {
        return httpClient.followRedirects();
    }

    public OpenedMusic open(String mediaUrl) throws IOException {
        var uri = validate(mediaUrl);
        var request = HttpRequest.newBuilder(uri)
                .timeout(properties.connectTimeout())
                .GET()
                .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                response.body().close();
                throw new IllegalArgumentException("music media_url redirect is not allowed");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw new IllegalArgumentException("music media_url returned non-success status");
            }
            return new OpenedMusic(uri, response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while opening music media_url", exception);
        }
    }

    private void validateResolvedAddresses(String host) {
        try {
            var addresses = hostResolver.resolve(host);
            if (addresses == null || addresses.isEmpty()) {
                throw new IllegalArgumentException("music media_url host cannot be resolved");
            }
            for (var address : addresses) {
                if (!publicAddress(address)) {
                    throw new IllegalArgumentException("music media_url resolved to non-public address");
                }
            }
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("music media_url host cannot be resolved", exception);
        }
    }

    private boolean publicAddress(InetAddress address) {
        return !address.isAnyLocalAddress()
                && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress()
                && !address.isSiteLocalAddress()
                && !address.isMulticastAddress()
                && !carrierGradeNat(address);
    }

    private boolean carrierGradeNat(InetAddress address) {
        var bytes = address.getAddress();
        return bytes.length == 4
                && Byte.toUnsignedInt(bytes[0]) == 100
                && Byte.toUnsignedInt(bytes[1]) >= 64
                && Byte.toUnsignedInt(bytes[1]) <= 127;
    }

    public record OpenedMusic(URI uri, InputStream inputStream) implements AutoCloseable {

        @Override
        public void close() throws IOException {
            inputStream.close();
        }
    }
}
