package com.wenshan.upload.storage;

import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FTP 式存储根目录：启动初始化（.upload-meta + 系统临时目录）、严格路径约束。
 */
class StoragePathsTest {

    @TempDir
    Path tempDir;

    private StoragePaths newPaths() {
        return new StoragePaths(tempDir.resolve("root").toString());
    }

    @Test
    void createsHiddenMetaDirAndSystemTempDirWithoutLegacyDirs() throws IOException {
        StoragePaths paths = newPaths();

        assertThat(paths.root()).isAbsolute();
        assertThat(paths.root()).isEqualTo(tempDir.resolve("root").toAbsolutePath().normalize());
        assertThat(paths.metaDir()).isDirectory();
        assertThat(paths.metaDir()).isEqualTo(paths.root().resolve(".upload-meta"));
        assertThat(paths.tmpDir()).isDirectory();
        assertThat(paths.tmpDir()).isEqualTo(Path.of(System.getProperty("java.io.tmpdir"),
                "scop-ftp-tmp").toAbsolutePath().normalize());

        // 新布局不再创建 <root>/files、<root>/meta、<root>/tmp
        assertThat(paths.root().resolve("files")).doesNotExist();
        assertThat(paths.root().resolve("meta")).doesNotExist();
        assertThat(paths.root().resolve("tmp")).doesNotExist();
        try (Stream<Path> children = Files.list(paths.root())) {
            assertThat(children.map(path -> path.getFileName().toString()).toList())
                    .containsExactly(".upload-meta");
        }

        assertThat(paths.resolveStoredPath("docs/report.pdf"))
                .isEqualTo(paths.root().resolve("docs/report.pdf"));
        assertThat(paths.resolveMetaFile("metadata.sqlite3"))
                .isEqualTo(paths.metaDir().resolve("metadata.sqlite3"));
    }

    @Test
    void rejectsAbsoluteDotDotEmptyAndInternalStoredPaths() {
        StoragePaths paths = newPaths();

        String[] invalidPaths = {
                null, "", "   ", "  /etc/passwd  ",
                "/etc/passwd", "../outside.txt", "a/../../outside.txt", "a/./b.txt", "..", ".",
                ".upload-meta", ".upload-meta/files.json", ".upload-meta/metadata.sqlite3"
        };
        for (String invalidPath : invalidPaths) {
            assertThatThrownBy(() -> paths.resolveStoredPath(invalidPath))
                    .as("storedPath=%s", invalidPath)
                    .isInstanceOfSatisfying(BizException.class,
                            exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        }
    }

    @Test
    void rejectsSymlinkComponentsForReadAndBrowse() throws IOException {
        StoragePaths paths = newPaths();
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(paths.root().resolve("escape"), outside);

        assertThatThrownBy(() -> paths.resolveStoredPath("escape/secret.txt"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> paths.resolveStoredPath("escape"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> paths.resolveRootDir("escape"))
                .isInstanceOf(BizException.class);
        // 目录外文件未被触碰
        assertThat(outside.resolve("secret.txt")).exists();
    }

    @Test
    void resolvesRootAndSubdirectoriesForBrowse() throws IOException {
        StoragePaths paths = newPaths();
        Files.createDirectories(paths.root().resolve("docs/sub"));

        assertThat(paths.resolveRootDir(null)).isEqualTo(paths.root());
        assertThat(paths.resolveRootDir("")).isEqualTo(paths.root());
        assertThat(paths.resolveRootDir("docs")).isEqualTo(paths.root().resolve("docs"));
        assertThat(paths.resolveRootDir("docs/sub")).isEqualTo(paths.root().resolve("docs/sub"));

        for (String invalidPath : new String[]{"../etc", "/etc", "docs/../../x", "docs/..", ".upload-meta"}) {
            assertThatThrownBy(() -> paths.resolveRootDir(invalidPath))
                    .as("browse path=%s", invalidPath)
                    .isInstanceOfSatisfying(BizException.class,
                            exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        }
    }

    @Test
    void startupFailsFastWhenMetaDirIsNotWritable() throws IOException {
        Path root = tempDir.resolve("readonly-root");
        StoragePaths writable = new StoragePaths(root.toString());

        assumeFalse("root".equals(System.getProperty("user.name")), "root 用户不受目录权限限制");
        assumeTrue(Files.getFileStore(writable.metaDir())
                .supportsFileAttributeView(PosixFileAttributeView.class), "仅 POSIX 文件系统验证");

        Files.setPosixFilePermissions(writable.metaDir(), PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assertThatThrownBy(() -> new StoragePaths(root.toString()))
                    .as("root 下 .upload-meta 不可写时必须在启动阶段快速失败")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("存储根目录不可用或不可写")
                    .hasMessageContaining(root.toAbsolutePath().normalize().toString());
        } finally {
            Files.setPosixFilePermissions(writable.metaDir(), PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }
}
