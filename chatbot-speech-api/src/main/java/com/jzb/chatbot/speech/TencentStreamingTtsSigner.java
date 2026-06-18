package com.jzb.chatbot.speech;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 腾讯云流式 TTS 签名器。
 * <p>
 * 按 stream_wsv2 旧式 HMAC-SHA1 query 签名规则生成 WebSocket URL。
 *
 * @author jiangzhibin
 * @since 2026-06-18 12:50:00
 */
public final class TencentStreamingTtsSigner {

    private static final String SCHEME = "wss";
    private static final String HOST = "tts.cloud.tencent.com";
    private static final String PATH = "/stream_wsv2";
    private static final String SIGN_METHOD = "HmacSHA1";
    private static final long EXPIRES_SECONDS = 3600;

    private final TencentStreamingTextToSpeechConfig config;
    private final Clock clock;

    public TencentStreamingTtsSigner(TencentStreamingTextToSpeechConfig config, Clock clock) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * 生成带签名的腾讯云流式 TTS WebSocket URL。
     *
     * @param sessionId 腾讯云会话 ID
     * @return 已签名 URI
     */
    public URI sign(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        var timestamp = clock.instant().getEpochSecond();
        var params = new TreeMap<String, String>();
        params.put("Action", "TextToStreamAudioWSv2");
        params.put("AppId", String.valueOf(config.appId()));
        params.put("Codec", config.codec());
        params.put("Expired", String.valueOf(timestamp + EXPIRES_SECONDS));
        params.put("SampleRate", String.valueOf(config.sampleRate()));
        params.put("SecretId", config.secretId());
        params.put("SessionId", sessionId);
        params.put("Speed", String.valueOf(config.speed()));
        params.put("Timestamp", String.valueOf(timestamp));
        params.put("VoiceType", config.voiceType());
        params.put("Volume", String.valueOf(config.volume()));
        var queryString = queryString(params);
        var signature = signQuery(queryString);
        return URI.create(SCHEME + "://" + HOST + PATH + "?" + queryString
                + "&Signature=" + urlEncode(signature));
    }

    /**
     * 将现有倍速语义映射为腾讯云 Speed 参数。
     *
     * @param speed 现有 TTS 倍速
     * @return 腾讯云 Speed
     */
    public static double toTencentSpeed(double speed) {
        if (!Double.isFinite(speed)) {
            return 0.0;
        }
        if (speed == 0.6) {
            return -2.0;
        }
        if (speed == 0.8) {
            return -1.0;
        }
        if (speed == 1.0) {
            return 0.0;
        }
        if (speed == 1.2) {
            return 1.0;
        }
        if (speed == 1.5) {
            return 2.0;
        }
        var rounded = Math.round(speed * 100.0) / 100.0;
        return Math.max(-2.0, Math.min(6.0, rounded));
    }

    private String signQuery(String queryString) {
        try {
            var mac = Mac.getInstance(SIGN_METHOD);
            mac.init(new SecretKeySpec(config.secretKey().getBytes(StandardCharsets.UTF_8), SIGN_METHOD));
            var plainText = "GET" + HOST + PATH + "?" + queryString;
            var digest = mac.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to sign Tencent streaming TTS URL", exception);
        }
    }

    private static String queryString(Map<String, String> params) {
        var query = new StringBuilder();
        for (var entry : params.entrySet()) {
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return query.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
