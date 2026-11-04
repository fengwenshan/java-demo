package com.wenshan.upload.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshan.upload.audit.RelayViaHop;
import com.wenshan.upload.relay.RelayHop;
import com.wenshan.upload.security.SignatureService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试侧 relay 客户端：按协议为「外层 A 跳 + 下游 hops」构造 {@code X-Relay-Chain}，
 * 用本机已知的假 secret 复算每跳签名，避免测试里出现真实 AppSecret。
 *
 * <p>链头 A 的 timestamp/nonce/signature 与请求标准签名头必须完全一致（服务端会校验），
 * 下游 hops 的签名只需透传，假 B/C 不验签，但测试会用 {@link #signatureMatches} 复算断言。</p>
 */
public final class RelayTestClient {

    public static final String B_APP_KEY = "b-app-fake";
    public static final String B_APP_SECRET = "b-secret-fake-not-real-please-change-32";
    public static final String B_DEVICE_ID = "b-device";
    public static final String C_APP_KEY = "c-app-fake";
    public static final String C_APP_SECRET = "c-secret-fake-not-real-please-change-32";
    public static final String C_DEVICE_ID = "c-device";
    public static final String A_DEVICE_ID = "a-device";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int port;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public RelayTestClient(int port) {
        this.port = port;
    }

    /** 构造一个下游 hop（假 B/C）；签名只透传，不参与测试侧校验。 */
    public static RelayHop hop(String url, String name, String appKey, String deviceId) {
        return new RelayHop(url, name, appKey, System.currentTimeMillis(),
                TestSigner.newNonce(), "relay-signature-" + name, deviceId);
    }

    /**
     * 构造一个带真实签名的下游 hop（测试侧用假 secret 预签，可被 {@link #signatureMatches} 复算）。
     *
     * @param pathAtHop 该跳实际收到的路径（最终目标 = 原始业务路径）
     */
    public static RelayHop signedHop(String url, String name, String appKey, String secret, String deviceId,
                                     String method, String pathAtHop, String rawQuery,
                                     byte[] body, String declaredContentSha) {
        String timestamp = TestSigner.now();
        String nonce = TestSigner.newNonce();
        String payloadHash = declaredContentSha != null
                ? declaredContentSha
                : (body == null ? SignatureService.emptyPayloadHash() : TestSigner.sha256(body));
        String signature = TestSigner.signature(secret,
                TestSigner.canonical(appKey, timestamp, nonce, method, pathAtHop, rawQuery, payloadHash));
        return new RelayHop(url, name, appKey, Long.parseLong(timestamp), nonce, signature, deviceId);
    }

    /** 下游最终目标的签名复算：测试用假 secret 验证 A 转发时是否带上了下一跳的签名头。 */
    public static boolean signatureMatches(String secret, String appKey, String timestamp, String nonce,
                                           String method, String path, String rawQuery,
                                           String payloadHash, String signature) {
        String canonical = TestSigner.canonical(appKey, timestamp, nonce, method, path, rawQuery, payloadHash);
        return TestSigner.signature(secret, canonical).equals(signature);
    }

    public static List<RelayHop> decodeChain(String header) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(header);
            return MAPPER.readValue(bytes,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, RelayHop.class));
        } catch (Exception e) {
            throw new IllegalArgumentException("非法 X-Relay-Chain: " + header, e);
        }
    }

    public static List<RelayViaHop> decodeVia(String header) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(header);
            return MAPPER.readValue(bytes,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, RelayViaHop.class));
        } catch (Exception e) {
            throw new IllegalArgumentException("非法 X-Relay-Via: " + header, e);
        }
    }

    public static String encodeChain(List<RelayHop> hops) {
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MAPPER.writeValueAsBytes(hops));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 发送一个经 A 转发的签名请求。
     *
     * @param method              实际 HTTP 方法（大写）
     * @param relayPath           A 上的路径，必须以 /api/relay/api/ 开头
     * @param rawQuery            原始 query（不含 ?，可为 null）
     * @param body                请求体（无 body 传 null）
     * @param contentType         Content-Type（multipart/octet 必须显式指定）
     * @param declaredContentSha  X-Content-Sha256（multipart/octet 用）
     * @param downstreamHops      第 1..n-1 跳（B、C…）
     */
    public HttpResponse<byte[]> send(String method, String relayPath, String rawQuery, byte[] body,
                                     String contentType, String declaredContentSha,
                                     List<RelayHop> downstreamHops) throws Exception {
        String timestamp = TestSigner.now();
        String nonce = TestSigner.newNonce();
        String payloadHash = payloadHash(body, declaredContentSha);
        String signature = TestSigner.signature(TestSigner.APP_SECRET,
                TestSigner.canonical(TestSigner.APP_KEY, timestamp, nonce, method, relayPath,
                        rawQuery, payloadHash));
        List<RelayHop> chain = new ArrayList<>();
        chain.add(new RelayHop("http://127.0.0.1:" + port, "A", TestSigner.APP_KEY,
                Long.parseLong(timestamp), nonce, signature, A_DEVICE_ID));
        chain.addAll(downstreamHops);

        HttpRequest.Builder builder = HttpRequest.newBuilder(url(relayPath, rawQuery))
                .header("X-App-Key", TestSigner.APP_KEY)
                .header("X-Timestamp", timestamp)
                .header("X-Nonce", nonce)
                .header("X-Signature", signature)
                .header("X-Device-Id", A_DEVICE_ID)
                .header("X-Relay-Chain", encodeChain(chain));
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        if (declaredContentSha != null) {
            builder.header("X-Content-Sha256", declaredContentSha);
        }
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        builder.method(method, publisher);
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private String payloadHash(byte[] body, String declaredContentSha) {
        if (declaredContentSha != null) {
            return declaredContentSha;
        }
        return body == null ? SignatureService.emptyPayloadHash() : TestSigner.sha256(body);
    }

    public URI url(String relayPath, String rawQuery) {
        String url = "http://127.0.0.1:" + port + relayPath;
        return URI.create(rawQuery == null || rawQuery.isBlank() ? url : url + "?" + rawQuery);
    }

    /** 便于测试复算 query：把 key=value 按规范顺序拼成 raw query。 */
    public static String query(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : new LinkedHashMap<>(params).entrySet()) {
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return out.toString();
    }

    public static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
