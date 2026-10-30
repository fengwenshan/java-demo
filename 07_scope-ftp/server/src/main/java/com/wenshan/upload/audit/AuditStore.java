package com.wenshan.upload.audit;

import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.config.AppProperties;
import com.wenshan.upload.dto.AuditLogItem;
import com.wenshan.upload.dto.AuditLogPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计日志 SQLite 存储（客户端查询数据源；JSONL 仍由 {@link AuditLogService} 双写保留）。
 *
 * <p>数据库固定为 {@code <app.audit.dir>/audit.sqlite3}，表 {@code audit_log}，时间字段存 epoch millis。
 * 启动建表（WAL、busy_timeout=5000）；DB 不可用只记录 error 并禁用查询（JSONL 不受影响），
 * 写入失败只告警。查询强制 appKey 过滤，关键字 LIKE 已转义，单页最多 200 条。</p>
 */
@Component
public class AuditStore {

    private static final Logger log = LoggerFactory.getLogger(AuditStore.class);
    public static final String DB_FILE_NAME = "audit.sqlite3";
    public static final int MAX_PAGE_SIZE = 200;
    private static final String COLUMNS = "id, ts, request_id, app_key, client_name, device_id, client_ip, "
            + "method, path, query, action, root, target_root, path_param, target_dir, session_id, "
            + "terminal_command, body, content_sha256, request_bytes, response_bytes, duration_ms, "
            + "http_status, success, error, user_agent, file_name, created_at";

    private final Path dir;
    private final Path dbPath;
    private final boolean available;
    private final AtomicLong writeFailures = new AtomicLong();

    public AuditStore(AppProperties properties) {
        AppProperties.Audit config = properties.getAudit();
        String configuredDir = config.getDir() == null || config.getDir().isBlank()
                ? "./logs/audit" : config.getDir().trim();
        this.dir = Path.of(configuredDir).toAbsolutePath().normalize();
        this.dbPath = this.dir.resolve(DB_FILE_NAME);
        if (!config.isEnabled()) {
            this.available = false;
            return;
        }
        boolean ready = false;
        try {
            Files.createDirectories(dir);
            initSchema();
            ready = true;
            log.info("审计 SQLite 已启用: {}", dbPath);
        } catch (IOException | SQLException | RuntimeException e) {
            log.error("审计 SQLite 初始化失败，客户端审计查询已禁用（JSONL 不受影响）: {} ({})",
                    dbPath, e.toString());
        }
        this.available = ready;
    }

    public boolean isAvailable() {
        return available;
    }

    public Path dir() {
        return dir;
    }

    public Path dbPath() {
        return dbPath;
    }

    /** 追加一条审计事件；失败只告警并返回 false（不影响 JSONL/业务请求）。 */
    public boolean insert(AuditEvent event) {
        if (!available || event == null) {
            return false;
        }
        try {
            return insertOnce(event);
        } catch (SQLException | RuntimeException e) {
            Throwable error = e;
            if (isMissingTable(error) && recoverSchema()) {
                try {
                    return insertOnce(event);
                } catch (SQLException | RuntimeException retry) {
                    error = retry;
                }
            }
            long failures = writeFailures.incrementAndGet();
            if (failures == 1 || failures % 100 == 0) {
                log.warn("审计 SQLite 写入失败（累计 {} 次，JSONL 不受影响）: {}", failures, error.toString());
            }
            return false;
        }
    }

    private boolean insertOnce(AuditEvent event) throws SQLException {
        try (Connection connection = open()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO audit_log(ts, request_id, app_key, client_name, device_id, client_ip, "
                            + "method, path, query, action, root, target_root, path_param, target_dir, "
                            + "session_id, terminal_command, body, content_sha256, request_bytes, "
                            + "response_bytes, duration_ms, http_status, success, error, user_agent, "
                            + "file_name, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                            + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                int index = 1;
                statement.setString(index++, event.ts());
                statement.setString(index++, event.requestId());
                statement.setString(index++, event.appKey());
                setNullableString(statement, index++, event.clientName());
                setNullableString(statement, index++, event.deviceId());
                setNullableString(statement, index++, event.clientIp());
                setNullableString(statement, index++, event.method());
                setNullableString(statement, index++, event.path());
                setNullableString(statement, index++, event.query());
                setNullableString(statement, index++, event.action());
                setNullableString(statement, index++, event.root());
                setNullableString(statement, index++, event.targetRoot());
                setNullableString(statement, index++, event.pathParam());
                setNullableString(statement, index++, event.targetDir());
                setNullableString(statement, index++, event.sessionId());
                setNullableString(statement, index++, event.terminalCommand());
                setNullableString(statement, index++, event.body());
                setNullableString(statement, index++, event.contentSha256());
                setNullableLong(statement, index++, event.requestBytes());
                setNullableLong(statement, index++, event.responseBytes());
                statement.setLong(index++, event.durationMs());
                statement.setInt(index++, event.httpStatus());
                statement.setInt(index++, event.success() ? 1 : 0);
                setNullableString(statement, index++, event.error());
                setNullableString(statement, index++, event.userAgent());
                setNullableString(statement, index, event.fileName());
                statement.setLong(index + 1, createdAtMillis(event.ts()));
                statement.executeUpdate();
            }
            return true;
        }
    }

