package com.wenshan.upload.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;

/**
 * 契约第 2 节签名算法实现。
 *
 * <pre>
 * 签名串（8 行，\n 分隔，末尾无换行）：
 * v1
 * {appKey}
 * {timestampMillis}
 * {nonce}
 * {METHOD}
 * {path}
 * {canonicalQuery}
 * {payloadHash}
 *
 * X-Signature = lowercase_hex(HMAC-SHA256(appSecret, canonicalString))
 * </pre>
 */
@Component
public class SignatureService {

    public static final String SIGNATURE_VERSION = "v1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final char LINE_SEPARATOR = '\n';

    private final Clock clock;

    public SignatureService(Clock clock) {
        this.clock = clock;
    }

    /** 服务端当前时间（毫秒），测试中可通过固定 Clock 控制。 */
    public long nowMillis() {
        return clock.millis();
    }

    /** 按契约 2.3 拼装签名串。 */
    public String canonicalString(String appKey,
                                  String timestampMillis,
                                  String nonce,
                                  String method,
                                  String path,
                                  String canonicalQuery,
                                  String payloadHash) {
        return SIGNATURE_VERSION
                + LINE_SEPARATOR + appKey
                + LINE_SEPARATOR + timestampMillis
                + LINE_SEPARATOR + nonce
                + LINE_SEPARATOR + method
                + LINE_SEPARATOR + path
                + LINE_SEPARATOR + canonicalQuery
                + LINE_SEPARATOR + payloadHash;
    }

    /** 对签名串计算 HMAC-SHA256，返回 64 位小写十六进制。 */
    public String sign(String appSecret, String canonicalString) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(canonicalString.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 初始化失败", e);
        }
    }

    /** 常量时间比较签名（防止时序侧信道）。 */
    public boolean verify(String appSecret, String canonicalString, String providedSignature) {
        if (providedSignature == null) {
            return false;
        }
        String expected = sign(appSecret, canonicalString);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                providedSignature.getBytes(StandardCharsets.US_ASCII));
    }

    /** SHA-256 小写十六进制。 */
    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /** 空请求体的 payloadHash，契约 2.5 固定值。 */
    public static String emptyPayloadHash() {
        return sha256Hex(new byte[0]);
    }
}
