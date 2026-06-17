package com.jzb.chatbot.voice;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jzb.chatbot.voice.protocol.XiaozhiMessageCodec;
import com.jzb.chatbot.voice.protocol.XiaozhiServerEventFactory;
import org.junit.jupiter.api.Test;

class XiaozhiVoiceSessionTest {

    private final XiaozhiMessageCodec codec = new XiaozhiMessageCodec(new ObjectMapper());
    private final XiaozhiServerEventFactory eventFactory = new XiaozhiServerEventFactory(new ObjectMapper());

    @Test
    void shouldCancelPlaybackWhenReplacingTurnState() {
        var session = new XiaozhiVoiceSession("session-1");

        var streamingPlayback = newPlayback(session);
        session.updatePlayback(streamingPlayback);
        session.startAsrStream(16_000);
        assertThat(streamingPlayback.cancelled()).isTrue();

        var newConversationPlayback = newPlayback(session);
        session.updatePlayback(newConversationPlayback);
        session.startNewConversation();
        assertThat(newConversationPlayback.cancelled()).isTrue();

        var clearConversationPlayback = newPlayback(session);
        session.updatePlayback(clearConversationPlayback);
        session.clearConversation();
        assertThat(clearConversationPlayback.cancelled()).isTrue();
    }

    @Test
    void shouldClearOnlyOwnedPlaybackWhenFinishingPlayback() {
        var session = new XiaozhiVoiceSession("session-1");
        var firstPlayback = newPlayback(session);
        var secondPlayback = newPlayback(session);

        assertThat(session.startNotificationPlayback(firstPlayback)).isTrue();
        assertThat(session.startNotificationPlayback(secondPlayback)).isFalse();
        assertThat(secondPlayback.cancelled()).isTrue();

        session.clearPlayback(secondPlayback);
        assertThat(session.hasPlayback(firstPlayback)).isTrue();

        session.markIdleIfPlayback(secondPlayback);
        assertThat(session.hasPlayback(firstPlayback)).isTrue();

        session.markIdleIfPlayback(firstPlayback);
        assertThat(session.state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
        assertThat(session.hasPlayback(firstPlayback)).isFalse();
    }

    private XiaozhiTtsPlayback newPlayback(XiaozhiVoiceSession session) {
        return new XiaozhiTtsPlayback(
                new TestWebSocketSession(session.sessionId()),
                session,
                codec,
                eventFactory
        );
    }
}
