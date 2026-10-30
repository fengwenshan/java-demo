package com.wenshan.upload.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约 3.5 / 3.11 默认配置：单文件上限 5GiB、分片大小 8MiB、会话 TTL 24h。
 * （集成测试 profile 会把 maxFileSize 调小，这里固定默认值本身。）
 */
class AppPropertiesTest {

    @Test
    void defaultsMatchContract() {
        AppProperties.Storage storage = new AppProperties().getStorage();
        assertThat(storage.getMaxFileSize()).isEqualTo(5_368_709_120L);
        assertThat(storage.getChunkSize()).isEqualTo(8_388_608);
        assertThat(storage.getSessionTtlMs()).isEqualTo(86_400_000L);
    }

    @Test
    void defaultRootsMatchApplicationYmlAndAreOverridable() {
        AppProperties.Storage storage = new AppProperties().getStorage();
        // 代码默认与 application.yml 的默认 roots 一致：唯一根 id=default, name=默认目录, path=./data
        assertThat(storage.getRoots()).hasSize(1);
        AppProperties.Root defaultRoot = storage.getRoots().get(0);
        assertThat(defaultRoot.getId()).isEqualTo(AppProperties.Storage.DEFAULT_ROOT_ID);
        assertThat(defaultRoot.getName()).isEqualTo(AppProperties.Storage.DEFAULT_ROOT_NAME);
        assertThat(defaultRoot.getPath()).isEqualTo(AppProperties.Storage.DEFAULT_ROOT_PATH);

        storage.setRoots(java.util.List.of(
                new AppProperties.Root("work", "工作资料", "/tmp/work")));
        assertThat(storage.getRoots()).hasSize(1);
        assertThat(storage.getRoots().get(0).getId()).isEqualTo("work");
        // 显式置空是允许的：由 StorageRootRegistry 在启动阶段快速失败
        storage.setRoots(null);
        assertThat(storage.getRoots()).isEmpty();
    }
}
