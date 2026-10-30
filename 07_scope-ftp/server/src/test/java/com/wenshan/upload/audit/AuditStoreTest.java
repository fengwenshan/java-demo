package com.wenshan.upload.audit;

import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.config.AppProperties;
import com.wenshan.upload.dto.AuditLogItem;
import com.wenshan.upload.dto.AuditLogPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 审计 SQLite：建表/索引、插入、过滤/分页/排序、appKey 隔离、保留清理、重启保留、DB 不可用降级。 */
class AuditStoreTest {

    @TempDir
    Path tempDir;

    private AuditStore newStore(Path dir) {
        AppProperties properties = new AppProperties();
        properties.getAudit().setDir(dir.toString());
        properties.getAudit().setEnabled(true);
        return new AuditStore(properties);
    }

    @Test
    void createsSchemaAndIndexes() throws SQLException {
        AuditStore store = newStore(tempDir.resolve("audit"));
        assertThat(store.isAvailable()).isTrue();
        assertThat(store.dbPath()).isRegularFile();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + store.dbPath())) {
            assertThat(tableNames(connection)).contains("audit_log");
            assertThat(indexNames(connection)).contains(
                    "idx_audit_log_ts", "idx_audit_log_action", "idx_audit_log_app_key",
                    "idx_audit_log_request_id", "idx_audit_log_success", "idx_audit_log_app_ts");
        }
    }

    @Test
    void recreatesSchemaWhenAuditDbIsDeletedAtRuntime() throws IOException {
        AuditStore store = newStore(tempDir.resolve("audit"));
        Instant base = Instant.parse("2026-10-30T01:00:00Z");
        assertThat(store.insert(event("r1", "demo-app", "upload", "/api/files/upload", 200, true, base)))
                .isTrue();

        Files.delete(store.dbPath());

        AuditLogPage empty = store.query(new AuditStore.Query("demo-app", 1, 50, null, null, null, null, null));
        assertThat(empty.total()).isZero();
        assertThat(store.insert(event("r2", "demo-app", "upload", "/api/files/upload", 200, true, base.plusSeconds(1))))
                .isTrue();
        AuditLogPage after = store.query(new AuditStore.Query("demo-app", 1, 50, null, null, null, null, null));
        assertThat(after.total()).isEqualTo(1);
        assertThat(after.items().get(0).requestId()).isEqualTo("r2");
    }

    @Test
    void insertAndQueryWithFiltersPagingAndOrdering() {
        AuditStore store = newStore(tempDir.resolve("audit"));
        Instant base = Instant.parse("2026-10-30T01:00:00Z");
        store.insert(event("r1", "demo-app", "files.list", "/api/files", 200, true, base));
        store.insert(event("r2", "demo-app", "upload", "/api/files/upload", 200, true, base.plusSeconds(1)));
        store.insert(event("r3", "demo-app", "download", "/api/files/download", 404, false, base.plusSeconds(2)));
        store.insert(event("r4", "other-app", "files.list", "/api/files", 200, true, base.plusSeconds(3)));

        AuditLogPage all = store.query(new AuditStore.Query("demo-app", 1, 2, null, null, null, null, null));
        assertThat(all.total()).isEqualTo(3);
        assertThat(all.page()).isEqualTo(1);
        assertThat(all.size()).isEqualTo(2);
        assertThat(all.hasMore()).isTrue();
        assertThat(all.items()).extracting(AuditLogItem::requestId).containsExactly("r3", "r2"); // 时间倒序
        assertThat(all.items()).extracting(AuditLogItem::success).containsExactly(false, true);

        AuditLogPage second = store.query(new AuditStore.Query("demo-app", 2, 2, null, null, null, null, null));
        assertThat(second.items()).extracting(AuditLogItem::requestId).containsExactly("r1");
        assertThat(second.hasMore()).isFalse();

        AuditLogPage byAction = store.query(new AuditStore.Query(
                "demo-app", 1, 50, "upload", null, null, null, null));
        assertThat(byAction.total()).isEqualTo(1);
        assertThat(byAction.items().get(0).requestId()).isEqualTo("r2");

        AuditLogPage byActions = store.query(new AuditStore.Query(
                "demo-app", 1, 50, null, List.of("upload", "download"), null, null, null, null));
        assertThat(byActions.items()).extracting(AuditLogItem::requestId).containsExactly("r3", "r2");

        AuditLogPage bySuccess = store.query(new AuditStore.Query(
                "demo-app", 1, 50, null, false, null, null, null));
        assertThat(bySuccess.items()).extracting(AuditLogItem::requestId).containsExactly("r3");

        long from = base.plusSeconds(1).toEpochMilli();
        long to = base.plusSeconds(2).toEpochMilli();
        AuditLogPage byTime = store.query(new AuditStore.Query("demo-app", 1, 50, null, null, null, from, to));
        assertThat(byTime.items()).extracting(AuditLogItem::requestId).containsExactly("r3", "r2");

        // appKey 强制隔离
        AuditLogPage other = store.query(new AuditStore.Query("other-app", 1, 50, null, null, null, null, null));
        assertThat(other.total()).isEqualTo(1);
        assertThat(other.items().get(0).requestId()).isEqualTo("r4");
    }

    @Test
    void keywordSearchEscapesLikeWildcards() {
        AuditStore store = newStore(tempDir.resolve("audit"));
        Instant base = Instant.parse("2026-10-30T01:00:00Z");
        store.insert(event("r1", "demo-app", "files.list", "/api/files/100%_literal.txt", 200, true, base));
        store.insert(event("r2", "demo-app", "files.list", "/api/files/100Xliteral.txt", 200, true, base.plusSeconds(1)));

        AuditLogPage percent = store.query(new AuditStore.Query(
                "demo-app", 1, 50, null, null, "100%_literal", null, null));
        assertThat(percent.items()).extracting(AuditLogItem::requestId).containsExactly("r1");
        AuditLogPage percentOnly = store.query(new AuditStore.Query(
                "demo-app", 1, 50, null, null, "100%", null, null));
        assertThat(percentOnly.items()).extracting(AuditLogItem::requestId).containsExactly("r1");
    }

    @Test
    void retentionDeletesOnlyExpiredRows() {
        AuditStore store = newStore(tempDir.resolve("audit"));
        Instant old = Instant.parse("2020-01-01T00:00:00Z");
        Instant recent = Instant.now().minusSeconds(3600);
        store.insert(event("old", "demo-app", "files.list", "/api/files", 200, true, old));
        store.insert(event("recent", "demo-app", "files.list", "/api/files", 200, true, recent));

        long cutoff = Instant.now().minusSeconds(90 * 86_400L).toEpochMilli();
        assertThat(store.deleteOlderThan(cutoff)).isEqualTo(1);
        assertThat(store.query(new AuditStore.Query("demo-app", 1, 50, null, null, null, null, null))
                .items()).extracting(AuditLogItem::requestId).containsExactly("recent");
    }

    @Test
    void dataSurvivesStoreRestart() {
        Path dir = tempDir.resolve("audit");
        AuditStore store = newStore(dir);
        store.insert(event("r1", "demo-app", "upload", "/api/files/upload", 200, true, Instant.now()));

        AuditStore reloaded = newStore(dir);
        assertThat(reloaded.query(new AuditStore.Query("demo-app", 1, 50, null, null, null, null, null))
                .items()).extracting(AuditLogItem::requestId).containsExactly("r1");
    }

    @Test
    void unavailableDatabaseDisablesQueriesButDoesNotThrowOnInsert() throws IOException {
        Path dir = tempDir.resolve("audit");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(AuditStore.DB_FILE_NAME), "not a sqlite database");

        AuditStore store = newStore(dir);
        assertThat(store.isAvailable()).isFalse();
        assertThat(store.insert(event("r1", "demo-app", "files.list", "/api/files", 200, true, Instant.now())))
                .isFalse();
        assertThatThrownBy(() -> store.query(new AuditStore.Query(
                "demo-app", 1, 50, null, null, null, null, null)))
                .isInstanceOfSatisfying(BizException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        assertThat(store.deleteOlderThan(0)).isZero();
    }

    private static AuditEvent event(String requestId, String appKey, String action, String path,
                                    int status, boolean success, Instant createdAt) {
        String ts = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        return new AuditEvent(ts, requestId, appKey, "测试客户端", "device-1", "127.0.0.1",
                "GET", path, null, action, "default", null, "docs/a.txt", null, null, null,
                null, null, 0L, 12L, 3L, status, success, null, "JUnit", null);
    }

    private static List<String> tableNames(Connection connection) throws SQLException {
        return names(connection, "table");
    }

    private static List<String> indexNames(Connection connection) throws SQLException {
        return names(connection, "index");
    }

    private static List<String> names(Connection connection, String type) throws SQLException {
        List<String> result = new ArrayList<>();
        try (ResultSet resultSet = connection.createStatement().executeQuery(
                "SELECT name FROM sqlite_master WHERE type = '" + type + "'")) {
            while (resultSet.next()) {
                result.add(resultSet.getString(1));
            }
        }
        return result;
    }
}
