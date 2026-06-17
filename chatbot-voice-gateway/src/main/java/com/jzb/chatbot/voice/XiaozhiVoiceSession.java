package com.jzb.chatbot.voice;

import com.jzb.chatbot.voice.protocol.XiaozhiAudioFrame;
import java.util.ArrayList;
import java.util.List;

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
    private volatile XiaozhiTtsPlayback playback;

    public XiaozhiVoiceSession(String sessionId) {
        this.sessionId = sessionId;
    }

    public String sessionId() {
        return sessionId;
    }

    public State state() {
        return state;
    }

    public int protocolVersion() {
        return protocolVersion;
    }

    public String authorization() {
        return authorization;
    }

    public String deviceId() {
        return deviceId == null || deviceId.isBlank() ? sessionId : deviceId;
    }

    public String clientId() {
        return clientId;
    }

    public String conversationId() {
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = defaultConversationId();
        }
        return conversationId;
    }

    public String startNewConversation() {
        conversationSequence++;
        conversationId = defaultConversationId() + "-" + sessionId + "-" + conversationSequence;
        audioFrames.clear();
        state = State.IDLE;
        return conversationId;
    }

    public String clearConversation() {
        conversationId = defaultConversationId();
        conversationSequence = 0;
        audioFrames.clear();
        state = State.IDLE;
        return conversationId;
    }

    public void updateProtocolVersion(int protocolVersion) {
        this.protocolVersion = protocolVersion <= 0 ? 1 : protocolVersion;
    }

    public void updateHandshake(String authorization, String deviceId, String clientId, int protocolVersion) {
        this.authorization = blankToNull(authorization);
        this.deviceId = blankToNull(deviceId);
        this.clientId = blankToNull(clientId);
        updateProtocolVersion(protocolVersion);
    }

    public void markListening() {
        state = State.LISTENING;
        audioFrames.clear();
    }

    public void markProcessing() {
        state = State.PROCESSING;
    }

    public void markSpeaking() {
        state = State.SPEAKING;
    }

    public void markIdle() {
        state = State.IDLE;
        audioFrames.clear();
    }

    public void updatePlayback(XiaozhiTtsPlayback playback) {
        this.playback = playback;
    }

    public XiaozhiTtsPlayback cancelPlayback() {
        var currentPlayback = playback;
        if (currentPlayback != null) {
            currentPlayback.cancel();
        }
        return currentPlayback;
    }

    public void addAudioFrame(XiaozhiAudioFrame frame) {
        audioFrames.add(frame);
    }

    public List<XiaozhiAudioFrame> drainAudioFrames() {
        var frames = List.copyOf(audioFrames);
        audioFrames.clear();
        return frames;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String defaultConversationId() {
        return "conv-" + deviceId();
    }
}
