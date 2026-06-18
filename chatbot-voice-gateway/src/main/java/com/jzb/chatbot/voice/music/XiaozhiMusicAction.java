package com.jzb.chatbot.voice.music;

import com.jzb.chatbot.voice.hermes.HermesAgentEvent;

/**
 * 小智音乐动作。
 * <p>
 * 承载 Hermes agent 返回的音乐播放控制指令，Java 不从自然语言中推断音乐意图。
 *
 * @author jiangzhibin
 * @since 2026-06-18 00:00:00
 */
public record XiaozhiMusicAction(
        String action,
        String title,
        String artist,
        String mediaUrl,
        long positionSeconds,
        String confirmationText
) {

    public static XiaozhiMusicAction from(HermesAgentEvent event) {
        return new XiaozhiMusicAction(
                event.action(),
                event.title(),
                event.artist(),
                event.mediaUrl(),
                event.positionSeconds(),
                event.confirmationText()
        );
    }
}
