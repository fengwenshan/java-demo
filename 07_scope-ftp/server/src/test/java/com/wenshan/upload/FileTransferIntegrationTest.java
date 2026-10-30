package com.wenshan.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenshan.upload.dto.CopyRequest;
import com.wenshan.upload.dto.MoveRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 契约 3.22/3.23：文件/目录拷贝（_副本 命名/编号、递归、失败回滚、不写元数据）与移动
 * （同根/跨根、目录前缀、元数据 rootId/storedPath 同步、冲突/自身子目录/越界拒绝）。
 */
@SpringBootTest(properties = "app.test.suite=file-transfer")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FileTransferIntegrationTest extends IntegrationTestSupport {

    private static final String COPY_PATH = "/api/files/copy";
    private static final String MOVE_PATH = "/api/files/move";
    private static final String UPLOAD_PATH = "/api/files/upload";
    private static final String DOWNLOAD_PATH = "/api/files/download";
    private static final String DIR_PATH = "/api/files/dir";
    private static final String LIST_PATH = "/api/files";

    private static final String ROOT_A_ID = "rootA";
    private static final String ROOT_B_ID = "rootB";
    private static final Path ROOT_A = createTempRoot("transfer-a");
    private static final Path ROOT_B = createTempRoot("transfer-b");

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
    void copyFileAddsSuffixAndDoesNotWriteMetadata() throws Exception {
        byte[] content = "copy me".getBytes(StandardCharsets.UTF_8);
        String id = uploadData(ROOT_A_ID, "docs", "a.txt", content).path("id").asText();
        int recordsBefore = records(ROOT_A_ID).size();

        copy(ROOT_A_ID, "docs/a.txt")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.root").value(ROOT_A_ID))
                .andExpect(jsonPath("$.data.path").value("docs/a_副本.txt"))
                .andExpect(jsonPath("$.data.type").value("file"))
                .andExpect(jsonPath("$.data.name").value("a_副本.txt"));
        assertThat(ROOT_A.resolve("docs/a_副本.txt")).hasBinaryContent(content);
        assertThat(ROOT_A.resolve("docs/a.txt")).hasBinaryContent(content);
        // 拷贝不写 files.json，原记录不变
        assertThat(records(ROOT_A_ID)).hasSize(recordsBefore);
        assertThat(recordOf(ROOT_A_ID, id).storedPath()).isEqualTo("docs/a.txt");
        mockMvc.perform(signedGetQuery(DOWNLOAD_PATH, Map.of("root", ROOT_A_ID, "path", "docs/a_副本.txt")))
                .andExpect(status().isOk());

        copy(ROOT_A_ID, "docs/a.txt")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.path").value("docs/a_副本 (1).txt"));
        copy(ROOT_A_ID, "docs/a.txt")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.path").value("docs/a_副本 (2).txt"));
        assertThat(ROOT_A.resolve("docs/a_副本 (2).txt")).hasBinaryContent(content);
    }

    @Test
    void copyNameRulesForNoExtensionDotfileAndMultiExtension() throws Exception {
        uploadData(ROOT_A_ID, "docs", "README", "readme".getBytes(StandardCharsets.UTF_8));
        uploadData(ROOT_A_ID, "docs", ".env", "env".getBytes(StandardCharsets.UTF_8));
        uploadData(ROOT_A_ID, "docs", "archive.tar.gz", "archive".getBytes(StandardCharsets.UTF_8));

        copy(ROOT_A_ID, "docs/README")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("README_副本"));
        copy(ROOT_A_ID, "docs/.env")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value(".env_副本"));
        copy(ROOT_A_ID, "docs/archive.tar.gz")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("archive.tar_副本.gz"));
        copy(ROOT_A_ID, "docs/archive.tar.gz")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("archive.tar_副本 (1).gz"));

        assertThat(ROOT_A.resolve("docs/README_副本")).hasContent("readme");
        assertThat(ROOT_A.resolve("docs/.env_副本")).hasContent("env");
        assertThat(ROOT_A.resolve("docs/archive.tar_副本.gz")).hasContent("archive");
    }

    @Test
    void copyDirectoryRecursivelyIncludesEmptyDirsAndNumbering() throws Exception {
        Files.createDirectories(ROOT_A.resolve("docs/pkg/empty"));
        Files.createDirectories(ROOT_A.resolve("docs/pkg/sub"));
        Files.writeString(ROOT_A.resolve("docs/pkg/a.txt"), "a");
        Files.writeString(ROOT_A.resolve("docs/pkg/sub/b.txt"), "b");

        copy(ROOT_A_ID, "docs/pkg")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("directory"))
                .andExpect(jsonPath("$.data.name").value("pkg_副本"))
                .andExpect(jsonPath("$.data.path").value("docs/pkg_副本"));
        assertThat(ROOT_A.resolve("docs/pkg_副本/a.txt")).hasContent("a");
        assertThat(ROOT_A.resolve("docs/pkg_副本/sub/b.txt")).hasContent("b");
        assertThat(ROOT_A.resolve("docs/pkg_副本/empty")).isDirectory();
        // 拷贝不写元数据：目标路径下没有对应的 files.json 记录
        assertThat(records(ROOT_A_ID))
                .noneMatch(record -> record.storedPath().startsWith("docs/pkg_副本/"));

        copy(ROOT_A_ID, "docs/pkg")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("pkg_副本 (1)"));
        assertThat(ROOT_A.resolve("docs/pkg_副本 (1)/sub/b.txt")).hasContent("b");
    }

    @Test
    void copyRejectsSymlinksAndRollsBack() throws Exception {
        Files.createDirectories(ROOT_A.resolve("docs/real/sub"));
        Files.writeString(ROOT_A.resolve("docs/real/a.txt"), "a");
        Files.createSymbolicLink(ROOT_A.resolve("docs/real/sub/link"), Path.of("/etc/hosts"));
        copy(ROOT_A_ID, "docs/real")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("符号链接")));
        assertThat(ROOT_A.resolve("docs/real_副本")).doesNotExist();
        assertThat(ROOT_A.resolve("docs/real/a.txt")).hasContent("a");

        // 源本身是 symlink：路径校验直接拒绝
        Files.createSymbolicLink(ROOT_A.resolve("docs/leaf.txt"), Path.of("/etc/hosts"));
        copy(ROOT_A_ID, "docs/leaf.txt")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        assertThat(ROOT_A.resolve("docs/leaf_副本.txt")).doesNotExist();

        copy(ROOT_A_ID, ".upload-meta/files.json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void copyRejectsInvalidPathsAndUnknownRoot() throws Exception {
        uploadData(ROOT_A_ID, "docs", "safe.txt", "safe".getBytes(StandardCharsets.UTF_8));
        copy(ROOT_A_ID, "docs/missing.txt")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
        for (String badPath : new String[]{ROOT_A.resolve("docs/safe.txt").toString(), "../escape.txt", ".upload-meta"}) {
            copy(ROOT_A_ID, badPath)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
        copy("ghost", "docs/safe.txt")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        assertThat(ROOT_A.resolve("docs/safe.txt")).hasContent("safe");
        assertThat(ROOT_A.resolve("docs/safe_副本.txt")).doesNotExist();
    }

    @Test
    void moveFileSameRootUpdatesMetadataAndDownload() throws Exception {
        byte[] content = "move me".getBytes(StandardCharsets.UTF_8);
        String id = uploadData(ROOT_A_ID, "docs", "a.txt", content).path("id").asText();
        Files.createDirectories(ROOT_A.resolve("docs/dest"));

        move(ROOT_A_ID, "docs/a.txt", null, "docs/dest")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.root").value(ROOT_A_ID))
                .andExpect(jsonPath("$.data.path").value("docs/dest/a.txt"))
                .andExpect(jsonPath("$.data.type").value("file"))
                .andExpect(jsonPath("$.data.name").value("a.txt"));
        assertThat(ROOT_A.resolve("docs/a.txt")).doesNotExist();
        assertThat(ROOT_A.resolve("docs/dest/a.txt")).hasBinaryContent(content);
        FileRecord record = recordOf(ROOT_A_ID, id);
        assertThat(record.storedPath()).isEqualTo("docs/dest/a.txt");
        assertThat(record.name()).isEqualTo("a.txt");
        assertThat(record.rootId()).isEqualTo(ROOT_A_ID);

        mockMvc.perform(signedGetQuery(DOWNLOAD_PATH, Map.of("root", ROOT_A_ID, "path", "docs/dest/a.txt")))
                .andExpect(status().isOk());
        mockMvc.perform(TestSigner.signedGet("/api/files/" + id + "/download", null))
                .andExpect(status().isOk());
        mockMvc.perform(signedGetQuery(LIST_PATH, Map.of("root", ROOT_A_ID, "keyword", "a.txt")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].rootId").value(ROOT_A_ID))
                .andExpect(jsonPath("$.data.items[0].name").value("a.txt"));

        // 再移动到根目录（targetDir=""）
        move(ROOT_A_ID, "docs/dest/a.txt", null, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.path").value("a.txt"));
        assertThat(ROOT_A.resolve("a.txt")).hasBinaryContent(content);
        assertThat(recordOf(ROOT_A_ID, id).storedPath()).isEqualTo("a.txt");
    }

    @Test
    void moveDirectorySameRootRewritesMetadataPrefix() throws Exception {
        byte[] a = "a".getBytes(StandardCharsets.UTF_8);
        byte[] b = "b".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(ROOT_A.resolve("docs/pkg/sub"));
        String idA = uploadData(ROOT_A_ID, "docs/pkg", "a.txt", a).path("id").asText();
        String idB = uploadData(ROOT_A_ID, "docs/pkg/sub", "b.txt", b).path("id").asText();
        Files.createDirectories(ROOT_A.resolve("docs/archive"));

        move(ROOT_A_ID, "docs/pkg", null, "docs/archive")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("directory"))
                .andExpect(jsonPath("$.data.path").value("docs/archive/pkg"))
                .andExpect(jsonPath("$.data.name").value("pkg"));
        assertThat(ROOT_A.resolve("docs/pkg")).doesNotExist();
        assertThat(ROOT_A.resolve("docs/archive/pkg/a.txt")).hasBinaryContent(a);
        assertThat(ROOT_A.resolve("docs/archive/pkg/sub/b.txt")).hasBinaryContent(b);
        assertThat(recordOf(ROOT_A_ID, idA).storedPath()).isEqualTo("docs/archive/pkg/a.txt");
        assertThat(recordOf(ROOT_A_ID, idB).storedPath()).isEqualTo("docs/archive/pkg/sub/b.txt");
        mockMvc.perform(signedGetQuery(DIR_PATH, Map.of("root", ROOT_A_ID, "path", "docs/archive")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries[?(@.name=='pkg')]").exists());
    }

    @Test
    void moveFileCrossRootUpdatesRootIdAndMetadata() throws Exception {
        byte[] content = "cross".getBytes(StandardCharsets.UTF_8);
        String id = uploadData(ROOT_A_ID, "docs", "a.txt", content).path("id").asText();
        Files.createDirectories(ROOT_B.resolve("sub"));

        move(ROOT_A_ID, "docs/a.txt", ROOT_B_ID, "sub")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.root").value(ROOT_B_ID))
                .andExpect(jsonPath("$.data.path").value("sub/a.txt"))
                .andExpect(jsonPath("$.data.type").value("file"))
                .andExpect(jsonPath("$.data.name").value("a.txt"));
        assertThat(ROOT_A.resolve("docs/a.txt")).doesNotExist();
        assertThat(ROOT_B.resolve("sub/a.txt")).hasBinaryContent(content);
        assertThat(rootRegistry.requireRoot(ROOT_A_ID).metaStore()
                .findFile(TestSigner.APP_KEY, id)).isEmpty();
        FileRecord moved = recordOf(ROOT_B_ID, id);
        assertThat(moved.storedPath()).isEqualTo("sub/a.txt");
        assertThat(moved.rootId()).isEqualTo(ROOT_B_ID);
        assertThat(moved.name()).isEqualTo("a.txt");

        mockMvc.perform(signedGetQuery(DOWNLOAD_PATH, Map.of("root", ROOT_B_ID, "path", "sub/a.txt")))
                .andExpect(status().isOk());
        mockMvc.perform(TestSigner.signedGet("/api/files/" + id + "/download", null))
                .andExpect(status().isOk());
        mockMvc.perform(signedGetQuery(LIST_PATH, Map.of("root", ROOT_B_ID, "keyword", "a.txt")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.id=='" + id + "')].rootId").value(ROOT_B_ID));
    }

    @Test
    void moveDirectoryCrossRootMovesAllRecords() throws Exception {
        byte[] a = "a".getBytes(StandardCharsets.UTF_8);
        byte[] b = "b".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(ROOT_A.resolve("docs/pkg/sub"));
        String idA = uploadData(ROOT_A_ID, "docs/pkg", "a.txt", a).path("id").asText();
        String idB = uploadData(ROOT_A_ID, "docs/pkg/sub", "b.txt", b).path("id").asText();

        move(ROOT_A_ID, "docs/pkg", ROOT_B_ID, "docs")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.root").value(ROOT_B_ID))
                .andExpect(jsonPath("$.data.path").value("docs/pkg"));
        assertThat(ROOT_A.resolve("docs/pkg")).doesNotExist();
        assertThat(ROOT_B.resolve("docs/pkg/a.txt")).hasBinaryContent(a);
        assertThat(ROOT_B.resolve("docs/pkg/sub/b.txt")).hasBinaryContent(b);
        assertThat(rootRegistry.requireRoot(ROOT_A_ID).metaStore()
                .findFile(TestSigner.APP_KEY, idA)).isEmpty();
        assertThat(rootRegistry.requireRoot(ROOT_A_ID).metaStore()
                .findFile(TestSigner.APP_KEY, idB)).isEmpty();
        assertThat(recordOf(ROOT_B_ID, idA).storedPath()).isEqualTo("docs/pkg/a.txt");
        assertThat(recordOf(ROOT_B_ID, idA).rootId()).isEqualTo(ROOT_B_ID);
        assertThat(recordOf(ROOT_B_ID, idB).storedPath()).isEqualTo("docs/pkg/sub/b.txt");
        mockMvc.perform(TestSigner.signedGet("/api/files/" + idB + "/download", null))
                .andExpect(status().isOk());
    }

    @Test
    void moveRejectsSelfSubdirectoryConflictAndMissingTarget() throws Exception {
        Files.createDirectories(ROOT_A.resolve("docs/pkg/sub"));
        Files.writeString(ROOT_A.resolve("docs/pkg/a.txt"), "a");

        move(ROOT_A_ID, "docs/pkg", null, "docs/pkg/sub")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("自身或子目录")));
        move(ROOT_A_ID, "docs/pkg", null, "docs/pkg")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("自身或子目录")));
        assertThat(ROOT_A.resolve("docs/pkg/a.txt")).hasContent("a");

        // 目标位置已存在同名目录/文件 → 40000，源保留
        Files.createDirectories(ROOT_A.resolve("docs/other/pkg"));
        move(ROOT_A_ID, "docs/pkg", null, "docs/other")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("已存在同名文件或目录")));
        assertThat(ROOT_A.resolve("docs/pkg/a.txt")).hasContent("a");
        assertThat(ROOT_A.resolve("docs/other/pkg/a.txt")).doesNotExist();

        move(ROOT_A_ID, "docs/pkg", null, "docs/missing-dir")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("目标目录不存在")));

        Files.writeString(ROOT_A.resolve("docs/plain.txt"), "plain");
        move(ROOT_A_ID, "docs/pkg", null, "docs/plain.txt")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("目标不是目录")));

        // 文件移动到自身所在目录：目标同名 → 40000
        uploadData(ROOT_A_ID, "docs", "self.txt", "self".getBytes(StandardCharsets.UTF_8));
        move(ROOT_A_ID, "docs/self.txt", null, "docs")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("已存在同名文件或目录")));
        assertThat(ROOT_A.resolve("docs/self.txt")).hasContent("self");
    }

    @Test
    void moveRejectsInvalidPathsSymlinksMetadataAndUnknownRoots() throws Exception {
        byte[] content = "safe".getBytes(StandardCharsets.UTF_8);
        String safeId = uploadData(ROOT_A_ID, "docs", "safe.txt", content).path("id").asText();
        assertThat(recordOf(ROOT_A_ID, safeId).storedPath()).isEqualTo("docs/safe.txt");

        for (String badPath : new String[]{ROOT_A.resolve("docs/safe.txt").toString(), "../safe.txt", ".upload-meta"}) {
            move(ROOT_A_ID, badPath, null, "docs")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
        move(ROOT_A_ID, "docs/safe.txt", "ghost", "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        move("ghost", "docs/safe.txt", null, "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        move(ROOT_A_ID, "docs/safe.txt", null, "../escape")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        Files.createSymbolicLink(ROOT_A.resolve("docs/link.txt"), Path.of("/etc/hosts"));
        move(ROOT_A_ID, "docs/link.txt", null, "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        assertThat(ROOT_A.resolve("docs/safe.txt")).hasBinaryContent(content);
        assertThat(ROOT_A.resolve("safe.txt")).doesNotExist();
        assertThat(recordOf(ROOT_A_ID, safeId).storedPath()).isEqualTo("docs/safe.txt");
    }

    @Test
    void copyAndMoveWithoutSignatureRejected() throws Exception {
        byte[] copyBody = objectMapper.writeValueAsBytes(new CopyRequest(ROOT_A_ID, "docs/a.txt"));
        mockMvc.perform(post(COPY_PATH).contentType("application/json").content(copyBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
        byte[] moveBody = objectMapper.writeValueAsBytes(
                new MoveRequest(ROOT_A_ID, "docs/a.txt", null, ""));
        mockMvc.perform(post(MOVE_PATH).contentType("application/json").content(moveBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    // ------------------------------------------------------------------

    private ResultActions copy(String root, String path) throws Exception {
        byte[] body = objectMapper.writeValueAsBytes(new CopyRequest(root, path));
        return mockMvc.perform(TestSigner.signedJsonPost(post(COPY_PATH), COPY_PATH, body));
    }

    private ResultActions move(String root, String path, String targetRoot, String targetDir) throws Exception {
        byte[] body = objectMapper.writeValueAsBytes(
                new MoveRequest(root, path, targetRoot, targetDir));
        return mockMvc.perform(TestSigner.signedJsonPost(post(MOVE_PATH), MOVE_PATH, body));
    }

    private JsonNode uploadData(String root, String dir, String fileName, byte[] content) throws Exception {
        MockMultipartHttpServletRequestBuilder builder = multipart(UPLOAD_PATH)
                .file(new MockMultipartFile("file", fileName, "application/octet-stream", content));
        builder.param("name", fileName);
        if (root != null) {
            builder.param("root", root);
        }
        if (dir != null) {
            builder.param("dir", dir);
        }
        ResultActions result = mockMvc.perform(TestSigner.signedMultipart(builder, UPLOAD_PATH, content, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        return body(result).path("data");
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

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private List<FileRecord> records(String rootId) {
        return rootRegistry.requireRoot(rootId).metaStore().filesOf(TestSigner.APP_KEY);
    }

    private FileRecord recordOf(String rootId, String id) {
        return rootRegistry.requireRoot(rootId).metaStore()
                .findFile(TestSigner.APP_KEY, id)
                .orElseThrow();
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
