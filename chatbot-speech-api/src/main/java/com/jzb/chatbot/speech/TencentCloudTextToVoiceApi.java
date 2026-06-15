package com.jzb.chatbot.speech;

import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.tts.v20190823.TtsClient;
import com.tencentcloudapi.tts.v20190823.models.TextToVoiceRequest;
import java.util.UUID;

/**
 * 腾讯云官方 SDK TextToVoice 适配器。
 * <p>
 * 负责把项目内请求转换为腾讯云 TTS SDK 请求。
 *
 * @author jiangzhibin
 * @since 2026-06-15 17:25:00
 */
class TencentCloudTextToVoiceApi implements TencentTextToVoiceApi {

    private final TtsClient client;

    TencentCloudTextToVoiceApi(TencentCloudTextToSpeechConfig config) {
        var credential = new Credential(config.secretId(), config.secretKey());
        var httpProfile = new HttpProfile();
        httpProfile.setEndpoint(config.endpoint());
        httpProfile.setConnTimeout((int) config.timeout().toSeconds());
        httpProfile.setReadTimeout((int) config.timeout().toSeconds());
        var clientProfile = new ClientProfile();
        clientProfile.setHttpProfile(httpProfile);
        this.client = new TtsClient(credential, config.region(), clientProfile);
    }

    @Override
    public String synthesize(TencentTextToVoiceRequest request) {
        try {
            var sdkRequest = new TextToVoiceRequest();
            sdkRequest.setText(request.text());
            sdkRequest.setSessionId(UUID.randomUUID().toString());
            sdkRequest.setVoiceType(Long.parseLong(request.voiceType()));
            sdkRequest.setCodec(request.codec());
            sdkRequest.setSampleRate((long) request.sampleRate());
            sdkRequest.setPrimaryLanguage(1L);
            var response = client.TextToVoice(sdkRequest);
            return response.getAudio();
        } catch (TencentCloudSDKException exception) {
            throw new IllegalStateException("Tencent Cloud TTS request failed", exception);
        }
    }
}
