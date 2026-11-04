package com.wenshan.upload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshan.upload.dto.ActivateRequest;
import com.wenshan.upload.security.SignatureService;
import com.wenshan.upload.support.IntegrationTestSupport;
import com.wenshan.upload.support.TestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SignatureFilter 对 /api/relay 路径的设备绑定 / 限流成本必须按剥离前缀后的业务路径计算：
 * 否则 A/B 上的设备绑定不生效、relay 上传只扣 1 个令牌。
 *
 * <p>relay 关闭（app.relay.enabled=false），请求通过签名/绑定/限流后由 RelayForwardFilter 返回 40320，
 * 用返回码区分「被签名过滤器拦截（40300 / 42900）」与「到达转发过滤器（40320）」。</p>
 */
@SpringBootTest(properties = {
        "app.security.device-binding-enabled=true",
        "app.security.rate-limit.capacity=1",
        "app.security.rate-limit.refill-per-second=0",
        "app.relay.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SignatureRelayPathTest extends IntegrationTestSupport {

    private static final String BOUND_DEVICE = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_DEVICE = "22222222-2222-2222-2222-222222222222";

    @Test
    void relayPathUsesEffectivePathForDeviceBindingAndUploadCost() throws Exception {
        // 1. 绑定 demo-app 的设备
        byte[] activateBody = new ObjectMapper()
                .writeValueAsBytes(new ActivateRequest(BOUND_DEVICE, "测试设备"));
        mockMvc.perform(TestSigner.signedJsonPost(post("/api/auth/activate"),
                        "/api/auth/activate", activateBody))
                .andExpect(status().isOk());

        // 2. /api/relay/api/files 是文件接口：设备不匹配 → 40300（而不是放行到转发过滤器 40320）
        rateLimitService.clear();
        mockMvc.perform(signedGetWithDevice("/api/relay/api/files", "relay-files-binding", OTHER_DEVICE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        // 3. /api/relay/api/files/upload/init 是上传：成本 5 > capacity 1 → 42900
        rateLimitService.clear();
        byte[] uploadBody = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MockHttpServletRequestBuilder upload = post("/api/relay/api/files/upload/init")
                .contentType("application/json").content(uploadBody);
        upload = TestSigner.withSignature(upload, "POST", "/api/relay/api/files/upload/init", null,
                TestSigner.sha256(uploadBody), TestSigner.APP_KEY, TestSigner.APP_SECRET,
                TestSigner.now(), TestSigner.newNonce(), BOUND_DEVICE, null);
        mockMvc.perform(upload)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42900));

        // 4. 普通 relay GET 成本 1：签名/绑定/限流通过后到达转发过滤器（relay 关闭 → 40320）
        rateLimitService.clear();
        mockMvc.perform(signedGetWithDevice("/api/relay/api/files", "relay-files-ok", BOUND_DEVICE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40320));
    }

    private MockHttpServletRequestBuilder signedGetWithDevice(String path, String query, String deviceId) {
        MockHttpServletRequestBuilder builder = get(path);
        return TestSigner.withSignature(builder, "GET", path, query,
                SignatureService.emptyPayloadHash(), TestSigner.APP_KEY, TestSigner.APP_SECRET,
                TestSigner.now(), TestSigner.newNonce(), deviceId, null);
    }
}
