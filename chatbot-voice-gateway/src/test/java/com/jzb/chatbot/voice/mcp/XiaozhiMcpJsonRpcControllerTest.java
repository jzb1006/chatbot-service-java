package com.jzb.chatbot.voice.mcp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(XiaozhiMcpJsonRpcController.class)
class XiaozhiMcpJsonRpcControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private XiaozhiMcpJsonRpcService jsonRpcService;

    @MockitoBean
    private XiaozhiMcpAdminAuth adminAuth;

    @Test
    void shouldReturnToolsListForHermes() throws Exception {
        var response = (ObjectNode) new ObjectMapper().readTree("""
                {"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"xiaozhi_list_online_devices"}]}}
                """);
        given(adminAuth.matchesHermes("Bearer hermes-token")).willReturn(true);
        given(jsonRpcService.handle(any())).willReturn(response);

        mockMvc.perform(post("/api/hermes/xiaozhi/mcp")
                        .header("Authorization", "Bearer hermes-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools[0].name").value("xiaozhi_list_online_devices"));
    }

    @Test
    void shouldRejectHermesWhenTokenIsInvalid() throws Exception {
        given(adminAuth.matchesHermes("Bearer bad")).willReturn(false);

        mockMvc.perform(post("/api/hermes/xiaozhi/mcp")
                        .header("Authorization", "Bearer bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("hermes mcp token required"));
    }
}
