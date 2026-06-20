package com.jzb.chatbot.voice.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.when;

import com.jzb.chatbot.voice.XiaozhiVoiceSessionService;
import java.time.Instant;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class XiaozhiReminderServiceTest {

    private final XiaozhiVoiceSessionService sessionService = mock(XiaozhiVoiceSessionService.class);
    private final XiaozhiReminderService service = new XiaozhiReminderService(
            sessionService,
            Executors.newSingleThreadScheduledExecutor(),
            10L
    );

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void shouldNotifyDeviceWhenReminderIsDue() {
        when(sessionService.notifyDevice("device-1", "提醒我喝水")).thenReturn(true);

        var reminder = service.create("device-1", "提醒我喝水", Instant.now().toString());

        assertThat(reminder.deviceId()).isEqualTo("device-1");
        assertThat(reminder.message()).isEqualTo("提醒我喝水");
        then(sessionService).should(timeout(1000)).notifyDevice("device-1", "提醒我喝水");
    }

    @Test
    void shouldRetryWhenDeviceIsTemporarilyBusy() {
        when(sessionService.notifyDevice("device-1", "提醒我喝水"))
                .thenReturn(false)
                .thenReturn(true);

        service.create("device-1", "提醒我喝水", Instant.now().toString());

        then(sessionService).should(timeout(1000).times(2)).notifyDevice("device-1", "提醒我喝水");
    }
}
