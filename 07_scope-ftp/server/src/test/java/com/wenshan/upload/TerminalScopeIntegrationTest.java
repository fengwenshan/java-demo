package com.wenshan.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenshan.upload.dto.TerminalExecRequest;
import com.wenshan.upload.storage.StorageRootRegistry;
import com.wenshan.upload.support.IntegrationTestSupport;
import com.wenshan.upload.support.TestSigner;
import com.wenshan.upload.terminal.TerminalSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 终端安全范围集成测试（契约 3.21 收紧）：默认/指定 root 的 cwd、跨根 cd、根内读写成功、
 * 根外路径/重定向/symlink 逃逸拒绝、旧会话 cwd 越界拒绝。
 */
@SpringBootTest(properties = {
        "app.test.suite=terminal-scope",
        "app.storage.roots[0].id=root-a",
        "app.storage.roots[0].name=根A",
        "app.storage.roots[0].path=target/test-data/terminal-scope-a",
        "app.storage.roots[1].id=root-b",
        "app.storage.roots[1].name=根B",
        "app.storage.roots[1].path=target/test-data/terminal-scope-b"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TerminalScopeIntegrationTest extends IntegrationTestSupport {

    private static final String EXEC_PATH = "/api/terminal/exec";

    @Autowired
    private StorageRootRegistry registry;

    @Autowired
    private TerminalSessionStore sessionStore;

    private Path rootA;
    private Path rootB;

    @BeforeEach
    void resetSecondRoot() throws IOException {
        rootA = registry.findRoot("root-a").orElseThrow().path().toRealPath();
        rootB = registry.findRoot("root-b").orElseThrow().path().toRealPath();
        wipeUserContentOf(rootB);
    }

    @Test
    void defaultCwdIsFirstRoot() throws Exception {
        JsonNode data = execData(newSessionId(), "pwd", null, null);
        assertThat(Path.of(data.path("output").asText().trim())).isEqualTo(rootA);
        assertThat(Path.of(data.path("cwd").asText())).isEqualTo(rootA);
    }

    @Test
    void rootParamSelectsSecondRoot() throws Exception {
        JsonNode data = execData(newSessionId(), "pwd", null, "root-b");
        assertThat(Path.of(data.path("cwd").asText())).isEqualTo(rootB);
        assertThat(Path.of(data.path("output").asText().trim())).isEqualTo(rootB);
    }

    @Test
    void unknownRootFallsBackToFirstRoot() throws Exception {
        JsonNode data = execData(newSessionId(), "pwd", null, "no-such-root");
        assertThat(Path.of(data.path("cwd").asText())).isEqualTo(rootA);
    }

    @Test
    void requestedCwdTakesPriorityAndMustBeInsideRoots() throws Exception {
        // requestedCwd 在 rootB 内 → 即使 root=root-a 也以 cwd 为准
        JsonNode data = execData(newSessionId(), "pwd", rootB.toString(), "root-a");
        assertThat(Path.of(data.path("cwd").asText())).isEqualTo(rootB);

        // 根外 cwd → 40000
        mockMvc.perform(signedExec(newSessionId(), "pwd", "/tmp", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("目录不在安全范围")));
    }

    @Test
    void crossRootCdIsAllowed() throws Exception {
        String sessionId = newSessionId();
        JsonNode cd = execData(sessionId, "cd " + rootB, null, null);
        assertThat(Path.of(cd.path("cwd").asText())).isEqualTo(rootB);
        JsonNode pwd = execData(sessionId, "pwd", null, null);
        assertThat(Path.of(pwd.path("output").asText().trim())).isEqualTo(rootB);
        // 跨回 rootA 也允许
        JsonNode back = execData(sessionId, "cd " + rootA, null, null);
        assertThat(Path.of(back.path("cwd").asText())).isEqualTo(rootA);
    }

    @Test
    void readAndWriteInsideRootSucceeds() throws Exception {
        Path file = rootA.resolve("scope-note.txt");
        execData(newSessionId(), "echo hi > scope-note.txt", null, null);
        assertThat(file).exists();
        assertThat(Files.readString(file)).contains("hi");
        JsonNode cat = execData(newSessionId(), "cat scope-note.txt", null, null);
        assertThat(cat.path("output").asText()).contains("hi");
    }

    @Test
    void outsidePathRejectedWithoutCreatingAnything() throws Exception {
        Path marker = Path.of("/tmp/terminal-scope-marker-" + UUID.randomUUID() + ".txt");
        Files.deleteIfExists(marker);
        mockMvc.perform(signedExec(newSessionId(), "touch " + marker, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("命令涉及非安全目录")));
        assertThat(marker).doesNotExist();

        mockMvc.perform(signedExec(newSessionId(), "echo hi > /tmp/terminal-scope-redirect.txt", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("命令涉及非安全目录")));
        assertThat(Path.of("/tmp/terminal-scope-redirect.txt")).doesNotExist();
    }

    @Test
    void systemFilesRejected() throws Exception {
        mockMvc.perform(signedExec(newSessionId(), "cat /etc/hosts", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("命令涉及非安全目录：/etc/hosts")));
        mockMvc.perform(signedExec(newSessionId(), "find / -name x", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("命令涉及非安全目录")));
        mockMvc.perform(signedExec(newSessionId(), "cd /tmp", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("目录不在安全范围")));
    }

    @Test
    void symlinkEscapeRejected() throws Exception {
        Path link = rootA.resolve("link-etc");
        Files.deleteIfExists(link);
        Files.createSymbolicLink(link, Path.of("/etc"));
        mockMvc.perform(signedExec(newSessionId(), "cat link-etc/hosts", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("命令涉及非安全目录：link-etc/hosts")));
        mockMvc.perform(signedExec(newSessionId(), "cat " + link.resolve("hosts"), null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("命令涉及非安全目录")));
    }

    @Test
    void relativeEscapeRejected() throws Exception {
        Files.createDirectories(rootA.resolve("docs"));
        String sessionId = newSessionId();
        execData(sessionId, "cd docs", null, null);
        mockMvc.perform(signedExec(sessionId, "cat ../../etc/hosts", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("命令涉及非安全目录")));
    }

    @Test
    void oldSessionCwdOutsideRootIsRejected() throws Exception {
        String sessionId = newSessionId();
        execData(sessionId, "pwd", null, null);
        // 模拟历史会话 cwd 落在根外（根变更/内存被篡改）
        TerminalSessionStore.Session session = sessionStore.getOrCreate(
                TestSigner.APP_KEY + "\u0000" + sessionId, null, null);
        session.changeCwd(Path.of("/tmp"));
        mockMvc.perform(signedExec(sessionId, "pwd", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("目录不在安全范围")));
    }

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

    private void wipeUserContentOf(Path root) throws IOException {
        Files.createDirectories(root);
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            for (Path child : children) {
                if (com.wenshan.upload.storage.StoragePaths.INTERNAL_META_DIR
                        .equals(child.getFileName().toString())) {
                    continue;
                }
                deleteRecursively(child);
            }
        }
    }

    private static String newSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
