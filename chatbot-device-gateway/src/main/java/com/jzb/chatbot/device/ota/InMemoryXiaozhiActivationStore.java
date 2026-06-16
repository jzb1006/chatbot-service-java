package com.jzb.chatbot.device.ota;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 内存版小智 OTA 激活存储。
 * <p>
 * 适用于首版薄网关，不提供跨进程持久化语义。
 *
 * @author jiangzhibin
 * @since 2026-06-16 13:32:00
 */
@Component
public class InMemoryXiaozhiActivationStore implements XiaozhiActivationStore {

    private final Map<String, ActivationRecord> records = new ConcurrentHashMap<>();

    @Override
    public String createChallenge(OtaDeviceIdentity identity, Duration ttl) {
        var challenge = UUID.randomUUID().toString();
        records.put(key(identity), new ActivationRecord(challenge, Instant.now().plus(ttl), false));
        return challenge;
    }

    @Override
    public boolean activate(OtaDeviceIdentity identity, String challenge) {
        var record = records.get(key(identity));
        if (record == null || record.expiresAt().isBefore(Instant.now())) {
            return false;
        }
        if (!record.challenge().equals(challenge)) {
            return false;
        }
        records.put(key(identity), new ActivationRecord(challenge, record.expiresAt(), true));
        return true;
    }

    @Override
    public boolean isActivated(OtaDeviceIdentity identity) {
        var record = records.get(key(identity));
        return record != null && record.activated();
    }

    @Override
    public void remove(OtaDeviceIdentity identity) {
        records.remove(key(identity));
    }

    private String key(OtaDeviceIdentity identity) {
        if (!identity.serialNumber().isBlank()) {
            return "serial:" + identity.serialNumber();
        }
        return "device:" + identity.deviceId();
    }

    private record ActivationRecord(String challenge, Instant expiresAt, boolean activated) {
    }
}
