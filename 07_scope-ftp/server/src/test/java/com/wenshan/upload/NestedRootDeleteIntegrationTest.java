package com.wenshan.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshan.upload.security.NonceCache;
import com.wenshan.upload.security.RateLimitService;
import com.wenshan.upload.storage.StoragePaths;
import com.wenshan.upload.storage.StorageRootRegistry;
import com.wenshan.upload.store.FileRecord;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 递归删除的嵌套存储根防护：rootB 位于 rootA 内部时，递归删除 rootA 中包含 rootB 的目录
 * 必须 40000 且不删除任何内容（否则会连 rootB 的 .upload-meta 与用户数据一起删掉）。
 *
 * <p>本测试不继承 {@code IntegrationTestSupport}：它的「清空默认根用户内容」会把嵌套的 rootB
 * 一起删掉，因此这里自己按「保留 rootB + 两个根的 .upload-meta」的规则清理，并手动清
 * nonce 缓存 / 令牌桶。</p>
 */
@SpringBootTest(properties = "app.test.suite=nested-root-delete")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NestedRootDeleteIntegrationTest {

    private static final String ROOT_A_ID = "rootA";
    private static final String ROOT_B_ID = "rootB";
    private static final String DELETE_PATH = "/api/files";
    private static final String DOWNLOAD_PATH = "/api/files/download";
    private static final String UPLOAD_PATH = "/api/files/upload";

    private static final Path ROOT_A = createTempRoot("nested-delete-a");
    /** rootB 的父目录（rootA 下），必须在 Spring 上下文启动前存在。 */
    private static final Path OUTER = ROOT_A.resolve("outer");
    /** 嵌套在 rootA 内部的第二个存储根。 */
    private static final Path ROOT_B = OUTER.resolve("nested");

    static {
        try {
            // StoragePaths 初始化前先创建父目录，rootB 本身由注册表启动时创建
            Files.createDirectories(OUTER);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建嵌套存储根的父目录", e);
        }
    }

    @DynamicPropertySource
    static void registerRoots(DynamicPropertyRegistry registry) {
        registry.add("app.storage.roots[0].id", () -> ROOT_A_ID);
        registry.add("app.storage.roots[0].path", ROOT_A::toString);
        registry.add("app.storage.roots[1].id", () -> ROOT_B_ID);
        registry.add("app.storage.roots[1].path", ROOT_B::toString);
    }

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NonceCache nonceCache;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private StorageRootRegistry rootRegistry;

    @BeforeEach
    void resetState() throws IOException {
        nonceCache.clear();
        rateLimitService.clear();
        wipeKeepingNestedRoot();
    }

    @Test
    void deletingDirectoryContainingNestedRootIsRejectedAndNothingDeleted() throws Exception {
        byte[] aContent = "root-a-content".getBytes(StandardCharsets.UTF_8);
        byte[] bContent = "root-b-content".getBytes(StandardCharsets.UTF_8);
        byte[] looseContent = "outer-loose".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(ROOT_A.resolve("docs"));
        Files.createDirectories(ROOT_B.resolve("docs"));
        String idA = idOf(upload(ROOT_A_ID, "docs", "a.txt", aContent));
        String idB = idOf(upload(ROOT_B_ID, "docs", "b.txt", bContent));
        String idLoose = idOf(upload(ROOT_A_ID, "outer", "loose.txt", looseContent));

        // 父目录 "outer" 与嵌套根本身 "outer/nested" 都被拦截：40000 + 明确中文提示
        for (String target : List.of("outer", "outer/nested")) {
            mockMvc.perform(signedDeleteQuery(DELETE_PATH,
                            Map.of("root", ROOT_A_ID, "path", target, "recursive", "true")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000))
                    .andExpect(jsonPath("$.message").value(containsString("目录包含其他存储根")));
        }

        // 不删任何内容：rootA 用户内容、outer 内散落文件、rootB 用户内容与两个根的元数据都还在
        assertThat(ROOT_A.resolve("docs/a.txt")).hasBinaryContent(aContent);
        assertThat(ROOT_A.resolve("outer/loose.txt")).hasBinaryContent(looseContent);
        assertThat(ROOT_B.resolve("docs/b.txt")).hasBinaryContent(bContent);
        assertThat(ROOT_B.resolve(StoragePaths.INTERNAL_META_DIR)).isDirectory();
        assertThat(ROOT_A.resolve(StoragePaths.INTERNAL_META_DIR)).isDirectory();
        assertThat(findRecord(ROOT_A_ID, idA)).isNotNull();
        assertThat(findRecord(ROOT_A_ID, idLoose)).isNotNull();
        assertThat(findRecord(ROOT_B_ID, idB)).isNotNull();

        // 嵌套根仍然完全可用（按路径下载 rootB 文件）
        mockMvc.perform(signedGetQuery(DOWNLOAD_PATH,
                        Map.of("root", ROOT_B_ID, "path", "docs/b.txt")))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------

    private ResultActions upload(String root, String dir, String fileName, byte[] content) throws Exception {
        MockMultipartHttpServletRequestBuilder builder = multipart(UPLOAD_PATH)
                .file(new MockMultipartFile("file", fileName, "application/octet-stream", content));
        builder.param("name", fileName);
        builder.param("root", root);
        if (dir != null) {
            builder.param("dir", dir);
        }
        return mockMvc.perform(TestSigner.signedMultipart(builder, UPLOAD_PATH, content, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    private String idOf(ResultActions actions) throws Exception {
        JsonNode json = objectMapper.readTree(
                actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        return json.path("data").path("id").asText();
    }

    private MockHttpServletRequestBuilder signedGetQuery(String path, Map<String, String> params) {
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

    private FileRecord findRecord(String rootId, String id) {
        return rootRegistry.requireRoot(rootId).metaStore()
                .findFile(TestSigner.APP_KEY, id)
                .orElse(null);
    }

    // ------------------------------------------------------------------
    // 清理：保留 rootB（嵌套根）与两个根的 .upload-meta
    // ------------------------------------------------------------------

    private static void wipeKeepingNestedRoot() throws IOException {
        wipeChildren(ROOT_B);
        wipeChildren(ROOT_A, OUTER.getFileName().toString());
        if (Files.isDirectory(OUTER)) {
            try (var children = Files.newDirectoryStream(OUTER)) {
                for (Path child : children) {
                    if (child.toAbsolutePath().normalize()
                            .equals(ROOT_B.toAbsolutePath().normalize())) {
                        continue;
                    }
                    deleteRecursively(child);
                }
            }
        }
    }

    private static void wipeChildren(Path root, String... keepNames) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var children = Files.newDirectoryStream(root)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (StoragePaths.INTERNAL_META_DIR.equals(name)) {
                    continue;
                }
                boolean keep = false;
                for (String keepName : keepNames) {
                    if (keepName.equals(name)) {
                        keep = true;
                        break;
                    }
                }
                if (!keep) {
                    deleteRecursively(child);
                }
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
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

    private static Path createTempRoot(String prefix) {
        try {
            return Files.createTempDirectory(prefix + "-").toAbsolutePath().normalize();
        } catch (IOException e) {
            throw new IllegalStateException("无法创建测试存储根", e);
        }
    }
}
