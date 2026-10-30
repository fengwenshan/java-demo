package com.wenshan.upload.store;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wenshan.upload.storage.StoragePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SQLite 元数据存储 + 内存索引（每个存储根一个 {@code <root>/.upload-meta/metadata.sqlite3}）。
 *
 * <p>布局：{@code file_meta}/{@code device_meta}/{@code schema_meta} 三张表，时间统一存 epoch millis。
 * 启动时建表（WAL、synchronous=NORMAL、busy_timeout=5000），加载全部行到 volatile 不可变内存快照；
 * 读路径无锁走内存快照，所有写操作在 {@link #writeLock} 内用短事务同步落 SQLite，
 * **写成功后才更新内存快照**（写失败不污染内存）。多根场景由
 * {@link com.wenshan.upload.storage.StorageRootRegistry} 为每个根创建一个实例，天然按根隔离。</p>
 *
 * <p>旧版 {@code files.json}/{@code devices.json} 在首次启动且 DB 为空时自动迁移：
 * 先完整解析校验，再单事务导入；成功后把旧文件重命名为 {@code *.migrated-<epochMillis>}。
 * 迁移失败回滚并抛 {@link MetaStoreException}，旧 JSON 原样保留；DB 已有数据时不再导入。</p>
 */
public class MetaStore {

    private static final Logger log = LoggerFactory.getLogger(MetaStore.class);

    /** 旧 JSON 的格式版本（仅迁移读取用）。 */
    public static final int LEGACY_FORMAT_VERSION = 1;
    /** SQLite schema 版本。 */
    public static final int SCHEMA_VERSION = 1;

    static final String DB_FILE_NAME = "metadata.sqlite3";
    static final String FILES_FILE_NAME = "files.json";
    static final String DEVICES_FILE_NAME = "devices.json";
    private static final String MIGRATED_SUFFIX = ".migrated-";

    private static final String FILE_COLUMNS = "id, app_key, device_id, name, description, size, "
            + "content_type, sha256, stored_path, created_at, root_id";
    private static final String DEVICE_COLUMNS = "app_key, device_id, device_name, created_at, last_seen_at";

    /** 旧 JSON 固定使用 ISO-8601 字符串与 camelCase（record 组件名）；仅迁移时使用。 */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final Path metaDir;
    private final Path dbPath;
    private final ReentrantLock writeLock = new ReentrantLock();

    private volatile Map<String, FileRecord> files = Map.of();
    private volatile Map<String, DeviceRecord> devices = Map.of();

    public MetaStore(StoragePaths storagePaths) {
        this(storagePaths.metaDir());
    }

    MetaStore(Path metaDir) {
        this.metaDir = metaDir.toAbsolutePath().normalize();
        this.dbPath = this.metaDir.resolve(DB_FILE_NAME);
        try {
            Files.createDirectories(this.metaDir);
        } catch (IOException e) {
            throw new MetaStoreException("无法创建元数据目录: " + this.metaDir, e);
        }
        initSchema();
        migrateLegacyJsonIfNeeded();
        load();
        log.info("元数据已加载: {} 个文件记录, {} 个设备绑定, 数据库 {}", files.size(), devices.size(), dbPath);
    }

    public Path metaDir() {
        return metaDir;
    }

    /** SQLite 数据库文件路径（测试/运维排查用）。 */
    public Path dbPath() {
        return dbPath;
    }

    // ------------------------------------------------------------------
    // 文件元数据
    // ------------------------------------------------------------------

    /** 某个 appKey 的全部文件记录，createdAt 倒序（相同时间按 id 升序，保证顺序稳定）。 */
    public List<FileRecord> filesOf(String appKey) {
        List<FileRecord> result = new ArrayList<>();
        for (FileRecord record : files.values()) {
            if (record.appKey().equals(appKey)) {
                result.add(record);
            }
        }
        result.sort(Comparator.comparing(FileRecord::createdAt).reversed().thenComparing(FileRecord::id));
        return List.copyOf(result);
    }

    /** 内存分页 + 关键字过滤（忽略大小写）+ createdAt 倒序，语义与原 JPA 查询一致。 */
    public FileQueryResult queryFiles(String appKey, String keyword, int page, int size) {
        String filter = keyword == null ? null : keyword.toLowerCase(Locale.ROOT);
        List<FileRecord> matched = new ArrayList<>();
        for (FileRecord record : files.values()) {
            if (!record.appKey().equals(appKey)) {
                continue;
            }
            if (filter != null && !record.name().toLowerCase(Locale.ROOT).contains(filter)) {
                continue;
            }
            matched.add(record);
        }
        matched.sort(Comparator.comparing(FileRecord::createdAt).reversed().thenComparing(FileRecord::id));

        long total = matched.size();
        long offset = Math.max(0L, (long) (page - 1) * size);
        if (offset >= total) {
            return new FileQueryResult(List.of(), total);
        }
        int fromIndex = (int) offset;
        int toIndex = (int) Math.min(total, offset + size);
        return new FileQueryResult(List.copyOf(matched.subList(fromIndex, toIndex)), total);
    }

    public Optional<FileRecord> findFile(String appKey, String id) {
        FileRecord record = files.get(id);
        if (record == null || !record.appKey().equals(appKey)) {
            return Optional.empty();
        }
        return Optional.of(record);
    }

    /** 按 storedPath 精确匹配的第一条记录（路径式操作/排查用，跨 appKey）。 */
    public Optional<FileRecord> findByStoredPath(String storedPath) {
        if (storedPath == null) {
            return Optional.empty();
        }
        for (FileRecord record : files.values()) {
            if (storedPath.equals(record.storedPath())) {
                return Optional.of(record);
            }
        }
        return Optional.empty();
    }

    /** 指定 appKey 下某 id 的 storedPath。 */
    public Optional<String> storedPathOf(String appKey, String id) {
        return findFile(appKey, id).map(FileRecord::storedPath);
    }

    /** 新增或覆盖文件记录，成功后落 SQLite；写失败时内存保持原状。 */
    public void saveFile(FileRecord record) {
        Objects.requireNonNull(record, "record");
        writeLock.lock();
        try {
            try (Connection connection = open()) {
                transaction(connection, conn -> insertFile(conn, record));
            } catch (SQLException e) {
                throw new MetaStoreException("元数据写入失败: " + dbPath, e);
            }
            Map<String, FileRecord> updated = new HashMap<>(files);
            updated.put(record.id(), record);
            files = Map.copyOf(updated);
        } finally {
            writeLock.unlock();
        }
    }

    /** 删除文件记录并落库；不存在或不属于该 appKey 时返回 empty。 */
    public Optional<FileRecord> deleteFile(String appKey, String id) {
        writeLock.lock();
        try {
            FileRecord existing;
            try (Connection connection = open()) {
                existing = findFileRow(connection, appKey, id);
                if (existing == null) {
                    return Optional.empty();
                }
                transaction(connection, conn -> deleteFileRow(conn, id));
            } catch (SQLException e) {
                throw new MetaStoreException("元数据写入失败: " + dbPath, e);
            }
            Map<String, FileRecord> updated = new HashMap<>(files);
            updated.remove(id);
            files = Map.copyOf(updated);
            return Optional.of(existing);
        } finally {
            writeLock.unlock();
        }
    }

    /** 删除 storedPath 完全匹配的文件记录（路径式删除时联动清理元数据）；返回被删除的记录。 */
    public List<FileRecord> removeByStoredPath(String storedPath) {
        Objects.requireNonNull(storedPath, "storedPath");
        writeLock.lock();
        try {
            List<FileRecord> matched;
            try (Connection connection = open()) {
                matched = queryRows(connection, "SELECT " + FILE_COLUMNS
                        + " FROM file_meta WHERE stored_path = ?", storedPath);
                if (matched.isEmpty()) {
                    return List.of();
                }
                transaction(connection, conn -> {
                    try (PreparedStatement statement = conn.prepareStatement(
                            "DELETE FROM file_meta WHERE stored_path = ?")) {
                        statement.setString(1, storedPath);
                        statement.executeUpdate();
                    }
                });
            } catch (SQLException e) {
                throw new MetaStoreException("元数据写入失败: " + dbPath, e);
            }
            Map<String, FileRecord> updated = new HashMap<>(files);
            for (FileRecord record : matched) {
                updated.remove(record.id());
            }
            files = Map.copyOf(updated);
            return List.copyOf(matched);
        } finally {
            writeLock.unlock();
        }
    }

    /** 兼容命名：删除 storedPath 完全匹配的记录。 */
    public List<FileRecord> deleteByStoredPath(String storedPath) {
        return removeByStoredPath(storedPath);
    }

    /** 读取 storedPath 完全匹配（文件）或以 {@code prefix + "/"} 开头的全部记录（目录移动用，跨 appKey）。 */
    public List<FileRecord> filesAtOrUnder(String storedPath, boolean directory) {
        Objects.requireNonNull(storedPath, "storedPath");
        String prefix = storedPath + "/";
        List<FileRecord> matched = new ArrayList<>();
        for (FileRecord record : files.values()) {
            String current = record.storedPath();
            if (current == null) {
                continue;
            }
            if (directory ? current.startsWith(prefix) : current.equals(storedPath)) {
                matched.add(record);
            }
        }
        return List.copyOf(matched);
    }

    /** 批量写入/覆盖文件记录（跨根移动时把记录搬到目标根），单事务落盘。 */
    public int saveFiles(List<FileRecord> records) {
        Objects.requireNonNull(records, "records");
        if (records.isEmpty()) {
            return 0;
        }
        writeLock.lock();
        try {
            try (Connection connection = open()) {
                transaction(connection, conn -> {
                    for (FileRecord record : records) {
                        insertFile(conn, record);
                    }
                });
            } catch (SQLException e) {
                throw new MetaStoreException("元数据写入失败: " + dbPath, e);
            }
            Map<String, FileRecord> updated = new HashMap<>(files);
            for (FileRecord record : records) {
                updated.put(record.id(), record);
            }
            files = Map.copyOf(updated);
            return records.size();
        } finally {
            writeLock.unlock();
        }
    }

    /** 按 id 批量删除文件记录（跨根移动时清理源根记录）；返回实际删除数量。 */
    public int removeByIds(java.util.Collection<String> ids) {
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) {
            return 0;
        }
        writeLock.lock();
        try {
            int count;
            try (Connection connection = open()) {
                count = 0;
                final int[] removed = {0};
                transaction(connection, conn -> {
                    try (PreparedStatement statement = conn.prepareStatement(
                            "DELETE FROM file_meta WHERE id = ?")) {
                        for (String id : ids) {
                            statement.setString(1, id);
                            removed[0] += statement.executeUpdate();
                        }
                    }
                });
                count = removed[0];
            } catch (SQLException e) {
                throw new MetaStoreException("元数据写入失败: " + dbPath, e);
            }
            if (count == 0) {
                return 0;
            }
            Map<String, FileRecord> updated = new HashMap<>(files);
            for (String id : ids) {
                updated.remove(id);
            }
            files = Map.copyOf(updated);
            return count;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 文件重命名后同步元数据：把所有 {@code storedPath} 完全等于 {@code oldStoredPath} 的记录
     * 更新为新的 {@code storedPath}/{@code name}，单事务落盘。无匹配记录时返回 0 且不写盘。
     */
    public int updateFilePath(String oldStoredPath, String newStoredPath, String newName) {
        Objects.requireNonNull(oldStoredPath, "oldStoredPath");
        Objects.requireNonNull(newStoredPath, "newStoredPath");
        Objects.requireNonNull(newName, "newName");
        writeLock.lock();
        try {
            List<FileRecord> matched;
            try (Connection connection = open()) {
                matched = queryRows(connection, "SELECT " + FILE_COLUMNS
                        + " FROM file_meta WHERE stored_path = ?", oldStoredPath);
                if (matched.isEmpty()) {
                    return 0;
                }
                List<FileRecord> updatedRecords = new ArrayList<>();
                for (FileRecord record : matched) {
                    updatedRecords.add(new FileRecord(record.id(), record.appKey(), record.deviceId(), newName,
                            record.description(), record.size(), record.contentType(), record.sha256(),
                            newStoredPath, record.createdAt(), record.rootId()));
                }
                transaction(connection, conn -> {
                    try (PreparedStatement statement = conn.prepareStatement(
                            "UPDATE file_meta SET stored_path = ?, name = ? WHERE id = ?")) {
                        for (FileRecord record : updatedRecords) {
                            statement.setString(1, record.storedPath());
                            statement.setString(2, record.name());
                            statement.setString(3, record.id());
                            statement.executeUpdate();
                        }
                    }
                });
                Map<String, FileRecord> updated = new HashMap<>(files);
                for (FileRecord record : updatedRecords) {
                    updated.put(record.id(), record);
                }
                files = Map.copyOf(updated);
                return updatedRecords.size();
            }
        } catch (SQLException e) {
            throw new MetaStoreException("元数据写入失败: " + dbPath, e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 目录重命名后同步元数据：把所有 {@code storedPath} 以 {@code oldDirectory + "/"} 开头的记录
     * 前缀替换为新目录，单事务落盘；文件本身 name 不变。无匹配记录时返回 0 且不写盘。
     */
    public int updateDirectoryPrefix(String oldDirectory, String newDirectory) {
        Objects.requireNonNull(oldDirectory, "oldDirectory");
        Objects.requireNonNull(newDirectory, "newDirectory");
        String oldPrefix = oldDirectory + "/";
        String newPrefix = newDirectory + "/";
        writeLock.lock();
        try {
            List<FileRecord> matched;
            try (Connection connection = open()) {
                matched = queryRows(connection, "SELECT " + FILE_COLUMNS + " FROM file_meta", null);
                List<FileRecord> updatedRecords = new ArrayList<>();
                for (FileRecord record : matched) {
                    String storedPath = record.storedPath();
                    if (storedPath != null && storedPath.startsWith(oldPrefix)) {
                        updatedRecords.add(new FileRecord(record.id(), record.appKey(), record.deviceId(),
                                record.name(), record.description(), record.size(), record.contentType(),
                                record.sha256(), newPrefix + storedPath.substring(oldPrefix.length()),
                                record.createdAt(), record.rootId()));
                    }
                }
                if (updatedRecords.isEmpty()) {
                    return 0;
                }
                transaction(connection, conn -> {
                    try (PreparedStatement statement = conn.prepareStatement(
                            "UPDATE file_meta SET stored_path = ? WHERE id = ?")) {
                        for (FileRecord record : updatedRecords) {
                            statement.setString(1, record.storedPath());
                            statement.setString(2, record.id());
                            statement.executeUpdate();
                        }
                    }
                });
                Map<String, FileRecord> updated = new HashMap<>(files);
                for (FileRecord record : updatedRecords) {
                    updated.put(record.id(), record);
                }
                files = Map.copyOf(updated);
                return updatedRecords.size();
            }
        } catch (SQLException e) {
            throw new MetaStoreException("元数据写入失败: " + dbPath, e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 在线编辑覆盖保存后同步元数据：把所有 {@code storedPath} 完全等于该值的记录
     * 更新为新的 {@code size}/{@code sha256}（contentType/name 等保持不变），单事务落盘。
     * 没有匹配记录时返回 0 且不写盘。
     */
    public int updateContentByStoredPath(String storedPath, long size, String sha256) {
        Objects.requireNonNull(storedPath, "storedPath");
        Objects.requireNonNull(sha256, "sha256");
        writeLock.lock();
        try {
            List<FileRecord> matched;
            try (Connection connection = open()) {
                matched = queryRows(connection, "SELECT " + FILE_COLUMNS
                        + " FROM file_meta WHERE stored_path = ?", storedPath);
                if (matched.isEmpty()) {
                    return 0;
                }
                List<FileRecord> updatedRecords = new ArrayList<>();
                for (FileRecord record : matched) {
                    updatedRecords.add(new FileRecord(record.id(), record.appKey(), record.deviceId(),
                            record.name(), record.description(), size, record.contentType(), sha256,
                            record.storedPath(), record.createdAt(), record.rootId()));
                }
                transaction(connection, conn -> {
                    try (PreparedStatement statement = conn.prepareStatement(
                            "UPDATE file_meta SET size = ?, sha256 = ? WHERE id = ?")) {
                        for (FileRecord record : updatedRecords) {
                            statement.setLong(1, record.size());
                            statement.setString(2, record.sha256());
                            statement.setString(3, record.id());
                            statement.executeUpdate();
                        }
                    }
                });
                Map<String, FileRecord> updated = new HashMap<>(files);
                for (FileRecord record : updatedRecords) {
                    updated.put(record.id(), record);
                }
                files = Map.copyOf(updated);
                return updatedRecords.size();
            }
        } catch (SQLException e) {
            throw new MetaStoreException("元数据写入失败: " + dbPath, e);
        } finally {
            writeLock.unlock();
        }
    }

    // ------------------------------------------------------------------
    // 设备绑定
    // ------------------------------------------------------------------

    public Optional<DeviceRecord> findDevice(String appKey) {
        if (appKey == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(devices.get(appKey));
    }

    /** 新增或覆盖设备绑定（每个 appKey 只保留一条，换 deviceId 时覆盖旧绑定），成功后落库。 */
    public void saveDevice(DeviceRecord record) {
        Objects.requireNonNull(record, "record");
        writeLock.lock();
        try {
            try (Connection connection = open()) {
                transaction(connection, conn -> {
                    try (PreparedStatement delete = conn.prepareStatement(
                            "DELETE FROM device_meta WHERE app_key = ?")) {
                        delete.setString(1, record.appKey());
                        delete.executeUpdate();
                    }
                    try (PreparedStatement insert = conn.prepareStatement(
                            "INSERT INTO device_meta(" + DEVICE_COLUMNS + ") VALUES (?, ?, ?, ?, ?)")) {
                        insert.setString(1, record.appKey());
                        insert.setString(2, record.deviceId());
                        insert.setString(3, record.deviceName());
                        insert.setLong(4, record.createdAt().toEpochMilli());
                        if (record.lastSeenAt() == null) {
                            insert.setNull(5, java.sql.Types.INTEGER);
                        } else {
                            insert.setLong(5, record.lastSeenAt().toEpochMilli());
                        }
                        insert.executeUpdate();
                    }
                });
            } catch (SQLException e) {
                throw new MetaStoreException("元数据写入失败: " + dbPath, e);
            }
            Map<String, DeviceRecord> updated = new HashMap<>(devices);
            updated.put(record.appKey(), record);
            devices = Map.copyOf(updated);
        } finally {
            writeLock.unlock();
        }
    }

    // ------------------------------------------------------------------
    // 初始化 / 加载 / 迁移 / SQL 工具
    // ------------------------------------------------------------------

    private void initSchema() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS schema_meta (version INTEGER NOT NULL)");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS file_meta (
                        id TEXT PRIMARY KEY,
                        app_key TEXT NOT NULL,
                        device_id TEXT,
                        name TEXT NOT NULL,
                        description TEXT,
                        size INTEGER NOT NULL,
                        content_type TEXT,
                        sha256 TEXT,
                        stored_path TEXT,
                        created_at INTEGER NOT NULL,
                        root_id TEXT
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS device_meta (
                        app_key TEXT NOT NULL,
                        device_id TEXT NOT NULL,
                        device_name TEXT,
                        created_at INTEGER NOT NULL,
                        last_seen_at INTEGER,
                        PRIMARY KEY (app_key, device_id)
                    )""");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_file_meta_app_created "
                    + "ON file_meta(app_key, created_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_file_meta_stored_path "
                    + "ON file_meta(stored_path)");
            try (PreparedStatement versionInsert = connection.prepareStatement(
                    "INSERT INTO schema_meta(version) SELECT ? WHERE NOT EXISTS "
                            + "(SELECT 1 FROM schema_meta)")) {
                versionInsert.setInt(1, SCHEMA_VERSION);
                versionInsert.executeUpdate();
            }
            try (ResultSet resultSet = statement.executeQuery("SELECT version FROM schema_meta LIMIT 1")) {
                if (!resultSet.next()) {
                    throw new SQLException("schema_meta 缺少版本记录");
                }
                int version = resultSet.getInt(1);
                if (version != SCHEMA_VERSION) {
                    throw new SQLException("不支持的元数据 schema 版本: " + version);
                }
            }
        } catch (SQLException e) {
            throw new MetaStoreException("元数据数据库初始化失败: " + dbPath, e);
        }
    }

    /** 首次启动 DB 为空且存在旧 files.json/devices.json 时，单事务导入并备份旧文件。 */
    private void migrateLegacyJsonIfNeeded() {
        Path legacyFiles = metaDir.resolve(FILES_FILE_NAME);
        Path legacyDevices = metaDir.resolve(DEVICES_FILE_NAME);
        boolean hasFiles = Files.isRegularFile(legacyFiles);
        boolean hasDevices = Files.isRegularFile(legacyDevices);
        if (!hasFiles && !hasDevices) {
            return;
        }
        try (Connection connection = open()) {
            if (!isDatabaseEmpty(connection)) {
                log.warn("SQLite 元数据已有数据，跳过旧 JSON 迁移（旧文件保留）: {}", metaDir);
                return;
            }
            List<FileRecord> legacyFileRecords = readLegacyFiles(legacyFiles);
            List<DeviceRecord> legacyDeviceRecords = readLegacyDevices(legacyDevices);

            try {
                connection.setAutoCommit(false);
                for (FileRecord record : legacyFileRecords) {
                    insertFile(connection, record);
                }
                for (DeviceRecord record : legacyDeviceRecords) {
                    insertDevice(connection, record);
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(connection);
                throw new MetaStoreException("旧 JSON 元数据迁移失败: " + metaDir, e);
            }
        } catch (SQLException e) {
            throw new MetaStoreException("旧 JSON 元数据迁移失败: " + metaDir, e);
        }
        long timestamp = System.currentTimeMillis();
        backupLegacy(legacyFiles, hasFiles, timestamp);
        backupLegacy(legacyDevices, hasDevices, timestamp);
        log.info("旧 JSON 元数据迁移完成: files={}, devices={}, 目录 {}",
                countLegacy(legacyFiles, FilesFile.class), countLegacy(legacyDevices, DevicesFile.class), metaDir);
    }

    private List<FileRecord> readLegacyFiles(Path path) {
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        FilesFile payload = readLegacy(path, FilesFile.class);
        if (payload == null || payload.files() == null) {
            return List.of();
        }
        List<FileRecord> records = new ArrayList<>();
        for (FileRecord record : payload.files()) {
            if (record == null || record.id() == null || record.appKey() == null || record.name() == null
                    || record.createdAt() == null) {
                throw new MetaStoreException("旧 JSON 元数据迁移失败（记录不完整，原文件保留）: " + path);
            }
            records.add(record);
        }
        return records;
    }

    private List<DeviceRecord> readLegacyDevices(Path path) {
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        DevicesFile payload = readLegacy(path, DevicesFile.class);
        if (payload == null || payload.devices() == null) {
            return List.of();
        }
        List<DeviceRecord> records = new ArrayList<>();
        for (DeviceRecord record : payload.devices()) {
            if (record == null || record.appKey() == null || record.deviceId() == null
                    || record.createdAt() == null) {
                throw new MetaStoreException("旧 JSON 元数据迁移失败（记录不完整，原文件保留）: " + path);
            }
            records.add(record);
        }
        return records;
    }

    private <T> T readLegacy(Path path, Class<T> type) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length == 0) {
                return null;
            }
            return MAPPER.readValue(bytes, type);
        } catch (IOException e) {
            throw new MetaStoreException("旧 JSON 元数据迁移失败（文件损坏，原文件保留）: " + path, e);
        }
    }

    private static long countLegacy(Path path, Class<?> type) {
        return Files.isRegularFile(path) ? 1 : 0;
    }

    private void backupLegacy(Path legacy, boolean exists, long timestamp) {
        if (!exists || !Files.isRegularFile(legacy)) {
            return;
        }
        Path backup = legacy.resolveSibling(legacy.getFileName() + MIGRATED_SUFFIX + timestamp);
        try {
            Files.move(legacy, backup);
            log.info("旧 JSON 元数据已备份: {} -> {}", legacy.getFileName(), backup.getFileName());
        } catch (IOException e) {
            // 迁移数据已落库；备份失败只告警，旧文件保留（下次启动 DB 非空不会重复导入）
            log.warn("旧 JSON 元数据备份失败（原文件保留）: {} ({})", legacy, e.toString());
        }
    }

    private void load() {
        Map<String, FileRecord> loadedFiles = new HashMap<>();
        Map<String, DeviceRecord> loadedDevices = new HashMap<>();
        try (Connection connection = open()) {
            for (FileRecord record : queryRows(connection,
                    "SELECT " + FILE_COLUMNS + " FROM file_meta", null)) {
                loadedFiles.put(record.id(), record);
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + DEVICE_COLUMNS + " FROM device_meta");
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    DeviceRecord record = mapDevice(resultSet);
                    loadedDevices.put(record.appKey(), record);
                }
            }
        } catch (SQLException e) {
            throw new MetaStoreException("元数据加载失败: " + dbPath, e);
        }
        files = Map.copyOf(loadedFiles);
        devices = Map.copyOf(loadedDevices);
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
        } catch (SQLException e) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 忽略关闭异常，抛出原始 PRAGMA 错误
            }
            throw e;
        }
        return connection;
    }

    private static boolean isDatabaseEmpty(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT (SELECT COUNT(*) FROM file_meta) + (SELECT COUNT(*) FROM device_meta)")) {
            return resultSet.next() && resultSet.getLong(1) == 0L;
        }
    }

    private static FileRecord findFileRow(Connection connection, String appKey, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + FILE_COLUMNS + " FROM file_meta WHERE id = ? AND app_key = ?")) {
            statement.setString(1, id);
            statement.setString(2, appKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapFile(resultSet) : null;
            }
        }
    }

    private List<FileRecord> queryRows(Connection connection, String sql, String parameter) throws SQLException {
        List<FileRecord> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (parameter != null) {
                statement.setString(1, parameter);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(mapFile(resultSet));
                }
            }
        }
        return rows;
    }

    private static void insertFile(Connection connection, FileRecord record) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO file_meta(" + FILE_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(id) DO UPDATE SET app_key = excluded.app_key, "
                        + "device_id = excluded.device_id, name = excluded.name, "
                        + "description = excluded.description, size = excluded.size, "
                        + "content_type = excluded.content_type, sha256 = excluded.sha256, "
                        + "stored_path = excluded.stored_path, created_at = excluded.created_at, "
                        + "root_id = excluded.root_id")) {
            statement.setString(1, record.id());
            statement.setString(2, record.appKey());
            setNullableString(statement, 3, record.deviceId());
            statement.setString(4, record.name());
            setNullableString(statement, 5, record.description());
            statement.setLong(6, record.size());
            setNullableString(statement, 7, record.contentType());
            setNullableString(statement, 8, record.sha256());
            setNullableString(statement, 9, record.storedPath());
            statement.setLong(10, record.createdAt().toEpochMilli());
            setNullableString(statement, 11, record.rootId());
            statement.executeUpdate();
        }
    }

    private static void insertDevice(Connection connection, DeviceRecord record) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO device_meta(" + DEVICE_COLUMNS + ") VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT(app_key, device_id) DO UPDATE SET "
                        + "device_name = excluded.device_name, created_at = excluded.created_at, "
                        + "last_seen_at = excluded.last_seen_at")) {
            statement.setString(1, record.appKey());
            statement.setString(2, record.deviceId());
            setNullableString(statement, 3, record.deviceName());
            statement.setLong(4, record.createdAt().toEpochMilli());
            if (record.lastSeenAt() == null) {
                statement.setNull(5, java.sql.Types.INTEGER);
            } else {
                statement.setLong(5, record.lastSeenAt().toEpochMilli());
            }
            statement.executeUpdate();
        }
    }

    private static void deleteFileRow(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM file_meta WHERE id = ?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }

    private static void setNullableString(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static FileRecord mapFile(ResultSet resultSet) throws SQLException {
        return new FileRecord(
                resultSet.getString("id"),
                resultSet.getString("app_key"),
                resultSet.getString("device_id"),
                resultSet.getString("name"),
                resultSet.getString("description"),
                resultSet.getLong("size"),
                resultSet.getString("content_type"),
                resultSet.getString("sha256"),
                resultSet.getString("stored_path"),
                Instant.ofEpochMilli(resultSet.getLong("created_at")),
                resultSet.getString("root_id"));
    }

    private static DeviceRecord mapDevice(ResultSet resultSet) throws SQLException {
        long lastSeen = resultSet.getLong("last_seen_at");
        boolean lastSeenNull = resultSet.wasNull();
        return new DeviceRecord(
                resultSet.getString("app_key"),
                resultSet.getString("device_id"),
                resultSet.getString("device_name"),
                Instant.ofEpochMilli(resultSet.getLong("created_at")),
                lastSeenNull ? null : Instant.ofEpochMilli(lastSeen));
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection connection) throws SQLException;
    }

    private static void transaction(Connection connection, SqlWork work) {
        try {
            connection.setAutoCommit(false);
            work.run(connection);
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly(connection);
            throw new MetaStoreException("元数据写入失败: " + e.getMessage(), e);
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 回滚失败时保留原始异常
        }
    }

    /** 旧 files.json 顶层结构（仅迁移读取）。 */
    public record FilesFile(int version, List<FileRecord> files) {
    }

    /** 旧 devices.json 顶层结构（仅迁移读取）。 */
    public record DevicesFile(int version, List<DeviceRecord> devices) {
    }

    /** 内存分页查询结果。 */
    public record FileQueryResult(List<FileRecord> items, long total) {
    }
}
