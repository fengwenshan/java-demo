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

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 契约 3.4 ~ 3.7：上传 - 列表 - 下载 - 删除全链路及上传边界校验。
 */
@SpringBootTest(properties = "app.test.suite=file-flow")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FileFlowIntegrationTest extends IntegrationTestSupport {

    private static final String UPLOAD_PATH = "/api/files/upload";

    @Test
    void uploadListDownloadDeleteFullFlowWithChineseName() throws Exception {
        byte[] content = "hello 安全上传".getBytes(StandardCharsets.UTF_8);
        String contentSha = TestSigner.sha256(content);

        MockMultipartHttpServletRequestBuilder uploadBuilder = multipart(UPLOAD_PATH)
                .file(new MockMultipartFile("file", "original.pdf", "application/pdf", content));
        uploadBuilder.param("name", "设计稿.pdf");
        uploadBuilder.param("description", "季度方案");

        ResultActions uploadResult = mockMvc.perform(TestSigner.signedMultipart(uploadBuilder, UPLOAD_PATH, content, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("设计稿.pdf"))
                .andExpect(jsonPath("$.data.description").value("季度方案"))
                .andExpect(jsonPath("$.data.size").value(content.length))
                .andExpect(jsonPath("$.data.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.data.sha256").value(contentSha))
                .andExpect(jsonPath("$.data.createdAt", containsString("T")));
        JsonNode uploaded = body(uploadResult).path("data");
        String id = uploaded.path("id").asText();
        assertThat(id).isNotBlank();
        assertThat(uploaded.path("createdAt").asText()).endsWith("Z");

        // 列表：中文 keyword + 分页
        String rawQuery = "keyword=%E8%AE%BE%E8%AE%A1&page=1&size=20";
        MockHttpServletRequestBuilder listBuilder = get("/api/files")
                .queryParam("page", "1")
                .queryParam("size", "20")
                .queryParam("keyword", "设计");
        mockMvc.perform(TestSigner.signedGet(listBuilder, "/api/files", rawQuery))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.items[0].id").value(id))
                .andExpect(jsonPath("$.data.items[0].name").value("设计稿.pdf"))
                .andExpect(jsonPath("$.data.items[0].sha256").value(contentSha));

        // 下载：二进制流 + 契约要求的响应头
        String downloadPath = "/api/files/" + id + "/download";
        ResultActions downloadResult = mockMvc.perform(TestSigner.signedGet(downloadPath, null))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Sha256", contentSha))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("application/pdf")))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, String.valueOf(content.length)))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("filename*=UTF-8''%E8%AE%BE%E8%AE%A1%E7%A8%BF.pdf")));
        assertThat(downloadResult.andReturn().getResponse().getContentAsByteArray()).isEqualTo(content);

        // 删除
        mockMvc.perform(TestSigner.signedDelete("/api/files/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(id));

        // 删除后下载 40400、列表为空
        mockMvc.perform(TestSigner.signedGet(downloadPath, null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400))
                .andExpect(jsonPath("$.message").value("资源不存在"));
        MockHttpServletRequestBuilder listAfterDelete = get("/api/files")
                .queryParam("page", "1")
                .queryParam("size", "20")
                .queryParam("keyword", "设计");
        mockMvc.perform(TestSigner.signedGet(listAfterDelete, "/api/files", rawQuery))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void fileHashMismatchRejectedAndStoredFileRemoved() throws Exception {
        byte[] actualContent = "real content".getBytes(StandardCharsets.UTF_8);
        String declaredSha = TestSigner.sha256("declared but different content");

        MockMultipartHttpServletRequestBuilder builder = multipart(UPLOAD_PATH)
                .file(new MockMultipartFile("file", "a.pdf", "application/pdf", actualContent));
        mockMvc.perform(TestSigner.signedMultipartWithDeclaredSha(builder, UPLOAD_PATH, declaredSha, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("文件内容校验失败"));

        assertThat(countStoredFiles()).isZero();
    }

    @Test
    void arbitraryFileTypesAreAccepted() throws Exception {
        // 已取消扩展名白名单：可执行文件、脚本、无扩展名文件都允许上传。
        for (String fileName : new String[]{"bad.exe", "script.sh", "no-extension"}) {
            byte[] content = ("content-" + fileName).getBytes(StandardCharsets.UTF_8);
            String contentType = fileName.endsWith(".exe") ? "application/octet-stream" : "text/plain";
            MockMultipartHttpServletRequestBuilder builder = multipart(UPLOAD_PATH)
                    .file(new MockMultipartFile("file", fileName, contentType, content));
            mockMvc.perform(TestSigner.signedMultipart(builder, UPLOAD_PATH, content, null))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.name").value(fileName));
        }
        assertThat(countStoredFiles()).isEqualTo(3);
    }

    @Test
    void emptyFileRejectedWith40000() throws Exception {
        byte[] content = new byte[0];
        MockMultipartHttpServletRequestBuilder builder = multipart(UPLOAD_PATH)
                .file(new MockMultipartFile("file", "empty.pdf", "application/pdf", content));
        mockMvc.perform(TestSigner.signedMultipart(builder, UPLOAD_PATH, content, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void oversizedFileRejectedWith40003() throws Exception {
        byte[] content = new byte[2 * 1024 * 1024];
        MockMultipartHttpServletRequestBuilder builder = multipart(UPLOAD_PATH)
                .file(new MockMultipartFile("file", "big.pdf", "application/pdf", content));
        mockMvc.perform(TestSigner.signedMultipart(builder, UPLOAD_PATH, content, null))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value(40003))
                .andExpect(jsonPath("$.message").value("文件超过大小限制"));
        assertThat(countStoredFiles()).isZero();
    }

    @Test
    void missingFilePartRejectedWith40000() throws Exception {
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        MockMultipartHttpServletRequestBuilder builder = multipart(UPLOAD_PATH)
                .file(new MockMultipartFile("other", "a.pdf", "application/pdf", content));
        mockMvc.perform(TestSigner.signedMultipart(builder, UPLOAD_PATH, content, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void tooLongDescriptionRejectedWith40000() throws Exception {
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        MockMultipartHttpServletRequestBuilder builder = multipart(UPLOAD_PATH)
                .file(new MockMultipartFile("file", "a.pdf", "application/pdf", content));
        builder.param("description", "说".repeat(201));
        mockMvc.perform(TestSigner.signedMultipart(builder, UPLOAD_PATH, content, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void fileNameIsSanitizedBeforeStoring() throws Exception {
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        MockMultipartHttpServletRequestBuilder builder = multipart(UPLOAD_PATH)
                .file(new MockMultipartFile("file", "origin.pdf", "application/pdf", content));
        builder.param("name", "../../etc/evil.txt");
        ResultActions result = mockMvc.perform(TestSigner.signedMultipart(builder, UPLOAD_PATH, content, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("evil.txt"));
        String id = body(result).path("data").path("id").asText();
        mockMvc.perform(TestSigner.signedDelete("/api/files/" + id)).andExpect(status().isOk());
    }

    @Test
    void listPaginationValidation() throws Exception {
        mockMvc.perform(TestSigner.signedGet(get("/api/files").queryParam("page", "0"), "/api/files", "page=0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(TestSigner.signedGet(get("/api/files").queryParam("size", "101"), "/api/files", "size=101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(TestSigner.signedGet(get("/api/files").queryParam("size", "0"), "/api/files", "size=0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void filesAreScopedToAppKey() throws Exception {
        byte[] content = "scoped".getBytes(StandardCharsets.UTF_8);
        MockMultipartHttpServletRequestBuilder builder = multipart(UPLOAD_PATH)
                .file(new MockMultipartFile("file", "scope.pdf", "application/pdf", content));
        ResultActions result = mockMvc.perform(TestSigner.signedMultipart(builder, UPLOAD_PATH, content, null))
                .andExpect(status().isOk());
        String id = body(result).path("data").path("id").asText();

        // 第二个 appKey 看不到、也下载/删除不了 demo-app 的文件
        MockHttpServletRequestBuilder secondList = get("/api/files");
        mockMvc.perform(TestSigner.withSignature(secondList, "GET", "/api/files", null,
                        SignatureService.emptyPayloadHash(), TestSigner.SECOND_APP_KEY,
                        TestSigner.SECOND_APP_SECRET, TestSigner.now(), TestSigner.newNonce(), null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        MockHttpServletRequestBuilder secondDownload = get("/api/files/" + id + "/download");
        mockMvc.perform(TestSigner.withSignature(secondDownload, "GET", "/api/files/" + id + "/download", null,
                        SignatureService.emptyPayloadHash(), TestSigner.SECOND_APP_KEY,
                        TestSigner.SECOND_APP_SECRET, TestSigner.now(), TestSigner.newNonce(), null, null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));

        // 原 appKey 正常删除
        mockMvc.perform(TestSigner.signedDelete("/api/files/" + id)).andExpect(status().isOk());
    }

    @Test
    void notFoundForUnknownId() throws Exception {
        String randomId = UUID.randomUUID().toString();
        mockMvc.perform(TestSigner.signedGet("/api/files/" + randomId + "/download", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
        mockMvc.perform(TestSigner.signedDelete("/api/files/" + randomId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
        mockMvc.perform(TestSigner.signedGet("/api/files", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.total").isNumber());
    }
}
