package com.wenshan.upload.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SQLite 元数据存储：建表/WAL、原子写、重启重载、并发写不丢数据、内存分页过滤、
 * 批量/更新方法、设备 upsert、失败不污染内存。
 */
class MetaStoreTest {

    private static final String SHA = "0".repeat(64);

    @TempDir
    Path tempDir;

    private MetaStore newStore(Path metaDir) {
        return new MetaStore(metaDir);
    }

    private static FileRecord file(String id, String appKey, String name, Instant createdAt) {
        return new FileRecord(id, appKey, "device-1", name, null, 3L,
                "text/plain", SHA, "2026/10/28/store-" + id + ".txt", createdAt);
    }

    private static FileRecord fileWithRoot(String id, String storedPath, Instant createdAt) {
        return new FileRecord(id, "demo-app", "device-1", "root-" + id + ".txt", "描述", 3L,
                "text/plain", SHA, storedPath, createdAt, "default");
    }

    @Test
    void persistsToSqliteAndReloadsAfterRestart() throws Exception {
        Path metaDir = tempDir.resolve("meta");
        MetaStore store = newStore(metaDir);
        Instant createdAt = Instant.parse("2026-10-28T04:12:00Z");
        store.saveFile(file("id-1", "demo-app", "设计稿.pdf", createdAt));
        store.saveFile(fileWithRoot("id-2", "docs/report.txt", createdAt.plusSeconds(10)));
        store.saveDevice(new DeviceRecord("demo-app", "device-1", "我的 MacBook", createdAt, createdAt));

        // SQLite 文件落盘；不再生成旧 JSON
        assertThat(store.dbPath()).isEqualTo(metaDir.resolve(MetaStore.DB_FILE_NAME)).isRegularFile();
        assertThat(metaDir.resolve("files.json")).doesNotExist();
        assertThat(metaDir.resolve("devices.json")).doesNotExist();
        assertThat(store.dbPath()).hasFileName("metadata.sqlite3");
        assertThat(fileRowCount(metaDir)).isEqualTo(2);
        assertThat(deviceRowCount(metaDir)).isEqualTo(1);

        // 直接查 SQLite 验证字段/时间/root_id 持久化
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + store.dbPath());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT root_id, created_at FROM file_meta WHERE id = ?")) {
            statement.setString(1, "id-2");
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString("root_id")).isEqualTo("default");
                assertThat(resultSet.getLong("created_at")).isEqualTo(createdAt.plusSeconds(10).toEpochMilli());
            }
        }

        // 重启（新实例）后从 SQLite 重新加载
        MetaStore reloaded = newStore(metaDir);
        assertThat(reloaded.filesOf("demo-app"))
                .extracting(FileRecord::id)
                .containsExactly("id-2", "id-1"); // createdAt 倒序
        assertThat(reloaded.findFile("demo-app", "id-2").orElseThrow().rootId()).isEqualTo("default");
        assertThat(reloaded.findDevice("demo-app"))
                .get()
                .extracting(DeviceRecord::deviceId, DeviceRecord::deviceName)
                .containsExactly("device-1", "我的 MacBook");
    }

    @Test
    void duplicateSaveIsIdempotentAndOverwrites() throws Exception {
        Path metaDir = tempDir.resolve("meta");
        MetaStore store = newStore(metaDir);
        Instant createdAt = Instant.parse("2026-10-28T04:12:00Z");
        store.saveFile(file("id-1", "demo-app", "旧名字.txt", createdAt));
        store.saveFile(file("id-1", "demo-app", "新名字.txt", createdAt));
        assertThat(store.filesOf("demo-app")).hasSize(1);
        assertThat(store.findFile("demo-app", "id-1").orElseThrow().name()).isEqualTo("新名字.txt");

        MetaStore reloaded = newStore(metaDir);
        assertThat(reloaded.filesOf("demo-app")).hasSize(1);
        assertThat(reloaded.findFile("demo-app", "id-1").orElseThrow().name()).isEqualTo("新名字.txt");
        assertThat(fileRowCount(metaDir)).isEqualTo(1);
    }

    @Test
    void deleteRemovesRecordFromDiskAndMemory() throws Exception {
        Path metaDir = tempDir.resolve("meta");
        MetaStore store = newStore(metaDir);
        Instant createdAt = Instant.parse("2026-10-28T04:12:00Z");
        store.saveFile(file("id-1", "demo-app", "a.txt", createdAt));
        store.saveFile(file("id-2", "demo-app", "b.txt", createdAt));
        store.saveFile(file("id-3", "other-app", "c.txt", createdAt));

        assertThat(store.deleteFile("demo-app", "id-2")).isPresent();
        assertThat(store.deleteFile("demo-app", "id-2")).isEmpty();
        assertThat(store.deleteFile("other-app", "id-1")).isEmpty(); // appKey 隔离
        assertThat(store.filesOf("demo-app")).extracting(FileRecord::id).containsExactly("id-1");

        MetaStore reloaded = newStore(metaDir);
        assertThat(reloaded.filesOf("demo-app")).extracting(FileRecord::id).containsExactly("id-1");
        assertThat(reloaded.filesOf("other-app")).extracting(FileRecord::id).containsExactly("id-3");
        assertThat(fileRowCount(metaDir)).isEqualTo(2);
    }

    @Test
    void queryPaginatesFiltersAndSortsInMemory() {
        Path metaDir = tempDir.resolve("meta");
        MetaStore store = newStore(metaDir);
        Instant base = Instant.parse("2026-10-28T04:00:00Z");
        store.saveFile(file("id-1", "demo-app", "季度方案.PDF", base));
        store.saveFile(file("id-2", "demo-app", "季度预算.xlsx", base.plusSeconds(1)));
        store.saveFile(file("id-3", "demo-app", "会议纪要.md", base.plusSeconds(2)));
        store.saveFile(file("id-4", "other-app", "季度方案.pdf", base.plusSeconds(3)));

        MetaStore.FileQueryResult firstPage = store.queryFiles("demo-app", null, 1, 2);
        assertThat(firstPage.total()).isEqualTo(3);
        assertThat(firstPage.items()).extracting(FileRecord::id).containsExactly("id-3", "id-2");

        MetaStore.FileQueryResult secondPage = store.queryFiles("demo-app", null, 2, 2);
        assertThat(secondPage.items()).extracting(FileRecord::id).containsExactly("id-1");

        MetaStore.FileQueryResult filtered = store.queryFiles("demo-app", "季度", 1, 20);
        assertThat(filtered.total()).isEqualTo(2);
        assertThat(filtered.items()).extracting(FileRecord::id).containsExactly("id-2", "id-1");

        assertThat(store.queryFiles("demo-app", "不存在的关键字", 1, 20).items()).isEmpty();
        assertThat(store.queryFiles("demo-app", null, 99, 20).items()).isEmpty();
    }

    @Test
    void batchSaveRemoveByIdsAndPrefixLookup() throws Exception {
        Path metaDir = tempDir.resolve("meta");
        MetaStore store = newStore(metaDir);
        Instant base = Instant.parse("2026-10-28T04:00:00Z");
        store.saveFiles(List.of(
                fileWithRoot("id-1", "docs/a.txt", base),
                fileWithRoot("id-2", "docs/sub/b.txt", base.plusSeconds(1)),
                fileWithRoot("id-3", "other/c.txt", base.plusSeconds(2))));

        assertThat(store.filesAtOrUnder("docs", true))
                .extracting(FileRecord::id).containsExactlyInAnyOrder("id-1", "id-2");
        assertThat(store.filesAtOrUnder("docs/a.txt", false))
                .extracting(FileRecord::id).containsExactly("id-1");
        assertThat(store.findByStoredPath("docs/sub/b.txt")).get()
                .extracting(FileRecord::id).isEqualTo("id-2");
        assertThat(store.storedPathOf("demo-app", "id-1")).contains("docs/a.txt");

        assertThat(store.removeByIds(List.of("id-1", "id-2", "missing"))).isEqualTo(2);
        assertThat(store.removeByIds(List.of("missing"))).isZero();
        assertThat(store.filesOf("demo-app")).extracting(FileRecord::id).containsExactly("id-3");
        assertThat(newStore(metaDir).filesOf("demo-app")).extracting(FileRecord::id).containsExactly("id-3");
        assertThat(fileRowCount(metaDir)).isEqualTo(1);
    }

    @Test
    void updateFilePathPrefixAndContentPersist() throws Exception {
        Path metaDir = tempDir.resolve("meta");
        MetaStore store = newStore(metaDir);
        Instant base = Instant.parse("2026-10-28T04:00:00Z");
        store.saveFiles(List.of(
                fileWithRoot("id-1", "docs/a.txt", base),
                fileWithRoot("id-2", "docs/sub/b.txt", base.plusSeconds(1)),
                fileWithRoot("id-3", "other/c.txt", base.plusSeconds(2))));

        assertThat(store.updateFilePath("docs/a.txt", "docs/renamed.txt", "renamed.txt")).isEqualTo(1);
        assertThat(store.updateFilePath("docs/missing.txt", "docs/x.txt", "x.txt")).isZero();
        assertThat(store.updateDirectoryPrefix("docs", "archive")).isEqualTo(2);
        assertThat(store.updateDirectoryPrefix("nope", "x")).isZero();
        assertThat(store.updateContentByStoredPath("archive/renamed.txt", 9L, "f".repeat(64))).isEqualTo(1);
        assertThat(store.updateContentByStoredPath("archive/missing.txt", 1L, SHA)).isZero();

        MetaStore reloaded = newStore(metaDir);
        assertThat(reloaded.findFile("demo-app", "id-1")).get()
                .extracting(FileRecord::storedPath, FileRecord::name, FileRecord::size, FileRecord::sha256)
                .containsExactly("archive/renamed.txt", "renamed.txt", 9L, "f".repeat(64));
        assertThat(reloaded.findFile("demo-app", "id-2")).get()
                .extracting(FileRecord::storedPath, FileRecord::name)
                .containsExactly("archive/sub/b.txt", "root-id-2.txt");
        assertThat(reloaded.findFile("demo-app", "id-3")).get()
                .extracting(FileRecord::storedPath).isEqualTo("other/c.txt");
    }

    @Test
    void saveDeviceUpsertsOneBindingPerAppKey() throws Exception {
        Path metaDir = tempDir.resolve("meta");
        MetaStore store = newStore(metaDir);
        Instant base = Instant.parse("2026-10-28T04:00:00Z");
        store.saveDevice(new DeviceRecord("demo-app", "device-1", "旧设备", base, base));
        store.saveDevice(new DeviceRecord("demo-app", "device-2", "新设备", base.plusSeconds(5), base.plusSeconds(5)));

        assertThat(store.findDevice("demo-app")).get()
                .extracting(DeviceRecord::deviceId, DeviceRecord::deviceName)
                .containsExactly("device-2", "新设备");
        assertThat(deviceRowCount(metaDir)).isEqualTo(1);
        MetaStore reloaded = newStore(metaDir);
        assertThat(reloaded.findDevice("demo-app")).get().extracting(DeviceRecord::deviceId).isEqualTo("device-2");
        assertThat(deviceRowCount(metaDir)).isEqualTo(1);
    }

    @Test
    void transactionFailureDoesNotPolluteMemory() throws Exception {
        Path metaDir = tempDir.resolve("meta");
        MetaStore store = newStore(metaDir);
        store.saveFile(file("ok", "demo-app", "ok.txt", Instant.parse("2026-10-28T04:00:00Z")));

        FileRecord invalid = new FileRecord("bad", "demo-app", null, null, null, 1L,
                "text/plain", SHA, "bad.txt", Instant.parse("2026-10-28T04:00:00Z"));

        assertThatThrownBy(() -> store.saveFile(invalid)).isInstanceOf(MetaStoreException.class);
        assertThat(store.findFile("demo-app", "bad")).isEmpty();
        assertThat(store.filesOf("demo-app")).extracting(FileRecord::id).containsExactly("ok");
        assertThat(fileRowCount(metaDir)).isEqualTo(1);
        assertThat(newStore(metaDir).findFile("demo-app", "bad")).isEmpty();

        // 批量事务：一条失败则整批回滚，内存/DB 都不应出现任何新记录
        FileRecord batchOk = file("batch-ok", "demo-app", "batch-ok.txt", Instant.now());
        assertThatThrownBy(() -> store.saveFiles(List.of(batchOk, invalid)))
                .isInstanceOf(MetaStoreException.class);
        assertThat(store.findFile("demo-app", "batch-ok")).isEmpty();
        assertThat(fileRowCount(metaDir)).isEqualTo(1);
    }

    @Test
    void concurrentWritesDoNotLoseRecords() throws Exception {
        Path metaDir = tempDir.resolve("meta");
        MetaStore store = newStore(metaDir);
        int threads = 8;
        int perThread = 25;
        int total = threads * perThread;
        Instant now = Instant.parse("2026-10-28T04:12:00Z");

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int thread = 0; thread < threads; thread++) {
                int threadIndex = thread;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int index = 0; index < perThread; index++) {
                        String id = "id-" + threadIndex + "-" + index;
                        store.saveFile(file(id, "demo-app", "并发-" + id + ".txt", now));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(store.filesOf("demo-app")).hasSize(total);
        MetaStore reloaded = newStore(metaDir);
        assertThat(reloaded.filesOf("demo-app")).hasSize(total);
        assertThat(fileRowCount(metaDir)).isEqualTo(total);
    }

    @Test
    void concurrentSavesAndDeletesKeepConsistentState() throws Exception {
        Path metaDir = tempDir.resolve("meta");
        MetaStore store = newStore(metaDir);
        Instant now = Instant.parse("2026-10-28T04:12:00Z");
        int preloaded = 100;
        for (int index = 0; index < preloaded; index++) {
            store.saveFile(file("old-" + index, "demo-app", "旧文件-" + index + ".txt", now));
        }

        int deleteThreads = 5;
        int deletesPerThread = 12; // 共删除 60 条
        int addThreads = 5;
        int addsPerThread = 4; // 共新增 20 条
        ExecutorService pool = Executors.newFixedThreadPool(deleteThreads + addThreads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int thread = 0; thread < deleteThreads; thread++) {
                int threadIndex = thread;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int index = 0; index < deletesPerThread; index++) {
                        int ordinal = threadIndex * deletesPerThread + index;
                        store.deleteFile("demo-app", "old-" + ordinal);
                    }
                    return null;
                }));
            }
            for (int thread = 0; thread < addThreads; thread++) {
                int threadIndex = thread;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int index = 0; index < addsPerThread; index++) {
                        String id = "new-" + threadIndex + "-" + index;
                        store.saveFile(file(id, "demo-app", "新文件-" + id + ".txt", now.plusSeconds(1)));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        int expected = preloaded - deleteThreads * deletesPerThread + addThreads * addsPerThread;
        assertThat(store.filesOf("demo-app")).hasSize(expected);
        assertThat(store.findFile("demo-app", "old-0")).isEmpty();
        assertThat(store.findFile("demo-app", "old-59")).isEmpty();
        assertThat(store.findFile("demo-app", "old-60")).isPresent();
        MetaStore reloaded = newStore(metaDir);
        assertThat(reloaded.filesOf("demo-app")).hasSize(expected);
        assertThat(fileRowCount(metaDir)).isEqualTo(expected);
    }

    @Test
    void databaseSchemaAndIndexesMatchContract() throws Exception {
        Path metaDir = tempDir.resolve("meta");
        newStore(metaDir);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + metaDir.resolve("metadata.sqlite3"))) {
            assertThat(tableNames(connection)).contains("file_meta", "device_meta", "schema_meta");
            assertThat(indexNames(connection)).contains("idx_file_meta_app_created", "idx_file_meta_stored_path");
            try (ResultSet resultSet = connection.createStatement().executeQuery("SELECT version FROM schema_meta")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1)).isEqualTo(MetaStore.SCHEMA_VERSION);
            }
        }
    }

    private static long fileRowCount(Path metaDir) throws SQLException {
        return count(metaDir, "file_meta");
    }

    private static long deviceRowCount(Path metaDir) throws SQLException {
        return count(metaDir, "device_meta");
    }

    private static long count(Path metaDir, String table) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + metaDir.resolve("metadata.sqlite3"));
             ResultSet resultSet = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
    }

    private static List<String> tableNames(Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet resultSet = connection.createStatement().executeQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table'")) {
            while (resultSet.next()) {
                names.add(resultSet.getString(1));
            }
        }
        return names;
    }

    private static List<String> indexNames(Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet resultSet = connection.createStatement().executeQuery(
                "SELECT name FROM sqlite_master WHERE type = 'index'")) {
            while (resultSet.next()) {
                names.add(resultSet.getString(1));
            }
        }
        return names;
    }
}
