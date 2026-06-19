package com.jzb.chatbot.speech;

import com.tencent.asrv2.SpeechRecognizer;
import com.tencent.asrv2.SpeechRecognizerListener;
import com.tencent.asrv2.SpeechRecognizerRequest;
import com.tencent.asrv2.SpeechRecognizerResponse;
import com.tencent.core.ws.Credential;
import com.tencent.core.ws.SpeechClient;
import java.util.UUID;

/**
 * 腾讯云 SDK 实时 ASR 识别器工厂。
 *
 * @author jiangzhibin
 * @since 2026-06-17 00:00:00
 */
class TencentSdkRealtimeSpeechRecognizerFactory implements TencentRealtimeSpeechRecognizerFactory, AutoCloseable {

    private static final String WS_API_URL = "wss://asr.cloud.tencent.com/asr/v2/";
    private static final int PCM_VOICE_FORMAT = 1;
    private static final int EIGHT_KHZ_SAMPLE_RATE = 8000;

    private final SpeechClient speechClient = new SpeechClient(WS_API_URL);

    @Override
    public TencentRealtimeSpeechRecognizer create(
            TencentRealtimeSpeechToTextConfig config,
            TencentRealtimeSpeechRecognitionListener listener
    ) {
        var request = SpeechRecognizerRequest.init();
        request.setEngineModelType(config.engineModelType());
        request.setVoiceFormat(PCM_VOICE_FORMAT);
        if (config.sampleRate() == EIGHT_KHZ_SAMPLE_RATE) {
            request.setInputSampleRate(EIGHT_KHZ_SAMPLE_RATE);
        }
        request.setVoiceId(UUID.randomUUID().toString());
        var credential = new Credential(config.appId(), config.secretId(), config.secretKey());
        try {
            var sdkRecognizer = new SpeechRecognizer(speechClient, credential, request, sdkListener(listener));
            return new TencentSdkRealtimeSpeechRecognizer(sdkRecognizer);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create Tencent realtime ASR", exception);
        }
    }

    private SpeechRecognizerListener sdkListener(TencentRealtimeSpeechRecognitionListener listener) {
        return new SpeechRecognizerListener() {
            @Override
            public void onRecognitionResultChange(SpeechRecognizerResponse response) {
            }

            @Override
            public void onRecognitionStart(SpeechRecognizerResponse response) {
            }

            @Override
            public void onSentenceBegin(SpeechRecognizerResponse response) {
            }

            @Override
            public void onSentenceEnd(SpeechRecognizerResponse response) {
                listener.onSentenceEnd(text(response));
            }

            @Override
            public void onRecognitionComplete(SpeechRecognizerResponse response) {
                listener.onComplete(text(response));
            }

            @Override
            public void onFail(SpeechRecognizerResponse response) {
                listener.onFail(response == null ? "" : response.getMessage());
            }

            @Override
            public void onMessage(SpeechRecognizerResponse response) {
            }
        };
    }

    private String text(SpeechRecognizerResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getVoiceTextStr() == null) {
            return "";
        }
        return response.getResult().getVoiceTextStr();
    }

    @Override
    public void close() {
        speechClient.shutdown();
    }
}
