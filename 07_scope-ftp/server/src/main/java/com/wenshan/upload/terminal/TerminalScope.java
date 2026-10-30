package com.wenshan.upload.terminal;

import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.storage.StorageRoot;
import com.wenshan.upload.storage.StorageRootRegistry;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/**
 * 终端的安全范围：只允许访问 {@code app.storage.roots} 内的目录（契约 3.21 收紧）。
 *
 * <p>允许根来自 {@link StorageRootRegistry}，启动时统一解析为真实路径（存在则 {@code toRealPath()}，
 * 否则绝对 normalize）。前缀判断按路径段比较（{@link Path#startsWith}），避免
 * {@code /rootA-evil} 被误判在 {@code /rootA} 内。</p>
 *
 * <p>这仍是护栏而非沙箱：命令行里的命令本身（如 python、git）可能间接访问系统文件，无法完全阻止。</p>
 */
@Component
public class TerminalScope {

    /** 越界统一后缀（契约 3.21 收紧）。 */
    public static final String MESSAGE_SUFFIX = "（终端仅允许访问 app.storage.roots 内的目录）";

    private final StorageRootRegistry registry;
    private final List<Path> allowedRoots;
    private final Path defaultRoot;

    public TerminalScope(StorageRootRegistry registry) {
        this.registry = registry;
        this.allowedRoots = registry.roots().stream()
                .map(root -> realOrNormalized(root.path()))
                .toList();
        this.defaultRoot = allowedRoots.get(0);
    }

    /** 全部允许根的绝对真实路径（配置顺序；第一个为默认根）。 */
    public List<Path> allowedRoots() {
        return allowedRoots;
    }

    /** 默认根（未指定 root / 无参数 cd 的兜底）。 */
    public Path defaultRoot() {
        return defaultRoot;
    }

    /** root 参数 → 根路径：合法 id 用该根；null/非法 id 均回退默认根（客户端旧数据兼容）。 */
    public Path rootPath(String rootParam) {
        return registry.findRoot(rootParam)
                .map(root -> realOrNormalized(root.path()))
                .orElse(defaultRoot);
    }

    /** 找到包含指定 cwd 的根顶部；找不到返回默认根。 */
    public Path rootContaining(Path cwd) {
        Path normalized = cwd.toAbsolutePath().normalize();
        for (Path root : allowedRoots) {
            if (normalized.startsWith(root)) {
                return root;
            }
        }
        return defaultRoot;
    }

    /** cwd 是否在任一允许根内（按路径段前缀）。 */
    public boolean isInsideAllowed(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        for (Path root : allowedRoots) {
            if (normalized.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 首次会话 cwd：必须是存在的绝对目录，且真实路径落在允许根内。
     *
     * @throws BizException 40000：非绝对路径 / 不存在 / 非目录 / 越界
     */
    public Path requireInitialCwd(String rawCwd) {
        String value = rawCwd.trim();
        if (!value.startsWith("/")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "cwd 必须是绝对路径");
        }
        Path path;
        try {
            path = Path.of(value).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "cwd 路径非法: " + value);
        }
        if (!Files.exists(path)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "cwd 不存在: " + value);
        }
        if (!Files.isDirectory(path)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "cwd 不是目录: " + value);
        }
        try {
            Path real = path.toRealPath();
            if (!isInsideAllowed(real)) {
                throw outside("目录", value);
            }
            return real;
        } catch (IOException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "cwd 不可访问: " + value);
        }
    }

    /**
     * cd 目标校验：真实路径必须落在允许根内。
     *
     * @param displayToken 展示给用户的原始参数（如 {@code ~}、{@code /tmp}）
     */
    public Path requireDirectoryInside(Path candidate, String displayToken) {
        Path path = candidate.toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "目录不存在: " + displayToken);
        }
        if (!Files.isDirectory(path)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不是目录: " + displayToken);
        }
        try {
            Path real = path.toRealPath();
            if (!isInsideAllowed(real)) {
                throw outside("目录", displayToken);
            }
            return real;
        } catch (IOException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "目录不可访问: " + displayToken);
        }
    }

    /** 「目录不在安全范围：<token>（…）」异常。 */
    public static BizException outside(String kind, String token) {
        return new BizException(ErrorCode.BAD_REQUEST, kind + "不在安全范围：" + token + MESSAGE_SUFFIX);
    }

    /** 真实路径（存在则解析 symlink；不存在则绝对 normalize）。 */
    public static Path realOrNormalized(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    /**
     * 路径（可能尚不存在）经 symlink 解析后是否在允许根内：
     * 找最深的已存在祖先做 toRealPath，防止「根内 symlink → /etc」逃逸；
     * 已存在的路径本身若解析到根外同样拒绝。
     */
    public static boolean realInsideAny(List<Path> roots, Path candidate) {
        Path probe = candidate.toAbsolutePath().normalize();
        while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
            probe = probe.getParent();
        }
        if (probe == null) {
            return false;
        }
        try {
            Path real = probe.toRealPath();
            return insideAny(roots, real);
        } catch (IOException e) {
            return false;
        }
    }

    /** 绝对 normalize 后按路径段前缀判断是否在任一允许根内。 */
    public static boolean insideAny(List<Path> roots, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        for (Path root : roots) {
            if (normalized.startsWith(root)) {
                return true;
            }
        }
        return false;
    }
}
