package com.wenshan.upload;

import com.wenshan.upload.dto.TerminalExecRequest;
import com.wenshan.upload.support.IntegrationTestSupport;
import com.wenshan.upload.support.TestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code app.terminal.enabled=false} 时终端接口应拒绝执行（40000「终端功能未启用」）。 */
@SpringBootTest(properties = {
        "app.test.suite=terminal-disabled",
        "app.terminal.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TerminalDisabledIntegrationTest extends IntegrationTestSupport {

    private static final String EXEC_PATH = "/api/terminal/exec";

    @Test
    void terminalReturnsDisabledErrorWhenSwitchedOff() throws Exception {
        TerminalExecRequest request = new TerminalExecRequest(
                UUID.randomUUID().toString().replace("-", ""), "echo hi", null);
        MockHttpServletRequestBuilder builder = TestSigner.signedJsonPost(post(EXEC_PATH), EXEC_PATH,
                objectMapper.writeValueAsBytes(request));
        mockMvc.perform(builder)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("终端功能未启用")));
    }
}
