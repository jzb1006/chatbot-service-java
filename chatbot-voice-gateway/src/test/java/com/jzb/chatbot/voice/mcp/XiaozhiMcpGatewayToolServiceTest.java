package com.jzb.chatbot.voice.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jzb.chatbot.voice.reminder.XiaozhiReminderService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class XiaozhiMcpGatewayToolServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final XiaozhiMcpBridge bridge = mock(XiaozhiMcpBridge.class);
    private final XiaozhiReminderService reminderService = mock(XiaozhiReminderService.class);
    private final XiaozhiMcpGatewayToolService service = new XiaozhiMcpGatewayToolService(
            objectMapper,
            bridge,
            reminderService
    );

    @Test
    void shouldExposeStableHermesGatewayTools() {
        var tools = service.gatewayTools();

        assertThat(tools)
                .extracting(tool -> tool.path("name").asText())
                .containsExactly(
                        "xiaozhi_list_online_devices",
                        "xiaozhi_list_device_tools",
                        "xiaozhi_call_device_tool",
                        "xiaozhi_create_reminder"
                );
        assertThat(tools.get(2).path("inputSchema").path("properties").has("deviceId")).isTrue();
        assertThat(tools.get(2).path("inputSchema").path("properties").has("name")).isTrue();
        assertThat(tools.get(2).path("inputSchema").path("properties").has("arguments")).isTrue();
        assertThat(tools.get(3).path("inputSchema").path("properties").has("message")).isTrue();
        assertThat(tools.get(3).path("inputSchema").path("properties").has("remindAt")).isTrue();
        assertThat(tools.get(3).path("inputSchema").path("properties").has("delaySeconds")).isTrue();
        assertThat(tools.get(3).path("inputSchema").path("required"))
                .extracting(JsonNode::asText)
                .contains("message")
                .doesNotContain("deviceId", "remindAt");
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

    @Test
    void shouldCreateReminderForExplicitDevice() throws Exception {
        given(reminderService.create(
                "device-1",
                "提醒我喝水",
                "2026-06-17T18:00:00+08:00"
        )).willReturn(new XiaozhiReminderService.CreatedReminder(
                "reminder-1",
                "device-1",
                "提醒我喝水",
                java.time.Instant.parse("2026-06-17T10:00:00Z")
        ));

        var result = service.call("xiaozhi_create_reminder", objectMapper.readTree("""
                {"deviceId":"device-1","message":"提醒我喝水","remindAt":"2026-06-17T18:00:00+08:00"}
                """), Duration.ofSeconds(1));

        assertThat(result.path("id").asText()).isEqualTo("reminder-1");
        assertThat(result.path("deviceId").asText()).isEqualTo("device-1");
        assertThat(result.path("message").asText()).isEqualTo("提醒我喝水");
        assertThat(result.path("remindAt").asText()).isEqualTo("2026-06-17T10:00:00Z");
    }

    @Test
    void shouldCreateReminderForOnlyOnlineDeviceWhenDeviceIdIsMissing() throws Exception {
        given(bridge.onlineDeviceIds()).willReturn(List.of("device-1"));
        given(reminderService.create(
                "device-1",
                "提醒我喝水",
                "2026-06-17T18:00:00+08:00"
        )).willReturn(new XiaozhiReminderService.CreatedReminder(
                "reminder-1",
                "device-1",
                "提醒我喝水",
                java.time.Instant.parse("2026-06-17T10:00:00Z")
        ));

        var result = service.call("xiaozhi_create_reminder", objectMapper.readTree("""
                {"message":"提醒我喝水","remindAt":"2026-06-17T18:00:00+08:00"}
                """), Duration.ofSeconds(1));

        assertThat(result.path("deviceId").asText()).isEqualTo("device-1");
    }

    @Test
    void shouldCreateReminderFromDelaySeconds() throws Exception {
        given(bridge.onlineDeviceIds()).willReturn(List.of("device-1"));
        given(reminderService.createAfter("device-1", "提醒我喝水", 60L))
                .willReturn(new XiaozhiReminderService.CreatedReminder(
                        "reminder-1",
                        "device-1",
                        "提醒我喝水",
                        java.time.Instant.parse("2026-06-17T10:00:00Z")
                ));

        var result = service.call("xiaozhi_create_reminder", objectMapper.readTree("""
                {"message":"提醒我喝水","delaySeconds":60}
                """), Duration.ofSeconds(1));

        assertThat(result.path("id").asText()).isEqualTo("reminder-1");
        assertThat(result.path("deviceId").asText()).isEqualTo("device-1");
    }
}
