package com.jzb.chatbot.voice;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

class XiaozhiWebSocketConfigTest {

    @Test
    void shouldRegisterFirmwareCompatibleWebSocketPaths() {
        var handler = mock(XiaozhiWebSocketHandler.class);
        var registry = new RecordingWebSocketHandlerRegistry();

        new XiaozhiWebSocketConfig(handler).registerWebSocketHandlers(registry);

        Assertions.assertThat(registry.paths())
                .containsExactly("/xiaozhi/v1", "/ws/xiaozhi/v1", "/ws/xiaozhi/v1/");
        verify(registry.registration()).setAllowedOrigins("*");
    }

    private static class RecordingWebSocketHandlerRegistry implements WebSocketHandlerRegistry {

        private final WebSocketHandlerRegistration registration = mock(WebSocketHandlerRegistration.class);
        private List<String> paths = List.of();

        @Override
        public WebSocketHandlerRegistration addHandler(WebSocketHandler handler, String... paths) {
            this.paths = List.of(paths);
            return registration;
        }

        private List<String> paths() {
            return paths;
        }

        private WebSocketHandlerRegistration registration() {
            return registration;
        }
    }
}
