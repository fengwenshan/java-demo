package com.wenshan.upload.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 审计脱敏/截断/字段提取/action 映射单元测试。 */
class AuditSanitizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void masksSensitiveBodyFieldsAndKeepsOthers() throws Exception {
        byte[] body = ("{\"root\":\"work\",\"path\":\"docs/a.txt\",\"content\":\"TOP-SECRET-BODY\","
                + "\"appSecret\":\"APP-SECRET\",\"nested\":{\"password\":\"PWD\",\"token\":\"TOK\"},"
                + "\"items\":[{\"secret\":\"S1\"},{\"ok\":true}]}")
                .getBytes(StandardCharsets.UTF_8);
        String sanitized = AuditSanitizer.sanitizeBody(body, 4000);
        assertThat(sanitized)
                .doesNotContain("TOP-SECRET-BODY", "APP-SECRET", "PWD", "TOK", "S1")
                .contains("\"content\":\"***\"", "\"appSecret\":\"***\"", "\"password\":\"***\"",
                        "\"token\":\"***\"", "\"secret\":\"***\"", "\"root\":\"work\"",
                        "\"path\":\"docs/a.txt\"", "\"ok\":true");
    }

    @Test
    void truncatesBodyAtMaxChars() {
        byte[] body = ("{\"content\":\"x\",\"data\":\"" + "a".repeat(200) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        String sanitized = AuditSanitizer.sanitizeBody(body, 50);
        assertThat(sanitized).endsWith(AuditSanitizer.TRUNCATED_SUFFIX);
        assertThat(sanitized).hasSize(50 + AuditSanitizer.TRUNCATED_SUFFIX.length());
    }

    @Test
    void doesNotFallbackToRawBodyWhenNotJson() {
        assertThat(AuditSanitizer.sanitizeBody("not json at all".getBytes(StandardCharsets.UTF_8), 4000))
                .isNull();
    }

    @Test
    void masksSensitiveQueryValuesOnly() {
        String query = "root=work&token=QUERY-TOKEN&secret=QUERY-SECRET&path=docs%2Fa.txt&nonce=NONCE";
        String sanitized = AuditSanitizer.sanitizeQuery(query);
        assertThat(sanitized)
                .doesNotContain("QUERY-TOKEN", "QUERY-SECRET", "NONCE")
                .contains("root=work", "token=***", "secret=***", "nonce=***", "path=docs%2Fa.txt");
        assertThat(AuditSanitizer.sanitizeQuery(null)).isNull();
        assertThat(AuditSanitizer.sanitizeQuery("  ")).isNull();
    }

    @Test
    void extractsMetaFromJsonAndQueryFallback() {
        byte[] body = "{\"root\":\"work\",\"targetRoot\":\"media\",\"path\":\"docs/a.txt\",\"targetDir\":\"sub\"}"
                .getBytes(StandardCharsets.UTF_8);
        AuditSanitizer.Meta meta = AuditSanitizer.parseMeta(body, "path=query-path&sessionId=sess-1");
        assertThat(meta.root()).isEqualTo("work");
        assertThat(meta.targetRoot()).isEqualTo("media");
        assertThat(meta.pathParam()).isEqualTo("docs/a.txt");
        assertThat(meta.targetDir()).isEqualTo("sub");
        assertThat(meta.sessionId()).isEqualTo("sess-1");

        AuditSanitizer.Meta fromQuery = AuditSanitizer.parseMeta(null, "root=work&path=a%2Fb.txt");
        assertThat(fromQuery.root()).isEqualTo("work");
        assertThat(fromQuery.pathParam()).isEqualTo("a/b.txt");
    }

    @Test
    void terminalCommandIsExtractedInFull() {
        String command = "echo audit; cd docs && cat a.txt";
        byte[] body = ("{\"root\":\"work\",\"sessionId\":\"s1\",\"command\":\"" + command + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        AuditSanitizer.Meta meta = AuditSanitizer.parseMeta(body, null);
        assertThat(meta.terminalCommand()).isEqualTo(command);
        assertThat(meta.sessionId()).isEqualTo("s1");
    }

    @Test
    void mapsKnownActionsAndFallsBackForUnknown() {
        assertThat(AuditAction.resolve("GET", "/api/auth/verify")).isEqualTo("auth.verify");
        assertThat(AuditAction.resolve("POST", "/api/auth/activate")).isEqualTo("auth.activate");
        assertThat(AuditAction.resolve("GET", "/api/storage/roots")).isEqualTo("roots.list");
        assertThat(AuditAction.resolve("GET", "/api/files")).isEqualTo("files.list");
        assertThat(AuditAction.resolve("GET", "/api/files/dir")).isEqualTo("dir.list");
        assertThat(AuditAction.resolve("GET", "/api/files/search")).isEqualTo("search");
        assertThat(AuditAction.resolve("GET", "/api/audit/logs")).isEqualTo("audit.list");
        assertThat(AuditAction.resolve("GET", "/api/files/download")).isEqualTo("download");
        assertThat(AuditAction.resolve("GET", "/api/files/abc-123/download")).isEqualTo("download.byId");
        assertThat(AuditAction.resolve("POST", "/api/files/upload")).isEqualTo("upload");
        assertThat(AuditAction.resolve("POST", "/api/files/upload/init")).isEqualTo("upload.init");
        assertThat(AuditAction.resolve("PUT", "/api/files/upload/u1/chunk")).isEqualTo("upload.chunk");
        assertThat(AuditAction.resolve("POST", "/api/files/upload/u1/complete")).isEqualTo("upload.complete");
        assertThat(AuditAction.resolve("GET", "/api/files/upload/u1")).isEqualTo("upload.status");
        assertThat(AuditAction.resolve("GET", "/api/files/upload/u1/status")).isEqualTo("upload.status");
        assertThat(AuditAction.resolve("DELETE", "/api/files/upload/u1")).isEqualTo("upload.cancel");
        assertThat(AuditAction.resolve("GET", "/api/files/content")).isEqualTo("content.read");
        assertThat(AuditAction.resolve("PUT", "/api/files/content")).isEqualTo("content.save");
        assertThat(AuditAction.resolve("POST", "/api/files/rename")).isEqualTo("rename");
        assertThat(AuditAction.resolve("DELETE", "/api/files")).isEqualTo("delete");
        assertThat(AuditAction.resolve("DELETE", "/api/files/id-1")).isEqualTo("delete");
        assertThat(AuditAction.resolve("POST", "/api/files/extract")).isEqualTo("extract");
        assertThat(AuditAction.resolve("POST", "/api/files/copy")).isEqualTo("copy");
        assertThat(AuditAction.resolve("POST", "/api/files/move")).isEqualTo("move");
        assertThat(AuditAction.resolve("POST", "/api/terminal/exec")).isEqualTo("terminal.exec");
        assertThat(AuditAction.resolve("GET", "/api/unknown/thing")).isEqualTo("GET /api/unknown/thing");
        assertThat(AuditAction.resolve("POST", "/api/files/whatever")).isEqualTo("POST /api/files/whatever");
    }
}
