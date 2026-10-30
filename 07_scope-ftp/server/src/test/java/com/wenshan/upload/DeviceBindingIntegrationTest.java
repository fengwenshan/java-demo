package com.wenshan.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenshan.upload.support.IntegrationTestSupport;
import com.wenshan.upload.support.TestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 契约 3.2 / 3.3 / 2.6 第 6 步：设备激活与设备绑定 40300。
 */
@SpringBootTest(properties = {
        "app.test.suite=device-binding",
        "app.security.device-binding-enabled=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeviceBindingIntegrationTest extends IntegrationTestSupport {

    @Test
    void deviceBindingLifecycle() throws Exception {
        String deviceA = UUID.randomUUID().toString();
        String deviceB = UUID.randomUUID().toString();

        // 激活前：device 为 null，deviceBindingEnabled 为 true
        mockMvc.perform(TestSigner.signedGet("/api/auth/verify", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.device").value(nullValue()))
                .andExpect(jsonPath("$.data.deviceBindingEnabled").value(true));

        // 激活设备 A
        JsonNode activateBody = activate(deviceA, "我的 MacBook");
        String boundAt = activateBody.path("data").path("boundAt").asText();
        org.assertj.core.api.Assertions.assertThat(boundAt).endsWith("Z");

        mockMvc.perform(TestSigner.signedGet("/api/auth/verify", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.device.deviceId").value(deviceA))
                .andExpect(jsonPath("$.data.device.deviceName").value("我的 MacBook"));

        // 携带正确 deviceId 可访问文件接口
        mockMvc.perform(TestSigner.signedGetWithDevice("/api/files", null, deviceA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 缺少 X-Device-Id → 40300
        mockMvc.perform(TestSigner.signedGet("/api/files", null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300))
                .andExpect(jsonPath("$.message").value("设备未授权"));

        // 携带其它 deviceId → 40300
        mockMvc.perform(TestSigner.signedGetWithDevice("/api/files", null, deviceB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        // 已绑定其它设备时再用 B 激活 → 40300
        signedActivate(deviceB, "另一台设备")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        // 非文件接口（verify）不受设备绑定限制
        mockMvc.perform(TestSigner.signedGet("/api/auth/verify", null))
                .andExpect(status().isOk());
    }

    @Test
    void activateValidatesRequestBody() throws Exception {
        // deviceId 非 UUID → 40000
        byte[] invalidDeviceId = objectMapper.writeValueAsBytes(Map.of("deviceId", "not-a-uuid", "deviceName", "设备"));
        mockMvc.perform(signedActivateRequest(invalidDeviceId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        // deviceName 为空 → 40000
        byte[] emptyName = objectMapper.writeValueAsBytes(
                Map.of("deviceId", UUID.randomUUID().toString(), "deviceName", ""));
        mockMvc.perform(signedActivateRequest(emptyName))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    private JsonNode activate(String deviceId, String deviceName) throws Exception {
        byte[] body = objectMapper.writeValueAsBytes(Map.of("deviceId", deviceId, "deviceName", deviceName));
        return body(mockMvc.perform(signedActivateRequest(body)));
    }

    private ResultActions signedActivate(String deviceId, String deviceName) throws Exception {
        byte[] body = objectMapper.writeValueAsBytes(Map.of("deviceId", deviceId, "deviceName", deviceName));
        return mockMvc.perform(signedActivateRequest(body));
    }

    private MockHttpServletRequestBuilder signedActivateRequest(byte[] body) {
        MockHttpServletRequestBuilder builder = post("/api/auth/activate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        return TestSigner.withSignature(builder, "POST", "/api/auth/activate", null,
                TestSigner.sha256(body), TestSigner.APP_KEY, TestSigner.APP_SECRET,
                TestSigner.now(), TestSigner.newNonce(), null, null);
    }
}
