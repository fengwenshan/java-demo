package com.wenshan.upload.support;

import com.wenshan.upload.security.CanonicalQuery;
import com.wenshan.upload.security.SignatureService;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Clock;
import java.util.UUID;

/**
 * 测试侧签名工具，独立按契约 2.3 拼签名串（与服务端实现只共享 CanonicalQuery / HMAC 算法，拼装规则在测试里再写一遍）。
 */
public final class TestSigner {

    public static final String APP_KEY = "demo-app";
    public static final String APP_SECRET = "demo-secret-please-change-32-bytes-min";
    public static final String SECOND_APP_KEY = "second-app";
    public static final String SECOND_APP_SECRET = "second-secret-please-change-32-bytes";
    public static final String DISABLED_APP_KEY = "disabled-app";
    public static final String DISABLED_APP_SECRET = "disabled-secret-please-change-32-byte";
    public static final String WRONG_SECRET = "wrong-secret-this-is-not-configured";

    private static final SignatureService SIGNATURE_SERVICE = new SignatureService(Clock.systemUTC());

    private TestSigner() {
    }

    public static String now() {
        return String.valueOf(System.currentTimeMillis());
    }

    public static String newNonce() {
        return UUID.randomUUID().toString();
    }

    public static String sha256(byte[] data) {
        return SignatureService.sha256Hex(data);
    }

    public static String sha256(String text) {
        return SignatureService.sha256Hex(text);
    }

    public static String canonical(String appKey, String timestamp, String nonce, String method,
                                   String path, String rawQuery, String payloadHash) {
        return SIGNATURE_SERVICE.canonicalString(appKey, timestamp, nonce, method, path,
                CanonicalQuery.canonicalize(rawQuery), payloadHash);
    }

    public static String signature(String secret, String canonical) {
        return SIGNATURE_SERVICE.sign(secret, canonical);
    }

    /** 通用签名：计算 canonical → HMAC → 写入请求头（并在签名时固定 query string）。 */
    public static MockHttpServletRequestBuilder withSignature(MockHttpServletRequestBuilder builder,
                                                              String method,
                                                              String path,
                                                              String rawQuery,
                                                              String payloadHash,
                                                              String appKey,
                                                              String secret,
                                                              String timestamp,
                                                              String nonce,
                                                              String deviceId,
                                                              String contentSha256) {
        String canonical = canonical(appKey, timestamp, nonce, method, path, rawQuery, payloadHash);
        builder.header("X-App-Key", appKey)
                .header("X-Timestamp", timestamp)
                .header("X-Nonce", nonce)
                .header("X-Signature", signature(secret, canonical));
        if (deviceId != null) {
            builder.header("X-Device-Id", deviceId);
        }
        if (contentSha256 != null) {
            builder.header("X-Content-Sha256", contentSha256);
        }
        if (rawQuery != null) {
            final String query = rawQuery;
            builder.with(request -> {
                request.setQueryString(query);
                return request;
            });
        }
        return builder;
    }

    public static MockHttpServletRequestBuilder signedGet(String path, String rawQuery) {
        return signedGet(MockMvcRequestBuilders.get(path), path, rawQuery);
    }

    public static MockHttpServletRequestBuilder signedGet(MockHttpServletRequestBuilder builder,
                                                          String path,
                                                          String rawQuery) {
        return withSignature(builder, "GET", path, rawQuery, SignatureService.emptyPayloadHash(),
                APP_KEY, APP_SECRET, now(), newNonce(), null, null);
    }

    public static MockHttpServletRequestBuilder signedGetWithDevice(String path, String rawQuery, String deviceId) {
        return withSignature(MockMvcRequestBuilders.get(path), "GET", path, rawQuery,
                SignatureService.emptyPayloadHash(), APP_KEY, APP_SECRET, now(), newNonce(), deviceId, null);
    }

