package com.wenshan.upload.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshan.upload.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 审计异步写入/JSONL 格式/过期清理/不可用目录降级单元测试。 */
class AuditLogServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void writesOneJsonLinePerEventAndFlushesOnShutdown(@TempDir Path dir) throws Exception {
        AppProperties properties = new AppProperties();
        properties.getAudit().setDir(dir.resolve("audit").toString());
        properties.getAudit().setEnabled(true);
        AuditLogService service = new AuditLogService(properties, new AuditStore(properties));
        try {
            assertThat(service.isActive()).isTrue();
            service.record(event("req-1", "GET", "/api/files", "files.list", 200, true, null));
            service.record(event("req-2", "POST", "/api/files/copy", "copy", 400, false, "40000 x"));
            List<String> lines = awaitLines(dir.resolve("audit"), 2, 5000);
            assertThat(lines).hasSize(2);
            JsonNode first = MAPPER.readTree(lines.get(0));
            assertThat(first.path("requestId").asText()).isEqualTo("req-1");
            assertThat(first.path("appKey").asText()).isEqualTo("demo-app");
            assertThat(first.path("path").asText()).isEqualTo("/api/files");
            assertThat(first.path("success").asBoolean()).isTrue();
            assertThat(OffsetDateTime.parse(first.path("ts").asText())).isNotNull();
            JsonNode second = MAPPER.readTree(lines.get(1));
            assertThat(second.path("httpStatus").asInt()).isEqualTo(400);
            assertThat(second.path("success").asBoolean()).isFalse();
            assertThat(second.path("error").asText()).isEqualTo("40000 x");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void disabledAuditDoesNotWriteAnything(@TempDir Path dir) throws IOException {
        AppProperties properties = new AppProperties();
        properties.getAudit().setDir(dir.resolve("audit").toString());
        properties.getAudit().setEnabled(false);
        AuditLogService service = new AuditLogService(properties, new AuditStore(properties));
        try {
            assertThat(service.isActive()).isFalse();
            service.record(event("req", "GET", "/api/files", "files.list", 200, true, null));
            assertThat(dir.resolve("audit")).doesNotExist();
        } finally {
            service.shutdown();
        }
    }

    @Test
    void unavailableDirectoryDisablesAuditWithoutThrowing(@TempDir Path dir) throws IOException {
        Path asFile = Files.createFile(dir.resolve("not-a-dir"));
        AppProperties properties = new AppProperties();
        properties.getAudit().setDir(asFile.toString());
        properties.getAudit().setEnabled(true);
        AuditLogService service = new AuditLogService(properties, new AuditStore(properties));
        try {
            assertThat(service.isActive()).isFalse();
            service.record(event("req", "GET", "/api/files", "files.list", 200, true, null));
            assertThat(Files.readAllLines(asFile, StandardCharsets.UTF_8)).isEmpty();
        } finally {
            service.shutdown();
        }
    }


    @Test
    void writeFailureIsCaughtAndDoesNotReachCaller(@TempDir Path dir) throws Exception {
        Path auditDir = dir.resolve("audit");
        AppProperties properties = new AppProperties();
        properties.getAudit().setDir(auditDir.toString());
        properties.getAudit().setEnabled(true);
        AuditLogService service = new AuditLogService(properties, new AuditStore(properties));
        try {
            assertThat(service.isActive()).isTrue();
            // 启动后把目录换成同名普通文件：writer 打开当日文件会失败，只 warn，不影响 record 调用方
            try (java.util.stream.Stream<Path> paths = Files.walk(auditDir)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
            Files.createFile(auditDir);
            service.record(event("req-write-fail", "GET", "/api/files", "files.list", 200, true, null));
            Thread.sleep(300);
            assertThat(service.isActive()).isTrue();
            assertThat(service.droppedCount()).isZero();
        } finally {
            service.shutdown();
        }
    }

    @Test
    void cleanupDeletesOnlyExpiredDayFiles(@TempDir Path dir) throws IOException {
        Path old = Files.writeString(dir.resolve("audit-2020-01-01.jsonl"), "old\n");
        Path recent = Files.writeString(dir.resolve("audit-2024-01-30.jsonl"), "recent\n");
        Path keepOther = Files.writeString(dir.resolve("notes.txt"), "keep\n");

        int deleted = AuditLogService.cleanupHistory(dir, 30, LocalDate.of(2024, 1, 31));

        assertThat(deleted).isEqualTo(1);
        assertThat(old).doesNotExist();
        assertThat(recent).exists();
        assertThat(keepOther).exists();
    }

    private static AuditEvent event(String requestId, String method, String path, String action,
                                    int status, boolean success, String error) {
        return new AuditEvent(
                OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                requestId, "demo-app", "测试客户端", null, "127.0.0.1",
                method, path, null, action,
                "work", null, "docs/a.txt", null, null, null, null, null,
                0L, 12L, 3L, status, success, error, "JUnit", null);
    }

    private static List<String> awaitLines(Path dir, int expected, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.list(dir)) {
                    Path file = files.filter(item -> item.getFileName().toString().startsWith("audit-")).findFirst().orElse(null);
                    if (file != null) {
                        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        if (lines.size() >= expected) {
                            return lines;
                        }
                    }
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("等待审计日志 " + expected + " 行超时");
    }
}
