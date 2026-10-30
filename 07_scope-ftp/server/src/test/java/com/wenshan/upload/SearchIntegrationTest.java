package com.wenshan.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenshan.upload.support.IntegrationTestSupport;
import com.wenshan.upload.support.TestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 根目录递归搜索接口（契约 3.16）：嵌套/忽略大小写、跳过 .upload-meta 与 symlink、limit/truncated、非法参数。
 */
@SpringBootTest(properties = "app.test.suite=search")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SearchIntegrationTest extends IntegrationTestSupport {

    private static final String SEARCH_PATH = "/api/files/search";

    @Test
    void findsNestedEntriesCaseInsensitivelyAndSortsDirectoriesFirst() throws Exception {
        Path nested = Files.createDirectories(storagePaths.root().resolve("docs/2026/十月"));
        byte[] pdf = "pdf-content".getBytes(StandardCharsets.UTF_8);
        Files.write(nested.resolve("报告.pdf"), pdf);
        Files.writeString(storagePaths.root().resolve("docs/2026/REPORT-final.txt"), "txt");
        Files.createDirectories(storagePaths.root().resolve("报告归档"));
        Files.writeString(storagePaths.root().resolve("docs/notes.txt"), "notes");
        Files.writeString(storagePaths.root().resolve("报告-根级.txt"), "root");

        ResultActions result = mockMvc.perform(searchRequest("报告", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.keyword").value("报告"))
                .andExpect(jsonPath("$.data.truncated").value(false))
                .andExpect(jsonPath("$.data.entries", hasSize(3)));

        JsonNode entries = body(result).path("data").path("entries");
        // 目录在前，文件在后；目录内按名称忽略大小写升序
        assertThat(entries.get(0).path("name").asText()).isEqualTo("报告归档");
        assertThat(entries.get(0).path("type").asText()).isEqualTo("directory");
        assertThat(entries.get(0).path("path").asText()).isEqualTo("报告归档");
        assertThat(entries.get(0).path("parent").asText()).isEmpty();
        assertThat(entries.get(0).path("size").isNull()).isTrue();
        assertThat(entries.get(0).path("modifiedAt").asText()).endsWith("Z");
        // 文件名升序：报告-根级.txt < 报告.pdf（在同一层级下按名称排序，与所在目录无关）
        List<String> fileNames = new ArrayList<>();
        for (int index = 1; index < entries.size(); index++) {
            JsonNode entry = entries.get(index);
            assertThat(entry.path("type").asText()).isEqualTo("file");
            fileNames.add(entry.path("name").asText());
        }
        assertThat(fileNames).contains("报告.pdf", "报告-根级.txt");
        // 嵌套文件的绝对相对路径与父目录正确
        JsonNode nestedEntry = null;
        for (JsonNode entry : entries) {
            if ("报告.pdf".equals(entry.path("name").asText())) {
                nestedEntry = entry;
            }
        }
        assertThat(nestedEntry).isNotNull();
        assertThat(nestedEntry.path("path").asText()).isEqualTo("docs/2026/十月/报告.pdf");
        assertThat(nestedEntry.path("parent").asText()).isEqualTo("docs/2026/十月");
        assertThat(nestedEntry.path("size").asLong()).isEqualTo(pdf.length);

        // 忽略大小写：report → REPORT-final.txt
        ResultActions caseInsensitive = mockMvc.perform(searchRequest("report", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries", hasSize(1)));
        JsonNode matched = body(caseInsensitive).path("data").path("entries").get(0);
        assertThat(matched.path("name").asText()).isEqualTo("REPORT-final.txt");
        assertThat(matched.path("path").asText()).isEqualTo("docs/2026/REPORT-final.txt");
        assertThat(matched.path("parent").asText()).isEqualTo("docs/2026");

        // keyword 首尾空白会被 trim（响应里回显 trim 后的关键字）
        mockMvc.perform(searchRequest("  notes  ", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.keyword").value("notes"))
                .andExpect(jsonPath("$.data.entries", hasSize(1)));
    }

    @Test
    void hiddenInternalMetaDirectoryNeverAppears() throws Exception {
        Files.writeString(storagePaths.root().resolve("user.txt"), "user");
        // .upload-meta 内的 files.json 包含 "files"、"json" 等关键字，但不应被搜索到
        ResultActions result = mockMvc.perform(searchRequest("files.json", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries", hasSize(0)));
        for (JsonNode entry : body(result).path("data").path("entries")) {
            assertThat(entry.path("path").asText()).doesNotStartWith(".upload-meta");
        }
        mockMvc.perform(searchRequest("upload-meta", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries", hasSize(0)));
    }

    @Test
    void skipsSymlinksPointingOutsideRoot() throws Exception {
        Path outside = Files.createDirectories(storagePaths.root().getParent()
                .resolve("search-outside-" + UUID.randomUUID()));
        Files.writeString(outside.resolve("secret-report.txt"), "secret-outside");
        Path link = storagePaths.root().resolve("escape-link");
        Files.createSymbolicLink(link, outside);
        try {
            mockMvc.perform(searchRequest("secret-report", null))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries", hasSize(0)));
            mockMvc.perform(searchRequest("escape-link", null))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries", hasSize(0)));
        } finally {
            Files.deleteIfExists(link);
            deleteRecursively(outside);
        }
    }

    @Test
    void limitTruncatesResults() throws Exception {
        Path dir = Files.createDirectories(storagePaths.root().resolve("many"));
        for (int index = 1; index <= 5; index++) {
            Files.writeString(dir.resolve("match-" + index + ".txt"), "x");
        }
        ResultActions limited = mockMvc.perform(searchRequest("match", 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.truncated").value(true))
                .andExpect(jsonPath("$.data.entries", hasSize(2)));
        assertThat(body(limited).path("data").path("entries").get(0).path("name").asText())
                .isEqualTo("match-1.txt");

        ResultActions all = mockMvc.perform(searchRequest("match", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.truncated").value(false))
                .andExpect(jsonPath("$.data.entries", hasSize(5)));
        assertThat(body(all).path("data").path("entries").get(4).path("name").asText())
                .isEqualTo("match-5.txt");
        assertThat(body(all).path("data").path("entries").get(0).path("modifiedAt").asText())
                .endsWith("Z");
    }

    @Test
    void invalidKeywordAndLimitRejectedWith40000() throws Exception {
        mockMvc.perform(TestSigner.signedGet(SEARCH_PATH, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(searchRequest("", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(searchRequest("   ", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(searchRequest("k".repeat(101), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(searchRequest("abc", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(searchRequest("abc", 501))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void unsignedSearchRejectedWith40100() throws Exception {
        mockMvc.perform(get(SEARCH_PATH).queryParam("keyword", "abc"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    // ------------------------------------------------------------------

    private MockHttpServletRequestBuilder searchRequest(String keyword, Integer limit) {
        MockHttpServletRequestBuilder builder = get(SEARCH_PATH);
        StringBuilder rawQuery = new StringBuilder();
        if (keyword != null) {
            builder.queryParam("keyword", keyword);
            rawQuery.append("keyword=").append(URLEncoder.encode(keyword, StandardCharsets.UTF_8));
        }
        if (limit != null) {
            builder.queryParam("limit", String.valueOf(limit));
            if (rawQuery.length() > 0) {
                rawQuery.append('&');
            }
            rawQuery.append("limit=").append(limit);
        }
        return TestSigner.signedGet(builder, SEARCH_PATH, rawQuery.length() == 0 ? null : rawQuery.toString());
    }
}
