package com.jzb.chatbot.speech;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TencentStreamingTtsSignerTest {

    @Test
    void shouldBuildSignedStreamWebSocketUrl() {
        var config = new TencentStreamingTextToSpeechConfig(
                1300000000,
                "secret-id",
                "secret-key",
                "101001",
                "pcm",
                16000,
                0.0,
                0.0,
                Duration.ofSeconds(30)
        );
        var clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);
        var signer = new TencentStreamingTtsSigner(config, clock);

        var uri = signer.sign("session-1");

        assertThat(uri.toString()).startsWith("wss://tts.cloud.tencent.com/stream_wsv2?");
        assertThat(uri.getQuery()).contains(
                "Action=TextToStreamAudioWSv2",
                "AppId=1300000000",
                "Codec=pcm",
                "SampleRate=16000",
                "SecretId=secret-id",
                "SessionId=session-1",
                "VoiceType=101001",
                "Speed=0.0",
                "Signature="
        );
        assertThat(uri.getQuery()).contains("Expired=1700003600", "Timestamp=1700000000");
    }

    @Test
    void shouldMapTextToSpeechSpeedToTencentSpeed() {
        assertThat(TencentStreamingTtsSigner.toTencentSpeed(0.6)).isEqualTo(-2.0);
        assertThat(TencentStreamingTtsSigner.toTencentSpeed(0.8)).isEqualTo(-1.0);
        assertThat(TencentStreamingTtsSigner.toTencentSpeed(1.0)).isEqualTo(0.0);
        assertThat(TencentStreamingTtsSigner.toTencentSpeed(1.2)).isEqualTo(1.0);
        assertThat(TencentStreamingTtsSigner.toTencentSpeed(1.5)).isEqualTo(2.0);
        assertThat(TencentStreamingTtsSigner.toTencentSpeed(9.0)).isEqualTo(6.0);
    }
}
