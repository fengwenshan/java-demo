package com.wenshan.upload.service;

import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.dto.SearchData;
import com.wenshan.upload.dto.SearchEntry;
import com.wenshan.upload.storage.StoragePaths;
import com.wenshan.upload.storage.StorageRoot;
import com.wenshan.upload.storage.StorageRootRegistry;
import org.springframework.stereotype.Service;

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
 * 契约 3.14：在存储根目录内**递归**搜索文件/目录（客户端「快速定位」）。
 *
 * <p>规则：文件名忽略大小写包含匹配；跳过内部元数据目录 {@code .upload-meta} 与所有符号链接
 * （不跟随、不泄露 root 外内容）；防御上限为最多扫描 {@value #MAX_SCAN_ENTRIES} 个条目、
 * 深度 {@value #MAX_DEPTH}，超出置 {@code truncated=true}。结果按「目录在前、文件在后，
 * 各组按名称忽略大小写升序」排序，最多返回 {@code limit} 条（默认 100，最大 500）。</p>
 */
@Service
public class SearchService {

    /** 最多扫描的条目数（root 下所有层级的文件+目录总数）。 */
    public static final int MAX_SCAN_ENTRIES = 50_000;
    /** 最大递归深度（root 的直接子项深度为 1）。 */
    public static final int MAX_DEPTH = 20;
    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 500;
    public static final int MAX_KEYWORD_LENGTH = 100;

    private static final String TYPE_DIRECTORY = "directory";
    private static final String TYPE_FILE = "file";

    private final StorageRootRegistry rootRegistry;

    public SearchService(StorageRootRegistry rootRegistry) {
        this.rootRegistry = rootRegistry;
    }

    /**
     * 只在指定根内递归搜索（root 缺省=默认根），不会命中其它根的文件。
     *
     * @param rootParam 根 id（null/空白=默认根；非法 id → 40000）
     * @param keyword   关键字（trim 后 1~100 字符，否则 40000）
     * @param limit     最多返回条数（1~500，缺省 100）
     */
    public SearchData search(String rootParam, String keyword, int limit) {
        StorageRoot root = rootRegistry.requireRoot(rootParam);
        String normalizedKeyword = normalizeKeyword(keyword);
        int effectiveLimit = normalizeLimit(limit);
        SearchContext context = new SearchContext(normalizedKeyword.toLowerCase(Locale.ROOT));
        walk(root.paths().root(), 1, "", context);
        List<SearchEntry> entries = new ArrayList<>(context.matched);
        entries.sort(Comparator.comparing(SearchEntry::type)
                .thenComparing(entry -> entry.name().toLowerCase(Locale.ROOT))
                .thenComparing(SearchEntry::name));
        // 先全量排序再截断，保证「limit 内结果」是排序后的前 N 条（与遍历顺序无关）
        boolean truncated = context.truncated || entries.size() > effectiveLimit;
        if (entries.size() > effectiveLimit) {
            entries = new ArrayList<>(entries.subList(0, effectiveLimit));
        }
        return new SearchData(normalizedKeyword, truncated, List.copyOf(entries));
    }

    private void walk(Path directory, int depth, String relativeDir, SearchContext context) {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            stream.forEach(children::add);
        } catch (IOException e) {
            // 无权限/竞态删除的目录直接跳过，不影响其它分支
            return;
        }
        for (Path child : children) {
            if (context.scanned >= MAX_SCAN_ENTRIES) {
                context.truncated = true;
                return;
            }
            // 不跟随、不列出符号链接，彻底规避 root 外逃逸
            if (Files.isSymbolicLink(child)) {
                continue;
            }
            String name = child.getFileName().toString();
            // 内部元数据目录在任何层级都隐藏（多根可嵌套，嵌套根的 .upload-meta 也不能暴露）
            if (StoragePaths.INTERNAL_META_DIR.equals(name)) {
                continue;
            }
            final boolean isDirectory;
            if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                isDirectory = true;
            } else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                isDirectory = false;
            } else {
                continue;
            }
            context.scanned++;
            String childRelative = relativeDir.isEmpty() ? name : relativeDir + "/" + name;
            if (name.toLowerCase(Locale.ROOT).contains(context.needle)) {
                SearchEntry entry = toEntry(child, name, childRelative, relativeDir, isDirectory);
                if (entry != null) {
                    context.matched.add(entry);
                }
            }
            if (!isDirectory) {
                continue;
            }
            if (depth >= MAX_DEPTH) {
                if (hasVisibleChild(child)) {
                    context.truncated = true;
                }
            } else {
                walk(child, depth + 1, childRelative, context);
            }
        }
    }

    private SearchEntry toEntry(Path child, String name, String childRelative,
                                String parentRelative, boolean isDirectory) {
        try {
            Long size = isDirectory ? null : Files.size(child);
            Instant modifiedAt = Files.getLastModifiedTime(child).toInstant().truncatedTo(ChronoUnit.SECONDS);
            return new SearchEntry(name, childRelative, parentRelative,
                    isDirectory ? TYPE_DIRECTORY : TYPE_FILE, size, modifiedAt);
        } catch (IOException e) {
            return null;
        }
    }

    private boolean hasVisibleChild(Path directory) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                if (Files.isSymbolicLink(child)) {
                    continue;
                }
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                        || Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            return false;
        }
        return false;
    }

    private static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少 keyword 参数");
        }
        String normalized = keyword.trim();
        if (normalized.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "keyword 不能为空");
        }
        if (normalized.length() > MAX_KEYWORD_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "keyword 最长 100 字符");
        }
        return normalized;
    }

    private static int normalizeLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BizException(ErrorCode.BAD_REQUEST, "limit 必须为 1~500");
        }
        return limit;
    }

    /** 递归搜索过程的状态（truncated 表示达到扫描/深度上限，结果可能不完整）。 */
    private static final class SearchContext {
        private final String needle;
        private final List<SearchEntry> matched = new ArrayList<>();
        private int scanned;
        private boolean truncated;

        private SearchContext(String needle) {
            this.needle = needle;
        }
    }
}
