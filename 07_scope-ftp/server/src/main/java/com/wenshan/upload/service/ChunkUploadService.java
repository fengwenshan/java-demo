package com.wenshan.upload.service;

import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.config.AppProperties;
import com.wenshan.upload.dto.ChunkAckData;
import com.wenshan.upload.dto.ChunkCancelData;
import com.wenshan.upload.dto.ChunkInitData;
import com.wenshan.upload.dto.ChunkInitRequest;
import com.wenshan.upload.dto.ChunkStatusData;
import com.wenshan.upload.dto.FileMeta;
import com.wenshan.upload.storage.StorageRoot;
import com.wenshan.upload.storage.StorageRootRegistry;
import com.wenshan.upload.store.FileRecord;
import com.wenshan.upload.store.UploadSessionStore;
import com.wenshan.upload.store.UploadSessionStore.UploadSessionMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * 大文件分片上传 / 断点续传（契约 3.11 ~ 3.15）。
 *
 * <p>流程：{@code init} 建立会话（会话目录在系统临时目录，见 {@link UploadSessionStore}）→
 * {@code chunk} 按 index 乱序/重复上传分片（每片 sha 校验、大小上限、累计大小上限）→
 * {@code status} 返回已接收分片供续传 → {@code complete} 校验分片齐全 + 总大小 + 整文件 SHA-256，
 * 然后以与单文件上传完全一致的目录校验/文件名清洗/重名规则落到存储根目录内，写元数据并清理会话。</p>
 *
 * <p>同一 uploadId 的写操作（chunk/complete/cancel）由每会话独立锁串行化，避免并发写坏分片；
 * 会话不存在返回 40400。complete 缺片/大小不符返回 40000 并**保留会话**（可续传），
 * 整文件 SHA 不符返回 40001 并**清理会话**。</p>
 */
@Service
public class ChunkUploadService {

    private static final Logger log = LoggerFactory.getLogger(ChunkUploadService.class);

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int DEFAULT_CHUNK_SIZE = 8 * 1024 * 1024;
    private static final Pattern SHA256_HEX_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final String CHUNK_TEMP_SUFFIX = ".tmp-";

    private final UploadSessionStore sessionStore;
    private final FileStorageService storageService;
    private final StorageRootRegistry rootRegistry;
    private final AppProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<String, ReentrantLock> uploadLocks = new ConcurrentHashMap<>();

