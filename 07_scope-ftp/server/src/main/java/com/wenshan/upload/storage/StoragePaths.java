package com.wenshan.upload.storage;

import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 单个存储根目录与严格的路径约束工具（FTP 式布局）。一个实例只服务一个根：
 * 由 {@link StorageRootRegistry} 按 {@code app.storage.roots[]} 为每个根创建一个实例，
 * 因此所有路径校验天然只可能命中「本次请求指定的那个根」。
 *
 * <p>根目录下只放用户的真实文件/目录。内部元数据走隐藏目录 {@code <root>/.upload-meta/}；
 * multipart 临时文件走系统临时目录 {@code ${java.io.tmpdir}/scop-ftp-tmp}。启动时解析为绝对路径、
 * 创建目录并做可写探测，失败立即快速失败。</p>
 *
 * <p>{@link #resolveStoredPath(String)} 是「按路径访问 root 内文件」的唯一入口：只接受相对 root 的路径，
 * 拒绝绝对路径、{@code .}/{@code ..} 段、空段、NUL、任何层级的内部元数据目录 {@code .upload-meta}
 * 与任何符号链接组件，并对已存在的部分用 {@code toRealPath()} 再校验一次，确保不会读写到根目录之外。</p>
 */
public class StoragePaths {

    private static final Logger log = LoggerFactory.getLogger(StoragePaths.class);

    /** 内部元数据目录名（隐藏，不出现在目录浏览中，也不允许作为下载/删除/上传目标）。 */
    public static final String INTERNAL_META_DIR = ".upload-meta";
    /** multipart 临时目录名（位于系统临时目录下，不再放在 root 内）。 */
    public static final String TEMP_DIR_NAME = "scop-ftp-tmp";

    private final Path root;
    private final Path metaDir;
    private final Path tmpDir;
    private final Path rootReal;

    StoragePaths(String configuredRootDir) {
        if (configuredRootDir == null || configuredRootDir.isBlank()) {
            throw new IllegalStateException("存储根 path 不能为空");
        }
        String configured = configuredRootDir.trim();
        this.root = Path.of(configured).toAbsolutePath().normalize();
        this.metaDir = root.resolve(INTERNAL_META_DIR);
        this.tmpDir = Path.of(System.getProperty("java.io.tmpdir"), TEMP_DIR_NAME)
                .toAbsolutePath().normalize();

        Path computedRootReal;
        try {
            Files.createDirectories(root);
            Files.createDirectories(metaDir);
            Files.createDirectories(tmpDir);
            computedRootReal = root.toRealPath();
            requireWithin(computedRootReal, metaDir.toRealPath(), INTERNAL_META_DIR);
            verifyWritable(root);
            verifyWritable(metaDir);
            verifyWritable(tmpDir);
        } catch (IOException e) {
            log.error("存储根目录初始化失败: root={} ({})", root, e.toString());
            throw new IllegalStateException(
                    "存储根目录不可用或不可写: " + root
                            + "，请检查 app.storage.roots[] 配置与目录权限", e);
        }
        this.rootReal = computedRootReal;
        log.info("存储根目录已生效: {}（用户文件/目录根；内部元数据={}；multipart 临时目录={}）",
                root, metaDir, tmpDir);
    }

    /** 配置的根目录（绝对路径，未解析符号链接）。 */
    public Path root() {
        return root;
    }

    /** 内部元数据目录 {@code <root>/.upload-meta}。 */
    public Path metaDir() {
        return metaDir;
    }

    /** multipart 临时目录 {@code ${java.io.tmpdir}/scop-ftp-tmp}。 */
    public Path tmpDir() {
        return tmpDir;
    }

    /** 根目录的真实路径（已解析符号链接），用于真实路径越界校验。 */
    public Path rootReal() {
        return rootReal;
    }

    /**
     * 目录浏览用：把相对 root 的目录路径解析为绝对路径；{@code null}/空串/纯空白表示 root 本身。
     *
     * <p>规则与 {@link #resolveStoredPath(String)} 一致，且不允许进入内部目录 {@code .upload-meta}。</p>
     *
     * @throws BizException 40000：非法路径/符号链接/越界/内部目录
     */
    public Path resolveRootDir(String relativeDir) {
        if (relativeDir == null || relativeDir.isBlank()) {
            return root;
        }
        Path relative = parseRelative(relativeDir);
        rejectInternalMeta(relative);
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            throw invalid("目录越出存储根目录");
        }
        assertNoSymlinkComponents(relative);
        if (Files.exists(candidate)) {
            try {
                if (!candidate.toRealPath().startsWith(rootReal)) {
                    throw invalid("目录真实路径越出存储根目录");
                }
            } catch (IOException e) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "目录校验失败");
            }
        }
        return candidate;
    }

    /**
     * 把相对 root 的文件路径解析为绝对路径（下载 / 删除 / 元数据 storedPath 统一入口）。
     *
     * @throws BizException 40000：路径为空/绝对路径/含 {@code ..}/含符号链接/越出根目录/指向内部目录
     */
    public Path resolveStoredPath(String storedPath) {
        Path relative = parseRelative(storedPath);
        rejectInternalMeta(relative);
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            throw invalid("路径越出存储根目录");
        }
        assertNoSymlinkComponents(relative);
        assertExistingPathWithinRoot(candidate);
        return candidate;
    }

    /** 校验元数据文件（files.json / devices.json）路径确实位于 .upload-meta 目录内。 */
    public Path resolveMetaFile(String fileName) {
        Path relative = parseRelative(fileName);
        Path candidate = metaDir.resolve(relative).normalize();
        if (!candidate.startsWith(metaDir) || !metaDir.equals(candidate.getParent())) {
            throw invalid("元数据路径越出元数据目录");
        }
        return candidate;
    }

    // ------------------------------------------------------------------
    // 内部校验
    // ------------------------------------------------------------------

    private static Path parseRelative(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            throw invalid("存储路径为空");
        }
        if (!storedPath.trim().equals(storedPath)) {
            throw invalid("存储路径首尾不能包含空白");
        }
        if (storedPath.indexOf('\0') >= 0) {
            throw invalid("存储路径包含非法字符");
        }
        Path relative;
        try {
            relative = Path.of(storedPath);
        } catch (InvalidPathException e) {
            throw invalid("存储路径格式非法");
        }
        if (relative.isAbsolute()) {
            throw invalid("不允许绝对路径");
        }
        if (relative.getNameCount() == 0) {
            throw invalid("存储路径为空");
        }
        for (Path part : relative) {
            String name = part.toString();
            if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
                throw invalid("不允许 . / .. 路径段");
            }
        }
        return relative;
    }

    private static void rejectInternalMeta(Path relative) {
        for (Path part : relative) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (INTERNAL_META_DIR.equals(name)) {
                throw invalid("不允许访问内部元数据目录");
            }
        }
    }

    /** 路径上任何一级组件是符号链接都拒绝（最严格策略，彻底规避 symlink 逃逸）。 */
    private void assertNoSymlinkComponents(Path relative) {
        Path cursor = root;
        for (Path part : relative) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) {
                throw invalid("存储路径包含符号链接");
            }
        }
    }

    /** 对路径中已存在的最近一级祖先取真实路径，校验仍在 root 真实路径内。 */
    private void assertExistingPathWithinRoot(Path candidate) {
        Path existing = candidate;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null || !existing.startsWith(root)) {
            throw invalid("存储路径越界");
        }
        try {
            if (!existing.toRealPath().startsWith(rootReal)) {
                throw invalid("存储路径真实路径越出存储根目录");
            }
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "存储路径校验失败");
        }
    }

    private static void requireWithin(Path rootReal, Path childReal, String name) throws IOException {
        if (!childReal.startsWith(rootReal)) {
            throw new IOException("目录 " + name + " 的真实路径不在根目录内: " + childReal);
        }
    }

    /** 实际创建并删除一个探测文件来确认目录可写（比 Files.isWritable 更可靠）。 */
    private static void verifyWritable(Path directory) throws IOException {
        Path probe = Files.createTempFile(directory, ".write-check-", ".tmp");
        try {
            Files.deleteIfExists(probe);
        } catch (IOException e) {
            log.warn("删除写权限探测文件失败: {}", probe);
        }
    }

    private static BizException invalid(String detail) {
        return new BizException(ErrorCode.BAD_REQUEST, "非法存储路径：" + detail);
    }
}
