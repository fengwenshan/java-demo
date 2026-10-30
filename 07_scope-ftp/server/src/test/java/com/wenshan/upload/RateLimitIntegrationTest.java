package com.wenshan.upload;

import com.wenshan.upload.security.SignatureService;
import com.wenshan.upload.support.IntegrationTestSupport;
import com.wenshan.upload.support.TestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 契约 2.6 第 7 步 / 第 4 节：令牌桶限流 42900，且无效签名不消耗令牌。
 */
@SpringBootTest(properties = {
        "app.test.suite=rate-limit",
        "app.security.rate-limit.capacity=1",
        "app.security.rate-limit.refill-per-second=0"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitIntegrationTest extends IntegrationTestSupport {

    @Test
    void invalidSignatureDoesNotConsumeTokenButValidRequestDoes() throws Exception {
        // 无效签名 → 40100，且在签名之后才限流，不消耗令牌
        mockMvc.perform(signedVerify(TestSigner.WRONG_SECRET))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));

        // 第一个签名通过的请求消耗唯一令牌 → 200
        mockMvc.perform(signedVerify(TestSigner.APP_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 令牌耗尽 → 42900
        mockMvc.perform(signedVerify(TestSigner.APP_SECRET))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42900))
                .andExpect(jsonPath("$.message").value("请求过于频繁"));
    }

    private MockHttpServletRequestBuilder signedVerify(String secret) {
        return TestSigner.withSignature(get("/api/auth/verify"), "GET", "/api/auth/verify", null,
                SignatureService.emptyPayloadHash(), TestSigner.APP_KEY, secret,
                TestSigner.now(), TestSigner.newNonce(), null, null);
    }
}
