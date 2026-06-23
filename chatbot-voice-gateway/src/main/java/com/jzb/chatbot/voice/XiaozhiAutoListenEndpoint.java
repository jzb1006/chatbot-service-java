package com.jzb.chatbot.voice;

import com.jzb.chatbot.speech.StreamingOpusToPcmDecoder;
import com.jzb.chatbot.voice.protocol.XiaozhiAudioFrame;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Lightweight endpoint detector for auto listening mode.
 */
final class XiaozhiAutoListenEndpoint {

    enum Result {
        CONTINUE,
        END_OF_UTTERANCE
    }

    private final StreamingOpusToPcmDecoder decoder;
    private final long frameDurationMillis;
    private final long minSpeechMillis;
    private final long silenceMillis;
    private final double speechRmsThreshold;
    private long candidateSpeechMillis;
    private long silenceAfterSpeechMillis;
    private boolean speechStarted;

    XiaozhiAutoListenEndpoint(
            int sampleRate,
            int frameDurationMillis,
            XiaozhiAutoStopProperties properties
    ) {
        this.decoder = new StreamingOpusToPcmDecoder(sampleRate);
        this.frameDurationMillis = Math.max(1, frameDurationMillis);
        this.minSpeechMillis = properties.minSpeechDuration().toMillis();
        this.silenceMillis = properties.silenceDuration().toMillis();
        this.speechRmsThreshold = properties.speechRmsThreshold();
    }

    Result accept(XiaozhiAudioFrame frame) {
        if (frame == null || frame.payload() == null || frame.payload().length == 0) {
            return Result.CONTINUE;
        }
        var pcm = decoder.decode(ByteBuffer.wrap(frame.payload()));
        if (pcm.length == 0) {
            return Result.CONTINUE;
        }
        if (rms(pcm) >= speechRmsThreshold) {
            candidateSpeechMillis += frameDurationMillis;
            silenceAfterSpeechMillis = 0;
            if (candidateSpeechMillis >= minSpeechMillis) {
                speechStarted = true;
            }
            return Result.CONTINUE;
        }
        if (!speechStarted) {
            candidateSpeechMillis = 0;
            return Result.CONTINUE;
        }
        silenceAfterSpeechMillis += frameDurationMillis;
        return silenceAfterSpeechMillis >= silenceMillis ? Result.END_OF_UTTERANCE : Result.CONTINUE;
    }

    private double rms(byte[] pcm) {
        var input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        var sampleCount = 0;
        var sumSquares = 0.0;
        while (input.remaining() >= Short.BYTES) {
            var sample = input.getShort();
            sumSquares += (double) sample * sample;
            sampleCount++;
        }
        if (sampleCount == 0) {
            return 0.0;
        }
        return Math.sqrt(sumSquares / sampleCount) / Short.MAX_VALUE;
    }
}