    /** 无请求体的签名 POST（如分片 complete / cancel 之外的 complete）。 */
    public static MockHttpServletRequestBuilder signedPost(String path) {
        return withSignature(MockMvcRequestBuilders.post(path), "POST", path, null,
                SignatureService.emptyPayloadHash(), APP_KEY, APP_SECRET, now(), newNonce(), null, null);
    }

    public static MockHttpServletRequestBuilder signedDelete(String path) {
        return withSignature(MockMvcRequestBuilders.delete(path), "DELETE", path, null,
                SignatureService.emptyPayloadHash(), APP_KEY, APP_SECRET, now(), newNonce(), null, null);
    }

    /** 带 query 的签名 DELETE（JSON 无请求体：payloadHash=空内容哈希）。 */
    public static MockHttpServletRequestBuilder signedDelete(MockHttpServletRequestBuilder builder,
                                                             String path,
                                                             String rawQuery) {
        return withSignature(builder, "DELETE", path, rawQuery,
                SignatureService.emptyPayloadHash(), APP_KEY, APP_SECRET, now(), newNonce(), null, null);
    }

    /** 签名 JSON PUT（payloadHash=body SHA-256）。 */
    public static MockHttpServletRequestBuilder signedJsonPut(MockHttpServletRequestBuilder builder,
                                                              String path,
                                                              byte[] body) {
        builder.contentType("application/json").content(body);
        return withSignature(builder, "PUT", path, null, sha256(body),
                APP_KEY, APP_SECRET, now(), newNonce(), null, null);
    }

    public static MockHttpServletRequestBuilder signedJsonPost(MockHttpServletRequestBuilder builder,
                                                               String path,
                                                               byte[] body) {
        return signedJsonPost(builder, path, body, "application/json");
    }

    public static MockHttpServletRequestBuilder signedJsonPost(MockHttpServletRequestBuilder builder,
                                                               String path,
                                                               byte[] body,
                                                               String contentType) {
        builder.contentType(contentType).content(body);
        return withSignature(builder, "POST", path, null, sha256(body),
                APP_KEY, APP_SECRET, now(), newNonce(), null, null);
    }

    /** multipart 上传：声明哈希 = 实际内容哈希。 */
    public static MockHttpServletRequestBuilder signedMultipart(MockHttpServletRequestBuilder builder,
                                                                String path,
                                                                byte[] fileContent,
                                                                String deviceId) {
        String contentSha = sha256(fileContent);
        return withSignature(builder, "POST", path, null, contentSha,
                APP_KEY, APP_SECRET, now(), newNonce(), deviceId, contentSha);
    }

    /**
     * 分片上传：{@code PUT /api/files/upload/{uploadId}/chunk?index=N}，
     * Content-Type=application/octet-stream 裸字节，X-Content-Sha256=分片内容哈希且参与签名。
     */
    public static MockHttpServletRequestBuilder signedChunkPut(String uploadId, int index, byte[] body) {
        return signedChunkPutWithDeclaredSha(uploadId, index, body, sha256(body));
    }

    /** 分片上传：声明哈希与真实内容可以不一致，用于分片 40001 测试。 */
    public static MockHttpServletRequestBuilder signedChunkPutWithDeclaredSha(
            String uploadId, int index, byte[] body, String declaredSha256) {
        String path = "/api/files/upload/" + uploadId + "/chunk";
        String rawQuery = "index=" + index;
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.put(path)
                .queryParam("index", String.valueOf(index))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(body);
        return withSignature(builder, "PUT", path, rawQuery, declaredSha256,
                APP_KEY, APP_SECRET, now(), newNonce(), null, declaredSha256);
    }

    /** multipart 上传：声明哈希与真实内容可以不一致，用于 40001 测试。 */
    public static MockHttpServletRequestBuilder signedMultipartWithDeclaredSha(MockHttpServletRequestBuilder builder,
                                                                               String path,
                                                                               String declaredSha256,
                                                                               String deviceId) {
        return withSignature(builder, "POST", path, null, declaredSha256,
                APP_KEY, APP_SECRET, now(), newNonce(), deviceId, declaredSha256);
    }
}
