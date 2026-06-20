package com.jzb.chatbot.voice.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;

import com.jzb.chatbot.voice.XiaozhiVoiceSessionService;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class XiaozhiReminderServiceTest {

    private final XiaozhiVoiceSessionService sessionService = mock(XiaozhiVoiceSessionService.class);
    private final XiaozhiReminderService service = new XiaozhiReminderService(sessionService);

    @Test
    void shouldNotifyDeviceWhenReminderIsDue() {
        var reminder = service.create("device-1", "提醒我喝水", Instant.now().toString());

        assertThat(reminder.deviceId()).isEqualTo("device-1");
        assertThat(reminder.message()).isEqualTo("提醒我喝水");
        then(sessionService).should(timeout(1000)).notifyDevice("device-1", "提醒我喝水");
    }
}
