package com.jzb.chatbot.hermes;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jzb.chatbot.common.id.ConversationId;
import com.jzb.chatbot.common.id.DeviceId;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpHermesClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldCallResponsesApiAndExtractAssistantText() throws Exception {
        var receivedPath = new AtomicReference<String>();
        var receivedAuthorization = new AtomicReference<String>();
        var receivedSessionKey = new AtomicReference<String>();
        var receivedBody = new AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            receivedPath.set(exchange.getRequestURI().getPath());
            receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedSessionKey.set(exchange.getRequestHeaders().getFirst("X-Hermes-Session-Key"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes()));
            var body = """
                    {"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"你好"}]}]}
                    """;
            exchange.sendResponseHeaders(200, body.getBytes().length);
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });
        server.start();
        var client = new HttpHermesClient(new ObjectMapper());

        var response = client.chat(
                new HermesRequest(new DeviceId("device-1"), new ConversationId("conv-1"), "ping"),
                new HermesClientConfig(
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/",
                        "hermes-agent",
                        "secret-key",
                        Duration.ofSeconds(3),
                        "owner"
                )
        );

        assertThat(response.text()).isEqualTo("你好");
        assertThat(receivedPath.get()).isEqualTo("/v1/responses");
        assertThat(receivedAuthorization.get()).isEqualTo("Bearer secret-key");
        assertThat(receivedSessionKey.get()).isEqualTo("owner");
        assertThat(receivedBody.get()).contains("\"model\":\"hermes-agent\"");
        assertThat(receivedBody.get()).contains("\"input\":\"ping\"");
        assertThat(receivedBody.get()).contains("\"conversation\":\"conv-1\"");
    }
}
