package com.wenshan.upload.storage;

import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.config.AppProperties;
import com.wenshan.upload.dto.StorageRootInfo;
import com.wenshan.upload.store.MetaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 多存储根注册表：把配置解析成一组 {@link StorageRoot}，并作为「root 参数 → 具体根」的唯一入口。
 *
 * <p>配置来源（优先级从高到低）：</p>
 * <ol>
 *   <li>环境变量 {@code UPLOAD_STORAGE_ROOTS}：{@code id=path,id2=path2} 简单形式；</li>
 *   <li>{@code app.storage.roots[]}（id/name/path 列表）；</li>
 * </ol>
 * <p>解析后没有根配置时启动失败（中文提示），不会隐式使用 ./data。</p>
 *
 * <p>启动校验（失败快速失败，服务不启动）：id 必须匹配 {@code ^[a-zA-Z0-9_-]{1,32}$} 且唯一；
 * path 不能为空、不能是已存在的普通文件、不能重复；目录不存在则创建，并做真实写入探测确认可写。</p>
 */
@Component
public class StorageRootRegistry {

    private static final Logger log = LoggerFactory.getLogger(StorageRootRegistry.class);

    public static final String ENV_ROOTS = "UPLOAD_STORAGE_ROOTS";

    private static final Pattern ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,32}$");

    private final List<StorageRoot> roots;
    private final Map<String, StorageRoot> rootsById;
    private final StorageRoot defaultRoot;

    @Autowired
    public StorageRootRegistry(AppProperties properties) {
        this(properties, System.getenv(ENV_ROOTS));
    }

    /** 测试用：可显式传入 {@code id=path,...} 形式的根配置（等价于环境变量内容）。 */
    StorageRootRegistry(AppProperties properties, String rootsSpec) {
        List<Spec> specs = resolveSpecs(properties, rootsSpec);
        Map<String, StorageRoot> registered = new LinkedHashMap<>();
        Set<String> paths = new HashSet<>();
        for (Spec spec : specs) {
            String id = spec.id() == null ? "" : spec.id().trim();
            if (!ID_PATTERN.matcher(id).matches()) {
                throw new IllegalStateException(
                        "存储根 id 非法（必须匹配 ^[a-zA-Z0-9_-]{1,32}$）: " + spec.id());
            }
            if (registered.containsKey(id)) {
                throw new IllegalStateException("存储根 id 重复: " + id);
            }
            String configuredPath = spec.path() == null ? "" : spec.path().trim();
            if (configuredPath.isEmpty()) {
                throw new IllegalStateException("存储根 path 不能为空: id=" + id);
            }
            Path path;
            try {
                path = Path.of(configuredPath).toAbsolutePath().normalize();
            } catch (InvalidPathException e) {
                throw new IllegalStateException("存储根 path 非法: id=" + id + ", path=" + configuredPath, e);
            }
            if (Files.exists(path) && !Files.isDirectory(path)) {
                throw new IllegalStateException("存储根 path 已存在但不是目录: " + path);
            }
            if (!paths.add(path.toString())) {
                throw new IllegalStateException("存储根 path 重复: " + path);
            }
            String name = (spec.name() == null || spec.name().isBlank()) ? id : spec.name().trim();
            StoragePaths rootPaths = new StoragePaths(path.toString());
            MetaStore metaStore = new MetaStore(rootPaths);
            StorageRoot root = new StorageRoot(id, name, path, rootPaths, metaStore);
            registered.put(id, root);
            log.info("存储根已生效: id={}, name={}, path={}", id, name, path);
        }
        this.roots = List.copyOf(registered.values());
        this.rootsById = Collections.unmodifiableMap(registered);
        this.defaultRoot = roots.get(0);
        log.info("多存储根已就绪: defaultRootId={}, 根数量={}", defaultRoot.id(), roots.size());
    }

    /** 全部根（配置顺序；第一个为默认根）。 */
    public List<StorageRoot> roots() {
        return roots;
    }

    /** 默认根（接口未传 root 时使用）。 */
    public StorageRoot defaultRoot() {
        return defaultRoot;
    }

    public String defaultRootId() {
        return defaultRoot.id();
    }

    /** 按 id 查找（不报错）。 */
    public Optional<StorageRoot> findRoot(String rootId) {
        if (rootId == null || rootId.isBlank()) {
            return Optional.of(defaultRoot);
        }
        return Optional.ofNullable(rootsById.get(rootId.trim()));
    }

    /**
     * 解析请求的 root 参数：{@code null}/空白 = 默认根；不存在/非法 id → 40000。
     * 只按配置中的 id 精确匹配，绝不把 root 当成路径前缀使用。
     */
    public StorageRoot requireRoot(String rootParam) {
        if (rootParam == null || rootParam.isBlank()) {
            return defaultRoot;
        }
        StorageRoot root = rootsById.get(rootParam.trim());
        if (root == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "root 不存在: " + rootParam);
        }
        return root;
    }

    /** 对外展示的根列表（含绝对路径）。 */
    public List<StorageRootInfo> infos() {
        return roots.stream().map(StorageRoot::info).toList();
    }

    // ------------------------------------------------------------------

    private static List<Spec> resolveSpecs(AppProperties properties, String rootsSpec) {
        List<Spec> specs = new ArrayList<>();
        if (rootsSpec != null && !rootsSpec.isBlank()) {
            specs.addAll(parseEnvSpec(rootsSpec));
            return specs;
        }
        for (AppProperties.Root root : properties.getStorage().getRoots()) {
            if (root != null) {
                specs.add(new Spec(root.getId(), root.getName(), root.getPath()));
            }
        }
        if (specs.isEmpty()) {
            throw new IllegalStateException(
                    "未配置存储根：请设置 app.storage.roots[] 或 UPLOAD_STORAGE_ROOTS（id=path,id2=path2）");
        }
        return specs;
    }

    /** 解析 {@code id=path,id2=path2}（简单形式；路径中允许出现 '='，只按第一个 '=' 切分）。 */
    private static List<Spec> parseEnvSpec(String rootsSpec) {
        List<Spec> specs = new ArrayList<>();
        for (String rawPair : rootsSpec.split(",")) {
            String pair = rawPair.trim();
            if (pair.isEmpty()) {
                continue;
            }
            int separator = pair.indexOf('=');
            if (separator <= 0 || separator == pair.length() - 1) {
                throw new IllegalStateException(
                        "环境变量 " + ENV_ROOTS + " 格式应为 id=path,id2=path2: " + rootsSpec);
            }
            String id = pair.substring(0, separator).trim();
            String path = pair.substring(separator + 1).trim();
            if (id.isEmpty() || path.isEmpty()) {
                throw new IllegalStateException(
                        "环境变量 " + ENV_ROOTS + " 存在空的 id 或 path: " + rootsSpec);
            }
            specs.add(new Spec(id, null, path));
        }
        if (specs.isEmpty()) {
            throw new IllegalStateException("环境变量 " + ENV_ROOTS + " 未包含任何有效的 id=path");
        }
        return specs;
    }

    private record Spec(String id, String name, String path) {
    }
}
