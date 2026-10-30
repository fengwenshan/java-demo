package com.wenshan.upload.service;

import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.config.AppProperties;
import com.wenshan.upload.dto.ExtractData;
import com.wenshan.upload.storage.StorageRoot;
import com.wenshan.upload.storage.StorageRootRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 契约 3.19 zip 安全解压：把 root 内的 .zip 解压到同级同名目录（重名追加 {@code (1)}/{@code (2)}…）。
 *
 * <p>顶层包裹自动剥层：若所有 entry 的第一段路径唯一且该名字确实是目录（存在 {@code name/} 目录 entry，
 * 或全部 entry 都在 {@code name/} 下），则剥掉这层包裹，内容直接解到同级同名目录
 * （{@code 压缩包.zip} 内 {@code pkg/a.txt} → {@code 压缩包/a.txt}）；空 zip、单根文件、多顶层段保持原行为。</p>
 *
 * <p>安全边界：entry 名不得绝对路径/含 {@code ..}/反斜杠/NUL/.upload-meta；拒绝符号链接与加密 zip；
 * 限制 entry 数、解压总字节、单 entry 字节（可配置）防 zip bomb；任何失败都尽力删除本次创建的目标目录，
 * 绝不覆盖已有文件/目录。解压出的文件不写 files.json（不是上传产生的文件）。</p>
 */
@Service
public class ZipExtractService {

    private static final Logger log = LoggerFactory.getLogger(ZipExtractService.class);
    private static final int MAX_TARGET_SUFFIX_ATTEMPTS = 1000;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String META_DIR = ".upload-meta";

    private final StorageRootRegistry rootRegistry;
    private final FileStorageService storageService;
    private final AppProperties properties;

    public ZipExtractService(StorageRootRegistry rootRegistry,
                             FileStorageService storageService,
                             AppProperties properties) {
        this.rootRegistry = rootRegistry;
        this.storageService = storageService;
        this.properties = properties;
    }

