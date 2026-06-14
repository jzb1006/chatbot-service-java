package com.jzb.chatbot.device;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jzb.chatbot.device.dto.DeviceChatResponse;
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

    @Test
    void shouldReturnChatResponse() throws Exception {
        given(chatService.chat(any())).willReturn(new DeviceChatResponse("conv-1", "pong"));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"device_id":"device-1","conversation_id":"conv-1","message":"ping"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation_id").value("conv-1"))
                .andExpect(jsonPath("$.reply").value("pong"));
    }
}
