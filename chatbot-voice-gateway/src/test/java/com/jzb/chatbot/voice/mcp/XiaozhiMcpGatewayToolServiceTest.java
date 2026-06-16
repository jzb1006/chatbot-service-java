package com.jzb.chatbot.voice.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class XiaozhiMcpGatewayToolServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final XiaozhiMcpBridge bridge = mock(XiaozhiMcpBridge.class);
    private final XiaozhiMcpGatewayToolService service = new XiaozhiMcpGatewayToolService(objectMapper, bridge);

    @Test
    void shouldExposeStableHermesGatewayTools() {
        var tools = service.gatewayTools();

        assertThat(tools)
                .extracting(tool -> tool.path("name").asText())
                .containsExactly(
                        "xiaozhi_list_online_devices",
                        "xiaozhi_list_device_tools",
                        "xiaozhi_call_device_tool"
                );
        assertThat(tools.get(2).path("inputSchema").path("properties").has("deviceId")).isTrue();
        assertThat(tools.get(2).path("inputSchema").path("properties").has("name")).isTrue();
        assertThat(tools.get(2).path("inputSchema").path("properties").has("arguments")).isTrue();
    }

    @Test
    void shouldListOnlineDevices() {
        given(bridge.onlineDeviceIds()).willReturn(List.of("device-1", "device-2"));

        var result = service.call("xiaozhi_list_online_devices", objectMapper.createObjectNode(), Duration.ofSeconds(1));

        assertThat(result.path("devices")).extracting(JsonNode::asText).containsExactly("device-1", "device-2");
    }

    @Test
    void shouldCallDeviceToolWithOriginalToolName() throws Exception {
        var deviceResponse = objectMapper.readTree("""
                {"jsonrpc":"2.0","id":10000,"result":{"content":[{"type":"text","text":"ok"}],"isError":false}}
                """);
        given(bridge.call(eq("device-1"), any(), any())).willReturn(CompletableFuture.completedFuture(deviceResponse));

        var args = objectMapper.readTree("""
                {"deviceId":"device-1","name":"self.get_device_status","arguments":{}}
                """);
        var result = service.call("xiaozhi_call_device_tool", args, Duration.ofSeconds(1));

        assertThat(result.path("content").get(0).path("text").asText()).isEqualTo("ok");
        var payloadCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(bridge).call(eq("device-1"), payloadCaptor.capture(), any());
        assertThat(payloadCaptor.getValue().path("method").asText()).isEqualTo("tools/call");
        assertThat(payloadCaptor.getValue().path("params").path("name").asText()).isEqualTo("self.get_device_status");
        assertThat(payloadCaptor.getValue().path("id").isNumber()).isTrue();
    }
}
