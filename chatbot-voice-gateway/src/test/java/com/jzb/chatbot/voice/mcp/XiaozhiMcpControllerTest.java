package com.jzb.chatbot.voice.mcp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(XiaozhiMcpController.class)
class XiaozhiMcpControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private XiaozhiMcpBridge bridge;

    @MockitoBean
    private XiaozhiMcpAdminAuth adminAuth;

    @Test
    void shouldListDevicesWithMcpReadiness() throws Exception {
        given(adminAuth.matches("admin-token")).willReturn(true);
        given(bridge.onlineDevices()).willReturn(List.of(
                new XiaozhiMcpBridge.DeviceMcpSession("device-1", true),
                new XiaozhiMcpBridge.DeviceMcpSession("device-2", false)
        ));

        mockMvc.perform(get("/api/xiaozhi/devices")
                        .header("X-MCP-Admin-Token", "admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devices[0]").value("device-1"))
                .andExpect(jsonPath("$.devices[1]").value("device-2"))
                .andExpect(jsonPath("$.deviceSessions[0].deviceId").value("device-1"))
                .andExpect(jsonPath("$.deviceSessions[0].mcpReady").value(true))
                .andExpect(jsonPath("$.deviceSessions[1].deviceId").value("device-2"))
                .andExpect(jsonPath("$.deviceSessions[1].mcpReady").value(false));
    }

    @Test
    void shouldRejectMissingAdminTokenWhenRequired() throws Exception {
        given(adminAuth.matches("")).willReturn(false);

        mockMvc.perform(post("/api/xiaozhi/devices/device-1/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("mcp admin token required"));
    }

    @Test
    void shouldSendMcpPayloadToDevice() throws Exception {
        given(adminAuth.matches("admin-token")).willReturn(true);
        given(bridge.send(eq("device-1"), any())).willReturn(true);

        mockMvc.perform(post("/api/xiaozhi/devices/device-1/mcp")
                        .header("X-MCP-Admin-Token", "admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("sent"));
    }

    @Test
    void shouldReturnDeviceOfflineWhenBridgeCannotSend() throws Exception {
        given(adminAuth.matches("admin-token")).willReturn(true);
        given(bridge.send(eq("device-1"), any())).willReturn(false);

        mockMvc.perform(post("/api/xiaozhi/devices/device-1/mcp")
                        .header("X-MCP-Admin-Token", "admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("device offline"));
    }

    @Test
    void shouldReturnGatewayTimeoutWhenRpcFutureTimesOut() throws Exception {
        given(adminAuth.matches("admin-token")).willReturn(true);
        var future = new CompletableFuture<JsonNode>();
        future.completeExceptionally(new TimeoutException("mcp request timed out"));
        given(bridge.call(eq("device-1"), any(), any())).willReturn(future);

        mockMvc.perform(post("/api/xiaozhi/devices/device-1/mcp/rpc")
                        .header("X-MCP-Admin-Token", "admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error").value("mcp request timed out"));
    }

    @Test
    void shouldReturnBadRequestWhenRpcRequestIdIsNotNumeric() throws Exception {
        given(adminAuth.matches("admin-token")).willReturn(true);
        given(bridge.call(eq("device-1"), any(), any()))
                .willThrow(new IllegalArgumentException("mcp request id must be numeric"));

        mockMvc.perform(post("/api/xiaozhi/devices/device-1/mcp/rpc")
                        .header("X-MCP-Admin-Token", "admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":\"bad\",\"method\":\"tools/list\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("mcp request id must be numeric"));
    }
}
