package com.jzb.chatbot.voice.reminder;

import com.jzb.chatbot.voice.XiaozhiVoiceSessionService;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 小智提醒调度服务。
 * <p>
 * 保存进程内提醒任务，并在到期时通过当前在线 WebSocket 会话主动播报。
 *
 * @author jiangzhibin
 * @since 2026-06-17 17:39:00
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class XiaozhiReminderService {

    private final XiaozhiVoiceSessionService sessionService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            Runtime.getRuntime().availableProcessors(),
            Thread.ofVirtual().name("xiaozhi-reminder-", 0).factory()
    );
    private final ConcurrentHashMap<String, CreatedReminder> reminders = new ConcurrentHashMap<>();

    /**
     * 创建提醒任务。
     *
     * @param deviceId 设备 ID
     * @param message 提醒内容
     * @param remindAt ISO-8601 到期时间
     * @return 已创建提醒
     */
    public CreatedReminder create(String deviceId, String message, String remindAt) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId is required");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        var triggerAt = parseRemindAt(remindAt);
        var reminder = new CreatedReminder(UUID.randomUUID().toString(), deviceId, message, triggerAt);
        reminders.put(reminder.id(), reminder);
        scheduler.schedule(
                () -> fire(reminder),
                Math.max(0L, triggerAt.toEpochMilli() - Instant.now().toEpochMilli()),
                TimeUnit.MILLISECONDS
        );
        return reminder;
    }

    /**
     * 创建延迟提醒任务。
     *
     * @param deviceId 设备 ID
     * @param message 提醒内容
     * @param delaySeconds 延迟秒数
     * @return 已创建提醒
     */
    public CreatedReminder createAfter(String deviceId, String message, long delaySeconds) {
        if (delaySeconds < 0) {
            throw new IllegalArgumentException("delaySeconds must not be negative");
        }
        return create(deviceId, message, Instant.now().plusSeconds(delaySeconds).toString());
    }

    /**
     * 监听语音会话解析出的提醒请求。
     *
     * @param event 提醒请求事件
     */
    @EventListener
    public void handleReminderRequested(XiaozhiReminderRequestedEvent event) {
        createAfter(event.deviceId(), event.message(), event.delaySeconds());
    }

    private void fire(CreatedReminder reminder) {
        reminders.remove(reminder.id());
        var notified = sessionService.notifyDevice(reminder.deviceId(), reminder.message());
        if (!notified) {
            log.warn("xiaozhi reminder skipped because device is offline, reminderId={}, deviceId={}",
                    reminder.id(), reminder.deviceId());
            return;
        }
        log.info("xiaozhi reminder delivered, reminderId={}, deviceId={}, message={}",
                reminder.id(), reminder.deviceId(), reminder.message());
    }

    private Instant parseRemindAt(String remindAt) {
        if (remindAt == null || remindAt.isBlank()) {
            throw new IllegalArgumentException("remindAt is required");
        }
        try {
            return Instant.parse(remindAt);
        } catch (DateTimeParseException exception) {
            try {
                return java.time.OffsetDateTime.parse(remindAt).toInstant();
            } catch (DateTimeParseException ignored) {
                throw new IllegalArgumentException("remindAt must be ISO-8601 datetime");
            }
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * 已创建的提醒任务。
     *
     * @param id 提醒 ID
     * @param deviceId 设备 ID
     * @param message 提醒内容
     * @param remindAt 到期时间
     */
    public record CreatedReminder(String id, String deviceId, String message, Instant remindAt) {
    }
}
