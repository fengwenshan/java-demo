package com.wenshan.upload.service;

import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.dto.DirEntry;
import com.wenshan.upload.dto.DirListData;
import com.wenshan.upload.storage.StoragePaths;
import com.wenshan.upload.storage.StorageRoot;
import com.wenshan.upload.storage.StorageRootRegistry;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 只读目录浏览（FTP 式）：严格限制在存储根目录内，不返回文件内容。
 *
 * <p>排序：目录在前、文件在后，各自按名称忽略大小写升序；最多返回 {@value #MAX_ENTRIES} 条，
 * 超出时截断并置 {@code truncated=true}。符号链接条目直接跳过（不跟随、不泄露目标）；
 * 内部元数据目录 {@code .upload-meta} 在根目录列表中隐藏（也无法通过 path 进入）。</p>
 */
@Service
public class DirectoryService {

    /** 单次最多返回的条目数（可配置前先固定常量）。 */
    public static final int MAX_ENTRIES = 2000;

    private static final String TYPE_DIRECTORY = "directory";
    private static final String TYPE_FILE = "file";

    private final StorageRootRegistry rootRegistry;

    public DirectoryService(StorageRootRegistry rootRegistry) {
        this.rootRegistry = rootRegistry;
    }

    /**
     * 列出指定根内相对 root 的目录内容（空/null 表示 root）。
     *
     * @param rootParam 根 id（null/空白=默认根）
     * @throws BizException 40000 非法 root/路径/符号链接/越界；40400 目录不存在或不是目录
     */
    public DirListData listDirectory(String rootParam, String requestedPath) {
        StorageRoot root = rootRegistry.requireRoot(rootParam);
        StoragePaths storagePaths = root.paths();
        Path directory = storagePaths.resolveRootDir(requestedPath);
        if (!Files.isDirectory(directory)) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        String relativePath = toRelativePath(storagePaths, directory);
        String parentPath = relativePath.isEmpty() ? null : parentOf(relativePath);

        List<DirEntry> entries = new ArrayList<>();
        boolean truncated = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                // 内部元数据目录在任何层级都隐藏（多根可嵌套，嵌套根的 .upload-meta 也不能暴露）
                if (StoragePaths.INTERNAL_META_DIR.equals(child.getFileName().toString())) {
                    continue;
                }
                // 跳过符号链接（可能是 root 外逃逸入口，也可能是循环链接）
                if (Files.isSymbolicLink(child)) {
                    continue;
                }
                final boolean isDirectory;
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    isDirectory = true;
                } else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                    isDirectory = false;
                } else {
                    // socket / fifo / device 等特殊文件不对外列出
                    continue;
                }
                // 防御：真实路径必须仍在 root 内（bind mount、竞态替换等）
                try {
                    if (!child.toRealPath().startsWith(storagePaths.rootReal())) {
                        continue;
                    }
                } catch (IOException e) {
                    continue;
                }
                String name = child.getFileName().toString();
                String childPath = relativePath.isEmpty() ? name : relativePath + "/" + name;
                Long size = null;
                if (!isDirectory) {
                    try {
                        size = Files.size(child);
                    } catch (IOException e) {
                        continue;
                    }
                }
                Instant modifiedAt = Files.getLastModifiedTime(child).toInstant().truncatedTo(ChronoUnit.SECONDS);
                entries.add(new DirEntry(name, childPath, isDirectory ? TYPE_DIRECTORY : TYPE_FILE, size, modifiedAt));
            }
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "目录读取失败");
        }

        entries.sort(Comparator.comparing(DirEntry::type)
                .thenComparing(entry -> entry.name().toLowerCase(Locale.ROOT))
                .thenComparing(DirEntry::name));
        if (entries.size() > MAX_ENTRIES) {
            entries = new ArrayList<>(entries.subList(0, MAX_ENTRIES));
            truncated = true;
        }
        return new DirListData(relativePath, parentPath, truncated, List.copyOf(entries));
    }

    /** 绝对路径 → 相对 root 的路径（统一 "/" 分隔，root 本身为空字符串）。 */
    private static String toRelativePath(StoragePaths paths, Path directory) {
        Path relative = paths.root().relativize(directory);
        String text = relative.toString().replace(File.separatorChar, '/');
        return text.equals(".") ? "" : text;
    }

    /** 去掉最后一段；直接子项（"files"）的父目录是 root，即空字符串。 */
    private static String parentOf(String relativePath) {
        int index = relativePath.lastIndexOf('/');
        return index < 0 ? "" : relativePath.substring(0, index);
    }
}
