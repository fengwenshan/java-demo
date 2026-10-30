package com.wenshan.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenshan.upload.support.IntegrationTestSupport;
import com.wenshan.upload.support.TestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 自定义存储根目录（app.storage.roots[0].path）：FTP 式落盘位置、隐藏元数据目录、verify.storageDir。
 */
@SpringBootTest(properties = {
        "app.test.suite=storage-root",
        "app.storage.roots[0].id=default",
        "app.storage.roots[0].name=自定义根",
        "app.storage.roots[0].path=target/test-data/root-custom"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StorageRootIntegrationTest extends IntegrationTestSupport {

    private static final String UPLOAD_PATH = "/api/files/upload";

    @Test
    void uploadLandsUnderConfiguredRootAndMetaIsHidden() throws Exception {
        Path expectedRoot = Path.of("target/test-data/root-custom").toAbsolutePath().normalize();
        assertThat(storagePaths.root()).isEqualTo(expectedRoot);
        assertThat(storagePaths.metaDir()).isEqualTo(expectedRoot.resolve(".upload-meta"));
        assertThat(storagePaths.metaDir()).isDirectory();
        // 新布局不再创建 root/files、root/meta、root/tmp
        assertThat(expectedRoot.resolve("files")).doesNotExist();
        assertThat(expectedRoot.resolve("meta")).doesNotExist();
        assertThat(expectedRoot.resolve("tmp")).doesNotExist();

        byte[] content = "custom storage root content".getBytes(StandardCharsets.UTF_8);
        MockMultipartHttpServletRequestBuilder builder = multipart(UPLOAD_PATH)
                .file(new MockMultipartFile("file", "root.txt", "text/plain", content));
        builder.param("name", "自定义目录.txt");
        ResultActions result = mockMvc.perform(TestSigner.signedMultipart(builder, UPLOAD_PATH, content, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("自定义目录.txt"));
        String id = body(result).path("data").path("id").asText();
        assertThat(id).isNotBlank();

        // 文件以展示名直接落在 root 下
        List<Path> storedFiles;
        try (Stream<Path> walk = Files.walk(storagePaths.root())) {
            storedFiles = walk.filter(Files::isRegularFile)
                    .filter(path -> !isInsideInternalMeta(path))
                    .toList();
        }
        assertThat(storedFiles).hasSize(1);
        assertThat(storedFiles.get(0)).isEqualTo(storagePaths.root().resolve("自定义目录.txt"));
        assertThat(storedFiles.get(0)).hasContent("custom storage root content");
        assertThat(storagePaths.metaDir().resolve("metadata.sqlite3")).isRegularFile();
        // 生效 root 必须是自定义目录（测试 profile 不使用默认 ./data）
        assertThat(storagePaths.root()).isNotEqualTo(Path.of("./data").toAbsolutePath().normalize());
    }

    @Test
    void verifyReturnsStorageDirForSettingsPage() throws Exception {
        ResultActions result = mockMvc.perform(TestSigner.signedGet("/api/auth/verify", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.appKey").value("demo-app"))
                .andExpect(jsonPath("$.data.serverTime").isNumber())
                .andExpect(jsonPath("$.data.limits.maxFileSize").value(1048576))
                .andExpect(jsonPath("$.data.limits.allowedExtensions").doesNotExist())
                .andExpect(jsonPath("$.data.storageDir").isString());
        JsonNode data = body(result).path("data");
        assertThat(data.path("storageDir").asText()).isEqualTo(storagePaths.root().toString());
        assertThat(Path.of(data.path("storageDir").asText())).isAbsolute();
    }
}
