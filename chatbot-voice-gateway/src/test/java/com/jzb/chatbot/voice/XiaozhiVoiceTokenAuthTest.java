package com.jzb.chatbot.voice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class XiaozhiVoiceTokenAuthTest {

    @Test
    void shouldNotRequireTokenWhenExpectedTokenIsBlank() {
        var tokenAuth = new XiaozhiVoiceTokenAuth("");

        assertThat(tokenAuth.required()).isFalse();
        assertThat(tokenAuth.matches(null)).isTrue();
    }

    @Test
    void shouldAcceptBearerTokenCaseInsensitively() {
        var tokenAuth = new XiaozhiVoiceTokenAuth("expected-token");

        assertThat(tokenAuth.matches("bearer expected-token")).isTrue();
    }

    @Test
    void shouldAcceptRawToken() {
        var tokenAuth = new XiaozhiVoiceTokenAuth("expected-token");

        assertThat(tokenAuth.matches("expected-token")).isTrue();
    }

    @Test
    void shouldRejectMissingOrWrongTokenWhenRequired() {
        var tokenAuth = new XiaozhiVoiceTokenAuth("expected-token");

        assertThat(tokenAuth.matches(null)).isFalse();
        assertThat(tokenAuth.matches("wrong-token")).isFalse();
    }
}
