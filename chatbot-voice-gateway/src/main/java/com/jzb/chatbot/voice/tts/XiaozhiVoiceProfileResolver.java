package com.jzb.chatbot.voice.tts;

import com.jzb.chatbot.common.id.VoiceId;

/**
 * 小智设备语音配置解析器。
 * <p>
 * 当前阶段只解析全局默认配置，后续设备级配置可在不影响调用方的前提下扩展解析来源。
 *
 * @author jiangzhibin
 * @since 2026-06-17 21:49:00
 */
public class XiaozhiVoiceProfileResolver {

    private final XiaozhiVoiceProfile defaultProfile;

    /**
     * 创建小智设备语音配置解析器。
     *
     * @param defaultVoiceId 默认音色
     * @param defaultSpeed 默认语速
     * @param defaultPitch 默认音调
     */
    public XiaozhiVoiceProfileResolver(VoiceId defaultVoiceId, double defaultSpeed, double defaultPitch) {
        this.defaultProfile = new XiaozhiVoiceProfile(defaultVoiceId, defaultSpeed, defaultPitch);
    }

    /**
     * 解析设备语音配置。
     *
     * @param deviceId 设备标识
     * @return 设备语音配置
     */
    public XiaozhiVoiceProfile resolve(String deviceId) {
        return defaultProfile;
    }
}
