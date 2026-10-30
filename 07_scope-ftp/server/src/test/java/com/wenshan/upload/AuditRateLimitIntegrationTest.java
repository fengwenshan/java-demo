package com.wenshan.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenshan.upload.support.IntegrationTestSupport;
import com.wenshan.upload.support.TestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 限流请求也必须写审计日志（容量 1、不补充：第二次 42900）。 */
@SpringBootTest(properties = {
        "app.test.suite=audit-rate",
        "app.security.rate-limit.capacity=1",
        "app.security.rate-limit.refill-per-second=0"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditRateLimitIntegrationTest extends IntegrationTestSupport {

    private static final Path AUDIT_DIR;

    static {
        try {
            AUDIT_DIR = Files.createTempDirectory("audit-rate-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void auditDir(DynamicPropertyRegistry registry) {
        registry.add("app.audit.dir", AUDIT_DIR::toString);
    }

    @Test
    void rateLimitedRequestIsAudited() throws Exception {
        mockMvc.perform(TestSigner.signedGet("/api/files", null)).andExpect(status().isOk());
        ResultActions rejected = mockMvc.perform(TestSigner.signedGet("/api/files", null))
                .andExpect(status().isTooManyRequests());
        String requestId = rejected.andReturn().getResponse().getHeader("X-Request-Id");
        assertThat(requestId).isNotBlank();

        JsonNode event = awaitAudit(requestId);
        assertThat(event.path("httpStatus").asInt()).isEqualTo(429);
        assertThat(event.path("success").asBoolean()).isFalse();
        assertThat(event.path("error").asText()).contains("42900");
        assertThat(event.path("action").asText()).isEqualTo("files.list");
    }

    private JsonNode awaitAudit(String requestId) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try (Stream<Path> files = Files.list(AUDIT_DIR)) {
                for (Path file : files.filter(item -> item.getFileName().toString().startsWith("audit-")).toList()) {
                    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                        JsonNode node = objectMapper.readTree(line);
                        if (requestId.equals(node.path("requestId").asText())) {
                            return node;
                        }
                    }
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("等待限流审计日志超时");
    }
}
