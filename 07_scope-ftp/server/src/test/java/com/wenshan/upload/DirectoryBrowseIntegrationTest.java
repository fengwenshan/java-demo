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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 只读目录浏览接口 GET /api/files/dir：FTP 式 root、子目录、越界拒绝、隐藏 .upload-meta、排序与截断。
 */
@SpringBootTest(properties = "app.test.suite=directory-browse")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DirectoryBrowseIntegrationTest extends IntegrationTestSupport {

    private static final String DIR_PATH = "/api/files/dir";

    @Test
    void rootListsUserContentAndHidesInternalMetaDir() throws Exception {
        Files.createDirectories(storagePaths.root().resolve("docs"));
        Files.createDirectories(storagePaths.root().resolve("empty-dir"));
        Files.writeString(storagePaths.root().resolve("note.txt"), "note");

        ResultActions result = mockMvc.perform(dirRequest(null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.path").value(""))
                .andExpect(jsonPath("$.data.parent").value(nullValue()))
                .andExpect(jsonPath("$.data.truncated").value(false));

        List<String> names = new ArrayList<>();
        boolean noteSeen = false;
        for (JsonNode entry : body(result).path("data").path("entries")) {
            String name = entry.path("name").asText();
            names.add(name);
            assertThat(entry.path("path").asText()).isEqualTo(name);
            if ("note.txt".equals(name)) {
                noteSeen = true;
                assertThat(entry.path("type").asText()).isEqualTo("file");
                assertThat(entry.path("size").asLong()).isEqualTo(4);
            }
        }
        assertThat(names).contains("docs", "empty-dir", "note.txt");
        assertThat(names).doesNotContain(".upload-meta");
        assertThat(noteSeen).isTrue();
        // 目录在前（docs、empty-dir 都是目录），文件在后，各自按名称忽略大小写升序
        assertThat(names.indexOf("docs")).isLessThan(names.indexOf("note.txt"));
        assertThat(names.indexOf("empty-dir")).isLessThan(names.indexOf("note.txt"));
    }

    @Test
    void listsUploadedFileInSubdirectory() throws Exception {
        Files.createDirectories(storagePaths.root().resolve("docs"));
        byte[] content = "dir browse content".getBytes(StandardCharsets.UTF_8);
        MockMultipartHttpServletRequestBuilder uploadBuilder = multipart("/api/files/upload")
                .file(new MockMultipartFile("file", "browse.txt", "text/plain", content));
        uploadBuilder.param("name", "browse.txt");
        uploadBuilder.param("dir", "docs");
        ResultActions uploadResult = mockMvc.perform(
                        TestSigner.signedMultipart(uploadBuilder, "/api/files/upload", content, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        String uploadId = body(uploadResult).path("data").path("id").asText();
        assertThat(storedPathOf(uploadId)).isEqualTo("docs/browse.txt");

        ResultActions result = mockMvc.perform(dirRequest("docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.path").value("docs"))
                .andExpect(jsonPath("$.data.parent").value(""))
                .andExpect(jsonPath("$.data.truncated").value(false));
        JsonNode matched = null;
        for (JsonNode entry : body(result).path("data").path("entries")) {
            if ("browse.txt".equals(entry.path("name").asText())) {
                matched = entry;
            }
        }
        assertThat(matched).isNotNull();
        assertThat(matched.path("type").asText()).isEqualTo("file");
        assertThat(matched.path("path").asText()).isEqualTo("docs/browse.txt");
        assertThat(matched.path("size").asLong()).isEqualTo(content.length);
        assertThat(matched.path("modifiedAt").asText()).endsWith("Z");
    }

    @Test
    void rejectsTraversalAbsoluteAndInternalPaths() throws Exception {
        for (String invalidPath : new String[]{"../../etc", "/etc", "docs/../../x", "docs/..", ".upload-meta"}) {
            mockMvc.perform(dirRequest(invalidPath))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
    }

    @Test
    void missingOrNonDirectoryReturns404() throws Exception {
        Files.writeString(storagePaths.root().resolve("plain-file.txt"), "not a directory");
        mockMvc.perform(dirRequest("plain-file.txt"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
        mockMvc.perform(dirRequest("no-such-dir-" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
        // 内部元数据目录不可进入
        mockMvc.perform(dirRequest(".upload-meta"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void skipsSymlinksAndRejectsDirectSymlinkAccess() throws Exception {
        Path outside = Files.createDirectories(storagePaths.root().getParent()
                .resolve("outside-" + UUID.randomUUID()));
        Files.writeString(outside.resolve("secret.txt"), "secret-outside");
        Path link = storagePaths.root().resolve("escape-link");
        Files.createSymbolicLink(link, outside);
        try {
            ResultActions rootResult = mockMvc.perform(dirRequest(null))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
            List<String> rootNames = new ArrayList<>();
            for (JsonNode entry : body(rootResult).path("data").path("entries")) {
                rootNames.add(entry.path("name").asText());
            }
            assertThat(rootNames).doesNotContain("escape-link", "secret.txt");

            mockMvc.perform(dirRequest("escape-link"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        } finally {
            Files.deleteIfExists(link);
            deleteRecursively(outside);
        }
    }

    @Test
    void sortsDirectoriesFirstThenFilesIgnoringCaseAndListsHiddenFiles() throws Exception {
        Path sortDir = Files.createDirectories(storagePaths.root().resolve("browse-sort"));
        Files.createDirectories(sortDir.resolve("bDir"));
        Files.createDirectories(sortDir.resolve("adir"));
        Files.writeString(sortDir.resolve("B.txt"), "b");
        Files.writeString(sortDir.resolve("a.txt"), "a");
        Files.writeString(sortDir.resolve(".hidden"), "hidden");

        ResultActions result = mockMvc.perform(dirRequest("browse-sort"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.path").value("browse-sort"))
                .andExpect(jsonPath("$.data.parent").value(""));
        List<String> names = new ArrayList<>();
        for (JsonNode entry : body(result).path("data").path("entries")) {
            names.add(entry.path("name").asText());
        }
        assertThat(names).containsExactly("adir", "bDir", ".hidden", "a.txt", "B.txt");
    }

    @Test
    void truncatesAtTwoThousandEntries() throws Exception {
        Path bigDir = Files.createDirectories(storagePaths.root().resolve("browse-big"));
        for (int index = 0; index < 2005; index++) {
            Files.createFile(bigDir.resolve(String.format("f-%04d.txt", index)));
        }

        ResultActions result = mockMvc.perform(dirRequest("browse-big"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.truncated").value(true));
        JsonNode entries = body(result).path("data").path("entries");
        assertThat(entries.size()).isEqualTo(2000);
        assertThat(entries.get(0).path("name").asText()).isEqualTo("f-0000.txt");
        assertThat(entries.get(1999).path("name").asText()).isEqualTo("f-1999.txt");
        assertThat(entries.get(0).path("type").asText()).isEqualTo("file");
    }

    @Test
    void unsignedRequestRejectedWith40100() throws Exception {
        mockMvc.perform(get(DIR_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    private MockHttpServletRequestBuilder dirRequest(String path) {
        if (path == null) {
            return TestSigner.signedGet(DIR_PATH, null);
        }
        String rawQuery = "path=" + URLEncoder.encode(path, StandardCharsets.UTF_8);
        return TestSigner.signedGet(get(DIR_PATH).queryParam("path", path), DIR_PATH, rawQuery);
    }
}