    public ExtractData extract(String appKey, String rootParam, String path) {
        StorageRoot root = rootRegistry.requireRoot(rootParam);
        if (path == null || path.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少 path 参数");
        }
        Path zip = storageService.resolve(root.paths(), path);
        if (!Files.exists(zip, LinkOption.NOFOLLOW_LINKS)) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        if (!Files.isRegularFile(zip, LinkOption.NOFOLLOW_LINKS)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只能解压普通 zip 文件");
        }
        String fileName = zip.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只支持 .zip 文件");
        }
        String baseName = fileName.substring(0, fileName.length() - ".zip".length());
        if (baseName.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "zip 文件名无效");
        }
        // 先做不可回滚的 zip 级预检（加密/symlink）再创建目标目录，避免留下空目录
        ZipSecurity.checkArchive(zip);
        Path targetDir = createTargetDirectory(root, zip, baseName);
        try {
            Counts counts = extractEntries(root, zip, targetDir);
            String relativeTarget = storageService.relativize(root.paths(), targetDir);
            return new ExtractData(relativeTarget, counts.entries(), counts.bytes());
        } catch (BizException e) {
            deleteRecursively(targetDir);
            throw e;
        } catch (IOException e) {
            deleteRecursively(targetDir);
            throw new BizException(ErrorCode.BAD_REQUEST, "zip 解压失败: " + e.getMessage());
        }
    }

    /**
     * 同级目录名=去 .zip 的文件名；已存在则依次尝试 {@code name (1)}…{@code name (999)}；
     * 用 createDirectory 原子占位，绝不覆盖已有文件/目录。
     */
    private Path createTargetDirectory(StorageRoot root, Path zip, String baseName) {
        Path parent = zip.getParent();
        if (parent == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "zip 文件所在目录无效");
        }
        for (int suffix = 0; suffix < MAX_TARGET_SUFFIX_ATTEMPTS; suffix++) {
            String candidateName = suffix == 0 ? baseName : baseName + " (" + suffix + ")";
            Path candidate = parent.resolve(candidateName);
            // 目标是新建目录：仍走严格路径校验（root 内、无 symlink、非 .upload-meta）
            storageService.relativize(root.paths(), candidate);
            try {
                Files.createDirectory(candidate);
                return candidate;
            } catch (FileAlreadyExistsException e) {
                // 已存在：试下一个后缀
            } catch (IOException e) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "创建解压目录失败");
            }
        }
        throw new BizException(ErrorCode.INTERNAL_ERROR,
                "同名解压目录过多（已尝试 " + MAX_TARGET_SUFFIX_ATTEMPTS + " 次）");
    }

    private Counts extractEntries(StorageRoot root, Path zip, Path targetDir) throws IOException {
        int entries = 0;
        long bytes = 0;
        int entryCount = 0;
        Set<String> seen = new HashSet<>();
        // 先用 central directory 探测单层包裹目录（ZipInputStream 无法回看/统计）
        String wrapper = detectWrapperDirectory(zip);
        if (wrapper != null) {
            log.info("zip 检测到单层包裹目录，自动剥层: {}", wrapper);
        }
        try (InputStream raw = Files.newInputStream(zip);
             ZipInputStream zis = new ZipInputStream(new BufferedInputStream(raw, BUFFER_SIZE))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > properties.getStorage().getMaxExtractEntries()) {
                    throw new BizException(ErrorCode.BAD_REQUEST,
                            "zip 条目数超过上限（" + properties.getStorage().getMaxExtractEntries() + "）");
                }
                String name = entry.getName();
                String normalized = validateEntryName(name);
                String relative = stripWrapper(normalized, wrapper);
                if (relative == null) {
                    // wrapper/ 目录 entry 映射到 targetDir 本身；剥离后为空/`.` 的目录 entry 跳过
                    continue;
                }
                // 剥离前缀后再做一次名称校验/判重（判重使用剥离后的路径）
                relative = validateEntryName(relative);
                if (!seen.add(relative)) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "zip 存在重复条目: " + name);
                }
                Path target = targetDir.resolve(relative).normalize();
                if (!target.startsWith(targetDir)) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "zip 条目路径非法: " + name);
                }
                // 目标文件/目录必须仍在该 root 内，且不经过 symlink/.upload-meta
                storageService.relativize(root.paths(), target);
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                long size = copyEntry(zis, target, bytes);
                entries++;
                bytes += size;
            }
        } catch (ZipException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "zip 文件损坏或加密，拒绝解压");
        }
        return new Counts(entries, bytes);
    }

    /**
     * 流式写出单个 entry，同时校验单 entry 上限与解压总字节上限；目标用 CREATE_NEW 保证不覆盖。
     *
     * @return 本 entry 实际解压字节数
     */
    private long copyEntry(InputStream zis, Path target, long bytesSoFar) throws IOException {
        long entryBytes = 0;
        long maxEntryBytes = properties.getStorage().getMaxExtractEntryBytes();
        long maxTotalBytes = properties.getStorage().getMaxExtractTotalBytes();
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = zis.read(buffer)) != -1) {
                entryBytes += read;
                if (entryBytes > maxEntryBytes) {
                    throw new BizException(ErrorCode.BAD_REQUEST,
                            "zip 单个文件超过解压上限（" + maxEntryBytes + " 字节）");
                }
                if (bytesSoFar + entryBytes > maxTotalBytes) {
                    throw new BizException(ErrorCode.BAD_REQUEST,
                            "zip 解压总大小超过上限（" + maxTotalBytes + " 字节）");
                }
                out.write(buffer, 0, read);
            }
        } catch (FileAlreadyExistsException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "zip 条目与已有文件冲突");
        }
        return entryBytes;
    }

    /**
     * 探测「唯一一层顶层目录包裹」：扫描 central directory 统计第一段路径集合。
     * 仅当集合唯一、该名字是安全单段且确实是目录（存在 {@code name/} entry 或全部 entry 都在 {@code name/} 下）、
     * 且不存在与包裹同名的根文件时返回包裹名；空 zip / 单根文件 / 多顶层段返回 null。
     */
    private static String detectWrapperDirectory(Path zip) throws IOException {
        Set<String> topLevel = new LinkedHashSet<>();
        List<String> names = new ArrayList<>();
        try (ZipFile zipFile = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            var entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name == null || name.isBlank()) {
                    continue;
                }
                names.add(name);
                topLevel.add(firstSegment(name));
            }
        }
        if (topLevel.size() != 1) {
            return null;
        }
        String candidate = topLevel.iterator().next();
        if (candidate.isEmpty()) {
            return null;
        }
        try {
            // 包裹名必须是安全单段（复用 entry 名校验：拒绝 ..、.upload-meta、反斜杠/NUL 等）
            if (!validateEntryName(candidate + "/").equals(candidate)) {
                return null;
            }
        } catch (BizException e) {
            return null;
        }
        boolean rootFile = names.stream().anyMatch(name -> name.equals(candidate));
        boolean directoryEntry = names.stream().anyMatch(name -> name.equals(candidate + "/"));
        boolean allNested = names.stream().allMatch(name -> name.startsWith(candidate + "/"));
        if (rootFile) {
            return null;
        }
        return directoryEntry || allNested ? candidate : null;
    }

    /** 路径第一段（目录 entry 的末尾 '/' 不影响；绝对路径返回空串 → 不剥）。 */
    private static String firstSegment(String name) {
        if (name.startsWith("/")) {
            return "";
        }
        int slash = name.indexOf('/');
        return slash < 0 ? name : name.substring(0, slash);
    }

    /**
     * 去掉包裹前缀；{@code wrapper/} 与剥离后为空的目录 entry 返回 null（映射 targetDir 本身/跳过）。
     * 无包裹时原样返回。防御性：不以前缀开头的 entry 保持原路径（由后续校验兜底）。
     */
    private static String stripWrapper(String normalized, String wrapper) {
        if (wrapper == null) {
            return normalized;
        }
        if (normalized.equals(wrapper)) {
            return null;
        }
        String prefix = wrapper + "/";
        if (!normalized.startsWith(prefix)) {
            return normalized;
        }
        String stripped = normalized.substring(prefix.length());
        if (stripped.isEmpty() || ".".equals(stripped)) {
            return null;
        }
        return stripped;
    }

    /**
     * entry 名校验：非空、非绝对路径、无反斜杠/NUL/盘符、无 {@code ..}/{@code .} 段、
     * 无中间空段、任意层级不得是 {@code .upload-meta}。返回去掉末尾 '/' 的相对路径。
     */
    private static String validateEntryName(String name) {
        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "zip 条目名称为空");
        }
        if (name.indexOf('\0') >= 0 || name.indexOf('\\') >= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "zip 条目名称非法: " + name);
        }
        if (name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "zip 条目使用绝对路径，拒绝解压: " + name);
        }
        try {
            if (Path.of(name).isAbsolute()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "zip 条目使用绝对路径，拒绝解压: " + name);
            }
        } catch (InvalidPathException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "zip 条目名称非法: " + name);
        }
        boolean directoryEntry = name.endsWith("/");
        String trimmed = directoryEntry ? name.substring(0, name.length() - 1) : name;
        String[] segments = trimmed.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "zip 条目路径包含空段: " + name);
            }
            if (".".equals(segment) || "..".equals(segment)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "zip 条目包含非法路径段: " + name);
            }
            if (META_DIR.equalsIgnoreCase(segment)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "zip 条目不得包含内部元数据目录: " + name);
            }
        }
        return trimmed;
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("回滚删除解压目录失败: {}", path);
                }
            });
        } catch (IOException e) {
            log.warn("回滚遍历解压目录失败: {}", root);
        }
    }

    private record Counts(int entries, long bytes) {
    }
}
