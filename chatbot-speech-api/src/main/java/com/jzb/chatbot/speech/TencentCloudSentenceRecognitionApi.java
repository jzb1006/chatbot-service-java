package com.jzb.chatbot.speech;

import com.tencentcloudapi.asr.v20190614.AsrClient;
import com.tencentcloudapi.asr.v20190614.models.SentenceRecognitionRequest;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import java.util.UUID;

/**
 * 腾讯云官方 SDK SentenceRecognition 适配器。
 * <p>
 * 负责把项目内请求转换为腾讯云 ASR SDK 请求。
 *
 * @author jiangzhibin
 * @since 2026-06-16 01:38:00
 */
class TencentCloudSentenceRecognitionApi implements TencentSentenceRecognitionApi {

    private static final long SOURCE_TYPE_BASE64 = 1L;
    private static final long SUB_SERVICE_TYPE_SENTENCE = 2L;

    private final AsrClient client;

    TencentCloudSentenceRecognitionApi(TencentCloudSpeechToTextConfig config) {
        var credential = new Credential(config.secretId(), config.secretKey());
        var httpProfile = new HttpProfile();
        httpProfile.setEndpoint(config.endpoint());
        httpProfile.setConnTimeout((int) config.timeout().toSeconds());
        httpProfile.setReadTimeout((int) config.timeout().toSeconds());
        var clientProfile = new ClientProfile();
        clientProfile.setHttpProfile(httpProfile);
        this.client = new AsrClient(credential, config.region(), clientProfile);
    }

    @Override
    public String recognize(TencentSentenceRecognitionRequest request) {
        try {
            var sdkRequest = toSdkRequest(request);
            var response = client.SentenceRecognition(sdkRequest);
            return response.getResult();
        } catch (TencentCloudSDKException exception) {
            throw new IllegalStateException("Tencent Cloud ASR request failed", exception);
        }
    }

    static SentenceRecognitionRequest toSdkRequest(TencentSentenceRecognitionRequest request) {
        var sdkRequest = new SentenceRecognitionRequest();
        sdkRequest.setSourceType(SOURCE_TYPE_BASE64);
        sdkRequest.setSubServiceType(SUB_SERVICE_TYPE_SENTENCE);
        sdkRequest.setEngSerViceType(request.engineModelType());
        sdkRequest.setVoiceFormat(request.voiceFormat());
        if (isPcm8k(request)) {
            sdkRequest.setInputSampleRate((long) request.sampleRate());
        }
        sdkRequest.setUsrAudioKey(UUID.randomUUID().toString());
        sdkRequest.setData(request.audio());
        sdkRequest.setDataLen((long) request.audioBytes());
        return sdkRequest;
    }

    private static boolean isPcm8k(TencentSentenceRecognitionRequest request) {
        return "pcm".equalsIgnoreCase(request.voiceFormat()) && request.sampleRate() == 8000;
    }
}
