package com.wenshan.upload;

import com.wenshan.upload.security.SignatureService;
import com.wenshan.upload.support.IntegrationTestSupport;
import com.wenshan.upload.support.TestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 契约 2.6 签名校验顺序与错误码。
 */
@SpringBootTest(properties = "app.test.suite=signature-filter")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SignatureFilterIntegrationTest extends IntegrationTestSupport {

    @Test
    void healthNeedsNoSignature() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.time").isNumber())
                .andExpect(jsonPath("$.timestamp").isNumber());
    }

    @Test
    void missingHeadersRejectedWith40100() throws Exception {
        mockMvc.perform(get("/api/auth/verify"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.message").value("签名无效"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.timestamp").isNumber());
    }

    @Test
    void unknownAppKeyRejectedWith40103() throws Exception {
        MockHttpServletRequestBuilder builder = signedVerify(TestSigner.APP_SECRET, "unknown-app",
                TestSigner.now(), TestSigner.newNonce());
        mockMvc.perform(builder)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40103))
                .andExpect(jsonPath("$.message").value("客户端未授权"));
    }

    @Test
    void disabledAppKeyRejectedWith40103() throws Exception {
        MockHttpServletRequestBuilder builder = signedVerify(TestSigner.DISABLED_APP_SECRET,
                TestSigner.DISABLED_APP_KEY, TestSigner.now(), TestSigner.newNonce());
        mockMvc.perform(builder)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40103));
    }

    @Test
    void expiredTimestampRejectedWith40101() throws Exception {
        long expired = System.currentTimeMillis() - 400_000L;
        MockHttpServletRequestBuilder builder = signedVerify(TestSigner.APP_SECRET, TestSigner.APP_KEY,
                String.valueOf(expired), TestSigner.newNonce());
        mockMvc.perform(builder)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101))
                .andExpect(jsonPath("$.message").value("请求已过期"));
    }

    @Test
    void futureTimestampRejectedWith40101() throws Exception {
        long future = System.currentTimeMillis() + 400_000L;
        MockHttpServletRequestBuilder builder = signedVerify(TestSigner.APP_SECRET, TestSigner.APP_KEY,
                String.valueOf(future), TestSigner.newNonce());
        mockMvc.perform(builder)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    void nonNumericTimestampRejectedWith40100() throws Exception {
        MockHttpServletRequestBuilder builder = signedVerify(TestSigner.APP_SECRET, TestSigner.APP_KEY,
                "not-a-number", TestSigner.newNonce());
        mockMvc.perform(builder)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void wrongSignatureRejectedWith40100() throws Exception {
        MockHttpServletRequestBuilder builder = signedVerify(TestSigner.WRONG_SECRET, TestSigner.APP_KEY,
                TestSigner.now(), TestSigner.newNonce());
        mockMvc.perform(builder)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void malformedNonceRejectedWith40100() throws Exception {
        MockHttpServletRequestBuilder builder = signedVerify(TestSigner.APP_SECRET, TestSigner.APP_KEY,
                TestSigner.now(), "short");
        mockMvc.perform(builder)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void signatureIsBoundToPath() throws Exception {
        // 用 /api/files 的 path 签名，却请求 /api/auth/verify
        MockHttpServletRequestBuilder builder = TestSigner.withSignature(
                get("/api/auth/verify"), "GET", "/api/files", null,
                SignatureService.emptyPayloadHash(), TestSigner.APP_KEY, TestSigner.APP_SECRET,
                TestSigner.now(), TestSigner.newNonce(), null, null);
        mockMvc.perform(builder)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void queryStringIsBoundToSignature() throws Exception {
        String timestamp = TestSigner.now();
        String nonce = TestSigner.newNonce();
        String signedQuery = "page=1&size=20";
        String canonical = TestSigner.canonical(TestSigner.APP_KEY, timestamp, nonce,
                "GET", "/api/files", signedQuery, SignatureService.emptyPayloadHash());
        // 用 page=1 的签名，实际请求 page=2：query 必须参与签名
        MockHttpServletRequestBuilder tampered = get("/api/files")
                .queryParam("page", "2")
                .queryParam("size", "20")
                .header("X-App-Key", TestSigner.APP_KEY)
                .header("X-Timestamp", timestamp)
                .header("X-Nonce", nonce)
                .header("X-Signature", TestSigner.signature(TestSigner.APP_SECRET, canonical));
        mockMvc.perform(tampered)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void jsonBodyIsBoundToSignature() throws Exception {
        String timestamp = TestSigner.now();
        String nonce = TestSigner.newNonce();
        String signedBody = "{\"deviceId\":\"00000000-0000-0000-0000-000000000001\",\"deviceName\":\"signed\"}";
        String tamperedBody = "{\"deviceId\":\"00000000-0000-0000-0000-000000000002\",\"deviceName\":\"tampered\"}";
        MockHttpServletRequestBuilder builder = post("/api/auth/activate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(tamperedBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        TestSigner.withSignature(builder, "POST", "/api/auth/activate", null,
                TestSigner.sha256(signedBody.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                TestSigner.APP_KEY, TestSigner.APP_SECRET, timestamp, nonce, null, null);
        mockMvc.perform(builder)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void invalidSignatureDoesNotConsumeNonce() throws Exception {
        String timestamp = TestSigner.now();
        String nonce = TestSigner.newNonce();
        // 无效签名：nonce 不得写入缓存
        mockMvc.perform(signedVerify(TestSigner.WRONG_SECRET, TestSigner.APP_KEY, timestamp, nonce))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
        // 同一 nonce + 正确签名仍可通过
        mockMvc.perform(signedVerify(TestSigner.APP_SECRET, TestSigner.APP_KEY, timestamp, nonce))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void replayedNonceRejectedWith40102() throws Exception {
        String timestamp = TestSigner.now();
        String nonce = TestSigner.newNonce();
        mockMvc.perform(signedVerify(TestSigner.APP_SECRET, TestSigner.APP_KEY, timestamp, nonce))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(signedVerify(TestSigner.APP_SECRET, TestSigner.APP_KEY, timestamp, nonce))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40102))
                .andExpect(jsonPath("$.message").value("请求重复"));
    }

    @Test
    void validVerifyReturnsContractShape() throws Exception {
        mockMvc.perform(TestSigner.signedGet("/api/auth/verify", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.appKey").value("demo-app"))
                .andExpect(jsonPath("$.data.name").value("测试客户端"))
                .andExpect(jsonPath("$.data.serverTime").isNumber())
                .andExpect(jsonPath("$.data.deviceBindingEnabled").value(false))
                .andExpect(jsonPath("$.data.device").value(nullValue()))
                .andExpect(jsonPath("$.data.limits.maxFileSize").value(1048576))
                // 已取消扩展名白名单，limits 中不再返回 allowedExtensions
                .andExpect(jsonPath("$.data.limits.allowedExtensions").doesNotExist())
                // 新增只读字段（向后兼容）：服务端生效的存储根目录绝对路径
                .andExpect(jsonPath("$.data.storageDir").isString());
    }

    @Test
    void nonUtf8JsonBodyRejectedWith40000() throws Exception {
        byte[] body = {(byte) 0xFF, (byte) 0xFE, (byte) 0x80, 0x7B, 0x7D};
        MockHttpServletRequestBuilder builder = post("/api/auth/activate");
        builder.contentType(MediaType.APPLICATION_JSON).content(body);
        TestSigner.withSignature(builder, "POST", "/api/auth/activate", null, TestSigner.sha256(body),
                TestSigner.APP_KEY, TestSigner.APP_SECRET, TestSigner.now(), TestSigner.newNonce(), null, null);
        mockMvc.perform(builder)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void nonUtf8CharsetRejectedWith40000() throws Exception {
        byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MockHttpServletRequestBuilder builder = post("/api/auth/activate");
        builder.contentType("application/json;charset=GBK").content(body);
        TestSigner.withSignature(builder, "POST", "/api/auth/activate", null, TestSigner.sha256(body),
                TestSigner.APP_KEY, TestSigner.APP_SECRET, TestSigner.now(), TestSigner.newNonce(), null, null);
        mockMvc.perform(builder)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void jsonBodyIsForwardedToControllerAfterSignatureCheck() throws Exception {
        String json = "{\"deviceId\":\"" + java.util.UUID.randomUUID() + "\",\"deviceName\":\"我的 MacBook\"}";
        byte[] body = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MockHttpServletRequestBuilder builder = post("/api/auth/activate");
        builder.contentType(MediaType.APPLICATION_JSON).content(body);
        // 用 second-app 激活，避免影响本上下文 demo-app 的「未绑定」状态
        TestSigner.withSignature(builder, "POST", "/api/auth/activate", null, TestSigner.sha256(body),
                TestSigner.SECOND_APP_KEY, TestSigner.SECOND_APP_SECRET,
                TestSigner.now(), TestSigner.newNonce(), null, null);
        mockMvc.perform(builder)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.appKey").value(TestSigner.SECOND_APP_KEY))
                .andExpect(jsonPath("$.data.deviceName").value("我的 MacBook"))
                .andExpect(jsonPath("$.data.boundAt", startsWith("20")))
                .andExpect(header().string("Content-Type", startsWith("application/json")));
    }

    private MockHttpServletRequestBuilder signedVerify(String secret, String appKey, String timestamp, String nonce) {
        return TestSigner.withSignature(get("/api/auth/verify"), "GET", "/api/auth/verify", null,
                SignatureService.emptyPayloadHash(), appKey, secret, timestamp, nonce, null, null);
    }
}
