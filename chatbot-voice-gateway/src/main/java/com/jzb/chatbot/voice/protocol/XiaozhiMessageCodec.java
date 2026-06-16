package com.jzb.chatbot.voice.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 小智协议 JSON 编解码器。
 * <p>
 * 处理文本控制帧以及 WebSocket binary v1/v2/v3 音频帧。
 *
 * @author jiangzhibin
 * @since 2026-06-14 18:36:50
 */
@Component
@RequiredArgsConstructor
public class XiaozhiMessageCodec {

    private final ObjectMapper objectMapper;

    /**
     * 编码服务端 hello 消息。
     *
     * @param sessionId 会话标识
     * @return JSON 文本
     * @throws JsonProcessingException JSON 序列化失败
     */
    public String encodeServerHello(String sessionId) throws JsonProcessingException {
        return encodeServerHello(sessionId, XiaozhiAudioParams.defaults());
    }

    /**
     * 编码服务端 hello 消息。
     *
     * @param sessionId 会话标识
     * @param audioParams 音频参数
     * @return JSON 文本
     * @throws JsonProcessingException JSON 序列化失败
     */
    public String encodeServerHello(String sessionId, XiaozhiAudioParams audioParams) throws JsonProcessingException {
        return objectMapper.writeValueAsString(XiaozhiServerHello.websocket(sessionId, audioParams));
    }

    /**
     * 解码设备 hello 消息。
     *
     * @param text JSON 文本
     * @return 设备 hello
     * @throws JsonProcessingException JSON 解析失败
     */
    public XiaozhiClientHello decodeClientHello(String text) throws JsonProcessingException {
        return objectMapper.readValue(text, XiaozhiClientHello.class);
    }

    /**
     * 解码客户端文本控制帧。
     *
     * @param text JSON 文本
     * @return 客户端消息
     * @throws JsonProcessingException JSON 解析失败
     */
    public XiaozhiClientMessage decodeText(String text) throws JsonProcessingException {
        return objectMapper.readValue(text, XiaozhiClientMessage.class);
    }

    /**
     * 解码小智二进制音频帧。
     *
     * @param protocolVersion 二进制协议版本
     * @param buffer WebSocket 二进制 payload
     * @return 统一音频帧
     */
    public XiaozhiAudioFrame decodeAudioFrame(int protocolVersion, ByteBuffer buffer) {
        try {
            var input = buffer.slice();
            if (protocolVersion == 2) {
                return decodeBinaryV2(input);
            }
            if (protocolVersion == 3) {
                return decodeBinaryV3(input);
            }
            var payload = new byte[input.remaining()];
            input.get(payload);
            return new XiaozhiAudioFrame(1, 0, payload);
        } catch (BufferUnderflowException | IllegalArgumentException exception) {
            throw new XiaozhiProtocolException("invalid binary audio frame");
        }
    }

    /**
     * 编码服务端下行二进制音频帧。
     *
     * @param protocolVersion 二进制协议版本
     * @param timestamp 时间戳
     * @param payload Opus payload
     * @return WebSocket 二进制 payload
     */
    public ByteBuffer encodeAudioFrame(int protocolVersion, long timestamp, ByteBuffer payload) {
        var bytes = toBytes(payload);
        if (protocolVersion == 2) {
            var output = ByteBuffer.allocate(16 + bytes.length);
            output.putShort((short) 2);
            output.putShort((short) 0);
            output.putInt(0);
            output.putInt((int) timestamp);
            output.putInt(bytes.length);
            output.put(bytes);
            output.flip();
            return output;
        }
        if (protocolVersion == 3) {
            var output = ByteBuffer.allocate(4 + bytes.length);
            output.put((byte) 0);
            output.put((byte) 0);
            output.putShort((short) bytes.length);
            output.put(bytes);
            output.flip();
            return output;
        }
        return ByteBuffer.wrap(bytes);
    }

    private XiaozhiAudioFrame decodeBinaryV2(ByteBuffer input) {
        requireRemaining(input, 16);
        input.getShort();
        input.getShort();
        input.getInt();
        var timestamp = Integer.toUnsignedLong(input.getInt());
        var payloadSize = input.getInt();
        requirePayload(input, payloadSize);
        var payload = new byte[payloadSize];
        input.get(payload);
        return new XiaozhiAudioFrame(2, timestamp, payload);
    }

    private XiaozhiAudioFrame decodeBinaryV3(ByteBuffer input) {
        requireRemaining(input, 4);
        input.get();
        input.get();
        var payloadSize = Short.toUnsignedInt(input.getShort());
        requirePayload(input, payloadSize);
        var payload = new byte[payloadSize];
        input.get(payload);
        return new XiaozhiAudioFrame(3, 0, payload);
    }

    private void requirePayload(ByteBuffer input, int payloadSize) {
        if (payloadSize < 0 || input.remaining() < payloadSize) {
            throw new XiaozhiProtocolException("invalid binary audio frame payload size");
        }
    }

    private void requireRemaining(ByteBuffer input, int size) {
        if (input.remaining() < size) {
            throw new XiaozhiProtocolException("invalid binary audio frame header");
        }
    }

    private byte[] toBytes(ByteBuffer payload) {
        if (payload == null) {
            return new byte[0];
        }
        var input = payload.slice();
        var bytes = new byte[input.remaining()];
        input.get(bytes);
        return bytes;
    }
}
