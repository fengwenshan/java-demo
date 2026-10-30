package com.wenshan.upload.service;

import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.dto.CopyData;
import com.wenshan.upload.dto.MoveData;
import com.wenshan.upload.storage.StorageRoot;
import com.wenshan.upload.storage.StorageRootRegistry;
import com.wenshan.upload.store.FileRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 契约 3.22/3.23：文件/目录「拷贝」（原地生成 {@code _副本}）与「移动」（可跨存储根）。
 *
 * <p>路径一律经 {@link com.wenshan.upload.storage.StoragePaths#resolveStoredPath(String)} 严格校验：
 * 拒绝绝对路径、{@code ..}、符号链接、内部元数据目录与越界；目标路径同样重新校验，绝不覆盖已有文件/目录。</p>
 *
 * <p>拷贝不写 files.json（与 zip 解压一致）；移动会同步 files.json：同根更新 storedPath/name，
 * 跨根把记录搬到目标根并更新 rootId。失败回滚：拷贝删除本次目标；移动尽量把磁盘/元数据改回原状。</p>
 */
@Service
public class FileTransferService {

    private static final Logger log = LoggerFactory.getLogger(FileTransferService.class);
    private static final int MAX_COPY_SUFFIX_ATTEMPTS = 1000;
    private static final String COPY_SUFFIX = "_副本";
    private static final String META_DIR = ".upload-meta";

    private final StorageRootRegistry rootRegistry;
    private final FileStorageService storageService;

    public FileTransferService(StorageRootRegistry rootRegistry, FileStorageService storageService) {
        this.rootRegistry = rootRegistry;
        this.storageService = storageService;
    }

    // ------------------------------------------------------------------
    // 拷贝
    // ------------------------------------------------------------------

    /**
     * 契约 3.22：在源同父目录生成副本。文件 {@code a.txt → a_副本.txt}、目录 {@code abc → abc_副本}；
     * 重名依次 {@code _副本 (1)}…{@code _副本 (999)}，超过 1000 个候选返回 50000。
     * 目录递归复制且不跟随符号链接；失败删除本次目标；不写 files.json。
     */
    public CopyData copy(String appKey, String rootParam, String path) {
        StorageRoot root = rootRegistry.requireRoot(rootParam);
        if (path == null || path.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少 path 参数");
        }
        Path source = storageService.resolve(root.paths(), path);
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        boolean directory = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS);
        if (!directory && !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只能拷贝普通文件或目录");
        }
        if (directory) {
            rejectContainsOtherRoots(root, source);
        }
        String sourceName = source.getFileName().toString();
        Path parent = source.getParent();
        if (parent == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "源路径没有父目录");
        }
        String parentRelative = storageService.relativize(root.paths(), parent);
        Path target = null;
        String targetRelative = null;
        String targetName = null;
        for (int attempt = 0; attempt < MAX_COPY_SUFFIX_ATTEMPTS; attempt++) {
            String candidate = copyName(sourceName, directory, attempt);
            Path candidatePath = parent.resolve(candidate);
            String candidateRelative = parentRelative.isEmpty() ? candidate : parentRelative + "/" + candidate;
            // 目标路径同样走严格校验（父目录无 symlink / 非内部目录 / 不越界）
            root.paths().resolveStoredPath(candidateRelative);
            if (!Files.exists(candidatePath, LinkOption.NOFOLLOW_LINKS)) {
                target = candidatePath;
                targetRelative = candidateRelative;
                targetName = candidate;
                break;
            }
        }
        if (target == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "无法生成副本名称：同目录已存在 1000 个同名副本");
        }
        if (directory) {
            copyDirectory(source, target);
        } else {
            copyFile(source, target);
        }
        log.info("拷贝成功: root={}, {} -> {}（type={}）", root.id(),
                storageService.relativize(root.paths(), source), targetRelative,
                directory ? "directory" : "file");
        return new CopyData(root.id(), targetRelative, directory ? "directory" : "file", targetName);
    }

    /** 副本命名：文件保留最后一段扩展名（{@code .env}/{@code README} 视为无扩展名），目录整体追加。 */
    static String copyName(String sourceName, boolean directory, int attempt) {
        String suffix = attempt == 0 ? COPY_SUFFIX : COPY_SUFFIX + " (" + attempt + ")";
        if (directory) {
            return sourceName + suffix;
        }
        int dot = sourceName.lastIndexOf('.');
        if (dot <= 0 || dot == sourceName.length() - 1) {
            return sourceName + suffix;
        }
        return sourceName.substring(0, dot) + suffix + sourceName.substring(dot);
    }

    private void copyFile(Path source, Path target) {
        try {
            Files.copy(source, target);
        } catch (FileAlreadyExistsException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "目标名称已存在");
        } catch (IOException e) {
            deleteQuietly(target);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "拷贝失败: " + e.getMessage());
        }
    }

    /** 递归复制目录（不跟随 symlink）；任何异常都删除本次目标目录。 */
    private void copyDirectory(Path source, Path target) {
        try {
            Files.createDirectory(target);
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (dir.equals(source)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path relative = source.relativize(dir);
                    rejectInternalMeta(relative);
                    if (Files.isSymbolicLink(dir)) {
                        throw new BizException(ErrorCode.BAD_REQUEST, "源目录包含符号链接，无法拷贝: " + relative);
                    }
                    Files.createDirectories(target.resolve(relative));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path relative = source.relativize(file);
                    rejectInternalMeta(relative);
                    if (Files.isSymbolicLink(file) || !attrs.isRegularFile()) {
                        throw new BizException(ErrorCode.BAD_REQUEST, "源目录包含符号链接或特殊文件，无法拷贝: " + relative);
                    }
                    Files.copy(file, target.resolve(relative));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (BizException e) {
            deleteQuietlyRecursively(target);
            throw e;
        } catch (IOException e) {
            deleteQuietlyRecursively(target);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "目录拷贝失败: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 移动
    // ------------------------------------------------------------------

    /**
     * 契约 3.23：把文件/目录移动到指定根的目标目录（可跨根）。禁止移动到自身/子目录、
     * 禁止覆盖同名目标；同根用 {@code Files.move}，跨根复制+删除；同步 files.json。
     */
    public MoveData move(String appKey, String rootParam, String path, String targetRootParam, String targetDir) {
        StorageRoot sourceRoot = rootRegistry.requireRoot(rootParam);
        StorageRoot targetRoot = (targetRootParam == null || targetRootParam.isBlank())
                ? sourceRoot
                : rootRegistry.requireRoot(targetRootParam);
        if (path == null || path.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少 path 参数");
        }
        Path source = storageService.resolve(sourceRoot.paths(), path);
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        boolean directory = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS);
        if (!directory && !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只能移动普通文件或目录");
        }
        if (directory) {
            rejectContainsOtherRoots(sourceRoot, source);
        }
        String sourceName = source.getFileName().toString();
        Path targetDirPath = targetRoot.paths().resolveRootDir(targetDir);
        if (!Files.exists(targetDirPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "目标目录不存在");
        }
        if (!Files.isDirectory(targetDirPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "目标不是目录");
        }
        // 目录不能移动到自身/自身子目录（同时覆盖嵌套的其他存储根位于源内的情况）
        if (directory && (targetDirPath.equals(source) || targetDirPath.startsWith(source))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能移动到自身或子目录");
        }
        Path target = targetDirPath.resolve(sourceName).normalize();
        if (!target.startsWith(targetDirPath)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "目标路径非法");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "目标位置已存在同名文件或目录");
        }
        String oldRelative = storageService.relativize(sourceRoot.paths(), source);
        String newRelative = storageService.relativize(targetRoot.paths(), target);
        // 目标路径严格校验（父目录无 symlink / 非内部目录 / 不越界）
        targetRoot.paths().resolveStoredPath(newRelative);
        if (sourceRoot.id().equals(targetRoot.id())) {
            moveWithinRoot(sourceRoot, source, target, oldRelative, newRelative, directory, sourceName);
        } else {
            moveAcrossRoots(appKey, sourceRoot, targetRoot, source, target,
                    oldRelative, newRelative, directory);
        }
        log.info("移动成功: {}:{} -> {}:{}（type={}）", sourceRoot.id(), oldRelative,
                targetRoot.id(), newRelative, directory ? "directory" : "file");
        return new MoveData(targetRoot.id(), newRelative, directory ? "directory" : "file", sourceName);
    }

    private void moveWithinRoot(StorageRoot root, Path source, Path target, String oldRelative,
                                String newRelative, boolean directory, String sourceName) {
        try {
            Files.move(source, target);
        } catch (FileAlreadyExistsException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "目标位置已存在同名文件或目录");
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "移动失败: " + e.getMessage());
        }
        try {
            if (directory) {
                root.metaStore().updateDirectoryPrefix(oldRelative, newRelative);
            } else {
                root.metaStore().updateFilePath(oldRelative, newRelative, sourceName);
            }
        } catch (RuntimeException e) {
            rollbackWithinRoot(target, source);
            throw e;
        }
    }

    private void rollbackWithinRoot(Path target, Path source) {
        try {
            Files.move(target, source);
        } catch (IOException | RuntimeException e) {
            log.warn("移动元数据同步失败后磁盘回滚失败: {} -> {} ({})", target, source, e.toString());
        }
    }

    /** 跨根移动：复制（源保持不动）→ 写目标元数据 → 删源磁盘 → 删源元数据；任一步失败尽量回滚。 */
    private void moveAcrossRoots(String appKey, StorageRoot sourceRoot, StorageRoot targetRoot,
                                 Path source, Path target, String oldRelative, String newRelative,
                                 boolean directory) {
        if (directory) {
            copyDirectory(source, target);
        } else {
            copyFile(source, target);
        }
        List<FileRecord> movedRecords = remapRecords(
                sourceRoot.metaStore().filesAtOrUnder(oldRelative, directory),
                oldRelative, newRelative, directory, targetRoot.id());
        List<String> movedIds = movedRecords.stream().map(FileRecord::id).toList();
        try {
            targetRoot.metaStore().saveFiles(movedRecords);
        } catch (RuntimeException e) {
            deleteQuietlyRecursively(target);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "移动失败：目标元数据写入失败");
        }
        try {
            deleteRecursively(source);
        } catch (IOException e) {
            rollbackCrossRoot(targetRoot, target, movedIds, source);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "移动失败：源文件删除失败");
        }
        try {
            sourceRoot.metaStore().removeByIds(movedIds);
        } catch (RuntimeException e) {
            rollbackCrossRoot(targetRoot, target, movedIds, source);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "移动失败：源元数据清理失败");
        }
        log.debug("跨根移动完成: appKey={}, records={}", appKey, movedIds.size());
    }

    /** 跨根移动失败回滚：删目标元数据、尽量把内容复制回源、删目标磁盘。均为 best effort。 */
    private void rollbackCrossRoot(StorageRoot targetRoot, Path target, List<String> movedIds, Path source) {
        try {
            targetRoot.metaStore().removeByIds(movedIds);
        } catch (RuntimeException e) {
            log.warn("跨根移动回滚：清理目标元数据失败 ({})", e.toString());
        }
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                    copyDirectoryPlain(target, source);
                } else {
                    Files.copy(target, source);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("跨根移动回滚：恢复源文件失败 ({})", e.toString());
        }
        deleteQuietlyRecursively(target);
    }

    /** 回滚专用：把目录复制回源（不重复做业务校验/回滚包装）。 */
    private void copyDirectoryPlain(Path source, Path target) throws IOException {
        Files.createDirectory(target);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(source)) {
                    Files.createDirectories(target.resolve(source.relativize(dir)));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private List<FileRecord> remapRecords(List<FileRecord> records, String oldRelative,
                                          String newRelative, boolean directory, String targetRootId) {
        List<FileRecord> moved = new ArrayList<>(records.size());
        for (FileRecord record : records) {
            String storedPath = directory
                    ? newRelative + record.storedPath().substring(oldRelative.length())
                    : newRelative;
            moved.add(new FileRecord(record.id(), record.appKey(), record.deviceId(), record.name(),
                    record.description(), record.size(), record.contentType(), record.sha256(),
                    storedPath, record.createdAt(), targetRootId));
        }
        return moved;
    }

    // ------------------------------------------------------------------
    // 安全辅助
    // ------------------------------------------------------------------

    /** 目录内若包含其他已配置存储根，整体拷贝/移动会让别的根失效 → 40000。 */
    private void rejectContainsOtherRoots(StorageRoot currentRoot, Path directory) {
        for (StorageRoot other : rootRegistry.roots()) {
            if (other.id().equals(currentRoot.id())) {
                continue;
            }
            if (other.path().startsWith(directory)) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "目录包含其他存储根，无法操作: " + other.id());
            }
        }
    }

    private static void rejectInternalMeta(Path relative) {
        for (Path part : relative) {
            if (META_DIR.equals(part.toString().toLowerCase(Locale.ROOT))) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "目录包含内部元数据目录，无法拷贝: " + relative.toString().replace('\\', '/'));
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path);
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException e) {
                    log.warn("回滚删除失败: {}", item);
                }
            });
        }
    }

    private static void deleteQuietlyRecursively(Path path) {
        try {
            deleteRecursively(path);
        } catch (IOException e) {
            log.warn("回滚删除目录失败: {} ({})", path, e.toString());
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            if (path != null) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.warn("清理失败: {}", path);
        }
    }
}
