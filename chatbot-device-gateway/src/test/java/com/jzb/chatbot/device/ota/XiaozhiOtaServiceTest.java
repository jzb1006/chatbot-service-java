package com.jzb.chatbot.device.ota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.jzb.chatbot.device.InvalidDeviceChatRequestException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class XiaozhiOtaServiceTest {

    @Test
    void shouldBuildOtaResponseWithWebsocketServerTimeAndEmptyFirmwareWhenNoUpgrade() {
        var objectMapper = new ObjectMapper();
        var properties = XiaozhiOtaProperties.defaults()
                .withWebsocketUrl("ws://203.195.202.54:8766/xiaozhi/v1")
                .withWebsocketToken("device-token")
                .withWebsocketVersion(3);
        var factory = new XiaozhiOtaResponseFactory(objectMapper);

        var response = factory.checkResponse(
                new OtaDeviceIdentity("device-1", "client-1", "", "1", "xiaozhi/1.0.0"),
                properties,
                false,
                null
        );

        assertThat(response.path("websocket").path("url").asText())
                .isEqualTo("ws://203.195.202.54:8766/xiaozhi/v1");
        assertThat(response.path("websocket").path("token").asText()).isEqualTo("device-token");
        assertThat(response.path("websocket").path("version").asInt()).isEqualTo(3);
        assertThat(response.path("server_time").path("timestamp").isNumber()).isTrue();
        assertThat(response.has("activation")).isFalse();
        assertThat(response.path("firmware").path("version").asText()).isEmpty();
    }

    @Test
    void shouldNotReturnWebsocketTokenWhenDeviceIsNotAllowed() {
        var properties = XiaozhiOtaProperties.defaults()
                .withWebsocketUrl("ws://203.195.202.54:8766/xiaozhi/v1")
                .withWebsocketToken("secret-token")
                .withAllowedDeviceIds(List.of("allowed-device"));
        var service = new XiaozhiOtaService(
                properties,
                new InMemoryXiaozhiActivationStore(),
                new XiaozhiOtaResponseFactory(new ObjectMapper())
        );

        var response = service.check(new OtaDeviceIdentity("other-device", "client-1", "", "1", ""), NullNode.instance);

        assertThat(response.path("websocket").path("token").asText()).isEmpty();
        assertThat(response.path("firmware").path("url").asText()).isEmpty();
    }

    @Test
    void shouldActivateDeviceWithIssuedChallenge() throws Exception {
        var objectMapper = new ObjectMapper();
        var properties = activationRequiredProperties(Duration.ofSeconds(30));
        var service = new XiaozhiOtaService(
                properties,
                new InMemoryXiaozhiActivationStore(),
                new XiaozhiOtaResponseFactory(objectMapper)
        );
        var identity = new OtaDeviceIdentity("device-1", "client-1", "", "1", "");
        var challenge = service.check(identity, NullNode.instance).path("activation").path("challenge").asText();

        var status = service.activate(identity, objectMapper.readTree("""
                {"challenge":"%s"}
                """.formatted(challenge)));

        assertThat(status).isEqualTo(XiaozhiOtaService.ActivationStatus.ACTIVATED);
        assertThat(service.check(identity, NullNode.instance).has("activation")).isFalse();
    }

    @Test
    void shouldRejectExpiredActivationChallenge() throws Exception {
        var objectMapper = new ObjectMapper();
        var properties = activationRequiredProperties(Duration.ofMillis(1));
        var service = new XiaozhiOtaService(
                properties,
                new InMemoryXiaozhiActivationStore(),
                new XiaozhiOtaResponseFactory(objectMapper)
        );
        var identity = new OtaDeviceIdentity("device-1", "client-1", "", "1", "");
        var challenge = service.check(identity, NullNode.instance).path("activation").path("challenge").asText();
        Thread.sleep(5);

        var status = service.activate(identity, objectMapper.readTree("""
                {"challenge":"%s"}
                """.formatted(challenge)));

        assertThat(status).isEqualTo(XiaozhiOtaService.ActivationStatus.REJECTED);
    }

    @Test
    void shouldNotIssueActivationChallengeWithoutStableIdentity() {
        var service = new XiaozhiOtaService(
                activationRequiredProperties(Duration.ofSeconds(30)),
                new InMemoryXiaozhiActivationStore(),
                new XiaozhiOtaResponseFactory(new ObjectMapper())
        );

        var response = service.check(new OtaDeviceIdentity("", "client-1", "", "1", ""), NullNode.instance);

        assertThat(response.has("activation")).isFalse();
    }

    @Test
    void shouldRejectActivationWithoutStableIdentity() throws Exception {
        var service = new XiaozhiOtaService(
                activationRequiredProperties(Duration.ofSeconds(30)),
                new InMemoryXiaozhiActivationStore(),
                new XiaozhiOtaResponseFactory(new ObjectMapper())
        );

        var status = service.activate(
                new OtaDeviceIdentity("", "client-1", "", "1", ""),
                new ObjectMapper().readTree("""
                        {"challenge":"challenge"}
                        """)
        );

        assertThat(status).isEqualTo(XiaozhiOtaService.ActivationStatus.REJECTED);
    }

    @Test
    void shouldRejectFirmwareSymlinkEscapingFirmwareDirectory(@TempDir Path tempDir) throws Exception {
        var firmwareDirectory = tempDir.resolve("firmware");
        var outsideFile = tempDir.resolve("secret.bin");
        Files.createDirectories(firmwareDirectory);
        Files.writeString(outsideFile, "secret");
        Files.createSymbolicLink(firmwareDirectory.resolve("firmware.bin"), outsideFile);
        var service = new XiaozhiOtaService(
                propertiesWithFirmwareDirectory(firmwareDirectory),
                new InMemoryXiaozhiActivationStore(),
                new XiaozhiOtaResponseFactory(new ObjectMapper())
        );

        assertThatThrownBy(() -> service.firmware("firmware.bin"))
                .isInstanceOf(InvalidDeviceChatRequestException.class)
                .hasMessageContaining("invalid firmware path");
    }

    private XiaozhiOtaProperties activationRequiredProperties(Duration activationTtl) {
        return new XiaozhiOtaProperties(
                true,
                "ws://203.195.202.54:8766/xiaozhi/v1",
                "",
                3,
                "",
                "",
                false,
                null,
                true,
                "请在服务端完成设备激活",
                activationTtl,
                List.of(),
                List.of()
        );
    }

    private XiaozhiOtaProperties propertiesWithFirmwareDirectory(Path firmwareDirectory) {
        return new XiaozhiOtaProperties(
                true,
                "ws://203.195.202.54:8766/xiaozhi/v1",
                "",
                3,
                "",
                "",
                false,
                firmwareDirectory,
                false,
                "请在服务端完成设备激活",
                Duration.ofSeconds(30),
                List.of(),
                List.of()
        );
    }
}
