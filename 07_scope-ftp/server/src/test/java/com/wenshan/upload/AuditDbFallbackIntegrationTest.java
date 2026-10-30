package com.wenshan.upload;

import com.wenshan.upload.audit.AuditLogService;
import com.wenshan.upload.audit.AuditStore;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.support.IntegrationTestSupport;
import com.wenshan.upload.support.TestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审计 SQLite 损坏/不可用降级：JSONL 继续写、业务请求不受影响，查询接口返回 50000 明确错误。
 */
@SpringBootTest(properties = "app.test.suite=audit-db-fallback")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditDbFallbackIntegrationTest extends IntegrationTestSupport {

    private static final Path AUDIT_DIR;

    static {
        try {
            AUDIT_DIR = Files.createTempDirectory("audit-db-bad-");
            Files.writeString(AUDIT_DIR.resolve("audit.sqlite3"), "this is not a sqlite database");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void auditDir(DynamicPropertyRegistry registry) {
        registry.add("app.audit.dir", AUDIT_DIR::toString);
    }

    @Autowired
    private AuditStore auditStore;

    @Autowired
    private AuditLogService auditLogService;

    @Test
    void corruptAuditDatabaseDegradesToJsonlOnly() throws Exception {
        assertThat(auditStore.isAvailable()).isFalse();
        assertThat(auditLogService.isActive()).isTrue();

        mockMvc.perform(TestSigner.signedGet("/api/files", null)).andExpect(status().isOk());
        awaitJsonlLine();

        mockMvc.perform(TestSigner.signedGet("/api/audit/logs", null))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ErrorCode.INTERNAL_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value("审计日志存储不可用"));
    }

    private void awaitJsonlLine() throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try (Stream<Path> files = Files.list(AUDIT_DIR)) {
                for (Path file : files.filter(item -> item.getFileName().toString().startsWith("audit-")
                        && item.getFileName().toString().endsWith(".jsonl")).toList()) {
                    if (!Files.readAllLines(file, StandardCharsets.UTF_8).isEmpty()) {
                        return;
                    }
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("等待 JSONL 审计行超时");
    }
}
