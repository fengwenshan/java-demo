package com.wenshan.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenshan.upload.dto.RenameRequest;
import com.wenshan.upload.storage.StoragePaths;
import com.wenshan.upload.storage.StorageRootRegistry;
import com.wenshan.upload.store.FileRecord;
import com.wenshan.upload.support.IntegrationTestSupport;
import com.wenshan.upload.support.TestSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 契约 3.17 重命名 + 3.10 删除空目录：
 * 文件/目录重命名（磁盘 + 元数据同步）、冲突不覆盖、非法 newName、越界/跨根/symlink 拒绝、
 * 空目录可删/非空目录 40000、文件删除回归、root 隔离。
 */
@SpringBootTest(properties = "app.test.suite=rename")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RenameIntegrationTest extends IntegrationTestSupport {

    private static final String RENAME_PATH = "/api/files/rename";
    private static final String DELETE_PATH = "/api/files";
    private static final String DIR_PATH = "/api/files/dir";
    private static final String DOWNLOAD_PATH = "/api/files/download";
    private static final String LIST_PATH = "/api/files";
    private static final String UPLOAD_PATH = "/api/files/upload";

    private static final String ROOT_A_ID = "rootA";
    private static final String ROOT_B_ID = "rootB";
    private static final Path ROOT_A = createTempRoot("rename-a");
    private static final Path ROOT_B = createTempRoot("rename-b");

    @DynamicPropertySource
    static void registerRoots(DynamicPropertyRegistry registry) {
        registry.add("app.storage.roots[0].id", () -> ROOT_A_ID);
        registry.add("app.storage.roots[0].path", ROOT_A::toString);
        registry.add("app.storage.roots[1].id", () -> ROOT_B_ID);
        registry.add("app.storage.roots[1].path", ROOT_B::toString);
    }

    @Autowired
    private StorageRootRegistry rootRegistry;

    @BeforeEach
    void prepareRoots() throws IOException {
        wipe(ROOT_A);
        wipe(ROOT_B);
        Files.createDirectories(ROOT_A.resolve("docs"));
        Files.createDirectories(ROOT_B.resolve("docs"));
    }

    @Test
    void renameFileUpdatesDiskAndMetadata() throws Exception {
        byte[] content = "rename me".getBytes(StandardCharsets.UTF_8);
        String id = uploadData(ROOT_A_ID, "docs", "old.txt", content).path("id").asText();

        ResultActions renamed = rename(ROOT_A_ID, "docs/old.txt", "new.txt")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.oldPath").value("docs/old.txt"))
                .andExpect(jsonPath("$.data.newPath").value("docs/new.txt"))
                .andExpect(jsonPath("$.data.type").value("file"));

        assertThat(ROOT_A.resolve("docs/old.txt")).doesNotExist();
        assertThat(ROOT_A.resolve("docs/new.txt")).hasBinaryContent(content);
        assertThat(storedPathOf(id)).isEqualTo("docs/new.txt");
        assertThat(recordName(id)).isEqualTo("new.txt");

        // 新路径可下载（按路径）、旧路径 40400；按 id 下载仍然可用
        mockMvc.perform(signedGetQuery(DOWNLOAD_PATH, Map.of("root", ROOT_A_ID, "path", "docs/new.txt")))
                .andExpect(status().isOk());
        mockMvc.perform(signedGetQuery(DOWNLOAD_PATH, Map.of("root", ROOT_A_ID, "path", "docs/old.txt")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
        mockMvc.perform(TestSigner.signedGet("/api/files/" + id + "/download", null))
                .andExpect(status().isOk());

        // 列表按新名字可查，且 rootId 不变
        mockMvc.perform(signedGetQuery(LIST_PATH, Map.of("root", ROOT_A_ID, "keyword", "new.txt")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("new.txt"))
                .andExpect(jsonPath("$.data.items[0].rootId").value(ROOT_A_ID));

        // 重命名不影响 id：按 id 删除仍按记录 rootId 找到新路径
        mockMvc.perform(TestSigner.signedDelete("/api/files/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id));
        assertThat(ROOT_A.resolve("docs/new.txt")).doesNotExist();
    }

    @Test
    void renameDirectoryMovesChildrenAndRewritesMetadataPrefix() throws Exception {
        byte[] a = "a".getBytes(StandardCharsets.UTF_8);
        byte[] b = "b".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(ROOT_A.resolve("docs/sub"));
        String idA = uploadData(ROOT_A_ID, "docs", "a.txt", a).path("id").asText();
        String idB = uploadData(ROOT_A_ID, "docs/sub", "b.txt", b).path("id").asText();

        rename(ROOT_A_ID, "docs", "文档")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.oldPath").value("docs"))
                .andExpect(jsonPath("$.data.newPath").value("文档"))
                .andExpect(jsonPath("$.data.type").value("directory"));

        assertThat(ROOT_A.resolve("docs")).doesNotExist();
        assertThat(ROOT_A.resolve("文档/a.txt")).hasBinaryContent(a);
        assertThat(ROOT_A.resolve("文档/sub/b.txt")).hasBinaryContent(b);
        // 元数据前缀整体替换
        assertThat(storedPathOf(idA)).isEqualTo("文档/a.txt");
        assertThat(storedPathOf(idB)).isEqualTo("文档/sub/b.txt");
        // 文件名不变
        assertThat(recordName(idA)).isEqualTo("a.txt");
        assertThat(recordName(idB)).isEqualTo("b.txt");

        mockMvc.perform(signedGetQuery(DOWNLOAD_PATH, Map.of("root", ROOT_A_ID, "path", "文档/sub/b.txt")))
                .andExpect(status().isOk());
        mockMvc.perform(signedGetQuery(DOWNLOAD_PATH, Map.of("root", ROOT_A_ID, "path", "docs/a.txt")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));

        ResultActions browse = mockMvc.perform(signedGetQuery(DIR_PATH, Map.of("root", ROOT_A_ID, "path", "文档")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.path").value("文档"));
        assertThat(namesOf(body(browse).path("data").path("entries"))).contains("a.txt", "sub");
    }

    @Test
    void renameNeverOverwritesExistingTarget() throws Exception {
        byte[] a = "first".getBytes(StandardCharsets.UTF_8);
        byte[] b = "second".getBytes(StandardCharsets.UTF_8);
        String idA = uploadData(ROOT_A_ID, "docs", "a.txt", a).path("id").asText();
        String idB = uploadData(ROOT_A_ID, "docs", "b.txt", b).path("id").asText();

        rename(ROOT_A_ID, "docs/a.txt", "b.txt")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("已存在")));

        // 两个文件的内容/元数据都没有变化
        assertThat(ROOT_A.resolve("docs/a.txt")).hasBinaryContent(a);
        assertThat(ROOT_A.resolve("docs/b.txt")).hasBinaryContent(b);
        assertThat(storedPathOf(idA)).isEqualTo("docs/a.txt");
        assertThat(storedPathOf(idB)).isEqualTo("docs/b.txt");

        // 目录重命名到已存在的文件/目录同样 40000 且不覆盖
        Files.createDirectories(ROOT_A.resolve("docs/dir1"));
        Files.createDirectories(ROOT_A.resolve("docs/dir2"));
        rename(ROOT_A_ID, "docs/dir1", "dir2")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        assertThat(ROOT_A.resolve("docs/dir1")).isDirectory();
        assertThat(ROOT_A.resolve("docs/dir2")).isDirectory();
        // 重命名为自身也视为冲突，不产生任何变化
        rename(ROOT_A_ID, "docs/a.txt", "a.txt")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        assertThat(ROOT_A.resolve("docs/a.txt")).hasBinaryContent(a);
    }

    @Test
    void invalidNewNamesRejectedWith40000() throws Exception {
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        String id = uploadData(ROOT_A_ID, "docs", "keep.txt", content).path("id").asText();

        for (String badName : List.of("", "   ", "a/b", "a\\b", "..", ".", ".upload-meta", "../escape.txt", "x".repeat(256))) {
            rename(ROOT_A_ID, "docs/keep.txt", badName)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
        assertThat(ROOT_A.resolve("docs/keep.txt")).hasBinaryContent(content);
        assertThat(storedPathOf(id)).isEqualTo("docs/keep.txt");
        // 缺少字段 / 空请求体
        byte[] missingBody = objectMapper.writeValueAsBytes(new RenameRequest(ROOT_A_ID, "docs/keep.txt", null));
        mockMvc.perform(TestSigner.signedJsonPost(post(RENAME_PATH), RENAME_PATH, missingBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void renamePathBoundariesRejected() throws Exception {
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        uploadData(ROOT_A_ID, "docs", "keep.txt", content);
        String bName = ROOT_B.getFileName().toString();

        // 不存在 → 40400
        rename(ROOT_A_ID, "docs/missing.txt", "n.txt")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
        // 根目录本身 / 非法相对路径 → 40000
        for (String badPath : new String[]{"", ".", "..", "docs/../docs/keep.txt"}) {
            rename(ROOT_A_ID, badPath, "n.txt")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
        // 跨根：../rootB/... 与绝对路径 → 40000，rootB 文件不动
        byte[] bContent = "b".getBytes(StandardCharsets.UTF_8);
        uploadData(ROOT_B_ID, "docs", "keep.txt", bContent);
        rename(ROOT_A_ID, "../" + bName + "/docs/keep.txt", "hacked.txt")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        rename(ROOT_A_ID, ROOT_B.resolve("docs/keep.txt").toString(), "hacked.txt")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        assertThat(ROOT_B.resolve("docs/keep.txt")).hasBinaryContent(bContent);
        assertThat(ROOT_B.resolve("docs/hacked.txt")).doesNotExist();

        // symlink 指向 rootB → 40000
        Files.createSymbolicLink(ROOT_A.resolve("link-to-b"), ROOT_B);
        rename(ROOT_A_ID, "link-to-b/docs/keep.txt", "hacked.txt")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        assertThat(ROOT_B.resolve("docs/keep.txt")).hasBinaryContent(bContent);
        // 非法 root id → 40000
        rename("ghost", "docs/keep.txt", "n.txt")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        assertThat(ROOT_A.resolve("docs/keep.txt")).hasBinaryContent(content);
    }

    @Test
    void renameInRootBStaysInsideRootB() throws Exception {
        byte[] content = "media".getBytes(StandardCharsets.UTF_8);
        String id = uploadData(ROOT_B_ID, "docs", "m.bin", content).path("id").asText();

        rename(ROOT_B_ID, "docs", "media-docs")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("directory"));
        assertThat(ROOT_B.resolve("media-docs/m.bin")).hasBinaryContent(content);
        assertThat(ROOT_B.resolve("docs")).doesNotExist();
        assertThat(ROOT_A.resolve("media-docs")).doesNotExist();
        assertThat(storedPathOfIn(ROOT_B_ID, id)).isEqualTo("media-docs/m.bin");

        // root 缺省时操作默认根 rootA：rootB 的路径不可见 → 40400
        rename(null, "media-docs/m.bin", "x.bin")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
        assertThat(ROOT_B.resolve("media-docs/m.bin")).hasBinaryContent(content);
    }

    @Test
    void deleteEmptyDirectorySucceeds() throws Exception {
        Files.createDirectories(ROOT_A.resolve("docs/empty"));

        mockMvc.perform(signedDeleteQuery(DELETE_PATH, Map.of("root", ROOT_A_ID, "path", "docs/empty")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.path").value("docs/empty"));
        assertThat(ROOT_A.resolve("docs/empty")).doesNotExist();
    }

    @Test
    void deleteNonEmptyDirectoryRejectedAndContentKept() throws Exception {
        byte[] content = "keep".getBytes(StandardCharsets.UTF_8);
        uploadData(ROOT_A_ID, "docs", "inside.txt", content);
        Files.createDirectories(ROOT_A.resolve("docs/sub"));

        mockMvc.perform(signedDeleteQuery(DELETE_PATH, Map.of("root", ROOT_A_ID, "path", "docs")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("目录非空")));
        assertThat(ROOT_A.resolve("docs/inside.txt")).hasBinaryContent(content);
        assertThat(ROOT_A.resolve("docs/sub")).isDirectory();

        // 只含子目录也算非空
        Files.createDirectories(ROOT_A.resolve("only-dirs/child/x"));
        mockMvc.perform(signedDeleteQuery(DELETE_PATH, Map.of("root", ROOT_A_ID, "path", "only-dirs")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("目录非空")));
        assertThat(ROOT_A.resolve("only-dirs/child/x")).isDirectory();
    }

    @Test
    void deleteFileByPathRegression() throws Exception {
        byte[] content = "delete me".getBytes(StandardCharsets.UTF_8);
        String id = uploadData(ROOT_A_ID, "docs", "f.txt", content).path("id").asText();

        mockMvc.perform(signedDeleteQuery(DELETE_PATH, Map.of("root", ROOT_A_ID, "path", "docs/f.txt")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.path").value("docs/f.txt"));
        assertThat(ROOT_A.resolve("docs/f.txt")).doesNotExist();
        assertThat(rootRegistry.requireRoot(ROOT_A_ID).metaStore()
                .findFile(TestSigner.APP_KEY, id)).isEmpty();
        // 再删 → 40400；非法路径/根目录/内部目录 → 40000
        mockMvc.perform(signedDeleteQuery(DELETE_PATH, Map.of("root", ROOT_A_ID, "path", "docs/f.txt")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
        for (String badPath : new String[]{"", ".upload-meta", ".upload-meta/files.json",
                ".upload-meta/metadata.sqlite3"}) {
            mockMvc.perform(signedDeleteQuery(DELETE_PATH, Map.of("root", ROOT_A_ID, "path", badPath)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
    }

    @Test
    void renameAndDeleteRespectSignatureAndRoot() throws Exception {
        // 非法/未知 root 在 rename/delete 上都是 40000，且不影响任何根
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        uploadData(ROOT_B_ID, "docs", "safe.txt", content);
        rename("ghost", "docs/safe.txt", "n.txt")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(signedDeleteQuery(DELETE_PATH, Map.of("root", "ghost", "path", "docs/safe.txt")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        assertThat(ROOT_B.resolve("docs/safe.txt")).hasBinaryContent(content);
    }

    // ------------------------------------------------------------------

    private ResultActions rename(String root, String path, String newName) throws Exception {
        byte[] body = objectMapper.writeValueAsBytes(new RenameRequest(root, path, newName));
        return mockMvc.perform(TestSigner.signedJsonPost(post(RENAME_PATH), RENAME_PATH, body));
    }

    private JsonNode uploadData(String root, String dir, String fileName, byte[] content) throws Exception {
        MockMultipartHttpServletRequestBuilder builder = multipart(UPLOAD_PATH)
                .file(new MockMultipartFile("file", fileName, "application/octet-stream", content));
        builder.param("name", fileName);
        builder.param("root", root);
        if (dir != null) {
            builder.param("dir", dir);
        }
        ResultActions result = mockMvc.perform(TestSigner.signedMultipart(builder, UPLOAD_PATH, content, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        return body(result).path("data");
    }

    private MockHttpServletRequestBuilder signedGetQuery(String path, Map<String, String> params) {
        if (params.isEmpty()) {
            return TestSigner.signedGet(path, null);
        }
        MockHttpServletRequestBuilder builder = get(path);
        List<String> rawPairs = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            builder.queryParam(entry.getKey(), entry.getValue());
            rawPairs.add(encode(entry.getKey()) + "=" + encode(entry.getValue()));
        }
        return TestSigner.signedGet(builder, path, String.join("&", rawPairs));
    }

    private MockHttpServletRequestBuilder signedDeleteQuery(String path, Map<String, String> params) {
        MockHttpServletRequestBuilder builder = delete(path);
        List<String> rawPairs = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            builder.queryParam(entry.getKey(), entry.getValue());
            rawPairs.add(encode(entry.getKey()) + "=" + encode(entry.getValue()));
        }
        return TestSigner.signedDelete(builder, path, String.join("&", rawPairs));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String recordName(String id) throws Exception {
        return recordOf(ROOT_A_ID, id).name();
    }

    private String storedPathOfIn(String rootId, String id) {
        return recordOf(rootId, id).storedPath();
    }

    private FileRecord recordOf(String rootId, String id) {
        return rootRegistry.requireRoot(rootId).metaStore()
                .findFile(TestSigner.APP_KEY, id)
                .orElseThrow();
    }

    private static List<String> namesOf(JsonNode entries) {
        List<String> names = new ArrayList<>();
        for (JsonNode entry : entries) {
            names.add(entry.path("name").asText());
        }
        return names;
    }

    private static void wipe(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var children = Files.newDirectoryStream(root)) {
            for (Path child : children) {
                if (StoragePaths.INTERNAL_META_DIR.equals(child.getFileName().toString())) {
                    continue;
                }
                deleteRecursively(child);
            }
        }
    }

    private static Path createTempRoot(String prefix) {
        try {
            return Files.createTempDirectory(prefix + "-").toAbsolutePath().normalize();
        } catch (IOException e) {
            throw new IllegalStateException("无法创建测试存储根", e);
        }
    }
}
