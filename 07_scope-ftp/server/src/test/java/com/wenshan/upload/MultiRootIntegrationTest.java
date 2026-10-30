package com.wenshan.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenshan.upload.dto.ChunkInitRequest;
import com.wenshan.upload.storage.StoragePaths;
import com.wenshan.upload.storage.StorageRootRegistry;
import com.wenshan.upload.store.FileRecord;
import com.wenshan.upload.store.UploadSessionStore.UploadSessionMeta;
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
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 多存储根 + 严格 root 隔离：
 * <ul>
 *   <li>两个临时根 rootA/rootB，roots 接口与 verify 暴露配置；</li>
 *   <li>上传/浏览/下载/删除/搜索/分片都按 root 落到各自根，同名文件互不影响；</li>
 *   <li>跨根路径（..、绝对路径、symlink）全部 40000 且目标文件不受影响；</li>
 *   <li>按 id 操作按记录 rootId 解析；伪造/不一致的 rootId 只报错不删除任何文件；</li>
 *   <li>非法 root id 在所有接口上 40000。</li>
 * </ul>
 */
@SpringBootTest(properties = "app.test.suite=multi-root")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MultiRootIntegrationTest extends IntegrationTestSupport {

    private static final String ROOT_A_ID = "rootA";
    private static final String ROOT_B_ID = "rootB";
    private static final String UPLOAD_PATH = "/api/files/upload";
    private static final String INIT_PATH = "/api/files/upload/init";
    private static final String LIST_PATH = "/api/files";
    private static final String DIR_PATH = "/api/files/dir";
    private static final String DOWNLOAD_PATH = "/api/files/download";
    private static final String SEARCH_PATH = "/api/files/search";

    private static final Path ROOT_A = createTempRoot("root-a");
    private static final Path ROOT_B = createTempRoot("root-b");

    @DynamicPropertySource
    static void registerRoots(DynamicPropertyRegistry registry) {
        registry.add("app.storage.roots[0].id", () -> ROOT_A_ID);
        registry.add("app.storage.roots[0].name", () -> "根 A");
        registry.add("app.storage.roots[0].path", ROOT_A::toString);
        registry.add("app.storage.roots[1].id", () -> ROOT_B_ID);
        registry.add("app.storage.roots[1].name", () -> "根 B");
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
    void rootsEndpointAndVerifyExposeConfiguredRoots() throws Exception {
        mockMvc.perform(TestSigner.signedGet("/api/storage/roots", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.defaultRootId").value(ROOT_A_ID))
                .andExpect(jsonPath("$.data.roots", hasSize(2)))
                .andExpect(jsonPath("$.data.roots[0].id").value(ROOT_A_ID))
                .andExpect(jsonPath("$.data.roots[0].name").value("根 A"))
                .andExpect(jsonPath("$.data.roots[0].path").value(ROOT_A.toString()))
                .andExpect(jsonPath("$.data.roots[1].id").value(ROOT_B_ID))
                .andExpect(jsonPath("$.data.roots[1].name").value("根 B"))
                .andExpect(jsonPath("$.data.roots[1].path").value(ROOT_B.toString()));

        mockMvc.perform(TestSigner.signedGet("/api/auth/verify", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultRootId").value(ROOT_A_ID))
                .andExpect(jsonPath("$.data.storageDir").value(ROOT_A.toString()))
                .andExpect(jsonPath("$.data.roots", hasSize(2)))
                .andExpect(jsonPath("$.data.roots[1].id").value(ROOT_B_ID))
                .andExpect(jsonPath("$.data.limits.maxFileSize").value(1_048_576));
    }

    @Test
    void uploadsLandInRequestedRootAndListFiltersByRoot() throws Exception {
        String stem = "same-" + UUID.randomUUID().toString().substring(0, 8);
        byte[] contentA = "content-of-A".getBytes(StandardCharsets.UTF_8);
        byte[] contentB = "content-of-B".getBytes(StandardCharsets.UTF_8);

        JsonNode metaA = uploadData(ROOT_A_ID, "docs", stem + ".txt", contentA);
        JsonNode metaB = uploadData(ROOT_B_ID, "docs", stem + ".txt", contentB);
        assertThat(metaA.path("rootId").asText()).isEqualTo(ROOT_A_ID);
        assertThat(metaB.path("rootId").asText()).isEqualTo(ROOT_B_ID);

        assertThat(ROOT_A.resolve("docs").resolve(stem + ".txt")).hasContent("content-of-A");
        assertThat(ROOT_B.resolve("docs").resolve(stem + ".txt")).hasContent("content-of-B");

        // 列表：不传 root 聚合所有根；传 root 只返回该根
        ResultActions all = mockMvc.perform(signedQuery(get(LIST_PATH),
                        LIST_PATH, Map.of("keyword", stem)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));
        assertThat(rootIdsOf(body(all).path("data").path("items")))
                .containsExactlyInAnyOrder(ROOT_A_ID, ROOT_B_ID);

        ResultActions onlyB = mockMvc.perform(signedQuery(get(LIST_PATH),
                        LIST_PATH, Map.of("keyword", stem, "root", ROOT_B_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].rootId").value(ROOT_B_ID))
                .andExpect(jsonPath("$.data.items[0].name").value(stem + ".txt"));

        // 路径下载按 root 解析
        ResultActions downloadB = mockMvc.perform(signedQuery(get(DOWNLOAD_PATH),
                        DOWNLOAD_PATH, Map.of("root", ROOT_B_ID, "path", "docs/" + stem + ".txt")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Sha256", TestSigner.sha256(contentB)));
        assertThat(downloadB.andReturn().getResponse().getContentAsByteArray()).isEqualTo(contentB);
    }

    @Test
    void byIdOperationsUseRecordRootId() throws Exception {
        String stem = "byid-" + UUID.randomUUID().toString().substring(0, 8);
        byte[] contentA = "A-content".getBytes(StandardCharsets.UTF_8);
        byte[] contentB = "B-content".getBytes(StandardCharsets.UTF_8);
        String idA = uploadData(ROOT_A_ID, "docs", stem + ".txt", contentA).path("id").asText();
        String idB = uploadData(ROOT_B_ID, "docs", stem + ".txt", contentB).path("id").asText();

        // 删除 rootB 的记录：只删 B 的文件，A 的同名文件不受影响
        mockMvc.perform(TestSigner.signedDelete(LIST_PATH + "/" + idB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(idB));
        assertThat(ROOT_B.resolve("docs").resolve(stem + ".txt")).doesNotExist();
        assertThat(ROOT_A.resolve("docs").resolve(stem + ".txt")).hasContent("A-content");

        // 按 id 下载 rootA 的文件
        ResultActions download = mockMvc.perform(TestSigner.signedGet(LIST_PATH + "/" + idA + "/download", null))
                .andExpect(status().isOk());
        assertThat(download.andReturn().getResponse().getContentAsByteArray()).isEqualTo(contentA);

        // 删除 rootA 的记录后 A 文件消失
        mockMvc.perform(TestSigner.signedDelete(LIST_PATH + "/" + idA))
                .andExpect(status().isOk());
        assertThat(ROOT_A.resolve("docs").resolve(stem + ".txt")).doesNotExist();
    }

    @Test
    void crossRootPathAttacksAreRejectedAndTargetUntouched() throws Exception {
        byte[] secret = "rootB-secret".getBytes(StandardCharsets.UTF_8);
        uploadData(ROOT_B_ID, "docs", "secret.txt", secret);
        String bName = ROOT_B.getFileName().toString();
        String escapedPath = "../" + bName + "/docs/secret.txt";
        String absolutePath = ROOT_B.resolve("docs/secret.txt").toString();

        for (String badPath : List.of(escapedPath, absolutePath, "docs/../../" + bName + "/docs/secret.txt")) {
            mockMvc.perform(signedQuery(get(DOWNLOAD_PATH),
                            DOWNLOAD_PATH, Map.of("root", ROOT_A_ID, "path", badPath)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
            mockMvc.perform(signedDeleteQuery(delete(LIST_PATH),
                            LIST_PATH, Map.of("root", ROOT_A_ID, "path", badPath)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }

        // symlink 指向 rootB：rootA 下访问必须被拒绝（不跟随）
        Files.createSymbolicLink(ROOT_A.resolve("link-to-b"), ROOT_B);
        mockMvc.perform(signedQuery(get(DOWNLOAD_PATH),
                        DOWNLOAD_PATH, Map.of("root", ROOT_A_ID, "path", "link-to-b/docs/secret.txt")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(signedDeleteQuery(delete(LIST_PATH),
                        LIST_PATH, Map.of("root", ROOT_A_ID, "path", "link-to-b/docs/secret.txt")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        // 目标文件必须仍然存在且内容不变
        assertThat(ROOT_B.resolve("docs").resolve("secret.txt")).hasBinaryContent(secret);
    }

    @Test
    void forgedRootIdMetadataCannotDeleteAnyFile() throws Exception {
        String name = "victim-" + UUID.randomUUID().toString().substring(0, 8) + ".txt";
        byte[] content = "victim-content".getBytes(StandardCharsets.UTF_8);
        String id = uploadData(ROOT_A_ID, "docs", name, content).path("id").asText();
        FileRecord original = rootRegistry.requireRoot(ROOT_A_ID).metaStore()
                .findFile(TestSigner.APP_KEY, id).orElseThrow();

        // 1) 伪造不存在的 rootId：报错且不删除
        rootRegistry.requireRoot(ROOT_A_ID).metaStore().saveFile(withRootId(original, "ghost"));
        mockMvc.perform(TestSigner.signedDelete(LIST_PATH + "/" + id))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(50000));
        assertThat(ROOT_A.resolve("docs").resolve(name)).hasBinaryContent(content);

        // 2) 伪造为另一个已配置根的 rootId（但该根没有这条记录）：报错且不删除
        rootRegistry.requireRoot(ROOT_A_ID).metaStore().saveFile(withRootId(original, ROOT_B_ID));
        mockMvc.perform(TestSigner.signedDelete(LIST_PATH + "/" + id))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(50000));
        assertThat(ROOT_A.resolve("docs").resolve(name)).hasBinaryContent(content);

        // 3) 恢复真实记录后按 rootId 正常删除
        rootRegistry.requireRoot(ROOT_A_ID).metaStore().saveFile(original);
        mockMvc.perform(TestSigner.signedDelete(LIST_PATH + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id));
        assertThat(ROOT_A.resolve("docs").resolve(name)).doesNotExist();
    }

    @Test
    void searchOnlyMatchesRequestedRoot() throws Exception {
        String stem = "needle" + UUID.randomUUID().toString().substring(0, 8);
        uploadData(ROOT_A_ID, "docs", stem + "-a.txt", "a".getBytes(StandardCharsets.UTF_8));
        uploadData(ROOT_B_ID, "docs", stem + "-b.txt", "b".getBytes(StandardCharsets.UTF_8));

        ResultActions onlyA = mockMvc.perform(signedQuery(get(SEARCH_PATH),
                        SEARCH_PATH, Map.of("root", ROOT_A_ID, "keyword", stem)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries", hasSize(1)));
        JsonNode aEntry = body(onlyA).path("data").path("entries").get(0);
        assertThat(aEntry.path("name").asText()).isEqualTo(stem + "-a.txt");
        assertThat(aEntry.path("path").asText()).isEqualTo("docs/" + stem + "-a.txt");

        ResultActions onlyB = mockMvc.perform(signedQuery(get(SEARCH_PATH),
                        SEARCH_PATH, Map.of("root", ROOT_B_ID, "keyword", stem)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries", hasSize(1)));
        assertThat(body(onlyB).path("data").path("entries").get(0).path("name").asText())
                .isEqualTo(stem + "-b.txt");

        // 不传 root 只搜默认根 rootA（不会串到 rootB）
        ResultActions defaultRoot = mockMvc.perform(signedQuery(get(SEARCH_PATH),
                        SEARCH_PATH, Map.of("keyword", stem)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries", hasSize(1)));
        assertThat(body(defaultRoot).path("data").path("entries").get(0).path("name").asText())
                .isEqualTo(stem + "-a.txt");
    }

    @Test
    void directoryBrowseIsRootScoped() throws Exception {
        uploadData(ROOT_A_ID, "docs", "a-only.txt", "a".getBytes(StandardCharsets.UTF_8));
        uploadData(ROOT_B_ID, "docs", "b-only.txt", "b".getBytes(StandardCharsets.UTF_8));

        ResultActions dirA = mockMvc.perform(signedQuery(get(DIR_PATH),
                        DIR_PATH, Map.of("root", ROOT_A_ID, "path", "docs")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.path").value("docs"));
        assertThat(namesOf(body(dirA).path("data").path("entries"))).contains("a-only.txt")
                .doesNotContain("b-only.txt");

        ResultActions dirB = mockMvc.perform(signedQuery(get(DIR_PATH),
                        DIR_PATH, Map.of("root", ROOT_B_ID, "path", "docs")))
                .andExpect(status().isOk());
        assertThat(namesOf(body(dirB).path("data").path("entries"))).contains("b-only.txt")
                .doesNotContain("a-only.txt");
    }

    @Test
    void unknownRootIdRejectedEverywhere() throws Exception {
        mockMvc.perform(signedQuery(get(LIST_PATH), LIST_PATH, Map.of("root", "ghost")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(signedQuery(get(DIR_PATH), DIR_PATH, Map.of("root", "ghost")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(signedQuery(get(DOWNLOAD_PATH), DOWNLOAD_PATH,
                        Map.of("root", "ghost", "path", "docs/x.txt")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(signedDeleteQuery(delete(LIST_PATH), LIST_PATH,
                        Map.of("root", "ghost", "path", "docs/x.txt")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(signedQuery(get(SEARCH_PATH), SEARCH_PATH,
                        Map.of("root", "ghost", "keyword", "x")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        MockMultipartHttpServletRequestBuilder multipartBuilder = multipart(UPLOAD_PATH)
                .file(new MockMultipartFile("file", "x.txt", "text/plain", content));
        multipartBuilder.param("name", "x.txt");
        multipartBuilder.param("root", "ghost");
        mockMvc.perform(TestSigner.signedMultipart(multipartBuilder, UPLOAD_PATH, content, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        byte[] initBody = objectMapper.writeValueAsBytes(new ChunkInitRequest(
                "x.txt", "docs", 1L, TestSigner.sha256(content), null, "ghost"));
        mockMvc.perform(TestSigner.signedJsonPost(post(INIT_PATH), INIT_PATH, initBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void chunkedUploadLandsInRequestedRoot() throws Exception {
        String name = "chunk-" + UUID.randomUUID().toString().substring(0, 8) + ".bin";
        byte[] content = new byte[256 * 1024];
        new java.util.Random(42).nextBytes(content);
        String sha = TestSigner.sha256(content);
        byte[] initBody = objectMapper.writeValueAsBytes(new ChunkInitRequest(
                name, "docs", (long) content.length, sha, "多根分片", ROOT_B_ID));

        ResultActions init = mockMvc.perform(TestSigner.signedJsonPost(post(INIT_PATH), INIT_PATH, initBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.chunkSize").isNumber());
        JsonNode initData = body(init).path("data");
        String uploadId = initData.path("uploadId").asText();
        assertThat(initData.path("chunkSize").asInt()).isPositive();

        // 会话 meta 必须记录 rootId（后续 chunk/complete 不依赖请求再传 root）
        UploadSessionMeta session = uploadSessionStore.loadMeta(uploadId).orElseThrow();
        assertThat(session.rootId()).isEqualTo(ROOT_B_ID);

        mockMvc.perform(TestSigner.signedChunkPut(uploadId, 0, content))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.received").value(1));
        mockMvc.perform(TestSigner.signedGet("/api/files/upload/" + uploadId, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.received", hasSize(1)))
                .andExpect(jsonPath("$.data.received[0]").value(0));

        ResultActions complete = mockMvc.perform(
                        TestSigner.signedPost("/api/files/upload/" + uploadId + "/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rootId").value(ROOT_B_ID))
                .andExpect(jsonPath("$.data.sha256").value(sha));
        assertThat(complete.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains(name);

        assertThat(ROOT_B.resolve("docs").resolve(name)).hasBinaryContent(content);
        assertThat(ROOT_A.resolve("docs").resolve(name)).doesNotExist();

        // 分片 init 的 dir 校验按目标根进行
        byte[] badDir = objectMapper.writeValueAsBytes(new ChunkInitRequest(
                "x.bin", "no-such-dir", 10L, TestSigner.sha256(new byte[]{1}), null, ROOT_B_ID));
        mockMvc.perform(TestSigner.signedJsonPost(post(INIT_PATH), INIT_PATH, badDir))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void uploadDirectoryValidationIsPerRoot() throws Exception {
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);

        // rootA 没有 docs2 目录（rootB 有）→ 40400 而不是串到 rootB
        Files.createDirectories(ROOT_B.resolve("docs2"));
        MockMultipartHttpServletRequestBuilder missingDir = multipart(UPLOAD_PATH)
                .file(new MockMultipartFile("file", "x.txt", "text/plain", content));
        missingDir.param("name", "x.txt");
        missingDir.param("root", ROOT_A_ID);
        missingDir.param("dir", "docs2");
        mockMvc.perform(TestSigner.signedMultipart(missingDir, UPLOAD_PATH, content, null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));

        // 绝对路径 / .. 越界 → 40000
        for (String badDir : List.of("..", ROOT_B.resolve("docs2").toString())) {
            MockMultipartHttpServletRequestBuilder bad = multipart(UPLOAD_PATH)
                    .file(new MockMultipartFile("file", "x.txt", "text/plain", content));
            bad.param("name", "x.txt");
            bad.param("root", ROOT_A_ID);
            bad.param("dir", badDir);
            mockMvc.perform(TestSigner.signedMultipart(bad, UPLOAD_PATH, content, null))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
        // 失败的上传不会在任一目标根落下文件
        assertThat(ROOT_A.resolve("docs2")).doesNotExist();
        assertThat(ROOT_B.resolve("docs2").toFile().list()).isEmpty();
    }

    // ------------------------------------------------------------------

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

    private MockHttpServletRequestBuilder signedQuery(MockHttpServletRequestBuilder builder,
                                                      String path, Map<String, String> params) {
        if (params.isEmpty()) {
            return TestSigner.signedGet(builder, path, null);
        }
        List<String> rawPairs = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            builder.queryParam(entry.getKey(), entry.getValue());
            rawPairs.add(encode(entry.getKey()) + "=" + encode(entry.getValue()));
        }
        return TestSigner.signedGet(builder, path, String.join("&", rawPairs));
    }

    private MockHttpServletRequestBuilder signedDeleteQuery(MockHttpServletRequestBuilder builder,
                                                            String path, Map<String, String> params) {
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

    private static List<String> rootIdsOf(JsonNode items) {
        List<String> ids = new ArrayList<>();
        for (JsonNode item : items) {
            ids.add(item.path("rootId").asText());
        }
        return ids;
    }

    private static List<String> namesOf(JsonNode entries) {
        List<String> names = new ArrayList<>();
        for (JsonNode entry : entries) {
            names.add(entry.path("name").asText());
        }
        return names;
    }

    private static FileRecord withRootId(FileRecord record, String rootId) {
        return new FileRecord(record.id(), record.appKey(), record.deviceId(), record.name(),
                record.description(), record.size(), record.contentType(), record.sha256(),
                record.storedPath(), record.createdAt(), rootId);
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