    public ChunkUploadService(UploadSessionStore sessionStore,
                              FileStorageService storageService,
                              StorageRootRegistry rootRegistry,
                              AppProperties properties,
                              Clock clock) {
        this.sessionStore = sessionStore;
        this.storageService = storageService;
        this.rootRegistry = rootRegistry;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 契约 3.11：建立分片上传会话。
     *
     * <p>校验顺序：name → size（&gt;0 且 ≤ maxFileSize，超出 40003）→ sha256（64 hex）→ dir
     * （规则同单文件上传：非法 40000 / 不存在 40400 / 是文件 40000）→ description（≤200）。</p>
     */
    public ChunkInitData init(ChunkInitRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请求体不能为空");
        }
        String safeName = FileService.sanitizeFileName(request.name());
        if (safeName == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "name 必填且不能为空");
        }
        long size = request.size() == null ? 0L : request.size();
        if (size <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "size 必须大于 0");
        }
        long maxFileSize = properties.getStorage().getMaxFileSize();
        if (size > maxFileSize) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE,
                    "文件超过大小限制（最大 " + maxFileSize + " 字节）");
        }
        String sha256 = normalizeSha256(request.sha256());
        // root 决定整个会话的落盘根：后续 chunk/complete/cancel 都按会话记录的 rootId 解析
        StorageRoot root = rootRegistry.requireRoot(request.root());
        String dir = request.dir() == null ? "" : request.dir();
        // 目录规则与单文件上传一致：必须在指定 root 内且已存在、且不是内部元数据目录
        storageService.resolveTargetDirectory(root.paths(), dir.isBlank() ? null : dir);
        String description = FileService.normalizeDescription(request.description());
        int chunkSize = effectiveChunkSize();

        String uploadId = UUID.randomUUID().toString();
        Instant createdAt = Instant.ofEpochMilli(clock.millis()).truncatedTo(ChronoUnit.SECONDS);
        sessionStore.saveMeta(new UploadSessionMeta(
                uploadId, safeName, dir, size, sha256, description, chunkSize, createdAt, root.id()));
        log.info("分片上传会话已创建: uploadId={}, rootId={}, name={}, dir={}, size={}, chunks={}",
                uploadId, root.id(), safeName, dir, size, totalChunks(size, chunkSize));
        return new ChunkInitData(uploadId, chunkSize);
    }

    /**
     * 契约 3.12：写入一个分片（裸字节）。重复写同一 index 是幂等的（原子覆盖，可用于失败重试）。
     *
     * @param declaredChunkSha256 请求头 X-Content-Sha256（已参与签名），必须等于该分片真实 SHA-256
     */
    public ChunkAckData writeChunk(String uploadId, int index, InputStream body, String declaredChunkSha256) {
        String id = UploadSessionStore.requireValidUploadId(uploadId);
        ReentrantLock lock = lockFor(id);
        lock.lock();
        try {
            UploadSessionMeta meta = requireSession(id);
            int chunkSize = meta.chunkSize();
            long totalChunks = totalChunks(meta.size(), chunkSize);
            if (index < 0 || index >= totalChunks) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "index 越界（必须为 0~" + (totalChunks - 1) + "）");
            }
            String expectedSha = normalizeSha256(declaredChunkSha256);
            if (body == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "缺少分片内容");
            }

            Path sessionDir = sessionStore.sessionDir(id);
            Path temp = sessionDir.resolve("chunk-" + index + ".part" + CHUNK_TEMP_SUFFIX + UUID.randomUUID());
            long writtenBytes = 0L;
            String actualSha;
            MessageDigest digest = newSha256();
            try (InputStream input = body;
                 OutputStream output = Files.newOutputStream(temp,
                         StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    writtenBytes += read;
                    if (writtenBytes > chunkSize) {
                        throw new BizException(ErrorCode.BAD_REQUEST,
                                "分片大小超过 chunkSize=" + chunkSize);
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
                actualSha = HexFormat.of().formatHex(digest.digest());
            } catch (IOException e) {
                deleteQuietly(temp);
                throw new BizException(ErrorCode.INTERNAL_ERROR, "分片写入失败");
            } catch (BizException e) {
                deleteQuietly(temp);
                throw e;
            }
            if (writtenBytes == 0) {
                deleteQuietly(temp);
                throw new BizException(ErrorCode.BAD_REQUEST, "分片为空");
            }
            if (!actualSha.equals(expectedSha)) {
                deleteQuietly(temp);
                throw new BizException(ErrorCode.CONTENT_HASH_MISMATCH, "分片内容校验失败");
            }
            long cumulative = writtenBytes + otherChunksSize(id, index);
            if (cumulative > meta.size()) {
                deleteQuietly(temp);
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "累计分片大小超过声明的 size=" + meta.size());
            }
            Path chunkTarget = sessionStore.chunkPath(id, index);
            try {
                try {
                    Files.move(temp, chunkTarget,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, chunkTarget, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                deleteQuietly(temp);
                throw new BizException(ErrorCode.INTERNAL_ERROR, "分片写入失败");
            }
            int received = sessionStore.receivedChunks(id).size();
            return new ChunkAckData(id, received);
        } finally {
            lock.unlock();
        }
    }

    /** 契约 3.13：查询已接收分片（断点续传）。会话不存在 → 40400。 */
    public ChunkStatusData status(String uploadId) {
        UploadSessionMeta meta = requireSession(uploadId);
        List<Integer> received = sessionStore.receivedChunks(meta.uploadId());
        return new ChunkStatusData(meta.uploadId(), meta.name(), meta.size(), meta.chunkSize(), received);
    }

    /**
     * 契约 3.14：校验分片齐全 + 总大小 + 整文件 SHA-256 后拼装落盘并写元数据。
     *
     * <p>缺片/总大小不符 → 40000 且保留会话（客户端可补传后重试）；
     * 整文件 SHA-256 与 init 声明值不符 → 40001 且删除会话与半成品。</p>
     */
    public FileMeta complete(String appKey, String deviceId, String uploadId) {
        String id = UploadSessionStore.requireValidUploadId(uploadId);
        ReentrantLock lock = lockFor(id);
        lock.lock();
        try {
            UploadSessionMeta meta = requireSession(id);
            StorageRoot root = resolveSessionRoot(meta);
            long totalChunks = totalChunks(meta.size(), meta.chunkSize());
            List<Integer> received = sessionStore.receivedChunks(id);
            if (!isComplete(received, totalChunks)) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "分片不完整（已接收 " + received.size() + "/" + totalChunks + "）");
            }
            long sum = chunksTotalSize(id, received);
            if (sum != meta.size()) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "分片总大小 " + sum + " 与声明的 size=" + meta.size() + " 不一致");
            }
            // 目标目录仍需存在（init 后可能被删除）
            storageService.resolveTargetDirectory(root.paths(), meta.dir().isBlank() ? null : meta.dir());

            FileStorageService.StoreResult stored;
            try {
                stored = storageService.store(root.paths(), meta.dir().isBlank() ? null : meta.dir(),
                        meta.name(), () -> new ChunkSequenceInputStream(chunkPaths(id, totalChunks)));
            } catch (BizException e) {
                // 目录/大小等问题：保留会话，客户端可修复后重试
                throw e;
            }
            if (stored.size() != meta.size()) {
                storageService.delete(root.paths(), stored.relativePath());
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "拼装后大小 " + stored.size() + " 与声明的 size=" + meta.size() + " 不一致");
            }
            if (!stored.sha256().equalsIgnoreCase(meta.sha256())) {
                storageService.delete(root.paths(), stored.relativePath());
                sessionStore.deleteSession(id);
                uploadLocks.remove(id, lock);
                throw new BizException(ErrorCode.CONTENT_HASH_MISMATCH,
                        "整文件 SHA-256 与声明值不一致");
            }

            Instant createdAt = Instant.ofEpochMilli(clock.millis()).truncatedTo(ChronoUnit.SECONDS);
            FileRecord record = new FileRecord(
                    UUID.randomUUID().toString(),
                    appKey,
                    deviceId,
                    meta.name(),
                    meta.description(),
                    stored.size(),
                    FileService.inferContentType(meta.name()),
                    stored.sha256(),
                    stored.relativePath(),
                    createdAt,
                    root.id());
            try {
                root.metaStore().saveFile(record);
            } catch (RuntimeException e) {
                // 元数据落盘失败：删除已拼装文件，保留会话以便重试 complete
                storageService.delete(root.paths(), stored.relativePath());
                throw e;
            }
            sessionStore.deleteSession(id);
            uploadLocks.remove(id, lock);
            log.info("分片上传完成: uploadId={}, rootId={}, storedPath={}, size={}",
                    id, root.id(), stored.relativePath(), stored.size());
            return FileService.toMeta(record, root.id());
        } finally {
            lock.unlock();
        }
    }

    /** 契约 3.15：取消上传（删除会话目录）；会话不存在 → 40400。 */
    public ChunkCancelData cancel(String uploadId) {
        String id = UploadSessionStore.requireValidUploadId(uploadId);
        ReentrantLock lock = lockFor(id);
        lock.lock();
        try {
            requireSession(id);
            sessionStore.deleteSession(id);
            uploadLocks.remove(id, lock);
            return new ChunkCancelData(id);
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------

    /** 会话记录的 rootId 决定落盘根；旧会话（无 rootId）按默认根处理。 */
    private StorageRoot resolveSessionRoot(UploadSessionMeta meta) {
        String rootId = meta.rootId() == null || meta.rootId().isBlank()
                ? rootRegistry.defaultRootId()
                : meta.rootId();
        StorageRoot root = rootRegistry.findRoot(rootId).orElse(null);
        if (root == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "上传会话的存储根已不存在: " + rootId);
        }
        return root;
    }

    private UploadSessionMeta requireSession(String uploadId) {
        String id = UploadSessionStore.requireValidUploadId(uploadId);
        return sessionStore.loadMeta(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "上传会话不存在"));
    }

    private ReentrantLock lockFor(String uploadId) {
        return uploadLocks.computeIfAbsent(uploadId, key -> new ReentrantLock());
    }

    private int effectiveChunkSize() {
        int configured = properties.getStorage().getChunkSize();
        return configured > 0 ? configured : DEFAULT_CHUNK_SIZE;
    }

    private static long totalChunks(long size, int chunkSize) {
        long effective = chunkSize > 0 ? chunkSize : 1L;
        return (size + effective - 1) / effective;
    }

    private static boolean isComplete(List<Integer> received, long totalChunks) {
        if (totalChunks > Integer.MAX_VALUE || received.size() != (int) totalChunks) {
            return false;
        }
        for (int index = 0; index < totalChunks; index++) {
            if (!received.get(index).equals(index)) {
                return false;
            }
        }
        return true;
    }

    /** 已接收分片中除 {@code excludedIndex} 之外的分片总大小（用于累计大小上限校验）。 */
    private long otherChunksSize(String uploadId, int excludedIndex) {
        long total = 0L;
        for (int receivedIndex : sessionStore.receivedChunks(uploadId)) {
            if (receivedIndex == excludedIndex) {
                continue;
            }
            try {
                total += Files.size(sessionStore.chunkPath(uploadId, receivedIndex));
            } catch (IOException e) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "分片读取失败");
            }
        }
        return total;
    }

    private long chunksTotalSize(String uploadId, List<Integer> received) {
        long total = 0L;
        for (int index : received) {
            try {
                total += Files.size(sessionStore.chunkPath(uploadId, index));
            } catch (IOException e) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "分片读取失败");
            }
        }
        return total;
    }

    private List<Path> chunkPaths(String uploadId, long totalChunks) {
        return java.util.stream.LongStream.range(0, totalChunks)
                .mapToObj(index -> sessionStore.chunkPath(uploadId, (int) index))
                .toList();
    }

    private static String normalizeSha256(String sha256) {
        if (sha256 == null || sha256.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "sha256 必填");
        }
        String normalized = sha256.trim().toLowerCase(Locale.ROOT);
        if (!SHA256_HEX_PATTERN.matcher(normalized).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "sha256 必须为 64 位十六进制");
        }
        return normalized;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("清理分片临时文件失败: {}", path);
        }
    }

    /**
     * 按顺序拼接分片文件的输入流（惰性打开，最多同时打开一个分片文件，避免大文件打开过多 FD）。
     */
    private static final class ChunkSequenceInputStream extends InputStream {

        private final List<Path> chunkPaths;
        private int nextIndex;
        private InputStream current;

        private ChunkSequenceInputStream(List<Path> chunkPaths) {
            this.chunkPaths = chunkPaths;
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int read = read(single, 0, 1);
            return read == -1 ? -1 : single[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            while (true) {
                if (current == null) {
                    if (nextIndex >= chunkPaths.size()) {
                        return -1;
                    }
                    current = Files.newInputStream(chunkPaths.get(nextIndex++));
                }
                int read = current.read(buffer, offset, length);
                if (read != -1) {
                    return read;
                }
                current.close();
                current = null;
            }
        }

        @Override
        public void close() throws IOException {
            if (current != null) {
                current.close();
                current = null;
            }
        }
    }
}
