package com.jzb.chatbot.device.ota;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(XiaozhiOtaController.class)
class XiaozhiOtaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private XiaozhiOtaService otaService;

    @Test
    void shouldReturnOtaCheckResponse() throws Exception {
        given(otaService.check(any(), any())).willReturn(new ObjectMapper().readTree("""
                {
                  "server_time": {"timestamp": 1760000000000, "timezone_offset": 480},
                  "websocket": {"url": "ws://203.195.202.54:8766/xiaozhi/v1", "token": "device-token", "version": 3},
                  "firmware": {"version": "", "url": ""}
                }
                """));

        mockMvc.perform(post("/api/ota/check")
                        .header("Device-Id", "device-1")
                        .header("Client-Id", "client-1")
                        .header("Activation-Version", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.websocket.url").value("ws://203.195.202.54:8766/xiaozhi/v1"))
                .andExpect(jsonPath("$.websocket.version").value(3))
                .andExpect(jsonPath("$.server_time.timestamp").isNumber());
    }

    @Test
    void shouldReturnAcceptedWhenActivationIsPending() throws Exception {
        given(otaService.activate(any(), any())).willReturn(XiaozhiOtaService.ActivationStatus.PENDING);

        mockMvc.perform(post("/api/ota/check/activate")
                        .header("Device-Id", "device-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    void shouldReturnForbiddenWhenActivationIsRejected() throws Exception {
        given(otaService.activate(any(), any())).willReturn(XiaozhiOtaService.ActivationStatus.REJECTED);

        mockMvc.perform(post("/api/ota/check/activate")
                        .header("Device-Id", "device-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challenge\":\"bad\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("activation rejected"));
    }

    @Test
    void shouldRejectFirmwarePathTraversal() throws Exception {
        mockMvc.perform(get("/api/ota/firmware/../secret.bin"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid firmware path"));
    }
}
