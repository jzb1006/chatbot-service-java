package com.jzb.chatbot.voice.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        when(sessionService.notifyDevice("device-1", "该喝水了，别忘了。")).thenReturn(true);

        var reminder = service.create("device-1", "喝水", "该喝水了，别忘了。", Instant.now().toString());

        assertThat(reminder.deviceId()).isEqualTo("device-1");
        assertThat(reminder.message()).isEqualTo("喝水");
        assertThat(reminder.dueText()).isEqualTo("该喝水了，别忘了。");
        then(sessionService).should(timeout(1000)).notifyDevice("device-1", "该喝水了，别忘了。");
    }

    @Test
    void shouldRetryWhenDeviceIsTemporarilyBusy() {
        when(sessionService.notifyDevice("device-1", "该喝水了，别忘了。"))
                .thenReturn(false)
                .thenReturn(true);

        service.create("device-1", "喝水", "该喝水了，别忘了。", Instant.now().toString());

        then(sessionService).should(timeout(1000).times(2)).notifyDevice("device-1", "该喝水了，别忘了。");
    }

    @Test
    void shouldRejectBlankOrTooLongDueText() {
        assertThatThrownBy(() -> service.create("device-1", "喝水", " ", Instant.now().toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dueText is required");
        assertThatThrownBy(() -> service.create("device-1", "喝水", "喝".repeat(81), Instant.now().toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dueText must not exceed 80 characters");
    }
}
