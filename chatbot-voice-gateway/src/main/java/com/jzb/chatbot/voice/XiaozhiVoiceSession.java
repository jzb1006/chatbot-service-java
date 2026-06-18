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

    private static final long NO_PLAYBACK_GENERATION = -1L;

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
    private volatile long turnGeneration;
    private volatile long abortedTurnGeneration = -1;
    private long playbackGeneration;
    private long activePlaybackGeneration = NO_PLAYBACK_GENERATION;

    public XiaozhiVoiceSession(String sessionId) {
        this.sessionId = sessionId;
    }

    public String sessionId() {
        return sessionId;
    }

    public synchronized State state() {
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

    public synchronized String startNewConversation() {
        conversationSequence++;
        conversationId = defaultConversationId() + "-" + sessionId + "-" + conversationSequence;
        markIdleInternal();
        return conversationId;
    }

    public synchronized String clearConversation() {
        conversationId = defaultConversationId();
        conversationSequence = 0;
        markIdleInternal();
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

    public synchronized long markListening() {
        turnGeneration++;
        activePlaybackGeneration = NO_PLAYBACK_GENERATION;
        state = State.LISTENING;
        audioFrames.clear();
        return turnGeneration;
    }

    public synchronized long markProcessing() {
        activePlaybackGeneration = NO_PLAYBACK_GENERATION;
        state = State.PROCESSING;
        return turnGeneration;
    }

    /**
     * 仅当当前处于监听态时，原子切换到处理态并取出已缓存音频帧。
     *
     * @return 处理音频帧快照，accepted 为 false 表示当前状态不接受 stop
     */
    public synchronized ProcessingAudio tryDrainAudioFramesForProcessing() {
        if (state != State.LISTENING) {
            return new ProcessingAudio(turnGeneration, List.of(), false);
        }
        activePlaybackGeneration = NO_PLAYBACK_GENERATION;
        state = State.PROCESSING;
        var frames = List.copyOf(audioFrames);
        audioFrames.clear();
        return new ProcessingAudio(turnGeneration, frames, true);
    }

    public synchronized long markSpeaking() {
        return beginRuntimePlayback();
    }

    public synchronized long tryBeginNotificationPlayback() {
        if (state != State.IDLE) {
            return NO_PLAYBACK_GENERATION;
        }
        return beginSpeakingPlayback();
    }

    public synchronized long beginRuntimePlayback() {
        return beginRuntimePlayback(null);
    }

    public synchronized long beginRuntimePlayback(Long expectedPlaybackGeneration) {
        if (expectedPlaybackGeneration != null) {
            return playbackActive(expectedPlaybackGeneration) ? expectedPlaybackGeneration : NO_PLAYBACK_GENERATION;
        }
        if (state == State.LISTENING) {
            return NO_PLAYBACK_GENERATION;
        }
        if (state == State.SPEAKING && activePlaybackGeneration != NO_PLAYBACK_GENERATION) {
            return NO_PLAYBACK_GENERATION;
        }
        return beginSpeakingPlayback();
    }

    public synchronized boolean playbackActive(long generation) {
        return state == State.SPEAKING && activePlaybackGeneration == generation;
    }

    public synchronized boolean turnActive(long generation) {
        return turnGeneration == generation && abortedTurnGeneration != generation && state == State.PROCESSING;
    }

    /**
     * 判断普通语音回合是否已经被新的状态或会话切换取消。
     *
     * @param generation 回合代际
     * @return true 表示该普通回合已失效
     */
    public synchronized boolean regularTurnCancelled(long generation) {
        return abortedTurnGeneration == generation || turnGeneration != generation || state == State.LISTENING;
    }

    /**
     * 仅当普通语音回合仍未被取消时，在会话锁内执行动作。
     *
     * @param generation 回合代际
     * @param action 待执行动作
     * @return true 表示动作已执行
     */
    public synchronized boolean runIfRegularTurnNotCancelled(long generation, Runnable action) {
        if (regularTurnCancelled(generation)) {
            return false;
        }
        action.run();
        return true;
    }

    public synchronized boolean completePlayback(long generation) {
        if (!playbackActive(generation)) {
            return false;
        }
        markIdleInternal();
        return true;
    }

    public synchronized boolean markIdleIfTurnActive(long generation) {
        if (!turnActive(generation)) {
            return false;
        }
        markIdleInternal();
        return true;
    }

    public synchronized void markIdle() {
        markIdleInternal();
    }

    private void markIdleInternal() {
        state = State.IDLE;
        activePlaybackGeneration = NO_PLAYBACK_GENERATION;
        audioFrames.clear();
    }

    private long beginSpeakingPlayback() {
        state = State.SPEAKING;
        activePlaybackGeneration = ++playbackGeneration;
        return activePlaybackGeneration;
    }

    public void requestAbort() {
        abortedTurnGeneration = turnGeneration;
    }

    public boolean abortRequested(long turnGeneration) {
        return abortedTurnGeneration == turnGeneration;
    }

    public void updatePlayback(XiaozhiTtsPlayback playback) {
        this.playback = playback;
    }

    public void clearPlayback(XiaozhiTtsPlayback playback) {
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

    public synchronized void addAudioFrame(XiaozhiAudioFrame frame) {
        audioFrames.add(frame);
    }

    /**
     * 仅当当前仍处于监听态时写入音频帧。
     *
     * @param frame 音频帧
     * @return true 表示已写入
     */
    public synchronized boolean addAudioFrameIfListening(XiaozhiAudioFrame frame) {
        if (state != State.LISTENING) {
            return false;
        }
        audioFrames.add(frame);
        return true;
    }

    public synchronized List<XiaozhiAudioFrame> drainAudioFrames() {
        var frames = List.copyOf(audioFrames);
        audioFrames.clear();
        return frames;
    }

    /**
     * 待处理音频帧快照。
     *
     * @param turnGeneration 回合代际
     * @param frames 音频帧列表
     * @param accepted 是否接受本次处理
     */
    public record ProcessingAudio(long turnGeneration, List<XiaozhiAudioFrame> frames, boolean accepted) {
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String defaultConversationId() {
        return "conv-" + deviceId();
    }
}
