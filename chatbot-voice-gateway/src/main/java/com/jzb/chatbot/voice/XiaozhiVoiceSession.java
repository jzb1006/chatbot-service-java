package com.jzb.chatbot.voice;

import com.jzb.chatbot.speech.SpeechToTextAudioStream;
import com.jzb.chatbot.speech.StreamingOpusToPcmDecoder;
import com.jzb.chatbot.voice.bargein.XiaozhiBargeInTurn;
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
    private long asrTurnSequence;
    private AsrTurn asrTurn;
    private long bargeInTurnSequence;
    private XiaozhiBargeInTurn bargeInTurn;
    private String currentSpeakingText = "";
    private long currentSpeakingStartedAtEpochMillis;
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
        requestAbortLocked();
        cancelPlaybackLocked();
        terminateAsrStreamLocked();
        conversationSequence++;
        conversationId = defaultConversationId() + "-" + sessionId + "-" + conversationSequence;
        markIdleInternal();
        return conversationId;
    }

    public synchronized String clearConversation() {
        requestAbortLocked();
        cancelPlaybackLocked();
        terminateAsrStreamLocked();
        conversationId = defaultConversationId();
        conversationSequence = 0;
        markIdleInternal();
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

    public synchronized long markListening() {
        cancelPlaybackLocked();
        terminateAsrStreamLocked();
        clearBargeInTurnLocked();
        turnGeneration++;
        activePlaybackGeneration = NO_PLAYBACK_GENERATION;
        state = State.LISTENING;
        audioFrames.clear();
        clearCurrentSpeakingLocked();
        return turnGeneration;
    }

    public synchronized AsrTurn startAsrStream(int sampleRate) {
        cancelPlaybackLocked();
        terminateAsrStreamLocked();
        clearBargeInTurnLocked();
        turnGeneration++;
        activePlaybackGeneration = NO_PLAYBACK_GENERATION;
        asrTurn = new AsrTurn(
                ++asrTurnSequence,
                turnGeneration,
                deviceId(),
                conversationId(),
                new SpeechToTextAudioStream(),
                new StreamingOpusToPcmDecoder(sampleRate)
        );
        state = State.LISTENING;
        audioFrames.clear();
        clearCurrentSpeakingLocked();
        return asrTurn;
    }

    public synchronized long markProcessing() {
        activePlaybackGeneration = NO_PLAYBACK_GENERATION;
        clearBargeInTurnLocked();
        clearCurrentSpeakingLocked();
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
        clearBargeInTurnLocked();
        clearCurrentSpeakingLocked();
        state = State.PROCESSING;
        var frames = List.copyOf(audioFrames);
        audioFrames.clear();
        return new ProcessingAudio(turnGeneration, frames, true);
    }

    public synchronized long markSpeaking() {
        return beginRuntimePlayback();
    }

    /**
     * 在会话仍处于空闲状态时注册旧式播放控制器并进入播报状态。
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
        beginSpeakingPlayback();
        return true;
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
        clearBargeInTurnLocked();
        clearCurrentSpeakingLocked();
        audioFrames.clear();
    }

    private long beginSpeakingPlayback() {
        state = State.SPEAKING;
        activePlaybackGeneration = ++playbackGeneration;
        currentSpeakingStartedAtEpochMillis = System.currentTimeMillis();
        return activePlaybackGeneration;
    }

    public synchronized void requestAbort() {
        requestAbortLocked();
    }

    private void requestAbortLocked() {
        abortedTurnGeneration = turnGeneration;
        clearBargeInTurnLocked();
        clearCurrentSpeakingLocked();
    }

    public boolean abortRequested(long turnGeneration) {
        return abortedTurnGeneration == turnGeneration;
    }

    public void updatePlayback(XiaozhiTtsPlayback playback) {
        this.playback = playback;
    }

    /**
     * 在守卫仍有效时注册旧式播放控制器并进入播报状态。
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
            beginSpeakingPlayback();
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
        clearBargeInTurnLocked();
        clearCurrentSpeakingLocked();
        activePlaybackGeneration = NO_PLAYBACK_GENERATION;
    }

    public synchronized boolean hasPlayback(XiaozhiTtsPlayback expectedPlayback) {
        return playback == expectedPlayback && !expectedPlayback.cancelled();
    }

    public synchronized void markIdleIfPlayback(XiaozhiTtsPlayback expectedPlayback) {
        if (playback != expectedPlayback || expectedPlayback.cancelled()) {
            return;
        }
        playback = null;
        markIdleInternal();
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

    public void writeAudioFrameToAsr(XiaozhiAudioFrame frame) {
        var currentTurn = activeAsrTurn();
        if (currentTurn == null || frame == null) {
            return;
        }
        var pcm = currentTurn.opusDecoder().decode(ByteBuffer.wrap(frame.payload()));
        currentTurn.audioStream().write(pcm);
    }

    public synchronized XiaozhiBargeInTurn startBargeInTurn(int sampleRate) {
        if (state != State.SPEAKING || activePlaybackGeneration == NO_PLAYBACK_GENERATION) {
            return null;
        }
        clearBargeInTurnLocked();
        bargeInTurn = new XiaozhiBargeInTurn(
                ++bargeInTurnSequence,
                activePlaybackGeneration,
                deviceId(),
                new SpeechToTextAudioStream(),
                new StreamingOpusToPcmDecoder(sampleRate),
                System.currentTimeMillis()
        );
        return bargeInTurn;
    }

    public synchronized XiaozhiBargeInTurn activeBargeInTurn() {
        return bargeInTurn;
    }

    public synchronized boolean activeBargeInTurnMatches(XiaozhiBargeInTurn turn) {
        return bargeInTurn != null
                && bargeInTurn.matches(turn)
                && state == State.SPEAKING
                && activePlaybackGeneration == turn.playbackGeneration();
    }

    public synchronized boolean completeBargeInTurn(XiaozhiBargeInTurn turn) {
        if (bargeInTurn == null || !bargeInTurn.matches(turn)) {
            return false;
        }
        bargeInTurn.audioStream().complete();
        return true;
    }

    public synchronized boolean cancelPlaybackAndListenIfBargeInTurnActive(XiaozhiBargeInTurn turn) {
        if (!activeBargeInTurnMatches(turn)) {
            return false;
        }
        abortedTurnGeneration = turnGeneration;
        if (playback != null) {
            playback.cancel();
            playback = null;
        }
        clearBargeInTurnLocked();
        turnGeneration++;
        activePlaybackGeneration = NO_PLAYBACK_GENERATION;
        state = State.LISTENING;
        audioFrames.clear();
        clearCurrentSpeakingLocked();
        return true;
    }

    public void writeAudioFrameToBargeIn(XiaozhiAudioFrame frame) {
        var currentTurn = activeBargeInTurn();
        if (currentTurn == null || frame == null) {
            return;
        }
        var pcm = currentTurn.opusDecoder().decode(ByteBuffer.wrap(frame.payload()));
        currentTurn.audioStream().write(pcm);
    }

    public synchronized void clearBargeInTurn(XiaozhiBargeInTurn turn) {
        if (bargeInTurn == null || !bargeInTurn.matches(turn)) {
            return;
        }
        clearBargeInTurnLocked();
    }

    public synchronized void updateCurrentSpeakingText(String text) {
        currentSpeakingText = text == null ? "" : text;
    }

    public synchronized void appendCurrentSpeakingText(String text) {
        if (text != null && !text.isBlank()) {
            currentSpeakingText = currentSpeakingText + text;
        }
    }

    public synchronized String currentSpeakingText() {
        return currentSpeakingText;
    }

    public synchronized long currentSpeakingElapsedMillis() {
        if (currentSpeakingStartedAtEpochMillis <= 0) {
            return 0;
        }
        return System.currentTimeMillis() - currentSpeakingStartedAtEpochMillis;
    }

    private void clearBargeInTurnLocked() {
        if (bargeInTurn == null) {
            return;
        }
        bargeInTurn.audioStream().complete();
        bargeInTurn = null;
    }

    private void clearCurrentSpeakingLocked() {
        currentSpeakingText = "";
        currentSpeakingStartedAtEpochMillis = 0;
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

    /**
     * 仅当流式 ASR 回合仍处于监听态时切换到处理态。
     *
     * @param turn ASR 回合
     * @return true 表示当前回合已进入处理态
     */
    public synchronized boolean beginAsrTurnProcessing(AsrTurn turn) {
        if (asrTurn == null || !asrTurn.matches(turn)) {
            return false;
        }
        if (state == State.PROCESSING) {
            return true;
        }
        if (state != State.LISTENING) {
            return false;
        }
        activePlaybackGeneration = NO_PLAYBACK_GENERATION;
        state = State.PROCESSING;
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
        activePlaybackGeneration = NO_PLAYBACK_GENERATION;
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

    public record AsrTurn(
            long turnId,
            long turnGeneration,
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
