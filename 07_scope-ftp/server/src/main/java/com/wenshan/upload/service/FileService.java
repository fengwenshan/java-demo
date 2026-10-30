package com.wenshan.upload.service;

import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.config.AppProperties;
import com.wenshan.upload.dto.DeleteResult;
import com.wenshan.upload.dto.FileListData;
import com.wenshan.upload.dto.FileMeta;
import com.wenshan.upload.dto.PathDeleteResult;
import com.wenshan.upload.dto.RenameData;
import com.wenshan.upload.storage.StoragePaths;
import com.wenshan.upload.storage.StorageRoot;
import com.wenshan.upload.storage.StorageRootRegistry;
import com.wenshan.upload.store.FileRecord;
import com.wenshan.upload.store.MetaStore;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLConnection;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 文件上传 / 列表 / 下载 / 删除业务（元数据走各存储根的 {@link MetaStore} JSON 文件 + 内存索引）。
 *
 * <p>多根模型：所有操作先按请求的 {@code root} 参数经 {@link StorageRootRegistry#requireRoot(String)}
 * 解析出唯一一个 {@link StorageRoot}，再只用该根的 {@code paths}/{@code metaStore} 完成操作——
 * 路径校验、落盘、元数据都不会碰到别的根或根外。</p>
 *
 * <p>FTP 式存储：上传可指定相对 root 的目录，文件以原始文件名落在该目录；元数据 storedPath
 * 相对 root（如 {@code docs/report.pdf}）。</p>
 */
@Service
public class FileService {

    private static final int MAX_NAME_LENGTH = 255;
    private static final int MAX_DESCRIPTION_LENGTH = 200;
    private static final int MAX_KEYWORD_LENGTH = 100;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final StorageRootRegistry rootRegistry;
    private final FileStorageService storageService;
    private final AppProperties properties;
    private final Clock clock;

    public FileService(StorageRootRegistry rootRegistry,
                       FileStorageService storageService,
                       AppProperties properties,
                       Clock clock) {
        this.rootRegistry = rootRegistry;
        this.storageService = storageService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 上传到指定根：{@code root} 缺省=默认根；可选 dir 表示相对 root 的目标目录（空/缺省=root，必须已存在）；
     * expectedSha256 为 X-Content-Sha256（已参与签名），落盘后必须与真实文件哈希一致。
     */
    public FileMeta upload(String appKey, String deviceId, String rootParam, MultipartFile file,
                           String name, String description, String dir, String expectedSha256) {
        StorageRoot root = rootRegistry.requireRoot(rootParam);
        if (file == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少文件");
        }
        if (file.getSize() == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "空文件");
        }
        long maxFileSize = properties.getStorage().getMaxFileSize();
        if (file.getSize() > maxFileSize) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE);
        }
        String safeName = sanitizeFileName(name != null && !name.isBlank() ? name : file.getOriginalFilename());
        if (safeName == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件名无效");
        }
        String normalizedDescription = normalizeDescription(description);

        // 目录合法性/存在性在 FileStorageService 内按该根统一校验（40000 / 40400）
        FileStorageService.StoreResult stored = storageService.store(root.paths(), dir, safeName, file);
        if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(stored.sha256())) {
            storageService.delete(root.paths(), stored.relativePath());
            throw new BizException(ErrorCode.CONTENT_HASH_MISMATCH);
        }

        String contentType = file.getContentType();
        if (contentType == null || contentType.isBlank()) {
            contentType = DEFAULT_CONTENT_TYPE;
        }
        Instant createdAt = Instant.ofEpochMilli(clock.millis()).truncatedTo(ChronoUnit.SECONDS);
        FileRecord record = new FileRecord(
                UUID.randomUUID().toString(),
                appKey,
                deviceId,
                safeName,
                normalizedDescription,
                stored.size(),
                contentType,
                stored.sha256(),
                stored.relativePath(),
                createdAt,
                root.id());
        try {
            root.metaStore().saveFile(record);
        } catch (RuntimeException e) {
            // 元数据落盘失败时删除已写入的磁盘文件，避免留下无主文件
            storageService.delete(root.paths(), stored.relativePath());
            throw e;
        }
        return toMeta(record, root.id());
    }

    /**
     * 契约 3.4 分页查询：不传 root 时聚合所有根（单根部署等价旧行为）；传 root 只查该根。
     * createdAt 倒序（相同时间按 id 升序），关键字过滤与分页在内存完成。
     */
    public FileListData list(String appKey, String rootParam, int page, int size, String keyword) {
        if (page < 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "page 必须从 1 开始");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BizException(ErrorCode.BAD_REQUEST, "size 必须为 1~100");
        }
        String normalizedKeyword = keyword == null ? null : keyword.trim();
        if (normalizedKeyword != null && normalizedKeyword.length() > MAX_KEYWORD_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "keyword 最长 100 字符");
        }
        if (normalizedKeyword != null && normalizedKeyword.isEmpty()) {
            normalizedKeyword = null;
        }
        List<StorageRoot> roots = rootParam == null || rootParam.isBlank()
                ? rootRegistry.roots()
                : List.of(rootRegistry.requireRoot(rootParam));
        String needle = normalizedKeyword == null ? null : normalizedKeyword.toLowerCase(Locale.ROOT);

        List<RecordWithRoot> matched = new ArrayList<>();
        for (StorageRoot root : roots) {
            for (FileRecord record : root.metaStore().filesOf(appKey)) {
                if (needle != null && !record.name().toLowerCase(Locale.ROOT).contains(needle)) {
                    continue;
                }
                matched.add(new RecordWithRoot(root, record));
            }
        }
        matched.sort(Comparator.comparing((RecordWithRoot item) -> item.record().createdAt()).reversed()
                .thenComparing(item -> item.record().id()));
        long total = matched.size();
        int fromIndex = (int) Math.min((long) (page - 1) * size, total);
        int toIndex = (int) Math.min((long) fromIndex + size, total);
        List<FileMeta> items = matched.subList(fromIndex, toIndex).stream()
                .map(item -> toMeta(item.record(), item.root().id()))
                .toList();
        return new FileListData(items, total, page, size);
    }

    /**
     * 契约 3.6 按 id 下载：仅允许下载当前 appKey 上传的文件。
     * 记录按 {@code rootId} 解析到对应根；rootId 未配置或记录与所在根不一致时直接报错（不读任何文件）。
     */
    public FileDownload download(String appKey, String id) {
        Located located = locate(appKey, id);
        // 元数据里的 storedPath 被篡改（绝对路径 / .. / symlink 逃逸 / 内部目录）时在此直接拒绝（40000）
        Path path = storageService.resolve(located.root().paths(), located.record().storedPath());
        if (!storageService.exists(located.root().paths(), located.record().storedPath())) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return new FileDownload(located.record(), path);
    }

    /**
     * 契约 3.7 按 id 删除：先按记录 rootId 解析根并校验路径合法（非法路径不删元数据），
     * 再删该根的元数据记录并落盘，最后清理磁盘文件。无法解析 rootId 时只报错、不删任何东西。
     */
    public DeleteResult delete(String appKey, String id) {
        Located located = locate(appKey, id);
        storageService.resolve(located.root().paths(), located.record().storedPath());
        FileRecord removed = located.root().metaStore().deleteFile(appKey, id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND));
        storageService.delete(located.root().paths(), removed.storedPath());
        return new DeleteResult(removed.id());
    }

    /**
     * 浏览视图按路径下载：path 相对指定根；目录/内部目录/越界 → 40000，不存在 → 40400。
     * 只读取文件内容用于计算 SHA-256（≤ maxFileSize），不写入任何东西。
     */
    public PathDownload downloadByPath(String appKey, String rootParam, String relativePath) {
        StorageRoot root = rootRegistry.requireRoot(rootParam);
        Path path = storageService.resolve(root.paths(), relativePath);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "目标不是文件");
        }
        long size;
        String sha256;
        try {
            size = Files.size(path);
            sha256 = storageService.sha256(path);
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "文件读取失败");
        }
        String canonicalPath = storageService.relativize(root.paths(), path);
        String fileName = path.getFileName().toString();
        return new PathDownload(fileName, inferContentType(fileName), size, sha256, canonicalPath, path);
    }

    /**
     * 浏览视图按路径删除：指定根内普通文件（删除并联动清理 storedPath 完全相同的记录）或空目录；
     * 非空目录 → 40000（不删任何内容），根目录本身/内部元数据/越界/跨根由路径校验拦截（40000）。
     */
    public PathDeleteResult deleteByPath(String appKey, String rootParam, String relativePath) {
        StorageRoot root = rootRegistry.requireRoot(rootParam);
        Path path = storageService.resolve(root.paths(), relativePath);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        String canonicalPath = storageService.relativize(root.paths(), path);
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            // 只允许删除空目录：目录里没有文件/子目录才是空（嵌套的其他存储根含 .upload-meta，会被判非空）
            try (var children = Files.newDirectoryStream(path)) {
                if (children.iterator().hasNext()) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "目录非空，不能删除");
                }
            } catch (IOException e) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "目录读取失败");
            }
            try {
                Files.delete(path);
            } catch (DirectoryNotEmptyException e) {
                throw new BizException(ErrorCode.BAD_REQUEST, "目录非空，不能删除");
            } catch (IOException e) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "目录删除失败");
            }
            // 空目录内没有文件，无需改动元数据
            return new PathDeleteResult(canonicalPath);
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只能删除普通文件或目录");
        }
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "文件删除失败");
        }
        root.metaStore().removeByStoredPath(canonicalPath);
        return new PathDeleteResult(canonicalPath);
    }

    /**
     * 契约 3.17 重命名：{@code path} 相对指定根（规则同下载/删除），{@code newName} 为不含路径分隔符的
     * 单段新名字，不改变所在目录。支持普通文件与目录（目录整体移动）；不覆盖已存在的目标（40000）。
     *
     * <p>磁盘重命名成功后同步元数据（文件：storedPath/name；目录：storedPath 前缀），
     * 元数据写失败时尽力把磁盘改动回滚，避免出现「磁盘已改名但元数据还指向旧路径」。</p>
     */
    public RenameData rename(String appKey, String rootParam, String path, String newName) {
        StorageRoot root = rootRegistry.requireRoot(rootParam);
        if (path == null || path.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少 path 参数");
        }
        String safeName = validateNewName(newName);
        Path source = storageService.resolve(root.paths(), path);
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        boolean directory = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS);
        if (!directory && !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只能重命名普通文件或目录");
        }
        if (directory) {
            rejectRenamingAnotherRoot(root, source);
        }
        String oldPath = storageService.relativize(root.paths(), source);
        Path target = source.getParent().resolve(safeName);
        String newPath = storageService.relativize(root.paths(), target);
        // 目标路径同样走严格校验（防止父目录被并发替换为 symlink 等）
        root.paths().resolveStoredPath(newPath);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            // 包含「大小写不敏感文件系统上仅改大小写」与重命名为自身：一律不覆盖
            throw new BizException(ErrorCode.BAD_REQUEST, "目标名称已存在");
        }
        try {
            // 不带 REPLACE_EXISTING：并发出现同名目标时由文件系统保证不覆盖
            Files.move(source, target);
        } catch (FileAlreadyExistsException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "目标名称已存在");
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "重命名失败");
        }
        try {
            if (directory) {
                root.metaStore().updateDirectoryPrefix(oldPath, newPath);
            } else {
                root.metaStore().updateFilePath(oldPath, newPath, safeName);
            }
        } catch (RuntimeException e) {
            rollbackRename(target, source);
            throw e;
        }
        return new RenameData(oldPath, newPath, directory ? "directory" : "file");
    }

    // ------------------------------------------------------------------

    /**
     * 跨根按 id 定位记录：appKey 过滤语义与旧实现一致；记录 rootId 必须指向已配置的根，
     * 且该根确实持有同 id 记录（防止伪造/搬移元数据造成跨根读写）。
     */
    private Located locate(String appKey, String id) {
        for (StorageRoot owner : rootRegistry.roots()) {
            FileRecord record = owner.metaStore().findFile(appKey, id).orElse(null);
            if (record == null) {
                continue;
            }
            String recordRootId = record.rootId() == null || record.rootId().isBlank()
                    ? owner.id()
                    : record.rootId();
            StorageRoot resolved = rootRegistry.findRoot(recordRootId).orElse(null);
            if (resolved == null) {
                throw new BizException(ErrorCode.INTERNAL_ERROR,
                        "文件元数据 rootId 未配置: " + recordRootId);
            }
            if (!resolved.id().equals(owner.id())) {
                FileRecord inResolved = resolved.metaStore().findFile(appKey, id).orElse(null);
                if (inResolved == null) {
                    throw new BizException(ErrorCode.INTERNAL_ERROR,
                            "文件元数据与存储根不一致: rootId=" + recordRootId);
                }
                record = inResolved;
            }
            return new Located(resolved, record);
        }
        throw new BizException(ErrorCode.NOT_FOUND);
    }

    /**
     * 新名字校验：trim 后 1~255 字符；必须是单段名字（不含 /、\\、NUL、控制字符），
     * 不能是 . / .. 或内部元数据目录名。返回 trim 后的名字。
     */
    static String validateNewName(String raw) {
        String trimmed = raw == null ? null : raw.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "newName 不能为空");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "newName 最长 255 字符");
        }
        if (trimmed.indexOf('\0') >= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "newName 包含非法字符");
        }
        if (trimmed.indexOf('/') >= 0 || trimmed.indexOf('\\') >= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "newName 不能包含路径分隔符");
        }
        for (int index = 0; index < trimmed.length(); index++) {
            char current = trimmed.charAt(index);
            if (current < 0x20 || current == 0x7F) {
                throw new BizException(ErrorCode.BAD_REQUEST, "newName 包含控制字符");
            }
        }
        if (".".equals(trimmed) || "..".equals(trimmed)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "newName 不能是 . 或 ..");
        }
        if (StoragePaths.INTERNAL_META_DIR.equalsIgnoreCase(trimmed)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不允许使用内部元数据目录名");
        }
        return trimmed;
    }

    /** 目标目录若是其他已配置存储根（或包含它们），整体重命名会让别的根失效 → 40000。 */
    private void rejectRenamingAnotherRoot(StorageRoot currentRoot, Path directory) {
        for (StorageRoot other : rootRegistry.roots()) {
            if (other.id().equals(currentRoot.id())) {
                continue;
            }
            if (other.path().startsWith(directory)) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "不能重命名其他存储根所在目录: " + other.id());
            }
        }
    }

    /** 元数据同步失败时尽力把磁盘重命名改回去（回滚失败只记录日志，避免掩盖原始异常）。 */
    private void rollbackRename(Path renamed, Path original) {
        try {
            Files.move(renamed, original);
        } catch (IOException | RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(FileService.class)
                    .warn("重命名元数据同步失败后磁盘回滚失败: {} → {} ({})", renamed, original, e.toString());
        }
    }

    static String inferContentType(String fileName) {
        String guessed = URLConnection.guessContentTypeFromName(fileName);
        if (guessed == null || guessed.isBlank()) {
            return DEFAULT_CONTENT_TYPE;
        }
        return guessed;
    }

    static String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_DESCRIPTION_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "description 最长 200 字符");
        }
        return trimmed;
    }

    /**
     * 文件名校验/清洗：去掉路径分隔符与控制字符，最长 255 字符；清洗后为空视为无效。
     */
    static String sanitizeFileName(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.replace('\\', '/');
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        StringBuilder cleaned = new StringBuilder(name.length());
        for (int index = 0; index < name.length(); index++) {
            char current = name.charAt(index);
            if (current < 0x20 || current == 0x7F) {
                continue;
            }
            cleaned.append(current);
        }
        String result = cleaned.toString().trim();
        if (result.isEmpty()) {
            return null;
        }
        if (result.length() > MAX_NAME_LENGTH) {
            result = result.substring(0, MAX_NAME_LENGTH);
        }
        return result;
    }

    static FileMeta toMeta(FileRecord record, String rootId) {
        return new FileMeta(
                record.id(),
                record.name(),
                record.description(),
                record.size(),
                record.contentType(),
                record.sha256(),
                record.createdAt(),
                rootId);
    }

    /** 按 id 下载所需信息。 */
    public record FileDownload(FileRecord record, Path path) {
    }

    /** 按路径下载所需信息（name/contentType 由磁盘文件推断，sha256 现算）。 */
    public record PathDownload(String fileName, String contentType, long size,
                               String sha256, String relativePath, Path path) {
    }

    private record Located(StorageRoot root, FileRecord record) {
    }

    private record RecordWithRoot(StorageRoot root, FileRecord record) {
    }
}
