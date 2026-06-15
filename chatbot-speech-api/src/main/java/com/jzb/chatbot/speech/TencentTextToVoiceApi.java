package com.jzb.chatbot.speech;

/**
 * 腾讯云 TextToVoice 调用边界。
 * <p>
 * 隔离官方 SDK，便于单元测试替换。
 *
 * @author jiangzhibin
 * @since 2026-06-15 17:25:00
 */
interface TencentTextToVoiceApi {

    /**
     * 合成语音并返回 base64 PCM。
     *
     * @param request 合成请求
     * @return base64 音频
     */
    String synthesize(TencentTextToVoiceRequest request);
}
