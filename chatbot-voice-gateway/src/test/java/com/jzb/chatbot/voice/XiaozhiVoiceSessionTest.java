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

    @Test
    void shouldStartBargeInTurnOnlyWhileSpeaking() {
        var session = new XiaozhiVoiceSession("session-1");
        session.updateHandshake(null, "device-1", null, 1);

        assertThat(session.startBargeInTurn(16_000)).isNull();

        var playbackGeneration = session.markSpeaking();
        var turn = session.startBargeInTurn(16_000);

        assertThat(turn).isNotNull();
        assertThat(turn.playbackGeneration()).isEqualTo(playbackGeneration);
        assertThat(session.activeBargeInTurn()).isSameAs(turn);
    }

    @Test
    void shouldClearBargeInTurnWhenMarkListening() {
        var session = new XiaozhiVoiceSession("session-1");
        session.updateHandshake(null, "device-1", null, 1);
        session.markSpeaking();
        session.startBargeInTurn(16_000);

        session.markListening();

        assertThat(session.activeBargeInTurn()).isNull();
        assertThat(session.state()).isEqualTo(XiaozhiVoiceSession.State.LISTENING);
    }

    @Test
    void shouldPrepareSessionEndPlaybackFromSpeaking() {
        var session = new XiaozhiVoiceSession("session-1");
        session.markSpeaking();
        session.updateCurrentSpeakingText("这是一段正在播放的回答。");

        assertThat(session.prepareSessionEndPlayback()).isTrue();

        assertThat(session.state()).isEqualTo(XiaozhiVoiceSession.State.PROCESSING);
        assertThat(session.currentSpeakingText()).isEmpty();
    }

    @Test
    void shouldIgnoreSessionEndPlaybackWhenAlreadyIdle() {
        var session = new XiaozhiVoiceSession("session-1");

        assertThat(session.prepareSessionEndPlayback()).isFalse();
        assertThat(session.state()).isEqualTo(XiaozhiVoiceSession.State.IDLE);
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
