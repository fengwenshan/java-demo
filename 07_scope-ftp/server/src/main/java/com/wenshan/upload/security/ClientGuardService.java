package com.wenshan.upload.security;

import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.config.AppProperties;
import com.wenshan.upload.storage.StorageRootRegistry;
import com.wenshan.upload.store.MetaStore;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

/**
 * 客户端身份守卫：MAC/IP 黑名单 + verify→activate 顺序状态。
 *
 * <p>身份由“请求 IP（remoteAddr）+ 客户端上报的物理地址（X-Device-Mac）”组成。HTTP 层无法
 * 获取客户端 MAC，因此 MAC 由桌面客户端读取本机网卡后上报；服务端只做格式规范化。
 * 所有拦截都**实时查 SQLite**（手动删行即可解封，无需重启）。</p>
 *
 * <p>仅当 {@code app.security.client-identity-enforced=true} 时生效；关闭时只解析身份，不做
 * 任何拦截（测试/历史客户端不受影响）。</p>
 */
@Service
public class ClientGuardService {

    private static final Logger log = LoggerFactory.getLogger(ClientGuardService.class);

    public static final String HEADER_DEVICE_MAC = "X-Device-Mac";
    public static final String ATTR_CLIENT_IP = "com.wenshan.upload.guard.clientIp";
    public static final String ATTR_CLIENT_MAC = "com.wenshan.upload.guard.clientMac";

    private static final String REASON_IDENTITY_MISSING = "IDENTITY_MISSING";
    private static final String REASON_CALL_ORDER_VIOLATION = "CALL_ORDER_VIOLATION";

    private final AppProperties properties;
    private final StorageRootRegistry rootRegistry;

    public ClientGuardService(AppProperties properties, StorageRootRegistry rootRegistry) {
        this.properties = properties;
        this.rootRegistry = rootRegistry;
    }

    /** 是否开启强制身份校验。 */
    public boolean enforced() {
        return properties.getSecurity().isClientIdentityEnforced();
    }

    /** 客户端身份（可能为 null 字段；未开启强制校验时用于审计/透传）。 */
    public record ClientIdentity(String ip, String mac) {
    }

    /**
     * 解析并校验本次请求身份。
     *
     * <p>开启强制校验时：IP/MAC 缺失或非法 → 写黑名单并 40312；命中 IP 黑名单 → 40310；
     * 命中 MAC 黑名单 → 40311（任一条命中即拦截）。</p>
     */
    public ClientIdentity check(HttpServletRequest request) {
        String ip = resolveIp(request);
        String mac = normalizeMac(request.getHeader(HEADER_DEVICE_MAC));
        if (!enforced()) {
            return new ClientIdentity(ip, mac);
        }
        if (ip == null || mac == null) {
            log.warn("客户端身份缺失，写入黑名单: ip={}, mac={}, path={}", ip, mac, request.getRequestURI());
            ban(ip, mac, REASON_IDENTITY_MISSING);
            throw new SignatureFilter.RejectedException(ErrorCode.CLIENT_IDENTITY_MISSING);
        }
        MetaStore metaStore = metaStore();
        if (metaStore.isClientIpBlacklisted(ip)) {
            throw new SignatureFilter.RejectedException(ErrorCode.CLIENT_IP_BLACKLISTED);
        }
        if (metaStore.isClientMacBlacklisted(mac)) {
            throw new SignatureFilter.RejectedException(ErrorCode.CLIENT_MAC_BLACKLISTED);
        }
        return new ClientIdentity(ip, mac);
    }

    /**
     * activate 顺序校验：该 MAC+IP 必须在 {@code verify-activate-window-ms} 内成功 verify 过，
     * 否则把 MAC+IP 都写入黑名单并返回 40313。
     */
    public void requireVerifiedBeforeActivate(String ip, String mac, String appKey, String deviceId) {
        if (!enforced()) {
            return;
        }
        if (ip == null || mac == null) {
            ban(ip, mac, REASON_IDENTITY_MISSING);
            throw new SignatureFilter.RejectedException(ErrorCode.CLIENT_IDENTITY_MISSING);
        }
        long now = System.currentTimeMillis();
        long window = properties.getSecurity().getVerifyActivateWindowMs();
        Optional<Long> verifiedAt = metaStore().clientVerifiedAt(mac, ip);
        if (verifiedAt.isEmpty() || now - verifiedAt.get() > window) {
            log.warn("activate 前缺少近期 verify，写入黑名单: ip={}, mac={}, appKey={}", ip, mac, appKey);
            ban(ip, mac, REASON_CALL_ORDER_VIOLATION);
            throw new SignatureFilter.RejectedException(ErrorCode.CALL_ORDER_VIOLATION);
        }
    }

    /** 记录一次成功 verify（供后续 activate 校验）。 */
    public void markVerified(String ip, String mac, String appKey, String deviceId) {
        if (!enforced() || ip == null || mac == null) {
            return;
        }
        metaStore().markClientVerified(mac, ip, appKey, deviceId);
    }

    /** 把 MAC / IP 写入黑名单（空值跳过；任一条命中即拦截）。 */
    public void ban(String ip, String mac, String reason) {
        try {
            metaStore().blacklistClient(ip, mac, reason);
        } catch (RuntimeException e) {
            // 封禁写库失败不能把请求放行，但也不要吞掉原始 403：这里记录后继续抛验收错误
            log.error("写入客户端黑名单失败: ip={}, mac={}, reason={}", ip, mac, reason, e);
        }
    }

    private MetaStore metaStore() {
        return rootRegistry.defaultRoot().metaStore();
    }

    /** 客户端 IP：取 remoteAddr；为空返回 null。 */
    public static String resolveIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return ip == null || ip.isBlank() ? null : ip.trim();
    }

    /**
     * 规范化 MAC 为 {@code AA:BB:CC:DD:EE:FF}。
     *
     * <p>允许输入带 {@code : - . 空格} 分隔符的大小写十六进制；其它字符或长度不是 12 位返回 null。</p>
     */
    public static String normalizeMac(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || !trimmed.matches("[0-9A-Fa-f:. \\-]+")) {
            return null;
        }
        String hex = trimmed.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
        if (hex.length() != 12) {
            return null;
        }
        StringBuilder formatted = new StringBuilder(17);
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) {
                formatted.append(':');
            }
            formatted.append(hex, i, i + 2);
        }
        return formatted.toString();
    }
}
