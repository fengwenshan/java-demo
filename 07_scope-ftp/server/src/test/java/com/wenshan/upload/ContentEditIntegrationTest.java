package com.wenshan.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenshan.upload.dto.ContentSaveRequest;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 契约 3.18 在线编辑：读取文本 / 原子覆盖保存 / expectedSha256 并发冲突 /
 * 严格 UTF-8 与大小上限 / 路径与 root 隔离 / 元数据 sha+size 同步 / 临时文件清理。
 *
 * <p>本类把 max-edit-bytes 调小（1024）便于用小文件验证超限；其余上限用默认值。</p>
 */
@SpringBootTest(properties = {
        "app.test.suite=content-edit",
        "app.storage.max-edit-bytes=1024"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContentEditIntegrationTest extends IntegrationTestSupport {

    private static final String CONTENT_PATH = "/api/files/content";
    private static final String UPLOAD_PATH = "/api/files/upload";
    private static final String DOWNLOAD_PATH = "/api/files/download";
    private static final String LIST_PATH = "/api/files";

    private static final String ROOT_A_ID = "rootA";
    private static final String ROOT_B_ID = "rootB";
    private static final Path ROOT_A = createTempRoot("content-a");
    private static final Path ROOT_B = createTempRoot("content-b");

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
    void readsUtf8TextWithSizeAndCurrentSha() throws Exception {
        byte[] content = "第一行 hello\n第二行 world\n".getBytes(StandardCharsets.UTF_8);
        uploadData(ROOT_A_ID, "docs", "readme.txt", content);

        mockMvc.perform(signedGetQuery(CONTENT_PATH, Map.of("root", ROOT_A_ID, "path", "docs/readme.txt")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.path").value("docs/readme.txt"))
                .andExpect(jsonPath("$.data.content").value(new String(content, StandardCharsets.UTF_8)))
                .andExpect(jsonPath("$.data.size").value(content.length))
                .andExpect(jsonPath("$.data.sha256").value(TestSigner.sha256(content)))
                .andExpect(jsonPath("$.data.editable").value(true));
    }

    @Test
    void saveOverwritesSamePathAndSyncsShaSize() throws Exception {
        byte[] original = "old content".getBytes(StandardCharsets.UTF_8);
        String id = uploadData(ROOT_A_ID, "docs", "save-sync.txt", original).path("id").asText();
        byte[] updated = "新内容 updated".getBytes(StandardCharsets.UTF_8);
        String oldSha = recordOf(ROOT_A_ID, id).sha256();

        ResultActions saved = saveContent(ROOT_A_ID, "docs/save-sync.txt", "新内容 updated", oldSha)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.path").value("docs/save-sync.txt"))
                .andExpect(jsonPath("$.data.size").value(updated.length))
                .andExpect(jsonPath("$.data.sha256").value(TestSigner.sha256(updated)));

        // 磁盘原地覆盖（同路径、文件名不变）
        assertThat(ROOT_A.resolve("docs/save-sync.txt")).hasBinaryContent(updated);
        assertThat(Files.exists(ROOT_A.resolve("docs/save-sync.txt"))).isTrue();

        // 下载/列表返回新内容与新 size/sha
        mockMvc.perform(signedGetQuery(DOWNLOAD_PATH, Map.of("root", ROOT_A_ID, "path", "docs/save-sync.txt")))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray())
                        .isEqualTo(updated));
        mockMvc.perform(signedGetQuery(LIST_PATH, Map.of("root", ROOT_A_ID, "keyword", "save-sync")))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].size").value(updated.length))
                .andExpect(jsonPath("$.data.items[0].sha256").value(TestSigner.sha256(updated)));

        // 元数据同步：同一 id 的 sha/size 更新，storedPath/name 不变
        FileRecord record = recordOf(ROOT_A_ID, id);
        assertThat(record.sha256()).isEqualTo(TestSigner.sha256(updated));
        assertThat(record.size()).isEqualTo(updated.length);
        assertThat(record.storedPath()).isEqualTo("docs/save-sync.txt");
        assertThat(record.name()).isEqualTo("save-sync.txt");
        assertThat(body(saved).path("data").path("sha256").asText()).isNotEqualTo(oldSha);

        // 原子写不残留临时文件
        try (Stream<Path> children = Files.list(ROOT_A.resolve("docs"))) {
            assertThat(children.map(path -> path.getFileName().toString()))
                    .containsExactly("save-sync.txt");
        }
    }

    @Test
    void saveWithoutExpectedShaSucceeds() throws Exception {
        uploadData(ROOT_A_ID, "docs", "a.txt", "before".getBytes(StandardCharsets.UTF_8));
        saveContent(ROOT_A_ID, "docs/a.txt", "after", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.size").value(5));
        assertThat(ROOT_A.resolve("docs/a.txt")).hasContent("after");
    }

    @Test
    void saveEmptyContentIsAllowed() throws Exception {
        uploadData(ROOT_A_ID, "docs", "empty.txt", "not empty yet".getBytes(StandardCharsets.UTF_8));
        saveContent(ROOT_A_ID, "docs/empty.txt", "", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(0))
                .andExpect(jsonPath("$.data.sha256").value(TestSigner.sha256(new byte[0])));
        assertThat(Files.size(ROOT_A.resolve("docs/empty.txt"))).isZero();
    }

    @Test
    void expectedShaMismatchRejectsWithoutWriting() throws Exception {
        uploadData(ROOT_A_ID, "docs", "readme.txt", "v1".getBytes(StandardCharsets.UTF_8));
        // 先保存一次，旧 sha 立刻过期
        saveContent(ROOT_A_ID, "docs/readme.txt", "v2", TestSigner.sha256("v1"))
                .andExpect(status().isOk());
        String staleSha = TestSigner.sha256("v1");

        saveContent(ROOT_A_ID, "docs/readme.txt", "v3", staleSha)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("文件已被修改")));
        assertThat(ROOT_A.resolve("docs/readme.txt")).hasContent("v2");
    }

    @Test
    void binaryAndOversizedFilesAreRejected() throws Exception {
        // 非法 UTF-8（二进制）
        byte[] binary = new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, 0x01};
        Files.write(ROOT_A.resolve("docs/binary.bin"), binary);
        mockMvc.perform(signedGetQuery(CONTENT_PATH, Map.of("root", ROOT_A_ID, "path", "docs/binary.bin")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("二进制或非 UTF-8")));

        // 超过 max-edit-bytes（本类配置为 1024）
        byte[] big = new byte[1500];
        java.util.Arrays.fill(big, (byte) 'a');
        Files.write(ROOT_A.resolve("docs/big.txt"), big);
        mockMvc.perform(signedGetQuery(CONTENT_PATH, Map.of("root", ROOT_A_ID, "path", "docs/big.txt")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("文件过大")));

        // 保存内容超限同样拒绝，且不写入
        uploadData(ROOT_A_ID, "docs", "small.txt", "ok".getBytes(StandardCharsets.UTF_8));
        saveContent(ROOT_A_ID, "docs/small.txt", "x".repeat(2000), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("文件过大")));
        assertThat(ROOT_A.resolve("docs/small.txt")).hasContent("ok");
    }

    @Test
    void rejectsUnsafeMissingAndCrossRootTargets() throws Exception {
        uploadData(ROOT_A_ID, "docs", "readme.txt", "hello".getBytes(StandardCharsets.UTF_8));
        uploadData(ROOT_B_ID, "docs", "b.txt", "root-b".getBytes(StandardCharsets.UTF_8));
        // symlink 指向 root 内真实文件也拒绝（symlink 组件不可信）
        Files.createSymbolicLink(ROOT_A.resolve("docs/link.txt"), ROOT_A.resolve("docs/readme.txt"));

        assertCode(signedGetQuery(CONTENT_PATH, Map.of("root", ROOT_A_ID, "path", "docs")), 40000);
        assertCode(signedGetQuery(CONTENT_PATH, Map.of("root", ROOT_A_ID, "path", "docs/missing.txt")), 40400);
        assertCode(signedGetQuery(CONTENT_PATH, Map.of("root", ROOT_A_ID, "path", "/etc/passwd")), 40000);
        assertCode(signedGetQuery(CONTENT_PATH, Map.of("root", ROOT_A_ID, "path", "../x.txt")), 40000);
        assertCode(signedGetQuery(CONTENT_PATH,
                Map.of("root", ROOT_A_ID, "path", ".upload-meta/files.json")), 40000);
        assertCode(saveContent(ROOT_A_ID, ".upload-meta/metadata.sqlite3", "x", null), 40000);
        assertCode(signedGetQuery(CONTENT_PATH, Map.of("root", "ghost", "path", "docs/readme.txt")), 40000);
        assertCode(signedGetQuery(CONTENT_PATH, Map.of("root", ROOT_A_ID, "path", "docs/link.txt")), 40000);
        // B 根的文件不能用 A 根 + 相对/绝对路径读到
        assertCode(signedGetQuery(CONTENT_PATH,
                Map.of("root", ROOT_A_ID, "path", "../content-b/docs/b.txt")), 40000);
        assertCode(signedGetQuery(CONTENT_PATH,
                Map.of("root", ROOT_A_ID, "path", ROOT_B.resolve("docs/b.txt").toString())), 40000);
        // 但用自己的根可以正常读
        mockMvc.perform(signedGetQuery(CONTENT_PATH, Map.of("root", ROOT_B_ID, "path", "docs/b.txt")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("root-b"));
    }

    @Test
    void saveRejectsUnsafeTargetsAndNeverCreatesNewFile() throws Exception {
        uploadData(ROOT_A_ID, "docs", "readme.txt", "hello".getBytes(StandardCharsets.UTF_8));
        assertCode(saveContent(ROOT_A_ID, "docs", "x", null), 40000);
        assertCode(saveContent(ROOT_A_ID, "docs/missing.txt", "x", null), 40400);
        assertCode(saveContent(ROOT_A_ID, "../outside.txt", "x", null), 40000);
        assertCode(saveContent(ROOT_A_ID, ".upload-meta/files.json", "x", null), 40000);
        assertCode(saveContent("ghost", "docs/readme.txt", "x", null), 40000);
        assertThat(ROOT_A.resolve("docs/missing.txt")).doesNotExist();
        assertThat(ROOT_A.resolve("docs/readme.txt")).hasContent("hello");
    }

    @Test
    void saveUpdatesExistingRecordButDoesNotCreateRecordForUnrecordedFile() throws Exception {
        // 直接落盘（无元数据记录）：保存后内容变化，但不新增 files.json 记录
        Files.writeString(ROOT_A.resolve("docs/raw.txt"), "raw", StandardCharsets.UTF_8);
        long recordsBefore = recordCount(ROOT_A_ID);
        saveContent(ROOT_A_ID, "docs/raw.txt", "raw-updated", null)
                .andExpect(status().isOk());
        assertThat(ROOT_A.resolve("docs/raw.txt")).hasContent("raw-updated");
        assertThat(recordCount(ROOT_A_ID)).isEqualTo(recordsBefore);

        // 有记录的仍正常同步
        String id = uploadData(ROOT_A_ID, "docs", "tracked.txt", "tracked".getBytes(StandardCharsets.UTF_8))
                .path("id").asText();
        saveContent(ROOT_A_ID, "docs/tracked.txt", "tracked-v2", null).andExpect(status().isOk());
        assertThat(recordOf(ROOT_A_ID, id).size()).isEqualTo("tracked-v2".length());
        assertThat(recordOf(ROOT_A_ID, id).sha256()).isEqualTo(TestSigner.sha256("tracked-v2"));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private ResultActions saveContent(String root, String path, String content, String expectedSha)
            throws Exception {
        ContentSaveRequest request = new ContentSaveRequest(root, path, content, expectedSha);
        byte[] body = objectMapper.writeValueAsBytes(request);
        return mockMvc.perform(TestSigner.signedJsonPut(put(CONTENT_PATH), CONTENT_PATH, body));
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

    private void assertCode(MockHttpServletRequestBuilder request, int code) throws Exception {
        mockMvc.perform(request)
                .andExpect(jsonPath("$.code").value(code));
    }

    private static void assertCode(ResultActions result, int code) throws Exception {
        result.andExpect(jsonPath("$.code").value(code));
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

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private FileRecord recordOf(String rootId, String id) {
        return rootRegistry.requireRoot(rootId).metaStore()
                .findFile(TestSigner.APP_KEY, id)
                .orElseThrow();
    }

    private long recordCount(String rootId) {
        return rootRegistry.requireRoot(rootId).metaStore().filesOf(TestSigner.APP_KEY).size();
    }

    private static void wipe(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            Files.createDirectories(root);
            return;
        }
        // 保留内部元数据目录：MetaStore 持久化依赖它（与其它集成测试一致）
        try (Stream<Path> children = Files.list(root)) {
            for (Path child : children.toList()) {
                if (StoragePaths.INTERNAL_META_DIR.equals(child.getFileName().toString())) {
                    continue;
                }
                deleteRecursively(child);
            }
        }
    }

    private static Path createTempRoot(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
