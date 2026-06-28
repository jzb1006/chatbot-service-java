package com.jzb.chatbot.voice;

import com.jzb.chatbot.speech.StreamingOpusToPcmDecoder;
import com.jzb.chatbot.voice.protocol.XiaozhiAudioFrame;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Lightweight endpoint detector for auto listening mode.
 */
final class XiaozhiAutoListenEndpoint {

    private static final double RELATIVE_SILENCE_RATIO = 0.65;
    private static final double WINDOW_MIN_SILENCE_RATIO = 0.35;
    private static final double WINDOW_MAX_SPEECH_SPIKE_RATIO = 0.25;

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
    private final double[] trailingRms = new double[20];
    private long totalMillis;
    private long frameCount;
    private long candidateSpeechMillis;
    private long silenceAfterSpeechMillis;
    private long aboveThresholdFrames;
    private long belowThresholdFrames;
    private double peakRms;
    private double minRms = Double.POSITIVE_INFINITY;
    private double lastRms;
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
        lastRms = frameRms;
        peakRms = Math.max(peakRms, frameRms);
        minRms = Math.min(minRms, frameRms);
        trailingRms[(int) ((frameCount - 1) % trailingRms.length)] = frameRms;
        if (totalMillis >= maxDurationMillis) {
            return Result.MAX_DURATION_REACHED;
        }
        var aboveSpeechThreshold = frameRms >= speechRmsThreshold;
        if (!speechStarted && aboveSpeechThreshold) {
            aboveThresholdFrames++;
            candidateSpeechMillis += frameDurationMillis;
            silenceAfterSpeechMillis = 0;
            if (candidateSpeechMillis >= minSpeechMillis) {
                speechStarted = true;
            }
            return Result.CONTINUE;
        }
        if (aboveSpeechThreshold) {
            aboveThresholdFrames++;
        } else {
            belowThresholdFrames++;
        }
        if (!speechStarted) {
            candidateSpeechMillis = 0;
            return totalMillis >= noSpeechTimeoutMillis ? Result.NO_SPEECH_TIMEOUT : Result.CONTINUE;
        }
        if (!isSilenceAfterSpeech(frameRms, aboveSpeechThreshold)) {
            silenceAfterSpeechMillis = 0;
            return Result.CONTINUE;
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

    synchronized double minRms() {
        return minRms == Double.POSITIVE_INFINITY ? 0.0 : minRms;
    }

    synchronized double lastRms() {
        return lastRms;
    }

    synchronized double trailingMinRms() {
        return trailingRmsStats().min();
    }

    synchronized double trailingAvgRms() {
        return trailingRmsStats().avg();
    }

    synchronized long aboveThresholdFrames() {
        return aboveThresholdFrames;
    }

    synchronized long belowThresholdFrames() {
        return belowThresholdFrames;
    }

    synchronized long silenceAfterSpeechMillis() {
        return silenceAfterSpeechMillis;
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

    private RmsStats trailingRmsStats() {
        var sampleCount = Math.min(frameCount, trailingRms.length);
        if (sampleCount == 0) {
            return new RmsStats(0.0, 0.0, 0, 0);
        }
        var sum = 0.0;
        var min = Double.POSITIVE_INFINITY;
        var aboveThresholdCount = 0;
        for (var index = 0; index < sampleCount; index++) {
            var value = trailingRms[index];
            sum += value;
            min = Math.min(min, value);
            if (value >= speechRmsThreshold) {
                aboveThresholdCount++;
            }
        }
        return new RmsStats(min, sum / sampleCount, aboveThresholdCount, sampleCount);
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

    private boolean isSilenceAfterSpeech(double frameRms, boolean aboveSpeechThreshold) {
        if (!aboveSpeechThreshold) {
            return true;
        }
        var adaptiveSilenceThreshold = Math.max(speechRmsThreshold, peakRms * RELATIVE_SILENCE_RATIO);
        var stats = trailingRmsStats();
        var windowMinSilenceThreshold = Math.max(speechRmsThreshold, peakRms * WINDOW_MIN_SILENCE_RATIO);
        return stats.avg() < adaptiveSilenceThreshold
                && stats.min() < windowMinSilenceThreshold
                && stats.aboveThresholdRatio() <= WINDOW_MAX_SPEECH_SPIKE_RATIO;
    }

    private record RmsStats(double min, double avg, long aboveThresholdCount, long sampleCount) {

        private double aboveThresholdRatio() {
            if (sampleCount == 0) {
                return 0.0;
            }
            return (double) aboveThresholdCount / sampleCount;
        }
    }
}
