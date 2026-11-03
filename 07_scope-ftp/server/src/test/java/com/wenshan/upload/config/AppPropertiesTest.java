package com.wenshan.upload.config;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 契约 3.5 / 3.11 默认配置：单文件上限 5GiB、分片大小 8MiB、会话 TTL 24h。
 * （集成测试 profile 会把 maxFileSize 调小，这里固定默认值本身。）
 *
 * <p>凭证必填：{@code AppKey}/{@code AppSecret} 没有内置默认值，缺失时启动即失败。</p>
 */
class AppPropertiesTest {

    private static AppProperties withClients(AppProperties.Client... clients) {
        AppProperties properties = new AppProperties();
        properties.getSecurity().setClients(new ArrayList<>(List.of(clients)));
        return properties;
    }

    @Test
    void startupFailsWhenAppKeyMissing() {
        AppProperties properties = withClients(
                new AppProperties.Client("", "secret-please-change-32-bytes-min", "测试", true));

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AppKey");
    }

    @Test
    void startupFailsWhenAppSecretMissing() {
        AppProperties properties = withClients(
                new AppProperties.Client("demo-app", "", "测试", true));

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AppSecret");
    }

    @Test
    void startupFailsWhenNoEnabledClient() {
        AppProperties properties = withClients(
                new AppProperties.Client("demo-app", "secret-please-change-32-bytes-min", "测试", false));

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("启用");
    }

    @Test
    void startupFailsWhenAppKeyDuplicated() {
        AppProperties properties = withClients(
                new AppProperties.Client("dup-key", "secret-please-change-32-bytes-min", "一", true),
                new AppProperties.Client("dup-key", "another-secret-please-change-32-b", "二", true));

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复");
    }

    @Test
    void startupAcceptsExplicitCredentials() {
        AppProperties properties = withClients(
                new AppProperties.Client("demo-app", "secret-please-change-32-bytes-min", "测试", true));

        assertThatCode(properties::afterPropertiesSet).doesNotThrowAnyException();
    }

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
        // 代码兜底默认值：唯一根 id=default, name=默认目录, path=./data（实际环境文件会覆盖 roots）
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