    /**
     * 分页查询审计日志（强制 appKey；action 精确、success 精确、keyword 模糊、from/to 闭区间）。
     */
    public AuditLogPage query(Query query) {
        if (!available) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "审计日志存储不可用");
        }
        try {
            return queryPage(query);
        } catch (SQLException e) {
            if (isMissingTable(e) && recoverSchema()) {
                try {
                    return queryPage(query);
                } catch (SQLException retry) {
                    e = retry;
                }
            }
            log.warn("审计 SQLite 查询失败: {}", e.toString());
            throw new BizException(ErrorCode.INTERNAL_ERROR, "审计日志查询失败");
        }
    }

    private AuditLogPage queryPage(Query query) throws SQLException {
        try (Connection connection = open()) {
            List<Object> parameters = new ArrayList<>();
            String where = buildWhere(query, parameters);
            long total;
            try (PreparedStatement count = connection.prepareStatement(
                    "SELECT COUNT(*) FROM audit_log" + where)) {
                bind(count, parameters);
                try (ResultSet resultSet = count.executeQuery()) {
                    total = resultSet.next() ? resultSet.getLong(1) : 0L;
                }
            }

            List<AuditLogItem> items = new ArrayList<>();
            long offset = (long) (query.page() - 1) * query.size();
            if (offset < total) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT " + COLUMNS + " FROM audit_log" + where
                                + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?")) {
                    List<Object> pageParameters = new ArrayList<>(parameters);
                    pageParameters.add(query.size());
                    pageParameters.add(offset);
                    bind(statement, pageParameters);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            items.add(mapItem(resultSet));
                        }
                    }
                }
            }
            boolean hasMore = offset + items.size() < total;
            return new AuditLogPage(List.copyOf(items), total, query.page(), query.size(), hasMore);
        }
    }

    /** 审计库文件被外部删除/替换时，连接会打开一个空库；自动重建 schema 后重试。 */
    private boolean recoverSchema() {
        try {
            initSchema();
            log.warn("审计 SQLite schema 缺失，已自动重建: {}", dbPath);
            return true;
        } catch (SQLException | RuntimeException e) {
            log.warn("审计 SQLite schema 自动重建失败: {} ({})", dbPath, e.toString());
            return false;
        }
    }

    private static boolean isMissingTable(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message != null && message.toLowerCase(java.util.Locale.ROOT).contains("no such table");
    }

    /** 删除 created_at 早于 cutoff 的行；返回删除数量（DB 不可用时返回 0）。 */
    public int deleteOlderThan(long cutoffMillis) {
        if (!available) {
            return 0;
        }
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM audit_log WHERE created_at < ?")) {
            statement.setLong(1, cutoffMillis);
            return statement.executeUpdate();
        } catch (SQLException e) {
            log.warn("清理过期审计 SQLite 行失败: {}", e.toString());
            return 0;
        }
    }

    /** 按保留天数清理；返回删除行数。 */
    public int cleanup(int maxHistoryDays) {
        long cutoff = Instant.now().minusSeconds(Math.max(1, maxHistoryDays) * 86_400L).toEpochMilli();
        return deleteOlderThan(cutoff);
    }

    private void initSchema() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS audit_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        ts TEXT,
                        request_id TEXT,
                        app_key TEXT,
                        client_name TEXT,
                        device_id TEXT,
                        client_ip TEXT,
                        method TEXT,
                        path TEXT,
                        query TEXT,
                        action TEXT,
                        root TEXT,
                        target_root TEXT,
                        path_param TEXT,
                        target_dir TEXT,
                        session_id TEXT,
                        terminal_command TEXT,
                        body TEXT,
                        content_sha256 TEXT,
                        request_bytes INTEGER,
                        response_bytes INTEGER,
                        duration_ms INTEGER,
                        http_status INTEGER,
                        success INTEGER,
                        error TEXT,
                        user_agent TEXT,
                        file_name TEXT,
                        created_at INTEGER NOT NULL
                    )""");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_log_ts ON audit_log(ts)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_log_action ON audit_log(action)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_log_app_key ON audit_log(app_key)");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_audit_log_request_id ON audit_log(request_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_log_success ON audit_log(success)");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_audit_log_app_ts ON audit_log(app_key, ts)");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_audit_log_created_at ON audit_log(created_at)");
            try (ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM audit_log")) {
                resultSet.next();
            }
        }
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
        } catch (SQLException e) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 保留原始 PRAGMA 异常
            }
            throw e;
        }
        return connection;
    }

    private static String buildWhere(Query query, List<Object> parameters) {
        StringBuilder where = new StringBuilder(" WHERE app_key = ?");
        parameters.add(query.appKey());
        if (query.action() != null && !query.action().isBlank()) {
            where.append(" AND action = ?");
            parameters.add(query.action());
        }
        if (query.actions() != null && !query.actions().isEmpty()) {
            where.append(" AND action IN (");
            for (int index = 0; index < query.actions().size(); index++) {
                if (index > 0) {
                    where.append(',');
                }
                where.append('?');
            }
            where.append(')');
            parameters.addAll(query.actions());
        }
        if (query.success() != null) {
            where.append(" AND success = ?");
            parameters.add(query.success() ? 1 : 0);
        }
        if (query.keyword() != null && !query.keyword().isBlank()) {
            String pattern = likePattern(query.keyword());
            where.append(" AND (path LIKE ? ESCAPE '\\' OR request_id LIKE ? ESCAPE '\\'")
                    .append(" OR path_param LIKE ? ESCAPE '\\' OR file_name LIKE ? ESCAPE '\\'")
                    .append(" OR terminal_command LIKE ? ESCAPE '\\')");
            for (int index = 0; index < 5; index++) {
                parameters.add(pattern);
            }
        }
        if (query.fromMillis() != null) {
            where.append(" AND created_at >= ?");
            parameters.add(query.fromMillis());
        }
        if (query.toMillis() != null) {
            where.append(" AND created_at <= ?");
            parameters.add(query.toMillis());
        }
        return where.toString();
    }

    private static void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            statement.setObject(index + 1, parameters.get(index));
        }
    }

    static String likePattern(String keyword) {
        return "%" + keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static AuditLogItem mapItem(ResultSet resultSet) throws SQLException {
        return new AuditLogItem(
                resultSet.getLong("id"),
                resultSet.getString("ts"),
                resultSet.getString("request_id"),
                resultSet.getString("app_key"),
                resultSet.getString("client_name"),
                resultSet.getString("device_id"),
                resultSet.getString("client_ip"),
                resultSet.getString("method"),
                resultSet.getString("path"),
                resultSet.getString("query"),
                resultSet.getString("action"),
                resultSet.getString("root"),
                resultSet.getString("target_root"),
                resultSet.getString("path_param"),
                resultSet.getString("target_dir"),
                resultSet.getString("session_id"),
                resultSet.getString("terminal_command"),
                resultSet.getString("body"),
                resultSet.getString("content_sha256"),
                nullableLong(resultSet, "request_bytes"),
                nullableLong(resultSet, "response_bytes"),
                resultSet.getLong("duration_ms"),
                resultSet.getInt("http_status"),
                resultSet.getInt("success") == 1,
                resultSet.getString("error"),
                resultSet.getString("user_agent"),
                resultSet.getString("file_name"));
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static void setNullableString(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setLong(index, value);
        }
    }

    private static long createdAtMillis(String ts) {
        if (ts == null) {
            return System.currentTimeMillis();
        }
        try {
            return OffsetDateTime.parse(ts).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(ts).toEpochMilli();
            } catch (DateTimeParseException ignored) {
                return System.currentTimeMillis();
            }
        }
    }

    /** 查询条件（page 从 1 开始，size 1..200，from/to 为闭区间 epoch millis）。 */
    public record Query(
            String appKey,
            int page,
            int size,
            String action,
            List<String> actions,
            Boolean success,
            String keyword,
            Long fromMillis,
            Long toMillis
    ) {
        /** 兼容只按单个 action / 无分类查询的旧调用。 */
        public Query(String appKey, int page, int size, String action,
                     Boolean success, String keyword, Long fromMillis, Long toMillis) {
            this(appKey, page, size, action, null, success, keyword, fromMillis, toMillis);
        }
    }
}
