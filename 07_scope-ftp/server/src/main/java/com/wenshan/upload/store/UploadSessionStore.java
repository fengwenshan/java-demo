package com.wenshan.upload.store;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 分片上传会话的磁盘存储（断点续传基础）。
 *
 * <p>会话目录固定在系统临时目录：{@code ${java.io.tmpdir}/scop-ftp-sessions/<uploadId>/}，
 * 内含 {@code meta.json}（会话元信息）与 {@code chunk-<index>.part}（已接收分片）。
 * 会话目录**不在**用户存储根目录内，因此不会出现在目录浏览/搜索中。启动时清理超过 TTL
 * （默认 24 小时，{@code app.storage.session-ttl-ms}）的会话。</p>
 *
 * <p>本类只负责路径、序列化与清理；分片写入的大小/哈希/顺序校验与并发控制在
 * {@code ChunkUploadService} 内完成。</p>
 */
@Component
public class UploadSessionStore {

    private static final Logger log = LoggerFactory.getLogger(UploadSessionStore.class);

    /** 系统临时目录下的会话根目录名。 */
    public static final String SESSION_ROOT_NAME = "scop-ftp-sessions";

    private static final String META_FILE_NAME = "meta.json";
    private static final String CHUNK_PREFIX = "chunk-";
    private static final String CHUNK_SUFFIX = ".part";
    private static final String TEMP_SUFFIX = ".tmp-";

    private static final Pattern UPLOAD_ID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern CHUNK_FILE_PATTERN = Pattern.compile("^chunk-(\\d+)\\.part$");

    /** meta.json 固定 ISO-8601 时间与 camelCase，避免依赖 Web 层的 Jackson 配置。 */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final Path sessionRoot;

    @Autowired
    public UploadSessionStore(AppProperties properties) {
        this(Path.of(System.getProperty("java.io.tmpdir"), SESSION_ROOT_NAME));
        long ttlMs = properties.getStorage().getSessionTtlMs();
        cleanupExpired(ttlMs);
        log.info("分片上传会话目录已生效: {}（TTL={}ms，启动时已清理过期会话）", sessionRoot, ttlMs);
    }

