package com.wenshan.upload;

import com.wenshan.upload.dto.CreateEntryRequest;
import com.wenshan.upload.storage.StorageRootRegistry;
import com.wenshan.upload.support.IntegrationTestSupport;
import com.wenshan.upload.support.TestSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 新建文件 / 文件夹：父目录校验、单段名称校验、不覆盖已有目标、不写元数据。
 */
@SpringBootTest(properties = "app.test.suite=file-create")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FileCreateIntegrationTest extends IntegrationTestSupport {

    private static final String CREATE_PATH = "/api/files/create";
    private static final String ROOT_ID = "createRoot";
    private static final Path ROOT = createTempRoot("file-create");

    @DynamicPropertySource
    static void registerRoot(DynamicPropertyRegistry registry) {
        registry.add("app.storage.roots[0].id", () -> ROOT_ID);
        registry.add("app.storage.roots[0].path", ROOT::toString);
    }

    @Autowired
    private StorageRootRegistry rootRegistry;

    @BeforeEach
    void prepareRoot() throws IOException {
        Files.createDirectories(ROOT.resolve("docs"));
    }

    @Test
    void createsEmptyFileAtRootAndNestedDirectory() throws Exception {
        create(ROOT_ID, "", "new.txt", "file")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.root").value(ROOT_ID))
                .andExpect(jsonPath("$.data.path").value("new.txt"))
                .andExpect(jsonPath("$.data.type").value("file"))
                .andExpect(jsonPath("$.data.name").value("new.txt"));
        assertThat(ROOT.resolve("new.txt")).isRegularFile();
        assertThat(Files.size(ROOT.resolve("new.txt"))).isZero();

        create(ROOT_ID, "docs", "pkg", "directory")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.path").value("docs/pkg"))
                .andExpect(jsonPath("$.data.type").value("directory"))
                .andExpect(jsonPath("$.data.name").value("pkg"));
        assertThat(ROOT.resolve("docs/pkg")).isDirectory();

        create(ROOT_ID, "docs/pkg", "empty.txt", "file")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.path").value("docs/pkg/empty.txt"));
        assertThat(ROOT.resolve("docs/pkg/empty.txt")).isRegularFile();
    }

    @Test
    void acceptsNameWithSurroundingWhitespaceButStoresTrimmedName() throws Exception {
        create(ROOT_ID, "docs", "  spaced.txt  ", "file")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("spaced.txt"))
                .andExpect(jsonPath("$.data.path").value("docs/spaced.txt"));
        assertThat(ROOT.resolve("docs/spaced.txt")).isRegularFile();
    }

    @Test
    void rejectsDuplicateFileOrDirectoryWithoutOverwriting() throws Exception {
        Files.writeString(ROOT.resolve("docs/existing.txt"), "keep");
        create(ROOT_ID, "docs", "existing.txt", "file")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("同名文件或目录已存在")));
        assertThat(ROOT.resolve("docs/existing.txt")).hasContent("keep");

        Files.createDirectories(ROOT.resolve("docs/existing-dir"));
        create(ROOT_ID, "docs", "existing-dir", "directory")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        assertThat(ROOT.resolve("docs/existing-dir")).isDirectory();
    }

    @Test
    void rejectsInvalidTypeNameAndParent() throws Exception {
        create(ROOT_ID, "docs", "a.txt", "symlink")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("type")));

        for (String badName : new String[]{"", "   ", ".", "..", "a/b", "a\\b", "a\nb", ".upload-meta"}) {
            create(ROOT_ID, "docs", badName, "file")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }

        create(ROOT_ID, "docs/missing", "a.txt", "file")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("目标目录不存在")));

        Files.writeString(ROOT.resolve("docs/plain.txt"), "plain");
        create(ROOT_ID, "docs/plain.txt", "a.txt", "file")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("目标路径不是目录")));
    }

    @Test
    void rejectsSymlinkedParentAndUnknownRoot() throws Exception {
        Path outside = createTempRoot("file-create-outside");
        try {
            Files.createSymbolicLink(ROOT.resolve("docs/link"), outside);
            create(ROOT_ID, "docs/link", "a.txt", "file")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
            assertThat(outside.resolve("a.txt")).doesNotExist();
        } finally {
            Files.deleteIfExists(ROOT.resolve("docs/link"));
        }

        create("ghost", "docs", "a.txt", "file")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void doesNotWriteFilesMetadataForNewEntries() throws Exception {
        int before = rootRegistry.requireRoot(ROOT_ID).metaStore()
                .filesOf(TestSigner.APP_KEY).size();
        create(ROOT_ID, "docs", "meta-check.txt", "file")
                .andExpect(status().isOk());
        create(ROOT_ID, "docs", "meta-check-dir", "directory")
                .andExpect(status().isOk());
        assertThat(rootRegistry.requireRoot(ROOT_ID).metaStore()
                .filesOf(TestSigner.APP_KEY)).hasSize(before);
    }

    private static Path createTempRoot(String prefix) {
        try {
            return Files.createTempDirectory(prefix + "-").toAbsolutePath().normalize();
        } catch (IOException e) {
            throw new IllegalStateException("无法创建测试存储根", e);
        }
    }

    private ResultActions create(String root, String parent, String name, String type) throws Exception {
        byte[] body = objectMapper.writeValueAsBytes(new CreateEntryRequest(root, parent, name, type));
        return mockMvc.perform(TestSigner.signedJsonPost(post(CREATE_PATH), CREATE_PATH, body));
    }
}
