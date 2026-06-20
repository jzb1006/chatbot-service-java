package com.jzb.chatbot.voice.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jzb.chatbot.voice.TestWebSocketSession;
import com.jzb.chatbot.voice.protocol.XiaozhiServerEventFactory;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class XiaozhiMcpBridgeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final XiaozhiServerEventFactory eventFactory = new XiaozhiServerEventFactory(objectMapper);
    private final XiaozhiMcpBridge bridge = new XiaozhiMcpBridge(eventFactory);

    @Test
    void shouldSendMcpPayloadToOnlineDevice() throws Exception {
        var session = new TestWebSocketSession("ws-session-1");
        bridge.register("device-1", "ws-session-1", session);

        var sent = bridge.send("device-1", objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                """));

        assertThat(sent).isTrue();
        assertThat(session.getSentMessages())
                .singleElement()
                .satisfies(message -> assertThat(message.getPayload().toString())
                        .contains("\"type\":\"mcp\"", "\"method\":\"tools/list\""));
    }

    @Test
    void shouldExposeMcpReadinessOnlyAfterDeviceHelloDeclaresMcp() {
        var session = new TestWebSocketSession("ws-session-1");
        bridge.register("device-1", "ws-session-1", session);

        assertThat(bridge.onlineDevices())
                .singleElement()
                .satisfies(device -> {
                    assertThat(device.deviceId()).isEqualTo("device-1");
                    assertThat(device.mcpReady()).isFalse();
                });

        bridge.markMcpReady("device-1", "ws-session-1");

        assertThat(bridge.onlineDevices())
                .singleElement()
                .satisfies(device -> assertThat(device.mcpReady()).isTrue());
    }

    @Test
    void shouldRejectMcpCallBeforeDeviceDeclaresMcpReady() throws Exception {
        var session = new TestWebSocketSession("ws-session-1");
        bridge.register("device-1", "ws-session-1", session);

        var future = bridge.call("device-1", objectMapper.readTree("""
                {"jsonrpc":"2.0","id":10,"method":"tools/list"}
                """), Duration.ofSeconds(1));

        assertThat(future).failsWithin(Duration.ofMillis(100))
                .withThrowableThat()
                .withMessageContaining("device mcp is not ready");
        assertThat(session.getSentMessages()).isEmpty();
    }

    @Test
    void shouldReturnFalseWhenDeviceIsOffline() throws Exception {
        var sent = bridge.send("offline-device", objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                """));

        assertThat(sent).isFalse();
    }

    @Test
    void shouldCompletePendingRequestWhenDeviceRespondsWithSameId() throws Exception {
        var session = new TestWebSocketSession("ws-session-1");
        bridge.register("device-1", "ws-session-1", session);
        bridge.markMcpReady("device-1", "ws-session-1");
        var future = bridge.call("device-1", objectMapper.readTree("""
                {"jsonrpc":"2.0","id":7,"method":"tools/list"}
                """), Duration.ofSeconds(1));

        bridge.handleInbound("device-1", objectMapper.readTree("""
                {"jsonrpc":"2.0","id":7,"result":{"tools":[]}}
                """));

        assertThat(future).succeedsWithin(Duration.ofMillis(100))
                .satisfies(json -> assertThat(json.path("result").path("tools").isArray()).isTrue());
    }

    @Test
    void shouldRejectNonNumericRequestIdBeforeSendingToDevice() throws Exception {
        var session = new TestWebSocketSession("ws-session-1");
        bridge.register("device-1", "ws-session-1", session);
        bridge.markMcpReady("device-1", "ws-session-1");

        assertThatThrownBy(() -> bridge.call("device-1", objectMapper.readTree("""
                {"jsonrpc":"2.0","id":"abc","method":"tools/list"}
                """), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mcp request id must be numeric");
        assertThat(session.getSentMessages()).isEmpty();
    }

    @Test
    void shouldCancelPendingRequestsWhenDeviceDisconnects() throws Exception {
        var session = new TestWebSocketSession("ws-session-1");
        bridge.register("device-1", "ws-session-1", session);
        bridge.markMcpReady("device-1", "ws-session-1");
        var future = bridge.call("device-1", objectMapper.readTree("""
                {"jsonrpc":"2.0","id":8,"method":"tools/list"}
                """), Duration.ofSeconds(1));

        bridge.unregister("device-1", "ws-session-1");

        assertThat(future).failsWithin(Duration.ofMillis(100))
                .withThrowableThat()
                .withMessageContaining("device disconnected");
    }

    @Test
    void shouldKeepPendingRequestsWhenStaleSessionDisconnectsAfterReconnect() throws Exception {
        var staleSession = new TestWebSocketSession("ws-session-1");
        var currentSession = new TestWebSocketSession("ws-session-2");
        bridge.register("device-1", "ws-session-1", staleSession);
        bridge.register("device-1", "ws-session-2", currentSession);
        bridge.markMcpReady("device-1", "ws-session-2");
        var future = bridge.call("device-1", objectMapper.readTree("""
                {"jsonrpc":"2.0","id":9,"method":"tools/list"}
                """), Duration.ofSeconds(1));

        bridge.unregister("device-1", "ws-session-1");
        bridge.handleInbound("device-1", objectMapper.readTree("""
                {"jsonrpc":"2.0","id":9,"result":{"tools":[]}}
                """));

        assertThat(future).succeedsWithin(Duration.ofMillis(100))
                .satisfies(json -> assertThat(json.path("result").path("tools").isArray()).isTrue());
    }
}
