package com.jzb.chatbot.speech;

/**
 * 腾讯云 SentenceRecognition 调用边界。
 * <p>
 * 隔离官方 SDK，便于单元测试替换。
 *
 * @author jiangzhibin
 * @since 2026-06-16 01:38:00
 */
interface TencentSentenceRecognitionApi {

    /**
     * 识别单轮音频并返回文本。
     *
     * @param request 识别请求
     * @return 识别文本
     */
    String recognize(TencentSentenceRecognitionRequest request);
}
