package com.wenshan.upload.config;

import com.wenshan.upload.storage.StoragePaths;
import com.wenshan.upload.storage.StorageRootRegistry;
import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 通用 Bean 定义。
 */
@Configuration
public class AppConfig {

    /** 统一时钟，便于测试替换为固定时钟。 */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * 默认根（第一个配置的根）的 {@link StoragePaths}。
     *
     * <p>保留该 Bean 是为了单根兼容与 multipart 临时目录；多根业务代码统一通过
     * {@link StorageRootRegistry} 获取「请求指定的根」的 paths/store，不直接注入该 Bean。</p>
     */
    @Bean
    public StoragePaths storagePaths(StorageRootRegistry storageRootRegistry) {
        return storageRootRegistry.defaultRoot().paths();
    }

    /**
     * 显式指定 multipart 临时目录的绝对路径：${java.io.tmpdir}/scop-ftp-tmp
     * （不再放在存储根目录内，root 下只保留用户文件；StoragePaths 已在启动时创建并校验可写）。
     */
    @Bean
    public MultipartConfigElement multipartConfigElement(StoragePaths storagePaths,
                                                         MultipartProperties multipartProperties) {
        return new MultipartConfigElement(
                storagePaths.tmpDir().toString(),
                multipartProperties.getMaxFileSize().toBytes(),
                multipartProperties.getMaxRequestSize().toBytes(),
                (int) multipartProperties.getFileSizeThreshold().toBytes());
    }
}
