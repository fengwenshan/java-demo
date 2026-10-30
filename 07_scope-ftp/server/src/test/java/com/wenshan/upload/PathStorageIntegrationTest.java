package com.wenshan.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenshan.upload.security.SignatureService;
import com.wenshan.upload.support.IntegrationTestSupport;
import com.wenshan.upload.support.TestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FTP 式存储：按目录上传、重名后缀、隐蔽元数据目录、路径式下载/删除。
 */
@SpringBootTest(properties = "app.test.suite=path-storage")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PathStorageIntegrationTest extends IntegrationTestSupport {

    private static final String UPLOAD_PATH = "/api/files/upload";
    private static final String DOWNLOAD_PATH = "/api/files/download";
    private static final String FILES_PATH = "/api/files";

    @Test
    void uploadToSubdirectoryLandsThereAndCanBeDownloaded() throws Exception {
        Files.createDirectories(storagePaths.root().resolve("docs"));
        byte[] content = "subdir content".getBytes(StandardCharsets.UTF_8);

        ResultActions uploadResult = upload("docs", "report.pdf", "report.pdf", content)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("report.pdf"))
                .andExpect(jsonPath("$.data.size").value(content.length));
        String id = body(uploadResult).path("data").path("id").asText();
        assertThat(storedPathOf(id)).isEqualTo("docs/report.pdf");
        String sha = TestSigner.sha256(content);

        // 文件以原名落在 root/docs 下
        assertThat(storagePaths.root().resolve("docs/report.pdf")).hasBinaryContent(content);

        // 按 id 下载仍可用
        mockMvc.perform(TestSigner.signedGet(FILES_PATH + "/" + id + "/download", null))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Sha256", sha))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        // 路径式下载：响应头与按 id 下载一致
        ResultActions pathResult = mockMvc.perform(downloadPath("docs/report.pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Sha256", sha))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, String.valueOf(content.length)))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("report.pdf")));
        assertThat(pathResult.andReturn().getResponse().getContentAsByteArray()).isEqualTo(content);
    }

    @Test
    void duplicateNamesGetNumberedSuffix() throws Exception {
        Files.createDirectories(storagePaths.root().resolve("docs"));
        byte[] first = "first content".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second content".getBytes(StandardCharsets.UTF_8);

        ResultActions firstResult = upload("docs", "report.pdf", "report.pdf", first)
                .andExpect(status().isOk());
        ResultActions secondResult = upload("docs", "report.pdf", "report.pdf", second)
                .andExpect(status().isOk())
                // 展示名保持原文件名
                .andExpect(jsonPath("$.data.name").value("report.pdf"));
        String firstId = body(firstResult).path("data").path("id").asText();
        String secondId = body(secondResult).path("data").path("id").asText();
        assertThat(storedPathOf(firstId)).isEqualTo("docs/report.pdf");
        assertThat(storedPathOf(secondId)).isEqualTo("docs/report (1).pdf");

        assertThat(storagePaths.root().resolve("docs/report.pdf")).hasBinaryContent(first);
        assertThat(storagePaths.root().resolve("docs/report (1).pdf")).hasBinaryContent(second);

        // 元数据两条记录
        mockMvc.perform(TestSigner.signedGet(get(FILES_PATH)
                        .queryParam("page", "1").queryParam("size", "20")
                        .queryParam("keyword", "report"),
                FILES_PATH, "keyword=report&page=1&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));
    }

    @Test
    void uploadWithoutDirLandsInRoot() throws Exception {
        byte[] content = "root content".getBytes(StandardCharsets.UTF_8);
        ResultActions result = upload(null, "root-file.txt", "root-file.txt", content)
                .andExpect(status().isOk());
        String id = body(result).path("data").path("id").asText();
        assertThat(storedPathOf(id)).isEqualTo("root-file.txt");
        assertThat(storagePaths.root().resolve("root-file.txt")).hasBinaryContent(content);
    }

    @Test
    void uploadToNestedExistingSubdirectoryWorks() throws Exception {
        Files.createDirectories(storagePaths.root().resolve("docs/2026"));
        byte[] content = "nested content".getBytes(StandardCharsets.UTF_8);
        ResultActions result = upload("docs/2026", "nested.txt", "nested.txt", content)
                .andExpect(status().isOk());
        String id = body(result).path("data").path("id").asText();
        assertThat(storedPathOf(id)).isEqualTo("docs/2026/nested.txt");
        assertThat(storagePaths.root().resolve("docs/2026/nested.txt")).hasBinaryContent(content);
    }

    @Test
    void invalidUploadDirRejected() throws Exception {
        Files.createDirectories(storagePaths.root().resolve("docs"));
        Files.writeString(storagePaths.root().resolve("plain.txt"), "not a dir");
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);

        for (String badDir : new String[]{"../x", "/etc", ".upload-meta"}) {
            upload(badDir, "a.txt", "a.txt", content)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
        upload("missing-dir", "a.txt", "a.txt", content)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
        upload("plain.txt", "a.txt", "a.txt", content)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        // 失败的上传不留下文件（root 下只有测试预置的 plain.txt）
        assertThat(countStoredFiles()).isEqualTo(1);
    }

    @Test
    void pathDownloadEdgeCases() throws Exception {
        Files.createDirectories(storagePaths.root().resolve("docs"));
        byte[] content = "download content".getBytes(StandardCharsets.UTF_8);
        upload("docs", "a.txt", "a.txt", content).andExpect(status().isOk());

        // 目录、内部目录、越界 → 40000
        for (String badPath : new String[]{"docs", "../etc/passwd", ".upload-meta",
                ".upload-meta/files.json", ".upload-meta/metadata.sqlite3"}) {
            mockMvc.perform(downloadPath(badPath))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
        mockMvc.perform(downloadPath("missing.txt"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
        ResultActions ok = mockMvc.perform(downloadPath("docs/a.txt"))
                .andExpect(status().isOk());
        assertThat(ok.andReturn().getResponse().getContentAsByteArray()).isEqualTo(content);
    }

    @Test
    void pathDeleteEdgeCases() throws Exception {
        Files.createDirectories(storagePaths.root().resolve("docs"));
        byte[] content = "delete content".getBytes(StandardCharsets.UTF_8);
        ResultActions uploadResult = upload("docs", "b.txt", "b.txt", content)
                .andExpect(status().isOk());
        String id = body(uploadResult).path("data").path("id").asText();
        assertThat(storedPathOf(id)).isEqualTo("docs/b.txt");

        for (String badPath : new String[]{"docs", "../etc/passwd", ".upload-meta",
                ".upload-meta/files.json", ".upload-meta/metadata.sqlite3"}) {
            mockMvc.perform(deletePath(badPath))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
        mockMvc.perform(deletePath("missing.txt"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));

        mockMvc.perform(deletePath("docs/b.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.path").value("docs/b.txt"));
        assertThat(storagePaths.root().resolve("docs/b.txt")).doesNotExist();
        // 元数据记录一并删除
        mockMvc.perform(TestSigner.signedGet(get(FILES_PATH)
                        .queryParam("page", "1").queryParam("size", "20")
                        .queryParam("keyword", "b.txt"),
                FILES_PATH, "keyword=b.txt&page=1&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        // 再次删除 → 40400
        mockMvc.perform(deletePath("docs/b.txt"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void browseHidesInternalMetaDirAndRootHasNoLegacyDirs() throws Exception {
        Files.createDirectories(storagePaths.root().resolve("docs"));
        upload("docs", "c.txt", "c.txt", "c".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isOk());

        ResultActions browse = mockMvc.perform(TestSigner.signedGet("/api/files/dir", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        List<String> rootNames = new ArrayList<>();
        for (JsonNode entry : body(browse).path("data").path("entries")) {
            rootNames.add(entry.path("name").asText());
        }
        assertThat(rootNames).contains("docs").doesNotContain(".upload-meta", "files", "meta", "tmp");
        mockMvc.perform(TestSigner.signedGet(get("/api/files/dir").queryParam("path", ".upload-meta"),
                        "/api/files/dir", "path=.upload-meta"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        // root 下只有用户目录 + .upload-meta，绝不出现旧布局目录
        List<String> children = new ArrayList<>();
        try (var stream = Files.list(storagePaths.root())) {
            stream.forEach(path -> children.add(path.getFileName().toString()));
        }
        assertThat(children).contains("docs", ".upload-meta")
                .doesNotContain("files", "meta", "tmp");
        // multipart 临时目录在系统临时目录下
        assertThat(storagePaths.tmpDir().toString())
                .isEqualTo(Path.of(System.getProperty("java.io.tmpdir"), "scop-ftp-tmp").toString());
        assertThat(storagePaths.tmpDir()).isDirectory();
    }

    // ------------------------------------------------------------------

    private ResultActions upload(String dir, String name, String fileName, byte[] content) throws Exception {
        MockMultipartHttpServletRequestBuilder builder = multipart(UPLOAD_PATH)
                .file(new MockMultipartFile("file", fileName, "application/octet-stream", content));
        builder.param("name", name);
        if (dir != null) {
            builder.param("dir", dir);
        }
        return mockMvc.perform(TestSigner.signedMultipart(builder, UPLOAD_PATH, content, null));
    }

    private MockHttpServletRequestBuilder downloadPath(String path) {
        String rawQuery = "path=" + URLEncoder.encode(path, StandardCharsets.UTF_8);
        return TestSigner.signedGet(get(DOWNLOAD_PATH).queryParam("path", path), DOWNLOAD_PATH, rawQuery);
    }

    private MockHttpServletRequestBuilder deletePath(String path) {
        String rawQuery = "path=" + URLEncoder.encode(path, StandardCharsets.UTF_8);
        return TestSigner.withSignature(delete(FILES_PATH).queryParam("path", path),
                "DELETE", FILES_PATH, rawQuery, SignatureService.emptyPayloadHash(),
                TestSigner.APP_KEY, TestSigner.APP_SECRET, TestSigner.now(), TestSigner.newNonce(),
                null, null);
    }
}
