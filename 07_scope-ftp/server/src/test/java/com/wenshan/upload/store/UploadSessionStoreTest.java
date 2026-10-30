package com.wenshan.upload.store;

import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.store.UploadSessionStore.UploadSessionMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分片上传会话存储：meta 读写、分片枚举、启动清理（>24h）与路径安全。
 */
class UploadSessionStoreTest {

    private static final long DAY_MS = 86_400_000L;

    @TempDir
    Path tempDir;

    @Test
    void savesAndLoadsSessionMetaAndEnumeratesChunks() throws IOException {
        UploadSessionStore store = new UploadSessionStore(tempDir);
        String uploadId = UUID.randomUUID().toString();
        UploadSessionMeta meta = new UploadSessionMeta(
                uploadId, "报告.pdf", "docs", 2_500L, "a".repeat(64), "说明", 1024,
                Instant.now().truncatedTo(ChronoUnit.SECONDS));
        store.saveMeta(meta);

        assertThat(store.loadMeta(uploadId)).contains(meta);
        assertThat(store.receivedChunks(uploadId)).isEmpty();

        // 乱序写入分片文件（含一个临时文件与非法命名，均不计入）
        Files.createDirectories(store.sessionDir(uploadId));
        Files.writeString(store.chunkPath(uploadId, 2), "c2", StandardCharsets.UTF_8);
        Files.writeString(store.chunkPath(uploadId, 0), "c0", StandardCharsets.UTF_8);
        Files.writeString(store.sessionDir(uploadId).resolve("chunk-1.part.tmp-x"), "tmp");
        Files.writeString(store.sessionDir(uploadId).resolve("not-a-chunk.txt"), "junk");
        assertThat(store.receivedChunks(uploadId)).containsExactly(0, 2);

        store.deleteSession(uploadId);
        assertThat(store.sessionDir(uploadId)).doesNotExist();
        assertThat(store.loadMeta(uploadId)).isEmpty();
    }

    @Test
    void cleanupExpiredRemovesOnlyOldSessions() throws IOException {
        UploadSessionStore store = new UploadSessionStore(tempDir);
        String oldId = UUID.randomUUID().toString();
        String freshId = UUID.randomUUID().toString();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        store.saveMeta(new UploadSessionMeta(oldId, "old.bin", "", 10L, "a".repeat(64), null, 1024,
                now.minusMillis(25 * DAY_MS)));
        store.saveMeta(new UploadSessionMeta(freshId, "fresh.bin", "", 10L, "b".repeat(64), null, 1024,
                now.minusMillis(DAY_MS / 2)));
        Files.writeString(store.chunkPath(oldId, 0), "x", StandardCharsets.UTF_8);
        Files.writeString(store.chunkPath(freshId, 0), "y", StandardCharsets.UTF_8);

        store.cleanupExpired(DAY_MS);

        assertThat(store.sessionDir(oldId)).doesNotExist();
        assertThat(store.sessionDir(freshId)).isDirectory();
        assertThat(store.receivedChunks(freshId)).containsExactly(0);
    }

    @Test
    void invalidUploadIdRejected() {
        UploadSessionStore store = new UploadSessionStore(tempDir);
        assertThatThrownBy(() -> store.sessionDir("../../etc/passwd"))
                .isInstanceOfSatisfying(BizException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> store.loadMeta("not-a-uuid"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void sessionRootUnderExpectedSystemTempName() {
        assertThat(UploadSessionStore.SESSION_ROOT_NAME).isEqualTo("scop-ftp-sessions");
        // 生产构造函数使用 ${java.io.tmpdir}/scop-ftp-sessions
        Path expected = Path.of(System.getProperty("java.io.tmpdir"), "scop-ftp-sessions");
        assertThat(expected.getFileName().toString()).isEqualTo(UploadSessionStore.SESSION_ROOT_NAME);
    }

    @Test
    void receivedChunksReadsEmptyForMissingSession() {
        UploadSessionStore store = new UploadSessionStore(tempDir);
        assertThat(store.receivedChunks(UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    void sessionMetaRecordCarriesAllFields() {
        UploadSessionMeta meta = new UploadSessionMeta("id", "n", "d", 3L, "c".repeat(64), "desc", 8,
                Instant.EPOCH);
        assertThat(List.of(meta.uploadId(), meta.name(), meta.dir())).containsExactly("id", "n", "d");
        assertThat(meta.size()).isEqualTo(3L);
        assertThat(meta.chunkSize()).isEqualTo(8);
    }
}