    /** 测试用：指定会话根目录（不自动清理过期会话，便于单独验证 cleanupExpired）。 */
    UploadSessionStore(Path sessionRoot) {
        this.sessionRoot = sessionRoot.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.sessionRoot);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建分片上传会话目录: " + this.sessionRoot, e);
        }
    }

    /** 会话根目录（绝对路径）。 */
    public Path sessionRoot() {
        return sessionRoot;
    }

    /** 校验 uploadId 是否为标准 UUID 字符串；非法时抛 40000，避免路径拼接被注入。 */
    public static String requireValidUploadId(String uploadId) {
        if (uploadId == null || !UPLOAD_ID_PATTERN.matcher(uploadId).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "uploadId 非法");
        }
        return uploadId.toLowerCase(java.util.Locale.ROOT);
    }

    /** 会话目录 {@code <sessionRoot>/<uploadId>}（uploadId 已校验）。 */
    public Path sessionDir(String uploadId) {
        return sessionRoot.resolve(requireValidUploadId(uploadId));
    }

    /** 分片文件路径 {@code <sessionDir>/chunk-<index>.part}。 */
    public Path chunkPath(String uploadId, int index) {
        return sessionDir(uploadId).resolve(CHUNK_PREFIX + index + CHUNK_SUFFIX);
    }

    /** 写入会话 meta.json（临时文件 + 原子移动）；会话目录不存在时创建。 */
    public void saveMeta(UploadSessionMeta meta) {
        Path directory = sessionDir(meta.uploadId());
        Path target = directory.resolve(META_FILE_NAME);
        Path temp = directory.resolve(META_FILE_NAME + TEMP_SUFFIX + UUID.randomUUID());
        try {
            Files.createDirectories(directory);
            try (OutputStream output = Files.newOutputStream(temp,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                MAPPER.writeValue(output, meta);
                output.flush();
            }
            try {
                Files.move(temp, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "上传会话写入失败");
        }
    }

    /** 读取会话 meta.json；会话不存在返回 empty，uploadId 非法抛 40000。 */
    public Optional<UploadSessionMeta> loadMeta(String uploadId) {
        Path directory = sessionDir(uploadId);
        Path target = directory.resolve(META_FILE_NAME);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(target);
            if (bytes.length == 0) {
                return Optional.empty();
            }
            return Optional.of(MAPPER.readValue(bytes, UploadSessionMeta.class));
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "上传会话读取失败");
        }
    }

    /** 已接收分片 index 列表（升序）；会话不存在返回空列表。 */
    public List<Integer> receivedChunks(String uploadId) {
        Path directory = sessionDir(uploadId);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<Integer> indexes = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                if (Files.isSymbolicLink(child) || !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                Matcher matcher = CHUNK_FILE_PATTERN.matcher(child.getFileName().toString());
                if (matcher.matches()) {
                    try {
                        indexes.add(Integer.parseInt(matcher.group(1)));
                    } catch (NumberFormatException ignored) {
                        // 超大数字文件名视为非法分片，忽略
                    }
                }
            }
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "上传会话读取失败");
        }
        indexes.sort(Comparator.naturalOrder());
        return List.copyOf(indexes);
    }

    /** 删除整个会话目录（递归，best effort 失败抛 50000）。 */
    public void deleteSession(String uploadId) {
        Path directory = sessionDir(uploadId);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        deleteRecursively(directory);
    }

    /** 启动时清理超过 ttlMs 的会话（meta.createdAt 缺失时按目录最后修改时间判断）。 */
    public void cleanupExpired(long ttlMs) {
        if (!Files.isDirectory(sessionRoot)) {
            return;
        }
        long now = System.currentTimeMillis();
        int removed = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sessionRoot)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (Files.isSymbolicLink(child)) {
                    continue;
                }
                long createdAt = sessionCreatedAt(child);
                if (now - createdAt > ttlMs) {
                    deleteRecursively(child);
                    removed++;
                }
            }
        } catch (IOException e) {
            log.warn("清理过期分片上传会话失败: {}", e.getMessage());
            return;
        }
        if (removed > 0) {
            log.info("已清理 {} 个过期分片上传会话（TTL={}ms）", removed, ttlMs);
        }
    }

    private long sessionCreatedAt(Path sessionDirectory) {
        Path metaFile = sessionDirectory.resolve(META_FILE_NAME);
        if (Files.isRegularFile(metaFile, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Instant createdAt = MAPPER.readValue(Files.readAllBytes(metaFile),
                        UploadSessionMeta.class).createdAt();
                if (createdAt != null) {
                    return createdAt.toEpochMilli();
                }
            } catch (IOException ignored) {
                // 坏 meta 回退到目录时间
            }
        }
        try {
            return Files.getLastModifiedTime(sessionDirectory, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException e) {
            return System.currentTimeMillis();
        }
    }

    private void deleteRecursively(Path path) {
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException e) {
                    log.warn("删除会话文件失败: {} ({})", item, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("删除会话目录失败: {} ({})", path, e.getMessage());
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("清理会话临时文件失败: {}", path);
        }
    }

    /**
     * 会话元信息（meta.json）：初始化时写入，按 index 分片续传、complete 时校验。
     *
     * @param uploadId    会话 id（UUID）
     * @param name        客户端声明的展示文件名（已清洗）
     * @param dir         相对存储根目录的目标目录（空字符串表示 root）
     * @param size        声明文件大小（字节）
     * @param sha256      声明整文件 SHA-256（小写 hex）
     * @param description 说明，可为 null
     * @param chunkSize   分片大小（字节）
     * @param createdAt   会话创建时间（UTC，秒精度）
     * @param rootId      目标存储根 id（旧会话没有该字段时为 null，complete 时按默认根处理）
     */
    public record UploadSessionMeta(
            String uploadId,
            String name,
            String dir,
            long size,
            String sha256,
            String description,
            int chunkSize,
            Instant createdAt,
            String rootId
    ) {

        /** 兼容旧测试/旧会话文件的构造器（不带 rootId）。 */
        public UploadSessionMeta(String uploadId, String name, String dir, long size, String sha256,
                                 String description, int chunkSize, Instant createdAt) {
            this(uploadId, name, dir, size, sha256, description, chunkSize, createdAt, null);
        }
    }
}
