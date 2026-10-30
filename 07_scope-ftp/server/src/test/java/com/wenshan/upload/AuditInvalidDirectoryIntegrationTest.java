package com.wenshan.upload;

import com.wenshan.upload.audit.AuditLogService;
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
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 审计目录不可用（配置成普通文件）时：禁用审计、error 日志，但业务请求不受影响。 */
@SpringBootTest(properties = "app.test.suite=audit-bad-dir")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditInvalidDirectoryIntegrationTest extends IntegrationTestSupport {

    private static final Path BAD_DIR;

    static {
        try {
            BAD_DIR = Files.createTempFile("audit-bad-dir-", ".file");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void badDir(DynamicPropertyRegistry registry) {
        registry.add("app.audit.dir", BAD_DIR::toString);
    }

    @Autowired
    private AuditLogService auditLogService;

    @Test
    void unavailableAuditDirectoryDoesNotBreakRequests() throws Exception {
        assertThat(auditLogService.isActive()).isFalse();
        mockMvc.perform(TestSigner.signedGet("/api/files", null)).andExpect(status().isOk());
    }
}
