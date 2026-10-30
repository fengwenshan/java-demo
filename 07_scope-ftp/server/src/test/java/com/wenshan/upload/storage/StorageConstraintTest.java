package com.wenshan.upload.storage;

import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.config.AppProperties;
import com.wenshan.upload.dto.FileMeta;
import com.wenshan.upload.security.SignatureService;
import com.wenshan.upload.service.FileService;
import com.wenshan.upload.service.FileStorageService;
import com.wenshan.upload.storage.StorageRootRegistry;
import com.wenshan.upload.store.FileRecord;
import com.wenshan.upload.store.MetaStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 存储目录约束：FTP 式落盘 + 篡改 metadata storedPath 后下载/删除必须被拒绝（不能读/删 root 外文件）。
 */
class StorageConstraintTest {

    private static final String APP_KEY = "demo-app";

    @TempDir
    Path tempDir;

    private AppProperties properties;
    private StorageRootRegistry rootRegistry;
    private StoragePaths storagePaths;
    private MetaStore metaStore;
    private FileStorageService storageService;
    private FileService fileService;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        Path rootPath = tempDir.resolve("root");

        rootRegistry = new StorageRootRegistry(properties, "default=" + rootPath);
        storagePaths = rootRegistry.defaultRoot().paths();
        metaStore = rootRegistry.defaultRoot().metaStore();
        storageService = new FileStorageService(properties);
        fileService = new FileService(rootRegistry, storageService, properties, Clock.systemUTC());
    }

    @Test
    void uploadLandsUnderConfiguredRootOnly() throws IOException {
        FileMeta uploaded = upload("custom-root.txt", "custom root content");

        FileRecord record = metaStore.findFile(APP_KEY, uploaded.id()).orElseThrow();
        assertThat(record.storedPath()).isEqualTo("custom-root.txt");
        Path stored = storagePaths.resolveStoredPath(record.storedPath());
        assertThat(stored).startsWith(storagePaths.root()).isRegularFile();
        assertThat(stored.getFileName().toString()).isEqualTo("custom-root.txt");

        assertThat(storagePaths.metaDir().resolve("metadata.sqlite3")).isRegularFile();
        assertThat(regularFileCount(storagePaths.root())).isEqualTo(1);

        // FTP 式布局：root 下不再出现 files/、meta/、tmp/
        assertThat(storagePaths.root().resolve("files")).doesNotExist();
        assertThat(storagePaths.root().resolve("meta")).doesNotExist();
        assertThat(storagePaths.root().resolve("tmp")).doesNotExist();
        assertThat(storagePaths.root()).isNotEqualTo(Path.of("./data").toAbsolutePath().normalize());
    }

    @Test
    void tamperedStoredPathIsRejectedForDownloadAndDelete() throws IOException {
        FileMeta uploaded = upload("tamper.txt", "tamper content");
        FileRecord original = metaStore.findFile(APP_KEY, uploaded.id()).orElseThrow();

        // 1) ../../etc/passwd
        assertTamperedRejected(original, "../../etc/passwd");
        // 2) 绝对路径
        assertTamperedRejected(original, "/etc/passwd");
        // 3) 指向 root 之外的符号链接
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path secret = Files.writeString(outside.resolve("secret.txt"), "should-not-be-read");
        Files.createSymbolicLink(storagePaths.root().resolve("escape"), outside);
        assertTamperedRejected(original, "escape/secret.txt");
        // 4) 内部元数据目录
        assertTamperedRejected(original, ".upload-meta/files.json");
        assertTamperedRejected(original, ".upload-meta/metadata.sqlite3");
        assertThat(secret).hasContent("should-not-be-read");
        assertThat(regularFileCount(outside)).isEqualTo(1);
        // 5) Linux/macOS 常见敏感路径也不能被删除
        assertThat(Path.of("/etc/passwd")).exists();
    }

    @Test
    void tamperedStoredPathDoesNotRemoveMetadataRecord() {
        FileMeta uploaded = upload("keep-meta.txt", "keep");
        FileRecord original = metaStore.findFile(APP_KEY, uploaded.id()).orElseThrow();
        FileRecord tampered = withStoredPath(original, "../../etc/passwd");
        metaStore.saveFile(tampered);

        assertThatThrownBy(() -> fileService.delete(APP_KEY, original.id()))
                .isInstanceOfSatisfying(BizException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        // 路径校验失败时元数据记录必须保留，避免数据被半删
        assertThat(metaStore.findFile(APP_KEY, original.id())).isPresent();
    }

    @Test
    void directSqliteTamperIsRejectedAfterReload() throws Exception {
        FileMeta uploaded = upload("sqlite-tamper.txt", "sqlite tamper content");
        FileRecord original = metaStore.findFile(APP_KEY, uploaded.id()).orElseThrow();

        // 直接 UPDATE SQLite（绕过 MetaStore），模拟磁盘上的元数据被篡改
        Path database = storagePaths.metaDir().resolve("metadata.sqlite3");
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "UPDATE file_meta SET stored_path = ? WHERE id = ?")) {
            statement.setString(1, "../../etc/passwd");
            statement.setString(2, original.id());
            statement.executeUpdate();
        }

        // 新实例从 SQLite 重载被篡改的路径，业务层仍必须拒绝
        StorageRootRegistry reloadedRegistry = new StorageRootRegistry(properties, "default=" + storagePaths.root());
        FileService reloadedService = new FileService(reloadedRegistry, storageService, properties, Clock.systemUTC());
        assertThatThrownBy(() -> reloadedService.download(APP_KEY, original.id()))
                .isInstanceOfSatisfying(BizException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> reloadedService.delete(APP_KEY, original.id()))
                .isInstanceOfSatisfying(BizException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThat(reloadedRegistry.defaultRoot().metaStore().findFile(APP_KEY, original.id())).isPresent();
    }

    private void assertTamperedRejected(FileRecord original, String tamperedPath) {
        metaStore.saveFile(withStoredPath(original, tamperedPath));

        assertThatThrownBy(() -> fileService.download(APP_KEY, original.id()))
                .as("download storedPath=%s", tamperedPath)
                .isInstanceOfSatisfying(BizException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> fileService.delete(APP_KEY, original.id()))
                .as("delete storedPath=%s", tamperedPath)
                .isInstanceOfSatisfying(BizException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThat(metaStore.findFile(APP_KEY, original.id())).isPresent();

        // 还原合法记录，保证后续场景（如 symlink）从干净状态开始
        metaStore.saveFile(original);
    }

    private FileRecord withStoredPath(FileRecord record, String storedPath) {
        return new FileRecord(record.id(), record.appKey(), record.deviceId(), record.name(),
                record.description(), record.size(), record.contentType(), record.sha256(),
                storedPath, record.createdAt());
    }

    private FileMeta upload(String fileName, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", fileName, "text/plain", bytes);
        return fileService.upload(APP_KEY, "device-1", null, file, fileName, null, null,
                SignatureService.sha256Hex(bytes));
    }

    /** 只统计用户区（不把 .upload-meta 里的元数据文件算进去）。 */
    private static long regularFileCount(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return 0;
        }
        Path metaDir = directory.resolve(".upload-meta");
        try (Stream<Path> walk = Files.walk(directory)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(metaDir))
                    .toList();
            return files.size();
        }
    }
}
