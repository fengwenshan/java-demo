package com.wenshan.upload.audit;

import com.wenshan.upload.config.AppProperties;
import com.wenshan.upload.dto.AuditLogItem;
import com.wenshan.upload.dto.AuditLogPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 旧审计库（无 relay_via 列）升级：启动自动 ALTER TABLE ADD COLUMN，
 * 旧行 relay_via 为 NULL（查询映射为空列表），新事件带来源链可正常写入/查询。
 */
class AuditStoreMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void addsRelayViaColumnToOldDatabase() throws Exception {
        Path db = tempDir.resolve(AuditStore.DB_FILE_NAME);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE audit_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        ts TEXT, request_id TEXT, app_key TEXT, client_name TEXT, device_id TEXT,
                        client_ip TEXT, method TEXT, path TEXT, query TEXT, action TEXT,
                        root TEXT, target_root TEXT, path_param TEXT, target_dir TEXT,
                        session_id TEXT, terminal_command TEXT, body TEXT, content_sha256 TEXT,
                        request_bytes INTEGER, response_bytes INTEGER, duration_ms INTEGER,
                        http_status INTEGER, success INTEGER, error TEXT, user_agent TEXT,
                        file_name TEXT, created_at INTEGER NOT NULL
                    )""");
            statement.executeUpdate("INSERT INTO audit_log(ts, app_key, created_at) "
                    + "VALUES ('2026-01-01T00:00:00Z', 'old-app', 1)");
        }
        AppProperties properties = new AppProperties();
        properties.getAudit().setEnabled(true);
        properties.getAudit().setDir(tempDir.toString());

        AuditStore store = new AuditStore(properties);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(audit_log)")) {
            boolean found = false;
            while (resultSet.next()) {
                if ("relay_via".equals(resultSet.getString("name"))) {
                    found = true;
                }
            }
            assertThat(found).isTrue();
            assertThat(Files.exists(db)).isTrue();
        }

        // 旧行：relay_via 为 NULL → 查询映射为空列表
        AuditLogPage legacy = store.query(new AuditStore.Query(
                "old-app", 1, 10, null, null, null, null, null));
        assertThat(legacy.total()).isEqualTo(1);
        assertThat(legacy.items().get(0).relayVia()).isEmpty();

        // 新行：来源链写入 SQLite 后可查询
        List<RelayViaHop> via = List.of(
                new RelayViaHop("a-app", "A", "http://10.0.0.1:8080"),
                new RelayViaHop("b-app", "B", "http://10.0.0.2:8080"));
        assertThat(store.insert(event("new-app", via))).isTrue();
        AuditLogPage page = store.query(new AuditStore.Query(
                "new-app", 1, 10, null, null, null, null, null));
        assertThat(page.total()).isEqualTo(1);
        AuditLogItem item = page.items().get(0);
        assertThat(item.relayVia()).isEqualTo(via);
        assertThat(item.action()).isEqualTo("relay.files.list");
        assertThat(item.path()).isEqualTo("/api/relay/api/files");
    }

    private static AuditEvent event(String appKey, List<RelayViaHop> relayVia) {
        String ts = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        return new AuditEvent(ts, "req-" + System.nanoTime(), appKey, "测试客户端", "device-1",
                "127.0.0.1", "GET", "/api/relay/api/files", null, "relay.files.list",
                "default", null, "docs/a.txt", null, null, null, null, null,
                0L, 12L, 3L, 200, true, null, "JUnit", null, relayVia);
    }
}
