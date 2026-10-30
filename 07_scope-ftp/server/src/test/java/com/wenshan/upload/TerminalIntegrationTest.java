package com.wenshan.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenshan.upload.dto.TerminalExecRequest;
import com.wenshan.upload.support.IntegrationTestSupport;
import com.wenshan.upload.support.TestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 契约 3.21 远程终端集成测试：默认 cwd=存储根、echo/pwd/exitCode、cd 持久与 cd -、会话隔离、
 * 无效 cwd、安全目录限制（cd/路径参数/重定向）、高危命令不执行、超时、截断、参数校验、未签名。
 *
 * <p>本类把超时调为 1s、输出上限调为 200 字节以便验证超时与截断（需容纳测试根路径）。</p>
 */
@SpringBootTest(properties = {
        "app.test.suite=terminal",
        "app.terminal.timeout-seconds=1",
        "app.terminal.max-output-bytes=200"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TerminalIntegrationTest extends IntegrationTestSupport {

    private static final String EXEC_PATH = "/api/terminal/exec";

    @Test
    void echoReturnsOutputAndExitCode() throws Exception {
        String sessionId = newSessionId();
        mockMvc.perform(signedExec(sessionId, "echo hello", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sessionId").value(sessionId))
                .andExpect(jsonPath("$.data.command").value("echo hello"))
                .andExpect(jsonPath("$.data.output").value(containsString("hello")))
                .andExpect(jsonPath("$.data.exitCode").value(0))
                .andExpect(jsonPath("$.data.truncated").value(false))
                .andExpect(jsonPath("$.data.cwd").isNotEmpty())
                .andExpect(jsonPath("$.data.durationMs").isNumber());
    }

    @Test
    void pwdUsesFirstStorageRootAsCwd() throws Exception {
        JsonNode data = execData(newSessionId(), "pwd", null, null);
        Path root = storagePaths.root().toRealPath();
        assertThat(data.path("exitCode").asInt()).isZero();
        assertThat(Path.of(data.path("output").asText().trim())).isEqualTo(root);
        assertThat(Path.of(data.path("cwd").asText())).isEqualTo(root);
    }

    @Test
    void nonZeroExitStillReturnsCodeZero() throws Exception {
        mockMvc.perform(signedExec(newSessionId(), "exit 3", null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.exitCode").value(3));
    }

    @Test
    void cdInsideRootPersistsAndCdDashReturnsPrevious() throws Exception {
        Path root = storagePaths.root().toRealPath();
        Files.createDirectories(root.resolve("docs"));
        String sessionId = newSessionId();

        JsonNode cd = execData(sessionId, "cd docs", null, null);
        assertThat(cd.path("output").asText()).isEmpty();
        assertThat(cd.path("exitCode").asInt()).isZero();
        assertThat(Path.of(cd.path("cwd").asText())).isEqualTo(root.resolve("docs"));

        JsonNode pwd = execData(sessionId, "pwd", null, null);
        assertThat(Path.of(pwd.path("output").asText().trim())).isEqualTo(root.resolve("docs"));

        JsonNode back = execData(sessionId, "cd -", null, null);
        assertThat(Path.of(back.path("cwd").asText())).isEqualTo(root);

        // cd 无参数 → 当前根顶部
        execData(sessionId, "cd docs", null, null);
        JsonNode rootTop = execData(sessionId, "cd", null, null);
        assertThat(Path.of(rootTop.path("cwd").asText())).isEqualTo(root);

        // 绝对根内路径可 cd
        JsonNode absolute = execData(sessionId, "cd " + root.resolve("docs"), null, null);
        assertThat(Path.of(absolute.path("cwd").asText())).isEqualTo(root.resolve("docs"));
    }

    @Test
    void cdOutsideRootRejected() throws Exception {
        mockMvc.perform(signedExec(newSessionId(), "cd /tmp", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("目录不在安全范围")))
                .andExpect(jsonPath("$.message").value(containsString("/tmp")));
        mockMvc.perform(signedExec(newSessionId(), "cd ~", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("目录不在安全范围")));
    }

    @Test
    void sessionsAreIsolated() throws Exception {
        Path root = storagePaths.root().toRealPath();
        Files.createDirectories(root.resolve("docs"));
        String sessionA = newSessionId();
        String sessionB = newSessionId();
        execData(sessionA, "cd docs", null, null);

        JsonNode pwdA = execData(sessionA, "pwd", null, null);
        JsonNode pwdB = execData(sessionB, "pwd", null, null);
        assertThat(Path.of(pwdA.path("output").asText().trim())).isEqualTo(root.resolve("docs"));
        assertThat(Path.of(pwdB.path("output").asText().trim())).isEqualTo(root);
    }

    @Test
    void invalidCwdRejected() throws Exception {
        // 根外绝对目录
        mockMvc.perform(signedExec(newSessionId(), "pwd", "/tmp", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("目录不在安全范围")));
        // 非绝对路径
        mockMvc.perform(signedExec(newSessionId(), "pwd", "relative/path", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        // cd 到不存在目录
        mockMvc.perform(signedExec(newSessionId(), "cd /definitely/not/exists", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void outsidePathArgumentsAreRejectedWithoutExecuting() throws Exception {
        Path marker = Files.createTempFile("terminal-scope-marker", ".txt");
        Files.deleteIfExists(marker);
        mockMvc.perform(signedExec(newSessionId(), "touch " + marker, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("命令涉及非安全目录")))
                .andExpect(jsonPath("$.message").value(containsString(marker.toString())));
        assertThat(marker).doesNotExist();

        mockMvc.perform(signedExec(newSessionId(), "cat /etc/hosts", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("命令涉及非安全目录：/etc/hosts")));

        mockMvc.perform(signedExec(newSessionId(), "echo hi > /tmp/terminal-scope-x", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("命令涉及非安全目录：/tmp/terminal-scope-x")));
        assertThat(Path.of("/tmp/terminal-scope-x")).doesNotExist();
    }

    @Test
    void writeInsideRootSucceeds() throws Exception {
        Path root = storagePaths.root().toRealPath();
        String sessionId = newSessionId();
        execData(sessionId, "echo hi > out.txt", null, null);
        JsonNode cat = execData(sessionId, "cat out.txt", null, null);
        assertThat(cat.path("output").asText()).contains("hi");
        assertThat(root.resolve("out.txt")).exists();
    }

    @Test
    void highRiskCommandIsRejectedWithoutExecuting() throws Exception {
        Path marker = Files.createTempFile("terminal-guard-marker", ".txt");
        Files.deleteIfExists(marker);
        String command = "touch " + marker + " ; rm -rf /";
        mockMvc.perform(signedExec(newSessionId(), command, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("高危命令已禁止执行")))
                .andExpect(jsonPath("$.message").value(containsString("原因")));
        // 先高危后路径检查：整条命令被拒绝，touch 不应执行
        assertThat(marker).doesNotExist();
    }

    @Test
    void commandValidationRejectsBadInput() throws Exception {
        mockMvc.perform(signedExec(newSessionId(), "", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(signedExec(newSessionId(), "echo a\necho b", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(signedExec(newSessionId(), "echo " + "a".repeat(4096), null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void badSessionIdRejected() throws Exception {
        mockMvc.perform(signedExec("short", "echo hi", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(signedExec("bad id with spaces!!", "echo hi", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(signedExec("x".repeat(65), "echo hi", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void unsignedRequestRejected() throws Exception {
        TerminalExecRequest request = new TerminalExecRequest(newSessionId(), "echo hi", null);
        mockMvc.perform(post(EXEC_PATH).contentType("application/json")
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void timeoutKillsProcessAndReturnsExitCodeMinusOne() throws Exception {
        long start = System.currentTimeMillis();
        JsonNode data = execData(newSessionId(), "sleep 3", null, null);
        long elapsed = System.currentTimeMillis() - start;
        assertThat(data.path("exitCode").asInt()).isEqualTo(-1);
        assertThat(data.path("output").asText()).contains("超时");
        assertThat(elapsed).isLessThan(3000L);
    }

    @Test
    void outputIsTruncatedAtConfiguredLimit() throws Exception {
        JsonNode data = execData(newSessionId(), "printf 'x%.0s' $(seq 1 300)", null, null);
        assertThat(data.path("truncated").asBoolean()).isTrue();
        assertThat(data.path("output").asText()).hasSize(200);
        assertThat(data.path("exitCode").asInt()).isZero();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private JsonNode execData(String sessionId, String command, String cwd, String root) throws Exception {
        ResultActions actions = mockMvc.perform(signedExec(sessionId, command, cwd, root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        return body(actions).path("data");
    }

    private MockHttpServletRequestBuilder signedExec(String sessionId, String command,
                                                     String cwd, String root) {
        TerminalExecRequest request = new TerminalExecRequest(sessionId, command, cwd, root);
        try {
            byte[] payload = objectMapper.writeValueAsBytes(request);
            return TestSigner.signedJsonPost(post(EXEC_PATH), EXEC_PATH, payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String newSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
