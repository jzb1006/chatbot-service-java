package com.jzb.chatbot.device;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jzb.chatbot.device.config.DeviceGatewayConfig;
import com.jzb.chatbot.device.config.DeviceGatewayConfigStore;
import com.jzb.chatbot.device.dto.DeviceChatResponse;
import com.jzb.chatbot.device.dto.DeviceChatStreamResponse;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DeviceChatController.class)
class DeviceChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeviceChatService chatService;

    @MockitoBean
    private DeviceGatewayConfigStore configStore;

    @Test
    void shouldReturnChatResponseWithLegacyAnswerField() throws Exception {
        given(configStore.get()).willReturn(DeviceGatewayConfig.defaults());
        given(chatService.chat(any(), any())).willReturn(new DeviceChatResponse("device-1", "conv-1", "pong"));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"device_id":"device-1","conversation_id":"conv-1","prompt":"ping"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.device_id").value("device-1"))
                .andExpect(jsonPath("$.conversation_id").value("conv-1"))
                .andExpect(jsonPath("$.answer").value("pong"));
    }

    @Test
    void shouldReturnStreamChatResponseWithConversationEvent() throws Exception {
        given(configStore.get()).willReturn(DeviceGatewayConfig.defaults());
        given(chatService.streamChat(any(), any())).willReturn(new DeviceChatStreamResponse(
                "device-1",
                "conv-1",
                Stream.of("event: message\ndata: {\"answer\":\"pong\"}\n\n")
        ));

        var result = mockMvc.perform(post("/api/chat/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"device_id":"device-1","conversation_id":"conv-1","prompt":"ping"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string("""
                        event: conversation
                        data: {"device_id":"device-1","conversation_id":"conv-1"}

                        event: message
                        data: {"answer":"pong"}

                        """));
    }

    @Test
    void shouldEscapeConversationEventJsonInStreamChatResponse() throws Exception {
        given(configStore.get()).willReturn(DeviceGatewayConfig.defaults());
        given(chatService.streamChat(any(), any())).willReturn(new DeviceChatStreamResponse(
                "device\n1",
                "conv\"1",
                Stream.of("event: done\ndata: {}\n\n")
        ));

        var result = mockMvc.perform(post("/api/chat/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"device_id":"device-1","conversation_id":"conv-1","prompt":"ping"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string("""
                        event: conversation
                        data: {"device_id":"device\\n1","conversation_id":"conv\\"1"}

                        event: done
                        data: {}

                        """));
    }

    @Test
    void shouldCreateConversationId() throws Exception {
        given(configStore.get()).willReturn(DeviceGatewayConfig.defaults());
        given(chatService.resolveDeviceId("device-1")).willReturn("device-1");
        given(chatService.createConversationId()).willReturn("generated-conversation-id");

        mockMvc.perform(post("/api/conversations/new")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"device_id":"device-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.device_id").value("device-1"))
                .andExpect(jsonPath("$.conversation_id").isNotEmpty());
    }

    @Test
    void shouldRejectMissingDeviceTokenWhenConfigured() throws Exception {
        given(configStore.get()).willReturn(DeviceGatewayConfig.defaults().withDeviceToken("expected-token"));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"device_id":"device-1","conversation_id":"conv-1","prompt":"ping"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("device token required"));
    }

    @Test
    void shouldRejectMissingDeviceTokenForStreamChatWhenConfigured() throws Exception {
        given(configStore.get()).willReturn(DeviceGatewayConfig.defaults().withDeviceToken("expected-token"));

        mockMvc.perform(post("/api/chat/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"device_id":"device-1","conversation_id":"conv-1","prompt":"ping"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("device token required"));
    }

    @Test
    void shouldRejectWrongDeviceTokenWhenConfigured() throws Exception {
        given(configStore.get()).willReturn(DeviceGatewayConfig.defaults().withDeviceToken("expected-token"));

        mockMvc.perform(post("/api/chat")
                        .header("X-Device-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"device_id":"device-1","conversation_id":"conv-1","prompt":"ping"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("device token required"));
    }

    @Test
    void shouldRejectMissingPrompt() throws Exception {
        given(configStore.get()).willReturn(DeviceGatewayConfig.defaults());
        given(chatService.chat(any(), any())).willThrow(new InvalidDeviceChatRequestException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "prompt is required"
        ));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"device_id":"device-1","conversation_id":"conv-1"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("prompt is required"));
    }

    @Test
    void shouldRejectTooLongPrompt() throws Exception {
        given(configStore.get()).willReturn(DeviceGatewayConfig.defaults());
        given(chatService.chat(any(), any())).willThrow(new InvalidDeviceChatRequestException(
                org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
                "prompt too long"
        ));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"device_id":"device-1","conversation_id":"conv-1","prompt":"too-long"}
                                """))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("prompt too long"));
    }

    @Test
    void shouldReturnJsonErrorWhenHermesFails() throws Exception {
        given(configStore.get()).willReturn(DeviceGatewayConfig.defaults());
        given(chatService.chat(any(), any())).willThrow(new IllegalStateException("Hermes HTTP 500: failed"));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"device_id":"device-1","conversation_id":"conv-1","prompt":"ping"}
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Hermes HTTP 500: failed"));
    }
}
