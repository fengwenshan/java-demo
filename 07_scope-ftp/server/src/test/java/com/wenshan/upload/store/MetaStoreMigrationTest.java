package com.wenshan.upload.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 旧 files.json/devices.json → SQLite 自动迁移：首次启动导入、时间/字段正确、旧文件备份、
 * DB 已有数据不重复导入、损坏 JSON 启动失败且不丢原文件。
 */
class MetaStoreMigrationTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    private static final String SHA = "0".repeat(64);

    @TempDir
    Path tempDir;

    private static FileRecord legacyFile(String id, String appKey, String name, Instant createdAt) {
        return new FileRecord(id, appKey, "legacy-device", name, "旧描述", 3L,
                "text/plain", SHA, "legacy/" + name, createdAt, null);
    }

    @Test
    void migratesLegacyJsonOnceAndBacksUpOriginalFiles() throws Exception {
        Path metaDir = tempDir.resolve("meta");
        Files.createDirectories(metaDir);
        Instant created = Instant.parse("2026-10-01T01:02:03Z");
        writeLegacyFiles(metaDir, List.of(
                legacyFile("id-1", "demo-app", "旧文件.txt", created),
                legacyFile("id-2", "demo-app", "旧文件2.txt", created.plusSeconds(5))),
                List.of(new DeviceRecord("demo-app", "device-1", "旧设备", created, created)));

        MetaStore store = new MetaStore(metaDir);

        assertThat(store.filesOf("demo-app")).extracting(FileRecord::id)
                .containsExactly("id-2", "id-1");
        FileRecord migrated = store.findFile("demo-app", "id-1").orElseThrow();
        assertThat(migrated.name()).isEqualTo("旧文件.txt");
        assertThat(migrated.description()).isEqualTo("旧描述");
        assertThat(migrated.deviceId()).isEqualTo("legacy-device");
        assertThat(migrated.createdAt()).isEqualTo(created);
        assertThat(migrated.rootId()).isNull();
        assertThat(store.findDevice("demo-app")).get()
                .extracting(DeviceRecord::deviceId, DeviceRecord::deviceName,
                        DeviceRecord::createdAt, DeviceRecord::lastSeenAt)
                .containsExactly("device-1", "旧设备", created, created);
        assertThat(store.dbPath()).isRegularFile();
        assertThat(legacyFileCount(metaDir, "file_meta")).isEqualTo(2);
        assertThat(legacyFileCount(metaDir, "device_meta")).isEqualTo(1);

        // 旧 JSON 被重命名为 .migrated-<timestamp>，原文件不再存在
        assertThat(metaDir.resolve("files.json")).doesNotExist();
        assertThat(metaDir.resolve("devices.json")).doesNotExist();
        assertThat(migratedBackups(metaDir, "files.json")).hasSize(1);
        assertThat(migratedBackups(metaDir, "devices.json")).hasSize(1);

        // 再次启动不重复导入，也不改动备份
        MetaStore reloaded = new MetaStore(metaDir);
        assertThat(reloaded.filesOf("demo-app")).hasSize(2);
        assertThat(legacyFileCount(metaDir, "file_meta")).isEqualTo(2);
        assertThat(migratedBackups(metaDir, "files.json")).hasSize(1);
    }

    @Test
    void existingDatabaseDataIsNotOverwrittenByLegacyJson() throws Exception {
        Path metaDir = tempDir.resolve("meta");
        MetaStore store = new MetaStore(metaDir);
        Instant created = Instant.parse("2026-10-01T01:02:03Z");
        store.saveFile(legacyFile("db-id", "demo-app", "数据库文件.txt", created));

        writeLegacyFiles(metaDir, List.of(legacyFile("legacy-id", "demo-app", "旧文件.txt", created)), List.of());

        MetaStore reloaded = new MetaStore(metaDir);
        assertThat(reloaded.filesOf("demo-app")).extracting(FileRecord::id).containsExactly("db-id");
        assertThat(legacyFileCount(metaDir, "file_meta")).isEqualTo(1);
        // DB 已有数据：旧 JSON 保留、不迁移、不改名
        assertThat(metaDir.resolve("files.json")).isRegularFile();
        assertThat(migratedBackups(metaDir, "files.json")).isEmpty();
    }

    @Test
    void corruptLegacyJsonFailsFastAndKeepsOriginalFile() throws IOException {
        Path metaDir = tempDir.resolve("meta");
        Files.createDirectories(metaDir);
        Files.writeString(metaDir.resolve("files.json"), "{not-json");
        Files.writeString(metaDir.resolve("devices.json"), "also-not-json");

        assertThatThrownBy(() -> new MetaStore(metaDir))
                .isInstanceOf(MetaStoreException.class)
                .hasMessageContaining("旧 JSON 元数据迁移失败");

        assertThat(metaDir.resolve("files.json")).hasContent("{not-json");
        assertThat(metaDir.resolve("devices.json")).hasContent("also-not-json");
        assertThat(migratedBackups(metaDir, "files.json")).isEmpty();
        assertThat(fileRowCount(metaDir)).isZero();
    }

    @Test
    void incompleteLegacyRecordFailsWithoutLosingAnyFile() throws IOException {
        Path metaDir = tempDir.resolve("meta");
        Files.createDirectories(metaDir);
        // name=null 在 DB 中违反 NOT NULL；迁移前校验应拒绝并保留原 JSON
        FileRecord invalid = new FileRecord("bad", "demo-app", null, null, null, 1L,
                "text/plain", SHA, "bad.txt", Instant.now(), null);
        Files.writeString(metaDir.resolve("files.json"),
                MAPPER.writeValueAsString(new MetaStore.FilesFile(1, List.of(invalid))));

        assertThatThrownBy(() -> new MetaStore(metaDir))
                .isInstanceOf(MetaStoreException.class)
                .hasMessageContaining("旧 JSON 元数据迁移失败");
        assertThat(metaDir.resolve("files.json")).isRegularFile();
        assertThat(migratedBackups(metaDir, "files.json")).isEmpty();
        assertThat(fileRowCount(metaDir)).isZero();
    }

    private static void writeLegacyFiles(Path metaDir, List<FileRecord> files, List<DeviceRecord> devices)
            throws IOException {
        Files.createDirectories(metaDir);
        Files.writeString(metaDir.resolve("files.json"),
                MAPPER.writeValueAsString(new MetaStore.FilesFile(MetaStore.LEGACY_FORMAT_VERSION, files)));
        if (!devices.isEmpty()) {
            Files.writeString(metaDir.resolve("devices.json"),
                    MAPPER.writeValueAsString(new MetaStore.DevicesFile(MetaStore.LEGACY_FORMAT_VERSION, devices)));
        }
    }

    private static List<Path> migratedBackups(Path metaDir, String originalName) throws IOException {
        try (Stream<Path> listing = Files.list(metaDir)) {
            return listing.filter(path -> path.getFileName().toString().startsWith(originalName + ".migrated-"))
                    .toList();
        }
    }

    private static long fileRowCount(Path metaDir) {
        return legacyFileCount(metaDir, "file_meta");
    }

    private static long legacyFileCount(Path metaDir, String table) {
        Path db = metaDir.resolve("metadata.sqlite3");
        if (!Files.isRegularFile(db)) {
            return 0L;
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             ResultSet resultSet = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new AssertionError("查询 SQLite 失败", e);
        }
    }
}
