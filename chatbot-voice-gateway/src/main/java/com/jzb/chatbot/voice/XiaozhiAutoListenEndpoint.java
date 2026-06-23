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
        END_OF_UTTERANCE,
        NO_SPEECH_TIMEOUT,
        MAX_DURATION_REACHED
    }

    private final StreamingOpusToPcmDecoder decoder;
    private final long frameDurationMillis;
    private final long minSpeechMillis;
    private final long silenceMillis;
    private final long noSpeechTimeoutMillis;
    private final long maxDurationMillis;
    private final double speechRmsThreshold;
    private long totalMillis;
    private long frameCount;
    private long candidateSpeechMillis;
    private long silenceAfterSpeechMillis;
    private double peakRms;
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
        this.noSpeechTimeoutMillis = properties.noSpeechTimeout().toMillis();
        this.maxDurationMillis = properties.maxDuration().toMillis();
        this.speechRmsThreshold = properties.speechRmsThreshold();
    }

    synchronized Result accept(XiaozhiAudioFrame frame) {
        if (frame == null || frame.payload() == null || frame.payload().length == 0) {
            return Result.CONTINUE;
        }
        var pcm = decoder.decode(ByteBuffer.wrap(frame.payload()));
        if (pcm.length == 0) {
            return Result.CONTINUE;
        }
        frameCount++;
        totalMillis += frameDurationMillis;
        var frameRms = rms(pcm);
        peakRms = Math.max(peakRms, frameRms);
        if (totalMillis >= maxDurationMillis) {
            return Result.MAX_DURATION_REACHED;
        }
        if (frameRms >= speechRmsThreshold) {
            candidateSpeechMillis += frameDurationMillis;
            silenceAfterSpeechMillis = 0;
            if (candidateSpeechMillis >= minSpeechMillis) {
                speechStarted = true;
            }
            return Result.CONTINUE;
        }
        if (!speechStarted) {
            candidateSpeechMillis = 0;
            return totalMillis >= noSpeechTimeoutMillis ? Result.NO_SPEECH_TIMEOUT : Result.CONTINUE;
        }
        silenceAfterSpeechMillis += frameDurationMillis;
        return silenceAfterSpeechMillis >= silenceMillis ? Result.END_OF_UTTERANCE : Result.CONTINUE;
    }

    synchronized long frameCount() {
        return frameCount;
    }

    synchronized long totalMillis() {
        return totalMillis;
    }

    synchronized double peakRms() {
        return peakRms;
    }

    synchronized boolean speechStarted() {
        return speechStarted;
    }

    synchronized long noSpeechTimeoutMillis() {
        return noSpeechTimeoutMillis;
    }

    synchronized long maxDurationMillis() {
        return maxDurationMillis;
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
