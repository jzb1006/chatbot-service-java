package com.jzb.chatbot.voice.music;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * 音乐媒体域名解析器。
 * <p>
 * 封装 DNS 解析，便于测试覆盖 SSRF 防护边界。
 *
 * @author jiangzhibin
 * @since 2026-06-18 00:00:00
 */
@FunctionalInterface
public interface MusicHostResolver {

    List<InetAddress> resolve(String host) throws UnknownHostException;

    static MusicHostResolver system() {
        return host -> List.of(InetAddress.getAllByName(host));
    }
}
