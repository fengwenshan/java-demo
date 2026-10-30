package com.wenshan.upload.storage;

import com.wenshan.upload.common.BizException;
import com.wenshan.upload.common.ErrorCode;
import com.wenshan.upload.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多根注册表：配置解析/启动校验/默认根/root 参数解析/嵌套根隔离。
 */
class StorageRootRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void envSpecCreatesConfiguredRootsWithFirstAsDefault() {
        Path rootA = tempDir.resolve("root-a");
        Path rootB = tempDir.resolve("root-b");

        StorageRootRegistry registry = new StorageRootRegistry(new AppProperties(),
                "rootA=" + rootA + ",rootB=" + rootB);

        assertThat(registry.roots()).extracting(StorageRoot::id).containsExactly("rootA", "rootB");
        assertThat(registry.defaultRootId()).isEqualTo("rootA");
        assertThat(registry.defaultRoot().path()).isEqualTo(rootA.toAbsolutePath().normalize());
        assertThat(registry.infos()).extracting(info -> info.id()).containsExactly("rootA", "rootB");
        assertThat(registry.infos().get(0).path()).isEqualTo(rootA.toAbsolutePath().normalize().toString());
        // 目录与独立元数据目录均被创建
        assertThat(rootA.resolve(StoragePaths.INTERNAL_META_DIR)).isDirectory();
        assertThat(rootB.resolve(StoragePaths.INTERNAL_META_DIR)).isDirectory();
        assertThat(rootA.resolve(StoragePaths.INTERNAL_META_DIR).resolve("metadata.sqlite3")).isRegularFile();
        assertThat(rootA.resolve(StoragePaths.INTERNAL_META_DIR).resolve("files.json")).doesNotExist();
    }

    @Test
    void emptyRootsFailFastInsteadOfFallingBackToDotData() {
        AppProperties properties = new AppProperties();
        properties.getStorage().setRoots(java.util.List.of());

        assertThatThrownBy(() -> new StorageRootRegistry(properties, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未配置存储根")
                .hasMessageContaining("app.storage.roots[]")
                .hasMessageContaining(StorageRootRegistry.ENV_ROOTS);
    }

    @Test
    void propertiesRootsAreUsedWhenEnvIsAbsent() {
        Path rootA = tempDir.resolve("from-yaml-a");
        Path rootB = tempDir.resolve("from-yaml-b");
        AppProperties properties = new AppProperties();
        properties.getStorage().setRoots(java.util.List.of(
                new AppProperties.Root("work", "工作资料", rootA.toString()),
                new AppProperties.Root("media", null, rootB.toString())));

        StorageRootRegistry registry = new StorageRootRegistry(properties, "");

        assertThat(registry.roots()).extracting(StorageRoot::id).containsExactly("work", "media");
        assertThat(registry.roots().get(0).name()).isEqualTo("工作资料");
        // name 省略时默认= id
        assertThat(registry.roots().get(1).name()).isEqualTo("media");
    }

    @Test
    void envSpecOverridesPropertiesRoots() {
        Path yamlRoot = tempDir.resolve("yaml-root");
        Path envRoot = tempDir.resolve("env-root");
        AppProperties properties = new AppProperties();
        properties.getStorage().setRoots(java.util.List.of(
                new AppProperties.Root("yaml", null, yamlRoot.toString())));

        StorageRootRegistry registry = new StorageRootRegistry(properties, "env=" + envRoot);

        assertThat(registry.roots()).extracting(StorageRoot::id).containsExactly("env");
    }

    @Test
    void requireRootFallsBackToDefaultAndRejectsUnknownId() {
        StorageRootRegistry registry = new StorageRootRegistry(new AppProperties(),
                "rootA=" + tempDir.resolve("a") + ",rootB=" + tempDir.resolve("b"));

        assertThat(registry.requireRoot(null).id()).isEqualTo("rootA");
        assertThat(registry.requireRoot("  ").id()).isEqualTo("rootA");
        assertThat(registry.requireRoot("rootB").id()).isEqualTo("rootB");
        assertThat(registry.findRoot("rootA")).isPresent();
        assertThat(registry.findRoot("ghost")).isEmpty();
        assertThatThrownBy(() -> registry.requireRoot("ghost"))
                .isInstanceOfSatisfying(BizException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.BAD_REQUEST));
        // root 只按 id 精确匹配，把 root 当路径前缀不会被接受
        assertThatThrownBy(() -> registry.requireRoot("../rootB"))
                .isInstanceOfSatisfying(BizException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void invalidConfigurationsFailFast() throws IOException {
        // 非法 id
        assertThatThrownBy(() -> new StorageRootRegistry(new AppProperties(),
                "bad id=" + tempDir.resolve("x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("id 非法");
        // 重复 id
        assertThatThrownBy(() -> new StorageRootRegistry(new AppProperties(),
                "dup=" + tempDir.resolve("x") + ",dup=" + tempDir.resolve("y")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("id 重复");
        // 重复 path
        assertThatThrownBy(() -> new StorageRootRegistry(new AppProperties(),
                "a=" + tempDir.resolve("same") + ",b=" + tempDir.resolve("same")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("path 重复");
        // path 是已存在的文件
        Path file = Files.writeString(tempDir.resolve("plain.txt"), "not a dir");
        assertThatThrownBy(() -> new StorageRootRegistry(new AppProperties(), "a=" + file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是目录");
        // 环境变量格式非法 / 空 path
        assertThatThrownBy(() -> new StorageRootRegistry(new AppProperties(), "no-equals-sign"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(StorageRootRegistry.ENV_ROOTS);
        assertThatThrownBy(() -> new StorageRootRegistry(new AppProperties(), "a="))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(StorageRootRegistry.ENV_ROOTS);
    }

    @Test
    void nestedRootsStillResolveOnlyInsideTheirOwnRoot() throws IOException {
        Path rootA = Files.createDirectories(tempDir.resolve("outer"));
        Path rootB = Files.createDirectories(rootA.resolve("inner"));
        StorageRootRegistry registry = new StorageRootRegistry(new AppProperties(),
                "outer=" + rootA + ",inner=" + rootB);

        StorageRoot outer = registry.requireRoot("outer");
        StorageRoot inner = registry.requireRoot("inner");
        Files.writeString(rootB.resolve("b.txt"), "b-content");

        // inner 根正常访问自己的文件
        assertThat(inner.paths().resolveStoredPath("b.txt")).hasContent("b-content");
        // outer 根通过嵌套路径访问 inner 的 .upload-meta 必须被拒绝（内部元数据任何层级都不可访问）
        assertThatThrownBy(() -> outer.paths().resolveStoredPath("inner/.upload-meta/metadata.sqlite3"))
                .isInstanceOf(BizException.class);
        // outer 根相对内嵌子目录的正常用户文件仍可访问（物理上在 outer 内，符合多根重叠语义）
        assertThat(outer.paths().resolveStoredPath("inner/b.txt")).hasContent("b-content");
        // 越出 outer 根（..）始终拒绝
        assertThatThrownBy(() -> outer.paths().resolveStoredPath("../etc/passwd"))
                .isInstanceOf(BizException.class);
    }
}
