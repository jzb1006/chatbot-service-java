package com.jzb.chatbot.voice;

import com.jzb.chatbot.speech.SpeechToTextAudioStream;
import com.jzb.chatbot.speech.StreamingOpusToPcmDecoder;
import com.jzb.chatbot.voice.protocol.XiaozhiAudioFrame;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 小智语音会话状态。
 * <p>
 * 保存单个 WebSocket 连接上的协议版本和当前语音回合状态。
 *
 * @author jiangzhibin
 * @since 2026-06-14 20:48:00
 */
public class XiaozhiVoiceSession {

    /**
     * 语音会话状态。
     */
    public enum State {
        IDLE,
        LISTENING,
        PROCESSING,
        SPEAKING
    }

    private final String sessionId;
    private final List<XiaozhiAudioFrame> audioFrames = new ArrayList<>();
    private State state = State.IDLE;
    private int protocolVersion = 1;
    private String authorization;
    private String deviceId;
    private String clientId;
    private String conversationId;
    private long conversationSequence;
    private long asrTurnSequence;
    private AsrTurn asrTurn;
    private volatile XiaozhiTtsPlayback playback;

    public XiaozhiVoiceSession(String sessionId) {
        this.sessionId = sessionId;
    }

    public String sessionId() {
        return sessionId;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized int protocolVersion() {
        return protocolVersion;
    }

    public synchronized String authorization() {
        return authorization;
    }

    public synchronized String deviceId() {
        return deviceId == null || deviceId.isBlank() ? sessionId : deviceId;
    }

    public synchronized String clientId() {
        return clientId;
    }

    public synchronized String conversationId() {
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = defaultConversationId();
        }
        return conversationId;
    }

    public synchronized String startNewConversation() {
        cancelPlaybackLocked();
        terminateAsrStreamLocked();
        conversationSequence++;
        conversationId = defaultConversationId() + "-" + sessionId + "-" + conversationSequence;
        audioFrames.clear();
        state = State.IDLE;
        return conversationId;
    }

    public synchronized String clearConversation() {
        cancelPlaybackLocked();
        terminateAsrStreamLocked();
        conversationId = defaultConversationId();
        conversationSequence = 0;
        audioFrames.clear();
        state = State.IDLE;
        return conversationId;
    }

    public synchronized void updateProtocolVersion(int protocolVersion) {
        this.protocolVersion = protocolVersion <= 0 ? 1 : protocolVersion;
    }

    public synchronized void updateHandshake(String authorization, String deviceId, String clientId, int protocolVersion) {
        this.authorization = blankToNull(authorization);
        this.deviceId = blankToNull(deviceId);
        this.clientId = blankToNull(clientId);
        updateProtocolVersion(protocolVersion);
    }

    public synchronized void markListening() {
        cancelPlaybackLocked();
        terminateAsrStreamLocked();
        state = State.LISTENING;
        audioFrames.clear();
    }

    public synchronized AsrTurn startAsrStream(int sampleRate) {
        cancelPlaybackLocked();
        terminateAsrStreamLocked();
        asrTurn = new AsrTurn(
                ++asrTurnSequence,
                deviceId(),
                conversationId(),
                new SpeechToTextAudioStream(),
                new StreamingOpusToPcmDecoder(sampleRate)
        );
        state = State.LISTENING;
        return asrTurn;
    }

    public synchronized void markProcessing() {
        state = State.PROCESSING;
    }

    public synchronized void markSpeaking() {
        state = State.SPEAKING;
    }

    /**
     * 在会话仍处于空闲状态时注册播放控制器并进入播报状态。
     *
     * @param playback 播放控制器
     * @return true 表示已注册播放控制器
     */
    public synchronized boolean startNotificationPlayback(XiaozhiTtsPlayback playback) {
        if (state != State.IDLE || this.playback != null) {
            playback.cancel();
            return false;
        }
        this.playback = playback;
        state = State.SPEAKING;
        return true;
    }

    public synchronized void markIdle() {
        cancelPlaybackLocked();
        state = State.IDLE;
        audioFrames.clear();
    }

    public void updatePlayback(XiaozhiTtsPlayback playback) {
        this.playback = playback;
    }

