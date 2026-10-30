package com.wenshan.upload.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshan.upload.config.AppProperties;
import com.wenshan.upload.security.NonceCache;
import com.wenshan.upload.security.RateLimitService;
import com.wenshan.upload.storage.StoragePaths;
import com.wenshan.upload.store.UploadSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 集成测试基类：每个测试前清理 root 下的用户文件/目录（保留内部元数据 .upload-meta）、
 * nonce 缓存与令牌桶，保证互不干扰。
 */
public abstract class IntegrationTestSupport {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected AppProperties properties;

    @Autowired
    protected NonceCache nonceCache;

    @Autowired
    protected RateLimitService rateLimitService;

    @Autowired
    protected StoragePaths storagePaths;

    @Autowired
    protected UploadSessionStore uploadSessionStore;

    @BeforeEach
    void resetState() throws IOException {
        nonceCache.clear();
        rateLimitService.clear();
        wipeUserContent();
        wipeUploadSessions();
    }

    /** 清空系统临时目录下的分片上传会话，避免跨测试串扰。 */
    protected void wipeUploadSessions() throws IOException {
        Path sessionRoot = uploadSessionStore.sessionRoot();
        if (!Files.isDirectory(sessionRoot)) {
            return;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(sessionRoot)) {
            for (Path child : children) {
                deleteRecursively(child);
            }
        }
    }

    /** 清空 root 下用户文件/目录，但保留内部元数据目录 .upload-meta。 */
    protected void wipeUserContent() throws IOException {
        Path storageRoot = storagePaths.root();
        Files.createDirectories(storageRoot);
        try (DirectoryStream<Path> children = Files.newDirectoryStream(storageRoot)) {
            for (Path child : children) {
                if (StoragePaths.INTERNAL_META_DIR.equals(child.getFileName().toString())) {
                    continue;
                }
                deleteRecursively(child);
            }
        }
    }

    /** 从 .upload-meta/metadata.sqlite3 的 file_meta 表读取指定 id 的 storedPath（相对 root）。 */
    protected String storedPathOf(String id) throws Exception {
        Path database = storagePaths.metaDir().resolve("metadata.sqlite3");
        if (!Files.isRegularFile(database)) {
            return null;
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT stored_path FROM file_meta WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    protected JsonNode body(ResultActions actions) throws Exception {
        String json = actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(json);
    }

    /** root 下用户区（不含 .upload-meta）的普通文件数。 */
    protected long countStoredFiles() throws IOException {
        Path storageRoot = storagePaths.root();
        if (!Files.exists(storageRoot)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(storageRoot)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> !isInsideInternalMeta(path))
                    .count();
        }
    }

    protected boolean isInsideInternalMeta(Path path) {
        Path metaDir = storagePaths.metaDir();
        return path.toAbsolutePath().normalize().startsWith(metaDir);
    }

    protected static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException ignored) {
                    // 清理失败不影响断言
                }
            });
        }
    }
}
