package com.jzb.chatbot.voice.tts;

/**
 * 小智 TTS 播放结果。
 * <p>
 * 记录一次 TTS runtime 调用的播放状态和观测指标，供会话层统一输出回合完成日志。
 *
 * @author jiangzhibin
 * @since 2026-06-18 05:40:00
 */
public record XiaozhiTtsResult(boolean played, int sentenceCount, int ttsFrames, boolean cancelled) {
}