    /**
     * 在守卫仍有效时注册播放控制器并进入播报状态。
     *
     * @param playback 播放控制器
     * @param activeSupplier 当前回合有效性判断
     * @return true 表示已注册播放控制器
     */
    public boolean startPlaybackIfActive(
            XiaozhiTtsPlayback playback,
            BooleanSupplier activeSupplier
    ) {
        synchronized (this) {
            if (!activeSupplier.getAsBoolean()) {
                playback.cancel();
                return false;
            }
            this.playback = playback;
            state = State.SPEAKING;
            return true;
        }
    }

    public synchronized void clearPlayback(XiaozhiTtsPlayback playback) {
        if (this.playback == playback) {
            this.playback = null;
        }
    }

    public XiaozhiTtsPlayback cancelPlayback() {
        var currentPlayback = playback;
        if (currentPlayback != null) {
            currentPlayback.cancel();
        }
        return currentPlayback;
    }

    private void cancelPlaybackLocked() {
        if (playback != null) {
            playback.cancel();
            playback = null;
        }
    }

    public synchronized boolean hasPlayback(XiaozhiTtsPlayback expectedPlayback) {
        return playback == expectedPlayback && !expectedPlayback.cancelled();
    }

    public synchronized void markIdleIfPlayback(XiaozhiTtsPlayback expectedPlayback) {
        if (playback != expectedPlayback || expectedPlayback.cancelled()) {
            return;
        }
        playback = null;
        state = State.IDLE;
        audioFrames.clear();
    }

    public synchronized void addAudioFrame(XiaozhiAudioFrame frame) {
        audioFrames.add(frame);
    }

    public void writeAudioFrameToAsr(XiaozhiAudioFrame frame) {
        var currentTurn = activeAsrTurn();
        if (currentTurn == null || frame == null) {
            return;
        }
        var pcm = currentTurn.opusDecoder().decode(ByteBuffer.wrap(frame.payload()));
        currentTurn.audioStream().write(pcm);
    }

    public synchronized AsrTurn completeAsrStream() {
        if (asrTurn != null) {
            asrTurn.audioStream().complete();
        }
        return asrTurn;
    }

    public synchronized void clearAsrStream(AsrTurn turn) {
        if (asrTurn == null || !asrTurn.matches(turn)) {
            return;
        }
        asrTurn.audioStream().close();
        asrTurn = null;
    }

    public synchronized void terminateAsrStream() {
        terminateAsrStreamLocked();
    }

    private void terminateAsrStreamLocked() {
        if (asrTurn == null) {
            return;
        }
        asrTurn.audioStream().complete();
        asrTurn = null;
    }

    public synchronized boolean isActiveAsrTurn(AsrTurn turn) {
        return asrTurn != null && asrTurn.matches(turn);
    }

    public synchronized boolean isCurrentAsrTurn(AsrTurn turn) {
        return isActiveAsrTurn(turn) && turn.matchesContext(deviceId(), conversationId());
    }

    public synchronized boolean clearAsrStreamIfListening(AsrTurn turn) {
        if (state != State.LISTENING || asrTurn == null || !asrTurn.matches(turn)) {
            return false;
        }
        asrTurn.audioStream().close();
        asrTurn = null;
        state = State.IDLE;
        audioFrames.clear();
        return true;
    }

    public synchronized void markIdleIfAsrTurn(AsrTurn turn) {
        if (asrTurn == null || !asrTurn.matches(turn)) {
            return;
        }
        asrTurn.audioStream().close();
        asrTurn = null;
        state = State.IDLE;
        audioFrames.clear();
    }

    public synchronized boolean hasActiveAsrStream() {
        return asrTurn != null;
    }

    public synchronized List<XiaozhiAudioFrame> drainAudioFrames() {
        var frames = List.copyOf(audioFrames);
        audioFrames.clear();
        return frames;
    }

    private synchronized AsrTurn activeAsrTurn() {
        return asrTurn;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String defaultConversationId() {
        return "conv-" + deviceId();
    }

    public record AsrTurn(
            long turnId,
            String deviceId,
            String conversationId,
            SpeechToTextAudioStream audioStream,
            StreamingOpusToPcmDecoder opusDecoder
    ) {

        private boolean matches(AsrTurn other) {
            return other != null && turnId == other.turnId && audioStream == other.audioStream;
        }

        private boolean matchesContext(String currentDeviceId, String currentConversationId) {
            return deviceId.equals(currentDeviceId) && conversationId.equals(currentConversationId);
        }
    }

}
