package com.wenshan.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenshan.upload.dto.ExtractRequest;
import com.wenshan.upload.storage.StoragePaths;
import com.wenshan.upload.storage.StorageRootRegistry;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 契约 3.19 zip 安全解压：正常嵌套解压 / 重名目录后缀 / zip-slip、绝对路径、反斜杠、
 * .upload-meta、symlink、加密、非 zip、损坏 zip 拒绝且回滚 / entry 数、总量、单文件上限 /
 * 空 zip / 重复 entry / root 隔离；单层包裹目录自动剥层（有/无/多顶层/单根文件/中文包裹/剥层后 zip-slip 回滚）。
 *
 * <p>本类把解压各上限调小（entries=4、total=1000、single=500）便于用小 zip 验证超限。</p>
 */
@SpringBootTest(properties = {
        "app.test.suite=zip-extract",
        "app.storage.max-extract-entries=4",
        "app.storage.max-extract-total-bytes=1000",
        "app.storage.max-extract-entry-bytes=500"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ZipExtractIntegrationTest extends IntegrationTestSupport {

    private static final String EXTRACT_PATH = "/api/files/extract";
    private static final String UPLOAD_PATH = "/api/files/upload";
    private static final String CONTENT_PATH = "/api/files/content";
    private static final String DOWNLOAD_PATH = "/api/files/download";
    private static final String DIR_PATH = "/api/files/dir";
    private static final String SEARCH_PATH = "/api/files/search";

    private static final String ROOT_A_ID = "rootA";
    private static final String ROOT_B_ID = "rootB";
    private static final Path ROOT_A = createTempRoot("extract-a");
    private static final Path ROOT_B = createTempRoot("extract-b");

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
    void extractsNestedZipToSiblingDirectoryWithoutMetadataRecord() throws Exception {
        byte[] zip = zipBytes(
                entry("readme.txt", "hello"),
                new Entry("sub/", null),
                entry("sub/inner.txt", "inner"));
        uploadData(ROOT_A_ID, "docs", "包.zip", zip);
        long recordsBefore = metaCount(ROOT_A_ID);

        ResultActions result = extract(ROOT_A_ID, "docs/包.zip")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.targetDir").value("docs/包"))
                .andExpect(jsonPath("$.data.entries").value(2))
                .andExpect(jsonPath("$.data.bytes").value(10));

        assertThat(ROOT_A.resolve("docs/包/readme.txt")).hasContent("hello");
        assertThat(ROOT_A.resolve("docs/包/sub/inner.txt")).hasContent("inner");
        // 解压产生的文件不写 files.json（只有原 zip 的 1 条记录）
        assertThat(metaCount(ROOT_A_ID)).isEqualTo(recordsBefore);

        // 目录浏览/下载/在线编辑/搜索都能正常使用
        mockMvc.perform(signedGetQuery(DIR_PATH, Map.of("root", ROOT_A_ID, "path", "docs/包")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.path").value("docs/包"))
                .andExpect(jsonPath("$.data.entries[?(@.name=='readme.txt')]").exists())
                .andExpect(jsonPath("$.data.entries[?(@.name=='sub')]").exists());
        mockMvc.perform(signedGetQuery(DOWNLOAD_PATH, Map.of("root", ROOT_A_ID, "path", "docs/包/sub/inner.txt")))
                .andExpect(status().isOk())
                .andExpect(result2 -> assertThat(result2.getResponse().getContentAsString())
                        .isEqualTo("inner"));
        mockMvc.perform(signedGetQuery(CONTENT_PATH, Map.of("root", ROOT_A_ID, "path", "docs/包/readme.txt")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("hello"))
                .andExpect(jsonPath("$.data.editable").value(true));
        mockMvc.perform(signedGetQuery(SEARCH_PATH, Map.of("root", ROOT_A_ID, "keyword", "inner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries[?(@.name=='inner.txt')]").exists());
    }

    @Test
    void existingTargetDirectoryGetsNumericSuffixAndIsNeverOverwritten() throws Exception {
        byte[] zip = zipBytes(entry("a.txt", "zip-a"));
        Files.createDirectories(ROOT_A.resolve("docs/pkg"));
        Files.writeString(ROOT_A.resolve("docs/pkg/important.txt"), "keep me", StandardCharsets.UTF_8);
        uploadData(ROOT_A_ID, "docs", "pkg.zip", zip);

        extract(ROOT_A_ID, "docs/pkg.zip")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetDir").value("docs/pkg (1)"));
        assertThat(ROOT_A.resolve("docs/pkg/important.txt")).hasContent("keep me");
        assertThat(ROOT_A.resolve("docs/pkg (1)/a.txt")).hasContent("zip-a");

        extract(ROOT_A_ID, "docs/pkg.zip")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetDir").value("docs/pkg (2)"));
        assertThat(ROOT_A.resolve("docs/pkg (2)/a.txt")).hasContent("zip-a");

        // 顶层 zip（dir 为空）解压到 root 同级目录
        uploadData(ROOT_A_ID, null, "top.zip", zip);
        extract(ROOT_A_ID, "top.zip")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetDir").value("top"));
        assertThat(ROOT_A.resolve("top/a.txt")).hasContent("zip-a");
    }

    @Test
    void zipSlipAbsoluteBackslashAndMetaEntriesRejectedWithRollback() throws Exception {
        assertRejectedWithRollback("slip.zip", zipBytes(entry("../evil.txt", "x")),
                "docs/slip", "非法路径段");
        assertThat(ROOT_A.resolve("evil.txt")).doesNotExist();
        assertThat(ROOT_A.resolve("docs/evil.txt")).doesNotExist();

        assertRejectedWithRollback("abs.zip", zipBytes(entry("/evil-abs.txt", "x")),
                "docs/abs", "绝对路径");
        assertThat(Path.of("/evil-abs.txt")).doesNotExist();

        assertRejectedWithRollback("back.zip", zipBytes(entry("a\\b.txt", "x")),
                "docs/back", "非法");
        assertRejectedWithRollback("meta.zip", zipBytes(entry(".upload-meta/files.json", "x")),
                "docs/meta", "元数据目录");
        assertRejectedWithRollback("nested-meta.zip", zipBytes(entry("a/.upload-meta/x.json", "x")),
                "docs/nested-meta", "元数据目录");
    }

    @Test
    void symlinkAndEncryptedEntriesRejectedBeforeTargetIsCreated() throws Exception {
        // Unix mode 0120777 → symlink entry
        byte[] symlinkZip = rawZip(0, 0xA1FF, "link.txt", "target.txt".getBytes(StandardCharsets.UTF_8));
        uploadData(ROOT_A_ID, "docs", "sym.zip", symlinkZip);
        extract(ROOT_A_ID, "docs/sym.zip")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("符号链接")));
        assertThat(ROOT_A.resolve("docs/sym")).doesNotExist();
        assertThat(ROOT_A.resolve("docs/link.txt")).doesNotExist();

        // 加密位（general purpose flag bit0）
        byte[] encryptedZip = rawZip(1, 0, "secret.txt", "data".getBytes(StandardCharsets.UTF_8));
        uploadData(ROOT_A_ID, "docs", "enc.zip", encryptedZip);
        extract(ROOT_A_ID, "docs/enc.zip")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("加密")));
        assertThat(ROOT_A.resolve("docs/enc")).doesNotExist();
    }

    @Test
    void nonZipCorruptZipAndMissingOrDirectoryPathsRejected() throws Exception {
        uploadData(ROOT_A_ID, "docs", "data.txt", "plain".getBytes(StandardCharsets.UTF_8));
        extract(ROOT_A_ID, "docs/data.txt")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("只支持 .zip 文件")));

        uploadData(ROOT_A_ID, "docs", "bad.zip", "not-a-zip".getBytes(StandardCharsets.UTF_8));
        extract(ROOT_A_ID, "docs/bad.zip")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        assertThat(ROOT_A.resolve("docs/bad")).doesNotExist();

        extract(ROOT_A_ID, "docs/missing.zip")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
        extract(ROOT_A_ID, "docs")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        extract("ghost", "docs/bad.zip")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void emptyZipCreatesEmptyTargetDirectory() throws Exception {
        uploadData(ROOT_A_ID, "docs", "empty.zip", zipBytes());
        extract(ROOT_A_ID, "docs/empty.zip")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetDir").value("docs/empty"))
                .andExpect(jsonPath("$.data.entries").value(0))
                .andExpect(jsonPath("$.data.bytes").value(0));
        assertThat(ROOT_A.resolve("docs/empty")).isDirectory();
        assertThat(entriesOf(ROOT_A.resolve("docs/empty"))).isEmpty();
        mockMvc.perform(signedGetQuery(DIR_PATH, Map.of("root", ROOT_A_ID, "path", "docs/empty")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries").isEmpty());
    }

    @Test
    void entryCountTotalBytesAndSingleEntryLimitsAreEnforcedWithRollback() throws Exception {
        // entries 上限 4：5 个文件
        byte[] many = zipBytes(entry("1.txt", "1"), entry("2.txt", "2"), entry("3.txt", "3"),
                entry("4.txt", "4"), entry("5.txt", "5"));
        assertRejectedWithRollback("many.zip", many, "docs/many", "条目数超过上限");

        // 总字节上限 1000：3 个 400 字节文件（每个 ≤ 单文件上限 500，合计 1200 > 1000）
        byte[] total = zipBytes(entry("a.bin", new byte[400]), entry("b.bin", new byte[400]),
                entry("c.bin", new byte[400]));
        assertRejectedWithRollback("total.zip", total, "docs/total", "总大小超过上限");

        // 单文件上限 500：一个 600 字节文件
        byte[] single = zipBytes(entry("big.bin", new byte[600]));
        assertRejectedWithRollback("big.zip", single, "docs/big", "单个文件超过解压上限");
    }

    @Test
    void duplicateEntryNamesRejected() throws Exception {
        // ZipOutputStream 不允许重复 entry，用中央目录手工构造两个同名 entry
        byte[] duplicate = rawZipEntries(
                new RawEntry("dup.txt", "first".getBytes(StandardCharsets.UTF_8), 0, 0),
                new RawEntry("dup.txt", "second".getBytes(StandardCharsets.UTF_8), 0, 0));
        uploadData(ROOT_A_ID, "docs", "dup.zip", duplicate);
        extract(ROOT_A_ID, "docs/dup.zip")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("重复条目")));
        assertThat(ROOT_A.resolve("docs/dup")).doesNotExist();
    }

    @Test
    void extractionStaysInsideRequestedRoot() throws Exception {
        byte[] zip = zipBytes(entry("b.txt", "root-b"));
        uploadData(ROOT_B_ID, "docs", "pkg.zip", zip);
        extract(ROOT_B_ID, "docs/pkg.zip")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetDir").value("docs/pkg"));
        assertThat(ROOT_B.resolve("docs/pkg/b.txt")).hasContent("root-b");
        assertThat(ROOT_A.resolve("docs/pkg")).doesNotExist();

        // 用 A 根尝试解压 B 根的 zip（相对越界/绝对路径）必须拒绝
        extract(ROOT_A_ID, "../extract-b/docs/pkg.zip")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        extract(ROOT_A_ID, ROOT_B.resolve("docs/pkg.zip").toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        assertThat(ROOT_A.resolve("docs/pkg")).doesNotExist();
    }

    @Test
    void singleTopLevelWrapperDirectoryIsStripped() throws Exception {
        // pkg/a.txt + pkg/sub/b.txt + pkg/empty/：唯一顶层段 pkg 且全在 pkg/ 下 → 剥层
        byte[] zip = zipBytes(
                entry("pkg/a.txt", "hello"),
                entry("pkg/sub/b.txt", "world"),
                new Entry("pkg/empty/", null));
        uploadData(ROOT_A_ID, "docs", "包裹.zip", zip);
        extract(ROOT_A_ID, "docs/包裹.zip")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetDir").value("docs/包裹"))
                .andExpect(jsonPath("$.data.entries").value(2))
                .andExpect(jsonPath("$.data.bytes").value(10));
        assertThat(ROOT_A.resolve("docs/包裹/a.txt")).hasContent("hello");
        assertThat(ROOT_A.resolve("docs/包裹/sub/b.txt")).hasContent("world");
        assertThat(ROOT_A.resolve("docs/包裹/empty")).isDirectory();
        // 外层 pkg 不应落盘
        assertThat(ROOT_A.resolve("docs/包裹/pkg")).doesNotExist();
    }

    @Test
    void noWrapperKeepsOriginalLayout() throws Exception {
        // 根下直接 a.txt + b/c.txt：多顶层段，不剥
        byte[] zip = zipBytes(
                entry("a.txt", "a"),
                new Entry("b/", null),
                entry("b/c.txt", "c"));
        uploadData(ROOT_A_ID, "docs", "普通.zip", zip);
        extract(ROOT_A_ID, "docs/普通.zip")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetDir").value("docs/普通"))
                .andExpect(jsonPath("$.data.entries").value(2));
        assertThat(ROOT_A.resolve("docs/普通/a.txt")).hasContent("a");
        assertThat(ROOT_A.resolve("docs/普通/b/c.txt")).hasContent("c");
    }

    @Test
    void multipleTopLevelDirectoriesAreNotStripped() throws Exception {
        byte[] zip = zipBytes(
                entry("pkg/a.txt", "a"),
                entry("other/b.txt", "b"));
        uploadData(ROOT_A_ID, "docs", "多个.zip", zip);
        extract(ROOT_A_ID, "docs/多个.zip")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetDir").value("docs/多个"));
        assertThat(ROOT_A.resolve("docs/多个/pkg/a.txt")).hasContent("a");
        assertThat(ROOT_A.resolve("docs/多个/other/b.txt")).hasContent("b");
        assertThat(ROOT_A.resolve("docs/多个/a.txt")).doesNotExist();
    }

    @Test
    void singleRootFileIsNotStripped() throws Exception {
        byte[] zip = zipBytes(entry("a.txt", "single"));
        uploadData(ROOT_A_ID, "docs", "单文件.zip", zip);
        extract(ROOT_A_ID, "docs/单文件.zip")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries").value(1))
                .andExpect(jsonPath("$.data.bytes").value(6));
        assertThat(ROOT_A.resolve("docs/单文件/a.txt")).hasContent("single");
    }

    @Test
    void chineseAndSpaceWrapperNameIsStripped() throws Exception {
        byte[] zip = zipBytes(
                entry("中文 包/a.txt", "a"),
                entry("中文 包/sub/b.txt", "b"));
        uploadData(ROOT_A_ID, "docs", "中文包裹.zip", zip);
        extract(ROOT_A_ID, "docs/中文包裹.zip")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetDir").value("docs/中文包裹"))
                .andExpect(jsonPath("$.data.entries").value(2));
        assertThat(ROOT_A.resolve("docs/中文包裹/a.txt")).hasContent("a");
        assertThat(ROOT_A.resolve("docs/中文包裹/sub/b.txt")).hasContent("b");
        assertThat(ROOT_A.resolve("docs/中文包裹/中文 包")).doesNotExist();
    }

    @Test
    void zipSlipInsideWrapperIsRejectedWithRollback() throws Exception {
        // pkg/../../escape.txt：第一段 pkg 会被探测为 wrapper，但剥离前的完整名含 .. 必须拒绝
        byte[] zip = zipBytes(entry("pkg/../../escape.txt", "evil"));
        uploadData(ROOT_A_ID, "docs", "恶意包裹.zip", zip);
        extract(ROOT_A_ID, "docs/恶意包裹.zip")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(containsString("非法路径段")));
        assertThat(ROOT_A.resolve("docs/恶意包裹")).doesNotExist();
        assertThat(ROOT_A.resolve("docs/escape.txt")).doesNotExist();
        assertThat(ROOT_A.resolve("escape.txt")).doesNotExist();
    }

    @Test
    void wrapperDirectoryEntryMapsToTargetRoot() throws Exception {
        // pkg/ 目录 entry 映射 targetDir 本身，不应创建 target/pkg
        byte[] zip = zipBytes(new Entry("pkg/", null), entry("pkg/a.txt", "a"));
        uploadData(ROOT_A_ID, "docs", "目录包裹.zip", zip);
        extract(ROOT_A_ID, "docs/目录包裹.zip")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetDir").value("docs/目录包裹"))
                .andExpect(jsonPath("$.data.entries").value(1));
        assertThat(ROOT_A.resolve("docs/目录包裹/a.txt")).hasContent("a");
        assertThat(ROOT_A.resolve("docs/目录包裹/pkg")).doesNotExist();
    }

    @Test
    void wrapperOnlyEmptyDirectoryStillCreatesEmptyTarget() throws Exception {
        byte[] zip = zipBytes(new Entry("only-empty/", null));
        uploadData(ROOT_A_ID, "docs", "空目录包裹.zip", zip);
        extract(ROOT_A_ID, "docs/空目录包裹.zip")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetDir").value("docs/空目录包裹"))
                .andExpect(jsonPath("$.data.entries").value(0))
                .andExpect(jsonPath("$.data.bytes").value(0));
        assertThat(ROOT_A.resolve("docs/空目录包裹")).isDirectory();
        assertThat(entriesOf(ROOT_A.resolve("docs/空目录包裹"))).isEmpty();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private ResultActions extract(String root, String path) throws Exception {
        ExtractRequest request = new ExtractRequest(root, path);
        byte[] body = objectMapper.writeValueAsBytes(request);
        return mockMvc.perform(TestSigner.signedJsonPost(post(EXTRACT_PATH), EXTRACT_PATH, body));
    }

    private void assertRejectedWithRollback(String zipName, byte[] zip, String targetDir,
                                            String messagePart) throws Exception {
        uploadData(ROOT_A_ID, "docs", zipName, zip);
        ResultActions actions = extract(ROOT_A_ID, "docs/" + zipName)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        if (messagePart != null) {
            actions.andExpect(jsonPath("$.message").value(containsString(messagePart)));
        }
        assertThat(ROOT_A.resolve(targetDir)).doesNotExist();
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

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private long metaCount(String rootId) {
        return rootRegistry.requireRoot(rootId).metaStore().filesOf(TestSigner.APP_KEY).size();
    }

    private static List<String> entriesOf(Path dir) throws IOException {
        try (Stream<Path> children = Files.list(dir)) {
            return children.map(path -> path.getFileName().toString()).toList();
        }
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

    // ---------------- zip 构造工具 ----------------

    private record Entry(String name, byte[] data) {
    }

    private static Entry entry(String name, String text) {
        return new Entry(name, text == null ? null : text.getBytes(StandardCharsets.UTF_8));
    }

    private static Entry entry(String name, byte[] data) {
        return new Entry(name, data);
    }

    /** 标准 zip（可含目录 entry 与同名重复 entry）。 */
    private static byte[] zipBytes(Entry... entries) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Entry entry : entries) {
                zos.putNextEntry(new ZipEntry(entry.name()));
                if (entry.data() != null) {
                    zos.write(entry.data());
                }
                zos.closeEntry();
            }
            zos.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 手工构造单 entry zip：可指定 general purpose flags（bit0=加密）与 Unix mode
     * （external attributes 高 16 位，0xA1FF=符号链接 0120777）。
     */
    private static byte[] rawZip(int flags, int unixMode, String name, byte[] data) {
        return rawZipEntries(new RawEntry(name, data, flags, unixMode));
    }

    /** 手工构造多 entry zip（允许同名重复 entry，用于测试重复条目拒绝）。 */
    private static byte[] rawZipEntries(RawEntry... entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] localOffsets = new int[entries.length];
        int[] crcs = new int[entries.length];
        for (int i = 0; i < entries.length; i++) {
            RawEntry entry = entries[i];
            byte[] nameBytes = entry.name().getBytes(StandardCharsets.UTF_8);
            CRC32 crc = new CRC32();
            crc.update(entry.data());
            crcs[i] = (int) crc.getValue();
            localOffsets[i] = out.size();
            writeInt(out, 0x04034b50);
            writeShort(out, 20);
            writeShort(out, entry.flags());
            writeShort(out, 0);
            writeShort(out, 0);
            writeShort(out, 0);
            writeInt(out, crcs[i]);
            writeInt(out, entry.data().length);
            writeInt(out, entry.data().length);
            writeShort(out, nameBytes.length);
            writeShort(out, 0);
            out.writeBytes(nameBytes);
            out.writeBytes(entry.data());
        }
        int centralOffset = out.size();
        for (int i = 0; i < entries.length; i++) {
            RawEntry entry = entries[i];
            byte[] nameBytes = entry.name().getBytes(StandardCharsets.UTF_8);
            writeInt(out, 0x02014b50);
            writeShort(out, (3 << 8) | 20);
            writeShort(out, 20);
            writeShort(out, entry.flags());
            writeShort(out, 0);
            writeShort(out, 0);
            writeShort(out, 0);
            writeInt(out, crcs[i]);
            writeInt(out, entry.data().length);
            writeInt(out, entry.data().length);
            writeShort(out, nameBytes.length);
            writeShort(out, 0);
            writeShort(out, 0);
            writeShort(out, 0);
            writeShort(out, 0);
            writeInt(out, (entry.unixMode() & 0xFFFF) << 16);
            writeInt(out, localOffsets[i]);
            out.writeBytes(nameBytes);
        }
        int centralSize = out.size() - centralOffset;
        writeInt(out, 0x06054b50);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, entries.length);
        writeShort(out, entries.length);
        writeInt(out, centralSize);
        writeInt(out, centralOffset);
        writeShort(out, 0);
        return out.toByteArray();
    }

    private record RawEntry(String name, byte[] data, int flags, int unixMode) {
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }
}
