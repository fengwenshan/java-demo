package com.wenshan.upload.relay;

import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.config.AppProperties;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * 转发目标 URL 与 SSRF 守卫。
 *
 * <p>只允许 http/https；禁止 userinfo/query/fragment；解析出 IP 后默认拒绝 loopback、
 * link-local（含云元数据 169.254.169.254）、multicast/unspecified、私网（可配置放行）。
 * {@code allowed-hosts} 非空时作为 host（或 host:port）白名单。</p>
 *
 * <p>残余风险：DNS rebinding 与「持有 A 凭据的客户端把 A 当代理」由 relay 默认关闭 +
 * 可选 host 白名单缓解，P2 再加固；本地启动脚本会默认开启，仅在开发/受控网络使用。</p>
 */
@Component
public class RelayUrlGuard {

    private final AppProperties properties;

    public RelayUrlGuard(AppProperties properties) {
        this.properties = properties;
    }

    public URI validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid("转发目标地址为空");
        }
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException e) {
            throw invalid("转发目标地址格式无效");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw invalid("转发目标只允许 http/https");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw invalid("转发目标地址不能包含用户信息、查询参数或片段");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw invalid("转发目标地址缺少主机名");
        }
        if (!allowedHost(host, uri.getPort())) {
            throw invalid("转发目标不在 allowed-hosts 中");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(stripBrackets(host));
        } catch (Exception e) {
            throw new BizException(ErrorCode.RELAY_TARGET_UNREACHABLE, "无法解析转发目标地址");
        }
        for (InetAddress address : addresses) {
            checkAddress(address);
        }
        return uri;
    }

    private boolean allowedHost(String host, int port) {
        List<String> allowed = properties.getRelay().getAllowedHosts();
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        String normalized = stripBrackets(host).toLowerCase(Locale.ROOT);
        String withPort = port < 0 ? normalized : normalized + ":" + port;
        return allowed.stream()
                .map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.equals(normalized) || value.equals(withPort));
    }

    private void checkAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isMulticastAddress()) {
            throw invalid("转发目标地址不允许");
        }
        if (address.isLoopbackAddress() && !properties.getRelay().isAllowLoopback()) {
            throw invalid("转发目标不允许 loopback");
        }
        if (address.isLinkLocalAddress()) {
            throw invalid("转发目标不允许 link-local/元数据地址");
        }
        if (address.isSiteLocalAddress() && !properties.getRelay().isAllowPrivateNetwork()) {
            throw invalid("转发目标不允许私网地址");
        }
    }

    private static String stripBrackets(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private BizException invalid(String message) {
        return new BizException(ErrorCode.RELAY_CHAIN_INVALID, message);
    }
}
