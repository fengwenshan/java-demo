package com.wenshan.upload;

import com.wenshan.upload.dto.TerminalExecRequest;
import com.wenshan.upload.support.IntegrationTestSupport;
import com.wenshan.upload.support.TestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 终端接口令牌消耗 5：令牌桶容量恰好 5 时，第一次成功执行即耗尽，第二次应 42900。
 * （若终端被误算成 1 个令牌，第二次仍会成功，本测试即可发现。）
 */
@SpringBootTest(properties = {
        "app.test.suite=terminal-rate",
        "app.security.rate-limit.capacity=5",
        "app.security.rate-limit.refill-per-second=0.001"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TerminalRateLimitIntegrationTest extends IntegrationTestSupport {

    private static final String EXEC_PATH = "/api/terminal/exec";

    @Test
    void terminalExecConsumesFiveTokens() throws Exception {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        mockMvc.perform(exec(sessionId, "echo first"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(exec(sessionId, "echo second"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42900));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder exec(
            String sessionId, String command) throws Exception {
        TerminalExecRequest request = new TerminalExecRequest(sessionId, command, null);
        return TestSigner.signedJsonPost(post(EXEC_PATH), EXEC_PATH,
                objectMapper.writeValueAsBytes(request));
    }
}
