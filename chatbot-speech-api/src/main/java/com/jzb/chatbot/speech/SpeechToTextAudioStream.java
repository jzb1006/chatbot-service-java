package com.jzb.chatbot.speech;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ASR PCM 音频流。
 * <p>
 * 生产者写入 16-bit little-endian PCM，消费者按块读取，避免引入 Reactor 依赖。
 *
 * @author jiangzhibin
 * @since 2026-06-17 00:00:00
 */
public final class SpeechToTextAudioStream implements AutoCloseable {

    private static final byte[] END = new byte[0];
    private static final byte[] TIMEOUT = new byte[0];

    private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
    private final AtomicBoolean completed = new AtomicBoolean();

    public synchronized void write(byte[] pcm) {
        if (pcm == null || pcm.length == 0 || completed.get()) {
            return;
        }
        chunks.offer(pcm.clone());
    }

    public synchronized void complete() {
        if (completed.compareAndSet(false, true)) {
            chunks.offer(END);
        }
    }

    public byte[] take(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (completed.get() && chunks.isEmpty()) {
            return END;
        }
        try {
            var timeoutMillis = Math.max(1, timeout.toMillis());
            var chunk = chunks.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            return chunk == null ? TIMEOUT : chunk;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return END;
        }
    }

    public boolean isEnd(byte[] chunk) {
        return chunk == END;
    }

    @Override
    public void close() {
        complete();
    }
}
